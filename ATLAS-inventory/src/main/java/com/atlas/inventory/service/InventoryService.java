package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.CreateFulfillmentLocationRequest;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.dto.request.TransferInventoryRequest;
import com.atlas.inventory.dto.request.UpdateFulfillmentLocationRequest;
import com.atlas.inventory.dto.response.InventoryLocationResponse;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.dto.response.InventoryTransferResponse;
import com.atlas.inventory.dto.response.LocationInventoryResponse;
import com.atlas.inventory.dto.response.ReservationResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InventoryService {
    void createInventoryPool(CreateInventoryPoolRequest request);
    InventoryLocationResponse createLocation(CreateFulfillmentLocationRequest request);
    List<InventoryLocationResponse> findLocations(UUID sellerId);
    InventoryLocationResponse updateLocation(UpdateFulfillmentLocationRequest request);
    List<LocationInventoryResponse> findLocationInventory(UUID sellerId, UUID locationId);
    Optional<UUID> findFulfillmentLocation(UUID sellerId, List<ReserveInventoryBatchRequest.Item> items);
    InventoryTransferResponse transfer(TransferInventoryRequest request);
    ReservationResponse reserve(ReserveInventoryRequest request);
    List<ReservationResponse> reserveAll(ReserveInventoryBatchRequest request);
    ReservationResponse claim(UUID orderId);
    List<ReservationResponse> claimAll(UUID orderId);
    Optional<ReservationResponse> tryReserve(ReserveInventoryRequest request);
    ReservationResponse findReservation(UUID orderId);
    List<ReservationResponse> findReservations(UUID orderId);

    /** 여러 주문의 reservation을 한 번의 조회로 반환한다. 읽기 전용 응답 조립용이다. */
    Map<UUID, List<ReservationResponse>> findReservationsByOrderIds(Collection<UUID> orderIds);
    ReservationResponse release(UUID orderId);
    List<ReservationResponse> releaseAll(UUID orderId);
    void restoreClaimed(UUID orderId, UUID sellerId, String skuId, int quantity);
    InventoryPoolResponse find(UUID sellerId, String skuId);
    List<InventoryPoolResponse> findAll(UUID sellerId);
}
