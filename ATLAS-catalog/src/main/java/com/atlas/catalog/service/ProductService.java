package com.atlas.catalog.service;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import java.util.List;
import java.util.UUID;

public interface ProductService {
    record ProductListingPage(List<ProductListingResponse> items, boolean hasMore) {
        public ProductListingPage { items = List.copyOf(items); }
    }
    ProductResponse createProduct(UUID sellerId, CreateProductRequest request);
    SellerSkuResponse registerSku(UUID sellerId, UUID productId, RegisterSellerSkuRequest request);
    SellerSkuResponse updateSkuPrice(UUID sellerId, UUID productId, String skuId, long priceWon);
    ProductListingPage listProductsPage(UUID sellerId, String query, Integer limit,
            UUID cursorSeller, UUID cursorProduct);
    ProductListingResponse findProduct(UUID productId);
}
