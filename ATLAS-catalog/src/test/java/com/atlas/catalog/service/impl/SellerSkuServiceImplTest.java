package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductSalesChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerSkuServiceImplTest {
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
    private final SellerSkuServiceImpl service = new SellerSkuServiceImpl(sellerSkus, products, clock);
    private final UUID sellerId = UUID.randomUUID();

    @Test
    void emptySkuListReturnsWithoutRepositoryQueries() {
        assertEquals(List.of(), service.findAll(sellerId, List.of()));
        verify(sellerSkus, never()).findAllBySellerIdAndSkuIdIn(sellerId, List.of());
        verify(products, never()).findAllByProductIdIn(List.of());
    }

    @Test
    void mapsMultipleSkusWithOneSkuAndOneProductQuery() {
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        List<String> skuIds = List.of("TEA-1", "TEA-2");
        SellerSku firstSku = new SellerSku(sellerId, firstProductId, "TEA-1", 1200, Instant.EPOCH);
        SellerSku secondSku = new SellerSku(sellerId, secondProductId, "TEA-2", 1500, Instant.EPOCH);
        Product firstProduct = mock(Product.class);
        Product secondProduct = mock(Product.class);
        when(firstProduct.getProductId()).thenReturn(firstProductId);
        when(firstProduct.getName()).thenReturn("Green tea");
        when(secondProduct.getProductId()).thenReturn(secondProductId);
        when(secondProduct.getName()).thenReturn("Black tea");
        when(sellerSkus.findAllBySellerIdAndSkuIdIn(sellerId, skuIds)).thenReturn(List.of(firstSku, secondSku));
        when(products.findAllByProductIdIn(List.of(firstProductId, secondProductId)))
                .thenReturn(List.of(firstProduct, secondProduct));

        var responses = service.findAll(sellerId, skuIds);

        assertEquals(2, responses.size());
        assertEquals("Green tea", responses.get(0).productName());
        assertEquals("TEA-1", responses.get(0).skuId());
        assertEquals(1200, responses.get(0).priceWon());
        assertEquals("Black tea", responses.get(1).productName());
        assertEquals("TEA-2", responses.get(1).skuId());
        assertEquals(1500, responses.get(1).priceWon());
        verify(sellerSkus).findAllBySellerIdAndSkuIdIn(sellerId, skuIds);
        verify(products).findAllByProductIdIn(List.of(firstProductId, secondProductId));
    }
    @Test
    void orderableSellerSkusReuseBatchedProductLookupForSharedProduct() {
        UUID productId = UUID.randomUUID();
        List<String> skuIds = List.of("TEA-1", "TEA-2");
        SellerSku firstSku = new SellerSku(sellerId, productId, "TEA-1", 1200, Instant.EPOCH);
        SellerSku secondSku = new SellerSku(sellerId, productId, "TEA-2", 1500, Instant.EPOCH);
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(productId);
        when(product.getName()).thenReturn("Green tea");
        when(sellerSkus.findAllBySellerIdAndSkuIdIn(sellerId, skuIds))
                .thenReturn(List.of(firstSku, secondSku));
        when(products.findAllByProductIdIn(List.of(productId))).thenReturn(List.of(product));
        when(products.findOrderableProductIds(List.of(productId), ProductSalesChannel.ONLINE_STORE.name()))
                .thenReturn(List.of(productId));

        List<SellerSkuResponse> responses = service.findOrderableSellerSkus(
                sellerId, skuIds, ProductSalesChannel.ONLINE_STORE, Instant.EPOCH);

        assertEquals(List.of("TEA-1", "TEA-2"),
                responses.stream().map(SellerSkuResponse::skuId).toList());
        assertEquals(List.of("Green tea", "Green tea"),
                responses.stream().map(SellerSkuResponse::productName).toList());
        verify(products).findAllByProductIdIn(List.of(productId));
        verify(products).findOrderableProductIds(List.of(productId), ProductSalesChannel.ONLINE_STORE.name());
    }

    @Test
    void orderableSkuUsesSalePriceOnlyInsideHalfOpenWindow() {
        UUID productId = UUID.randomUUID();
        String skuId = "SALE-1";
        Instant startsAt = Instant.parse("2026-11-08T12:00:00Z");
        Instant endsAt = Instant.parse("2026-11-08T13:00:00Z");
        SellerSku sku = new SellerSku(sellerId, productId, skuId, 1500, Instant.EPOCH);
        sku.setSalePrice(1200, startsAt, endsAt);
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(productId);
        when(product.getName()).thenReturn("Green tea");
        when(sellerSkus.findAllBySellerIdAndSkuIdIn(sellerId, List.of(skuId))).thenReturn(List.of(sku));
        when(products.findAllByProductIdIn(List.of(productId))).thenReturn(List.of(product));
        when(products.findOrderableProductIds(List.of(productId), ProductSalesChannel.ONLINE_STORE.name()))
                .thenReturn(List.of(productId));
        when(products.findOrderableProductIds(List.of(productId), ProductSalesChannel.CAMPAIGN.name()))
                .thenReturn(List.of(productId));

        var beforeStart = service.findOrderableSellerSku(
                sellerId, skuId, ProductSalesChannel.ONLINE_STORE, startsAt.minusNanos(1));
        var atStart = service.findOrderableSellerSku(
                sellerId, skuId, ProductSalesChannel.ONLINE_STORE, startsAt);
        var atEnd = service.findOrderableSellerSku(
                sellerId, skuId, ProductSalesChannel.ONLINE_STORE, endsAt);
        SellerSkuResponse campaignPrice = service.findOrderableSellerSku(
                sellerId, skuId, ProductSalesChannel.CAMPAIGN, startsAt);

        assertEquals(1500, beforeStart.effectivePriceWon());
        assertEquals(1200, atStart.effectivePriceWon());
        assertEquals(1500, atEnd.effectivePriceWon());
        assertEquals(1200, campaignPrice.effectivePriceWon());
        assertEquals(1500, atStart.priceWon());
        assertEquals(1200, atStart.salePriceWon());
    }
}
