package com.atlas.settlement.service;

import com.atlas.settlement.model.PayoutDispatchMetrics;
import com.atlas.settlement.model.PayoutDispatchPlan;
import com.atlas.settlement.model.PayoutDispatchRecord;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 지급 dispatch 요청과 처리 상태의 저장을 계약으로 노출합니다. SQL은 PayoutDispatchRepository에만 둔다. */
public interface PayoutDispatchStore {

    Optional<PayoutDispatchRecord> find(UUID payoutId);

    PayoutDispatchRecord enqueue(PayoutDispatchPlan p);

    List<PayoutDispatchRecord> claim(int limit, Duration leaseDuration);

    boolean reschedule(PayoutDispatchRecord r, Duration delay, String safeCode, String resultingStatus);

    boolean transition(PayoutDispatchRecord r, String status, String safeCode);

    boolean cancelPending(UUID payoutId);

    boolean requeueNeedsReview(UUID payoutId);

    boolean resolveNeedsReview(UUID payoutId, String result, String batchId, String itemId, Long feeMinor, Long feeWon, String safeCode);

    boolean recordProviderEvidence(PayoutDispatchRecord r, String batchId, String batchStatus, String itemId, String itemStatus, Long feeMinor, String feeCurrency, Long feeWon);

    PayoutDispatchMetrics metricsSnapshot();

    List<PayoutDispatchRecord> findNeedsReview(int limit, int offset);
}
