package com.atlas.application.controller.order;

import com.atlas.application.controller.dto.ReorderHttpRequest;
import com.atlas.application.controller.dto.OrderRefundHttpRequest;
import com.atlas.application.controller.dto.OrderReturnHttpRequest;
import com.atlas.application.controller.dto.OrderReturnInspectionHttpRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.order.dto.request.OrderReorderRequest;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderRefundSummaryResponse;
import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.service.OrderReorderService;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderReturnService;
import jakarta.validation.Valid;
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

    @PostMapping("/campaigns/{campaignId}/tickets/{ticketId}/checkout")
    public OrderResponse startCheckout(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        return orders.startCheckout(campaignId, ticketId);
    }

    @PostMapping("/orders/{orderId}/payment")
    public OrderResponse submitPayment(@PathVariable UUID orderId) {
        return orders.submitPayment(orderId);
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
