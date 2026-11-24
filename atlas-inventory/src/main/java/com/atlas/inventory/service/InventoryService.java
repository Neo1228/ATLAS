package com.atlas.inventory.service;

import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InventoryService {
    void createInventoryPool(InventoryRequest.CreateInventoryPool request);
    InventoryResponse.Location createLocation(InventoryRequest.CreateFulfillmentLocation request);
    List<InventoryResponse.Location> findLocations(UUID sellerId);
    InventoryResponse.Location updateLocation(InventoryRequest.UpdateFulfillmentLocation request);
    List<InventoryResponse.LocationInventory> findLocationInventory(UUID sellerId, UUID locationId);

    /**
     * 위치별 재고를 SKU 순서의 keyset 페이지로 조회한다. 운영 API는 SKU가 많은 위치에서 전체를 한 번에 읽지
     * 않도록 이 메서드를 쓴다.
     *
     * @param limit 페이지 크기. null이면 기본값, 허용 범위를 벗어나면 INVALID_REQUEST
     * @param afterSkuId 이전 페이지의 {@code nextCursor}. null이면 첫 페이지
     */
    InventoryResponse.LocationInventoryPage findLocationInventoryPage(
            UUID sellerId, UUID locationId, Integer limit, String afterSkuId);
    Optional<UUID> findFulfillmentLocation(UUID sellerId, List<InventoryRequest.ReserveInventoryBatch.Item> items);
    InventoryResponse.Transfer transfer(InventoryRequest.TransferInventory request);
    InventoryResponse.Reservation reserve(InventoryRequest.ReserveInventory request);
    List<InventoryResponse.Reservation> reserveAll(InventoryRequest.ReserveInventoryBatch request);
    InventoryResponse.Reservation claim(UUID orderId);
    List<InventoryResponse.Reservation> claimAll(UUID orderId);
    Optional<InventoryResponse.Reservation> tryReserve(InventoryRequest.ReserveInventory request);
    InventoryResponse.Reservation findReservation(UUID orderId);
    List<InventoryResponse.Reservation> findReservations(UUID orderId);

    /** 여러 주문의 reservation을 한 번의 조회로 반환한다. 읽기 전용 응답 조립용이다. */
    Map<UUID, List<InventoryResponse.Reservation>> findReservationsByOrderIds(Collection<UUID> orderIds);
    InventoryResponse.Reservation release(UUID orderId);
    List<InventoryResponse.Reservation> releaseAll(UUID orderId);
    void restoreClaimed(UUID orderId, UUID sellerId, String skuId, int quantity);
    InventoryResponse.Pool find(UUID sellerId, String skuId);
    List<InventoryResponse.Pool> findAll(UUID sellerId);
}
