package com.atlas.settlement.persistence.state;

/**
 * 지급 실행 상태.
 *
 * <p>REQUESTED → APPROVED → SENT → CONFIRMED 가 정상 경로이고, 어느 단계에서든 FAILED 로
 * 끝날 수 있다. 상태 전이는 조건부 UPDATE 로만 수행해, 같은 지급이 두 번 확정되지 않게 한다.
 */
public enum SettlementPayoutState {
    REQUESTED,
    APPROVED,
    SENT,
    CONFIRMED,
    FAILED
}
