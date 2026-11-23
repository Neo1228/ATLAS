package com.atlas.settlement.service;

import com.atlas.settlement.dto.response.PayPalBalanceReportDispositionResponse;
import com.atlas.settlement.dto.response.PayPalBalanceReportOperatorSummary;
import com.atlas.settlement.dto.response.PayPalBalanceReportPage;
import com.atlas.settlement.dto.response.PayPalBalanceReportRowResponse;
import com.atlas.settlement.dto.response.PayPalBalanceReportRunResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PayPal 잔액 보고서 대사 결과를 운영자가 조회하고 예외 행에 처분을 기록한다. */
public interface PayPalBalanceReportOperatorService {
    int DEFAULT_PAGE_SIZE = 20;
    int MAX_PAGE_SIZE = 100;
    int MAX_PAGE_OFFSET = 10_000;

    PayPalBalanceReportOperatorSummary summary();

    PayPalBalanceReportPage<PayPalBalanceReportRunResponse> listRuns(int requestedLimit, int offset);

    PayPalBalanceReportRunResponse search(String reportSha256);

    PayPalBalanceReportPage<PayPalBalanceReportRowResponse> listExceptions(String status, LocalDate from, LocalDate to, String recordId, int requestedLimit, int offset);

    PayPalBalanceReportPage<PayPalBalanceReportRowResponse> listRunRows(UUID runId, String status, String recordId, int requestedLimit, int offset);

    List<PayPalBalanceReportDispositionResponse> dispositionHistory(UUID runId, int rowNumber);

    PayPalBalanceReportDispositionResponse recordDisposition(UUID runId, int rowNumber, String disposition, String actor, String reason, String idempotencyKey);
}
