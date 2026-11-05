package com.atlas.application.controller.seller;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
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
    public InventoryAdjustmentResponse adjust(@PathVariable UUID sellerId, @PathVariable String skuId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdjustInventoryCapacityRequest request) {
        return adjustments.adjust(sellerId, skuId, idempotencyKey, request);
    }
}
