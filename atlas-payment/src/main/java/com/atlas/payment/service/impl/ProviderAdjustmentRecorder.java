package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PaymentProviderAdjustmentResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.model.ProviderAdjustmentKind;
import com.atlas.payment.model.ProviderAdjustmentStatus;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.projection.ProviderAdjustment;
import com.atlas.payment.persistence.projection.RefundedTotals;
import com.atlas.payment.provider.paypal.PayPalRefundStatus;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentProviderAdjustmentRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.util.IdGenerator;

/**
 * PayPal 쪽에서 시작돼 우리 요청과 짝이 없는 사건을 결제 시도에 묶어 기록한다(ADR-0111). 웹훅 트랜잭션 안에서만 부른다.
 *
 * <p>환불·역전은 금액을 원화로 정해 {@code POSTING}으로 두고, 호출자(주문 계층)가 같은 트랜잭션에서 판매자 원장을
 * 차감한다. 거절·분쟁은 돈을 움직이지 않고 {@code REVIEW}로 둔다. 분쟁 결과가 돈으로 이어지면 PayPal 이
 * capture 역전이나 환불 사건을 따로 보내고, 그 사건이 원장을 움직인다.
 */
final class ProviderAdjustmentRecorder {
    private static final PaymentWebhookResponse RETRY =
            new PaymentWebhookResponse(false, false, null, null, null, false, true);
    private static final PaymentWebhookResponse DUPLICATE =
            new PaymentWebhookResponse(true, true, null, null, null, false);
    private static final PaymentWebhookResponse ACKNOWLEDGED =
            new PaymentWebhookResponse(false, true, null, null, null, false);

    private final PaymentAttemptRepository attempts;
    private final PaymentRefundRepository refunds;
    private final PaymentProviderAdjustmentRepository adjustments;
    private final PaymentWebhookEventRepository webhookEvents;
    private final IdGenerator ids;

    ProviderAdjustmentRecorder(PaymentAttemptRepository attempts, PaymentRefundRepository refunds,
            PaymentProviderAdjustmentRepository adjustments, PaymentWebhookEventRepository webhookEvents,
            IdGenerator ids) {
        this.attempts = Objects.requireNonNull(attempts);
        this.refunds = Objects.requireNonNull(refunds);
        this.adjustments = Objects.requireNonNull(adjustments);
        this.webhookEvents = Objects.requireNonNull(webhookEvents);
        this.ids = Objects.requireNonNull(ids);
    }

    /** 환불 사건 외에 이 클래스가 맡는 사건 종류. 해당 없으면 null. */
    static ProviderAdjustmentKind kindOf(String eventType) {
        return switch (eventType) {
            case "PAYMENT.CAPTURE.REVERSED" -> ProviderAdjustmentKind.REVERSAL;
            case "PAYMENT.CAPTURE.DENIED" -> ProviderAdjustmentKind.DENIAL;
            case "CUSTOMER.DISPUTE.CREATED", "CUSTOMER.DISPUTE.UPDATED", "CUSTOMER.DISPUTE.RESOLVED" ->
                    ProviderAdjustmentKind.DISPUTE;
            default -> null;
        };
    }

