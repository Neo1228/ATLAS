package com.atlas.settlement.service;

import java.time.Duration;

/**
 * 판매자 잔액 마감(checkpoint)을 쓰기 경로 밖에서 앞으로 옮긴다(ADR-0104). 잔액 읽기(환불 분개의 보류 잔액, 지급 예약의
 * 지급 가능 잔액, 정산서 잔액)는 마감 행 + 커서 이후 항목을 더하므로, 마감이 멈춰도 값은 정확하고 읽는 양만 늘어난다.
 */
public interface SettlementBalanceCheckpointService {
    /**
     * 마감을 한 번(항목 최대 maxEntries 개) 진행한다. 다른 인스턴스가 마감 중이거나 옮길 범위가 없으면 아무것도 하지 않는다.
     *
     * @param safetyLag 커서를 지금보다 적어도 이만큼 뒤에 둔다(pg_stat_activity에 보이지 않는 세션 몫)
     * @return 이번 마감이 maxEntries 에서 끊겨 지금 더 옮길 항목이 남았으면 true
     */
    boolean foldOnce(int maxEntries, Duration safetyLag);
}
