package com.atlas.payment.repository;

import com.atlas.payment.model.ProviderAdjustmentKind;
import com.atlas.payment.model.ProviderAdjustmentStatus;
import com.atlas.payment.persistence.projection.DisputeHold;
import com.atlas.payment.persistence.projection.ProviderAdjustment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** PayPal 쪽에서 시작된 사건(payment_provider_adjustment, V16). */
@Repository
public class PaymentProviderAdjustmentRepository {
    private static final RowMapper<ProviderAdjustment> ROW_MAPPER = (resultSet, rowNumber) -> new ProviderAdjustment(
            resultSet.getObject("adjustment_id", UUID.class),
            ProviderAdjustmentKind.valueOf(resultSet.getString("kind")),
            resultSet.getString("provider_resource_id"),
            resultSet.getObject("attempt_id", UUID.class),
            resultSet.getObject("amount_won", Long.class),
            ProviderAdjustmentStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("review_reason"));

    private final JdbcTemplate jdbc;

    public PaymentProviderAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** 환불·역전은 같은 PayPal refund id 공간을 쓰므로 종류와 무관하게 찾는다. */
    public Optional<ProviderAdjustment> findMoneyByResourceIdForUpdate(String providerResourceId) {
        return jdbc.query("""
                SELECT * FROM payment_provider_adjustment
                WHERE provider_resource_id = ? AND kind IN ('REFUND', 'REVERSAL')
                FOR UPDATE
                """, ROW_MAPPER, providerResourceId).stream().findFirst();
    }

    public Optional<ProviderAdjustment> findById(UUID adjustmentId) {
        return jdbc.query("SELECT * FROM payment_provider_adjustment WHERE adjustment_id = ?",
                ROW_MAPPER, adjustmentId).stream().findFirst();
    }

