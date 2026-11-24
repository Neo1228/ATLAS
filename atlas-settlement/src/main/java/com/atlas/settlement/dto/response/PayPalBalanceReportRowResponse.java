package com.atlas.settlement.dto.response;

import java.util.UUID;

/** 금액과 원본 식별 정보를 제외한 대사 행 조회 DTO입니다. */
public record PayPalBalanceReportRowResponse(UUID runId, int rowNumber, String recordId,
        String relatedRecordId, String recordType, String status, String reasonCode) {
}
