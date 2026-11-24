package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductCategoryRequest;
import java.util.List;
import java.util.UUID;

public interface ProductCategoryService {
    CatalogResponse.ProductCategory createCategory(UUID sellerId, ProductCategoryRequest.Create request);
    List<CatalogResponse.ProductCategory> listCategories(UUID sellerId);
    CatalogResponse.ProductCategory updateCategoryParent(UUID sellerId, UUID categoryId, ProductCategoryRequest.UpdateParent request);
    CatalogResponse.ProductCategory updateCategoryName(UUID sellerId, UUID categoryId, ProductCategoryRequest.Update request);
}
