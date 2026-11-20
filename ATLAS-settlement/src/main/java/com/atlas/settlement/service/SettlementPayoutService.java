package com.atlas.settlement.service;

import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.persistence.projection.SellerBalance;
import com.atlas.settlement.persistence.projection.SettlementPayoutRow;
import com.atlas.settlement.persistence.state.SettlementPayoutState;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.repository.SettlementPayoutRepository;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 지급 실행.
 *
 * <p>지급은 정산 원장의 잔액을 실제로 옮기는 단계다. 지급이 확정되면 같은 금액의 분개
 * (SELLER_PAYABLE 차변 / PSP_CLEARING 대변)를 만들어, 원장이 "지급할 돈"에서 "지급한 돈"으로
 * 넘어가게 한다. 지급 자체를 원장 밖 상태로만 두면 statement 잔액이 영원히 줄지 않는다.
 *
 * <p>상태 전이는 조건부 UPDATE 로만 한다. 두 replica 가 동시에 확정을 시도해도 하나만 성공한다.
 */
@Service
public class SettlementPayoutService {
    private static final int MAX_PAYOUT_LIST = 100;

    private final SettlementJournalRepository journals;
    private final SettlementPayoutRepository payouts;
    private final Clock clock;

    public SettlementPayoutService(SettlementJournalRepository journals,
            SettlementPayoutRepository payouts, Clock clock) {
        this.journals = Objects.requireNonNull(journals);
        this.payouts = Objects.requireNonNull(payouts);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 지급을 요청한다. 금액은 호출자가 정하지 않고, 현재 지급 가능 잔액에서 계산한다.
     *
     * <p>이유: 호출자가 금액을 정하면 잔액보다 큰 지급을 요청할 수 있다. 지급 금액은
     * 원장 잔액이 결정해야 한다.
     */
    @Transactional
    public SellerPayoutResponse request(UUID sellerId, Instant periodStart, Instant periodEnd,
            String idempotencyKey) {
        if (sellerId == null || periodStart == null || periodEnd == null || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw invalid("Seller, period, and an idempotency key of at most 128 characters are required");
        }
        if (!periodEnd.isAfter(periodStart)) {
            throw invalid("Payout period end must be after start");
        }
        // 같은 seller 의 지급 요청을 직렬화한다. 잔액 확인과 예약 사이에 다른 요청이 끼어들면
        // 같은 잔액을 두 번 예약할 수 있다(원장 payable 은 확정 시점에만 줄어든다).
        journals.lockSellerPayouts(sellerId);
        // 같은 기간 재요청은 멱등이다. idempotency key 또는 같은 (seller, 기간) 이 이미 있으면
        // 새 지급을 만들지 않고 그 지급을 돌려준다. 이 검사가 잔액 검사보다 먼저여야 한다:
        // 첫 요청이 그 잔액을 이미 예약했으므로, 잔액부터 보면 "잔액 없음"으로 잘못 실패한다.
        Optional<SettlementPayoutRow> prior = payouts.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) return requireSameFacts(toDto(prior.get()), sellerId, periodStart, periodEnd);
        Optional<SettlementPayoutRow> samePeriod = payouts.findBySellerAndPeriod(sellerId, periodStart, periodEnd);
        if (samePeriod.isPresent()) {
            return requireSameFacts(toDto(samePeriod.get()), sellerId, periodStart, periodEnd);
        }

        long amount = payable(sellerId);
        if (amount <= 0) {
            throw conflict("Seller has no payable balance to pay out");
        }
        Instant now = clock.instant();
        Optional<UUID> created = payouts.request(sellerId, periodStart, periodEnd, amount,
                idempotencyKey, now);
        if (created.isEmpty()) {
            // 같은 기간에 이미 지급이 있다. 그 지급을 돌려준다(중복 지급 금지).
            return payouts.findBySeller(sellerId, MAX_PAYOUT_LIST).stream()
                    .filter(payout -> payout.periodStart().equals(periodStart)
                            && payout.periodEnd().equals(periodEnd))
                    .map(SettlementPayoutService::toDto)
                    .findFirst()
                    .orElseThrow(() -> conflict("Payout already exists for this period"));
        }
        return toDto(payouts.find(created.get()).orElseThrow(
                () -> new IllegalStateException("Created payout disappeared")));
    }

