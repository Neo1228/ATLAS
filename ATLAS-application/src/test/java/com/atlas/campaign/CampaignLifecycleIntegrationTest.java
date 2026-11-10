package com.atlas.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.persistence.state.CampaignStatus;
import com.atlas.campaign.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class CampaignLifecycleIntegrationTest extends PayPalIntegrationTestSupport {
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

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;

    @Test
    void closingCampaignRejectsNewJoinsButKeepsExistingTicketReplayable() {
        SellerResponse seller = sellers.createSeller();
        String skuId = "campaign-close-" + UUID.randomUUID();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("campaign close " + UUID.randomUUID(), "description"));
        products.registerSku(seller.sellerId(), product.productId(), new RegisterSellerSkuRequest(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                seller.sellerId(), skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());

        JoinQueueRequest existingRequest = new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-close-" + UUID.randomUUID(), "join-before-close");
        QueueTicketResponse original = admission.join(existingRequest);

        assertThat(campaigns.close(seller.sellerId(), campaign.campaignId()).status())
                .isEqualTo(CampaignStatus.CLOSED);
        assertThat(campaigns.close(seller.sellerId(), campaign.campaignId()).status())
                .isEqualTo(CampaignStatus.CLOSED);

        QueueTicketResponse replay = admission.join(existingRequest);
        assertThat(replay.ticketId()).isEqualTo(original.ticketId());
        assertThat(admission.find(campaign.campaignId(), original.ticketId()).status())
                .isEqualTo(original.status());
        assertThatThrownBy(() -> admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, existingRequest.buyerRef(), "join-after-close")))
                .isInstanceOf(CampaignException.class)
                .satisfies(exception -> assertThat(((CampaignException) exception).code())
                        .isEqualTo(CampaignException.Code.NOT_ELIGIBLE));
    }
}
