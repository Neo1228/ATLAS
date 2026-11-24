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
import com.atlas.inventory.dto.response.LocationInventoryPageResponse;
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

    /**
     * 위치별 재고를 SKU 순서의 keyset 페이지로 조회한다. 운영 API는 SKU가 많은 위치에서 전체를 한 번에 읽지
     * 않도록 이 메서드를 쓴다.
     *
     * @param limit 페이지 크기. null이면 기본값, 허용 범위를 벗어나면 INVALID_REQUEST
     * @param afterSkuId 이전 페이지의 {@code nextCursor}. null이면 첫 페이지
     */
    LocationInventoryPageResponse findLocationInventoryPage(
            UUID sellerId, UUID locationId, Integer limit, String afterSkuId);
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
