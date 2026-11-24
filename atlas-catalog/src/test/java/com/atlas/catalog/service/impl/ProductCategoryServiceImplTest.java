package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.request.CreateProductCategoryRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryRequest;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.SellerRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductCategoryServiceImplTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final ProductCategoryServiceImpl service = new ProductCategoryServiceImpl(sellers, categories);
    private final UUID sellerId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @Test
    void createsCategoryForSellerAndReturnsStableCode() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.saveAndFlush(any(ProductCategory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createCategory(sellerId, new CreateProductCategoryRequest("SHOES", "Shoes"));

        assertEquals(sellerId, response.sellerId());
        assertEquals("SHOES", response.code());
        assertEquals("Shoes", response.name());
    }

    @Test
    void rejectsDuplicateSellerCodeAsConflict() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.existsBySellerIdAndCode(sellerId, "SHOES")).thenReturn(true);

        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.createCategory(sellerId, new CreateProductCategoryRequest("SHOES", "Shoes")));

        assertEquals(Code.CONFLICT, exception.code());
    }

    @Test
    void cannotUpdateCategoryOwnedByAnotherSeller() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(categories.findByCategoryIdAndSellerId(categoryId, sellerId)).thenReturn(Optional.empty());

        CatalogException exception = assertThrows(CatalogException.class, () -> service.updateCategoryName(sellerId,
                categoryId, new UpdateProductCategoryRequest("New name")));

        assertEquals(Code.NOT_FOUND, exception.code());
        verify(categories).findByCategoryIdAndSellerId(categoryId, sellerId);
    }

    @Test
    void defaultCategoryCannotBeRenamed() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        ProductCategory uncategorized = mock(ProductCategory.class);
        when(uncategorized.getCode()).thenReturn(ProductCategory.UNCATEGORIZED_CODE);
        when(categories.findByCategoryIdAndSellerId(categoryId, sellerId)).thenReturn(Optional.of(uncategorized));

        CatalogException exception = assertThrows(CatalogException.class, () -> service.updateCategoryName(sellerId,
                categoryId, new UpdateProductCategoryRequest("Other")));

        assertEquals(Code.CONFLICT, exception.code());
    }

    @Test
    void listsCategoriesInAscendingCodeOrder() {
        when(sellers.existsById(sellerId)).thenReturn(true);

        service.listCategories(sellerId);

        verify(categories).findAllBySellerIdOrderByCode(sellerId);
    }
}