    /**
     * 우리 refund operation 과 provider refund id 로 짝지어지지 않은 환불({@code REFUND}) 또는 역전({@code REVERSAL}).
     * capture 를 우리 결제 시도로 찾지 못하면 이전처럼 재시도로 돌려 운영자 dead letter 로 보낸다.
     */
    PaymentWebhookResponse recordMoney(PaymentWebhookEventResponse event, ProviderAdjustmentKind kind, Instant now) {
        if (event.resourceId() == null || event.relatedCaptureId() == null) return RETRY;
        Optional<PaymentAttempt> mapped = attempts.findWithLockByProviderCaptureId(event.relatedCaptureId());
        if (mapped.isEmpty()) return RETRY;
        PaymentAttempt attempt = mapped.get();
        // 우리 환불은 operation 을 commit 한 뒤 PayPal 을 부르므로, 응답을 잃어 id 를 못 저장한 환불이 있으면 이 사건이
        // 그 환불일 수 있다. 그 사이 id 가 저장됐으면 재시도가 기존 짝짓기 경로를 탄다. 역전은 PayPal 이 시작하므로 해당 없다.
        if (kind == ProviderAdjustmentKind.REFUND
                && (refunds.hasProviderRefundInFlight(attempt.getAttemptId())
                || refunds.findByProviderRefundIdForUpdate(event.resourceId()).isPresent())) {
            return RETRY;
        }
        if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
            return DUPLICATE;
        }
        if (kind == ProviderAdjustmentKind.REFUND
                && PayPalRefundStatus.fromIgnoringCase(event.resourceStatus()) != PayPalRefundStatus.COMPLETED) {
            // 아직 돈이 움직이지 않은 외부 환불(PENDING/FAILED). 완료되면 PAYMENT.CAPTURE.REFUNDED 가 따로 온다.
            webhookEvents.markProcessed(event.eventId(), now);
            return ACKNOWLEDGED;
        }
        Optional<ProviderAdjustment> existing = adjustments.findMoneyByResourceIdForUpdate(event.resourceId());
        if (existing.isEmpty()) {
            Long providerMinor = minorUnits(event.resourceAmount());
            Pricing pricing = price(attempt, kind, event, providerMinor);
            UUID adjustmentId = ids.generateId();
            boolean inserted = adjustments.insertMoney(adjustmentId, kind, event.resourceId(), attempt.getAttemptId(),
                    event.relatedCaptureId(), providerMinor, event.resourceCurrency(), pricing.amountWon(),
                    event.resourceStatus(), pricing.status(), pricing.reviewReason(), event.eventId(), now);
            existing = inserted ? adjustments.findById(adjustmentId)
                    : adjustments.findMoneyByResourceIdForUpdate(event.resourceId());
        }
        UUID adjustmentId = existing.orElseThrow(() -> new IllegalStateException("Provider adjustment disappeared"))
                .adjustmentId();
        webhookEvents.markProcessed(event.eventId(), now);
        return PaymentWebhookResponse.providerAdjustment(adjustmentId);
    }

    /** 거절·분쟁. 돈을 움직이지 않고 검토로 둔다. 우리 capture 가 아니어도 기록해 운영자가 본다. */
    PaymentWebhookResponse recordReview(PaymentWebhookEventResponse event, ProviderAdjustmentKind kind, Instant now) {
        if (event.resourceId() == null) return RETRY;
        if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
            return DUPLICATE;
        }
        // 거절 사건의 자원은 capture 자체고, 분쟁은 disputed_transactions 의 판매자 거래 id 로 capture 를 가리킨다.
        String captureId = kind == ProviderAdjustmentKind.DENIAL ? event.resourceId() : event.relatedCaptureId();
        UUID attemptId = captureId == null ? null
                : attempts.findByProviderCaptureId(captureId).map(PaymentAttempt::getAttemptId).orElse(null);
        adjustments.upsertReview(kind, event.resourceId(), attemptId, captureId,
                minorUnits(event.resourceAmount()), event.resourceCurrency(), event.resourceStatus(),
                kind.name() + "_NEEDS_OPERATOR", event.eventId(), now);
        webhookEvents.markProcessed(event.eventId(), now);
        return ACKNOWLEDGED;
    }

    PaymentProviderAdjustmentResponse find(UUID adjustmentId) {
        ProviderAdjustment adjustment = adjustments.findById(adjustmentId)
                .orElseThrow(() -> new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND,
                        "Provider adjustment not found"));
        PaymentAttempt attempt = adjustment.attemptId() == null ? null
                : attempts.findById(adjustment.attemptId()).orElse(null);
        return new PaymentProviderAdjustmentResponse(adjustment.adjustmentId(), adjustment.kind(),
                adjustment.status(), attempt == null ? null : attempt.getOrderId(),
                attempt == null ? null : attempt.getCheckoutId(), adjustment.amountWon());
    }

    void markPosted(UUID adjustmentId, Instant now) {
        adjustments.markPosted(adjustmentId, now);
    }

    void markForReview(UUID adjustmentId, String reason, Instant now) {
        adjustments.markReview(adjustmentId, Objects.requireNonNull(reason), now);
    }

    /**
     * provider 금액을 원화로 바꾼다. capture 의 남은 금액 전부면 남은 원화 전부, 일부면 capture 비율로 내림한다
     * (판매자 차감이 실제보다 커지지 않는다). 남은 금액을 넘거나 사실이 어긋나면 돈을 움직이지 않고 검토로 보낸다.
     */
    private Pricing price(PaymentAttempt attempt, ProviderAdjustmentKind kind, PaymentWebhookEventResponse event,
            Long providerMinor) {
        if (attempt.getState() != PaymentState.CAPTURED || !attempt.hasFxSnapshot() || attempt.getTotalWon() == null
                || attempt.getProviderAmountMinor() == null || attempt.getProviderAmountMinor() <= 0) {
            return Pricing.review("ATTEMPT_NOT_CAPTURED");
        }
        if (kind == ProviderAdjustmentKind.REVERSAL
                && PayPalRefundStatus.fromIgnoringCase(event.resourceStatus()) != PayPalRefundStatus.COMPLETED) {
            return Pricing.review("REVERSAL_NOT_COMPLETED");
        }
        if (providerMinor == null || providerMinor <= 0
                || !attempt.getProviderCurrency().equalsIgnoreCase(event.resourceCurrency())) {
            return Pricing.review("AMOUNT_OR_CURRENCY_MISMATCH");
        }
        RefundedTotals refunded = refunds.refundedTotalsForAttempt(attempt.getAttemptId());
        long remainingMinor = attempt.getProviderAmountMinor() - refunded.providerAmountMinor();
        long remainingWon = attempt.getTotalWon() - refunded.amountWon();
        if (providerMinor > remainingMinor || remainingWon <= 0) {
            return Pricing.review("EXCEEDS_REMAINING_CAPTURE");
        }
        long amountWon = providerMinor == remainingMinor ? remainingWon
                : Math.min(remainingWon, BigDecimal.valueOf(providerMinor)
                        .multiply(BigDecimal.valueOf(attempt.getTotalWon()))
                        .divide(BigDecimal.valueOf(attempt.getProviderAmountMinor()), 0, RoundingMode.DOWN)
                        .longValueExact());
        if (amountWon <= 0) return Pricing.review("BELOW_ONE_WON");
        return new Pricing(ProviderAdjustmentStatus.POSTING, amountWon, null);
    }

    /** 소수 둘째 자리까지인 통화만 받는다(PayPal 결제 시도는 USD). 그 밖은 null. */
    private static Long minorUnits(BigDecimal amount) {
        if (amount == null) return null;
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException notTwoDecimals) {
            return null;
        }
    }

    private record Pricing(ProviderAdjustmentStatus status, Long amountWon, String reviewReason) {
        static Pricing review(String reason) {
            return new Pricing(ProviderAdjustmentStatus.REVIEW, null, reason);
        }
    }
}
