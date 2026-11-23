package com.atlas.settlement.service;

import java.util.UUID;

/** 운영자 조회용 대사 행. 금액과 원본 식별 정보는 포함하지 않는다. */
public record PayPalBalanceReportExceptionRow(UUID runId, int rowNumber, String recordId, String relatedRecordId,
        String recordType, String status, String reasonCode) {
}
