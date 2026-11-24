package com.atlas.settlement.model;

/**
 * 지급 dispatch outbox 의 처리 상태.
 *
 * <p>값 집합은 {@code settlement_payout_dispatch_status_check} 제약과 같다.
 * PENDING/WAITING_PROVIDER 는 worker 가 가져갈 수 있고, LEASED 는 worker 가 임대 중이다.
 * COMPLETED/FAILED/CANCELLED 는 terminal 이며 NEEDS_REVIEW 는 운영자 판단을 기다린다.
 */
public enum PayoutDispatchState {
    PENDING,
    LEASED,
    WAITING_PROVIDER,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW
}
