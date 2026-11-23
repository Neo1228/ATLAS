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

    @Transactional
    public InventoryReservationResponse reserve(UUID fulfillmentLocationId, String idempotencyKey,
            InventoryReservationRequest request) {
        validateLocation(fulfillmentLocationId);
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.orderId() == null) throw invalid("Order id is required");
        List<ItemRecord> items = normalize(request.items());
        Instant now = clock.instant();
        UUID reservationId = UUID.randomUUID();
        int inserted = repository.insertReservation(reservationId, fulfillmentLocationId, request.orderId(),
                idempotencyKey, now, now.plus(reservationTtl));
        if (inserted == 0) {
            ReservationRecord prior = repository.findByIdempotencyKeyForUpdate(
                    fulfillmentLocationId, idempotencyKey);
            if (prior == null) throw new IllegalStateException("Conflicting reservation disappeared");
            if (!prior.orderId().equals(request.orderId())) {
                throw new InventoryLocationException(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT,
                        "Idempotency key was used for a different order");
            }
            if ("CANCELLED".equals(prior.status())) {
                throw new InventoryLocationException(InventoryLocationException.Code.RESERVATION_CANCELLED,
                        "Reservation key was cancelled before or after reserve");
            }
            if (!prior.items().equals(items)) {
                throw new InventoryLocationException(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT,
                        "Idempotency key was used for different reservation facts");
            }
            return response(prior, true);
        }

        for (ItemRecord item : items) {
            if (repository.reserveAvailable(fulfillmentLocationId, item.sellerId(), item.skuId(), item.quantity()) != 1) {
                throw new InventoryLocationException(InventoryLocationException.Code.INSUFFICIENT_STOCK,
                        "Insufficient available stock for SKU " + item.skuId());
            }
        }
        for (ItemRecord item : items) {
            repository.insertItem(reservationId, item.sellerId(), item.skuId(), item.quantity());
        }
        ReservationRecord created = repository.find(fulfillmentLocationId, reservationId);
        if (created == null) throw new IllegalStateException("Created reservation disappeared");
        return response(created, false);
    }

    @Transactional
    public InventoryReservationResponse cancel(UUID fulfillmentLocationId, String idempotencyKey,
            InventoryCancellationRequest request) {
        validateLocation(fulfillmentLocationId);
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.orderId() == null) throw invalid("Order id is required");
        ReservationRecord reservation = repository.findByIdempotencyKeyForUpdate(
                fulfillmentLocationId, idempotencyKey);
        if (reservation == null) {
            Instant now = clock.instant();
            UUID tombstoneId = UUID.randomUUID();
            int inserted = repository.insertCancellationTombstone(tombstoneId, fulfillmentLocationId,
                    request.orderId(), idempotencyKey, now, now.plus(reservationTtl));
            if (inserted == 1) {
                ReservationRecord tombstone = repository.find(fulfillmentLocationId, tombstoneId);
                if (tombstone == null) throw new IllegalStateException("Cancellation tombstone disappeared");
                return response(tombstone, false);
            }
            reservation = repository.findByIdempotencyKeyForUpdate(fulfillmentLocationId, idempotencyKey);
            if (reservation == null) throw new IllegalStateException("Concurrent reservation disappeared");
        }
        if (!reservation.orderId().equals(request.orderId())) {
            throw new InventoryLocationException(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT,
                    "Idempotency key was used for a different order");
        }
        if ("CANCELLED".equals(reservation.status())
                || "RELEASED".equals(reservation.status())
                || "EXPIRED".equals(reservation.status())) {
            return response(reservation, true);
        }
        if (!"RESERVED".equals(reservation.status())) {
            throw invalidState("Claimed stock cannot be cancelled; use the refund workflow");
        }
        for (ItemRecord item : reservation.items()) {
            if (repository.releaseReserved(fulfillmentLocationId, item.sellerId(), item.skuId(), item.quantity()) != 1) {
                throw new IllegalStateException("Reserved stock is inconsistent with its reservation");
            }
        }
        transition(reservation.reservationId(), "CANCELLED");
        return response(requireForUpdate(fulfillmentLocationId, reservation.reservationId()), false);
    }


    @Transactional(readOnly = true)
    public InventoryReservationResponse findReservation(UUID fulfillmentLocationId, UUID reservationId) {
        validateLocation(fulfillmentLocationId);
        ReservationRecord reservation = repository.find(fulfillmentLocationId, reservationId);
        if (reservation == null) throw notFound("Reservation not found");
        return response(reservation, false);
    }

    @Transactional
    public InventoryReservationResponse claim(UUID fulfillmentLocationId, UUID reservationId) {
        validateLocation(fulfillmentLocationId);
        ReservationRecord reservation = requireForUpdate(fulfillmentLocationId, reservationId);
        if ("CLAIMED".equals(reservation.status())) return response(reservation, true);
        if (!"RESERVED".equals(reservation.status())) {
            throw invalidState("Only reserved stock can be claimed");
        }
        if (!reservation.expiresAt().isAfter(clock.instant())) {
            throw new InventoryLocationException(InventoryLocationException.Code.RESERVATION_EXPIRED,
                    "Reservation has expired");
        }
        for (ItemRecord item : reservation.items()) {
            if (repository.claimReserved(fulfillmentLocationId, item.sellerId(), item.skuId(), item.quantity()) != 1) {
                throw new IllegalStateException("Reserved stock is inconsistent with its reservation");
            }
        }
        transition(reservation.reservationId(), "CLAIMED");
        return response(requireForUpdate(fulfillmentLocationId, reservationId), false);
    }

    /**
     * 결제 매입이 확정되지 않은 claim을 되돌린다. claim 뒤 매입 거절·취소가 발생하면 판매되지 않은
     * 재고를 다시 판매 가능 수량으로 돌려야 하므로 CLAIMED만 RELEASED로 전이한다. 반복 호출은 멱등이다.
     */
    @Transactional
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
    public InventoryReservationResponse release(UUID fulfillmentLocationId, UUID reservationId) {
        validateLocation(fulfillmentLocationId);
        ReservationRecord reservation = requireForUpdate(fulfillmentLocationId, reservationId);
        if ("RELEASED".equals(reservation.status()) || "EXPIRED".equals(reservation.status())
                || "CANCELLED".equals(reservation.status())) {
            return response(reservation, true);
        }
        if (!"RESERVED".equals(reservation.status())) {
            throw invalidState("Only reserved stock can be released");
        }
        for (ItemRecord item : reservation.items()) {
            if (repository.releaseReserved(fulfillmentLocationId, item.sellerId(), item.skuId(), item.quantity()) != 1) {
                throw new IllegalStateException("Reserved stock is inconsistent with its reservation");
            }
        }
        transition(reservation.reservationId(), "RELEASED");
        return response(requireForUpdate(fulfillmentLocationId, reservationId), false);
    }

    @Transactional
    public int expireDue(int limit) {
        if (limit < 1 || limit > 1000) throw invalid("Expiry batch size must be between 1 and 1000");
        List<ReservationRecord> due = repository.findDueForUpdate(
                ownedFulfillmentLocationId, clock.instant(), limit);
        for (ReservationRecord reservation : due) {
            for (ItemRecord item : reservation.items()) {
                if (repository.releaseReserved(reservation.fulfillmentLocationId(), item.sellerId(), item.skuId(),
                        item.quantity()) != 1) {
                    throw new IllegalStateException("Reserved stock is inconsistent with an expired reservation");
                }
            }
            transition(reservation.reservationId(), "EXPIRED");
        }
        return due.size();
    }

    private ReservationRecord requireForUpdate(UUID fulfillmentLocationId, UUID reservationId) {
        ReservationRecord reservation = repository.findForUpdate(fulfillmentLocationId, reservationId);
        if (reservation == null) throw notFound("Reservation not found");
        return reservation;
    }

    private void transition(UUID reservationId, String status) {
        if (repository.transitionReserved(reservationId, status) != 1) {
            throw new IllegalStateException("Reservation state changed while locked");
        }
    }

    private List<ItemRecord> normalize(List<Item> requestedItems) {
 if (requestedItems == null || requestedItems.isEmpty()) throw invalid("At least one item is required");
 Map<String, ItemRecord> sorted = new TreeMap<>();
 for (Item item : requestedItems) {
  if (item == null || item.sellerId() == null || item.quantity() < 1) throw invalid("Seller id and positive quantity are required");
  validateSku(item.skuId());
  String key = item.sellerId() + ":" + item.skuId();
  if (sorted.putIfAbsent(key, new ItemRecord(item.sellerId(), item.skuId(), item.quantity())) != null) {
   throw invalid("Duplicate seller/SKU lines are not allowed");
  }
 }
 return List.copyOf(sorted.values());
 }

 private InventoryReservationResponse response(ReservationRecord reservation, boolean replayed) {
        List<InventoryReservationResponse.Item> items = reservation.items().stream()
                .map(item -> new InventoryReservationResponse.Item(item.sellerId(), item.skuId(), item.quantity())).toList();
        return new InventoryReservationResponse(reservation.reservationId(), reservation.fulfillmentLocationId(),
                reservation.orderId(), reservation.idempotencyKey(), reservation.status(), items,
                reservation.createdAt(), reservation.expiresAt(), replayed);
    }

    private InventoryStockResponse stockResponse(StockRecord stock) {
        return new InventoryStockResponse(stock.fulfillmentLocationId(), stock.sellerId(), stock.skuId(), stock.initialCapacity(),
                stock.availableQuantity(), stock.reservedQuantity(), stock.claimedQuantity());
    }

    private void validateLocation(UUID fulfillmentLocationId) {
        if (fulfillmentLocationId == null || !ownedFulfillmentLocationId.equals(fulfillmentLocationId)) {
            throw notFound("Fulfillment location not found");
        }
    }

    private void validateSku(String skuId) {
        if (skuId == null || !SKU_IDENTIFIER.matcher(skuId).matches()) throw invalid("SKU id is invalid");
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw invalid("Idempotency-Key must contain between 1 and 128 characters");
        }
    }

    private InventoryLocationException invalid(String message) {
        return new InventoryLocationException(InventoryLocationException.Code.INVALID_REQUEST, message);
    }

    private InventoryLocationException invalidState(String message) {
        return new InventoryLocationException(InventoryLocationException.Code.INVALID_STATE, message);
    }

    private InventoryLocationException notFound(String message) {
        return new InventoryLocationException(InventoryLocationException.Code.NOT_FOUND, message);
    }
}
