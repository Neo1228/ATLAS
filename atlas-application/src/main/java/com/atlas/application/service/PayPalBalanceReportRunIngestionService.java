package com.atlas.application.service;

import com.atlas.application.dto.response.SettlementHttpResponse;
import com.atlas.application.dto.response.SettlementHttpResponse.PayPalBalanceReportReconciliation.RowResult;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import com.atlas.settlement.model.PayPalBalanceReportRow;
import com.atlas.settlement.model.PayPalBalanceReportRun;
import com.atlas.settlement.service.PayPalBalanceReportStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PayPalBalanceReportRunIngestionService {

    private static final String REPORT_FORMAT = "CSV";

    private final PayPalBalanceReportReconciliationService reconciliationService;
    private final PayPalBalanceReportStore store;

    public PayPalBalanceReportRunIngestionService(
            PayPalBalanceReportReconciliationService reconciliationService,
            PayPalBalanceReportStore store) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.store = Objects.requireNonNull(store);
    }

    public SettlementHttpResponse.PayPalBalanceReportReconciliation reconcile(byte[] reportContent) {
        SettlementHttpResponse.PayPalBalanceReportReconciliation calculated = reconciliationService.reconcile(reportContent);
        Instant uploadedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID runId = UUID.randomUUID();
        PayPalBalanceReportRun run = toRun(runId, uploadedAt, calculated);
        List<PayPalBalanceReportRow> rows = calculated.rows().stream()
                .map(row -> toStoredRow(runId, row))
                .toList();

        if (store.createRunIfAbsent(run, rows).isPresent()) {
            return withRun(calculated, runId, uploadedAt, false, calculated.summary(), calculated.rows());
        }

        PayPalBalanceReportRun existing = store.findRunBySha256(calculated.reportSha256())
                .orElseThrow(() -> new IllegalStateException("PayPal report fingerprint conflict was not persisted"));
        List<RowResult> historicalRows = store.listAllRows(existing.runId()).stream()
                .map(PayPalBalanceReportRunIngestionService::toResponseRow)
                .toList();
        SettlementHttpResponse.PayPalBalanceReportReconciliation.Summary historicalSummary = new SettlementHttpResponse.PayPalBalanceReportReconciliation.Summary(
                existing.rowCount(), existing.matchedCount(), existing.unmatchedCount(),
                existing.duplicateCount(), existing.mismatchCount());
        return withRun(calculated, existing.runId(), existing.uploadedAt(), true, historicalSummary, historicalRows);
    }

    private static PayPalBalanceReportRun toRun(
            UUID runId,
            Instant uploadedAt,
            SettlementHttpResponse.PayPalBalanceReportReconciliation response) {
        SettlementHttpResponse.PayPalBalanceReportReconciliation.Summary summary = response.summary();
        return new PayPalBalanceReportRun(
                runId,
                response.reportSha256(),
                uploadedAt,
                REPORT_FORMAT,
                summary.totalRows(),
                summary.matched(),
                summary.unmatched(),
                summary.mismatch(),
                summary.duplicate());
    }

    private static PayPalBalanceReportRow toStoredRow(UUID runId, RowResult row) {
        return new PayPalBalanceReportRow(
                runId,
                row.rowNumber(),
                row.recordId(),
                row.relatedRecordId(),
                row.originalRecordId(),
                row.recordType(),
                row.recordSubtype(),
                row.currency(),
                row.grossAmount(),
                row.feeAmount(),
                row.netAmount(),
                row.transferId(),
                row.status(),
                row.reasonCode());
    }

    private static RowResult toResponseRow(PayPalBalanceReportRow row) {
        return new RowResult(
                row.rowNumber(),
                row.recordId(),
                row.relatedRecordId(),
                row.originalRecordId(),
                row.recordType(),
                row.recordSubtype(),
                row.currency(),
                row.grossAmount(),
                row.feeAmount(),
                row.netAmount(),
                row.transferId(),
                row.status(),
                row.reasonCode());
    }

    private static SettlementHttpResponse.PayPalBalanceReportReconciliation withRun(
            SettlementHttpResponse.PayPalBalanceReportReconciliation source,
            UUID runId,
            Instant uploadedAt,
            boolean duplicateUpload,
            SettlementHttpResponse.PayPalBalanceReportReconciliation.Summary summary,
            List<RowResult> rows) {
        return new SettlementHttpResponse.PayPalBalanceReportReconciliation(
                runId,
                uploadedAt,
                duplicateUpload,
                source.reportName(),
                source.reportSha256(),
                source.bankReceiptEvidence(),
                source.providerIdMappingStatus(),
                summary,
                rows);
    }
}
