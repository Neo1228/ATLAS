package com.atlas.settlement.service;

import java.math.BigDecimal;

/** 보고서 한 행을 읽은 값. */
public record ParsedPayPalReportRow(String recordId, String recordType, String recordSubtype, String relatedRecordId, String originalRecordId, String currency, BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount, String transferId) {
}
