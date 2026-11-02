package com.atlas.order.web;

import com.atlas.order.OrderApi;
import com.atlas.order.OrderView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderApi orders;

    public OrderController(OrderApi orders) {
        this.orders = orders;
    }

    @PostMapping("/campaigns/{campaignId}/tickets/{ticketId}/checkout")
    public OrderView startCheckout(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        return orders.startCheckout(campaignId, ticketId);
    }

    @PostMapping("/orders/{orderId}/payment")
    public OrderView submitPayment(@PathVariable UUID orderId) {
        return orders.submitPayment(orderId);
    }

    @PostMapping("/orders/{orderId}/reconcile")
    public OrderView reconcile(@PathVariable UUID orderId) {
        return orders.reconcilePayment(orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderView cancel(@PathVariable UUID orderId) {
        return orders.cancel(orderId);
    }

    @GetMapping("/orders/{orderId}")
    public OrderView find(@PathVariable UUID orderId) {
        return orders.find(orderId);
    }
}