    /** 지급을 승인한다. 외부 송금 전 검토 단계다. */
    @Transactional
    public SellerPayoutResponse approve(UUID payoutId) {
        return transition(payoutId, SettlementPayoutState.REQUESTED, SettlementPayoutState.APPROVED);
    }

    /** 지급을 송금했다고 표시한다. 실제 송금 결과를 받은 뒤 호출한다. */
    @Transactional
    public SellerPayoutResponse markSent(UUID payoutId) {
        return transition(payoutId, SettlementPayoutState.APPROVED, SettlementPayoutState.SENT);
    }

    /**
     * 지급을 확정하고 원장에 분개한다.
     *
     * <p>분개와 상태 전이를 같은 transaction 에서 수행한다. 둘 중 하나만 남으면 잔액과 지급
     * 이력이 어긋난다.
     */
    @Transactional
    public SellerPayoutResponse confirm(UUID payoutId) {
        SellerPayoutResponse payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutState.CONFIRMED) return payout;
        if (payout.state() != SettlementPayoutState.SENT) {
            throw conflict("Only a sent payout can be confirmed");
        }
        Instant now = clock.instant();
        if (!payouts.transition(payoutId, SettlementPayoutState.SENT, SettlementPayoutState.CONFIRMED, now)) {
            // 다른 요청이 먼저 확정했다. 현재 상태를 돌려준다(멱등).
            SellerPayoutResponse latest = requirePayout(payoutId);
            if (latest.state() == SettlementPayoutState.CONFIRMED) return latest;
            throw conflict("Payout could not be confirmed");
        }
        recordPayoutJournal(payout, now);
        return requirePayout(payoutId);
    }

    @Transactional
    public SellerPayoutResponse fail(UUID payoutId, String failureCode) {
        SellerPayoutResponse payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutState.CONFIRMED) {
            throw conflict("A confirmed payout cannot be failed");
        }
        if (!payouts.fail(payoutId, failureCode, clock.instant())) {
            return requirePayout(payoutId);
        }
        return requirePayout(payoutId);
    }

    public SellerPayoutResponse find(UUID payoutId) {
        return requirePayout(payoutId);
    }

    /**
     * 판매자 소유 지급만 돌려준다.
     *
     * <p>소유권 검증을 controller 가 아니라 service 에 두는 이유: controller 는 HTTP binding 과
     * response mapping 만 담당하고, 존재 여부 비노출(404) 판단은 도메인 규칙이다.
     */
    public SellerPayoutResponse findForSeller(UUID sellerId, UUID payoutId) {
        if (sellerId == null || payoutId == null) throw invalid("Seller and payout are required");
        SellerPayoutResponse payout = requirePayout(payoutId);
        if (!payout.sellerId().equals(sellerId)) {
            throw notFound("Payout not found");
        }
        return payout;
    }

    public List<SellerPayoutResponse> findBySeller(UUID sellerId, int limit) {
        if (sellerId == null) throw invalid("Seller is required");
        int bounded = limit <= 0 ? 20 : Math.min(limit, MAX_PAYOUT_LIST);
        return payouts.findBySeller(sellerId, bounded).stream()
                .map(SettlementPayoutService::toDto).toList();
    }

    /** 지급 가능 잔액과 보류·수수료를 함께 돌려준다. */
    public SellerBalance balance(UUID sellerId) {
        if (sellerId == null) throw invalid("Seller is required");
        return journals.sellerBalance(sellerId);
    }

    /** 아직 확정되지 않은 지급과 이미 확정된 지급을 함께 돌려준다. */
    public PayoutSummary summary(UUID sellerId) {
        SellerBalance balance = balance(sellerId);
        return new PayoutSummary(balance.payableAmountWon().longValueExact(),
                balance.reserveAmountWon().longValueExact(),
                balance.feeAmountWon().longValueExact(),
                payouts.pendingAmount(sellerId), payouts.confirmedAmount(sellerId));
    }

    /**
     * 지금 새로 지급할 수 있는 금액.
     *
     * <p>원장 payable 잔액에서 <b>아직 확정되지 않은 지급</b>을 뺀다. 지급 journal 은 확정
     * 시점에만 기록되므로, 이 차감이 없으면 서로 다른 기간의 지급 요청이 같은 잔액을 각각
     * 예약해 총 지급액이 잔액을 넘는다.
     *
     * <p>보류금(SELLER_RESERVE)은 지급 대상이 아니다.
     */
    private long payable(UUID sellerId) {
        SellerBalance balance = journals.sellerBalance(sellerId);
        long alreadyReserved = payouts.pendingAmount(sellerId);
        // 원장 payable 에서 아직 확정되지 않은 지급을 뺀 값이 새로 지급할 수 있는 금액이다.
        long available = balance.payableAmountWon().subtract(BigInteger.valueOf(alreadyReserved))
                .max(BigInteger.ZERO).longValueExact();
        return available;
    }

    private SellerPayoutResponse transition(UUID payoutId, SettlementPayoutState expected,
            SettlementPayoutState next) {
        SellerPayoutResponse payout = requirePayout(payoutId);
        if (payout.state() == next) return payout;
        if (payout.state() != expected) {
            throw conflict("Payout is in " + payout.state() + ", expected " + expected);
        }
        if (!payouts.transition(payoutId, expected, next, clock.instant())) {
            SellerPayoutResponse latest = requirePayout(payoutId);
            if (latest.state() == next) return latest;
            throw conflict("Payout transition was rejected");
        }
        return requirePayout(payoutId);
    }

    /**
     * 지급 확정 분개: SELLER_PAYABLE 차변 / PSP_CLEARING 대변.
     *
     * <p>Payout 은 별도 journal 로 기록하고 source_payout_id 로 연결해, 지급 1건이 분개 1건임을
     * DB 제약으로 보장한다.
     */
    private void recordPayoutJournal(SellerPayoutResponse payout, Instant now) {
        UUID journalId = journals.insertPayoutIfAbsent(payout.payoutId(), payout.sellerId(),
                payout.amountWon(), now);
        if (journalId == null) {
            throw new IllegalStateException("Payout journal was not persisted");
        }
        journals.insertEntry(journalId, payout.sellerId(), "SELLER_PAYABLE", "DEBIT",
                payout.amountWon(), now);
        journals.insertEntry(journalId, payout.sellerId(), "PSP_CLEARING", "CREDIT",
                payout.amountWon(), now);
    }

    private SellerPayoutResponse requirePayout(UUID payoutId) {
        if (payoutId == null) throw invalid("Payout is required");
        return toDto(payouts.find(payoutId).orElseThrow(() -> notFound("Payout not found")));
    }

    private static SellerPayoutResponse toDto(SettlementPayoutRow row) {
        return new SellerPayoutResponse(row.payoutId(), row.sellerId(), row.periodStart(),
                row.periodEnd(), row.amountWon(), row.state(), row.requestedAt(), row.sentAt(),
                row.confirmedAt());
    }

    private static SellerPayoutResponse requireSameFacts(SellerPayoutResponse prior, UUID sellerId,
            Instant periodStart, Instant periodEnd) {
        if (!prior.sellerId().equals(sellerId) || !prior.periodStart().equals(periodStart)
                || !prior.periodEnd().equals(periodEnd)) {
            throw conflict("Idempotency key was used for a different payout");
        }
        return prior;
    }

    /** 판매자 지급 현황. 금액 의미를 응답에서 바로 읽을 수 있게 나눈다. */
    public record PayoutSummary(long payableAmountWon, long reserveAmountWon, long feeAmountWon,
            long pendingPayoutAmountWon, long confirmedPayoutAmountWon) {
    }

    private static SettlementException invalid(String message) {
        return new SettlementException(SettlementException.Code.INVALID_REQUEST, message);
    }

    private static SettlementException conflict(String message) {
        return new SettlementException(SettlementException.Code.CONFLICT, message);
    }

    private static SettlementException notFound(String message) {
        return new SettlementException(SettlementException.Code.NOT_FOUND, message);
    }
}
