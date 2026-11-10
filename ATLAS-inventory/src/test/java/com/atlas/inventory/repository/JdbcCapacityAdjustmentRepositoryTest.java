package com.atlas.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class JdbcCapacityAdjustmentRepositoryTest {
    @Test
    void stabilizesDefaultLocationLockAndKeepsOtherLocationsSeparate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> lockKeys = new ArrayList<>();
        doAnswer(invocation -> {
            String lockKey = invocation.getArgument(2);
            lockKeys.add(lockKey);
            return null;
        }).when(jdbc).query(
                eq("SELECT pg_advisory_xact_lock(hashtextextended(?,0))"),
                any(RowCallbackHandler.class), any(Object[].class));

        String locationCodeQuery =
                "SELECT location_code FROM inventory_location WHERE seller_id = ? AND location_id = ?";
        UUID sellerId = UUID.randomUUID();
        UUID defaultLocationId = UUID.randomUUID();
        UUID firstLocationId = UUID.randomUUID();
        UUID secondLocationId = UUID.randomUUID();
        UUID missingLocationId = UUID.randomUUID();
        when(jdbc.queryForObject(eq(locationCodeQuery), eq(String.class), eq(sellerId), eq(defaultLocationId)))
                .thenReturn("DEFAULT");
        when(jdbc.queryForObject(eq(locationCodeQuery), eq(String.class), eq(sellerId), eq(firstLocationId)))
                .thenReturn("WAREHOUSE-A");
        when(jdbc.queryForObject(eq(locationCodeQuery), eq(String.class), eq(sellerId), eq(secondLocationId)))
                .thenReturn("WAREHOUSE-B");
        when(jdbc.queryForObject(eq(locationCodeQuery), eq(String.class), eq(sellerId), eq(missingLocationId)))
                .thenThrow(new EmptyResultDataAccessException(1));

        JdbcCapacityAdjustmentRepository repository = new JdbcCapacityAdjustmentRepository(jdbc);
        String skuId = "sku-1";
        String idempotencyKey = "adjustment-1";

        repository.lock(sellerId, skuId, idempotencyKey);
        assertThat(repository.lock(sellerId, defaultLocationId, skuId, idempotencyKey)).isTrue();
        assertThat(repository.lock(sellerId, firstLocationId, skuId, idempotencyKey)).isTrue();
        assertThat(repository.lock(sellerId, secondLocationId, skuId, idempotencyKey)).isTrue();
        assertThat(repository.lock(sellerId, missingLocationId, skuId, idempotencyKey)).isFalse();

        String defaultLockKey = sellerId + ":DEFAULT:" + skuId + ":" + idempotencyKey;
        String firstLocationLockKey = sellerId + ":" + firstLocationId + ":" + skuId + ":" + idempotencyKey;
        String secondLocationLockKey = sellerId + ":" + secondLocationId + ":" + skuId + ":" + idempotencyKey;
        assertThat(lockKeys).containsExactly(
                defaultLockKey, defaultLockKey, firstLocationLockKey, secondLocationLockKey);
    }
}
