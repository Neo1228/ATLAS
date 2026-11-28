package com.atlas.payment.service.impl;

import static com.atlas.payment.service.impl.PaymentPreconditions.invalidRequest;

import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import com.atlas.payment.dto.response.PaymentDisputeHoldResponse;
import com.atlas.payment.dto.response.PaymentProviderAdjustmentResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.model.PaymentPhase;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.provider.paypal.PayPalCaptureStatus;
import com.atlas.payment.provider.paypal.PayPalRefundStatus;
import com.atlas.payment.model.ProviderAdjustmentKind;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentProviderAdjustmentRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.IdGenerator;

/**
 * PayPal webhook 검증과 처리. webhook은 provider가 보내는 사실 통지이므로, 저장된 결제·환불의 금액·통화·ID가
 * 일치할 때만 내부 상태에 반영하고 어긋나면 반영하지 않고 거절 기록만 남긴다.
 */
@Component
public final class PaymentWebhookProcessor {
    private final OwnerLockRegistry ownerLocks;
    private final PaymentAttemptRepository repository;
    private final PaymentRefundRepository refunds;
    private final PaymentWebhookEventRepository webhookEvents;
    private final PaymentRecoveryRepository recovery;
    private final TransactionTemplate transactions;
    private final PaymentProvider psp;
    private final Clock clock;
    private final ProviderAdjustmentRecorder adjustments;

    public PaymentWebhookProcessor(OwnerLockRegistry ownerLocks, PaymentAttemptRepository repository,
            PaymentRefundRepository refunds, PaymentWebhookEventRepository webhookEvents,
            PaymentRecoveryRepository recovery, PaymentProviderAdjustmentRepository providerAdjustments,
            TransactionTemplate transactions, PaymentProvider psp, IdGenerator ids, Clock clock) {
        this.ownerLocks = Objects.requireNonNull(ownerLocks);
        this.repository = Objects.requireNonNull(repository);
        this.refunds = Objects.requireNonNull(refunds);
        this.webhookEvents = Objects.requireNonNull(webhookEvents);
        this.recovery = Objects.requireNonNull(recovery);
        this.transactions = Objects.requireNonNull(transactions);
        this.psp = Objects.requireNonNull(psp);
        this.clock = Objects.requireNonNull(clock);
        this.adjustments = new ProviderAdjustmentRecorder(repository, refunds, providerAdjustments, webhookEvents, ids);
    }

    PaymentWebhookEventResponse verifyPayPalWebhook(PaymentWebhookSignatureRequest signature, String rawBody) {
        if (signature == null || rawBody == null || rawBody.isBlank()) {
            throw invalidRequest("PayPal webhook signature and body are required");
        }
        return psp.verifyWebhook(signature, rawBody);
    }

