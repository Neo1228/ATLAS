package com.atlas.order.model;

/**
 * {@code atlas-inventory-location} 서비스가 돌려주는 위치 재고 예약의 상태.
 *
 * <p>값 집합은 inventory-location 의 {@code LocationReservationStatus} 와 그 DB 제약
 * {@code inventory_reservation_status}(inventory-location V2 migration)와 같다. inventory-location 은 따로 배포되는
 * 서비스라 그 enum 을 컴파일 의존으로 가져오지 않고, HTTP 경계({@code HttpLocationClient})에서 응답 문자열을 이
 * enum 으로 한 번 파싱한다. workflow 안의 위치별 진행 상태인 {@link LocationReservationState} 와는 값과 의미가 다르다.
 */
public enum RemoteReservationStatus {
    RESERVED,
    CLAIMED,
    RELEASED,
    EXPIRED,
    CANCELLED;

    /**
     * 더는 재고를 붙잡지 않고 다른 상태로 가지도 않는 상태인지. RESERVED 는 CLAIMED 나 이 상태들로 가고,
     * CLAIMED 는 RELEASED 로만 간다(inventory-location {@code LocationReservationStatus} 설명).
     */
    public boolean isTerminal() {
        return this == RELEASED || this == EXPIRED || this == CANCELLED;
    }
}
