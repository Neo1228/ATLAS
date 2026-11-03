package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import java.util.Optional;
import java.util.UUID;

public interface InventoryService {

    void createInventoryPool(CreateInventoryPoolRequest request);

    ReservationResponse reserve(ReserveInventoryRequest request);

    ReservationResponse claim(UUID orderId);
    Optional<ReservationResponse> tryReserve(ReserveInventoryRequest request);
    ReservationResponse findReservation(UUID orderId);

    ReservationResponse release(UUID orderId);

    InventoryPoolResponse find(UUID sellerId, String skuId);
}
