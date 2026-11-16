package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalBalanceReportReconciliationResponse;
import com.atlas.application.dto.response.PayPalBalanceReportReconciliationResponse.RowResult;
import com.atlas.application.dto.response.PayPalBalanceReportReconciliationResponse.Status;
import com.atlas.payment.service.PayPalReportMatchQuery;
import com.atlas.payment.service.PayPalReportMatchQuery.Candidate;
import com.atlas.payment.service.PayPalReportMatchQuery.Kind;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PayPalBalanceReportReconciliationService {
    private static final String BANK_RECEIPT_EVIDENCE = "NOT_VERIFIED_FROM_PAYPAL_REPORT_ALONE";
    private static final String PROVIDER_ID_MAPPING_STATUS = "EXACT_PROVIDER_ID_WITH_AMOUNT_CURRENCY_VALIDATION";
    private static final String SALE = "SALE";
    private static final String REFUND = "REFUND";
    private static final String WITHDRAWAL = "WITHDRAW MONEY";

    private final PayPalBalanceReportParser parser = new PayPalBalanceReportParser();
    private final PayPalReportMatchQuery matchQuery;

    public PayPalBalanceReportReconciliationService(PayPalReportMatchQuery matchQuery) {
        this.matchQuery = Objects.requireNonNull(matchQuery);
    }

    public PayPalBalanceReportReconciliationResponse reconcile(byte[] reportContent) {
        PayPalBalanceReportParser.ParsedReport report = parser.parse(reportContent);
        List<PayPalBalanceReportParser.ReportRow> rows = report.rows();
        Map<String, List<Candidate>> providerFacts = providerFacts(rows);
        Map<String, List<Integer>> occurrences = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            occurrences.computeIfAbsent(rows.get(index).recordId(), ignored -> new ArrayList<>()).add(index);
        }

        List<RowResult> results = new ArrayList<>(rows.size());
        int matched = 0;
        int unmatched = 0;
        int duplicate = 0;
        int mismatch = 0;
        for (int index = 0; index < rows.size(); index++) {
            PayPalBalanceReportParser.ReportRow row = rows.get(index);
            List<Integer> sameRecordIds = occurrences.get(row.recordId());
            RowResult result = sameRecordIds.size() > 1
                    ? repeatedRecord(row, index + 1, sameRecordIds, rows)
                    : classify(row, index + 1, providerFacts);
            results.add(result);
            switch (result.status()) {
                case MATCHED -> matched++;
                case UNMATCHED -> unmatched++;
                case DUPLICATE -> duplicate++;
                case MISMATCH -> mismatch++;
            }
        }

        return new PayPalBalanceReportReconciliationResponse(report.reportName(), sha256(reportContent),
                BANK_RECEIPT_EVIDENCE, PROVIDER_ID_MAPPING_STATUS,
                new PayPalBalanceReportReconciliationResponse.Summary(rows.size(), matched, unmatched, duplicate,
                        mismatch), results);
    }

    private Map<String, List<Candidate>> providerFacts(List<PayPalBalanceReportParser.ReportRow> rows) {
        Set<String> recordIds = new HashSet<>();
        for (PayPalBalanceReportParser.ReportRow row : rows) {
            String type = normalize(row.recordType());
            if (SALE.equals(type) || REFUND.equals(type)) addIfPresent(recordIds, row.recordId());
            if (REFUND.equals(type)) addIfPresent(recordIds, row.relatedRecordId());
        }
        if (recordIds.isEmpty()) return Map.of();

        Map<String, List<Candidate>> factsById = new HashMap<>();
        for (Candidate candidate : matchQuery.findByRecordIds(recordIds)) {
            factsById.computeIfAbsent(candidate.recordId(), ignored -> new ArrayList<>()).add(candidate);
        }
        return factsById;
    }

    private static void addIfPresent(Set<String> ids, String value) {
        if (value != null && !value.isBlank()) ids.add(value);
    }

    private static RowResult repeatedRecord(PayPalBalanceReportParser.ReportRow row, int rowNumber,
            List<Integer> sameRecordIds, List<PayPalBalanceReportParser.ReportRow> rows) {
        PayPalBalanceReportParser.ReportRow first = rows.get(sameRecordIds.getFirst());
        boolean conflicting = sameRecordIds.stream().map(rows::get)
                .anyMatch(other -> !sameReportFacts(first, other));
        return result(row, rowNumber, conflicting ? Status.MISMATCH : Status.DUPLICATE,
                conflicting ? "DUPLICATE_REPORT_RECORD_FACTS_CONFLICT" : "DUPLICATE_REPORT_RECORD_ID");
    }

    private static boolean sameReportFacts(PayPalBalanceReportParser.ReportRow left,
            PayPalBalanceReportParser.ReportRow right) {
        return normalize(left.recordType()).equals(normalize(right.recordType()))
                && normalize(left.recordSubtype()).equals(normalize(right.recordSubtype()))
                && normalize(left.relatedRecordId()).equals(normalize(right.relatedRecordId()))
                && normalize(left.originalRecordId()).equals(normalize(right.originalRecordId()))
                && normalize(left.currency()).equals(normalize(right.currency()))
                && left.grossAmount().compareTo(right.grossAmount()) == 0
                && sameAmount(left.feeAmount(), right.feeAmount())
                && sameAmount(left.netAmount(), right.netAmount())
                && normalize(left.transferId()).equals(normalize(right.transferId()));
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static RowResult classify(PayPalBalanceReportParser.ReportRow row, int rowNumber,
            Map<String, List<Candidate>> providerFacts) {
        String type = normalize(row.recordType());
        if (SALE.equals(type)) return classifySale(row, rowNumber, providerFacts);
        if (REFUND.equals(type)) return classifyRefund(row, rowNumber, providerFacts);
        if (WITHDRAWAL.equals(type)) {
            return result(row, rowNumber, Status.UNMATCHED, "BANK_STATEMENT_REQUIRED_FOR_TRANSFER_ID");
        }
        return result(row, rowNumber, Status.UNMATCHED, "UNSUPPORTED_PROVIDER_RECORD_TYPE");
    }

    private static RowResult classifySale(PayPalBalanceReportParser.ReportRow row, int rowNumber,
            Map<String, List<Candidate>> providerFacts) {
        List<Candidate> candidates = providerFacts.getOrDefault(row.recordId(), List.of());
        if (candidates.isEmpty()) {
            return result(row, rowNumber, Status.UNMATCHED, "PROVIDER_CAPTURE_ID_NOT_FOUND");
        }
        if (candidates.size() != 1) {
            return result(row, rowNumber, Status.MISMATCH, "AMBIGUOUS_PROVIDER_RECORD_ID");
        }
        Candidate candidate = candidates.getFirst();
        if (candidate.kind() != Kind.CAPTURE) {
            return result(row, rowNumber, Status.MISMATCH, "PROVIDER_ID_TYPE_CONFLICT");
        }
        if (!candidate.confirmed()) {
            return result(row, rowNumber, Status.UNMATCHED, "PROVIDER_CAPTURE_NOT_CONFIRMED");
        }
        if (!Objects.equals(row.recordId(), candidate.captureId())) {
            return result(row, rowNumber, Status.MISMATCH, "PROVIDER_CAPTURE_ID_CONFLICT");
        }
        return validateFacts(row, rowNumber, candidate, false, "EXACT_CAPTURE_ID_AND_AMOUNT_CURRENCY_MATCH");
    }

    private static RowResult classifyRefund(PayPalBalanceReportParser.ReportRow row, int rowNumber,
            Map<String, List<Candidate>> providerFacts) {
        List<Candidate> candidates = providerFacts.getOrDefault(row.recordId(), List.of());
        if (candidates.isEmpty()) {
            return result(row, rowNumber, Status.UNMATCHED, "PROVIDER_REFUND_ID_NOT_FOUND");
        }
        if (candidates.size() != 1) {
            return result(row, rowNumber, Status.MISMATCH, "AMBIGUOUS_PROVIDER_RECORD_ID");
        }
        Candidate refund = candidates.getFirst();
        if (refund.kind() != Kind.REFUND) {
            return result(row, rowNumber, Status.MISMATCH, "PROVIDER_ID_TYPE_CONFLICT");
        }
        if (!refund.confirmed()) {
            return result(row, rowNumber, Status.UNMATCHED, "PROVIDER_REFUND_NOT_CONFIRMED");
        }
        if (row.relatedRecordId().isBlank()) {
            return result(row, rowNumber, Status.UNMATCHED, "REFUND_CAPTURE_LINK_REQUIRED");
        }
        if (!Objects.equals(row.relatedRecordId(), refund.captureId())) {
            return result(row, rowNumber, Status.MISMATCH, "REFUND_CAPTURE_ID_CONFLICT");
        }
        List<Candidate> captures = providerFacts.getOrDefault(refund.captureId(), List.of()).stream()
                .filter(candidate -> candidate.kind() == Kind.CAPTURE)
                .toList();
        if (captures.isEmpty()) {
            return result(row, rowNumber, Status.UNMATCHED, "REFUND_CAPTURE_ID_NOT_FOUND");
        }
        if (captures.size() != 1) {
            return result(row, rowNumber, Status.MISMATCH, "AMBIGUOUS_REFUND_CAPTURE_ID");
        }
        if (!captures.getFirst().confirmed()) {
            return result(row, rowNumber, Status.UNMATCHED, "REFUND_CAPTURE_NOT_CONFIRMED");
        }
        return validateFacts(row, rowNumber, refund, true, "EXACT_REFUND_AND_CAPTURE_LINK_MATCH");
    }

    private static RowResult validateFacts(PayPalBalanceReportParser.ReportRow row, int rowNumber,
            Candidate candidate, boolean refund, String matchedReason) {
        if (candidate.providerAmountMinor() == null || candidate.providerAmountMinor() <= 0
                || candidate.providerCurrency() == null || candidate.providerCurrency().isBlank()) {
            return result(row, rowNumber, Status.UNMATCHED, "PROVIDER_AMOUNT_CURRENCY_FACTS_UNAVAILABLE");
        }
        BigDecimal expectedGross = BigDecimal.valueOf(candidate.providerAmountMinor(), 2);
        if (refund) expectedGross = expectedGross.negate();
        if (!normalize(row.currency()).equals(normalize(candidate.providerCurrency()))
                || row.grossAmount().compareTo(expectedGross) != 0) {
            return result(row, rowNumber, Status.MISMATCH, "PROVIDER_AMOUNT_OR_CURRENCY_CONFLICT");
        }
        return result(row, rowNumber, Status.MATCHED, matchedReason);
    }

    private static RowResult result(PayPalBalanceReportParser.ReportRow row, int rowNumber, Status status,
            String reasonCode) {
        return new RowResult(rowNumber, row.recordId(), row.relatedRecordId(), row.originalRecordId(),
                row.recordType(), row.recordSubtype(), row.currency(), row.grossAmount(),
                row.feeAmount(), row.netAmount(), row.transferId().isBlank() ? null : row.transferId(), status,
                reasonCode);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