    /** 환불·역전 한 건을 기록한다. 같은 refund id 가 이미 있으면 기록하지 않고 false. */
    public boolean insertMoney(UUID adjustmentId, ProviderAdjustmentKind kind, String providerResourceId,
            UUID attemptId, String captureId, Long providerAmountMinor, String providerCurrency, Long amountWon,
            String providerStatus, ProviderAdjustmentStatus status, String reviewReason, String eventId, Instant now) {
        if (!kind.movesMoney()) throw new IllegalArgumentException("Only refund or reversal moves money");
        return jdbc.update("""
                INSERT INTO payment_provider_adjustment (
                    adjustment_id, kind, provider_resource_id, attempt_id, capture_id,
                    provider_amount_minor, provider_currency, amount_won, provider_status,
                    status, review_reason, first_event_id, last_event_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, adjustmentId, kind.name(), providerResourceId, attemptId, captureId, providerAmountMinor,
                providerCurrency, amountWon, providerStatus, status.name(), reviewReason, eventId, eventId,
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /** 돈을 움직이지 않는 사건(거절·분쟁)을 검토 대기로 둔다. 같은 자원의 후속 사건은 마지막 상태만 갱신한다. */
    public void upsertReview(ProviderAdjustmentKind kind, String providerResourceId, UUID attemptId,
            String captureId, Long providerAmountMinor, String providerCurrency, String providerStatus,
            String reviewReason, String eventId, Instant now) {
        if (kind.movesMoney()) throw new IllegalArgumentException("Refund or reversal must use insertMoney");
        jdbc.update("""
                INSERT INTO payment_provider_adjustment (
                    adjustment_id, kind, provider_resource_id, attempt_id, capture_id,
                    provider_amount_minor, provider_currency, provider_status,
                    status, review_reason, first_event_id, last_event_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REVIEW', ?, ?, ?, ?, ?)
                ON CONFLICT (kind, provider_resource_id) DO UPDATE
                SET provider_status = EXCLUDED.provider_status,
                    last_event_id = EXCLUDED.last_event_id,
                    updated_at = EXCLUDED.updated_at
                """, UUID.randomUUID(), kind.name(), providerResourceId, attemptId, captureId, providerAmountMinor,
                providerCurrency, providerStatus, reviewReason, eventId, eventId,
                Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<ProviderAdjustment> findDisputeForUpdate(String disputeId) {
        return jdbc.query("""
                SELECT * FROM payment_provider_adjustment
                WHERE kind = 'DISPUTE' AND provider_resource_id = ?
                FOR UPDATE
                """, ROW_MAPPER, disputeId).stream().findFirst();
    }

    /** 분쟁의 첫 사건을 기록한다. 같은 분쟁이 이미 있으면 기록하지 않고 false. */
    public boolean insertDispute(UUID adjustmentId, String disputeId, UUID attemptId, String captureId,
            Long providerAmountMinor, String providerCurrency, Long amountWon, String providerStatus,
            ProviderAdjustmentStatus status, String reviewReason, String eventId, Instant now) {
        return jdbc.update("""
                INSERT INTO payment_provider_adjustment (
                    adjustment_id, kind, provider_resource_id, attempt_id, capture_id,
                    provider_amount_minor, provider_currency, amount_won, provider_status,
                    status, review_reason, first_event_id, last_event_id, created_at, updated_at)
                VALUES (?, 'DISPUTE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (kind, provider_resource_id) DO NOTHING
                """, adjustmentId, disputeId, attemptId, captureId, providerAmountMinor, providerCurrency, amountWon,
                providerStatus, status.name(), reviewReason, eventId, eventId,
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /**
     * 진행 중({@code HOLD})인 분쟁만 다음 상태로 옮긴다. 끝난 분쟁(차감·종료·검토)은 늦게 온 사건이 되살리지 못한다.
     * 바뀌었으면 true.
     */
    public boolean updateHeldDispute(UUID adjustmentId, Long providerAmountMinor, String providerCurrency,
            Long amountWon, String providerStatus, ProviderAdjustmentStatus status, String reviewReason,
            String eventId, Instant now) {
        return jdbc.update("""
                UPDATE payment_provider_adjustment
                SET provider_amount_minor = ?, provider_currency = ?, amount_won = ?, provider_status = ?,
                    status = ?, review_reason = ?, last_event_id = ?, updated_at = ?
                WHERE adjustment_id = ? AND status = 'HOLD'
                """, providerAmountMinor, providerCurrency, amountWon, providerStatus, status.name(), reviewReason,
                eventId, Timestamp.from(now), adjustmentId) == 1;
    }

    /** 끝난 분쟁에 늦게 온 사건은 마지막 provider 상태만 남긴다. */
    public void touchDispute(UUID adjustmentId, String providerStatus, String eventId, Instant now) {
        jdbc.update("""
                UPDATE payment_provider_adjustment SET provider_status = ?, last_event_id = ?, updated_at = ?
                WHERE adjustment_id = ?
                """, providerStatus, eventId, Timestamp.from(now), adjustmentId);
    }

    /** PayPal 이 시작한 capture 역전 중 원장에 넣은(넣는 중인) 금액 합. 분쟁 차감이 같은 돈을 두 번 빼지 않게 쓴다. */
    public long reversedMinorForAttempt(UUID attemptId) {
        Long minor = jdbc.queryForObject("""
                SELECT COALESCE(SUM(provider_amount_minor), 0) FROM payment_provider_adjustment
                WHERE attempt_id = ? AND kind = 'REVERSAL' AND status IN ('POSTING', 'POSTED')
                """, Long.class, attemptId);
        return minor == null ? 0 : minor;
    }

    /** 이 결제 시도에 판매자 패소로 차감한 분쟁이 있는가. */
    public boolean hasDebitedDispute(UUID attemptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payment_provider_adjustment
                    WHERE attempt_id = ? AND kind = 'DISPUTE' AND status IN ('POSTING', 'POSTED'))
                """, Boolean.class, attemptId));
    }

    /** 진행 중인 분쟁과 그 결제 시도의 주인(order 또는 checkout). 부분 인덱스 {@code payment_provider_adjustment_dispute_hold}. */
    public List<DisputeHold> heldDisputes() {
        return jdbc.query("""
                SELECT adjustment.adjustment_id, attempt.order_id, attempt.checkout_id, adjustment.amount_won
                FROM payment_provider_adjustment adjustment
                JOIN payment_attempt attempt ON attempt.attempt_id = adjustment.attempt_id
                WHERE adjustment.status = 'HOLD'
                ORDER BY adjustment.adjustment_id
                """, (resultSet, rowNumber) -> new DisputeHold(
                        resultSet.getObject("adjustment_id", UUID.class),
                        resultSet.getObject("order_id", UUID.class),
                        resultSet.getObject("checkout_id", UUID.class),
                        resultSet.getLong("amount_won")));
    }

    public boolean markPosted(UUID adjustmentId, Instant now) {
        return jdbc.update("""
                UPDATE payment_provider_adjustment SET status = 'POSTED', updated_at = ?
                WHERE adjustment_id = ? AND status = 'POSTING'
                """, Timestamp.from(now), adjustmentId) == 1;
    }

    public boolean markReview(UUID adjustmentId, String reviewReason, Instant now) {
        return jdbc.update("""
                UPDATE payment_provider_adjustment SET status = 'REVIEW', review_reason = ?, updated_at = ?
                WHERE adjustment_id = ? AND status = 'POSTING'
                """, reviewReason, Timestamp.from(now), adjustmentId) == 1;
    }
}
