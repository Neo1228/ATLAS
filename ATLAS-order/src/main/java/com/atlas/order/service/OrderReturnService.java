package com.atlas.order.service;

import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.dto.response.SellerOrderReturnPageResponse;
import com.atlas.order.model.OrderReturnStatus;
import java.util.List;
import java.util.UUID;

public interface OrderReturnService {
    OrderReturnResponse request(OrderReturnRequest request);
    OrderReturnResponse find(String buyerRef, UUID orderId, UUID returnId);

    /**
     * 구매자가 한 주문에 요청한 반품 목록.
     *
     * <p>returnId 를 모르면 상태를 확인할 수 없던 문제를 해결한다. 구매자는 앱을 다시 열었을 때
     * 진행 중인 반품을 찾을 수 있어야 한다.
     */
    List<OrderReturnResponse> findForBuyer(String buyerRef, UUID orderId);
    /** seller 소유의 반품 요청을 buyer 식별 정보 없이 keyset 순서로 조회한다. */
    SellerOrderReturnPageResponse findForSeller(UUID sellerId, OrderReturnStatus status, Integer limit, String cursor);
    OrderReturnResponse receive(UUID sellerId, UUID orderId, UUID returnId);
    OrderReturnResponse inspect(UUID sellerId, UUID orderId, UUID returnId, OrderReturnInspectionRequest request);
}
