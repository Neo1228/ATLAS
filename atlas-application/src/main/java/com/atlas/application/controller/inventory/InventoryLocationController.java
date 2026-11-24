package com.atlas.application.controller.inventory;

import com.atlas.application.dto.request.InventoryHttpRequest;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}")
public class InventoryLocationController {
    private final InventoryService inventory;
    private final InventoryCapacityAdjustmentService adjustments;

    @PostMapping("/fulfillment-locations")
    public InventoryResponse.Location create(@PathVariable UUID sellerId,
            @Valid @RequestBody InventoryHttpRequest.CreateFulfillmentLocation request) {
        return inventory.createLocation(
                new InventoryRequest.CreateFulfillmentLocation(sellerId, request.code(), request.name(), request.priority()));
    }

    @GetMapping("/fulfillment-locations")
    public List<InventoryResponse.Location> find(@PathVariable UUID sellerId) {
        return inventory.findLocations(sellerId).stream().toList();
    }

    @PutMapping("/fulfillment-locations/{locationId}")
    public InventoryResponse.Location update(@PathVariable UUID sellerId, @PathVariable UUID locationId,
            @Valid @RequestBody InventoryHttpRequest.UpdateFulfillmentLocation request) {
        return inventory.updateLocation(new InventoryRequest.UpdateFulfillmentLocation(
                sellerId, locationId, request.name(), request.priority(), request.active()));
    }

    @GetMapping("/inventory/locations/{locationId}")
    public InventoryResponse.LocationInventoryPage inventory(@PathVariable UUID sellerId,
            @PathVariable UUID locationId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return 
                inventory.findLocationInventoryPage(sellerId, locationId, limit, after);
    }

    @PostMapping("/inventory/locations/{locationId}/skus/{skuId}/capacity-adjustments")
    public InventoryResponse.Adjustment adjust(@PathVariable UUID sellerId, @PathVariable UUID locationId,
            @PathVariable String skuId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryHttpRequest.AdjustInventoryCapacity request) {
        return adjustments.adjust(sellerId, skuId, locationId,
                idempotencyKey, request.delta(), request.reason());
    }

    @PostMapping("/inventory/transfers")
    public InventoryResponse.Transfer transfer(@PathVariable UUID sellerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryHttpRequest.TransferInventory request) {
        return inventory.transfer(new InventoryRequest.TransferInventory(sellerId,
                request.skuId(), request.sourceLocationId(), request.destinationLocationId(), request.quantity(),
                idempotencyKey, request.reason()));
    }
}
