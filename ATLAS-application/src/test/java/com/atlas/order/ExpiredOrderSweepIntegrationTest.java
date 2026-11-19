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

    /**
     * 한 checkout 에 판매자가 여럿이면 주문이 여러 개이고, 만료 조회는 주문 단위로
     * 행을 만들므로 같은 checkout_id 가 중복될 수 있습니다. 호출자는 같은 checkout 에
     * 대한 후속 처리를 멱등하게 하므로 중복은 무해하지만, 이 계약을 테스트로 고정합니다.
     *
     * <p>또한 반환 순서가 만료가 임박한 순서인지 확인합니다. 정렬 키를
     * {@code checkout_id} 에서 {@code checkout_expires_at} 으로 바꾼 이유가
     * 부분 인덱스 사용이므로, 정렬 계약도 함께 고정되어야 합니다.
     */
    @Test
    void multiSellerCheckoutYieldsDuplicateIdsAndOrdersByExpiry() {
        jdbc.update("DELETE FROM inventory_reservation");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM buyer_checkout");
        Instant now = Instant.now();

        UUID lateCheckout = insertMultiSellerCheckout(now.minus(Duration.ofMinutes(5)), 2);
        UUID earlyCheckout = insertMultiSellerCheckout(now.minus(Duration.ofMinutes(30)), 3);

        List<UUID> candidates = orders.findExpiredCheckoutIds(OrderState.RESERVED, now, Limit.of(1_000));

        // 2 + 3 = 5 행. checkout 당 주문 수만큼 중복된다.
        assertThat(candidates).hasSize(5);
        assertThat(candidates).filteredOn(id -> id.equals(earlyCheckout)).hasSize(3);
        assertThat(candidates).filteredOn(id -> id.equals(lateCheckout)).hasSize(2);
        // 더 일찍 만료된 checkout 이 먼저 나온다.
        assertThat(candidates.getFirst()).isEqualTo(earlyCheckout);
    }

    private UUID insertMultiSellerCheckout(Instant expiresAt, int sellerCount) {
        UUID checkoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO buyer_checkout (
                    checkout_id, buyer_ref, idempotency_key, state, total_won, created_at, updated_at
                ) VALUES (?, 'sweep-buyer', ?, 'RESERVED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, checkoutId, "multi-checkout-key-" + checkoutId);
        for (int index = 0; index < sellerCount; index++) {
            jdbc.update("""
                    INSERT INTO purchase_order (
                        order_id, seller_id, buyer_ref, checkout_key, checkout_id, state,
                        checkout_expires_at, created_at, updated_at, order_source
                    ) VALUES (?, ?, 'sweep-buyer', ?, ?, 'RESERVED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')
                    """, UUID.randomUUID(), UUID.randomUUID(), "multi-order-key-" + UUID.randomUUID(),
                    checkoutId, Timestamp.from(expiresAt));
        }
        return checkoutId;
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
