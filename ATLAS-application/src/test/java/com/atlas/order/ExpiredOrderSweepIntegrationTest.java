package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class ExpiredOrderSweepIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderRepository orders;

    @Test
    void expiryCandidateQueryStopsAtTheRequestedBatchSize() {
        jdbc.update("DELETE FROM inventory_reservation");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM buyer_checkout");
        Instant now = Instant.now();
        Instant expiredAt = now.minus(Duration.ofMinutes(10));
        for (int index = 0; index < 60; index++) {
            insertExpiredCartOrder(expiredAt, index);
        }

        List<UUID> allCandidates = orders.findExpiredCheckoutIds(OrderState.RESERVED, now, Limit.of(1_000));
        assertThat(allCandidates).hasSize(60);

        List<UUID> bounded = orders.findExpiredCheckoutIds(OrderState.RESERVED, now, Limit.of(25));

        assertThat(bounded).hasSize(25);
        assertThat(allCandidates).containsAll(bounded);
    }

    private void insertExpiredCartOrder(Instant expiresAt, int index) {
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerCheckoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO buyer_checkout (
                    checkout_id, buyer_ref, idempotency_key, state, total_won, created_at, updated_at
                ) VALUES (?, 'sweep-buyer', ?, 'RESERVED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, buyerCheckoutId, "sweep-checkout-key-" + index);
        jdbc.update("""
                INSERT INTO purchase_order (
                    order_id, seller_id, buyer_ref, checkout_key, checkout_id, state,
                    checkout_expires_at, created_at, updated_at, order_source
                ) VALUES (?, ?, 'sweep-buyer', ?, ?, 'RESERVED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')
                """, orderId, sellerId, "sweep-key-" + orderId, buyerCheckoutId,
                Timestamp.from(expiresAt));
    }
}
