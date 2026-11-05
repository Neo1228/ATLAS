package com.atlas.application.controller.order;

import com.atlas.application.controller.dto.ReorderHttpRequest;
import com.atlas.application.controller.dto.OrderRefundHttpRequest;
import com.atlas.application.controller.dto.UpdateFulfillmentHttpRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.order.dto.response.OrderFulfillmentResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderRefundSummaryResponse;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderReorderService;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.OrderRefundService;
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
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orders;
    private final OrderReorderService reorders;
    private final OrderFulfillmentService fulfillments;
    private final OrderRefundService refunds;

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

    @GetMapping("/orders/{orderId}/fulfillment")
    public OrderFulfillmentResponse fulfillment(@PathVariable UUID orderId) {
        return fulfillments.find(orderId);
    }

    @PutMapping("/sellers/{sellerId}/orders/{orderId}/fulfillment")
    public OrderFulfillmentResponse updateFulfillment(@PathVariable UUID sellerId,
            @PathVariable UUID orderId, @Valid @RequestBody UpdateFulfillmentHttpRequest request) {
        return fulfillments.updateStatus(sellerId, orderId, request.status());
    }

    @PostMapping("/orders/{orderId}/reorder")
    public CartResponse reorder(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReorderHttpRequest request) {
        return reorders.reorder(orderId, idempotencyKey, request.confirmExistingCartMerge());
    }

    @PostMapping("/sellers/{sellerId}/orders/{orderId}/refund-records")
    public OrderRefundResponse refund(@PathVariable UUID sellerId, @PathVariable UUID orderId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody OrderRefundHttpRequest request) {
        OrderRefundRequest refundRequest = new OrderRefundRequest(request.items().stream()
                .map(item -> new OrderRefundRequest.Item(item.orderItemId(), item.quantity()))
                .toList());
        return refunds.refund(sellerId, orderId, idempotencyKey, refundRequest);
    }

    @GetMapping("/sellers/{sellerId}/orders/{orderId}/refund-record")
    public OrderRefundSummaryResponse refundSummary(@PathVariable UUID sellerId, @PathVariable UUID orderId) {
        return refunds.find(sellerId, orderId);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse find(@PathVariable UUID orderId) {
        return orders.find(orderId);
    }
}
