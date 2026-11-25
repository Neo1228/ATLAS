package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 종료된 ticket 정리(ADR-0088)가 주문 조회가 읽는 ticket을 지우지 않고, 오래되지 않았거나 아직 live인 ticket을
 * 건드리지 않으며, batch 상한을 지키는지 실제 스키마(V1~V4)에서 확인한다.
 */
@Testcontainers
@SpringBootTest
class AdmissionTombstonePurgeIntegrationTest extends PayPalIntegrationTestSupport {
    private static final Duration RETENTION = Duration.ofDays(30);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.admission.tombstone.purge-initial-delay", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTickets() {
        // purge는 전역이라 앞선 테스트가 남긴 후보가 개수 단정을 흔들지 않게 한다.
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM admission_ticket");
    }

    @Test
    void purgeDeletesOnlyOldTerminalTicketsThatNoOrderPointsTo() {
        Queue queue = openQueue();
        UUID orphanExpired = insertTicket(queue, "EXPIRED", 40);
        UUID orphanFailed = insertTicket(queue, "CHECKOUT_FAILED", 40);
        UUID referencedByOrder = insertTicket(queue, "EXPIRED", 40);
        insertOrderFor(queue, referencedByOrder);
        UUID checkoutStarted = insertTicket(queue, "CHECKOUT_FAILED", 40);
        jdbc.update("UPDATE admission_ticket SET checkout_started_at = created_at WHERE ticket_id = ?", checkoutStarted);
        UUID recent = insertTicket(queue, "EXPIRED", 5);
        UUID submitted = insertTicket(queue, "CHECKOUT_SUBMITTED", 40);
        UUID waiting = insertLiveTicket(queue, 40);

        int deleted = admission.purgeTombstones(RETENTION, 100);

        // referencedByOrder는 checkout_started_at이 비어 있어도(이 열이 생기기 전 주문) 주문이 가리키면 남는다.
        assertThat(deleted).isEqualTo(2);
        assertThat(existing(orphanExpired, orphanFailed, referencedByOrder, checkoutStarted, recent, submitted, waiting))
                .containsExactlyInAnyOrder(referencedByOrder, checkoutStarted, recent, submitted, waiting);
    }

    @Test
    void purgeNeverDeletesMoreThanTheBatchLimit() {
        Queue queue = openQueue();
        List<UUID> candidates = IntStream.range(0, 5).mapToObj(i -> insertTicket(queue, "EXPIRED", 60)).toList();

        assertThat(admission.purgeTombstones(RETENTION, 2)).isEqualTo(2);
        assertThat(existing(candidates.toArray(UUID[]::new))).hasSize(3);
    }

    private UUID insertTicket(Queue queue, String status, int ageDays) {
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admission_ticket (ticket_id, campaign_id, seller_id, sku_id, buyer_ref, idempotency_key,
                    sequence, status, expires_at, created_at, checkout_slot_acquired_at, checkout_slot_held)
                VALUES (?, ?, ?, ?, ?, ?, (SELECT coalesce(max(sequence), 0) + 1 FROM admission_ticket),
                    ?, NULL, now() - make_interval(days => ?), NULL, false)
                """, ticketId, queue.campaignId(), queue.sellerId(), queue.skuId(), "t-" + ticketId, "key-" + ticketId,
                status, ageDays);
        return ticketId;
    }

    private UUID insertLiveTicket(Queue queue, int ageDays) {
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admission_ticket (ticket_id, campaign_id, seller_id, sku_id, buyer_ref, idempotency_key,
                    sequence, status, expires_at, created_at, checkout_slot_acquired_at, checkout_slot_held)
                VALUES (?, ?, ?, ?, ?, ?, (SELECT coalesce(max(sequence), 0) + 1 FROM admission_ticket),
                    'WAITING', now() + interval '1 hour', now() - make_interval(days => ?), NULL, false)
                """, ticketId, queue.campaignId(), queue.sellerId(), queue.skuId(), "live-" + ticketId, "key-" + ticketId,
                ageDays);
        return ticketId;
    }

    private void insertOrderFor(Queue queue, UUID ticketId) {
        jdbc.update("""
                INSERT INTO purchase_order (order_id, seller_id, campaign_id, ticket_id, buyer_ref, state,
                    checkout_expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'order-buyer', 'EXPIRED', now(), now(), now())
                """, UUID.randomUUID(), queue.sellerId(), queue.campaignId(), ticketId);
    }

    private Queue openQueue() {
        CatalogResponse.Seller seller = sellers.createSeller();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("tombstone purge " + UUID.randomUUID(), "description"));
        String skuId = "purge-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, 5));
        AdmissionResponse.Campaign campaign = campaigns.create(
                new AdmissionRequest.CreateCampaign(seller.sellerId(), skuId, null));
        return new Queue(seller.sellerId(), skuId, campaign.campaignId());
    }

    private record Queue(UUID sellerId, String skuId, UUID campaignId) {}

    private List<UUID> existing(UUID... ticketIds) {
        String placeholders = String.join(", ", Collections.nCopies(ticketIds.length, "?"));
        return jdbc.queryForList("SELECT ticket_id FROM admission_ticket WHERE ticket_id IN (" + placeholders + ")",
                UUID.class, (Object[]) ticketIds);
    }
}
