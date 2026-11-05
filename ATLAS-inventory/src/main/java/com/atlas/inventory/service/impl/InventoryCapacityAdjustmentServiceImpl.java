package com.atlas.inventory.service.impl;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.projection.CapacityAdjustmentRecord;
import com.atlas.inventory.persistence.projection.InventoryPoolState;
import com.atlas.inventory.repository.JdbcCapacityAdjustmentRepository;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryCapacityAdjustmentServiceImpl implements InventoryCapacityAdjustmentService {
    private final JdbcCapacityAdjustmentRepository adjustments;
    private final TransactionTemplate transactions;

    public InventoryCapacityAdjustmentServiceImpl(JdbcCapacityAdjustmentRepository adjustments,
            PlatformTransactionManager transactionManager) {
        this.adjustments = Objects.requireNonNull(adjustments);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public InventoryAdjustmentResponse adjust(AdjustInventoryCapacityRequest request) {
        if (request == null || request.sellerId() == null || request.skuId() == null || request.skuId().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 256 || request.reason() == null
                || request.reason().isBlank() || request.delta() == 0) {
            throw new InventoryException(InventoryException.Code.INVALID_REQUEST,
                    "seller, SKU, idempotency key (maximum 256 characters), nonzero delta, and reason required");
        }
        if (request.delta() == Integer.MIN_VALUE) {
            throw new InventoryException(InventoryException.Code.INVALID_REQUEST,
                    "capacity adjustment delta outside range");
        }

        return transactions.execute(status -> {
            adjustments.lock(request.sellerId(), request.skuId(), request.idempotencyKey());
            CapacityAdjustmentRecord prior = adjustments.find(request.sellerId(), request.skuId(),
                    request.idempotencyKey()).orElse(null);
            if (prior != null) {
                if (prior.delta() != request.delta() || !prior.reason().equals(request.reason())) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "idempotency key already used different adjustment");
                }
                return response(prior);
            }

            CapacityAdjustmentRecord recorded = adjustments.record(request.sellerId(), request.skuId(),
                    request.idempotencyKey(), request.delta(), request.reason()).orElse(null);
            if (recorded != null) {
                return response(recorded);
            }

            InventoryPoolState current = adjustments.findPool(request.sellerId(), request.skuId()).orElseThrow(() ->
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
