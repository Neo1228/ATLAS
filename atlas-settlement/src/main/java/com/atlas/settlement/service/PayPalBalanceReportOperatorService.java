package com.atlas.settlement.service;

import com.atlas.settlement.dto.response.PayPalBalanceReportResponse;
import com.atlas.settlement.model.PayPalBalanceReportDispositionType;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PayPal 잔액 보고서 대사 결과를 운영자가 조회하고 예외 행에 처분을 기록한다. */
public interface PayPalBalanceReportOperatorService {
    int DEFAULT_PAGE_SIZE = 20;
    int MAX_PAGE_SIZE = 100;
    int MAX_PAGE_OFFSET = 10_000;

    PayPalBalanceReportResponse.OperatorSummary summary();

    PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Run> listRuns(int requestedLimit, int offset);

    PayPalBalanceReportResponse.Run search(String reportSha256);

    PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Row> listExceptions(PayPalBalanceReportRowStatus status, LocalDate from, LocalDate to, String recordId, int requestedLimit, int offset);

    PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Row> listRunRows(UUID runId, PayPalBalanceReportRowStatus status, String recordId, int requestedLimit, int offset);

    List<PayPalBalanceReportResponse.Disposition> dispositionHistory(UUID runId, int rowNumber);

    PayPalBalanceReportResponse.Disposition recordDisposition(UUID runId, int rowNumber, PayPalBalanceReportDispositionType disposition, String actor, String reason, String idempotencyKey);
}
