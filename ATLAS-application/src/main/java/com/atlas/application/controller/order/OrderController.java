package com.atlas.application.controller.order;

import com.atlas.application.controller.dto.OrderRefundHttpRequest;
import com.atlas.application.controller.dto.OrderReturnHttpRequest;
import com.atlas.application.controller.dto.OrderReturnInspectionHttpRequest;
import com.atlas.application.controller.dto.PaymentRecoveryStatusResponse;
import com.atlas.application.controller.dto.ReorderHttpRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.order.dto.request.OrderReorderRequest;
import com.atlas.order.dto.response.OrderHistoryPageResponse;
import com.atlas.order.dto.response.SellerOrderDetailResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderRefundSummaryResponse;
import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.dto.response.SellerOrderReturnPageResponse;
import com.atlas.order.persistence.state.OrderReturnStatus;
import com.atlas.order.service.OrderReorderService;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderReturnService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orders;
    private final OrderReorderService reorders;
    private final OrderRefundService refunds;
    private final OrderReturnService returns;
    private final Clock clock;

    @PostMapping("/campaigns/{campaignId}/tickets/{ticketId}/checkout")
    public OrderResponse startCheckout(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        return orders.startCheckout(campaignId, ticketId);
    }

    @PostMapping("/orders/{orderId}/payment")
    public OrderResponse submitPayment(@PathVariable UUID orderId) {
        return orders.submitPayment(orderId);
    }

    @GetMapping("/orders/{orderId}/payment/status")
    public PaymentRecoveryStatusResponse paymentStatus(@PathVariable UUID orderId) {
        OrderResponse order = orders.find(orderId);
        return PaymentRecoveryStatusResponse.from(
                orderId, order.state(), order.paymentState(), order.checkoutExpiresAt(), clock.instant());
    }

    @PostMapping("/orders/{orderId}/reconcile")
    public OrderResponse reconcile(@PathVariable UUID orderId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return orders.reconcilePayment(orderId, idempotencyKey);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable UUID orderId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return orders.cancel(orderId, idempotencyKey);
    }

    @GetMapping("/orders/{orderId}/payment/return")
    public OrderResponse paymentReturn(@PathVariable UUID orderId,
            @RequestParam("token") String providerOrderId) {
        return orders.completePaymentApproval(orderId, providerOrderId);
    }

    @GetMapping("/orders/{orderId}/payment/cancel")
    public OrderResponse paymentCancel(@PathVariable UUID orderId,
            @RequestParam(value = "token", required = false) String providerOrderId) {
        return orders.cancel(orderId, "paypal-cancel:" + orderId
                + (providerOrderId == null ? "" : ":" + providerOrderId));
    }


    @PostMapping("/orders/{orderId}/reorder")
    public CartResponse reorder(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReorderHttpRequest request) {
        return reorders.reorder(new OrderReorderRequest(orderId, idempotencyKey,
                request.confirmExistingCartMerge()));
    }

    @PostMapping("/sellers/{sellerId}/orders/{orderId}/refund-records")
    public OrderRefundResponse refund(@PathVariable UUID sellerId, @PathVariable UUID orderId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody OrderRefundHttpRequest request) {
        OrderRefundRequest refundRequest = new OrderRefundRequest(sellerId, orderId, idempotencyKey,
                request.items().stream()
                        .map(item -> new OrderRefundRequest.Item(item.orderItemId(), item.quantity()))
                        .toList());
        return refunds.refund(refundRequest);
    }

    @GetMapping("/sellers/{sellerId}/orders/{orderId}/refund-record")
    public OrderRefundSummaryResponse refundSummary(@PathVariable UUID sellerId, @PathVariable UUID orderId) {
        return refunds.find(sellerId, orderId);
    }

    @GetMapping("/buyers/{buyerRef}/orders")
    public OrderHistoryPageResponse buyerOrderHistory(@PathVariable String buyerRef,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return orders.findBuyerOrderHistory(buyerRef, limit, cursor);
    }

    /**
     * 판매자 주문 목록.
     *
     * <p>이 엔드포인트가 없으면 판매자는 자기가 이행해야 할 {@code orderId} 를 API 로 알 수 없다.
     * 배송·환불·반품 검수는 모두 {@code /sellers/{sellerId}/orders/{orderId}/...} 형태이므로,
     * 목록 없이는 첫 호출부터 도달 불가능했다. 구매자 이력과 같은 keyset 페이지네이션을 쓴다.
     */
    @GetMapping("/sellers/{sellerId}/orders")
    public OrderHistoryPageResponse sellerOrderHistory(@PathVariable UUID sellerId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return orders.findSellerOrderHistory(sellerId, limit, cursor);
    }

    @GetMapping("/sellers/{sellerId}/orders/{orderId}")
    public SellerOrderDetailResponse sellerOrderDetail(@PathVariable UUID sellerId, @PathVariable UUID orderId) {
        return orders.findSellerOrder(sellerId, orderId);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse find(@PathVariable UUID orderId) {
        return orders.find(orderId);
    }
    @PostMapping("/buyers/{buyerRef}/orders/{orderId}/returns")
    public OrderReturnResponse requestReturn(@PathVariable String buyerRef, @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody OrderReturnHttpRequest request) {
        OrderReturnRequest command = new OrderReturnRequest(buyerRef, orderId, idempotencyKey,
                request.items().stream().map(item -> new OrderReturnRequest.Item(item.orderItemId(), item.quantity())).toList());
        return returns.request(command);
    }

    /**
     * 구매자가 한 주문의 반품 목록을 본다.
     *
     * <p>returnId 를 모르면 상태를 확인할 수 없던 문제를 해결한다. 반품 요청 응답에는 id 가
     * 있지만, 앱을 다시 열거나 기록을 잃으면 조회 수단이 없었다.
     */
    @GetMapping("/buyers/{buyerRef}/orders/{orderId}/returns")
    public List<OrderReturnResponse> findReturns(@PathVariable String buyerRef, @PathVariable UUID orderId) {
        return returns.findForBuyer(buyerRef, orderId);
    }
    @GetMapping("/sellers/{sellerId}/returns")
    public SellerOrderReturnPageResponse sellerReturns(@PathVariable UUID sellerId,
            @RequestParam(value = "status", required = false) OrderReturnStatus status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return returns.findForSeller(sellerId, status, limit, cursor);
    }

    @GetMapping("/buyers/{buyerRef}/orders/{orderId}/returns/{returnId}")
    public OrderReturnResponse findReturn(@PathVariable String buyerRef, @PathVariable UUID orderId, @PathVariable UUID returnId) {
        return returns.find(buyerRef, orderId, returnId);
    }

    @PutMapping("/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/receipt")
    public OrderReturnResponse receiveReturn(@PathVariable UUID sellerId, @PathVariable UUID orderId, @PathVariable UUID returnId) {
        return returns.receive(sellerId, orderId, returnId);
    }

    @PutMapping("/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/inspection")
    public OrderReturnResponse inspectReturn(@PathVariable UUID sellerId, @PathVariable UUID orderId, @PathVariable UUID returnId,
            @Valid @RequestBody OrderReturnInspectionHttpRequest request) {
        return returns.inspect(sellerId, orderId, returnId, new OrderReturnInspectionRequest(
                request.items().stream().map(item -> new OrderReturnInspectionRequest.Item(item.orderItemId(), item.result())).toList()));
    }
}
