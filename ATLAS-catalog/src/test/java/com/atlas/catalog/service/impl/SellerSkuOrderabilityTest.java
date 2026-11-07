package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductSalesChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerSkuOrderabilityTest {
    private final SellerSkuRepository skus = mock(SellerSkuRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SellerSkuServiceImpl service = new SellerSkuServiceImpl(skus, products);
    private final UUID sellerId = UUID.randomUUID();

    @Test
    void unknownSkuIsOmittedAndOrderableSkuIsReturnedWithOneBatchProductLookup() {
        UUID productId = UUID.randomUUID();
        SellerSku sku = mock(SellerSku.class);
        Product product = mock(Product.class);
        when(sku.getProductId()).thenReturn(productId);
        when(sku.getSellerId()).thenReturn(sellerId);
        when(sku.getSkuId()).thenReturn("SKU-1");
        when(sku.getPriceWon()).thenReturn(1200L);
        when(product.getProductId()).thenReturn(productId);
        when(product.getName()).thenReturn("Tea");
        when(product.isOrderableOn(ProductSalesChannel.ONLINE_STORE)).thenReturn(true);
        when(skus.findAllBySellerIdAndSkuIdIn(sellerId, List.of("SKU-1", "MISSING"))).thenReturn(List.of(sku));
        when(products.findAllByProductIdIn(List.of(productId))).thenReturn(List.of(product));
        when(products.findOrderableProductIds(List.of(productId), ProductSalesChannel.ONLINE_STORE.name()))
                .thenReturn(List.of(productId));

        List<SellerSkuResponse> result = service.findOrderableSellerSkus(sellerId,
                List.of("SKU-1", "MISSING"), ProductSalesChannel.ONLINE_STORE);

        assertEquals(List.of("SKU-1"), result.stream().map(SellerSkuResponse::skuId).toList());
        verify(products).findAllByProductIdIn(List.of(productId));
    }

    @Test
    void knownButUnavailableSkuThrowsTypedCatalogError() {
        UUID productId = UUID.randomUUID();
        SellerSku sku = mock(SellerSku.class);
        Product product = mock(Product.class);
        when(sku.getProductId()).thenReturn(productId);
        when(product.getProductId()).thenReturn(productId);
        when(product.isOrderableOn(ProductSalesChannel.CAMPAIGN)).thenReturn(false);
        when(skus.findBySellerIdAndSkuId(sellerId, "SKU-1")).thenReturn(Optional.of(sku));
        when(skus.findAllBySellerIdAndSkuIdIn(sellerId, List.of("SKU-1"))).thenReturn(List.of(sku));
        when(products.findAllByProductIdIn(List.of(productId))).thenReturn(List.of(product));
        when(products.findOrderableProductIds(List.of(productId), ProductSalesChannel.CAMPAIGN.name()))
                .thenReturn(List.of());

        CatalogException error = assertThrows(CatalogException.class,
                () -> service.findOrderableSellerSku(sellerId, "SKU-1", ProductSalesChannel.CAMPAIGN));

        assertEquals(Code.PRODUCT_NOT_ORDERABLE, error.code());
    }
}
