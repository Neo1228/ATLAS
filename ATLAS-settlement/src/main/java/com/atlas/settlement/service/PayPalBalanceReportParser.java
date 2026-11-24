package com.atlas.settlement.service;

import com.atlas.settlement.service.ParsedPayPalReport;
import com.atlas.settlement.service.ParsedPayPalReportRow;
import com.atlas.settlement.exception.SettlementException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public final class PayPalBalanceReportParser {
    /** 한 번에 받아들이는 보고서의 최대 크기(바이트). */
    public static final int MAX_REPORT_BYTES = 10 * 1024 * 1024;
    static final int MAX_REPORT_ROWS = 10_000;

    public ParsedPayPalReport parse(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_REPORT_BYTES) {
            throw invalid();
        }

        Map<String, Integer> reportColumns = null;
        Map<String, Integer> detailColumns = null;
        Map<String, Integer> footerColumns = null;
        String reportName = null;
        String reportStatus = null;
        Integer totalFiles = null;
        Integer totalRecords = null;
        List<ParsedPayPalReportRow> rows = new ArrayList<>();

        try (InputStreamReader reader = new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT));
                CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            for (CSVRecord record : parser) {
                String rowType = value(record, 0).replace("\uFEFF", "").toUpperCase(Locale.ROOT);
                if ("RH".equals(rowType)) {
                    if (hasColumns(record, "report name", "report status")) {
                        if (reportColumns != null) throw invalid();
                        reportColumns = columns(record);
                    } else if (reportColumns != null) {
                        reportName = field(record, reportColumns, "report name");
                        reportStatus = field(record, reportColumns, "report status");
                    }
                } else if ("RD".equals(rowType)) {
                    if (hasColumns(record, "record id", "record type")) {
                        if (detailColumns != null) throw invalid();
                        requireUniqueHeader(record, "record id");
                        requireUniqueHeader(record, "record type");
                        requireUniqueAliasHeader(record, "transaction currency", "record currency");
                        requireUniqueAliasHeader(record, "gross transaction amount", "gross amount");
                        detailColumns = columns(record);
                        requiredIndex(detailColumns, "record id");
                        requiredIndex(detailColumns, "record type");
                        requiredAliasIndex(detailColumns, "transaction currency", "record currency");
                        requiredAliasIndex(detailColumns, "gross transaction amount", "gross amount");
                    } else if (detailColumns != null) {
                        if (rows.size() == MAX_REPORT_ROWS) throw invalid();
                        rows.add(parseRow(record, detailColumns));
                    }
                } else if ("RF".equals(rowType)) {
                    if (hasColumns(record, "total files")) {
                        if (footerColumns != null) throw invalid();
                        footerColumns = columns(record);
                    } else if (footerColumns != null) {
                        String files = field(record, footerColumns, "total files");
                        if (!files.isBlank()) {
                            try {
                                if (totalFiles != null) throw invalid();
                                totalFiles = new BigDecimal(files).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
                                String records = field(record, footerColumns, "total records");
                                if (!records.isBlank()) {
                                    totalRecords = new BigDecimal(records).setScale(0, RoundingMode.UNNECESSARY)
                                            .intValueExact();
                                }
                            } catch (ArithmeticException | NumberFormatException exception) {
                                throw invalid();
                            }
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            throw invalid();
        }

        if (reportColumns == null || detailColumns == null || reportName == null || reportName.isBlank()
                || reportStatus == null || !"success".equalsIgnoreCase(reportStatus.trim())
                || totalFiles == null || totalFiles != 1
                || (totalRecords != null && totalRecords != rows.size())
                || (!rows.isEmpty() && totalRecords == null)) {
            throw invalid();
        }
        return new ParsedPayPalReport(reportName.trim(), List.copyOf(rows));
    }

    private static ParsedPayPalReportRow parseRow(CSVRecord record, Map<String, Integer> columns) {
        String recordId = field(record, columns, "record id");
        String recordType = field(record, columns, "record type");
        String recordSubtype = field(record, columns, "record subtype");
        String relatedRecordId = field(record, columns, "related record id");
        String originalRecordId = field(record, columns, "original record id");
        String currency = fieldAlias(record, columns, "transaction currency", "record currency");
        String gross = fieldAlias(record, columns, "gross transaction amount", "gross amount");
        if (recordId.isBlank() || recordType.isBlank() || gross.isBlank()) throw invalid();
        try {
            return new ParsedPayPalReportRow(recordId, recordType, recordSubtype, relatedRecordId, originalRecordId, currency,
                    new BigDecimal(gross), optionalAmount(record, columns, "total fees net billed"),
                    optionalAmount(record, columns, "net amount"), field(record, columns, "transfer id"));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static BigDecimal optionalAmount(CSVRecord record, Map<String, Integer> columns, String name) {
        String value = field(record, columns, name);
        return value.isBlank() ? null : new BigDecimal(value);
    }

    private static Map<String, Integer> columns(CSVRecord record) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 1; index < record.size(); index++) {
            String column = normalize(record.get(index));
            if (!column.isEmpty()) result.putIfAbsent(column, index);
        }
        return result;
    }

    private static boolean hasColumns(CSVRecord record, String... names) {
        if (record.size() < 2) return false;
        for (String name : names) {
            if (headerCount(record, normalize(name)) == 0) return false;
        }
        return true;
    }

    private static void requireUniqueHeader(CSVRecord record, String name) {
        if (headerCount(record, normalize(name)) != 1) throw invalid();
    }

    private static void requireUniqueAliasHeader(CSVRecord record, String... aliases) {
        int count = 0;
        for (String alias : aliases) count += headerCount(record, normalize(alias));
        if (count != 1) throw invalid();
    }

    private static int headerCount(CSVRecord record, String name) {
        int count = 0;
        for (int index = 1; index < record.size(); index++) {
            if (normalize(record.get(index)).equals(name)) count++;
        }
        return count;
    }

    private static int requiredIndex(Map<String, Integer> columns, String name) {
        Integer index = columns.get(normalize(name));
        if (index == null) throw invalid();
        return index;
    }

    private static int requiredAliasIndex(Map<String, Integer> columns, String... aliases) {
        OptionalInt index = optionalAliasIndex(columns, aliases);
        if (index.isEmpty()) throw invalid();
        return index.getAsInt();
    }

    private static OptionalInt optionalAliasIndex(Map<String, Integer> columns, String... aliases) {
        Integer found = null;
        for (String alias : aliases) {
            Integer index = columns.get(normalize(alias));
            if (index == null) continue;
            if (found != null && !found.equals(index)) throw invalid();
            found = index;
        }
        return found == null ? OptionalInt.empty() : OptionalInt.of(found);
    }

    private static String field(CSVRecord record, Map<String, Integer> columns, String name) {
        Integer index = columns.get(normalize(name));
        return index == null ? "" : value(record, index);
    }

    private static String fieldAlias(CSVRecord record, Map<String, Integer> columns, String... aliases) {
        OptionalInt index = optionalAliasIndex(columns, aliases);
        return index.isEmpty() ? "" : value(record, index.getAsInt());
    }

    private static String value(CSVRecord record, int index) {
        return index >= record.size() ? "" : record.get(index).trim();
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static SettlementException invalid() {
        return new SettlementException(SettlementException.Code.INVALID_REQUEST,
                "PayPal Balance report is invalid or unsupported");
    }

}