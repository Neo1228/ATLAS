package com.atlas.application.service.payout;

import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.application.controller.dto.PayoutDispatchStatusResponse;
import com.atlas.application.controller.dto.PayoutDispatchReviewPage;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.exchange.ExchangeRateProvider;
import com.atlas.payment.provider.exchange.ExchangeRateQuote;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.dto.response.SettlementPayoutStatus;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.service.SettlementPayoutService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Prepares provider facts outside a transaction, then delegates the atomic approval/outbox commit. */
@Service
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class PayoutDispatchApprovalService {
    private final SettlementPayoutService payouts;
    private final SellerPayoutProfileService profiles;
    private final ExchangeRateProvider exchangeRates;
    private final PayoutFxConversionService conversions;
    private final PayoutRecipientCipher cipher;
    private final PayoutDispatchRepository dispatches;
    private final PayoutDispatchApprovalCommitService committer;

    public PayoutDispatchApprovalService(SettlementPayoutService payouts, SellerPayoutProfileService profiles,
            ExchangeRateProvider exchangeRates, PayoutFxConversionService conversions,
            PayoutRecipientCipher cipher, PayoutDispatchRepository dispatches,
            PayoutDispatchApprovalCommitService committer) {
        this.payouts = Objects.requireNonNull(payouts);
        this.profiles = Objects.requireNonNull(profiles);
        this.exchangeRates = Objects.requireNonNull(exchangeRates);
        this.conversions = Objects.requireNonNull(conversions);
        this.cipher = Objects.requireNonNull(cipher);
        this.dispatches = Objects.requireNonNull(dispatches);
        this.committer = Objects.requireNonNull(committer);
    }

    public SellerPayoutResponse approve(UUID payoutId, String idempotencyKey, String actor, String reason) {
        validateOperatorFacts(payoutId, idempotencyKey, actor, reason);
        if (dispatches.find(payoutId).isPresent()) {
            return committer.replay(payoutId, idempotencyKey, actor, reason.trim());
        }
        SellerPayoutResponse payout = payouts.find(payoutId);
        if (payout.state() != SettlementPayoutStatus.REQUESTED
                && payout.state() != SettlementPayoutStatus.APPROVED) {
            throw conflict("Only a requested payout can be approved for provider dispatch");
        }
        if (payout.state() == SettlementPayoutStatus.APPROVED) {
            throw conflict("Approved payout has no durable provider intent and requires operator review");
        }
        SellerPayoutProfileService.ActiveRecipient recipient = profiles.requireActiveRecipient(payout.sellerId());
        if (!"USD".equals(recipient.currency())) throw conflict("Only USD payout profiles can be dispatched");
        ExchangeRateQuote quote = exchangeRates.latestUsdKrwRate();
        if (quote == null) {
            throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                    "Payout FX quote is unavailable");
        }
        PayoutFxConversionService.Conversion conversion;
        try {
            conversion = conversions.convert(payout.amountWon(), quote);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw conflict("Payout is not eligible for automatic USD conversion");
        }
        String recipientCiphertext;
        try {
            recipientCiphertext = cipher.encrypt(recipient.verifiedEmail());
        } catch (IllegalArgumentException exception) {
            throw conflict("Verified Seller recipient cannot be protected for payout dispatch");
        }
        String recipientFingerprint = cipher.fingerprint(recipient.verifiedEmail());
        String batchId = stableId("batch", payoutId);
        String itemId = stableId("item", payoutId);
        String requestId = stableId("request", payoutId);
        String payloadHash = payloadHash(
                payout, recipient, conversion, recipientFingerprint, batchId, itemId, requestId);
        PayoutDispatchPlan plan = new PayoutDispatchPlan(payoutId, recipient.profileVersion(), recipientCiphertext,
                recipient.countryCode(), payout.amountWon(), conversion.providerAmountMinor(),
                conversion.fxRateWonPerUsd(), conversion.source(), conversion.asOfDate(),
                conversion.providerDebitBookValueWon(), conversion.fxRoundingExpenseWon(), batchId, itemId,
                requestId, payloadHash);
        return committer.approve(payoutId, plan, recipient, idempotencyKey, actor, reason.trim());
    }

    public SellerPayoutResponse cancelBeforeDispatch(UUID payoutId, String idempotencyKey,
            String actor, String reason) {
        validateOperatorFacts(payoutId, idempotencyKey, actor, reason);
        return committer.cancel(payoutId, idempotencyKey, actor, reason.trim());
    }

    public SellerPayoutResponse requeueNeedsReview(UUID payoutId, String idempotencyKey,
            String actor, String reason) {
        validateOperatorFacts(payoutId, idempotencyKey, actor, reason);
        return committer.requeueNeedsReview(payoutId, idempotencyKey, actor, reason.trim());
    }

    public PayoutDispatchStatusResponse dispatchStatus(UUID payoutId) {
        SellerPayoutResponse payout = payouts.find(payoutId);
        return dispatches.find(payoutId)
                .map(record -> new PayoutDispatchStatusResponse(record.payoutId(), payout.state().name(),
                        record.status(), record.senderBatchId(), record.providerBatchId(), record.providerItemId(),
                        record.providerBatchStatus(), record.providerItemStatus(), record.requestedAmountWon(),
                        record.providerAmountMinor(), record.payoutCurrency(), record.fxRateWonPerUsd(),
                        record.fxSource(), record.fxAsOfDate(), record.attemptCount(), record.firstEnqueuedAt(),
                        record.lastAttemptAt(), record.availableAt(), record.lastErrorCode()))
                .orElseGet(() -> new PayoutDispatchStatusResponse(payout.payoutId(), payout.state().name(),
                        "MANUAL_NO_PROVIDER_OUTBOX", null, null, null, null, null, payout.amountWon(),
                        0, "KRW", null, null, null, 0, payout.requestedAt(), null, null, null));
    }

    public PayoutDispatchReviewPage needsReview(int limit, int offset) {
        if (offset < 0 || offset > 100_000) {
            throw new SettlementException(SettlementException.Code.INVALID_REQUEST, "Invalid payout review offset");
        }
        int boundedLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        List<PayoutDispatchRecord> records = dispatches.findNeedsReview(boundedLimit + 1, offset);
        boolean hasMore = records.size() > boundedLimit;
        if (hasMore) records = records.subList(0, boundedLimit);
        List<PayoutDispatchStatusResponse> items = records.stream().map(record ->
                toStatus(record, payouts.find(record.payoutId()).state().name())).toList();
        return new PayoutDispatchReviewPage(boundedLimit, offset, hasMore, items);
    }

    private PayoutDispatchStatusResponse toStatus(PayoutDispatchRecord record, String payoutState) {
        return new PayoutDispatchStatusResponse(record.payoutId(), payoutState, record.status(),
                record.senderBatchId(), record.providerBatchId(), record.providerItemId(),
                record.providerBatchStatus(), record.providerItemStatus(), record.requestedAmountWon(),
                record.providerAmountMinor(), record.payoutCurrency(), record.fxRateWonPerUsd(),
                record.fxSource(), record.fxAsOfDate(), record.attemptCount(), record.firstEnqueuedAt(),
                record.lastAttemptAt(), record.availableAt(), record.lastErrorCode());
    }

    private static void validateOperatorFacts(UUID payoutId, String idempotencyKey, String actor, String reason) {
        if (payoutId == null || !StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 128
                || !StringUtils.hasText(actor) || actor.length() > 128
                || !StringUtils.hasText(reason) || reason.length() > 500
                || !reason.matches("[A-Za-z0-9가-힣 .,/_:-]+")) {
            throw new SettlementException(SettlementException.Code.INVALID_REQUEST,
                    "Payout, idempotency key, operator and reason are required");
        }
    }

    private static String stableId(String purpose, UUID payoutId) {
        return UUID.nameUUIDFromBytes(("atlas-paypal-payout:" + purpose + ":" + payoutId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String payloadHash(SellerPayoutResponse payout, SellerPayoutProfileService.ActiveRecipient recipient,
            PayoutFxConversionService.Conversion conversion, String recipientFingerprint,
            String batchId, String itemId, String requestId) {
        String facts = String.join("|", "v2", payout.payoutId().toString(),
                Integer.toString(recipient.profileVersion()), recipientFingerprint, recipient.countryCode(), "USD",
                Long.toString(payout.amountWon()), Long.toString(conversion.providerAmountMinor()),
                conversion.fxRateWonPerUsd().toPlainString(), conversion.source(), conversion.asOfDate().toString(),
                Long.toString(conversion.providerDebitBookValueWon()), Long.toString(conversion.fxRoundingExpenseWon()),
                batchId, itemId, requestId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static SettlementException conflict(String message) {
        return new SettlementException(SettlementException.Code.CONFLICT, message);
    }
}
