package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.repository.ProductCategoryAssignmentRepository;
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
    private final ProductCategoryAssignmentRepository categoryAssignments =
            mock(ProductCategoryAssignmentRepository.class);
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final ProductServiceImpl service = new ProductServiceImpl(sellers, products, categories,
            categoryAssignments, sellerSkus, inventory, Clock.systemUTC());
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

        assertEquals(List.of(categoryId), response.categoryIds());
    }

    @Test
    void rejectsCategoryOwnedByDifferentSeller() {
        UUID foreignCategory = UUID.randomUUID();
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(sellerId, List.of(foreignCategory)))
                .thenReturn(List.of());

        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId,
                        new CreateProductRequest("Item", null, List.of(foreignCategory))));

        assertEquals(Code.NOT_FOUND, exception.code());
    }

    @Test
    void rejectsDuplicateCategoryIdsBeforeSavingProduct() {
        when(sellers.existsById(sellerId)).thenReturn(true);

        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId,
                        new CreateProductRequest("Item", null, List.of(categoryId, categoryId))));

        assertEquals(Code.INVALID_REQUEST, exception.code());
        verify(products, never()).save(any(Product.class));
    }

    @Test
    void uncategorizedCannotBeCombinedWithAnotherCategory() {
        UUID otherCategoryId = UUID.randomUUID();
        ProductCategory uncategorized = mock(ProductCategory.class);
        ProductCategory otherCategory = mock(ProductCategory.class);
        List<UUID> requestedIds = List.of(categoryId, otherCategoryId);
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(sellerId, requestedIds))
                .thenReturn(List.of(uncategorized, otherCategory));
        when(uncategorized.getCategoryId()).thenReturn(categoryId);
        when(uncategorized.getCode()).thenReturn(ProductCategory.UNCATEGORIZED_CODE);
        when(otherCategory.getCategoryId()).thenReturn(otherCategoryId);
        when(otherCategory.getCode()).thenReturn("OTHER");

        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId, new CreateProductRequest("Item", null, requestedIds)));

        assertEquals(Code.INVALID_REQUEST, exception.code());
        verify(products, never()).save(any(Product.class));
    }

    @Test
    void reassignmentRequiresProductAndCategoryToBelongToSeller() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(sellerId, categoryId, "Item", null);
        ProductCategory category = mock(ProductCategory.class);
        when(products.findByProductIdAndSellerIdForUpdate(productId, sellerId)).thenReturn(Optional.of(product));
        when(categories.findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(sellerId, List.of(categoryId)))
                .thenReturn(List.of(category));
        when(category.getCategoryId()).thenReturn(categoryId);

        var response = service.updateProductCategories(sellerId, productId, List.of(categoryId));

        assertEquals(List.of(categoryId), response.categoryIds());
    }

    @Test
    void categoryReplacementPreservesExistingPrimaryWhenItRemainsAssigned() {
        UUID productId = UUID.randomUUID();
        UUID existingPrimaryId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID addedCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Product product = new Product(sellerId, existingPrimaryId, "Item", null);
        ProductCategory addedCategory = mock(ProductCategory.class);
        ProductCategory existingPrimary = mock(ProductCategory.class);
        when(products.findByProductIdAndSellerIdForUpdate(productId, sellerId)).thenReturn(Optional.of(product));
        when(categories.findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(
                sellerId, List.of(existingPrimaryId, addedCategoryId)))
                .thenReturn(List.of(addedCategory, existingPrimary));
        when(addedCategory.getCategoryId()).thenReturn(addedCategoryId);
        when(existingPrimary.getCategoryId()).thenReturn(existingPrimaryId);

        var response = service.updateProductCategories(sellerId, productId,
                List.of(existingPrimaryId, addedCategoryId));

        assertEquals(existingPrimaryId, product.getPrimaryCategoryId());
        assertEquals(List.of(addedCategoryId, existingPrimaryId), response.categoryIds());
    }

    @Test
    void categoryReplacementReturnsSortedIdsWhenPrimarySortsBeforeAdditionalCategories() {
        UUID productId = UUID.randomUUID();
        UUID primaryCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID additionalCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Product product = new Product(sellerId, primaryCategoryId, "Item", null);
        ProductCategory primaryCategory = mock(ProductCategory.class);
        ProductCategory additionalCategory = mock(ProductCategory.class);
        when(products.findByProductIdAndSellerIdForUpdate(productId, sellerId)).thenReturn(Optional.of(product));
        when(categories.findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(
                sellerId, List.of(additionalCategoryId, primaryCategoryId)))
                .thenReturn(List.of(primaryCategory, additionalCategory));
        when(primaryCategory.getCategoryId()).thenReturn(primaryCategoryId);
        when(additionalCategory.getCategoryId()).thenReturn(additionalCategoryId);

        var response = service.updateProductCategories(sellerId, productId,
                List.of(additionalCategoryId, primaryCategoryId));

        assertEquals(primaryCategoryId, product.getPrimaryCategoryId());
        assertEquals(List.of(primaryCategoryId, additionalCategoryId), response.categoryIds());
    }

    @Test
    void categoryReplacementChoosesFirstSortedCategoryWhenRemovingExistingPrimary() {
        UUID productId = UUID.randomUUID();
        UUID existingPrimaryId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID replacementCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Product product = new Product(sellerId, existingPrimaryId, "Item", null);
        ProductCategory replacementCategory = mock(ProductCategory.class);
        when(products.findByProductIdAndSellerIdForUpdate(productId, sellerId)).thenReturn(Optional.of(product));
        when(categories.findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(
                sellerId, List.of(replacementCategoryId)))
                .thenReturn(List.of(replacementCategory));
        when(replacementCategory.getCategoryId()).thenReturn(replacementCategoryId);

        var response = service.updateProductCategories(sellerId, productId, List.of(replacementCategoryId));

        assertEquals(replacementCategoryId, product.getPrimaryCategoryId());
        assertEquals(List.of(replacementCategoryId), response.categoryIds());
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
    void categoryFilterEmitsV4Cursor() {
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
        assertTrue(decoded.startsWith("v4:false:"));
    }

    private Product product(UUID productId) {
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(productId);
        when(product.getSellerId()).thenReturn(sellerId);
        when(product.getName()).thenReturn("Item");
        when(product.getPrimaryCategoryId()).thenReturn(categoryId);
        return product;
    }

    private static String cursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}