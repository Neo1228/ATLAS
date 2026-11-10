package com.atlas.campaign.service.impl;

import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.persistence.entity.Campaign;
import com.atlas.campaign.repository.CampaignRepository;
import com.atlas.campaign.persistence.state.CampaignStatus;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.exception.CatalogException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Optional;
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
        when(sellerSkus.findOrderableSellerSku(sellerId, "sku-1", ProductSalesChannel.CAMPAIGN)).thenReturn(
                new SellerSkuResponse(sellerId, UUID.randomUUID(), "sku-1", "Product",
                        1, null, null, null, 1));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign saved = invocation.getArgument(0);
            var id = Campaign.class.getDeclaredField("campaignId");
            id.setAccessible(true);
            id.set(saved, campaignId);
            return saved;
        });

        CampaignServiceImpl campaigns = new CampaignServiceImpl(campaignRepository, sellerSkus,
                Clock.fixed(now, ZoneOffset.UTC));
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
        when(sellerSkus.findOrderableSellerSku(sellerId, "unknown", ProductSalesChannel.CAMPAIGN)).thenReturn(null);
        CampaignServiceImpl campaigns = new CampaignServiceImpl(campaignRepository, sellerSkus, Clock.systemUTC());

        assertThrows(CampaignException.class, () -> campaigns.create(
                new CreateCampaignRequest(sellerId, "unknown", Duration.ofMinutes(5))));

        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    void unavailableKnownSkuPreventsCampaignCreation() {
        CampaignRepository repository = mock(CampaignRepository.class);
        SellerSkuService sellerSkus = mock(SellerSkuService.class);
        UUID sellerId = UUID.randomUUID();
        when(sellerSkus.findOrderableSellerSku(sellerId, "sku-1", ProductSalesChannel.CAMPAIGN))
                .thenThrow(new CatalogException(CatalogException.Code.PRODUCT_NOT_ORDERABLE, "Unavailable"));
        CampaignServiceImpl service = new CampaignServiceImpl(repository, sellerSkus, Clock.systemUTC());

        assertThrows(CatalogException.class, () -> service.create(
                new CreateCampaignRequest(sellerId, "sku-1", Duration.ofMinutes(5))));

        verify(repository, never()).save(any(Campaign.class));
    }

    @Test
    void unavailableKnownSkuPreventsCampaignOpening() {
        CampaignRepository repository = mock(CampaignRepository.class);
        SellerSkuService sellerSkus = mock(SellerSkuService.class);
        UUID sellerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        Campaign entity = new Campaign(sellerId, "sku-1", CampaignStatus.CLOSED, 300, Instant.now());
        when(repository.findWithWriteLockByCampaignId(campaignId)).thenReturn(Optional.of(entity));
        when(sellerSkus.findOrderableSellerSku(sellerId, "sku-1", ProductSalesChannel.CAMPAIGN))
                .thenThrow(new CatalogException(CatalogException.Code.PRODUCT_NOT_ORDERABLE, "Unavailable"));
        CampaignServiceImpl service = new CampaignServiceImpl(repository, sellerSkus, Clock.systemUTC());

        assertThrows(CatalogException.class, () -> service.open(campaignId));

        verify(repository, never()).save(any(Campaign.class));
    }

    @Test
    void closesOpenCampaignForOwningSeller() {
        CampaignRepository repository = mock(CampaignRepository.class);
        UUID sellerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        Campaign entity = new Campaign(sellerId, "sku-1", CampaignStatus.OPEN, 300, Instant.now());
        when(repository.findWithWriteLockByCampaignId(campaignId)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        CampaignServiceImpl service = new CampaignServiceImpl(
                repository, mock(SellerSkuService.class), Clock.systemUTC());

        var response = service.close(sellerId, campaignId);

        assertEquals(CampaignStatus.CLOSED, response.status());
        verify(repository).save(entity);
    }

    @Test
    void closingAlreadyClosedCampaignIsIdempotent() {
        CampaignRepository repository = mock(CampaignRepository.class);
        UUID sellerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        Campaign entity = new Campaign(sellerId, "sku-1", CampaignStatus.CLOSED, 300, Instant.now());
        when(repository.findWithWriteLockByCampaignId(campaignId)).thenReturn(Optional.of(entity));
        CampaignServiceImpl service = new CampaignServiceImpl(
                repository, mock(SellerSkuService.class), Clock.systemUTC());

        var response = service.close(sellerId, campaignId);

        assertEquals(CampaignStatus.CLOSED, response.status());
        verify(repository, never()).save(any(Campaign.class));
    }

    @Test
    void closeRejectsCampaignOwnedByAnotherSeller() {
        CampaignRepository repository = mock(CampaignRepository.class);
        UUID ownerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        Campaign entity = new Campaign(ownerId, "sku-1", CampaignStatus.OPEN, 300, Instant.now());
        when(repository.findWithWriteLockByCampaignId(campaignId)).thenReturn(Optional.of(entity));
        CampaignServiceImpl service = new CampaignServiceImpl(
                repository, mock(SellerSkuService.class), Clock.systemUTC());

        CampaignException exception = assertThrows(CampaignException.class,
                () -> service.close(UUID.randomUUID(), campaignId));

        assertEquals(CampaignException.Code.NOT_FOUND, exception.code());
        verify(repository, never()).save(any(Campaign.class));
    }

    @Test
    void closedCampaignIsNotOpenForAdmission() {
        CampaignRepository repository = mock(CampaignRepository.class);
        UUID sellerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        Campaign entity = new Campaign(sellerId, "sku-1", CampaignStatus.CLOSED, 300, Instant.now());
        when(repository.findForAdmission(campaignId)).thenReturn(Optional.of(entity));
        CampaignServiceImpl service = new CampaignServiceImpl(
                repository, mock(SellerSkuService.class), Clock.systemUTC());

        assertEquals(Optional.empty(), service.findOpenForAdmission(campaignId, "sku-1"));
    }

    @Test
    void findMissingCampaignThrowsTypedNotFound() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        UUID campaignId = UUID.randomUUID();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());
        CampaignServiceImpl service = new CampaignServiceImpl(campaignRepository, mock(SellerSkuService.class), Clock.systemUTC());

        CampaignException exception = assertThrows(CampaignException.class, () -> service.find(campaignId));

        assertEquals(CampaignException.Code.NOT_FOUND, exception.code());
    }

}
