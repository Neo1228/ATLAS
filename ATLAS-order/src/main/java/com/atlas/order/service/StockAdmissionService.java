package com.atlas.order.service;

import com.atlas.order.dto.request.StockClaimRequest;
import java.util.List;

/**
 * 재고보다 훨씬 많은 예약 요청이 몰릴 때, 어차피 거절될 요청이 workflow 행과 원격 호출을 만들지 않도록 앞단에서 거른다.
 *
 * <p>이 gate는 "허용 가능 수량"의 근사치일 뿐 재고의 정본이 아니다. 판매 가능 여부의 최종 판단은 항상
 * location의 조건부 UPDATE이며, gate가 틀리거나 사라져도 초과 예약은 생기지 않는다. gate가 하는 일은 확실히
 * 소진된 SKU의 요청을 싸게 거절하는 것뿐이다.
 */
public interface StockAdmissionService {
    /**
     * 같은 admissionKey로 다시 호출하면 수량을 다시 차감하지 않고 첫 결과를 돌려준다(멱등 재시도).
     * 판단할 수 없는 상태(저장소 장애 등)는 거절이 아니라 통과로 답한다. 정본 검증이 뒤에 남아 있기 때문이다.
     */
    boolean admit(String admissionKey, List<StockClaimRequest> claims);

    /**
     * 승인한 요청이 location에 실제 예약으로 반영됐다. gate가 따로 들고 있던 "승인됐지만 아직 예약 전" 수량을 정리한다.
     * 같은 key로 여러 번 불려도 한 번만 반영돼야 한다.
     */
    default void confirm(String admissionKey) {
    }

    /**
     * 승인한 요청이 예약 없이 끝났다(취소·실패). 차감해 둔 수량을 돌려줘 과잉 거절을 막는다. 멱등이어야 하며, 승인한 적 없는
     * key나 이미 처리한 key는 아무것도 바꾸지 않는다.
     */
    default void release(String admissionKey) {
    }
}
