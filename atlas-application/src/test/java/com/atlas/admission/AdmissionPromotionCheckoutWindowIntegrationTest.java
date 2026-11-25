package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.exception.CampaignException;
import com.atlas.admission.model.CheckoutSlotReleaseReason;
import com.atlas.admission.model.TicketStatus;
import com.atlas.admission.repository.QueueRepository;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 대기 ticket이 승격될 때 checkout 창이 join 시점이 아니라 slot 획득 시점부터 Campaign ticket TTL만큼 열리는지 검증합니다.
 * join 시각 기준 만료를 유지하면 TTL 대부분을 대기한 구매자는 승격 직후 slot을 잃습니다.
 */
@Testcontainers
@SpringBootTest
class AdmissionPromotionCheckoutWindowIntegrationTest extends PayPalIntegrationTestSupport {
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);
    private static final Duration REMAINING_JOIN_TTL = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private QueueRepository queueRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void promotedTicketAfterSlotReleaseGetsFullCheckoutWindowFromAcquisition() {
        Queue queue = openQueueWithSingleSlot("release");
        AdmissionResponse.QueueTicket holder = join(queue, "holder");
        AdmissionResponse.QueueTicket waiter = join(queue, "waiter");
        assertThat(holder.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(waiter.status()).isEqualTo(TicketStatus.WAITING);
        // join 즉시 slot을 얻은 ticket의 창은 획득 시점부터 TTL입니다. 승격 ticket도 같은 기준이어야 합니다.
        AdmissionResponse.QueueTicket storedHolder = admission.find(queue.campaignId(), holder.ticketId());
        assertThat(Duration.between(acquiredAt(storedHolder), storedHolder.expiresAt())).isEqualTo(TICKET_TTL);
        Instant joinExpiry = simulateWaitedMostOfTicketTtl(waiter);

        admission.releaseCheckoutSlot(queue.campaignId(), holder.ticketId(), CheckoutSlotReleaseReason.BUYER_CANCELLED);

        assertThat(admission.find(queue.campaignId(), holder.ticketId()).status()).isEqualTo(TicketStatus.CHECKOUT_FAILED);
        assertPromotedWithFullCheckoutWindow(queue, waiter, joinExpiry);
    }

    @Test
    void promotedTicketAfterHolderExpirySweepGetsFullCheckoutWindowFromAcquisition() {
        Queue queue = openQueueWithSingleSlot("sweep");
        AdmissionResponse.QueueTicket holder = join(queue, "holder");
        AdmissionResponse.QueueTicket waiter = join(queue, "waiter");
        assertThat(waiter.status()).isEqualTo(TicketStatus.WAITING);
        Instant joinExpiry = simulateWaitedMostOfTicketTtl(waiter);
        jdbc.update("UPDATE admission_ticket SET expires_at = ? WHERE ticket_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofSeconds(1))), holder.ticketId());

        admission.expireDueTickets();

        assertThat(admission.find(queue.campaignId(), holder.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
        assertPromotedWithFullCheckoutWindow(queue, waiter, joinExpiry);
    }

    /** 설정이 없을 때의 기본값(K 50, TTL 2분)과 상한(K 10,000, TTL 2시간)이 실제 DB 행에 적용되는지 확인한다(ADR-0083). */
    @Test
    void omittedKAndTicketTtlUseConfiguredDefaultsAndLimitsAreEnforced() {
        CatalogResponse.Seller seller = sellers.createSeller();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("admission defaults " + UUID.randomUUID(), "description"));
        String skuId = "admission-defaults-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, 5));

        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(seller.sellerId(), skuId, null));
        AdmissionResponse.Campaign campaign = campaigns.create(
                new AdmissionRequest.CreateCampaign(seller.sellerId(), skuId, null));

        Integer storedK = jdbc.queryForObject(
                "SELECT max_active_checkouts FROM seller_sku_admission_policy WHERE seller_id = ? AND sku_id = ?",
                Integer.class, seller.sellerId(), skuId);
        assertThat(storedK).isEqualTo(50);
        assertThat(campaigns.find(campaign.campaignId()).queueTicketTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThatThrownBy(() -> admission.configureSellerSkuPolicy(
                new AdmissionRequest.ConfigureSellerSkuPolicy(seller.sellerId(), skuId, 10_001)))
                .isInstanceOf(AdmissionException.class);
        assertThatThrownBy(() -> campaigns.create(new AdmissionRequest.CreateCampaign(
                seller.sellerId(), skuId, Duration.ofHours(2).plusSeconds(1))))
                .isInstanceOf(CampaignException.class);
    }

    private void assertPromotedWithFullCheckoutWindow(Queue queue, AdmissionResponse.QueueTicket waiter, Instant joinExpiry) {
        AdmissionResponse.QueueTicket promoted = admission.find(queue.campaignId(), waiter.ticketId());
        assertThat(promoted.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(promoted.checkoutSlotHeld()).isTrue();
        Instant acquiredAt = acquiredAt(promoted);
        assertThat(promoted.expiresAt()).isAfter(joinExpiry);
        assertThat(Duration.between(acquiredAt, promoted.expiresAt())).isEqualTo(TICKET_TTL);

        // join 시각 기준 만료는 지났지만 획득 후 TTL 이내인 시점의 만료 처리는 승격 ticket을 유지해야 합니다.
        Instant afterJoinExpiry = joinExpiry.plus(Duration.ofMinutes(1));
        assertThat(expireAtWithoutCommit(queue, afterJoinExpiry)).isZero();
        assertThat(expireAtWithoutCommit(queue, acquiredAt.plus(TICKET_TTL).minusSeconds(1))).isZero();
        assertThat(expireAtWithoutCommit(queue, acquiredAt.plus(TICKET_TTL))).isEqualTo(1);

        AdmissionResponse.QueueTicket checkout = admission.requireCheckoutSlot(queue.campaignId(), waiter.ticketId());
        assertThat(checkout.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(checkout.checkoutSlotHeld()).isTrue();
    }

    /** TTL 대부분을 대기한 상태를 만들기 위해 join 시각과 join 기준 만료 시각을 과거로 옮깁니다. */
    private Instant simulateWaitedMostOfTicketTtl(AdmissionResponse.QueueTicket waiter) {
        Instant now = Instant.now();
        Instant joinExpiry = now.plus(REMAINING_JOIN_TTL);
        assertThat(jdbc.update(
                "UPDATE admission_ticket SET created_at = ?, expires_at = ? WHERE ticket_id = ? AND status = 'WAITING'",
                Timestamp.from(joinExpiry.minus(TICKET_TTL)), Timestamp.from(joinExpiry), waiter.ticketId())).isEqualTo(1);
        return joinExpiry;
    }

    /** 지정 시각의 만료 처리 결과만 확인하고 rollback하여 이후 단계에 영향을 주지 않습니다. */
    private int expireAtWithoutCommit(Queue queue, Instant at) {
        Integer changed = new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return queueRepository.expireDueTicketsForSellerSku(queue.sellerId(), queue.skuId(), at);
        });
        return Objects.requireNonNull(changed);
    }

    private Instant acquiredAt(AdmissionResponse.QueueTicket ticket) {
        Timestamp acquiredAt = jdbc.queryForObject(
                "SELECT checkout_slot_acquired_at FROM admission_ticket WHERE ticket_id = ?",
                Timestamp.class, ticket.ticketId());
        return Objects.requireNonNull(acquiredAt).toInstant();
    }

    private Queue openQueueWithSingleSlot(String label) {
        CatalogResponse.Seller seller = sellers.createSeller();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("admission promotion " + label + " " + UUID.randomUUID(), "description"));
        String skuId = "admission-promotion-" + label + "-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(seller.sellerId(), skuId, 1));
        AdmissionResponse.Campaign campaign = campaigns.create(new AdmissionRequest.CreateCampaign(seller.sellerId(), skuId, TICKET_TTL));
        campaigns.open(campaign.campaignId());
        return new Queue(seller.sellerId(), skuId, campaign.campaignId());
    }

    private AdmissionResponse.QueueTicket join(Queue queue, String buyer) {
        return admission.join(new AdmissionRequest.JoinQueue(queue.campaignId(), queue.skuId(),
                buyer + "-" + UUID.randomUUID(), "promotion-" + UUID.randomUUID()));
    }

    private record Queue(UUID sellerId, String skuId, UUID campaignId) {}
}
