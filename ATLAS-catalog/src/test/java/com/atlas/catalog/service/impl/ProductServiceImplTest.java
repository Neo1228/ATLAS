package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductService.ProductListingPage;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductServiceImplTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ProductServiceImpl service = new ProductServiceImpl(sellers, products, sellerSkus, inventory,
            Clock.fixed(now, ZoneOffset.UTC));
    private final UUID sellerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void listingRequestsOneLookaheadButBatchesSkusOnlyForReturnedItems() {
        Product first = product(sellerId, UUID.randomUUID(), "first");
        Product second = product(sellerId, UUID.randomUUID(), "second");
        Product lookahead = product(sellerId, UUID.randomUUID(), "lookahead");
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(products.findListingPage(sellerId, "blue", null, null, 3)).thenReturn(List.of(first, second, lookahead));
        when(sellerSkus.findAllByProductIdIn(List.of(first.getProductId(), second.getProductId())))
                .thenReturn(List.of());

        ProductListingPage page = service.listProductsPage(sellerId, "  BLUE ", 2, null, null);

        assertEquals(2, page.items().size());
        assertEquals(true, page.hasMore());
        verify(sellerSkus).findAllByProductIdIn(List.of(first.getProductId(), second.getProductId()));
        verify(sellerSkus, never()).findAllByProductIdIn(List.of(lookahead.getProductId()));
    }

    @Test
    void listingDefaultsLimitAndNormalizesMissingQuery() {
        when(products.findListingPage(null, "", null, null, 11)).thenReturn(List.of());

        ProductListingPage page = service.listProductsPage(null, null, null, null, null);

        assertEquals(List.of(), page.items());
        assertEquals(false, page.hasMore());
        verify(products).findListingPage(null, "", null, null, 11);
    }

    @Test
    void listingRejectsInvalidLimitsAndIncompleteOrMismatchedCursors() {
        for (int invalid : List.of(-1, 0, 101)) {
            CatalogException exception = assertThrows(CatalogException.class,
                    () -> service.listProductsPage(null, null, invalid, null, null));
            assertEquals(Code.INVALID_REQUEST, exception.code());
        }
        CatalogException incomplete = assertThrows(CatalogException.class,
                () -> service.listProductsPage(null, null, 10, sellerId, null));
        when(sellers.existsById(sellerId)).thenReturn(true);
        CatalogException mismatched = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, null, 10, UUID.randomUUID(), productId));
        assertEquals(Code.INVALID_REQUEST, incomplete.code());
        assertEquals(Code.INVALID_REQUEST, mismatched.code());
        verify(products, never()).findListingPage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void listingRequiresExistingSeller() {
        when(sellers.existsById(sellerId)).thenReturn(false);
        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, null, 10, null, null));
        assertEquals(Code.NOT_FOUND, exception.code());
        verify(products, never()).findListingPage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void createsProductForExistingSeller() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.createProduct(sellerId, new CreateProductRequest("Tea", "description"));

        assertEquals(sellerId, response.sellerId());
        assertEquals("Tea", response.name());
        verify(products).save(any(Product.class));
    }

    @Test
    void registersSkuAndInitializesInventoryPool() {
        Product product = product(sellerId, productId, "Tea");
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.empty());
        when(sellerSkus.save(any(SellerSku.class))).thenAnswer(call -> call.getArgument(0));

        SellerSkuResponse response = service.registerSku(sellerId, productId,
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
        Product product = product(sellerId, productId, "Tea");
        SellerSku sku = new SellerSku(sellerId, productId, "TEA-1", 1200, now);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.of(sku));
        when(sellerSkus.save(sku)).thenReturn(sku);

        SellerSkuResponse response = service.updateSkuPrice(sellerId, productId, "TEA-1", 1500);

        assertEquals(1500, response.priceWon());
        assertEquals("Tea", response.productName());
        verify(sellerSkus).save(sku);
    }

    @Test
    void rejectsUnknownSeller() {
        when(sellers.existsById(sellerId)).thenReturn(false);
        CatalogException error = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId, new CreateProductRequest("Tea", "description")));
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

    @Test
    void sortsVariantsBySkuIdInSingleProductLookup() {
        UUID listingSellerId = UUID.randomUUID();
        UUID listingProductId = UUID.randomUUID();
        Product listingProduct = product(listingSellerId, listingProductId, "Tea");
        SellerSku laterSku = mock(SellerSku.class);
        when(laterSku.getSellerId()).thenReturn(listingSellerId);
        when(laterSku.getProductId()).thenReturn(listingProductId);
        when(laterSku.getSkuId()).thenReturn("SKU-Z");
        when(laterSku.getPriceWon()).thenReturn(1500L);
        SellerSku earlierSku = mock(SellerSku.class);
        when(earlierSku.getSellerId()).thenReturn(listingSellerId);
        when(earlierSku.getProductId()).thenReturn(listingProductId);
        when(earlierSku.getSkuId()).thenReturn("SKU-A");
        when(earlierSku.getPriceWon()).thenReturn(1200L);
        when(sellerSkus.findAllByProductId(listingProductId)).thenReturn(List.of(laterSku, earlierSku));
        when(products.findById(listingProductId)).thenReturn(Optional.of(listingProduct));

        List<String> productSkus = service.findProduct(listingProductId).variants().stream()
                .map(SellerSkuResponse::skuId).toList();

        assertEquals(List.of("SKU-A", "SKU-Z"), productSkus);
    }

    private static Product product(UUID sellerId, UUID productId, String name) {
        Product product = mock(Product.class);
        when(product.getSellerId()).thenReturn(sellerId);
        when(product.getProductId()).thenReturn(productId);
        when(product.getName()).thenReturn(name);
        return product;
    }
}
