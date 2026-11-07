package com.atlas.catalog.service;

import com.atlas.catalog.dto.request.CreateProductCategoryRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryRequest;
import com.atlas.catalog.dto.response.ProductCategoryResponse;
import java.util.List;
import java.util.UUID;

public interface ProductCategoryService {
    ProductCategoryResponse createCategory(UUID sellerId, CreateProductCategoryRequest request);
    List<ProductCategoryResponse> listCategories(UUID sellerId);
    ProductCategoryResponse updateCategoryName(UUID sellerId, UUID categoryId, UpdateProductCategoryRequest request);
}
