package com.atlas.campaign.service.impl;

import com.atlas.campaign.dto.request.RegisterSellerSkuRequest;
import com.atlas.campaign.dto.response.SellerSkuResponse;
import com.atlas.campaign.persistence.entity.SellerSku;
import com.atlas.campaign.repository.SellerRepository;
import com.atlas.campaign.repository.SellerSkuRepository;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerSkuServiceImplTest {

    @Test
    void registersGlobalCheckoutLimitAndCreatesInitialInventoryPool() {
        SellerRepository sellers = mock(SellerRepository.class);
        SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        UUID sellerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "sku-1")).thenReturn(Optional.empty());
        when(sellerSkus.save(any(SellerSku.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerSkuServiceImpl service = new SellerSkuServiceImpl(sellers, sellerSkus, inventory,
                Clock.fixed(now, ZoneOffset.UTC));
        SellerSkuResponse response = service.register(sellerId,
                new RegisterSellerSkuRequest("sku-1", 50, 4));

        assertEquals(new SellerSkuResponse(sellerId, "sku-1", 4), response);
        verify(sellerSkus).save(any(SellerSku.class));
        verify(inventory).createPool(new CreateInventoryPoolRequest(sellerId, "sku-1", 50));
    }
}
