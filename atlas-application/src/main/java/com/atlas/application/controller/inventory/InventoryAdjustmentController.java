package com.atlas.application.controller.inventory;

import com.atlas.application.dto.request.InventoryHttpRequest;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/inventory/{skuId}/capacity-adjustments")
public class InventoryAdjustmentController {
    private final InventoryCapacityAdjustmentService adjustments;

    @PostMapping
    public InventoryResponse.Adjustment adjust(@PathVariable UUID sellerId, @PathVariable String skuId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryHttpRequest.AdjustInventoryCapacity request) {
        return adjustments.adjust(new InventoryRequest.AdjustInventoryCapacity(sellerId, skuId, idempotencyKey,
                request.delta(), request.reason()));
    }
}
