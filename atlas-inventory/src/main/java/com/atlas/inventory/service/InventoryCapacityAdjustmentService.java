package com.atlas.inventory.service;

import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
import java.util.UUID;

public interface InventoryCapacityAdjustmentService {
    InventoryResponse.Adjustment adjust(InventoryRequest.AdjustInventoryCapacity request);
    InventoryResponse.Adjustment adjust(UUID sellerId,String skuId,UUID locationId,String idempotencyKey,int delta,String reason);
}
