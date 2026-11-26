package com.atlas.application.service;

import com.atlas.application.dto.response.SettlementHttpResponse;
import com.atlas.payment.dto.response.PayPalReportMatchResponse;
import com.atlas.application.dto.response.SettlementHttpResponse.PayPalBalanceReportReconciliation.RowResult;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import com.atlas.payment.service.PayPalReportMatchQuery;
import com.atlas.settlement.model.ParsedPayPalReport;
import com.atlas.settlement.model.ParsedPayPalReportRow;
import com.atlas.settlement.service.PayPalBalanceReportParser;
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

    private final PayPalBalanceReportParser parser;
    private final PayPalReportMatchQuery matchQuery;

    public PayPalBalanceReportReconciliationService(PayPalReportMatchQuery matchQuery,
            PayPalBalanceReportParser parser) {
        this.matchQuery = Objects.requireNonNull(matchQuery);
        this.parser = Objects.requireNonNull(parser);
    }

    public SettlementHttpResponse.PayPalBalanceReportReconciliation reconcile(byte[] reportContent) {
        ParsedPayPalReport report = parser.parse(reportContent);
        List<ParsedPayPalReportRow> rows = report.rows();
        Map<String, List<PayPalReportMatchResponse.Candidate>> providerFacts = providerFacts(rows);
        Map<String, List<Integer>> occurrences = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            // Record ID가 없는 행(Fees 같은 집계 행)은 서로 같은 ID로 반복된 것이 아니다.
            if (!rows.get(index).recordId().isBlank()) {
                occurrences.computeIfAbsent(rows.get(index).recordId(), ignored -> new ArrayList<>()).add(index);
            }
        }

        List<RowResult> results = new ArrayList<>(rows.size());
        int matched = 0;
        int unmatched = 0;
        int duplicate = 0;
        int mismatch = 0;
        for (int index = 0; index < rows.size(); index++) {
            ParsedPayPalReportRow row = rows.get(index);
            List<Integer> sameRecordIds = occurrences.getOrDefault(row.recordId(), List.of());
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

        return new SettlementHttpResponse.PayPalBalanceReportReconciliation(report.reportName(), sha256(reportContent),
                BANK_RECEIPT_EVIDENCE, PROVIDER_ID_MAPPING_STATUS,
                new SettlementHttpResponse.PayPalBalanceReportReconciliation.Summary(rows.size(), matched, unmatched, duplicate,
                        mismatch), results);
    }

    private Map<String, List<PayPalReportMatchResponse.Candidate>> providerFacts(List<ParsedPayPalReportRow> rows) {
        Set<String> recordIds = new HashSet<>();
        for (ParsedPayPalReportRow row : rows) {
            String type = normalize(row.recordType());
            if (SALE.equals(type) || REFUND.equals(type)) addIfPresent(recordIds, row.recordId());
            if (REFUND.equals(type)) addIfPresent(recordIds, row.relatedRecordId());
        }
        if (recordIds.isEmpty()) return Map.of();

        Map<String, List<PayPalReportMatchResponse.Candidate>> factsById = new HashMap<>();
        for (PayPalReportMatchResponse.Candidate candidate : matchQuery.findByRecordIds(recordIds)) {
            factsById.computeIfAbsent(candidate.recordId(), ignored -> new ArrayList<>()).add(candidate);
        }
        return factsById;
    }

    private static void addIfPresent(Set<String> ids, String value) {
        if (value != null && !value.isBlank()) ids.add(value);
    }

    private static RowResult repeatedRecord(ParsedPayPalReportRow row, int rowNumber,
            List<Integer> sameRecordIds, List<ParsedPayPalReportRow> rows) {
        ParsedPayPalReportRow first = rows.get(sameRecordIds.getFirst());
        boolean conflicting = sameRecordIds.stream().map(rows::get)
                .anyMatch(other -> !sameReportFacts(first, other));
        return result(row, rowNumber, conflicting ? PayPalBalanceReportRowStatus.MISMATCH : PayPalBalanceReportRowStatus.DUPLICATE,
                conflicting ? "DUPLICATE_REPORT_RECORD_FACTS_CONFLICT" : "DUPLICATE_REPORT_RECORD_ID");
    }

    private static boolean sameReportFacts(ParsedPayPalReportRow left,
            ParsedPayPalReportRow right) {
        return normalize(left.recordType()).equals(normalize(right.recordType()))
                && normalize(left.recordSubtype()).equals(normalize(right.recordSubtype()))
                && normalize(left.relatedRecordId()).equals(normalize(right.relatedRecordId()))
                && normalize(left.originalRecordId()).equals(normalize(right.originalRecordId()))
                && normalize(left.currency()).equals(normalize(right.currency()))
                && sameAmount(left.grossAmount(), right.grossAmount())
                && sameAmount(left.feeAmount(), right.feeAmount())
                && sameAmount(left.netAmount(), right.netAmount())
                && normalize(left.transferId()).equals(normalize(right.transferId()));
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static RowResult classify(ParsedPayPalReportRow row, int rowNumber,
            Map<String, List<PayPalReportMatchResponse.Candidate>> providerFacts) {
        String type = normalize(row.recordType());
        if (SALE.equals(type)) return classifySale(row, rowNumber, providerFacts);
        if (REFUND.equals(type)) return classifyRefund(row, rowNumber, providerFacts);
        if (WITHDRAWAL.equals(type)) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "BANK_STATEMENT_REQUIRED_FOR_TRANSFER_ID");
        }
        return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "UNSUPPORTED_PROVIDER_RECORD_TYPE");
    }

    private static RowResult classifySale(ParsedPayPalReportRow row, int rowNumber,
            Map<String, List<PayPalReportMatchResponse.Candidate>> providerFacts) {
        List<PayPalReportMatchResponse.Candidate> candidates = providerFacts.getOrDefault(row.recordId(), List.of());
        if (candidates.isEmpty()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "PROVIDER_CAPTURE_ID_NOT_FOUND");
        }
        if (candidates.size() != 1) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "AMBIGUOUS_PROVIDER_RECORD_ID");
        }
        PayPalReportMatchResponse.Candidate candidate = candidates.getFirst();
        if (candidate.kind() != PayPalReportMatchResponse.Kind.CAPTURE) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "PROVIDER_ID_TYPE_CONFLICT");
        }
        if (!candidate.confirmed()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "PROVIDER_CAPTURE_NOT_CONFIRMED");
        }
        if (!Objects.equals(row.recordId(), candidate.captureId())) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "PROVIDER_CAPTURE_ID_CONFLICT");
        }
        return validateFacts(row, rowNumber, candidate, false, "EXACT_CAPTURE_ID_AND_AMOUNT_CURRENCY_MATCH");
    }

    private static RowResult classifyRefund(ParsedPayPalReportRow row, int rowNumber,
            Map<String, List<PayPalReportMatchResponse.Candidate>> providerFacts) {
        List<PayPalReportMatchResponse.Candidate> candidates = providerFacts.getOrDefault(row.recordId(), List.of());
        if (candidates.isEmpty()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "PROVIDER_REFUND_ID_NOT_FOUND");
        }
        if (candidates.size() != 1) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "AMBIGUOUS_PROVIDER_RECORD_ID");
        }
        PayPalReportMatchResponse.Candidate refund = candidates.getFirst();
        if (refund.kind() != PayPalReportMatchResponse.Kind.REFUND) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "PROVIDER_ID_TYPE_CONFLICT");
        }
        if (!refund.confirmed()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "PROVIDER_REFUND_NOT_CONFIRMED");
        }
        if (row.relatedRecordId().isBlank()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "REFUND_CAPTURE_LINK_REQUIRED");
        }
        if (!Objects.equals(row.relatedRecordId(), refund.captureId())) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "REFUND_CAPTURE_ID_CONFLICT");
        }
        List<PayPalReportMatchResponse.Candidate> captures = providerFacts.getOrDefault(refund.captureId(), List.of()).stream()
                .filter(candidate -> candidate.kind() == PayPalReportMatchResponse.Kind.CAPTURE)
                .toList();
        if (captures.isEmpty()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "REFUND_CAPTURE_ID_NOT_FOUND");
        }
        if (captures.size() != 1) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "AMBIGUOUS_REFUND_CAPTURE_ID");
        }
        if (!captures.getFirst().confirmed()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "REFUND_CAPTURE_NOT_CONFIRMED");
        }
        return validateFacts(row, rowNumber, refund, true, "EXACT_REFUND_AND_CAPTURE_LINK_MATCH");
    }

    private static RowResult validateFacts(ParsedPayPalReportRow row, int rowNumber,
            PayPalReportMatchResponse.Candidate candidate, boolean refund, String matchedReason) {
        if (candidate.providerAmountMinor() == null || candidate.providerAmountMinor() <= 0
                || candidate.providerCurrency() == null || candidate.providerCurrency().isBlank()) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.UNMATCHED, "PROVIDER_AMOUNT_CURRENCY_FACTS_UNAVAILABLE");
        }
        BigDecimal expectedGross = BigDecimal.valueOf(candidate.providerAmountMinor(), 2);
        if (refund) expectedGross = expectedGross.negate();
        if (!normalize(row.currency()).equals(normalize(candidate.providerCurrency()))
                || row.grossAmount().compareTo(expectedGross) != 0) {
            return result(row, rowNumber, PayPalBalanceReportRowStatus.MISMATCH, "PROVIDER_AMOUNT_OR_CURRENCY_CONFLICT");
        }
        return result(row, rowNumber, PayPalBalanceReportRowStatus.MATCHED, matchedReason);
    }

    private static RowResult result(ParsedPayPalReportRow row, int rowNumber, PayPalBalanceReportRowStatus status,
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
