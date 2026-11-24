package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.model.ProductSalesChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SellerSkuService {
    CatalogResponse.SellerSku find(UUID sellerId, String skuId);
    List<CatalogResponse.SellerSku> findAll(UUID sellerId, List<String> skuIds);
    CatalogResponse.SellerSku findOrderableSellerSku(UUID sellerId, String skuId, ProductSalesChannel channel);
    CatalogResponse.SellerSku findOrderableSellerSku(UUID sellerId, String skuId, ProductSalesChannel channel, Instant asOf);
    List<CatalogResponse.SellerSku> findOrderableSellerSkus(UUID sellerId, List<String> skuIds,
            ProductSalesChannel channel, Instant asOf);
    List<CatalogResponse.SellerSku> findOrderableSellerSkus(UUID sellerId, List<String> skuIds, ProductSalesChannel channel);
}
