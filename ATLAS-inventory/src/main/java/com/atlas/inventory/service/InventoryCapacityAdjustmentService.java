package com.atlas.inventory.service;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.exception.InventoryException;
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

    public InventoryAdjustmentResponse adjust(UUID sellerId, String skuId, String key,
            AdjustInventoryCapacityRequest request) {
        if (sellerId == null || skuId == null || skuId.isBlank() || key == null || key.isBlank()
                || request == null || request.reason() == null || request.reason().isBlank()
                || request.delta() == 0) {
            throw new InventoryException(InventoryException.Code.INVALID_REQUEST,
                    "sellerId, skuId, Idempotency-Key, nonzero delta, and reason are required");
        }
        return transactions.execute(status -> {
            adjustments.lock(sellerId, skuId, key);
            InventoryAdjustmentResponse prior = adjustments.find(sellerId, skuId, key);
            if (prior != null) {
                if (prior.delta() != request.delta() || !prior.reason().equals(request.reason())) {
                    throw new InventoryException(InventoryException.Code.CONFLICT,
                            "Idempotency-Key was used with different adjustment facts");
                }
                return prior;
            }
            return adjustments.record(sellerId, skuId, key, request.delta(), request.reason());
        });
    }
}
