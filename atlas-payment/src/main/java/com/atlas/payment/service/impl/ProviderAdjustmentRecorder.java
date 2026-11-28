package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PaymentDisputeHoldResponse;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.IdGenerator;

/**
 * PayPal 쪽에서 시작돼 우리 요청과 짝이 없는 사건을 결제 시도에 묶어 기록한다(ADR-0111). 웹훅 트랜잭션 안에서만 부른다.
 *
 * <p>환불·역전은 금액을 원화로 정해 {@code POSTING}으로 두고, 호출자(주문 계층)가 같은 트랜잭션에서 판매자 원장을
 * 차감한다. 거절은 돈을 움직이지 않고 {@code REVIEW}로 둔다. 분쟁은 진행 중에 {@code HOLD}로 송금을 보류하고,
 * 판매자 패소로 끝나면 같은 환불 분개로 차감한다(ADR-0115).
 */
final class ProviderAdjustmentRecorder {
    private static final PaymentWebhookResponse RETRY =
            new PaymentWebhookResponse(false, false, null, null, null, false, true);
    private static final PaymentWebhookResponse DUPLICATE =
            new PaymentWebhookResponse(true, true, null, null, null, false);
    /** 판매자 패소. customer_disputes_v1 "The dispute was resolved in the customer's favor." */
    private static final String SELLER_LOSES = "RESOLVED_BUYER_FAVOUR";
    /**
     * 판매자 돈이 빠지지 않는 결과: 판매자 승소, PayPal 보호로 해결, 구매자 취소, 같은 거래의 새 분쟁으로 결정 없이 닫힘,
     * PayPal 이 분쟁을 기각(DENIED, 폐기 예정 값). 폐기 예정 값 ACCEPTED 는 누가 졌는지 문서가 말하지 않아 검토로 보낸다.
     */
    private static final Set<String> SELLER_KEEPS = Set.of(
            "RESOLVED_SELLER_FAVOUR", "RESOLVED_WITH_PAYOUT", "CANCELED_BY_BUYER", "NONE", "DENIED");
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

    /** capture 거절. 돈을 움직이지 않고 검토로 둔다. 우리 capture 가 아니어도 기록해 운영자가 본다. */
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

    /**
     * 분쟁(ADR-0115). PayPal 은 분쟁이 열리면 분쟁 금액을 묶고, 구매자 승소면 그 금액을 구매자에게 돌려주고 판매자 승소면
     * 묶음을 푼다(PayPal disputes overview). 그래서 진행 중에는 {@code HOLD}로 판매자 송금을 보류하고, 끝나면
     * 결과 코드가 {@code RESOLVED_BUYER_FAVOUR}일 때만 돌려준 금액을 기존 환불 분개로 차감한다. 그 밖의 결과는 차감 없이
     * {@code CLOSED}로 끝낸다. 끝난 분쟁은 늦게 온 사건이 되살리지 못한다.
     *
     * <p>재시도 대기열은 분쟁 결과 필드를 싣지 않으므로 이 경로는 자원 id 가 없을 때 말고는 재시도로 돌리지 않는다.
     */
    PaymentWebhookResponse recordDispute(PaymentWebhookEventResponse event, Instant now) {
        if (event.resourceId() == null) return RETRY;
        // 같은 capture 의 환불·역전 사건과 같은 순서(결제 시도 → adjustment)로 잠가 남은 금액 계산을 직렬화한다.
        Optional<PaymentAttempt> attempt = event.relatedCaptureId() == null ? Optional.empty()
                : attempts.findWithLockByProviderCaptureId(event.relatedCaptureId());
        if (!webhookEvents.insertReceived(event.eventId(), event.eventType(), event.resourceId(), now)) {
            return DUPLICATE;
        }
        Long disputedMinor = minorUnits(event.resourceAmount());
        DisputeState next = attempt.map(found -> isResolved(event) ? resolve(found, event) : hold(found, event))
                .orElse(DisputeState.review("DISPUTE_NEEDS_OPERATOR", disputedMinor, event.resourceCurrency()));
        Optional<ProviderAdjustment> existing = adjustments.findDisputeForUpdate(event.resourceId());
        UUID adjustmentId = existing.map(ProviderAdjustment::adjustmentId).orElseGet(ids::generateId);
        boolean inserted = existing.isEmpty() && adjustments.insertDispute(adjustmentId, event.resourceId(),
                attempt.map(PaymentAttempt::getAttemptId).orElse(null), event.relatedCaptureId(),
                next.providerMinor(), next.currency(), next.amountWon(), event.resourceStatus(), next.status(),
                next.reviewReason(), event.eventId(), now);
        if (!inserted) {
            // 이미 있는 분쟁(또는 동시에 먼저 기록된 분쟁). 진행 중일 때만 다음 상태로 옮긴다.
            adjustmentId = existing.or(() -> adjustments.findDisputeForUpdate(event.resourceId()))
                    .orElseThrow(() -> new IllegalStateException("Dispute adjustment disappeared")).adjustmentId();
            if (!adjustments.updateHeldDispute(adjustmentId, next.providerMinor(), next.currency(), next.amountWon(),
                    event.resourceStatus(), next.status(), next.reviewReason(), event.eventId(), now)) {
                adjustments.touchDispute(adjustmentId, event.resourceStatus(), event.eventId(), now);
                webhookEvents.markProcessed(event.eventId(), now);
                return ACKNOWLEDGED;
            }
        }
        webhookEvents.markProcessed(event.eventId(), now);
        return next.status() == ProviderAdjustmentStatus.POSTING
                ? PaymentWebhookResponse.providerAdjustment(adjustmentId) : ACKNOWLEDGED;
    }

