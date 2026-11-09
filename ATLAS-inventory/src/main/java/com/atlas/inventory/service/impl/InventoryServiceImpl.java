package com.atlas.inventory.service.impl;

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
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.entity.Reservation;
import com.atlas.inventory.persistence.projection.InventoryLocationRecord;
import com.atlas.inventory.persistence.projection.InventoryPoolSummary;
import com.atlas.inventory.persistence.projection.InventoryTransferRecord;
import com.atlas.inventory.persistence.projection.InventoryTransferResult;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.repository.InventoryPoolRepository;
import com.atlas.inventory.repository.JdbcInventoryReadRepository;
import com.atlas.inventory.repository.JdbcLocationRepository;
import com.atlas.inventory.repository.JdbcStockRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.service.InventoryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryServiceImpl implements InventoryService {
    private final InventoryPoolRepository pools;
    private final ReservationRepository reservations;
    private final JdbcStockRepository stock;
    private final JdbcLocationRepository locations;
    private final JdbcInventoryReadRepository inventoryReads;
    private final TransactionTemplate transactions;

    @Autowired
    public InventoryServiceImpl(InventoryPoolRepository pools, ReservationRepository reservations,
            JdbcStockRepository stock, JdbcLocationRepository locations, JdbcInventoryReadRepository inventoryReads,
            PlatformTransactionManager transactionManager) {
        this.pools=Objects.requireNonNull(pools); this.reservations=Objects.requireNonNull(reservations);
        this.stock=Objects.requireNonNull(stock); this.locations=Objects.requireNonNull(locations);
        this.inventoryReads=Objects.requireNonNull(inventoryReads);
        this.transactions=new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override public void createInventoryPool(CreateInventoryPoolRequest request) {
        Objects.requireNonNull(request,"request is required"); validSku(request.sellerId(),request.skuId());
        if(request.initialStock()<0) throw invalid("initial stock must not be negative");
        transactions.executeWithoutResult(s->stock.ensurePool(request.sellerId(),request.skuId(),request.initialStock()));
    }
    @Override public InventoryLocationResponse createLocation(CreateFulfillmentLocationRequest r) {
        Objects.requireNonNull(r); validSeller(r.sellerId());
        if(blank(r.code())||r.code().length()>64||blank(r.name())||r.name().length()>128||r.priority()<0) throw invalid("invalid location code, name or priority");
        InventoryLocationRecord location = Objects.requireNonNull(transactions.execute(status ->
                locations.create(r.sellerId(), r.code(), r.name(), r.priority())))
                .orElseThrow(() -> conflict("fulfillment location code already exists for seller"));
        return locationResponse(location);
    }
    @Override
    public List<InventoryLocationResponse> findLocations(UUID sellerId) {
        validSeller(sellerId);
        return locations.find(sellerId).stream().map(InventoryServiceImpl::locationResponse).toList();
    }
    @Override public InventoryLocationResponse updateLocation(UpdateFulfillmentLocationRequest r){
        Objects.requireNonNull(r); validSeller(r.sellerId()); if(r.locationId()==null||blank(r.name())||r.name().length()>128||r.priority()<0) throw invalid("invalid location update");
        InventoryLocationRecord location = Objects.requireNonNull(transactions.execute(status ->
                locations.update(r.sellerId(), r.locationId(), r.name(), r.priority(), r.active())))
                .orElseThrow(() -> new InventoryException(
                        InventoryException.Code.LOCATION_NOT_FOUND, "fulfillment location not found"));
        return locationResponse(location);
    }
    @Override
    public List<LocationInventoryResponse> findLocationInventory(UUID sellerId, UUID locationId) {
        validSeller(sellerId);
        Objects.requireNonNull(locationId);
        return pools.findAllByIdSellerIdAndIdLocationIdOrderByIdSkuId(sellerId, locationId).stream()
                .map(pool -> new LocationInventoryResponse(sellerId, locationId, pool.getId().getSkuId(),
                        pool.getInitialCapacity(), pool.getAvailable(), pool.getReserved(), pool.getClaimed()))
                .toList();
    }
    @Override public Optional<UUID> findFulfillmentLocation(UUID sellerId,List<ReserveInventoryBatchRequest.Item> items){
        validSeller(sellerId); validateItems(sellerId,items);
        return locations.advisorySelect(sellerId, requiredQuantities(items));
    }
    @Override
    public InventoryTransferResponse transfer(TransferInventoryRequest request) {
        if (request == null) {
            throw invalid("Inventory transfer request is required");
        }
        validSku(request.sellerId(), request.skuId());
        if (request.sourceLocationId() == null || request.destinationLocationId() == null
                || Objects.equals(request.sourceLocationId(), request.destinationLocationId())
                || request.quantity() < 1 || blank(request.idempotencyKey())
                || request.idempotencyKey().length() > 256 || blank(request.reason())) {
            throw invalid("Invalid inventory transfer request");
        }
        if (inventoryReads.find(request.sellerId(), request.skuId()) == null) {
            throw new InventoryException(InventoryException.Code.POOL_NOT_FOUND,
                    "Inventory pool does not exist for seller SKU");
        }
        InventoryTransferResult result = Objects.requireNonNull(transactions.execute(status -> {
            InventoryTransferResult attempt = locations.transfer(
                    request.sellerId(), request.skuId(), request.sourceLocationId(),
                    request.destinationLocationId(), request.quantity(), request.idempotencyKey(),
                    request.reason());
            if (attempt.failure() != InventoryTransferResult.Failure.NONE) {
                status.setRollbackOnly();
            }
            return attempt;
        }));
        return switch (result.failure()) {
            case NONE -> transferResponse(Objects.requireNonNull(result.transfer()));
            case IDEMPOTENCY_CONFLICT -> throw conflict("transfer idempotency key was used with different facts");
            case SOURCE_UNAVAILABLE -> throw new InventoryException(
                    InventoryException.Code.INSUFFICIENT_STOCK, "source location has insufficient available stock");
            case DESTINATION_UNAVAILABLE -> throw conflict(
                    "destination location must be active and have capacity for the transfer");
        };
    }
    @Override
    public ReservationResponse reserve(ReserveInventoryRequest request) {
        return tryReserve(request).orElseThrow(() -> new InventoryException(
                InventoryException.Code.UNAVAILABLE, "inventory unavailable"));
    }

    @Override
    public Optional<ReservationResponse> tryReserve(ReserveInventoryRequest request) {
        Objects.requireNonNull(request);
        validSku(request.sellerId(), request.skuId());
        validOrder(request.orderId());
        if (request.quantity() < 1) throw invalid("quantity must be positive");

        return transactions.execute(status -> {
            stock.lockOrder(request.orderId());
            List<Reservation> prior = reservations.findLockedByOrderId(request.orderId());
            if (!prior.isEmpty()) {
                Reservation existing = prior.get(0);
                if (prior.size() != 1
                        || !Objects.equals(existing.getCampaignId(), request.campaignId())
                        || !existing.getSellerId().equals(request.sellerId())
                        || !existing.getSkuId().equals(request.skuId())
                        || existing.getQuantity() != request.quantity()) {
                    throw conflict("order reservation facts differ");
                }
                return Optional.of(response(existing));
            }

            UUID location = locations.select(
                    request.sellerId(), Map.of(request.skuId(), request.quantity())).orElse(null);
            if (location == null) return Optional.empty();
            if (stock.reserve(request.sellerId(), request.skuId(), location, request.quantity()) != 1) {
                return Optional.empty();
            }
            Reservation reservation = new Reservation(
                    request.orderId(), request.campaignId(), request.sellerId(), request.skuId(),
                    request.quantity(), ReservationStatus.RESERVED, location);
            return Optional.of(response(reservations.save(reservation)));
        });
    }

    @Override
    public List<ReservationResponse> reserveAll(ReserveInventoryBatchRequest request) {
        Objects.requireNonNull(request);
        validateOrder(request.orderId());
        if (request.items() == null || request.items().isEmpty()) {
            throw invalid("reservation lines are required");
        }

        List<ReserveInventoryBatchRequest.Item> items = request.items().stream()
                .sorted(Comparator.comparing(ReserveInventoryBatchRequest.Item::sellerId)
                        .thenComparing(ReserveInventoryBatchRequest.Item::skuId))
                .toList();
        for (int i = 0; i < items.size(); i++) {
            ReserveInventoryBatchRequest.Item item = items.get(i);
            validSku(item.sellerId(), item.skuId());
            if (item.quantity() < 1) throw invalid("quantity must be positive");
            if (i > 0
                    && items.get(i - 1).sellerId().equals(item.sellerId())
                    && items.get(i - 1).skuId().equals(item.skuId())) {
                throw invalid("duplicate seller/SKU line");
            }
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            stock.lockOrder(request.orderId());
            List<Reservation> prior = reservations.findLockedByOrderId(request.orderId());
            if (!prior.isEmpty()) {
                if (!matches(prior, request.campaignId(), items)) {
                    throw conflict("order reservation facts differ");
                }
                return prior.stream().map(InventoryServiceImpl::response).toList();
            }

            Map<UUID, List<ReserveInventoryBatchRequest.Item>> bySeller = items.stream()
                    .collect(Collectors.groupingBy(
                            ReserveInventoryBatchRequest.Item::sellerId, TreeMap::new, Collectors.toList()));
            Map<UUID, UUID> chosen = new HashMap<>();
            for (var seller : bySeller.entrySet()) {
                UUID location = locations.select(seller.getKey(), requiredQuantities(seller.getValue()))
                        .orElseThrow(() -> new InventoryException(
                                InventoryException.Code.INSUFFICIENT_STOCK,
                                "no single active location can fulfill seller order"));
                chosen.put(seller.getKey(), location);
            }

            ArrayList<Reservation> saved = new ArrayList<>();
            for (ReserveInventoryBatchRequest.Item item : items) {
                UUID location = chosen.get(item.sellerId());
                if (stock.reserve(item.sellerId(), item.skuId(), location, item.quantity()) != 1) {
                    throw new InventoryException(
                            InventoryException.Code.INSUFFICIENT_STOCK,
                            "inventory changed or location became inactive");
                }
                saved.add(new Reservation(
                        request.orderId(), request.campaignId(), item.sellerId(), item.skuId(),
                        item.quantity(), ReservationStatus.RESERVED, location));
            }
            return reservations.saveAll(saved).stream().map(InventoryServiceImpl::response).toList();
        }));
    }

    @Override
    public ReservationResponse claim(UUID orderId) {
        return first(claimAll(orderId));
    }

    @Override
    public List<ReservationResponse> claimAll(UUID orderId) {
        return transition(orderId, ReservationStatus.CLAIMED);
    }

    @Override
    public ReservationResponse release(UUID orderId) {
        return first(releaseAll(orderId));
    }

    @Override
    public List<ReservationResponse> releaseAll(UUID orderId) {
        return transition(orderId, ReservationStatus.RELEASED);
    }

    private List<ReservationResponse> transition(UUID orderId, ReservationStatus target) {
        validOrder(orderId);
        return Objects.requireNonNull(transactions.execute(status -> {
            stock.lockOrder(orderId);
            List<Reservation> rows = reservations.findLockedByOrderId(orderId);
            if (rows.isEmpty()) {
                throw new InventoryException(InventoryException.Code.NOT_FOUND, "reservation not found");
            }

            for (Reservation reservation : rows) {
                if (reservation.getStatus() == target) continue;
                if (reservation.getStatus() != ReservationStatus.RESERVED) {
                    throw conflict("reservation cannot transition from " + reservation.getStatus());
                }
                int updated = target == ReservationStatus.CLAIMED
                        ? stock.claim(reservation.getSellerId(), reservation.getSkuId(),
                                reservation.getLocationId(), reservation.getQuantity())
                        : stock.release(reservation.getSellerId(), reservation.getSkuId(),
                                reservation.getLocationId(), reservation.getQuantity());
                if (updated != 1) {
                    throw new IllegalStateException("inventory reservation counters inconsistent");
                }
                reservation.setStatus(target);
            }
            return reservations.saveAll(rows).stream().map(InventoryServiceImpl::response).toList();
        }));
    }
    @Override
    public void restoreClaimed(UUID orderId, UUID sellerId, String skuId, int quantity) {
        validOrder(orderId);
        validSku(sellerId, skuId);
        if (quantity < 1) {
            throw invalid("quantity must be positive");
        }
        transactions.executeWithoutResult(status -> {
            stock.lockOrder(orderId);
            Reservation reservation = reservations.findLockedByOrderId(orderId).stream()
                    .filter(row -> row.getSellerId().equals(sellerId) && row.getSkuId().equals(skuId))
                    .findFirst()
                    .orElseThrow(() -> new InventoryException(
                            InventoryException.Code.NOT_FOUND, "claimed reservation does not exist"));
            int remaining = reservation.getQuantity() - reservation.getRestoredQuantity();
            if (reservation.getStatus() != ReservationStatus.CLAIMED || quantity > remaining) {
                throw conflict("claimed reservation quantity mismatch");
            }
            if (stock.restoreClaimed(sellerId, skuId, reservation.getLocationId(), quantity) != 1) {
                throw new IllegalStateException("inventory claimed counter inconsistent");
            }
            reservation.restoreClaimedQuantity(quantity);
            reservations.save(reservation);
        });
    }
    @Override public ReservationResponse findReservation(UUID orderId){return first(findReservations(orderId));}
    @Override public List<ReservationResponse> findReservations(UUID orderId){validOrder(orderId);return reservations.findAllByOrderIdOrderBySellerIdAscSkuIdAsc(orderId).stream().map(InventoryServiceImpl::response).toList();}
    @Override
    public InventoryPoolResponse find(UUID sellerId, String skuId) {
        validSku(sellerId, skuId);
        InventoryPoolSummary summary = inventoryReads.find(sellerId, skuId);
        return summary == null ? null : poolResponse(summary);
    }

    @Override
    public List<InventoryPoolResponse> findAll(UUID sellerId) {
        validSeller(sellerId);
        return inventoryReads.findAll(sellerId).stream().map(InventoryServiceImpl::poolResponse).toList();
    }

    private static InventoryPoolResponse poolResponse(InventoryPoolSummary summary) {
        return new InventoryPoolResponse(summary.sellerId(), summary.skuId(), summary.initialCapacity(),
                summary.available(), summary.reserved(), summary.claimed());
    }

    private static InventoryLocationResponse locationResponse(InventoryLocationRecord record) {
        return new InventoryLocationResponse(record.locationId(), record.sellerId(), record.code(),
                record.name(), record.priority(), record.active());
    }

    private static InventoryTransferResponse transferResponse(InventoryTransferRecord record) {
        return new InventoryTransferResponse(record.transferId(), record.sellerId(), record.skuId(),
                record.sourceLocationId(), record.destinationLocationId(), record.idempotencyKey(),
                record.quantity(), record.reason(), record.createdAt());
    }
    private static boolean matches(
            List<Reservation> prior, UUID campaign, List<ReserveInventoryBatchRequest.Item> items) {
        return Objects.equals(prior.get(0).getCampaignId(), campaign)
                && prior.size() == items.size()
                && items.stream().allMatch(item -> prior.stream().anyMatch(reservation ->
                        Objects.equals(reservation.getSellerId(), item.sellerId())
                                && Objects.equals(reservation.getSkuId(), item.skuId())
                                && reservation.getQuantity() == item.quantity()));
    }

    private static Map<String, Integer> requiredQuantities(
            List<ReserveInventoryBatchRequest.Item> items) {
        Map<String, Integer> quantities = new TreeMap<>();
        for (ReserveInventoryBatchRequest.Item item : items) {
            int current = quantities.getOrDefault(item.skuId(), 0);
            if (current > Integer.MAX_VALUE - item.quantity()) {
                throw invalid("total reservation quantity exceeds supported range");
            }
            quantities.put(item.skuId(), current + item.quantity());
        }
        return quantities;
    }

    private static void validateItems(UUID seller, List<ReserveInventoryBatchRequest.Item> items) {
        if (items == null || items.isEmpty()) throw invalid("reservation lines are required");
        for (ReserveInventoryBatchRequest.Item item : items) {
            if (!seller.equals(item.sellerId())) throw invalid("seller mismatch");
            validSku(item.sellerId(), item.skuId());
            if (item.quantity() < 1) throw invalid("quantity must be positive");
        }
    }

    private static ReservationResponse response(Reservation reservation) {
        return new ReservationResponse(
                reservation.getOrderId(), reservation.getCampaignId(), reservation.getSellerId(),
                reservation.getSkuId(), reservation.getQuantity(), reservation.getStatus(),
                reservation.getLocationId());
    }

    private static ReservationResponse first(List<ReservationResponse> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            throw new InventoryException(InventoryException.Code.NOT_FOUND, "reservation not found");
        }
        return reservations.get(0);
    }

    private static void validSeller(UUID sellerId) {
        if (sellerId == null) throw invalid("sellerId required");
    }

    private static void validSku(UUID sellerId, String skuId) {
        validSeller(sellerId);
        if (skuId == null || skuId.isBlank() || skuId.length() > 128) {
            throw invalid("skuId required");
        }
    }

    private static void validOrder(UUID orderId) {
        if (orderId == null) throw invalid("orderId required");
    }

    private static void validateOrder(UUID orderId) {
        validOrder(orderId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static InventoryException invalid(String message) {
        return new InventoryException(InventoryException.Code.INVALID_REQUEST, message);
    }

    private static InventoryException conflict(String message) {
        return new InventoryException(InventoryException.Code.CONFLICT, message);
    }
}
