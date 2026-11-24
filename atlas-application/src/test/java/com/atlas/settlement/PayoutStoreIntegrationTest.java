package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.schema.BaselineSchema;
import com.atlas.settlement.model.PayoutDispatchMetrics;
import com.atlas.settlement.model.PayoutDispatchPlan;
import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.model.SellerPayoutProfile;
import com.atlas.settlement.repository.JdbcPayoutDispatchStore;
import com.atlas.settlement.repository.JdbcPayoutOperatorActionStore;
import com.atlas.settlement.repository.JdbcSellerPayoutProfileStore;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import com.atlas.settlement.service.SellerPayoutProfileStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 판매자 지급 프로필·디스패치 outbox·운영자 조치 저장소의 실제 SQL을 PostgreSQL에서 확인한다. */
@Testcontainers(disabledWithoutDocker = true)
class PayoutStoreIntegrationTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-03-01T10:30:00Z");
    private static final String OUTBOX = "settlement_payout_dispatch_outbox";
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DriverManagerDataSource dataSource;

    private JdbcTemplate jdbc;
    private SellerPayoutProfileStore profiles;
    private PayoutDispatchStore dispatches;
    private PayoutOperatorActionStore actions;

    @BeforeAll
    static void createSchema() {
        dataSource = newDataSource();
        JdbcTemplate admin = new JdbcTemplate(dataSource);
        admin.execute("DROP SCHEMA public CASCADE");
        admin.execute("CREATE SCHEMA public");
        admin.execute(BaselineSchema.script());
    }

    @BeforeEach
    void clearRows() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE seller, settlement_payout CASCADE");
        profiles = new JdbcSellerPayoutProfileStore(jdbc);
        dispatches = new JdbcPayoutDispatchStore(jdbc);
        actions = new JdbcPayoutOperatorActionStore(jdbc);
    }

    @Test
    void findReturnsEmptyForSellerWithoutProfile() {
        assertThat(profiles.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void submitCreatesPendingReviewProfileThatFindReturns() {
        UUID sellerId = seedSeller();
        UUID accountId = seedAccount(sellerId);

        SellerPayoutProfile submitted = profiles.submit(sellerId, accountId, "US", SUBMITTED_AT);

        assertThat(submitted.sellerId()).isEqualTo(sellerId);
        assertThat(submitted.recipientAccountId()).isEqualTo(accountId);
        assertThat(submitted.countryCode()).isEqualTo("US");
        assertThat(submitted.payoutCurrency()).isEqualTo("USD");
        assertThat(submitted.state()).isEqualTo(SellerPayoutProfile.State.PENDING_REVIEW);
        assertThat(submitted.profileVersion()).isEqualTo(1);
        assertThat(submitted.updatedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(submitted.verifiedAt()).isNull();
        assertThat(submitted.verifiedBy()).isNull();
        assertThat(profiles.find(sellerId)).contains(submitted);
    }

    @Test
    void submitWithSameFactsKeepsVersionAndTimestamp() {
        UUID sellerId = seedSeller();
        UUID accountId = seedAccount(sellerId);
        SellerPayoutProfile first = profiles.submit(sellerId, accountId, "US", SUBMITTED_AT);

        SellerPayoutProfile replay = profiles.submit(sellerId, accountId, "US", VERIFIED_AT);

        assertThat(replay).isEqualTo(first);
        assertThat(replay.profileVersion()).isEqualTo(1);
        assertThat(count("seller_payout_profile")).isEqualTo(1);
    }

    @Test
    void submitWithChangedCountryBumpsVersion() {
        UUID sellerId = seedSeller();
        UUID accountId = seedAccount(sellerId);
        profiles.submit(sellerId, accountId, "US", SUBMITTED_AT);

        SellerPayoutProfile resubmitted = profiles.submit(sellerId, accountId, "KR", VERIFIED_AT);

        assertThat(resubmitted.countryCode()).isEqualTo("KR");
        assertThat(resubmitted.profileVersion()).isEqualTo(2);
        assertThat(resubmitted.state()).isEqualTo(SellerPayoutProfile.State.PENDING_REVIEW);
    }

    @Test
    void submitRejectsAccountOwnedByAnotherSeller() {
        UUID sellerId = seedSeller();
        UUID foreignAccount = seedAccount(seedSeller());

        assertThatThrownBy(() -> profiles.submit(sellerId, foreignAccount, "US", SUBMITTED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(profiles.find(sellerId)).isEmpty();
    }

    @Test
    void verifyActivatesPendingProfileAtExpectedVersion() {
        UUID sellerId = seedSeller();
        UUID accountId = seedAccount(sellerId);
        profiles.submit(sellerId, accountId, "US", SUBMITTED_AT);

        SellerPayoutProfile active = profiles.verify(sellerId, 1, "operator-1", VERIFIED_AT).orElseThrow();

        assertThat(active.state()).isEqualTo(SellerPayoutProfile.State.ACTIVE);
        assertThat(active.profileVersion()).isEqualTo(2);
        assertThat(active.verifiedAt()).isEqualTo(VERIFIED_AT);
        assertThat(active.verifiedBy()).isEqualTo("operator-1");
        assertThat(active.updatedAt()).isEqualTo(VERIFIED_AT);
        assertThat(profiles.find(sellerId)).contains(active);
    }

    @Test
    void verifyIgnoresStaleVersionAlreadyActiveProfileAndUnknownSeller() {
        UUID sellerId = seedSeller();
        UUID accountId = seedAccount(sellerId);
        SellerPayoutProfile pending = profiles.submit(sellerId, accountId, "US", SUBMITTED_AT);

        assertThat(profiles.verify(sellerId, 5, "operator-1", VERIFIED_AT)).isEmpty();
        assertThat(profiles.find(sellerId)).contains(pending);

        SellerPayoutProfile active = profiles.verify(sellerId, 1, "operator-1", VERIFIED_AT).orElseThrow();

        assertThat(profiles.verify(sellerId, active.profileVersion(), "operator-2", VERIFIED_AT.plusSeconds(60)))
                .isEmpty();
        assertThat(profiles.verify(UUID.randomUUID(), 1, "operator-1", VERIFIED_AT)).isEmpty();
        assertThat(profiles.find(sellerId)).contains(active);
    }

    @Test
    void recordActionStoresRequestHashOnceAndKeepsTheFirstHash() {
        UUID sellerId = seedSeller();
        UUID accountId = seedAccount(sellerId);
        profiles.submit(sellerId, accountId, "US", SUBMITTED_AT);
        String firstHash = "a".repeat(64);

        boolean first = profiles.recordAction(sellerId, 1, "SUBMIT", "key-1", firstHash, "seller", "초기 등록", SUBMITTED_AT);
        boolean duplicate = profiles.recordAction(
                sellerId, 1, "SUBMIT", "key-1", "b".repeat(64), "seller", "초기 등록", VERIFIED_AT);

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(profiles.findRequestHash(sellerId, "key-1")).contains(firstHash);
        assertThat(count("seller_payout_profile_action")).isEqualTo(1);
    }

    @Test
    void findRequestHashIsScopedToSellerAndIdempotencyKey() {
        UUID sellerId = seedSeller();
        UUID otherSeller = seedSeller();
        profiles.submit(sellerId, seedAccount(sellerId), "US", SUBMITTED_AT);
        profiles.submit(otherSeller, seedAccount(otherSeller), "US", SUBMITTED_AT);
        profiles.recordAction(sellerId, 1, "SUBMIT", "key-1", "a".repeat(64), "seller", "등록", SUBMITTED_AT);

        assertThat(profiles.findRequestHash(sellerId, "key-2")).isEmpty();
        assertThat(profiles.findRequestHash(otherSeller, "key-1")).isEmpty();
        assertThat(profiles.recordAction(
                otherSeller, 1, "SUBMIT", "key-1", "c".repeat(64), "seller", "등록", SUBMITTED_AT)).isTrue();
        assertThat(profiles.findRequestHash(otherSeller, "key-1")).contains("c".repeat(64));
        assertThat(profiles.findRequestHash(sellerId, "key-1")).contains("a".repeat(64));
    }

    @Test
    void operatorActionIsRecordedOnceAndSameFactsReplayIsAccepted() {
        UUID payoutId = seedPayout();

        boolean first = actions.record(payoutId, "op-key-1", "CANCEL", "operator-1", "중복 요청", SUBMITTED_AT);
        boolean replay = actions.record(payoutId, "op-key-1", "CANCEL", "operator-1", "중복 요청", VERIFIED_AT);

        assertThat(first).isTrue();
        assertThat(replay).isTrue();
        assertThat(count("settlement_payout_operator_action")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT created_at = ?::timestamptz FROM settlement_payout_operator_action",
                Boolean.class, SUBMITTED_AT.toString())).isTrue();
    }

    @Test
    void operatorActionWithConflictingFactsForSameKeyIsRejectedWithoutOverwriting() {
        UUID payoutId = seedPayout();
        actions.record(payoutId, "op-key-1", "CANCEL", "operator-1", "중복 요청", SUBMITTED_AT);

        assertThat(actions.record(payoutId, "op-key-1", "REQUEUE", "operator-1", "중복 요청", VERIFIED_AT)).isFalse();
        assertThat(actions.record(payoutId, "op-key-1", "CANCEL", "operator-2", "중복 요청", VERIFIED_AT)).isFalse();
        assertThat(actions.record(payoutId, "op-key-1", "CANCEL", "operator-1", "다른 사유", VERIFIED_AT)).isFalse();

        assertThat(count("settlement_payout_operator_action")).isEqualTo(1);
        assertThat(actions.matches(payoutId, "op-key-1", "CANCEL", "operator-1", "중복 요청")).isTrue();
    }

    @Test
    void operatorActionMatchesOnlyWhenEveryFactAndTheScopeAgree() {
        UUID payoutId = seedPayout();
        UUID otherPayout = seedPayout();
        actions.record(payoutId, "op-key-1", "CANCEL", "operator-1", "중복 요청", SUBMITTED_AT);

        assertThat(actions.matches(payoutId, "op-key-1", "CANCEL", "operator-1", "중복 요청")).isTrue();
        assertThat(actions.matches(payoutId, "op-key-1", "REQUEUE", "operator-1", "중복 요청")).isFalse();
        assertThat(actions.matches(payoutId, "op-key-1", "CANCEL", "operator-2", "중복 요청")).isFalse();
        assertThat(actions.matches(payoutId, "op-key-1", "CANCEL", "operator-1", "다른 사유")).isFalse();
        assertThat(actions.matches(payoutId, "op-key-2", "CANCEL", "operator-1", "중복 요청")).isFalse();
        assertThat(actions.matches(otherPayout, "op-key-1", "CANCEL", "operator-1", "중복 요청")).isFalse();
        assertThat(actions.record(otherPayout, "op-key-1", "REQUEUE", "operator-2", "재시도", SUBMITTED_AT)).isTrue();
    }

    @Test
    void findReturnsEmptyForUnknownDispatch() {
        assertThat(dispatches.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void enqueueCreatesPendingRowCarryingTheApprovalFacts() {
        UUID payoutId = seedPayout();
        PayoutDispatchPlan plan = plan(payoutId, 13_000);

        PayoutDispatchRecord record = dispatches.enqueue(plan);

        assertThat(record.payoutId()).isEqualTo(payoutId);
        assertThat(record.status()).isEqualTo("PENDING");
        assertThat(record.profileVersion()).isEqualTo(plan.profileVersion());
        assertThat(record.recipientEmailCiphertext()).isEqualTo(plan.recipientEmailCiphertext());
        assertThat(record.recipientCountryCode()).isEqualTo("US");
        assertThat(record.payoutCurrency()).isEqualTo("USD");
        assertThat(record.requestedAmountWon()).isEqualTo(13_000);
        assertThat(record.providerAmountMinor()).isEqualTo(1_000);
        assertThat(record.fxRateWonPerUsd()).isEqualByComparingTo("1300.50000000");
        assertThat(record.fxSource()).isEqualTo("ECB");
        assertThat(record.fxAsOfDate()).isEqualTo(LocalDate.of(2026, 2, 27));
        assertThat(record.providerDebitBookValueWon()).isEqualTo(13_005);
        assertThat(record.payoutFxRoundingExpenseWon()).isEqualTo(5);
        assertThat(record.senderBatchId()).isEqualTo(plan.senderBatchId());
        assertThat(record.senderItemId()).isEqualTo(plan.senderItemId());
        assertThat(record.paypalRequestId()).isEqualTo(plan.paypalRequestId());
        assertThat(record.requestPayloadSha256()).isEqualTo(plan.requestPayloadSha256());
        assertThat(record.attemptCount()).isZero();
        assertThat(record.leaseToken()).isNull();
        assertThat(record.leaseUntil()).isNull();
        assertThat(record.lastAttemptAt()).isNull();
        assertThat(record.providerBatchId()).isNull();
        assertThat(record.providerFeeMinor()).isNull();
        assertThat(dispatches.find(payoutId)).contains(record);
    }

    @Test
    void enqueueIsIdempotentAndNeverOverwritesApprovedFacts() {
        UUID payoutId = seedPayout();
        PayoutDispatchRecord first = dispatches.enqueue(plan(payoutId, 13_000));

        PayoutDispatchRecord replay = dispatches.enqueue(plan(payoutId, 13_000));
        PayoutDispatchRecord conflicting = dispatches.enqueue(plan(payoutId, 26_000));

        assertThat(replay).isEqualTo(first);
        assertThat(conflicting).isEqualTo(first);
        assertThat(conflicting.requestedAmountWon()).isEqualTo(13_000);
        assertThat(count(OUTBOX)).isEqualTo(1);
    }

    @Test
    void enqueueRejectsProviderRequestIdReusedByAnotherPayout() {
        UUID payoutId = seedPayout();
        UUID otherPayout = seedPayout();
        PayoutDispatchPlan first = plan(payoutId, 13_000);
        dispatches.enqueue(first);
        PayoutDispatchPlan clash = new PayoutDispatchPlan(otherPayout, 1, "v2:ciphertext", "US", 13_000, 1_000,
                new BigDecimal("1300.50000000"), "ECB", LocalDate.of(2026, 2, 27), 13_005, 5,
                "batch-" + otherPayout, "item-" + otherPayout, first.paypalRequestId(), "e".repeat(64));

        assertThatThrownBy(() -> dispatches.enqueue(clash)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(dispatches.find(otherPayout)).isEmpty();
        assertThat(count(OUTBOX)).isEqualTo(1);
    }

    @Test
    void claimLeasesDueRowsOldestFirstUpToTheLimit() {
        UUID oldest = enqueuePending();
        UUID middle = enqueuePending();
        UUID newest = enqueuePending();
        shiftAvailableAt(oldest, "-3 hours");
        shiftAvailableAt(middle, "-2 hours");
        shiftAvailableAt(newest, "-1 hour");

        List<PayoutDispatchRecord> firstClaim = dispatches.claim(2, LEASE);

        assertThat(firstClaim).extracting(PayoutDispatchRecord::payoutId).containsExactly(oldest, middle);
        assertThat(firstClaim).allSatisfy(leased -> {
            assertThat(leased.status()).isEqualTo("LEASED");
            assertThat(leased.attemptCount()).isEqualTo(1);
            assertThat(leased.leaseToken()).isNotNull();
            assertThat(leased.lastAttemptAt()).isNotNull();
            assertThat(leaseExpiresInAboutFiveMinutes(leased.payoutId())).isTrue();
        });
        assertThat(dispatches.claim(5, LEASE)).extracting(PayoutDispatchRecord::payoutId).containsExactly(newest);
        assertThat(dispatches.claim(5, LEASE)).isEmpty();
    }

    @Test
    void claimSkipsRowsThatAreNotDue() {
        UUID future = enqueuePending();
        UUID cancelled = enqueuePending();
        UUID completed = enqueuePending();
        UUID needsReview = enqueuePending();
        shiftAvailableAt(future, "1 hour");
        assertThat(dispatches.cancelPending(cancelled)).isTrue();
        forceStatus(completed, "COMPLETED");
        forceStatus(needsReview, "NEEDS_REVIEW");

        assertThat(dispatches.claim(10, LEASE)).isEmpty();

        assertThat(statusOf(future)).isEqualTo("PENDING");
    }

    @Test
    void claimReturnsNothingForNonPositiveLimitOrLeaseDuration() {
        UUID payoutId = enqueuePending();

        assertThat(dispatches.claim(0, LEASE)).isEmpty();
        assertThat(dispatches.claim(-1, LEASE)).isEmpty();
        assertThat(dispatches.claim(1, Duration.ZERO)).isEmpty();
        assertThat(dispatches.claim(1, Duration.ofSeconds(-5))).isEmpty();

        PayoutDispatchRecord untouched = dispatches.find(payoutId).orElseThrow();
        assertThat(untouched.status()).isEqualTo("PENDING");
        assertThat(untouched.attemptCount()).isZero();
    }

    @Test
    void claimReclaimsExpiredLeaseWithFreshTokenAndRejectsTheStaleWorker() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord firstLease = dispatches.claim(1, LEASE).getFirst();
        assertThat(dispatches.claim(1, LEASE)).isEmpty();
        jdbc.update("UPDATE " + OUTBOX + " SET lease_until = now() - interval '1 second' WHERE payout_id = ?",
                payoutId);

        PayoutDispatchRecord secondLease = dispatches.claim(1, LEASE).getFirst();

        assertThat(secondLease.payoutId()).isEqualTo(payoutId);
        assertThat(secondLease.leaseToken()).isNotEqualTo(firstLease.leaseToken());
        assertThat(secondLease.attemptCount()).isEqualTo(2);
        assertThat(dispatches.transition(firstLease, "COMPLETED", null)).isFalse();
        assertThat(dispatches.recordProviderEvidence(firstLease, "BATCH-1", "PENDING", "ITEM-1", "PENDING",
                null, null, null)).isFalse();
        assertThat(dispatches.reschedule(firstLease, Duration.ZERO, "STALE", "PENDING")).isFalse();
        assertThat(statusOf(payoutId)).isEqualTo("LEASED");
        assertThat(dispatches.transition(secondLease, "COMPLETED", null)).isTrue();
        assertThat(statusOf(payoutId)).isEqualTo("COMPLETED");
    }

    @Test
    void claimSkipsRowsLockedByAnotherTransaction() {
        UUID first = enqueuePending();
        UUID second = enqueuePending();
        shiftAvailableAt(first, "-2 hours");
        shiftAvailableAt(second, "-1 hour");
        PayoutDispatchStore otherWorker = new JdbcPayoutDispatchStore(new JdbcTemplate(newDataSource()));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        List<PayoutDispatchRecord> concurrentClaim = transaction.execute(status -> {
            PayoutDispatchRecord held = dispatches.claim(1, LEASE).getFirst();
            assertThat(held.payoutId()).isEqualTo(first);
            return otherWorker.claim(5, LEASE);
        });

        assertThat(concurrentClaim).extracting(PayoutDispatchRecord::payoutId).containsExactly(second);
        assertThat(statusOf(first)).isEqualTo("LEASED");
        assertThat(dispatches.find(first).orElseThrow().attemptCount()).isEqualTo(1);
    }

    @Test
    void transitionMovesLeasedRowToFinalStatusAndClearsTheLease() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord leased = dispatches.claim(1, LEASE).getFirst();

        assertThat(dispatches.transition(leased, "FAILED", "PROVIDER_REJECTED")).isTrue();

        PayoutDispatchRecord failed = dispatches.find(payoutId).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastErrorCode()).isEqualTo("PROVIDER_REJECTED");
        assertThat(failed.leaseToken()).isNull();
        assertThat(failed.leaseUntil()).isNull();
        assertThat(dispatches.transition(leased, "COMPLETED", null)).isFalse();
        assertThat(statusOf(payoutId)).isEqualTo("FAILED");
        assertThat(dispatches.claim(1, LEASE)).isEmpty();
    }

    @Test
    void transitionRejectsStatusOutsideTheDispatchLifecycleAndRowsThatAreNotLeased() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord pending = dispatches.find(payoutId).orElseThrow();
        PayoutDispatchRecord leased = dispatches.claim(1, LEASE).getFirst();

        assertThatThrownBy(() -> dispatches.transition(leased, "LEASED", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatches.transition(leased, "BOGUS", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dispatches.transition(pending, "COMPLETED", null)).isFalse();
        assertThat(statusOf(payoutId)).isEqualTo("LEASED");
    }

    @Test
    void rescheduleDelaysTheRowUntilAfterTheBackoff() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord leased = dispatches.claim(1, LEASE).getFirst();

        assertThat(dispatches.reschedule(leased, Duration.ofMinutes(10), "PROVIDER_TIMEOUT", "PENDING")).isTrue();

        PayoutDispatchRecord retry = dispatches.find(payoutId).orElseThrow();
        assertThat(retry.status()).isEqualTo("PENDING");
        assertThat(retry.lastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(retry.attemptCount()).isEqualTo(1);
        assertThat(retry.leaseToken()).isNull();
        assertThat(retry.leaseUntil()).isNull();
        assertThat(jdbc.queryForObject("SELECT available_at > now() + interval '9 minutes' FROM " + OUTBOX
                + " WHERE payout_id = ?", Boolean.class, payoutId)).isTrue();
        assertThat(dispatches.claim(1, LEASE)).isEmpty();
    }

    @Test
    void rescheduleWithNegativeDelayMakesTheRowImmediatelyDueAgain() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord leased = dispatches.claim(1, LEASE).getFirst();

        assertThat(dispatches.reschedule(leased, Duration.ofMinutes(-30), "RETRY_NOW", "PENDING")).isTrue();

        PayoutDispatchRecord reclaimed = dispatches.claim(1, LEASE).getFirst();
        assertThat(reclaimed.payoutId()).isEqualTo(payoutId);
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
    }

    @Test
    void rescheduleCanParkLeasedRowForReviewAndRejectsOtherStatuses() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord leased = dispatches.claim(1, LEASE).getFirst();

        assertThatThrownBy(() -> dispatches.reschedule(leased, Duration.ZERO, "X", "COMPLETED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatches.reschedule(leased, Duration.ZERO, "X", "LEASED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(statusOf(payoutId)).isEqualTo("LEASED");

        assertThat(dispatches.reschedule(leased, Duration.ZERO, "AMBIGUOUS_PROVIDER_RESULT", "NEEDS_REVIEW")).isTrue();

        assertThat(statusOf(payoutId)).isEqualTo("NEEDS_REVIEW");
        assertThat(dispatches.claim(1, LEASE)).isEmpty();
        assertThat(dispatches.reschedule(leased, Duration.ZERO, "AGAIN", "PENDING")).isFalse();
    }

    @Test
    void providerEvidenceIsStoredForTheCurrentLeaseAndSurvivesRescheduleToWaitingProvider() {
        UUID payoutId = enqueuePending();
        PayoutDispatchRecord leased = dispatches.claim(1, LEASE).getFirst();

        assertThat(dispatches.recordProviderEvidence(
                leased, "BATCH-1", "PROCESSING", "ITEM-1", "PENDING", 25L, "USD", 33L)).isTrue();
        assertThat(dispatches.recordProviderEvidence(
                leased, null, "SUCCESS", "ITEM-1", "SUCCESS", 25L, "USD", 33L)).isTrue();

        PayoutDispatchRecord withEvidence = dispatches.find(payoutId).orElseThrow();
        assertThat(withEvidence.providerBatchId()).isEqualTo("BATCH-1");
        assertThat(withEvidence.providerBatchStatus()).isEqualTo("SUCCESS");
        assertThat(withEvidence.providerItemId()).isEqualTo("ITEM-1");
        assertThat(withEvidence.providerItemStatus()).isEqualTo("SUCCESS");
        assertThat(withEvidence.providerFeeMinor()).isEqualTo(25L);
        assertThat(withEvidence.providerFeeCurrency()).isEqualTo("USD");
        assertThat(withEvidence.providerFeeWon()).isEqualTo(33L);

        assertThat(dispatches.reschedule(leased, Duration.ZERO, "PROVIDER_PENDING", "WAITING_PROVIDER")).isTrue();
        assertThat(dispatches.recordProviderEvidence(
                leased, "BATCH-2", "OTHER", "ITEM-2", "OTHER", null, null, null)).isFalse();

        PayoutDispatchRecord polled = dispatches.claim(1, LEASE).getFirst();
        assertThat(polled.payoutId()).isEqualTo(payoutId);
        assertThat(polled.providerBatchId()).isEqualTo("BATCH-1");
        assertThat(polled.attemptCount()).isEqualTo(2);
    }

    @Test
    void cancelPendingCancelsOnlyPendingRows() {
        UUID pending = enqueuePending();
        UUID leased = enqueuePending();
        UUID waiting = enqueuePending();
        UUID needsReview = enqueuePending();
        shiftAvailableAt(leased, "-1 hour");
        assertThat(dispatches.claim(1, LEASE).getFirst().payoutId()).isEqualTo(leased);
        forceWaitingProvider(waiting);
        forceStatus(needsReview, "NEEDS_REVIEW");

        assertThat(dispatches.cancelPending(pending)).isTrue();
        assertThat(dispatches.cancelPending(pending)).isFalse();
        assertThat(dispatches.cancelPending(leased)).isFalse();
        assertThat(dispatches.cancelPending(waiting)).isFalse();
        assertThat(dispatches.cancelPending(needsReview)).isFalse();
        assertThat(dispatches.cancelPending(UUID.randomUUID())).isFalse();

        assertThat(dispatches.find(pending).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(dispatches.find(pending).orElseThrow().lastErrorCode()).isEqualTo("OPERATOR_CANCELLED");
        assertThat(statusOf(leased)).isEqualTo("LEASED");
        assertThat(statusOf(waiting)).isEqualTo("WAITING_PROVIDER");
        assertThat(statusOf(needsReview)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void requeueNeedsReviewSendsRowWithoutProviderBatchBackToPending() {
        UUID payoutId = enqueuePending();
        parkInReview(payoutId, 3);

        assertThat(dispatches.requeueNeedsReview(payoutId)).isTrue();

        PayoutDispatchRecord requeued = dispatches.find(payoutId).orElseThrow();
        assertThat(requeued.status()).isEqualTo("PENDING");
        assertThat(requeued.attemptCount()).isZero();
        assertThat(requeued.lastErrorCode()).isEqualTo("OPERATOR_REQUEUED");
        assertThat(dispatches.claim(1, LEASE)).extracting(PayoutDispatchRecord::payoutId).containsExactly(payoutId);
    }

    @Test
    void requeueNeedsReviewKeepsProviderBatchByWaitingForTheProvider() {
        UUID payoutId = enqueuePending();
        parkInReview(payoutId, 2);
        jdbc.update("UPDATE " + OUTBOX + " SET provider_batch_id = 'BATCH-7' WHERE payout_id = ?", payoutId);

        assertThat(dispatches.requeueNeedsReview(payoutId)).isTrue();

        PayoutDispatchRecord requeued = dispatches.find(payoutId).orElseThrow();
        assertThat(requeued.status()).isEqualTo("WAITING_PROVIDER");
        assertThat(requeued.providerBatchId()).isEqualTo("BATCH-7");
        assertThat(requeued.attemptCount()).isZero();
    }

    @Test
    void requeueNeedsReviewIgnoresRowsThatAreNotInReview() {
        UUID pending = enqueuePending();
        UUID completed = enqueuePending();
        forceStatus(completed, "COMPLETED");

        assertThat(dispatches.requeueNeedsReview(pending)).isFalse();
        assertThat(dispatches.requeueNeedsReview(completed)).isFalse();
        assertThat(dispatches.requeueNeedsReview(UUID.randomUUID())).isFalse();

        assertThat(statusOf(pending)).isEqualTo("PENDING");
        assertThat(dispatches.find(pending).orElseThrow().lastErrorCode()).isNull();
        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
    }

    @Test
    void resolveNeedsReviewCompletesRowWithManualProviderEvidenceAndFee() {
        UUID payoutId = enqueuePending();
        parkInReview(payoutId, 1);

        assertThat(dispatches.resolveNeedsReview(
                payoutId, "COMPLETED", "BATCH-9", "ITEM-9", 25L, 33L, "MANUAL_COMPLETED")).isTrue();

        PayoutDispatchRecord resolved = dispatches.find(payoutId).orElseThrow();
        assertThat(resolved.status()).isEqualTo("COMPLETED");
        assertThat(resolved.providerBatchId()).isEqualTo("BATCH-9");
        assertThat(resolved.providerItemId()).isEqualTo("ITEM-9");
        assertThat(resolved.providerBatchStatus()).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(resolved.providerItemStatus()).isEqualTo("SUCCESS");
        assertThat(resolved.providerFeeMinor()).isEqualTo(25L);
        assertThat(resolved.providerFeeCurrency()).isEqualTo("USD");
        assertThat(resolved.providerFeeWon()).isEqualTo(33L);
        assertThat(resolved.lastErrorCode()).isEqualTo("MANUAL_COMPLETED");
        assertThat(dispatches.resolveNeedsReview(
                payoutId, "FAILED", null, null, null, null, "MANUAL_FAILED")).isFalse();
        assertThat(statusOf(payoutId)).isEqualTo("COMPLETED");
    }

    @Test
    void resolveNeedsReviewAsFailedKeepsExistingBatchAndRecordsNoFee() {
        UUID payoutId = enqueuePending();
        parkInReview(payoutId, 1);
        jdbc.update("UPDATE " + OUTBOX + " SET provider_batch_id = 'BATCH-1' WHERE payout_id = ?", payoutId);

        assertThat(dispatches.resolveNeedsReview(
                payoutId, "FAILED", null, null, null, null, "MANUAL_FAILED")).isTrue();

        PayoutDispatchRecord resolved = dispatches.find(payoutId).orElseThrow();
        assertThat(resolved.status()).isEqualTo("FAILED");
        assertThat(resolved.providerBatchId()).isEqualTo("BATCH-1");
        assertThat(resolved.providerItemId()).isNull();
        assertThat(resolved.providerItemStatus()).isEqualTo("FAILED");
        assertThat(resolved.providerFeeMinor()).isNull();
        assertThat(resolved.providerFeeCurrency()).isNull();
        assertThat(resolved.providerFeeWon()).isNull();
    }

    @Test
    void resolveNeedsReviewIgnoresRowsNotInReviewAndRejectsUnknownResult() {
        UUID pending = enqueuePending();
        UUID review = enqueuePending();
        parkInReview(review, 1);

        assertThat(dispatches.resolveNeedsReview(
                pending, "COMPLETED", "BATCH-1", "ITEM-1", null, null, "MANUAL_COMPLETED")).isFalse();
        assertThatThrownBy(() -> dispatches.resolveNeedsReview(
                review, "CANCELLED", null, null, null, null, "MANUAL_CANCELLED"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(statusOf(pending)).isEqualTo("PENDING");
        assertThat(dispatches.find(pending).orElseThrow().providerBatchId()).isNull();
        assertThat(statusOf(review)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void findNeedsReviewListsOnlyReviewRowsInEnqueueOrderWithPaging() {
        UUID oldest = enqueuePending();
        UUID middle = enqueuePending();
        UUID newest = enqueuePending();
        UUID pending = enqueuePending();
        forceStatus(oldest, "NEEDS_REVIEW");
        forceStatus(middle, "NEEDS_REVIEW");
        forceStatus(newest, "NEEDS_REVIEW");
        setFirstEnqueuedAt(oldest, "-3 hours");
        setFirstEnqueuedAt(middle, "-2 hours");
        setFirstEnqueuedAt(newest, "-1 hour");

        assertThat(dispatches.findNeedsReview(2, 0)).extracting(PayoutDispatchRecord::payoutId)
                .containsExactly(oldest, middle);
        assertThat(dispatches.findNeedsReview(2, 2)).extracting(PayoutDispatchRecord::payoutId)
                .containsExactly(newest);
        assertThat(dispatches.findNeedsReview(10, 3)).isEmpty();
        assertThat(dispatches.findNeedsReview(10, 0)).extracting(PayoutDispatchRecord::payoutId)
                .doesNotContain(pending);
    }

    @Test
    void metricsSnapshotCountsRowsByStatusAndMeasuresTheOldestUnfinishedRow() {
        UUID leased = enqueuePending();
        PayoutDispatchRecord lease = dispatches.claim(1, LEASE).getFirst();
        assertThat(lease.payoutId()).isEqualTo(leased);
        UUID pendingOne = enqueuePending();
        enqueuePending();
        UUID waiting = enqueuePending();
        UUID review = enqueuePending();
        UUID completed = enqueuePending();
        UUID failed = enqueuePending();
        UUID cancelled = enqueuePending();
        forceWaitingProvider(waiting);
        forceStatus(review, "NEEDS_REVIEW");
        forceStatus(completed, "COMPLETED");
        forceStatus(failed, "FAILED");
        forceStatus(cancelled, "CANCELLED");
        setFirstEnqueuedAt(pendingOne, "-2 hours");
        setFirstEnqueuedAt(completed, "-10 hours");
        setFirstEnqueuedAt(review, "-9 hours");

        PayoutDispatchMetrics metrics = dispatches.metricsSnapshot();

        assertThat(metrics.backlog()).isEqualTo(3);
        assertThat(metrics.leased()).isEqualTo(1);
        assertThat(metrics.needsReview()).isEqualTo(1);
        assertThat(metrics.completed()).isEqualTo(1);
        assertThat(metrics.failed()).isEqualTo(1);
        assertThat(metrics.oldestAgeSeconds()).isBetween(7_200.0, 7_320.0);
    }

    @Test
    void metricsSnapshotIsZeroForAnEmptyOutbox() {
        PayoutDispatchMetrics metrics = dispatches.metricsSnapshot();

        assertThat(metrics.backlog()).isZero();
        assertThat(metrics.leased()).isZero();
        assertThat(metrics.needsReview()).isZero();
        assertThat(metrics.completed()).isZero();
        assertThat(metrics.failed()).isZero();
        assertThat(metrics.oldestAgeSeconds()).isZero();
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID seedSeller() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        return sellerId;
    }

    private UUID seedUserAccount() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account(account_id, email, password_hash) VALUES (?, ?, ?)",
                accountId, accountId + "@example.test", "hash");
        return accountId;
    }

    private UUID seedAccount(UUID sellerId) {
        UUID accountId = seedUserAccount();
        jdbc.update("INSERT INTO seller_account(account_id, seller_id) VALUES (?, ?)", accountId, sellerId);
        return accountId;
    }

    private UUID seedPayout() {
        UUID sellerId = seedSeller();
        UUID payoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_payout (payout_id, seller_id, period_start, period_end, amount_won,
                    state, idempotency_key, requested_at, updated_at)
                VALUES (?, ?, '2026-02-01T00:00:00Z', '2026-03-01T00:00:00Z', 13000,
                    'APPROVED', ?, '2026-03-01T00:00:00Z', '2026-03-01T00:00:00Z')
                """, payoutId, sellerId, "payout-key-" + payoutId);
        return payoutId;
    }

    private UUID enqueuePending() {
        UUID payoutId = seedPayout();
        dispatches.enqueue(plan(payoutId, 13_000));
        return payoutId;
    }

    private static PayoutDispatchPlan plan(UUID payoutId, long requestedAmountWon) {
        return new PayoutDispatchPlan(payoutId, 1, "v2:ciphertext-" + payoutId, "US", requestedAmountWon, 1_000,
                new BigDecimal("1300.50000000"), "ECB", LocalDate.of(2026, 2, 27), requestedAmountWon + 5, 5,
                "batch-" + payoutId, "item-" + payoutId, "request-" + payoutId, "d".repeat(64));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String statusOf(UUID payoutId) {
        return jdbc.queryForObject("SELECT status FROM " + OUTBOX + " WHERE payout_id = ?", String.class, payoutId);
    }

    private boolean leaseExpiresInAboutFiveMinutes(UUID payoutId) {
        return jdbc.queryForObject("SELECT lease_until > now() + interval '270 seconds'"
                + " AND lease_until <= now() + interval '300 seconds' FROM " + OUTBOX + " WHERE payout_id = ?",
                Boolean.class, payoutId);
    }

    private void shiftAvailableAt(UUID payoutId, String interval) {
        jdbc.update("UPDATE " + OUTBOX + " SET available_at = now() + ?::interval WHERE payout_id = ?",
                interval, payoutId);
    }

    private void setFirstEnqueuedAt(UUID payoutId, String interval) {
        jdbc.update("UPDATE " + OUTBOX + " SET first_enqueued_at = now() + ?::interval WHERE payout_id = ?",
                interval, payoutId);
    }

    private void forceStatus(UUID payoutId, String status) {
        jdbc.update("UPDATE " + OUTBOX + " SET status = ? WHERE payout_id = ?", status, payoutId);
    }

    private void forceWaitingProvider(UUID payoutId) {
        jdbc.update("UPDATE " + OUTBOX + " SET status = 'WAITING_PROVIDER', provider_batch_id = 'BATCH-1'"
                + " WHERE payout_id = ?", payoutId);
    }

    private void parkInReview(UUID payoutId, int attempts) {
        jdbc.update("UPDATE " + OUTBOX + " SET status = 'NEEDS_REVIEW', attempt_count = ?,"
                + " last_error_code = 'AMBIGUOUS_PROVIDER_RESULT' WHERE payout_id = ?", attempts, payoutId);
    }
}
