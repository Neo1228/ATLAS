package com.atlas.catalog.service;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import java.util.List;
import java.util.UUID;

public interface ProductService {
    ProductResponse createProduct(UUID sellerId, CreateProductRequest request);
    SellerSkuResponse registerSku(UUID sellerId, UUID productId, RegisterSellerSkuRequest request);
    SellerSkuResponse updateSkuPrice(UUID sellerId, UUID productId, String skuId, long priceWon);
    List<ProductListingResponse> listProducts();
    List<ProductListingResponse> listProducts(UUID sellerId);
    List<ProductListingResponse> listProductsPage(UUID sellerId, String keyword, UUID cursorSeller,
            UUID cursorProduct, int limit);
    ProductListingResponse findProduct(UUID productId);
}
