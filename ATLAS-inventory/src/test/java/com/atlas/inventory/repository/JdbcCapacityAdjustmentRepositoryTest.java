package com.atlas.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class JdbcCapacityAdjustmentRepositoryTest {
    @Test
    void usesStableSellerSkuKeyAcrossDefaultAndLocationRoutes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> lockKeys = new ArrayList<>();
        doAnswer(invocation -> {
            String lockKey = invocation.getArgument(2);
            lockKeys.add(lockKey);
            return null;
        }).when(jdbc).query(
                eq("SELECT pg_advisory_xact_lock(hashtextextended(?,0))"),
                any(RowCallbackHandler.class), any(Object[].class));

        JdbcCapacityAdjustmentRepository repository = new JdbcCapacityAdjustmentRepository(jdbc);
        UUID sellerId = UUID.randomUUID();
        String skuId = "sku-1";
        String idempotencyKey = "adjustment-1";
        UUID defaultLocationId = UUID.randomUUID();
        UUID otherLocationId = UUID.randomUUID();

        repository.lock(sellerId, skuId, idempotencyKey);
        repository.lock(sellerId, defaultLocationId, skuId, idempotencyKey);
        repository.lock(sellerId, otherLocationId, skuId, idempotencyKey);

        String expectedKey = sellerId + ":" + skuId + ":" + idempotencyKey;
        assertThat(lockKeys).containsExactly(expectedKey, expectedKey, expectedKey);
    }
}
