package com.atlas.application.controller.order;

import com.atlas.order.dto.response.OrderFulfillmentResponse;
import com.atlas.order.service.OrderFulfillmentService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class OrderFulfillmentController {
    private final OrderFulfillmentService fulfillments;

    @GetMapping("/orders/{orderId}/fulfillment")
    public OrderFulfillmentResponse find(@PathVariable UUID orderId) {
        return fulfillments.find(orderId);
    }

    @PostMapping("/sellers/{sellerId}/orders/{orderId}/fulfillment/start")
    public OrderFulfillmentResponse start(@PathVariable UUID sellerId, @PathVariable UUID orderId) {
        return fulfillments.start(sellerId, orderId);
    }
}
