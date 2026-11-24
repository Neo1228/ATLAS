package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import java.util.UUID;

public interface InventoryCapacityAdjustmentService {
    InventoryAdjustmentResponse adjust(AdjustInventoryCapacityRequest request);
    InventoryAdjustmentResponse adjust(UUID sellerId,String skuId,UUID locationId,String idempotencyKey,int delta,String reason);
}
