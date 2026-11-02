package com.atlas.application.controller.order;

import com.atlas.order.service.OrderService;
import com.atlas.order.dto.response.OrderResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

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

    @GetMapping("/orders/{orderId}")
    public OrderResponse find(@PathVariable UUID orderId) {
        return orders.find(orderId);
    }
}
