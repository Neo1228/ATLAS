package com.atlas.settlement.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PayPal 잔액 대사 run·row·disposition 보존 저장소 계약입니다. */
public interface PayPalBalanceReportStore {

    /** run과 rows를 하나의 transaction으로 저장하고, SHA-256 fingerprint 중복 시 빈 값을 반환합니다. */
    Optional<UUID> createRunIfAbsent(PayPalBalanceReportRun run, List<PayPalBalanceReportRow> rows);

    /** runId로 불변 run 요약을 조회합니다. */
    Optional<PayPalBalanceReportRun> findRunById(UUID runId);

    /** SHA-256 fingerprint로 불변 run 요약을 조회합니다. */
    Optional<PayPalBalanceReportRun> findRunBySha256(String reportSha256);

    /** 최신 run부터 최대 500건의 요약을 조회합니다. */
    List<PayPalBalanceReportRunSummary> listRunSummaries(int limit);

    /** run 순서대로 최대 10,000건의 row 사실을 조회합니다. */
    List<PayPalBalanceReportRow> listRows(UUID runId, int limit);

    /** ingestion replay를 위해 run의 전체 row 집합을 조회합니다. */
    List<PayPalBalanceReportRow> listAllRows(UUID runId);

    /** idempotency key가 없을 때 dispositions를 추가합니다. */
    Optional<UUID> insertDispositionIfAbsent(PayPalBalanceReportDisposition disposition);

    /** Idempotency-Key로 기존 disposition을 조회합니다. */
    Optional<PayPalBalanceReportDisposition> findDispositionByIdempotencyKey(String idempotencyKey);

    /** run과 row 번호로 disposition 이력을 반환합니다. */
    List<PayPalBalanceReportDisposition> findDispositionHistory(UUID runId, int rowNumber);
}
