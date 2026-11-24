package com.atlas.settlement.service;

import com.atlas.settlement.model.PayPalBalanceReportDailyTrend;
import com.atlas.settlement.model.PayPalBalanceReportDisposition;
import com.atlas.settlement.model.PayPalBalanceReportExceptionRow;
import com.atlas.settlement.model.PayPalBalanceReportRow;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import com.atlas.settlement.model.PayPalBalanceReportRun;
import java.time.LocalDate;
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

    /** 최근 run부터 한 페이지를 조회합니다. */
    List<PayPalBalanceReportRun> listRuns(int limit, int offset);

    /** ingestion replay를 위해 run의 전체 row 집합을 조회합니다. */
    List<PayPalBalanceReportRow> listAllRows(UUID runId);

    /** idempotency key가 없을 때 dispositions를 추가합니다. */
    Optional<UUID> insertDispositionIfAbsent(PayPalBalanceReportDisposition disposition);

    /** Idempotency-Key로 기존 disposition을 조회합니다. */
    Optional<PayPalBalanceReportDisposition> findDispositionByIdempotencyKey(String idempotencyKey);

    /** run과 row 번호로 disposition 이력을 오래된 순서로 최대 limit건 반환합니다. */
    List<PayPalBalanceReportDisposition> findDispositionHistory(UUID runId, int rowNumber, int limit);

    /** 최근 30일(오늘 포함) 일별 UNMATCHED, MISMATCH 합계입니다. 업로드가 없는 날은 0입니다. */
    List<PayPalBalanceReportDailyTrend> dailyTrend();

    /** 운영자 처리가 아직 없는 예외 행 수입니다. */
    long countPendingExceptions();

    /** 모든 run의 예외 행입니다. status, 업로드 기간, recordId는 null이면 조건에서 제외합니다. */
    List<PayPalBalanceReportExceptionRow> listExceptions(PayPalBalanceReportRowStatus status, LocalDate from, LocalDate to,
            String recordId, int limit, int offset);

    /** 한 run의 행을 번호 순서로 한 페이지 조회합니다. status와 recordId는 null이면 조건에서 제외합니다. */
    List<PayPalBalanceReportExceptionRow> listRunRows(UUID runId, PayPalBalanceReportRowStatus status, String recordId, int limit,
            int offset);

    /** 행의 대사 상태입니다. 행이 없으면 비어 있습니다. */
    Optional<PayPalBalanceReportRowStatus> findRowStatus(UUID runId, int rowNumber);
}