    private static boolean isResolved(PaymentWebhookEventResponse event) {
        return "CUSTOMER.DISPUTE.RESOLVED".equals(event.eventType()) || "RESOLVED".equals(event.resourceStatus());
    }

    /**
     * 진행 중 분쟁의 보류 원화. 분쟁 금액을 capture 비율로 올림해 판매자에게 남길 몫이 모자라지 않게 하고, capture 남은
     * 원화를 넘기지 않는다. 금액·통화를 읽지 못하면 남은 원화 전부를 보류한다.
     */
    private DisputeState hold(PaymentAttempt attempt, PaymentWebhookEventResponse event) {
        Long disputedMinor = minorUnits(event.resourceAmount());
        if (!isPriced(attempt)) return DisputeState.review("DISPUTE_NEEDS_OPERATOR", disputedMinor,
                event.resourceCurrency());
        RefundedTotals refunded = refunds.refundedTotalsForAttempt(attempt.getAttemptId());
        long remainingWon = attempt.getTotalWon() - refunded.amountWon();
        if (remainingWon <= 0) {
            // 돌려줄 capture 가 남지 않았다. 분쟁에 져도 더 뺄 돈이 없으므로 보류하지 않는다.
            return new DisputeState(ProviderAdjustmentStatus.CLOSED, disputedMinor, event.resourceCurrency(), null,
                    null);
        }
        long holdWon = remainingWon;
        if (disputedMinor != null && disputedMinor > 0
                && attempt.getProviderCurrency().equalsIgnoreCase(event.resourceCurrency())) {
            holdWon = Math.min(remainingWon, BigDecimal.valueOf(disputedMinor)
                    .multiply(BigDecimal.valueOf(attempt.getTotalWon()))
                    .divide(BigDecimal.valueOf(attempt.getProviderAmountMinor()), 0, RoundingMode.CEILING)
                    .longValueExact());
        }
        return new DisputeState(ProviderAdjustmentStatus.HOLD, disputedMinor, event.resourceCurrency(), holdWon,
                null);
    }

