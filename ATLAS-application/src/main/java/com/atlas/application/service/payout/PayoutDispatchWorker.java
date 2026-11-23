package com.atlas.application.service.payout;

import com.atlas.application.persistence.projection.PayoutDispatchMetrics;
import com.atlas.application.persistence.projection.PayoutDispatchRecord;
import com.atlas.application.repository.payout.PayoutDispatchRepository;
import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.payment.dto.response.PayPalPayoutBatchResponse;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutException;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutException.Classification;
import com.atlas.payment.dto.request.PayPalPayoutRequest;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutsProvider;
import com.atlas.settlement.service.SettlementPayoutService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts.worker", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class PayoutDispatchWorker {
    private final PayoutDispatchRepository repository;
    private final PayoutDispatchOutcomeService outcomes;
    private final SettlementPayoutService payouts;
    private final PayPalPayoutsProvider provider;
    private final PayoutRecipientCipher cipher;
    private final PayoutFxConversionService conversions;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicReference<PayoutDispatchMetrics> metricSnapshot;
    private final int batchSize;
    private final Duration leaseDuration, maxUnknownAge, maxWaitingAge;
    private final int maxAttempts;

    public PayoutDispatchWorker(PayoutDispatchRepository repository, PayoutDispatchOutcomeService outcomes,
            SettlementPayoutService payouts, PayPalPayoutsProvider provider, PayoutRecipientCipher cipher,
            PayoutFxConversionService conversions, Clock clock, MeterRegistry metrics,
            @Value("${atlas.payment.paypal.payouts.worker.batch-size:1}") int batchSize,
            @Value("${atlas.payment.paypal.payouts.worker.lease-duration:PT60S}") Duration leaseDuration,
            @Value("${atlas.payment.paypal.payouts.worker.max-attempts:8}") int maxAttempts,
            @Value("${atlas.payment.paypal.payouts.worker.max-unknown-age:P29D}") Duration maxUnknownAge,
            @Value("${atlas.payment.paypal.payouts.worker.max-waiting-age:P60D}") Duration maxWaitingAge) {
        this.repository = Objects.requireNonNull(repository);
        this.outcomes = Objects.requireNonNull(outcomes);
        this.payouts = Objects.requireNonNull(payouts);
        this.provider = Objects.requireNonNull(provider);
        this.cipher = Objects.requireNonNull(cipher);
        this.conversions = Objects.requireNonNull(conversions);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        if (batchSize <= 0 || maxAttempts <= 0 || leaseDuration.isNegative() || leaseDuration.isZero()
                || maxUnknownAge.isNegative() || maxUnknownAge.isZero()
                || maxWaitingAge.isNegative() || maxWaitingAge.isZero()) {
            throw new IllegalArgumentException("Payout worker limits and durations must be positive");
        }
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        this.maxUnknownAge = maxUnknownAge;
        this.maxWaitingAge = maxWaitingAge;
        metricSnapshot = new AtomicReference<>(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        metrics.gauge("payout.dispatch.backlog", metricSnapshot, value -> value.get().backlog());
        metrics.gauge("payout.dispatch.leased", metricSnapshot, value -> value.get().leased());
        metrics.gauge("payout.dispatch.needs_review", metricSnapshot, value -> value.get().needsReview());
        metrics.gauge("payout.dispatch.completed", metricSnapshot, value -> value.get().completed());
        metrics.gauge("payout.dispatch.failed", metricSnapshot, value -> value.get().failed());
        metrics.gauge("payout.dispatch.oldest_age_seconds", metricSnapshot,
                value -> value.get().oldestAgeSeconds());
    }

    @Scheduled(fixedDelayString = "${atlas.payment.paypal.payouts.worker.poll-interval:PT5S}",
               initialDelayString = "${atlas.payment.paypal.payouts.worker.initial-delay:PT5S}")
    public void dispatchDue() {
        List<PayoutDispatchRecord> claimed = repository.claim(batchSize, leaseDuration);
        try {
            for (PayoutDispatchRecord record : claimed) process(record);
        } finally {
            metricSnapshot.set(repository.metricsSnapshot());
        }
    }

    private void process(PayoutDispatchRecord r) {
        if (expired(r)) {
            if (r.providerBatchId() == null && !payouts.beginDispatch(r.payoutId())) {
                outcomes.cancel(r);
                outcome("cancelled");
            } else {
                review(r, "MAX_ATTEMPTS_OR_AGE");
            }
            return;
        }
        try {
            PayPalPayoutBatchResponse batch;
            if (r.providerBatchId() == null) {
                // Linearization fence prevents a payout already cancelled from reaching PayPal.
                if (!payouts.beginDispatch(r.payoutId())) { outcomes.cancel(r); outcome("cancelled"); return; }
                String recipient = cipher.decrypt(r.recipientEmailCiphertext());
                PayPalPayoutRequest request = new PayPalPayoutRequest(r.senderBatchId(), r.senderItemId(),
                        r.paypalRequestId(), recipient, r.providerAmountMinor(), "USD");
                Timer.Sample sample = Timer.start(metrics);
                try {
                    batch = provider.createBatch(request);
                } finally {
                    sample.stop(metrics.timer("payout.dispatch.provider.duration", "operation", "create"));
                }
            } else {
                Timer.Sample sample = Timer.start(metrics);
                try {
                    batch = provider.getBatch(r.providerBatchId());
                } finally {
                    sample.stop(metrics.timer("payout.dispatch.provider.duration", "operation", "reconcile"));
                }
            }
            handleBatch(r, batch);
        } catch (PayPalPayoutException ex) {
            handleProviderException(r, ex);
        } catch (RuntimeException ex) {
            // Do not persist/log exception messages or provider response bodies.
            retryOrReview(r, "DISPATCH_ERROR");
        }
    }

    private void handleBatch(PayoutDispatchRecord r, PayPalPayoutBatchResponse batch) {
        if (batch == null || !safeProviderId(batch.batchId())) {
            review(r, "INVALID_BATCH_ID");
            return;
        }
        boolean batchMismatch = r.providerBatchId() != null && !r.providerBatchId().equals(batch.batchId());
        boolean senderMismatch = batch.senderBatchId() != null
                && !r.senderBatchId().equals(batch.senderBatchId());
        boolean missingSenderOnCreate = r.providerBatchId() == null && batch.senderBatchId() == null;
        if (batchMismatch || senderMismatch || missingSenderOnCreate) {
            review(r, "BATCH_ID_MISMATCH");
            return;
        }
        String batchStatus = safe(batch.batchStatus(), "UNKNOWN");
        List<PayPalPayoutBatchResponse.Item> items = batch.items().stream()
                .filter(item -> r.senderItemId().equals(item.senderItemId())).toList();
        if (items.size() > 1) {
            outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, null, null, "ITEM_MISMATCH");
            outcome("needs_review");
            return;
        }
        if (items.isEmpty()) {
            if (isTerminalBatchFailure(batch.batchStatus())) {
                outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, null, null, "BATCH_FAILED_WITHOUT_ITEM");
                outcome("needs_review");
            } else if (outcomes.accepted(r, batch.batchId(), batchStatus, null, null,
                    null, null, backoff(r))) {
                outcome("waiting");
            }
            return;
        }
        PayPalPayoutBatchResponse.Item item = items.getFirst();
        String itemStatus = safe(item.transactionStatus(), "UNKNOWN");
        String providerItemId = safeProviderId(item.itemId()) ? item.itemId() : null;
        if (hasUnsupportedRecipientConversion(item, r.providerAmountMinor())) {
            outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, providerItemId,
                    itemStatus, "RECIPIENT_CURRENCY_CONVERSION");
            outcome("needs_review");
            return;
        }
        if (!"USD".equalsIgnoreCase(item.currency()) || item.amount() == null
                || item.amount().compareTo(BigDecimal.valueOf(r.providerAmountMinor(), 2)) != 0) {
            outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, providerItemId,
                    itemStatus, "AMOUNT_OR_CURRENCY_MISMATCH");
            outcome("needs_review");
            return;
        }
        if ("SUCCESS".equals(itemStatus)) {
            if (providerItemId == null || item.fee() == null || item.fee().signum() < 0
                    || !"USD".equalsIgnoreCase(item.feeCurrency())) {
                outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, providerItemId,
                        itemStatus, "FEE_OR_ITEM_ID_MISSING");
                outcome("needs_review");
                return;
            }
            try {
                long feeMinor = item.fee().movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
                long feeWon = conversions.usdAmountToWonCeiling(item.fee(), r.fxRateWonPerUsd());
                if (outcomes.complete(r, batch.batchId(), batchStatus, item.itemId(), itemStatus, feeMinor, feeWon)) {
                    outcome("completed");
                }
            } catch (ArithmeticException | IllegalArgumentException exception) {
                outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, providerItemId,
                        itemStatus, "INVALID_PROVIDER_FEE");
                outcome("needs_review");
            }
        } else if ("UNCLAIMED".equals(itemStatus)) {
            if (outcomes.unclaimed(r, batch.batchId(), batchStatus, providerItemId, itemStatus,
                    null, null, backoff(r))) outcome("unclaimed");
        } else if (isWaitingItemStatus(itemStatus)) {
            if (outcomes.accepted(r, batch.batchId(), batchStatus, providerItemId, itemStatus,
                    null, null, backoff(r))) outcome("waiting");
        } else if (isDefinitiveFailure(itemStatus)) {
            if (providerItemId == null || item.fee() == null || item.fee().signum() != 0
                    || !"USD".equalsIgnoreCase(item.feeCurrency())) {
                outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, providerItemId,
                        itemStatus, "FAILED_FEE_UNCONFIRMED");
                outcome("needs_review");
            } else if (outcomes.fail(r, batch.batchId(), batchStatus, providerItemId,
                    itemStatus, "PROVIDER_ITEM_FAILED", 0L, 0L)) {
                outcome("failed");
            }
        } else {
            outcomes.reviewWithEvidence(r, batch.batchId(), batchStatus, providerItemId,
                    itemStatus, "UNKNOWN_PROVIDER_STATUS");
            outcome("needs_review");
        }
    }

    private void handleProviderException(PayoutDispatchRecord r, PayPalPayoutException exception) {
        Classification classification = exception.getClassification();
        if (classification == Classification.DEFINITIVE_NO_SEND) {
            if (outcomes.fail(r, null, null, null, null, "PROVIDER_REJECTED", null, null)) outcome("failed");
        } else if (classification == Classification.DUPLICATE_BATCH) {
            String originalBatchId = exception.getOriginalBatchId();
            if (!safeProviderId(originalBatchId)) {
                review(r, "DUPLICATE_WITHOUT_BATCH_ID");
            } else if (outcomes.accepted(r, originalBatchId, "UNKNOWN", null, null,
                    null, null, backoff(r))) {
                outcome("duplicate_waiting");
            }
        } else if (classification == Classification.RETRYABLE) {
            retryOrReview(r, "PROVIDER_RETRYABLE");
        } else {
            retryOrReview(r, "PROVIDER_UNKNOWN");
        }
    }

    private void retryOrReview(PayoutDispatchRecord r, String code) {
        if (expired(r)) {
            review(r, "MAX_ATTEMPTS_OR_AGE");
        } else if (outcomes.retry(r, backoff(r), code)) {
            outcome("retry");
        }
    }

    private void review(PayoutDispatchRecord r, String code) {
        if (outcomes.review(r, code)) outcome("needs_review");
    }

    private boolean expired(PayoutDispatchRecord r) {
        Duration age = Duration.between(r.firstEnqueuedAt(), clock.instant());
        Duration maxAge = r.providerBatchId() == null ? maxUnknownAge : maxWaitingAge;
        return age.compareTo(maxAge) >= 0
                || (r.providerBatchId() == null && r.attemptCount() >= maxAttempts);
    }

    private static Duration backoff(PayoutDispatchRecord record) {
        long seconds = Math.min(3600L, 1L << Math.min(12, Math.max(0, record.attemptCount() - 1)));
        long jitterPercent = 80L + Math.floorMod(record.payoutId().hashCode(), 41);
        return Duration.ofMillis(seconds * 1_000L * jitterPercent / 100L);
    }

    private static boolean isWaitingItemStatus(String status) {
        return List.of("PENDING", "PROCESSING").contains(status);
    }

    private static boolean isDefinitiveFailure(String status) {
        return List.of("FAILED", "DENIED").contains(status);
    }

    private static boolean hasUnsupportedRecipientConversion(PayPalPayoutBatchResponse.Item item, long payoutAmountMinor) {
        boolean hasConversionFacts = item.currencyConversionPresent() || item.conversionFromAmount() != null
                || item.conversionFromCurrency() != null || item.conversionToAmount() != null
                || item.conversionToCurrency() != null || item.conversionRate() != null;
        if (!hasConversionFacts) return false;
        BigDecimal expected = BigDecimal.valueOf(payoutAmountMinor, 2);
        return !"USD".equalsIgnoreCase(item.conversionFromCurrency())
                || !"USD".equalsIgnoreCase(item.conversionToCurrency())
                || item.conversionFromAmount() == null || item.conversionFromAmount().compareTo(expected) != 0
                || item.conversionToAmount() == null || item.conversionToAmount().compareTo(expected) != 0
                || item.conversionRate() == null || item.conversionRate().compareTo(BigDecimal.ONE) != 0;
    }
    private static boolean isTerminalBatchFailure(String status) {
        return status != null && List.of("FAILED", "DENIED", "CANCELED", "CANCELLED")
                .contains(status.toUpperCase(Locale.ROOT));
    }

    private static boolean safeProviderId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}");
    }
    private static String safe(String s) { return safe(s, null); }
    private static String safe(String s, String fallback) {
        if (s == null) return fallback;
        String value = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return value.length() > 32 ? value.substring(0, 32) : value;
    }
    private void outcome(String result) { metrics.counter("payout.dispatch.outcomes", "result", result).increment(); }
}
