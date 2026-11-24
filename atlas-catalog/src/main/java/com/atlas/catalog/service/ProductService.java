package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.model.ProductStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProductService {
    CatalogResponse.Product createProduct(UUID sellerId, ProductRequest.CreateProduct request);
    CatalogResponse.SellerSku registerSku(UUID sellerId, UUID productId, ProductRequest.RegisterSellerSku request);
    CatalogResponse.SellerSku updateSkuPrice(UUID sellerId, UUID productId, String skuId, long priceWon);
    CatalogResponse.SellerSku setSkuSalePrice(UUID sellerId, UUID productId, String skuId, long salePriceWon,
            Instant startsAt, Instant endsAt);
    CatalogResponse.SellerSku clearSkuSalePrice(UUID sellerId, UUID productId, String skuId);
    CatalogResponse.Product updateProductCategories(UUID sellerId, UUID productId, List<UUID> categoryIds);
    CatalogResponse.Product updateStatus(UUID sellerId, UUID productId, ProductStatus status);
    CatalogResponse.Product updateChannelAvailability(UUID sellerId, UUID productId, ProductSalesChannel channel,
            boolean available);
    CatalogResponse.ProductListingPage listSellerProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer limit, String cursor);
    CatalogResponse.ProductListingPage listProductsPage(UUID sellerId, UUID categoryId, String query, Integer limit, String cursor);
    CatalogResponse.ProductListingPage listProductsPage(UUID sellerId, UUID categoryId, String query,
            Long minPriceWon, Long maxPriceWon, Integer limit, String cursor);
    CatalogResponse.ProductListing findProduct(UUID productId);

    /** 공개 구매 가능 상태(ACTIVE + ONLINE_STORE)인지만 확인한다. 전체 상세 조립 없이 존재 검증에 쓴다. */
    boolean isPublicOrderable(UUID productId);
}