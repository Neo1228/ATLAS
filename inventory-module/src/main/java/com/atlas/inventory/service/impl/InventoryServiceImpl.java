package com.atlas.inventory.service.impl;

import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.persistence.entity.Pool;
import com.atlas.inventory.persistence.entity.PoolId;
import com.atlas.inventory.persistence.entity.Reservation;
import com.atlas.inventory.persistence.repository.PoolRepository;
import com.atlas.inventory.persistence.repository.ReservationRepository;
import com.atlas.inventory.persistence.repository.JdbcStockRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryServiceImpl implements InventoryService {

    private final PoolRepository poolRepository;
    private final ReservationRepository reservationRepository;
    private final JdbcStockRepository stockRepository;
    private final TransactionTemplate transactions;

    public InventoryServiceImpl(PoolRepository poolRepository,
                                ReservationRepository reservationRepository,
                                JdbcStockRepository stockRepository,
                                PlatformTransactionManager transactionManager) {
        this.poolRepository = Objects.requireNonNull(poolRepository);
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.stockRepository = Objects.requireNonNull(stockRepository);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public void createPool(CreateInventoryPoolRequest request) {
        if (request == null) throw invalidRequest("Inventory pool request is required");
        requirePoolKey(request.campaignId(), request.skuId());
        if (request.capacity() < 0) throw invalidRequest("capacity must not be negative");
        transactions.executeWithoutResult(status -> poolRepository.save(new Pool(
                new PoolId(request.campaignId(), request.skuId()), request.capacity())));
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
        requirePoolKey(request.campaignId(), request.skuId());
        if (request.orderId() == null) throw invalidRequest("orderId is required");
        if (request.quantity() != 1) throw invalidRequest("inventory reservations must contain exactly one unit");
        ReservationResponse reservation = transactions.execute(status -> {
            stockRepository.lockOrder(request.orderId());
            Reservation existing = reservationRepository.findById(request.orderId()).orElse(null);
            if (existing != null) {
                if (!existing.getCampaignId().equals(request.campaignId())
                        || !existing.getSkuId().equals(request.skuId()) || existing.getQuantity() != request.quantity()) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "order already has a reservation for different inventory");
                }
                return toResponse(existing);
            }
            if (stockRepository.reserveOne(request.campaignId(), request.skuId()) != 1) return null;
            return toResponse(reservationRepository.save(new Reservation(request.orderId(), request.campaignId(),
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
            requireConsistentPool(stockRepository.claimOne(reservation.getCampaignId(), reservation.getSkuId()));
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
            requireConsistentPool(stockRepository.releaseOne(reservation.getCampaignId(), reservation.getSkuId()));
            reservation.setStatus(ReservationStatus.RELEASED);
            return toResponse(reservationRepository.save(reservation));
        }));
    }

    @Override
    public InventoryResponse find(UUID campaignId, String skuId) {
        requirePoolKey(campaignId, skuId);
        return poolRepository.findById(new PoolId(campaignId, skuId))
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
        return new ReservationResponse(reservation.getOrderId(), reservation.getCampaignId(), reservation.getSkuId(),
                reservation.getQuantity(), reservation.getStatus());
    }

    private static InventoryResponse toResponse(Pool pool) {
        return new InventoryResponse(pool.getId().getCampaignId(), pool.getId().getSkuId(), pool.getInitialCapacity(),
                pool.getAvailable(), pool.getReserved(), pool.getClaimed());
    }

    private static void requireConsistentPool(int changed) {
        if (changed != 1) throw new IllegalStateException("reservation and inventory pool are inconsistent");
    }

    private static void requirePoolKey(UUID campaignId, String skuId) {
        if (campaignId == null) throw invalidRequest("campaignId is required");
        if (skuId == null || skuId.isBlank()) throw invalidRequest("skuId must not be blank");
    }

    private static InventoryException invalidRequest(String message) {
        return new InventoryException(InventoryException.Code.INVALID_REQUEST, message);
    }
}
