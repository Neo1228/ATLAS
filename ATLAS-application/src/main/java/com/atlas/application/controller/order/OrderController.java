package com.atlas.application.controller.order;

import com.atlas.application.controller.dto.ReorderHttpRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.service.OrderReorderService;
import com.atlas.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/campaigns/{campaignId}/tickets/{ticketId}/checkout")
    public OrderResponse startCheckout(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        return orders.startCheckout(campaignId, ticketId);
    }

    @PostMapping("/orders/{orderId}/payment")
    public OrderResponse submitPayment(@PathVariable UUID orderId) {
        return orders.submitPayment(orderId);
    }

    @PostMapping("/orders/{orderId}/reconcile")
    public OrderResponse reconcile(@PathVariable UUID orderId) {
        return orders.reconcilePayment(orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable UUID orderId) {
        return orders.cancel(orderId);
    }

    @PostMapping("/orders/{orderId}/reorder")
    public CartResponse reorder(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReorderHttpRequest request) {
        return reorders.reorder(orderId, idempotencyKey, request.confirmExistingCartMerge());
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse find(@PathVariable UUID orderId) {
        return orders.find(orderId);
    }
}
