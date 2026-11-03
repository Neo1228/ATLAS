package com.atlas.inventory.service.impl;

import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.persistence.entity.InventoryPool;
import com.atlas.inventory.persistence.entity.InventoryPoolId;
import com.atlas.inventory.persistence.entity.Reservation;
import com.atlas.inventory.repository.InventoryPoolRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.repository.JdbcStockRepository;
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
        return tryReserve(request).orElseThrow(() ->
                new InventoryException(InventoryException.Code.UNAVAILABLE,
                        "inventory pool is missing or has no available units"));
    }

    @Override
    public Optional<ReservationResponse> tryReserve(ReserveInventoryRequest request) {
        if (request == null) throw invalidRequest("Inventory reservation request is required");
        requireInventoryPoolKey(request.sellerId(), request.skuId());
        if (request.orderId() == null) throw invalidRequest("orderId is required");
        if (request.quantity() != 1) throw invalidRequest("inventory reservations must contain exactly one unit");
        ReservationResponse reservation = transactions.execute(status -> {
            stockRepository.lockOrder(request.orderId());
            Reservation existing = reservationRepository.findById(request.orderId()).orElse(null);
            if (existing != null) {
                if (!Objects.equals(existing.getCampaignId(), request.campaignId())
                        || !Objects.equals(existing.getSellerId(), request.sellerId())
                        || !Objects.equals(existing.getSkuId(), request.skuId()) || existing.getQuantity() != request.quantity()) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "order already has a reservation for different inventory");
                }
                return toResponse(existing);
            }
            if (stockRepository.reserveOne(request.sellerId(), request.skuId()) != 1) return null;
            return toResponse(reservationRepository.save(new Reservation(request.orderId(), request.campaignId(), request.sellerId(),
                    request.skuId(), 1, ReservationStatus.RESERVED)));
        });
        return Optional.ofNullable(reservation);
    }

    @Override
    public ReservationResponse claim(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId is required");
        return Objects.requireNonNull(transactions.execute(status -> {
            Reservation reservation = requireLockedReservation(orderId);
            if (reservation.getStatus() == ReservationStatus.CLAIMED) return toResponse(reservation);
            if (reservation.getStatus() != ReservationStatus.RESERVED) {
                throw new InventoryException(InventoryException.Code.CONFLICT, "released inventory cannot be claimed");
            }
            requireConsistentInventoryPool(stockRepository.claimOne(reservation.getSellerId(), reservation.getSkuId()));
            reservation.setStatus(ReservationStatus.CLAIMED);
            return toResponse(reservationRepository.save(reservation));
        }));
    }

    @Override
    public ReservationResponse release(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId is required");
        return Objects.requireNonNull(transactions.execute(status -> {
            Reservation reservation = requireLockedReservation(orderId);
            if (reservation.getStatus() == ReservationStatus.RELEASED) return toResponse(reservation);
            if (reservation.getStatus() != ReservationStatus.RESERVED) {
                throw new InventoryException(InventoryException.Code.CONFLICT, "claimed inventory cannot be released");
            }
            requireConsistentInventoryPool(stockRepository.releaseOne(reservation.getSellerId(), reservation.getSkuId()));
            reservation.setStatus(ReservationStatus.RELEASED);
            return toResponse(reservationRepository.save(reservation));
        }));
    }

    @Override
    public InventoryPoolResponse find(UUID sellerId, String skuId) {
        requireInventoryPoolKey(sellerId, skuId);
        return inventoryPoolRepository.findById(new InventoryPoolId(sellerId, skuId))
                .map(InventoryServiceImpl::toResponse).orElse(null);
    }

    @Override
    public ReservationResponse findReservation(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId is required");
        return reservationRepository.findById(orderId).map(InventoryServiceImpl::toResponse).orElse(null);
    }

    private Reservation requireLockedReservation(UUID orderId) {
        Reservation reservation = reservationRepository.findLockedByOrderId(orderId).orElse(null);
        if (reservation == null) {
            throw new InventoryException(InventoryException.Code.NOT_FOUND, "reservation does not exist");
        }
        return reservation;
    }

    private static ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(reservation.getOrderId(), reservation.getCampaignId(), reservation.getSellerId(),
                reservation.getSkuId(),
                reservation.getQuantity(), reservation.getStatus());
    }

    private static InventoryPoolResponse toResponse(InventoryPool pool) {
        return new InventoryPoolResponse(pool.getId().getSellerId(), pool.getId().getSkuId(), pool.getInitialCapacity(),
                pool.getAvailable(), pool.getReserved(), pool.getClaimed());
    }

    private static void requireConsistentInventoryPool(int changed) {
        if (changed != 1) throw new IllegalStateException("reservation and inventory pool are inconsistent");
    }

    private static void requireInventoryPoolKey(UUID sellerId, String skuId) {
        if (sellerId == null) throw invalidRequest("sellerId is required");
        if (skuId == null || skuId.isBlank()) throw invalidRequest("skuId must not be blank");
    }

    private static InventoryException invalidRequest(String message) {
        return new InventoryException(InventoryException.Code.INVALID_REQUEST, message);
    }
}
