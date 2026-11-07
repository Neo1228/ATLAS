package com.atlas.application.controller.order;

import com.atlas.application.controller.dto.OrderShipmentHttpRequest;
import com.atlas.order.dto.request.OrderShipmentRequest;
import com.atlas.order.dto.response.OrderShipmentResponse;
import com.atlas.order.service.OrderShipmentService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class OrderShipmentController {
    private final OrderShipmentService shipments;

    @PostMapping("/sellers/{sellerId}/orders/{orderId}/shipments")
    public OrderShipmentResponse create(@PathVariable UUID sellerId, @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderShipmentHttpRequest request) {
        return shipments.create(new OrderShipmentRequest(sellerId, orderId, idempotencyKey,
                request.carrierCode(), request.trackingNumber(),
                request.items().stream().map(item -> new OrderShipmentRequest.Item(
                        item.orderItemId(), item.quantity())).toList()));
    }

    @GetMapping("/buyers/{buyerRef}/orders/{orderId}/shipments")
    public List<OrderShipmentResponse> findAll(@PathVariable String buyerRef, @PathVariable UUID orderId) {
        return shipments.findAll(buyerRef, orderId);
    }

    @PutMapping("/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery")
    public OrderShipmentResponse deliver(@PathVariable UUID sellerId, @PathVariable UUID orderId,
            @PathVariable UUID shipmentId) {
        return shipments.deliver(sellerId, orderId, shipmentId);
    }
}
