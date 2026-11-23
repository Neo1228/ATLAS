package com.atlas.inventory.location.service;

import com.atlas.inventory.location.exception.InventoryLocationException;

import com.atlas.inventory.location.dto.request.InventoryReservationRequest;
import com.atlas.inventory.location.dto.request.InventoryCancellationRequest;
import com.atlas.inventory.location.dto.request.InventoryReservationRequest.Item;
import com.atlas.inventory.location.dto.request.SetLocationStockRequest;
import com.atlas.inventory.location.dto.response.InventoryReservationResponse;
import com.atlas.inventory.location.dto.response.InventoryStockResponse;
import com.atlas.inventory.location.repository.InventoryReservationRepository;
import com.atlas.inventory.location.repository.InventoryReservationRepository.ItemRecord;
import com.atlas.inventory.location.repository.InventoryReservationRepository.ReservationRecord;
import com.atlas.inventory.location.repository.InventoryReservationRepository.StockRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReservationService {
    private static final Pattern SKU_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,96}");

    private final InventoryReservationRepository repository;
    private final Clock clock;
    private final Duration reservationTtl;
    private final UUID ownedFulfillmentLocationId;

    public InventoryReservationService(InventoryReservationRepository repository, Clock clock,
            @Value("${inventory.reservation-ttl:PT2M}") Duration reservationTtl,
            @Value("${inventory.fulfillment-location-id}") UUID ownedFulfillmentLocationId) {
        this.repository = repository;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
        this.ownedFulfillmentLocationId = ownedFulfillmentLocationId;
        if (reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("Reservation TTL must be positive");
        }
    }

    @Transactional
    public InventoryStockResponse setInitialCapacity(UUID fulfillmentLocationId, UUID sellerId, String skuId,
            SetLocationStockRequest request) {
        validateLocation(fulfillmentLocationId);
        validateSku(skuId);
        if (sellerId == null || request == null || request.sellerId() == null || !sellerId.equals(request.sellerId())) {
            throw invalid("Path seller id must match request seller id");
        }
        if (request.initialCapacity() < 0) throw invalid("Initial capacity must be non-negative");
        if (repository.setInitialCapacity(fulfillmentLocationId, sellerId, skuId, request.initialCapacity()) != 1) {
            throw new InventoryLocationException(InventoryLocationException.Code.INVALID_STATE,
                    "Stock cannot be reset while units are reserved or claimed");
        }
        return stockResponse(repository.findStock(fulfillmentLocationId, sellerId, skuId));
    }

    @Transactional(readOnly = true)
    public InventoryStockResponse findStock(UUID fulfillmentLocationId, UUID sellerId, String skuId) {
        validateLocation(fulfillmentLocationId);
        if (sellerId == null) throw invalid("Seller id is required");
        validateSku(skuId);
        StockRecord stock = repository.findStock(fulfillmentLocationId, sellerId, skuId);
        if (stock == null) throw notFound("Stock not found");
        return stockResponse(stock);
    }
    /** 라우팅용 조회. 없는 판매자·SKU pool은 0으로 보고, 알 수 없는 location은 계속 NOT_FOUND다. */
    @Transactional(readOnly = true)
    public long availableStock(UUID fulfillmentLocationId, UUID sellerId, String skuId) {
        validateLocation(fulfillmentLocationId);
        if (sellerId == null) throw invalid("Seller id is required");
        validateSku(skuId);
        StockRecord stock = repository.findStock(fulfillmentLocationId, sellerId, skuId);
        return stock == null ? 0 : stock.availableQuantity();
    }
    /**
     * 결제 매입이 확정되지 않은 claim을 되돌린다. claim 뒤 매입 거절·취소가 발생하면 판매되지 않은
     * 재고를 다시 판매 가능 수량으로 돌려야 하므로 CLAIMED만 RELEASED로 전이한다. 반복 호출은 멱등이다.
     */
    public InventoryReservationResponse restore(UUID fulfillmentLocationId, UUID reservationId) {
        validateLocation(fulfillmentLocationId);
        ReservationRecord reservation = requireForUpdate(fulfillmentLocationId, reservationId);
        if ("RELEASED".equals(reservation.status())) return response(reservation, true);
        if (!"CLAIMED".equals(reservation.status())) {
            throw invalidState("Only claimed stock can be restored");
        }
        for (ItemRecord item : reservation.items()) {
            if (repository.restockedQuantity(reservationId, item.sellerId(), item.skuId()) > 0) {
                throw invalidState("Partially restocked reservations cannot be restored as a whole");
            }
        }
        for (ItemRecord item : reservation.items()) {
            if (repository.restoreClaimed(fulfillmentLocationId, item.sellerId(), item.skuId(), item.quantity()) != 1) {
                throw new IllegalStateException("Claimed stock is inconsistent with its reservation");
            }
        }
        if (repository.transitionClaimed(reservation.reservationId(), "RELEASED") != 1) {
            throw new IllegalStateException("Reservation state changed while locked");
        }
        return response(requireForUpdate(fulfillmentLocationId, reservationId), false);
    }

    /**
     * 환불·반품 검수 통과로 판매 확정(CLAIMED) 재고 일부를 판매 가능 수량으로 되돌린다.
     * 같은 restock key는 한 번만 적용되며, 다른 내용으로 재사용하면 거절한다. item별 누적 restock 수량은
     * reservation item 수량을 넘지 못한다. reservation row lock으로 같은 reservation의 동시 restock을 직렬화한다.
     */
    @Transactional
    public InventoryReservationResponse restock(UUID fulfillmentLocationId, UUID reservationId, String restockKey,
                                                List<Item> requestedItems) {
        validateLocation(fulfillmentLocationId);
        validateIdempotencyKey(restockKey);
        List<ItemRecord> requested = normalize(requestedItems);
        ReservationRecord reservation = requireForUpdate(fulfillmentLocationId, reservationId);
        UUID existingReservation = repository.findRestockReservation(restockKey);
        if (existingReservation != null) {
            if (!existingReservation.equals(reservationId)
                    || !sameItems(repository.findRestock(restockKey), requested)) {
                throw new InventoryLocationException(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT,
                        "Restock key was used for different facts");
            }
            return response(reservation, true);
        }
        if (!"CLAIMED".equals(reservation.status())) throw invalidState("Only claimed stock can be restocked");
        Map<String, ItemRecord> sold = new TreeMap<>();
        for (ItemRecord item : reservation.items()) sold.put(item.sellerId() + ":" + item.skuId(), item);
        Instant now = clock.instant();
        for (ItemRecord item : requested) {
            ItemRecord soldItem = sold.get(item.sellerId() + ":" + item.skuId());
            if (soldItem == null) throw invalid("Restock item is not part of the reservation");
            int already = repository.restockedQuantity(reservationId, item.sellerId(), item.skuId());
            if (already + item.quantity() > soldItem.quantity()) {
                throw invalidState("Restock quantity exceeds the sold quantity");
            }
            if (repository.restoreClaimed(fulfillmentLocationId, item.sellerId(), item.skuId(), item.quantity()) != 1) {
                throw new IllegalStateException("Claimed stock is inconsistent with its reservation");
            }
            repository.insertRestock(restockKey, reservationId, item.sellerId(), item.skuId(), item.quantity(), now);
        }
        return response(reservation, false);
    }

    private static boolean sameItems(List<ItemRecord> stored, List<ItemRecord> requested) {
        Map<String, Integer> left = new TreeMap<>();
        for (ItemRecord item : stored) left.put(item.sellerId() + ":" + item.skuId(), item.quantity());
        Map<String, Integer> right = new TreeMap<>();
        for (ItemRecord item : requested) right.put(item.sellerId() + ":" + item.skuId(), item.quantity());
        return left.equals(right);
    }

    @Transactional