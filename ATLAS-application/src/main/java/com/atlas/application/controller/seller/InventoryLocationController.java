package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.AdjustInventoryCapacityHttpRequest;
import com.atlas.application.controller.dto.CreateFulfillmentLocationHttpRequest;
import com.atlas.application.controller.dto.InventoryAdjustmentHttpResponse;
import com.atlas.application.controller.dto.InventoryLocationHttpResponse;
import com.atlas.application.controller.dto.InventoryTransferHttpResponse;
import com.atlas.application.controller.dto.LocationInventoryHttpResponse;
import com.atlas.application.controller.dto.TransferInventoryHttpRequest;
import com.atlas.application.controller.dto.UpdateFulfillmentLocationHttpRequest;
import com.atlas.inventory.dto.request.CreateFulfillmentLocationRequest;
import com.atlas.inventory.dto.request.TransferInventoryRequest;
import com.atlas.inventory.dto.request.UpdateFulfillmentLocationRequest;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import com.atlas.inventory.service.InventoryService;
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
@RequestMapping("/api/sellers/{sellerId}")
public class InventoryLocationController {
    private final InventoryService inventory;
    private final InventoryCapacityAdjustmentService adjustments;

    @PostMapping("/fulfillment-locations")
    public InventoryLocationHttpResponse create(@PathVariable UUID sellerId,
            @Valid @RequestBody CreateFulfillmentLocationHttpRequest request) {
        return InventoryLocationHttpResponse.from(inventory.createLocation(
                new CreateFulfillmentLocationRequest(sellerId, request.code(), request.name(), request.priority())));
    }

    @GetMapping("/fulfillment-locations")
    public List<InventoryLocationHttpResponse> find(@PathVariable UUID sellerId) {
        return inventory.findLocations(sellerId).stream().map(InventoryLocationHttpResponse::from).toList();
    }

    @PutMapping("/fulfillment-locations/{locationId}")
    public InventoryLocationHttpResponse update(@PathVariable UUID sellerId, @PathVariable UUID locationId,
            @Valid @RequestBody UpdateFulfillmentLocationHttpRequest request) {
        return InventoryLocationHttpResponse.from(inventory.updateLocation(new UpdateFulfillmentLocationRequest(
                sellerId, locationId, request.name(), request.priority(), request.active())));
    }

    @GetMapping("/inventory/locations/{locationId}")
    public List<LocationInventoryHttpResponse> inventory(@PathVariable UUID sellerId,
            @PathVariable UUID locationId) {
        return inventory.findLocationInventory(sellerId, locationId).stream()
                .map(LocationInventoryHttpResponse::from).toList();
    }

    @PostMapping("/inventory/locations/{locationId}/skus/{skuId}/capacity-adjustments")
    public InventoryAdjustmentHttpResponse adjust(@PathVariable UUID sellerId, @PathVariable UUID locationId,
            @PathVariable String skuId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AdjustInventoryCapacityHttpRequest request) {
        return InventoryAdjustmentHttpResponse.from(adjustments.adjust(sellerId, skuId, locationId,
                idempotencyKey, request.delta(), request.reason()));
    }

    @PostMapping("/inventory/transfers")
    public InventoryTransferHttpResponse transfer(@PathVariable UUID sellerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferInventoryHttpRequest request) {
        return InventoryTransferHttpResponse.from(inventory.transfer(new TransferInventoryRequest(sellerId,
                request.skuId(), request.sourceLocationId(), request.destinationLocationId(), request.quantity(),
                idempotencyKey, request.reason())));
    }
}
