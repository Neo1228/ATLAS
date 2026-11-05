package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.projection.CapacityAdjustmentRecord;
import com.atlas.inventory.persistence.projection.InventoryPoolState;
import com.atlas.inventory.repository.JdbcCapacityAdjustmentRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryCapacityAdjustmentService {
    private final JdbcCapacityAdjustmentRepository adjustments;
    private final TransactionTemplate transactions;

    public InventoryCapacityAdjustmentService(JdbcCapacityAdjustmentRepository adjustments,
                                               PlatformTransactionManager transactionManager) {
        this.adjustments = Objects.requireNonNull(adjustments);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public InventoryAdjustmentResponse adjust(UUID sellerId, String skuId, String idempotencyKey,
                                             AdjustInventoryCapacityRequest request) {
        if (sellerId == null || skuId == null || skuId.isBlank() || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 256
                || request == null || request.reason() == null || request.reason().isBlank() || request.delta() == 0) {
            throw new InventoryException(InventoryException.Code.INVALID_REQUEST,
                    "seller, SKU, idempotency key (maximum 256 characters), nonzero delta, and reason are required");
        }
        if (request.delta() == Integer.MIN_VALUE) {
            throw new InventoryException(InventoryException.Code.INVALID_REQUEST,
                    "capacity adjustment delta is outside the supported range");
        }

        return transactions.execute(status -> {
            adjustments.lock(sellerId, skuId, idempotencyKey);
            CapacityAdjustmentRecord prior = adjustments.find(sellerId, skuId, idempotencyKey).orElse(null);
            if (prior != null) {
                if (prior.delta() != request.delta() || !prior.reason().equals(request.reason())) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "idempotency key was already used for a different adjustment");
                }
                return response(prior);
            }

            CapacityAdjustmentRecord recorded = adjustments.record(sellerId, skuId, idempotencyKey,
                    request.delta(), request.reason()).orElse(null);
            if (recorded != null) return response(recorded);

            InventoryPoolState current = adjustments.findPool(sellerId, skuId).orElseThrow(() ->
                    new InventoryException(InventoryException.Code.POOL_NOT_FOUND, "inventory pool not found"));
            validateCapacity(current, request.delta());
            throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,
                    "adjustment exceeds available inventory");
        });
    }

    private static void validateCapacity(InventoryPoolState pool, int delta) {
        if (delta < 0 && pool.available() < -delta) {
            throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,
                    "adjustment exceeds available inventory");
        }
        if (delta > 0 && (pool.initialCapacity() > Integer.MAX_VALUE - delta
                || pool.available() > Integer.MAX_VALUE - delta)) {
            throw new InventoryException(InventoryException.Code.INVALID_REQUEST,
                    "adjustment exceeds maximum inventory capacity");
        }
    }

    private static InventoryAdjustmentResponse response(CapacityAdjustmentRecord record) {
        InventoryPoolState pool = record.pool();
        return new InventoryAdjustmentResponse(record.sellerId(), record.skuId(), record.idempotencyKey(),
                record.delta(), record.reason(), new InventoryPoolResponse(record.sellerId(), record.skuId(),
                pool.initialCapacity(), pool.available(), pool.reserved(), pool.claimed()), record.createdAt());
    }
}
