package com.atlas.order.service;

import java.util.List;
import java.util.UUID;

/**
 * 재고보다 훨씬 많은 예약 요청이 몰릴 때, 어차피 거절될 요청이 workflow 행과 원격 호출을 만들지 않도록 앞단에서 거른다.
 *
 * <p>이 gate는 "허용 가능 수량"의 근사치일 뿐 재고의 정본이 아니다. 판매 가능 여부의 최종 판단은 항상
 * location의 조건부 UPDATE이며, gate가 틀리거나 사라져도 초과 예약은 생기지 않는다. gate가 하는 일은 확실히
 * 소진된 SKU의 요청을 싸게 거절하는 것뿐이다.
 */
public interface ReservationAdmissionGate {

    /**
     * 같은 admissionKey로 다시 호출하면 수량을 다시 차감하지 않고 첫 결과를 돌려준다(멱등 재시도).
     * 판단할 수 없는 상태(저장소 장애 등)는 거절이 아니라 통과로 답한다. 정본 검증이 뒤에 남아 있기 때문이다.
     */
    boolean tryAdmit(String admissionKey, List<Claim> claims);

    record Claim(UUID fulfillmentLocationId, UUID sellerId, String skuId, int quantity) {}
}
