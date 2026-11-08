package com.atlas.catalog.service;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import java.time.Instant;
import java.util.UUID;

public interface ProductService {
    ProductResponse createProduct(UUID sellerId, CreateProductRequest request);
    SellerSkuResponse registerSku(UUID sellerId, UUID productId, RegisterSellerSkuRequest request);
    SellerSkuResponse updateSkuPrice(UUID sellerId, UUID productId, String skuId, long priceWon);
    SellerSkuResponse setSkuSalePrice(UUID sellerId, UUID productId, String skuId, long salePriceWon,
            Instant startsAt, Instant endsAt);
    SellerSkuResponse clearSkuSalePrice(UUID sellerId, UUID productId, String skuId);
    ProductResponse updateProductCategory(UUID sellerId, UUID productId, UUID categoryId);
    ProductResponse updateStatus(UUID sellerId, UUID productId, ProductStatus status);
    ProductResponse updateChannelAvailability(UUID sellerId, UUID productId, ProductSalesChannel channel,
            boolean available);
    ProductListingPageResponse listSellerProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer limit, String cursor);
    ProductListingPageResponse listProductsPage(UUID sellerId, UUID categoryId, String query, Integer limit, String cursor);
    ProductListingResponse findProduct(UUID productId);
}
