package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.request.CreateProductCategoryRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryParentRequest;
import com.atlas.catalog.dto.response.ProductCategoryResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.service.ProductCategoryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductCategoryServiceImpl implements ProductCategoryService {
    private final SellerRepository sellers;
    private final ProductCategoryRepository categories;

    @Override
    @Transactional
    public ProductCategoryResponse createCategory(UUID sellerId, CreateProductCategoryRequest request) {
        if (sellerId == null || request == null || request.code() == null || request.code().isBlank()
                || request.code().length() > 128 || request.name() == null || request.name().isBlank()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product category configuration");
        }
        if (!sellers.existsById(sellerId)) throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
        String code = request.code().trim();
        if (ProductCategory.UNCATEGORIZED_CODE.equals(code)) {
            throw new CatalogException(Code.CONFLICT, "UNCATEGORIZED category is reserved");
        }
        if (categories.existsBySellerIdAndCode(sellerId, code)) {
            throw new CatalogException(Code.CONFLICT, "Seller category code is already registered");
        }
        try {
            return toResponse(categories.saveAndFlush(new ProductCategory(sellerId, code, request.name().trim())));
        } catch (DataIntegrityViolationException exception) {
            throw new CatalogException(Code.CONFLICT, "Seller category code is already registered");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductCategoryResponse> listCategories(UUID sellerId) {
        requireSeller(sellerId);
        return categories.findAllBySellerIdOrderByCode(sellerId).stream().map(ProductCategoryServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    public ProductCategoryResponse updateCategoryParent(UUID sellerId, UUID categoryId,
            UpdateProductCategoryParentRequest request) {
        if (sellerId == null) throw new CatalogException(Code.INVALID_REQUEST, "Seller ID required");
        if (request != null && request.parentCategoryId() == null) {
            throw new CatalogException(Code.INVALID_REQUEST, "Parent category ID required");
        }
        sellers.findByIdForUpdate(sellerId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId));
        ProductCategory category = categories.findByCategoryIdAndSellerId(categoryId, sellerId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller category: " + categoryId));
        UUID parentId = request == null ? null : request.parentCategoryId();
        if (ProductCategory.UNCATEGORIZED_CODE.equals(category.getCode()) && parentId != null) {
            throw new CatalogException(Code.CONFLICT, "UNCATEGORIZED must remain a root");
        }
        if (parentId != null) {
            if (parentId.equals(categoryId)) throw new CatalogException(Code.CONFLICT, "Category cannot parent itself");
            if (categories.findByCategoryIdAndSellerId(parentId, sellerId).isEmpty()) {
                throw new CatalogException(Code.NOT_FOUND, "Unknown seller parent category: " + parentId);
            }
            if (categories.isDescendant(sellerId, categoryId, parentId)) {
                throw new CatalogException(Code.CONFLICT, "Category move would create a cycle");
            }
        }
        category.updateParent(parentId);
        return toResponse(categories.save(category));
    }

    @Override
    @Transactional
    public ProductCategoryResponse updateCategoryName(UUID sellerId, UUID categoryId,
            UpdateProductCategoryRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product category name");
        }
        requireSeller(sellerId);
        ProductCategory category = categories.findByCategoryIdAndSellerId(categoryId, sellerId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller category: " + categoryId));
        if (ProductCategory.UNCATEGORIZED_CODE.equals(category.getCode())) {
            throw new CatalogException(Code.CONFLICT, "UNCATEGORIZED category name is reserved");
        }
        category.updateName(request.name().trim());
        return toResponse(categories.save(category));
    }

    private void requireSeller(UUID sellerId) {
        if (sellerId == null) throw new CatalogException(Code.INVALID_REQUEST, "Seller ID required");
        if (!sellers.existsById(sellerId)) throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
    }

    private static ProductCategoryResponse toResponse(ProductCategory category) {
        return new ProductCategoryResponse(category.getCategoryId(), category.getSellerId(), category.getCode(),
                category.getName(), category.getParentCategoryId());
    }
}
