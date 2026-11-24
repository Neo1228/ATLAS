package com.atlas.settlement.service.impl;

import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.dto.response.SettlementPayoutStatus;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.model.SellerBalance;
import com.atlas.settlement.model.SettlementPayoutState;
import com.atlas.settlement.persistence.projection.SettlementPayoutRow;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.repository.SettlementPayoutRepository;
import com.atlas.settlement.repository.SettlementRefundReservationRepository;
import com.atlas.settlement.service.SettlementPayoutService;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementPayoutServiceImpl implements SettlementPayoutService {
    private static final int MAX_PAYOUT_LIST = 100;

    private final SettlementJournalRepository journals;
    private final SettlementPayoutRepository payouts;
    private final SettlementRefundReservationRepository refundReservations;
    private final Clock clock;

    public SettlementPayoutServiceImpl(SettlementJournalRepository journals,
            SettlementPayoutRepository payouts,
            SettlementRefundReservationRepository refundReservations, Clock clock) {
        this.journals = Objects.requireNonNull(journals);
        this.payouts = Objects.requireNonNull(payouts);
        this.refundReservations = Objects.requireNonNull(refundReservations);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout request(UUID sellerId, Instant periodStart, Instant periodEnd,
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
        //
        // FAILED 지급의 처리:
        // - 같은 key 재전송: 같은 요청의 재시도이므로 그 지급을 상태(FAILED) 그대로 돌려준다.
        //   key 는 요청 하나를 식별하고 그 요청의 결과는 이미 FAILED 로 확정됐다. 새 지급을 만들면
        //   같은 key 가 두 결과를 갖게 되고(key unique 제약과도 충돌), REQUESTED 로 꾸며 돌려주면
        //   실패를 숨긴다. 응답의 state=FAILED 로 호출자는 실패를 알 수 있고, 재지급은 새 key 로 요청한다.
        // - 새 key 로 같은 기간 요청: FAILED 는 잔액을 예약하지 않으므로 기간을 막지 않는다.
        //   findBySellerAndPeriod 는 FAILED 를 제외하므로 아래 잔액 검사를 거쳐 새 지급이 생성된다.
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
            // 같은 기간에 FAILED 가 아닌 지급이 이미 있다. 그 지급을 돌려준다(중복 지급 금지).
            // FAILED 지급은 partial unique index 대상이 아니므로 여기서 돌려주면 안 된다.
            return payouts.findBySellerAndPeriod(sellerId, periodStart, periodEnd)
                    .map(SettlementPayoutServiceImpl::toDto)
                    .orElseThrow(() -> conflict("Payout already exists for this period"));
        }
        return toDto(payouts.find(created.get()).orElseThrow(
                () -> new IllegalStateException("Created payout disappeared")));
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout approve(UUID payoutId) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        return transition(payoutId, SettlementPayoutState.REQUESTED, SettlementPayoutState.APPROVED);
    }

    @Transactional
    @Override
    public boolean beginDispatch(UUID payoutId) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.CANCELLED) return false;
        if (payout.state() == SettlementPayoutStatus.DISPATCHING) return true;
        if (payout.state() != SettlementPayoutStatus.APPROVED
                && payout.state() != SettlementPayoutStatus.UNKNOWN
                && payout.state() != SettlementPayoutStatus.NEEDS_REVIEW) return false;
        SettlementPayoutState expected = persistenceState(payout);
        return payouts.transition(payoutId, expected, SettlementPayoutState.DISPATCHING, clock.instant())
                || requirePayout(payoutId).state() == SettlementPayoutStatus.DISPATCHING;
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout markProviderAccepted(UUID payoutId) {
        return providerTransition(payoutId, SettlementPayoutState.SENT,
                Set.of(SettlementPayoutState.DISPATCHING, SettlementPayoutState.UNKNOWN,
                        SettlementPayoutState.UNCLAIMED, SettlementPayoutState.NEEDS_REVIEW));
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout markProviderUnknown(UUID payoutId) {
        return providerTransition(payoutId, SettlementPayoutState.UNKNOWN,
                Set.of(SettlementPayoutState.DISPATCHING));
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout markProviderUnclaimed(UUID payoutId) {
        return providerTransition(payoutId, SettlementPayoutState.UNCLAIMED,
                Set.of(SettlementPayoutState.DISPATCHING, SettlementPayoutState.SENT,
                        SettlementPayoutState.UNKNOWN, SettlementPayoutState.NEEDS_REVIEW));
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout markProviderNeedsReview(UUID payoutId, String reasonCode) {
        validateFailureCode(reasonCode);
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.NEEDS_REVIEW) return payout;
        if (payout.state() != SettlementPayoutStatus.DISPATCHING
                && payout.state() != SettlementPayoutStatus.SENT
                && payout.state() != SettlementPayoutStatus.UNKNOWN
                && payout.state() != SettlementPayoutStatus.UNCLAIMED) {
            throw conflict("Only an externally attempted payout can require provider review");
        }
        if (payouts.markNeedsReview(payoutId, reasonCode, clock.instant())) return requirePayout(payoutId);
        PayoutResponse.SellerPayout latest = requirePayout(payoutId);
        if (latest.state() == SettlementPayoutStatus.NEEDS_REVIEW) return latest;
        throw conflict("Payout could not be moved to provider review");
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout failWithProviderEvidence(UUID payoutId, String failureCode) {
        validateFailureCode(failureCode);
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.FAILED) return payout;
        if (payout.state() != SettlementPayoutStatus.DISPATCHING
                && payout.state() != SettlementPayoutStatus.SENT
                && payout.state() != SettlementPayoutStatus.UNKNOWN
                && payout.state() != SettlementPayoutStatus.UNCLAIMED
                && payout.state() != SettlementPayoutStatus.NEEDS_REVIEW) {
            throw conflict("Provider failure evidence cannot fail this payout state");
        }
        if (payouts.failWithProviderEvidence(payoutId, failureCode, clock.instant())) {
            return requirePayout(payoutId);
        }
        PayoutResponse.SellerPayout latest = requirePayout(payoutId);
        if (latest.state() == SettlementPayoutStatus.FAILED) return latest;
        throw conflict("Provider evidence could not fail payout");
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout markSent(UUID payoutId) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        return transition(payoutId, SettlementPayoutState.APPROVED, SettlementPayoutState.SENT);
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout confirm(UUID payoutId) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.CONFIRMED) return payout;
        if (payout.state() != SettlementPayoutStatus.SENT) {
            throw conflict("Only a sent payout can be confirmed");
        }
        Instant now = clock.instant();
        if (!payouts.transition(payoutId, SettlementPayoutState.SENT, SettlementPayoutState.CONFIRMED, now)) {
            PayoutResponse.SellerPayout latest = requirePayout(payoutId);
            if (latest.state() == SettlementPayoutStatus.CONFIRMED) return latest;
            throw conflict("Payout could not be confirmed");
        }
        recordPayoutJournal(payout, 0, 0, now);
        return requirePayout(payoutId);
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout confirmProviderSuccess(UUID payoutId, long providerDebitBookValueWon,
            long fxRoundingExpenseWon, long providerFeeWon) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.CONFIRMED) {
            if (payouts.providerConfirmationMatches(
                    payoutId, providerDebitBookValueWon, fxRoundingExpenseWon, providerFeeWon)) return payout;
            throw conflict("Confirmed payout does not match provider settlement facts");
        }
        if (payout.state() != SettlementPayoutStatus.DISPATCHING
                && payout.state() != SettlementPayoutStatus.SENT
                && payout.state() != SettlementPayoutStatus.UNKNOWN
                && payout.state() != SettlementPayoutStatus.UNCLAIMED
                && payout.state() != SettlementPayoutStatus.NEEDS_REVIEW) {
            throw conflict("Only an externally attempted payout can be provider-confirmed");
        }
        if (fxRoundingExpenseWon < 0 || providerFeeWon < 0) {
            throw invalid("Provider fee and FX-rounding expense cannot be negative");
        }
        long expectedDebit;
        try {
            expectedDebit = Math.addExact(payout.amountWon(), fxRoundingExpenseWon);
        } catch (ArithmeticException overflow) {
            throw invalid("Provider payout book value exceeds the supported amount");
        }
        if (providerDebitBookValueWon != expectedDebit) {
            throw conflict("Provider debit book value does not match the payout and FX snapshot");
        }
        Instant now = clock.instant();
        if (!payouts.confirmWithProviderEvidence(
                payoutId, providerDebitBookValueWon, fxRoundingExpenseWon, providerFeeWon, now)) {
            PayoutResponse.SellerPayout latest = requirePayout(payoutId);
            if (latest.state() == SettlementPayoutStatus.CONFIRMED
                    && payouts.providerConfirmationMatches(
                            payoutId, providerDebitBookValueWon, fxRoundingExpenseWon, providerFeeWon)) {
                return latest;
            }
            throw conflict("Provider payout confirmation was rejected");
        }
        recordPayoutJournal(payout, fxRoundingExpenseWon, providerFeeWon, now);
        return requirePayout(payoutId);
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout fail(UUID payoutId, String failureCode) {
        validateFailureCode(failureCode);
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.FAILED) return payout;
        if (payout.state() != SettlementPayoutStatus.REQUESTED
                && payout.state() != SettlementPayoutStatus.APPROVED) {
            throw conflict("Only a payout with no external transfer attempt can be failed");
        }
        if (!payouts.fail(payoutId, failureCode, clock.instant())) return requirePayout(payoutId);
        return requirePayout(payoutId);
    }

    @Transactional
    @Override
    public PayoutResponse.SellerPayout cancelBeforeDispatch(UUID payoutId, String reasonCode) {
        validateFailureCode(reasonCode);
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        if (payout.state() == SettlementPayoutStatus.CANCELLED) return payout;
        if (payout.state() != SettlementPayoutStatus.REQUESTED
                && payout.state() != SettlementPayoutStatus.APPROVED) {
            throw conflict("Payout cannot be cancelled after provider dispatch begins");
        }
        if (payouts.cancelBeforeDispatch(payoutId, reasonCode, clock.instant())) return requirePayout(payoutId);
        PayoutResponse.SellerPayout latest = requirePayout(payoutId);
        if (latest.state() == SettlementPayoutStatus.CANCELLED) return latest;
        throw conflict("Payout cancellation was rejected");
    }

    @Override
    public PayoutResponse.SellerPayout find(UUID payoutId) {
        return requirePayout(payoutId);
    }

    @Override
    public PayoutResponse.SellerPayout findForSeller(UUID sellerId, UUID payoutId) {
        if (sellerId == null || payoutId == null) throw invalid("Seller and payout are required");
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        if (!payout.sellerId().equals(sellerId)) {
            throw notFound("Payout not found");
        }
        return payout;
    }

    @Override
    public List<PayoutResponse.SellerPayout> findBySeller(UUID sellerId, int limit) {
        if (sellerId == null) throw invalid("Seller is required");
        int bounded = limit <= 0 ? 20 : Math.min(limit, MAX_PAYOUT_LIST);
        return payouts.findBySeller(sellerId, bounded).stream()
                .map(SettlementPayoutServiceImpl::toDto).toList();
    }

    @Override
    public SellerBalance balance(UUID sellerId) {
        if (sellerId == null) throw invalid("Seller is required");
        return journals.sellerBalance(sellerId);
    }

    @Override
    public PayoutResponse.Summary summary(UUID sellerId) {
        SellerBalance balance = balance(sellerId);
        long pendingPayout = payouts.pendingAmount(sellerId);
        long pendingRefund = refundReservations.pendingAmount(sellerId);
        BigInteger available = balance.payableAmountWon()
                .subtract(BigInteger.valueOf(pendingPayout).add(BigInteger.valueOf(pendingRefund)))
                .max(BigInteger.ZERO);
        return new PayoutResponse.Summary(balance.payableAmountWon().longValueExact(), available.longValueExact(),
                balance.reserveAmountWon().longValueExact(), balance.feeAmountWon().longValueExact(),
                pendingPayout, pendingRefund, payouts.confirmedAmount(sellerId));
    }

    @Override
    public PayoutResponse.RiskSnapshot riskSnapshot() {
        var exposure = payouts.negativePayableExposure();
        var refunds = refundReservations.metricsSnapshot();
        return new PayoutResponse.RiskSnapshot(exposure.sellerCount(), exposure.exposureAmountWon(),
                refunds.reservationCount(), refunds.amountWon());
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
        BigInteger alreadyReserved = BigInteger.valueOf(payouts.pendingAmount(sellerId))
                .add(BigInteger.valueOf(refundReservations.pendingAmount(sellerId)));
        // 원장 payable 에서 미확정 payout 과 refund commitment 를 빼야 이중 예약을 막는다.
        return balance.payableAmountWon().subtract(alreadyReserved)
                .max(BigInteger.ZERO).longValueExact();
    }

    private PayoutResponse.SellerPayout transition(UUID payoutId, SettlementPayoutState expected,
            SettlementPayoutState next) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        SettlementPayoutStatus expectedStatus = SettlementPayoutStatus.valueOf(expected.name());
        SettlementPayoutStatus nextStatus = SettlementPayoutStatus.valueOf(next.name());
        if (payout.state() == nextStatus) return payout;
        if (payout.state() != expectedStatus) {
            throw conflict("Payout is in " + payout.state() + ", expected " + expectedStatus);
        }
        if (!payouts.transition(payoutId, expected, next, clock.instant())) {
            PayoutResponse.SellerPayout latest = requirePayout(payoutId);
            if (latest.state() == nextStatus) return latest;
            throw conflict("Payout transition was rejected");
        }
        return requirePayout(payoutId);
    }

    private PayoutResponse.SellerPayout providerTransition(UUID payoutId, SettlementPayoutState next,
            Set<SettlementPayoutState> allowed) {
        PayoutResponse.SellerPayout payout = requirePayout(payoutId);
        journals.lockSellerPayouts(payout.sellerId());
        payout = requirePayout(payoutId);
        SettlementPayoutStatus nextStatus = SettlementPayoutStatus.valueOf(next.name());
        if (payout.state() == nextStatus) return payout;
        SettlementPayoutState currentState = persistenceState(payout);
        if (!allowed.contains(currentState)) {
            throw conflict("Provider transition is not allowed from " + payout.state());
        }
        if (payouts.transition(payoutId, currentState, next, clock.instant())) return requirePayout(payoutId);
        PayoutResponse.SellerPayout latest = requirePayout(payoutId);
        if (latest.state() == nextStatus) return latest;
        throw conflict("Provider payout transition was rejected");
    }

    private static SettlementPayoutState persistenceState(PayoutResponse.SellerPayout payout) {
        return SettlementPayoutState.valueOf(payout.state().name());
    }

    /**
     * 지급 확정 분개: SELLER_PAYABLE 차변 / PSP_CLEARING 대변.
     *
     * <p>Payout 은 별도 journal 로 기록하고 source_payout_id 로 연결해, 지급 1건이 분개 1건임을
     * DB 제약으로 보장한다.
     */
    private void recordPayoutJournal(PayoutResponse.SellerPayout payout, long fxRoundingExpenseWon,
            long providerFeeWon, Instant now) {
        long clearingCredit;
        try {
            clearingCredit = Math.addExact(Math.addExact(payout.amountWon(), fxRoundingExpenseWon), providerFeeWon);
        } catch (ArithmeticException overflow) {
            throw invalid("Payout journal amount exceeds the supported amount");
        }
        UUID journalId = journals.insertPayoutIfAbsent(payout.payoutId(), payout.sellerId(),
                payout.amountWon(), now);
        if (journalId == null) {
            throw new IllegalStateException("Payout journal was not persisted");
        }
        journals.insertEntry(journalId, payout.sellerId(), "SELLER_PAYABLE", "DEBIT", payout.amountWon(), now);
        if (fxRoundingExpenseWon > 0) {
            journals.insertEntry(journalId, payout.sellerId(), "PLATFORM_PAYOUT_FX_ROUNDING",
                    "DEBIT", fxRoundingExpenseWon, now);
        }
        if (providerFeeWon > 0) {
            journals.insertEntry(journalId, payout.sellerId(), "PLATFORM_PAYOUT_FEE", "DEBIT",
                    providerFeeWon, now);
        }
        journals.insertEntry(journalId, payout.sellerId(), "PSP_CLEARING", "CREDIT", clearingCredit, now);
    }

    private PayoutResponse.SellerPayout requirePayout(UUID payoutId) {
        if (payoutId == null) throw invalid("Payout is required");
        return toDto(payouts.find(payoutId).orElseThrow(() -> notFound("Payout not found")));
    }

    private static PayoutResponse.SellerPayout toDto(SettlementPayoutRow row) {
        return new PayoutResponse.SellerPayout(row.payoutId(), row.sellerId(), row.periodStart(),
                row.periodEnd(), row.amountWon(), row.settledAmountWon(),
                SettlementPayoutStatus.valueOf(row.state().name()), row.requestedAt(),
                row.sentAt(), row.confirmedAt(), row.cancelledAt());
    }

    private static PayoutResponse.SellerPayout requireSameFacts(PayoutResponse.SellerPayout prior, UUID sellerId,
            Instant periodStart, Instant periodEnd) {
        if (!prior.sellerId().equals(sellerId) || !prior.periodStart().equals(periodStart)
                || !prior.periodEnd().equals(periodEnd)) {
            throw conflict("Idempotency key was used for a different payout");
        }
        return prior;
    }

    private static void validateFailureCode(String failureCode) {
        if (failureCode == null || !failureCode.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid("Payout failure code must be a short alphanumeric identifier");
        }
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
