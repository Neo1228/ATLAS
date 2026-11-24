package com.atlas.application.controller.order;

import com.atlas.application.dto.request.OrderHttpRequest;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.request.OrderRequest;
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
    public OrderViewResponse.Shipment create(@PathVariable UUID sellerId, @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderHttpRequest.Shipment request) {
        return shipments.create(new OrderRequest.Shipment(sellerId, orderId, idempotencyKey,
                request.carrierCode(), request.trackingNumber(),
                request.items().stream().map(item -> new OrderRequest.Shipment.Item(
                        item.orderItemId(), item.quantity())).toList(), request.fulfillmentLocationId()));
    }

    @GetMapping("/buyers/{buyerRef}/orders/{orderId}/shipments")
    public List<OrderViewResponse.Shipment> findAll(@PathVariable String buyerRef, @PathVariable UUID orderId) {
        return shipments.findAll(buyerRef, orderId);
    }

    @PutMapping("/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery")
    public OrderViewResponse.Shipment deliver(@PathVariable UUID sellerId, @PathVariable UUID orderId,
            @PathVariable UUID shipmentId) {
        return shipments.deliver(sellerId, orderId, shipmentId);
    }
}
