package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.dto.response.ReservationResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryService {
    void createInventoryPool(CreateInventoryPoolRequest request);
    ReservationResponse reserve(ReserveInventoryRequest request);
    List<ReservationResponse> reserveAll(ReserveInventoryBatchRequest request);
    ReservationResponse claim(UUID orderId);
    List<ReservationResponse> claimAll(UUID orderId);
    Optional<ReservationResponse> tryReserve(ReserveInventoryRequest request);
    ReservationResponse findReservation(UUID orderId);
    List<ReservationResponse> findReservations(UUID orderId);
    ReservationResponse release(UUID orderId);
    List<ReservationResponse> releaseAll(UUID orderId);
    InventoryPoolResponse find(UUID sellerId, String skuId);
    List<InventoryPoolResponse> findAll(UUID sellerId, List<String> skuIds);
}
