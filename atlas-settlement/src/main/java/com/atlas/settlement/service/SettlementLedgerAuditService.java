package com.atlas.settlement.service;

import com.atlas.settlement.dto.response.LedgerAuditResponse;

/**
 * 원장 불변식 점검. 분개별 차변·대변 균형, 항목 없는 분개, 분개 대비 지급·환불 예약 일치를 읽기 전용
 * 질의로 확인한다. DB 제약이 막지 못하는 위반(소유자 계정의 수동 SQL, 제약이 NOT VALID 인 기존 데이터)을
 * 찾는 탐지 전용 장치이고 원장을 바꾸지 않는다.
 */
public interface SettlementLedgerAuditService {
    LedgerAuditResponse audit();
}
