package com.atlas.campaign.service.impl;

import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.SellerSkuResponse;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.persistence.entity.Campaign;
import com.atlas.campaign.persistence.repository.CampaignRepository;
import com.atlas.campaign.persistence.state.CampaignStatus;
import com.atlas.campaign.service.SellerSkuService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CampaignServiceTest {

    @Test
    void rejectsInvalidConfigurationBeforeLookingUpSellerSkuOrPersisting() {
        CampaignRepository campaignsRepository = mock(CampaignRepository.class);
        SellerSkuService sellerSkus = mock(SellerSkuService.class);
        CampaignServiceImpl campaigns = new CampaignServiceImpl(campaignsRepository, sellerSkus, Clock.systemUTC());

        assertThrows(CampaignException.class, () -> campaigns.create(
                new CreateCampaignRequest(null, "sku-1", Duration.ofMinutes(5))));

        verifyNoInteractions(campaignsRepository, sellerSkus);
    }

    @Test
    void createsCampaignForRegisteredSellerSkuAndReturnsSellerId() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        SellerSkuService sellerSkus = mock(SellerSkuService.class);
        UUID sellerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(sellerSkus.find(sellerId, "sku-1")).thenReturn(new SellerSkuResponse(sellerId, "sku-1", 3));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CampaignServiceImpl campaigns = new CampaignServiceImpl(campaignRepository, sellerSkus,
                Clock.fixed(now, ZoneOffset.UTC), () -> campaignId);
        var response = campaigns.create(new CreateCampaignRequest(sellerId, "sku-1", Duration.ofMinutes(5)));

        assertEquals(campaignId, response.campaignId());
        assertEquals(sellerId, response.sellerId());
        assertEquals("sku-1", response.skuId());
        assertEquals(CampaignStatus.CLOSED, response.status());
        assertEquals(Duration.ofMinutes(5), response.queueTicketTtl());
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    void rejectsCampaignForUnknownSellerSku() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        SellerSkuService sellerSkus = mock(SellerSkuService.class);
        UUID sellerId = UUID.randomUUID();
        when(sellerSkus.find(sellerId, "unknown")).thenReturn(null);
        CampaignServiceImpl campaigns = new CampaignServiceImpl(campaignRepository, sellerSkus, Clock.systemUTC());

        assertThrows(CampaignException.class, () -> campaigns.create(
                new CreateCampaignRequest(sellerId, "unknown", Duration.ofMinutes(5))));

        verify(campaignRepository, never()).save(any(Campaign.class));
    }
}
