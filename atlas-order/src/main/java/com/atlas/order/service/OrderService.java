package com.atlas.order.service;

import com.atlas.order.dto.response.OrderViewResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderService {
    int expireDueOrders();
    OrderViewResponse.Order startCheckout(UUID campaignId, UUID ticketId);
    OrderViewResponse.Order submitPayment(UUID orderId);
    OrderViewResponse.Order completePaymentApproval(UUID orderId, String providerOrderId);
    OrderViewResponse.Order reconcilePayment(UUID orderId, String idempotencyKey);
    OrderViewResponse.Order cancel(UUID orderId, String idempotencyKey);
    OrderViewResponse.Order find(UUID orderId);

    /**
     * 여러 주문의 응답을 한 transaction에서 조립한다. checkout 응답처럼 child order를
     * 한 번에 직렬화하는 읽기 전용 경로에서 주문별 transaction·조회 반복을 피한다.
     */
    List<OrderViewResponse.Order> findAll(List<UUID> orderIds);
    boolean isOwnedByBuyer(UUID orderId, String buyerRef);
    OrderViewResponse.HistoryPage findBuyerOrderHistory(String buyerRef, Integer limit, String cursor);

    /**
     * 판매자 주문 목록. 구매자 이력과 같은 keyset 페이지네이션을 쓴다.
     *
     * <p>이 API 가 없으면 판매자는 자기가 처리해야 할 orderId 를 알 수 없어, 배송·환불 등
     * 모든 이행 경로가 HTTP 만으로는 도달 불가능했다.
     */
    OrderViewResponse.HistoryPage findSellerOrderHistory(UUID sellerId, Integer limit, String cursor);
    /** 판매자만 자기 주문의 항목 식별자와 이행 필드를 조회한다. */
    OrderViewResponse.SellerOrderDetail findSellerOrder(UUID sellerId, UUID orderId);
    Optional<UUID> findDeliveredOrderItemId(String buyerRef, UUID productId);
}
