package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.inventory.service.InventoryService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductCategoryProductServiceTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final ProductServiceImpl service = new ProductServiceImpl(sellers, products, categories, sellerSkus,
            inventory, Clock.systemUTC());
    private final UUID sellerId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @Test
    void productWithoutCategoryUsesSellerDefault() {
        ProductCategory category = mock(ProductCategory.class);
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findBySellerIdAndCode(sellerId, ProductCategory.UNCATEGORIZED_CODE))
                .thenReturn(Optional.of(category));
        when(category.getCategoryId()).thenReturn(categoryId);
        when(products.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createProduct(sellerId, new CreateProductRequest("Item", null));

        assertEquals(categoryId, response.categoryId());
    }

    @Test
    void rejectsCategoryOwnedByDifferentSeller() {
        UUID foreignCategory = UUID.randomUUID();
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findByCategoryIdAndSellerId(foreignCategory, sellerId)).thenReturn(Optional.empty());

        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId, new CreateProductRequest("Item", null, foreignCategory)));

        assertEquals(Code.NOT_FOUND, exception.code());
    }

    @Test
    void reassignmentRequiresProductAndCategoryToBelongToSeller() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(sellerId, UUID.randomUUID(), "Item", null);
        ProductCategory category = mock(ProductCategory.class);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(categories.findByCategoryIdAndSellerId(categoryId, sellerId)).thenReturn(Optional.of(category));
        when(products.save(product)).thenReturn(product);

        var response = service.updateProductCategory(sellerId, productId, categoryId);

        assertEquals(categoryId, response.categoryId());
    }

    @Test
    void categoryFilterUsesSellerScopedRepositoryQuery() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        ProductCategory category = mock(ProductCategory.class);
        when(categories.findByCategoryIdAndSellerId(categoryId, sellerId)).thenReturn(Optional.of(category));
        when(products.findListingPage(sellerId, categoryId, "item", null, null, 11)).thenReturn(List.of());

        service.listProductsPage(sellerId, categoryId, " Item ", null, null);

        verify(products).findListingPage(sellerId, categoryId, "item", null, null, 11);
    }

    @Test
    void categoryFilterRequiresSellerScopeAndCursorIsBoundToFilters() {
        CatalogException noSeller = assertThrows(CatalogException.class,
                () -> service.listProductsPage(null, categoryId, null, null, null));
        assertEquals(Code.INVALID_REQUEST, noSeller.code());

        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findByCategoryIdAndSellerId(categoryId, sellerId))
                .thenReturn(Optional.of(mock(ProductCategory.class)));
        String v2Cursor = cursor("v2:" + sellerId + ":" + categoryId + ":" + encode("item") + ":"
                + sellerId + ":" + UUID.randomUUID());

        CatalogException changedCategory = assertThrows(CatalogException.class,
                () -> service.listProductsPage(sellerId, UUID.randomUUID(), "item", null, v2Cursor));

        assertEquals(Code.INVALID_REQUEST, changedCategory.code());
    }

    @Test
    void legacyV1CursorRemainsValidWhenCategoryIsAbsent() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(products.findListingPage(sellerId, null, "item", sellerId, categoryId, 11)).thenReturn(List.of());
        String v1Cursor = cursor("v1:" + sellerId + ":" + encode("item") + ":" + sellerId + ":" + categoryId);

        service.listProductsPage(sellerId, null, "item", null, v1Cursor);

        verify(products).findListingPage(sellerId, null, "item", sellerId, categoryId, 11);
    }

    @Test
    void categoryFilterEmitsV2Cursor() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findByCategoryIdAndSellerId(categoryId, sellerId))
                .thenReturn(Optional.of(mock(ProductCategory.class)));
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Product first = product(firstId);
        Product second = product(secondId);
        when(products.findListingPage(sellerId, categoryId, "", null, null, 2)).thenReturn(List.of(first, second));
        when(sellerSkus.findAllByProductIdIn(List.of(firstId))).thenReturn(List.of());

        ProductListingPageResponse page = service.listProductsPage(sellerId, categoryId, null, 1, null);

        String decoded = new String(Base64.getUrlDecoder().decode(page.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(decoded.startsWith("v2:"));
    }

    private Product product(UUID productId) {
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(productId);
        when(product.getSellerId()).thenReturn(sellerId);
        when(product.getName()).thenReturn("Item");
        when(product.getCategoryId()).thenReturn(categoryId);
        return product;
    }

    private static String cursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
