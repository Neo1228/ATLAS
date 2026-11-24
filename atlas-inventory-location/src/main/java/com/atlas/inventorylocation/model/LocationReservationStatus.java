package com.atlas.inventorylocation.model;

/**
 * 위치 재고 예약의 상태.
 *
 * <p>값 집합은 {@code inventory_reservation_status} 제약(V1, V2)과 같다. RESERVED 에서 CLAIMED 또는
 * RELEASED/EXPIRED/CANCELLED 로 가며, CLAIMED 는 다시 RELEASED 로만 간다.
 */
public enum LocationReservationStatus {
    RESERVED,
    CLAIMED,
    RELEASED,
    EXPIRED,
    CANCELLED
}
