package com.atlas.inventory.service.impl;

import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.entity.InventoryPool;
import com.atlas.inventory.persistence.entity.InventoryPoolId;
import com.atlas.inventory.persistence.entity.Reservation;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.repository.InventoryPoolRepository;
import com.atlas.inventory.repository.JdbcStockRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.service.InventoryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryServiceImpl implements InventoryService {
    private final InventoryPoolRepository inventoryPoolRepository;
    private final ReservationRepository reservationRepository;
    private final JdbcStockRepository stockRepository;
    private final TransactionTemplate transactions;

    public InventoryServiceImpl(InventoryPoolRepository inventoryPoolRepository,
                                ReservationRepository reservationRepository,
                                JdbcStockRepository stockRepository,
                                PlatformTransactionManager transactionManager) {
        this.inventoryPoolRepository = Objects.requireNonNull(inventoryPoolRepository);
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.stockRepository = Objects.requireNonNull(stockRepository);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public void createInventoryPool(CreateInventoryPoolRequest request) {
        if (request == null) throw invalidRequest("Inventory pool request is required");
        requireInventoryPoolKey(request.sellerId(), request.skuId());
        if (request.initialStock() < 0) throw invalidRequest("initialStock must not be negative");
        transactions.executeWithoutResult(status -> inventoryPoolRepository.save(new InventoryPool(
                new InventoryPoolId(request.sellerId(), request.skuId()), request.initialStock())));
    }

    @Override
    public ReservationResponse reserve(ReserveInventoryRequest request) {
        Optional<ReservationResponse> reservation = tryReserve(request);
        return reservation.orElseThrow(() -> new InventoryException(InventoryException.Code.UNAVAILABLE,
                "inventory pool is missing or has no available units"));
    }

    @Override
    public Optional<ReservationResponse> tryReserve(ReserveInventoryRequest request) {
        if (request == null) throw invalidRequest("Inventory reservation request is required");
        requireInventoryPoolKey(request.sellerId(), request.skuId());
        requireOrderId(request.orderId());
        if (request.quantity() < 1) throw invalidRequest("quantity must be positive");
        ReservationResponse reservation = transactions.execute(status -> {
            stockRepository.lockOrder(request.orderId());
            List<Reservation> existing = reservationRepository.findLockedByOrderId(request.orderId());
            if (!existing.isEmpty()) {
                Reservation row = existing.get(0);
                if (existing.size() != 1 || !Objects.equals(row.getCampaignId(), request.campaignId())
                        || !Objects.equals(row.getSellerId(), request.sellerId())
                        || !Objects.equals(row.getSkuId(), request.skuId())
                        || row.getQuantity() != request.quantity()) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "order already has a reservation for different inventory");
                }
                return toResponse(row);
            }
            if (stockRepository.reserve(request.sellerId(), request.skuId(), request.quantity()) != 1) return null;
            return toResponse(reservationRepository.save(new Reservation(request.orderId(), request.campaignId(),
                    request.sellerId(), request.skuId(), request.quantity(), ReservationStatus.RESERVED)));
        });
        return Optional.ofNullable(reservation);
    }

    @Override
    public List<ReservationResponse> reserveAll(ReserveInventoryBatchRequest request) {
        validate(request);
        List<ReserveInventoryBatchRequest.Item> items = request.items().stream()
                .sorted(Comparator.comparing(ReserveInventoryBatchRequest.Item::sellerId)
                        .thenComparing(ReserveInventoryBatchRequest.Item::skuId)).toList();
        for (int i = 1; i < items.size(); i++) {
            var prior = items.get(i - 1);
            var current = items.get(i);
            if (prior.sellerId().equals(current.sellerId()) && prior.skuId().equals(current.skuId())) {
                throw invalidRequest("duplicate sellerId/skuId lines must be combined");
            }
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            stockRepository.lockOrder(request.orderId());
            List<Reservation> existing = reservationRepository.findLockedByOrderId(request.orderId());
            if (!existing.isEmpty()) {
                if (existing.size() != items.size() || existing.stream().anyMatch(r -> !matches(r, request, items))) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "order already has a reservation for different inventory");
                }
                return existing.stream().map(InventoryServiceImpl::toResponse).toList();
            }
            List<Reservation> saved = new ArrayList<>();
            for (var item : items) {
                if (stockRepository.reserve(item.sellerId(), item.skuId(), item.quantity()) != 1) {
                    throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,
                            "insufficient stock for seller " + item.sellerId() + " sku " + item.skuId());
                }
                saved.add(reservationRepository.save(new Reservation(request.orderId(), request.campaignId(),
                        item.sellerId(), item.skuId(), item.quantity(), ReservationStatus.RESERVED)));
            }
            return saved.stream().map(InventoryServiceImpl::toResponse).toList();
        }));
    }

    private static boolean matches(Reservation r, ReserveInventoryBatchRequest req,
                                   List<ReserveInventoryBatchRequest.Item> items) {
        return Objects.equals(r.getCampaignId(), req.campaignId()) && items.stream().anyMatch(i ->
                i.sellerId().equals(r.getSellerId()) && i.skuId().equals(r.getSkuId()) && i.quantity() == r.getQuantity());
    }

    @Override public ReservationResponse claim(UUID orderId) { return first(claimAll(orderId)); }

    @Override
    public List<ReservationResponse> claimAll(UUID orderId) {
        requireOrderId(orderId);
        return Objects.requireNonNull(transactions.execute(status -> {
            List<Reservation> rows = requireLockedReservations(orderId);
            for (Reservation r : rows) {
                if (r.getStatus() == ReservationStatus.CLAIMED) continue;
                if (r.getStatus() != ReservationStatus.RESERVED) throw new InventoryException(InventoryException.Code.CONFLICT,
                        "released inventory cannot be claimed");
                requireConsistent(stockRepository.claim(r.getSellerId(), r.getSkuId(), r.getQuantity()));
                r.setStatus(ReservationStatus.CLAIMED);
            }
            return reservationRepository.saveAll(rows).stream().map(InventoryServiceImpl::toResponse).toList();
        }));
    }

    @Override public ReservationResponse release(UUID orderId) { return first(releaseAll(orderId)); }

    @Override
    public List<ReservationResponse> releaseAll(UUID orderId) {
        requireOrderId(orderId);
        return Objects.requireNonNull(transactions.execute(status -> {
            List<Reservation> rows = requireLockedReservations(orderId);
            for (Reservation r : rows) {
                if (r.getStatus() == ReservationStatus.RELEASED) continue;
                if (r.getStatus() != ReservationStatus.RESERVED) throw new InventoryException(InventoryException.Code.CONFLICT,
                        "claimed inventory cannot be released");
                requireConsistent(stockRepository.release(r.getSellerId(), r.getSkuId(), r.getQuantity()));
                r.setStatus(ReservationStatus.RELEASED);
            }
            return reservationRepository.saveAll(rows).stream().map(InventoryServiceImpl::toResponse).toList();
        }));
    }

    @Override public InventoryPoolResponse find(UUID sellerId, String skuId) {
        requireInventoryPoolKey(sellerId, skuId);
        return inventoryPoolRepository.findById(new InventoryPoolId(sellerId, skuId)).map(InventoryServiceImpl::toResponse).orElse(null);
    }

    @Override
    public List<InventoryPoolResponse> findAll(UUID sellerId, List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) throw invalidRequest("at least one skuId is required");
        skuIds.forEach(skuId -> requireInventoryPoolKey(sellerId, skuId));
        List<String> uniqueSkuIds = skuIds.stream().distinct().sorted().toList();
        return inventoryPoolRepository.findAllBySellerIdAndSkuIds(sellerId, uniqueSkuIds).stream()
                .map(InventoryServiceImpl::toResponse)
                .toList();
    }

    @Override public ReservationResponse findReservation(UUID orderId) {
        return firstOrNull(findReservations(orderId));
    }

    @Override public List<ReservationResponse> findReservations(UUID orderId) {
        requireOrderId(orderId);
        return reservationRepository.findAllByOrderIdOrderBySellerIdAscSkuIdAsc(orderId).stream()
                .map(InventoryServiceImpl::toResponse).toList();
    }

    private List<Reservation> requireLockedReservations(UUID orderId) {
        List<Reservation> rows = reservationRepository.findLockedByOrderId(orderId);
        if (rows.isEmpty()) throw new InventoryException(InventoryException.Code.NOT_FOUND, "reservation does not exist");
        return rows;
    }

    private static void validate(ReserveInventoryBatchRequest r) {
        if (r == null) throw invalidRequest("Inventory reservation request is required");
        requireOrderId(r.orderId());
        if (r.items() == null || r.items().isEmpty()) throw invalidRequest("at least one inventory item is required");
        for (var i : r.items()) {
            if (i == null) throw invalidRequest("inventory item is required");
            requireInventoryPoolKey(i.sellerId(), i.skuId());
            if (i.quantity() < 1) throw invalidRequest("quantity must be positive");
        }
    }
    private static ReservationResponse first(List<ReservationResponse> rows) {
        if (rows.isEmpty()) throw new InventoryException(InventoryException.Code.NOT_FOUND, "reservation does not exist");
        return rows.get(0);
    }
    private static ReservationResponse firstOrNull(List<ReservationResponse> rows) { return rows.isEmpty() ? null : rows.get(0); }
    private static void requireOrderId(UUID id) { if (id == null) throw invalidRequest("orderId is required"); }
    private static ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(r.getOrderId(), r.getCampaignId(), r.getSellerId(), r.getSkuId(), r.getQuantity(), r.getStatus());
    }
    private static InventoryPoolResponse toResponse(InventoryPool p) {
        return new InventoryPoolResponse(p.getId().getSellerId(), p.getId().getSkuId(), p.getInitialCapacity(), p.getAvailable(), p.getReserved(), p.getClaimed());
    }
    private static void requireConsistent(int changed) { if (changed != 1) throw new IllegalStateException("reservation and inventory pool are inconsistent"); }
    private static void requireInventoryPoolKey(UUID sellerId, String skuId) {
        if (sellerId == null) throw invalidRequest("sellerId is required");
        if (skuId == null || skuId.isBlank()) throw invalidRequest("skuId must not be blank");
    }
    private static InventoryException invalidRequest(String message) { return new InventoryException(InventoryException.Code.INVALID_REQUEST, message); }
}