    /**
     * 끝난 분쟁. 판매자 패소({@code RESOLVED_BUYER_FAVOUR})만 차감한다. {@code RESOLVED_WITH_PAYOUT}은 PayPal 보호로
     * 끝난 것이라 판매자 돈이 빠지지 않는다. 차감액은 구매자에게 돌려준 금액에서 이미 원장에 넣은 capture 역전을 빼고
     * capture 남은 금액으로 자른다. 역전이 분쟁의 돈을 먼저 옮겼으면 같은 돈을 두 번 빼지 않는다.
     */
    private DisputeState resolve(PaymentAttempt attempt, PaymentWebhookEventResponse event) {
        String outcome = event.disputeOutcomeCode();
        if (outcome == null || !SELLER_KEEPS.contains(outcome) && !SELLER_LOSES.equals(outcome)) {
            return DisputeState.review("DISPUTE_OUTCOME_UNKNOWN", minorUnits(event.resourceAmount()),
                    event.resourceCurrency());
        }
        if (SELLER_KEEPS.contains(outcome)) {
            return new DisputeState(ProviderAdjustmentStatus.CLOSED, minorUnits(event.resourceAmount()),
                    event.resourceCurrency(), null, null);
        }
        Long refundedMinor = minorUnits(event.disputeRefundedAmount());
        String currency = event.disputeRefundedCurrency() != null ? event.disputeRefundedCurrency()
                : event.resourceCurrency();
        if (!isPriced(attempt)) return DisputeState.review("DISPUTE_NEEDS_OPERATOR", refundedMinor, currency);
        if (refundedMinor == null || refundedMinor <= 0 || !attempt.getProviderCurrency().equalsIgnoreCase(currency)) {
            return DisputeState.review("DISPUTE_LOSS_AMOUNT_UNKNOWN", refundedMinor, currency);
        }
        RefundedTotals refunded = refunds.refundedTotalsForAttempt(attempt.getAttemptId());
        long remainingMinor = attempt.getProviderAmountMinor() - refunded.providerAmountMinor();
        long remainingWon = attempt.getTotalWon() - refunded.amountWon();
        long debitMinor = Math.min(refundedMinor - adjustments.reversedMinorForAttempt(attempt.getAttemptId()),
                remainingMinor);
        if (debitMinor <= 0 || remainingWon <= 0) {
            return new DisputeState(ProviderAdjustmentStatus.CLOSED, refundedMinor, currency, null, null);
        }
        long amountWon = debitMinor == remainingMinor ? remainingWon
                : Math.min(remainingWon, BigDecimal.valueOf(debitMinor)
                        .multiply(BigDecimal.valueOf(attempt.getTotalWon()))
                        .divide(BigDecimal.valueOf(attempt.getProviderAmountMinor()), 0, RoundingMode.DOWN)
                        .longValueExact());
        if (amountWon <= 0) return new DisputeState(ProviderAdjustmentStatus.CLOSED, refundedMinor, currency, null, null);
        return new DisputeState(ProviderAdjustmentStatus.POSTING, debitMinor, currency, amountWon, null);
    }

    private static boolean isPriced(PaymentAttempt attempt) {
        return attempt.getState() == PaymentState.CAPTURED && attempt.hasFxSnapshot() && attempt.getTotalWon() != null
                && attempt.getProviderAmountMinor() != null && attempt.getProviderAmountMinor() > 0;
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

    List<PaymentDisputeHoldResponse> heldDisputes() {
        return adjustments.heldDisputes().stream().map(hold -> new PaymentDisputeHoldResponse(hold.adjustmentId(),
                hold.orderId(), hold.checkoutId(), hold.amountWon())).toList();
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
        if (!isPriced(attempt)) {
            return Pricing.review("ATTEMPT_NOT_CAPTURED");
        }
        if (kind == ProviderAdjustmentKind.REVERSAL && adjustments.hasDebitedDispute(attempt.getAttemptId())) {
            // 판매자 패소로 이미 차감한 분쟁이 있다. 그 뒤의 역전은 같은 돈일 수 있어 자동으로 빼지 않는다(ADR-0115).
            return Pricing.review("DISPUTE_ALREADY_DEBITED");
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

    private record DisputeState(ProviderAdjustmentStatus status, Long providerMinor, String currency, Long amountWon,
            String reviewReason) {
        static DisputeState review(String reason, Long providerMinor, String currency) {
            return new DisputeState(ProviderAdjustmentStatus.REVIEW, providerMinor, currency, null, reason);
        }
    }

    private record Pricing(ProviderAdjustmentStatus status, Long amountWon, String reviewReason) {
        static Pricing review(String reason) {
            return new Pricing(ProviderAdjustmentStatus.REVIEW, null, reason);
        }
    }
}
