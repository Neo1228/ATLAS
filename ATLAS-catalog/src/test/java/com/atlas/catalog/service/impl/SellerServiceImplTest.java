package com.atlas.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.persistence.entity.Seller;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.SellerRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
class SellerServiceImplTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final SellerServiceImpl service = new SellerServiceImpl(sellers, categories);

    @Test
    void createsUncategorizedCategoryWithNewSeller() {
        UUID sellerId = UUID.randomUUID();
        Seller seller = mock(Seller.class);
        when(sellers.saveAndFlush(any(Seller.class))).thenReturn(seller);
        when(seller.getSellerId()).thenReturn(sellerId);

        service.createSeller();

        ArgumentCaptor<ProductCategory> categoryCaptor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categories).save(categoryCaptor.capture());
        assertEquals(sellerId, categoryCaptor.getValue().getSellerId());
        assertEquals(ProductCategory.UNCATEGORIZED_CODE, categoryCaptor.getValue().getCode());
        assertEquals(ProductCategory.UNCATEGORIZED_NAME, categoryCaptor.getValue().getName());
    }
}
