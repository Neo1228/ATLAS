package com.atlas.order.model;

/** 위치 재입고 한 건의 진행 상태. 값 집합은 {@code order_inventory_restock_state} 제약과 같다. */
public enum LocationRestockState {
    PENDING,
    COMPLETED,
    NEEDS_REVIEW
}
