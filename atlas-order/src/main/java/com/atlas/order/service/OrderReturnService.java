package com.atlas.order.service;

import com.atlas.order.dto.response.ReturnResponse;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.model.OrderReturnStatus;
import java.util.List;
import java.util.UUID;

public interface OrderReturnService {
    ReturnResponse.OrderReturn request(OrderRequest.Return request);
    ReturnResponse.OrderReturn find(String buyerRef, UUID orderId, UUID returnId);

    /**
     * 구매자가 한 주문에 요청한 반품 목록.
     *
     * <p>returnId 를 모르면 상태를 확인할 수 없던 문제를 해결한다. 구매자는 앱을 다시 열었을 때
     * 진행 중인 반품을 찾을 수 있어야 한다.
     */
    List<ReturnResponse.OrderReturn> findForBuyer(String buyerRef, UUID orderId);
    /** seller 소유의 반품 요청을 buyer 식별 정보 없이 keyset 순서로 조회한다. */
    ReturnResponse.SellerOrderReturnPage findForSeller(UUID sellerId, OrderReturnStatus status, Integer limit, String cursor);
    ReturnResponse.OrderReturn receive(UUID sellerId, UUID orderId, UUID returnId);
    ReturnResponse.OrderReturn inspect(UUID sellerId, UUID orderId, UUID returnId, OrderRequest.ReturnInspection request);

    /** 수령됐지만 검수 마감이 지난 반품 수. 운영자 알림용 집계이며 상태나 환불을 바꾸지 않는다(ADR-0089). */
    long countInspectionOverdue();
}
