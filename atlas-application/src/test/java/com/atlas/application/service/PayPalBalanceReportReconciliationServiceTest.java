package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.application.dto.response.PayPalBalanceReportReconciliationResponse.Status;
import com.atlas.application.dto.response.PayPalBalanceReportReconciliationResponse;
import com.atlas.payment.dto.response.PayPalReportMatchCandidateResponse;
import com.atlas.payment.dto.response.PayPalReportMatchKind;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.service.PayPalBalanceReportParser;
import com.atlas.settlement.service.impl.CsvPayPalBalanceReportParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayPalBalanceReportReconciliationServiceTest {
    private static final String REPORT = """
            RH,Report name,Report status,Report generated,Hierarchy,Timezone
            RH,BALANCE_TRANSACTIONAL_REPORT,Success,15/02/22,synthetic,UTC
            RS,Account ID,Balance,Currency,Available,Withheld,Total
            RD,Record ID,Related record ID,Original record ID,Record type,Record subtype,Record currency,Gross  amount,Total fees net billed,Net  amount,Transfer ID
            RD,capture-1,,capture-1,Sale,,USD,100.00,-3.00,97.00,
            RD,refund-1,capture-1,capture-1,Refund,Referenced,USD,-10.00,0,-10.00,
            RD,capture-mismatch,,capture-mismatch,Sale,,USD,49.99,-1.00,48.99,
            RD,capture-duplicate,,capture-duplicate,Sale,,USD,5.00,0,5.00,
            RD,capture-duplicate,,capture-duplicate,Sale,,USD,5.00,0,5.00,
            RD,missing-capture,,missing-capture,Sale,,USD,2.00,0,2.00,
            RD,withdrawal-1,,,Withdraw money,,USD,-90.00,0,-90.00,bank-transfer-1
            RF,File number,,Total records,Total files,,,,,Total records
            RF,1,,7,1,,,,,7
            """;

    private final Map<String, List<PayPalReportMatchCandidateResponse>> candidatesByRecordId = new HashMap<>();
    private final PayPalBalanceReportReconciliationService service = new PayPalBalanceReportReconciliationService(
            recordIds -> recordIds.stream().flatMap(id -> candidatesByRecordId
                    .getOrDefault(id, List.of()).stream()).toList(),
            new CsvPayPalBalanceReportParser());

    @Test
    void matchesCaptureAndRefundByExactProviderIdsAndTransactionFacts() {
        addCandidate("capture-1", PayPalReportMatchKind.CAPTURE, true, "capture-1", 10_000L, "USD");
        addCandidate("refund-1", PayPalReportMatchKind.REFUND, true, "capture-1", 1_000L, "USD");
        addCandidate("capture-mismatch", PayPalReportMatchKind.CAPTURE, true, "capture-mismatch", 5_000L, "USD");

        PayPalBalanceReportReconciliationResponse response = service.reconcile(REPORT.getBytes(StandardCharsets.UTF_8));

        assertThat(response.reportName()).isEqualTo("BALANCE_TRANSACTIONAL_REPORT");
        assertThat(response.reportSha256()).hasSize(64);
        assertThat(response.bankReceiptEvidence()).isEqualTo("NOT_VERIFIED_FROM_PAYPAL_REPORT_ALONE");
        assertThat(response.providerIdMappingStatus()).isEqualTo("EXACT_PROVIDER_ID_WITH_AMOUNT_CURRENCY_VALIDATION");
        assertThat(response.summary()).usingRecursiveComparison().isEqualTo(
                new PayPalBalanceReportReconciliationResponse.Summary(7, 2, 2, 2, 1));
        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::status)
                .containsExactly(Status.MATCHED, Status.MATCHED, Status.MISMATCH, Status.DUPLICATE,
                        Status.DUPLICATE, Status.UNMATCHED, Status.UNMATCHED);
        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::reasonCode)
                .containsExactly("EXACT_CAPTURE_ID_AND_AMOUNT_CURRENCY_MATCH", "EXACT_REFUND_AND_CAPTURE_LINK_MATCH",
                        "PROVIDER_AMOUNT_OR_CURRENCY_CONFLICT", "DUPLICATE_REPORT_RECORD_ID",
                        "DUPLICATE_REPORT_RECORD_ID", "PROVIDER_CAPTURE_ID_NOT_FOUND",
                        "BANK_STATEMENT_REQUIRED_FOR_TRANSFER_ID");
        assertThat(response.rows().get(1).relatedRecordId()).isEqualTo("capture-1");
        assertThat(response.rows().get(1).originalRecordId()).isEqualTo("capture-1");
        assertThat(response.rows().get(0).feeAmount()).isEqualByComparingTo("-3.00");
        assertThat(response.rows().get(0).netAmount()).isEqualByComparingTo("97.00");
        assertThat(response.rows().get(6).transferId()).isEqualTo("bank-transfer-1");
    }

    @Test
    void doesNotMatchOrderIdInPlaceOfCaptureId() {
        addCandidate("order-1", PayPalReportMatchKind.CAPTURE, true, "capture-1", 10_000L, "USD");
        String report = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record ID,Record type,Transaction Currency,Gross Transaction Amount\n"
                + "RD,capture-1,Sale,USD,100.00\n"
                + "RF,File number,Total records,Total files\nRF,1,1,1\n";

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(Status.UNMATCHED);
            assertThat(row.reasonCode()).isEqualTo("PROVIDER_CAPTURE_ID_NOT_FOUND");
        });
    }

    @Test
    void requiresRefundRecordAndRelatedCaptureIdsToMatchPersistedFacts() {
        addCandidate("refund-2", PayPalReportMatchKind.REFUND, true, "capture-2", 1_000L, "USD");
        addCandidate("capture-2", PayPalReportMatchKind.CAPTURE, true, "capture-2", 10_000L, "USD");
        String report = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record ID,Related Record ID,Record type,Record subtype,Transaction Currency,Gross Transaction Amount\n"
                + "RD,refund-2,another-capture,Refund,Referenced,USD,-10.00\n"
                + "RF,File number,Total records,Total files\nRF,1,1,1\n";

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(Status.MISMATCH);
            assertThat(row.reasonCode()).isEqualTo("REFUND_CAPTURE_ID_CONFLICT");
        });
    }

    @Test
    void rejectsCurrencyMismatchAndRefundRowsWithoutRelatedCaptureId() {
        addCandidate("capture-currency", PayPalReportMatchKind.CAPTURE, true, "capture-currency", 10_000L, "USD");
        addCandidate("refund-no-link", PayPalReportMatchKind.REFUND, true, "capture-no-link", 1_000L, "USD");
        String report = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record ID,Record type,Record subtype,Transaction Currency,Gross Transaction Amount\n"
                + "RD,capture-currency,Sale,,EUR,100.00\n"
                + "RD,refund-no-link,Refund,Referenced,USD,-10.00\n"
                + "RF,File number,Total records,Total files\nRF,1,2,1\n";

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::status)
                .containsExactly(Status.MISMATCH, Status.UNMATCHED);
        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::reasonCode)
                .containsExactly("PROVIDER_AMOUNT_OR_CURRENCY_CONFLICT", "REFUND_CAPTURE_LINK_REQUIRED");
    }

    @Test
    void leavesUnconfirmedAndAmbiguousProviderIdentifiersUnmatchedOrMismatched() {
        addCandidate("pending-capture", PayPalReportMatchKind.CAPTURE, false, "pending-capture", 1_000L, "USD");
        addCandidate("ambiguous-capture", PayPalReportMatchKind.CAPTURE, true, "ambiguous-capture", 1_000L, "USD");
        addCandidate("ambiguous-capture", PayPalReportMatchKind.REFUND, true, "another-capture", 1_000L, "USD");
        String report = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record ID,Record type,Transaction Currency,Gross Transaction Amount\n"
                + "RD,pending-capture,Sale,USD,10.00\n"
                + "RD,ambiguous-capture,Sale,USD,10.00\n"
                + "RF,File number,Total records,Total files\nRF,1,2,1\n";

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::status)
                .containsExactly(Status.UNMATCHED, Status.MISMATCH);
        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::reasonCode)
                .containsExactly("PROVIDER_CAPTURE_NOT_CONFIRMED", "AMBIGUOUS_PROVIDER_RECORD_ID");
    }

    @Test
    void parsesHeaderOrderAndAliasesInsteadOfUsingColumnPositions() {
        String report = """
                RH,Report status,Report name
                RH,Success,BALANCE_RECONCILIATION_REPORT
                RD,Transfer ID,Record currency,Record type,Gross amount,Record ID
                RD,transfer-1,USD,Withdraw money,-9.50,withdraw-2
                RF,File number,Total records,Total files
                RF,1,1,1
                """;
        PayPalBalanceReportReconciliationResponse result = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.recordId()).isEqualTo("withdraw-2");
            assertThat(row.currency()).isEqualTo("USD");
            assertThat(row.grossAmount()).isEqualByComparingTo("-9.50");
            assertThat(row.feeAmount()).isNull();
            assertThat(row.netAmount()).isNull();
            assertThat(row.transferId()).isEqualTo("transfer-1");
            assertThat(row.reasonCode()).isEqualTo("BANK_STATEMENT_REQUIRED_FOR_TRANSFER_ID");
        });
    }

    @Test
    void reportsRepeatedRecordIdsWithConflictingFactsAsMismatches() {
        String report = """
                RH,Report name,Report status
                RH,BALANCE_RECONCILIATION_REPORT,Success
                RD,Record ID,Related record ID,Original record ID,Record type,Record subtype,Transaction Currency,Gross Transaction Amount
                RD,duplicate-sale,,capture-1,Sale,,USD,1.00
                RD,duplicate-sale,,capture-2,Sale,,USD,1.00
                RF,File number,Total records,Total files
                RF,1,2,1
                """;

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::status)
                .containsExactly(Status.MISMATCH, Status.MISMATCH);
        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::reasonCode)
                .containsOnly("DUPLICATE_REPORT_RECORD_FACTS_CONFLICT");
    }

    @Test
    void reportsRepeatedRecordIdsWithConflictingFeesOrNetAsMismatches() {
        String report = """
                RH,Report name,Report status
                RH,BALANCE_RECONCILIATION_REPORT,Success
                RD,Record ID,Original record ID,Record type,Record currency,Gross amount,Total fees net billed,Net amount
                RD,duplicate-sale,original-sale,Sale,USD,10.00,-1.00,9.00
                RD,duplicate-sale,original-sale,Sale,USD,10.00,-2.00,8.00
                RF,File number,Total records,Total files
                RF,1,2,1
                """;

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::status)
                .containsExactly(Status.MISMATCH, Status.MISMATCH);
        assertThat(response.rows()).extracting(PayPalBalanceReportReconciliationResponse.RowResult::reasonCode)
                .containsOnly("DUPLICATE_REPORT_RECORD_FACTS_CONFLICT");
    }

    @Test
    void leavesUnsupportedRecordTypesUnmatched() {
        String report = """
                RH,Report name,Report status
                RH,BALANCE_RECONCILIATION_REPORT,Success
                RD,Record ID,Record type,Transaction Currency,Gross Transaction Amount
                RD,unknown-record,Other,USD,1.00
                RF,File number,Total records,Total files
                RF,1,1,1
                """;

        PayPalBalanceReportReconciliationResponse response = service.reconcile(report.getBytes(StandardCharsets.UTF_8));

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(Status.UNMATCHED);
            assertThat(row.reasonCode()).isEqualTo("UNSUPPORTED_PROVIDER_RECORD_TYPE");
        });
    }

    @Test
    void rejectsFailedReportsMissingRequiredColumnsAndSplitReports() {
        String failed = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Error\n"
                + "RD,Record ID,Record type,Transaction Currency,Gross Transaction Amount\n";
        String missingHeader = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record type,Transaction Currency,Gross Transaction Amount\n";
        String split = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record ID,Record type,Transaction Currency,Gross Transaction Amount\n"
                + "RF,File number,Total files\nRF,1,2\n";
        String missingFooter = "RH,Report name,Report status\nRH,BALANCE_RECONCILIATION_REPORT,Success\n"
                + "RD,Record ID,Record type,Transaction Currency,Gross Transaction Amount\n"
                + "RD,capture-1,Sale,USD,1.00\n";
        String inconsistentFooter = missingFooter + "RF,File number,Total records,Total files\nRF,1,2,1\n";

        assertInvalid(failed.getBytes(StandardCharsets.UTF_8));
        assertInvalid(missingHeader.getBytes(StandardCharsets.UTF_8));
        assertInvalid(split.getBytes(StandardCharsets.UTF_8));
        assertInvalid(missingFooter.getBytes(StandardCharsets.UTF_8));
        assertInvalid(inconsistentFooter.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOversizedReportBeforeParsing() {
        byte[] oversized = new byte[PayPalBalanceReportParser.MAX_REPORT_BYTES + 1];
        assertInvalid(oversized);
    }

    @Test
    void rejectsMalformedUtf8WithoutReplacingIdentifierBytes() {
        byte[] malformed = REPORT.getBytes(StandardCharsets.UTF_8);
        malformed[REPORT.indexOf("BALANCE_TRANSACTIONAL_REPORT")] = (byte) 0xff;

        assertInvalid(malformed);
    }

    private void assertInvalid(byte[] content) {
        assertThatThrownBy(() -> service.reconcile(content)).isInstanceOfSatisfying(SettlementException.class,
                exception -> assertThat(exception.code()).isEqualTo(SettlementException.Code.INVALID_REQUEST));
    }

    private void addCandidate(String recordId, PayPalReportMatchKind kind, boolean confirmed, String captureId,
            Long amountMinor, String currency) {
        candidatesByRecordId.computeIfAbsent(recordId, ignored -> new ArrayList<>())
                .add(new PayPalReportMatchCandidateResponse(recordId, kind, confirmed, captureId, amountMinor, currency));
    }
}
