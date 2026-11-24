package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.exception.CampaignException;
import com.atlas.admission.model.CampaignStatus;
import com.atlas.admission.service.CampaignService;
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
        CatalogResponse.Seller seller = sellers.createSeller();
        String skuId = "campaign-close-" + UUID.randomUUID();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("campaign close " + UUID.randomUUID(), "description"));
        products.registerSku(seller.sellerId(), product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(seller.sellerId(), skuId, 1));
        AdmissionResponse.Campaign campaign = campaigns.create(new AdmissionRequest.CreateCampaign(
                seller.sellerId(), skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());

        AdmissionRequest.JoinQueue existingRequest = new AdmissionRequest.JoinQueue(
                campaign.campaignId(), skuId, "buyer-close-" + UUID.randomUUID(), "join-before-close");
        AdmissionResponse.QueueTicket original = admission.join(existingRequest);

        assertThat(campaigns.close(seller.sellerId(), campaign.campaignId()).status())
                .isEqualTo(CampaignStatus.CLOSED);
        assertThat(campaigns.close(seller.sellerId(), campaign.campaignId()).status())
                .isEqualTo(CampaignStatus.CLOSED);

        AdmissionResponse.QueueTicket replay = admission.join(existingRequest);
        assertThat(replay.ticketId()).isEqualTo(original.ticketId());
        assertThat(admission.find(campaign.campaignId(), original.ticketId()).status())
                .isEqualTo(original.status());
        assertThatThrownBy(() -> admission.join(new AdmissionRequest.JoinQueue(
                campaign.campaignId(), skuId, existingRequest.buyerRef(), "join-after-close")))
                .isInstanceOf(CampaignException.class)
                .satisfies(exception -> assertThat(((CampaignException) exception).code())
                        .isEqualTo(CampaignException.Code.NOT_ELIGIBLE));
    }
}
