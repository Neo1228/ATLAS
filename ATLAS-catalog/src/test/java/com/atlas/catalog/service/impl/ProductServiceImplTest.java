package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceImplTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ProductServiceImpl service = new ProductServiceImpl(sellers, products, sellerSkus,
            new SellerSkuServiceImpl(sellerSkus, products), inventory, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID sellerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void createsProductForExistingSeller() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.createProduct(sellerId, new CreateProductRequest("Tea", "Green tea"));

        assertEquals(sellerId, response.sellerId());
        assertEquals("Tea", response.name());
        verify(products).save(any(Product.class));
    }

    @Test
    void registersSkuAndInitializesInventoryPool() {
        Product product = new Product(sellerId, "Tea", null);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.empty());
        when(sellerSkus.save(any(SellerSku.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.registerSku(sellerId, productId,
                new RegisterSellerSkuRequest("TEA-1", 1200, 0));

        assertEquals("Tea", response.productName());
        assertEquals(1200, response.priceWon());
        ArgumentCaptor<SellerSku> savedSku = ArgumentCaptor.forClass(SellerSku.class);
        verify(sellerSkus).save(savedSku.capture());
        assertEquals(now, savedSku.getValue().getCreatedAt());
        verify(inventory).createInventoryPool(new CreateInventoryPoolRequest(sellerId, "TEA-1", 0));
    }

    @Test
    void updatesOwnedSkuPriceAndReturnsUpdatedResponse() {
        Product product = new Product(sellerId, "Tea", null);
        SellerSku sku = new SellerSku(sellerId, productId, "TEA-1", 1200, now);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.of(sku));
        when(sellerSkus.save(sku)).thenReturn(sku);

        var response = service.updateSkuPrice(sellerId, productId, "TEA-1", 1500);

        assertEquals(1500, response.priceWon());
        assertEquals("Tea", response.productName());
        verify(sellerSkus).save(sku);
    }

    @Test
    void rejectsUnknownSeller() {
        when(sellers.existsById(sellerId)).thenReturn(false);

        CatalogException error = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId, new CreateProductRequest("Tea", null)));

        assertEquals(Code.NOT_FOUND, error.code());
    }

    @Test
    void rejectsInvalidSkuAndNonPositivePrice() {
        CatalogException invalidSku = assertThrows(CatalogException.class,
                () -> service.registerSku(sellerId, productId, new RegisterSellerSkuRequest(" ", 1200, 0)));
        CatalogException invalidPrice = assertThrows(CatalogException.class,
                () -> service.registerSku(sellerId, productId, new RegisterSellerSkuRequest("TEA-1", 0, 0)));

        assertEquals(Code.INVALID_REQUEST, invalidSku.code());
        assertEquals(Code.INVALID_REQUEST, invalidPrice.code());
        verify(products, never()).findById(productId);
        verify(inventory, never()).createInventoryPool(any());
    }
}
