package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.service.ProductStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductServiceImplTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ProductServiceImpl service = new ProductServiceImpl(sellers, products, categories, sellerSkus, inventory,
            Clock.fixed(now, ZoneOffset.UTC));
    private final UUID sellerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void legacyV3ListingCursorDecodesAndNextPageUsesV4WhileBatchingOnlyReturnedSkus() {
        Product first = product(sellerId, UUID.randomUUID(), "first");
        Product second = product(sellerId, UUID.randomUUID(), "second");
        Product lookahead = product(sellerId, UUID.randomUUID(), "lookahead");
        UUID cursorProduct = UUID.randomUUID();
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(products.findListingPage(sellerId, null, "blue", sellerId, cursorProduct, 3))
                .thenReturn(List.of(first, second, lookahead));
        when(sellerSkus.findAllByProductIdIn(List.of(first.getProductId(), second.getProductId())))
                .thenReturn(List.of());
        when(products.findAvailableChannelsByProductIdIn(
                List.of(first.getProductId(), second.getProductId()))).thenReturn(List.of());

        ProductListingPageResponse page = service.listProductsPage(sellerId, null, "  BLUE ", 2,
                encodeV3Cursor(sellerId, "blue", sellerId, cursorProduct));

        assertEquals(2, page.items().size());
        assertEquals(encodeV4Cursor(sellerId, "blue", sellerId, second.getProductId()), page.nextCursor());
        verify(sellerSkus).findAllByProductIdIn(List.of(first.getProductId(), second.getProductId()));
        verify(sellerSkus, never()).findAllByProductIdIn(List.of(lookahead.getProductId()));
    }

    @Test
    void listingDefaultsLimitAndNormalizesMissingQuery() {
        when(products.findListingPage(null, null, "", null, null, 11)).thenReturn(List.of());

        ProductListingPageResponse page = service.listProductsPage(null, null, null, null, null);

        assertEquals(List.of(), page.items());
        assertNull(page.nextCursor());
        verify(products).findListingPage(null, null, "", null, null, 11);
    }

    @Test
    void listingRejectsInvalidLimitsMalformedCursorsAndMismatchedSellerCursors() {
        for (int invalid : List.of(-1, 0, 101)) {
            CatalogException exception = assertThrows(CatalogException.class,
                    () -> service.listProductsPage(null, null, null, invalid, null));
            assertEquals(Code.INVALID_REQUEST, exception.code());
        }
        for (String cursor : List.of(
                "!not-base64!",
                encode("invalid:cursor:parts"),
                encode("not-a-uuid:" + productId),
                encode(sellerId + ":"))) {
            CatalogException exception = assertThrows(CatalogException.class,
                    () -> service.listProductsPage(null, null, null, null, cursor));
            assertEquals(Code.INVALID_REQUEST, exception.code());
            assertEquals("Invalid product cursor", exception.getMessage());
        }
        when(sellers.existsById(sellerId)).thenReturn(true);
        CatalogException mismatched = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, null, null, 10,
                        encodeV3Cursor(sellerId, "", UUID.randomUUID(), productId)));
        assertEquals(Code.INVALID_REQUEST, mismatched.code());
        CatalogException mismatchedQuery = assertThrows(CatalogException.class,
                () -> service.listProductsPage(null, null, "blue", 10,
                        encodeV3Cursor(null, "red", sellerId, productId)));
        assertEquals(Code.INVALID_REQUEST, mismatchedQuery.code());
        CatalogException mismatchedSellerFilter = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, null, null, 10,
                        encodeV3Cursor(UUID.randomUUID(), "", sellerId, productId)));
        assertEquals(Code.INVALID_REQUEST, mismatchedSellerFilter.code());
        verify(products, never()).findListingPage(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void listingRequiresExistingSeller() {
        when(sellers.existsById(sellerId)).thenReturn(false);
        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, null, null, 10, null));
        assertEquals(Code.NOT_FOUND, exception.code());
        verify(products, never()).findListingPage(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void createsProductForExistingSeller() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        ProductCategory defaultCategory = mock(ProductCategory.class);
        UUID defaultCategoryId = UUID.randomUUID();
        when(defaultCategory.getCategoryId()).thenReturn(defaultCategoryId);
        when(categories.findBySellerIdAndCode(sellerId, ProductCategory.UNCATEGORIZED_CODE))
                .thenReturn(Optional.of(defaultCategory));
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.createProduct(sellerId, new CreateProductRequest("Tea", "description"));

        assertEquals(sellerId, response.sellerId());
        assertEquals("Tea", response.name());
        assertEquals(ProductStatus.ACTIVE, response.status());
        assertEquals(List.of(ProductSalesChannel.ONLINE_STORE, ProductSalesChannel.CAMPAIGN),
                response.availableChannels());
        verify(products).save(any(Product.class));
    }

    @Test
    void registersSkuAndInitializesInventoryPool() {
        Product product = product(sellerId, productId, "Tea");
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.empty());
        when(sellerSkus.saveAndFlush(any(SellerSku.class))).thenAnswer(call -> call.getArgument(0));

        SellerSkuResponse response = service.registerSku(sellerId, productId,
                new RegisterSellerSkuRequest("TEA-1", 1200, 0));

        assertEquals("Tea", response.productName());
        assertEquals(1200, response.priceWon());
        ArgumentCaptor<SellerSku> savedSku = ArgumentCaptor.forClass(SellerSku.class);
        verify(sellerSkus).saveAndFlush(savedSku.capture());
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
        when(products.findPublicOrderableByProductId(listingProductId)).thenReturn(Optional.of(listingProduct));

        List<String> productSkus = service.findProduct(listingProductId).variants().stream()
                .map(SellerSkuResponse::skuId).toList();

        assertEquals(List.of("SKU-A", "SKU-Z"), productSkus);
    }

    @Test
    void versionThreeCursorsCannotCrossPublicAndManagementListingScopes() {
        String publicCursor = encode("v3:false:" + sellerId + ":-::" + sellerId + ":" + productId);
        String managementCursor = encode("v3:true:" + sellerId + ":-::" + sellerId + ":" + productId);

        CatalogException publicToManagement = assertThrows(CatalogException.class,
                () -> service.listSellerProductsPage(sellerId, null, null, null, publicCursor));
        CatalogException managementToPublic = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, null, null, null, managementCursor));

        assertEquals(Code.INVALID_REQUEST, publicToManagement.code());
        assertEquals(Code.INVALID_REQUEST, managementToPublic.code());
    }

    private static Product product(UUID sellerId, UUID productId, String name) {
        Product product = mock(Product.class);
        when(product.getSellerId()).thenReturn(sellerId);
        when(product.getProductId()).thenReturn(productId);
        when(product.getName()).thenReturn(name);
        when(product.getStatus()).thenReturn(ProductStatus.ACTIVE);
        when(product.getChannelAvailability()).thenReturn(Map.of(
                ProductSalesChannel.ONLINE_STORE, true, ProductSalesChannel.CAMPAIGN, true));
        return product;
    }

    private static String encodeV3Cursor(UUID filterSellerId, String keyword, UUID positionSellerId, UUID productId) {
        String sellerScope = filterSellerId == null ? "-" : filterSellerId.toString();
        String encodedKeyword = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyword.getBytes(StandardCharsets.UTF_8));
        return encode("v3:false:" + sellerScope + ":-:" + encodedKeyword + ":"
                + positionSellerId + ":" + productId);
    }

    private static String encodeV4Cursor(UUID filterSellerId, String keyword, UUID positionSellerId, UUID productId) {
        String sellerScope = filterSellerId == null ? "-" : filterSellerId.toString();
        String encodedKeyword = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyword.getBytes(StandardCharsets.UTF_8));
        return encode("v4:false:" + sellerScope + ":-:" + encodedKeyword + ":"
                + positionSellerId + ":" + productId);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
