package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;

public interface InventoryCapacityAdjustmentService {
    InventoryAdjustmentResponse adjust(AdjustInventoryCapacityRequest request);
}