    <T> T withPayPalWebhookOwnerLock(PaymentWebhookEventResponse event, Supplier<T> action) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(action);
        if (!"PAYMENT.CAPTURE.COMPLETED".equals(event.eventType())) {
            return action.get();
        }
        Optional<PaymentAttempt> mappedAttempt = event.providerOrderId() == null
                ? Optional.empty() : repository.findByProviderOrderId(event.providerOrderId());
        if (mappedAttempt.isEmpty() && event.resourceId() != null) {
            mappedAttempt = repository.findByProviderCaptureId(event.resourceId());
        }
        if (mappedAttempt.isEmpty()) {
            return action.get();
        }
        PaymentAttempt attempt = mappedAttempt.get();
        UUID ownerId = attempt.getOrderId() == null ? attempt.getCheckoutId() : attempt.getOrderId();
        return withOwnerLock(ownerId, action);
    }

    PaymentWebhookResponse processVerifiedPayPalWebhook(PaymentWebhookEventResponse event) {
        if (event == null) throw invalidRequest("Verified PayPal webhook event is required");
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            if ("PAYMENT.CAPTURE.COMPLETED".equals(event.eventType())) {
                Optional<PaymentAttempt> attempt = event.providerOrderId() == null
                        ? Optional.empty() : repository.findWithLockByProviderOrderId(event.providerOrderId());
                if (attempt.isEmpty() && event.resourceId() != null) {
                    attempt = repository.findWithLockByProviderCaptureId(event.resourceId());
                }
                if (attempt.isEmpty()) {
                    return new PaymentWebhookResponse(false, false, null, null, null, false, true);
                }
                PaymentAttempt current = attempt.get();
                if (PayPalCaptureStatus.fromIgnoringCase(event.resourceStatus()) != PayPalCaptureStatus.COMPLETED
                        || event.resourceAmount() == null || current.getTotalWon() == null
                        || !current.hasFxSnapshot()
                        || event.resourceAmount().compareTo(
                                BigDecimal.valueOf(current.getProviderAmountMinor(), 2)) != 0
                        || !current.getProviderCurrency().equalsIgnoreCase(event.resourceCurrency())
                        || event.resourceId() == null
                        || (event.providerOrderId() != null
                        && !event.providerOrderId().equals(current.getProviderOrderId()))
                        || (current.getProviderCaptureId() != null
                        && !event.resourceId().equals(current.getProviderCaptureId()))) {
                    return rejectedWebhook(event, now);
                }
                if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
                    // 캡처 이벤트가 이미 commit된 경우에도 이전 주문 조정 실패 후 재시도 필요
                    return new PaymentWebhookResponse(true, true, null, null, current.getAttemptId(), true);
                }
                current.setProviderCaptureId(event.resourceId());
                current.setState(PaymentState.CAPTURED);
                current.setPaymentPhase(PaymentPhase.CAPTURE);
                current.setAuthorizationActive(false);
                repository.save(current);
                recovery.markAttemptForApplication(current.getAttemptId());
                webhookEvents.markProcessed(event.eventId(), now);
                return new PaymentWebhookResponse(false, true, null, null, current.getAttemptId(), true);
            }

            PaymentRefundState refundState = refundWebhookState(event.eventType());
            if (refundState != null) {
                if (event.resourceId() == null) {
                    return new PaymentWebhookResponse(false, false, null, null, null, false, true);
                }
                var operation = refunds.findByProviderRefundIdForUpdate(event.resourceId());
                if (operation.isEmpty()) {
                    // 우리 operation 과 짝이 없는 환불: PayPal 대시보드 등에서 낸 환불이거나, 우리 환불의 id 를 아직
                    // 저장하지 못한 경우다. 둘을 가르고 앞의 것은 판매자 원장 차감 대상으로 기록한다(ADR-0111).
                    return adjustments.recordMoney(event, ProviderAdjustmentKind.REFUND, now);
                }
                PaymentRefundOperation current = operation.get();
                PayPalRefundStatus expectedStatus = switch (refundState) {
                    case COMPLETED -> PayPalRefundStatus.COMPLETED;
                    case PENDING, UNKNOWN -> PayPalRefundStatus.PENDING;
                    case FAILED -> PayPalRefundStatus.FAILED;
                };
                if (event.resourceAmount() == null || current.providerAmountMinor() == null
                        || event.resourceAmount().compareTo(
                                BigDecimal.valueOf(current.providerAmountMinor(), 2)) != 0
                        || current.providerCurrency() == null
                        || !current.providerCurrency().equalsIgnoreCase(event.resourceCurrency())
                        || PayPalRefundStatus.fromIgnoringCase(event.resourceStatus()) != expectedStatus
                        || (event.relatedCaptureId() != null
                        && !current.captureId().equals(event.relatedCaptureId()))) {
                    return rejectedWebhook(event, now);
                }
                if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
                    return new PaymentWebhookResponse(true, true, null, null, null, false);
                }
                refunds.updateResult(current.refundOperationId(), refundState, event.resourceId(),
                        event.resourceStatus(), now);
                if (refundState == PaymentRefundState.COMPLETED || refundState == PaymentRefundState.FAILED) {
                    recovery.markRefundForApplication(current.refundOperationId());
                }
                webhookEvents.markProcessed(event.eventId(), now);
                return new PaymentWebhookResponse(false, true, current.refundOperationId(),
                        refundState, null, false);
            }

            ProviderAdjustmentKind providerKind = ProviderAdjustmentRecorder.kindOf(event.eventType());
            if (providerKind != null) {
                if (providerKind.movesMoney()) return adjustments.recordMoney(event, providerKind, now);
                return providerKind == ProviderAdjustmentKind.DISPUTE
                        ? adjustments.recordDispute(event, now)
                        : adjustments.recordReview(event, providerKind, now);
            }

            if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
                return new PaymentWebhookResponse(true, true, null, null, null, false);
            }
            webhookEvents.markProcessed(event.eventId(), now);
            return new PaymentWebhookResponse(false, true, null, null, null, false);
        }));
    }

    PaymentProviderAdjustmentResponse findProviderAdjustment(UUID adjustmentId) {
        if (adjustmentId == null) throw invalidRequest("Provider adjustment id is required");
        return adjustments.find(adjustmentId);
    }

    List<PaymentDisputeHoldResponse> heldDisputes() {
        return adjustments.heldDisputes();
    }

    void markProviderAdjustmentPosted(UUID adjustmentId) {
        adjustments.markPosted(Objects.requireNonNull(adjustmentId), clock.instant());
    }

    void markProviderAdjustmentForReview(UUID adjustmentId, String reason) {
        adjustments.markForReview(Objects.requireNonNull(adjustmentId), reason, clock.instant());
    }

    private PaymentWebhookResponse rejectedWebhook(PaymentWebhookEventResponse event, Instant now) {
        if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
            return new PaymentWebhookResponse(true, true, null, null, null, false);
        }
        webhookEvents.markFailed(event.eventId(), now);
        return new PaymentWebhookResponse(false, false, null, null, null, false);
    }

    private static PaymentRefundState refundWebhookState(String eventType) {
        return switch (eventType) {
            case "PAYMENT.REFUND.COMPLETED", "PAYMENT.CAPTURE.REFUNDED" -> PaymentRefundState.COMPLETED;
            case "PAYMENT.REFUND.PENDING" -> PaymentRefundState.PENDING;
            case "PAYMENT.REFUND.FAILED" -> PaymentRefundState.FAILED;
            default -> null;
        };
    }

    private <T> T withOwnerLock(UUID ownerId, Supplier<T> action) {
        return ownerLocks.withLock(ownerId, action);
    }
}
