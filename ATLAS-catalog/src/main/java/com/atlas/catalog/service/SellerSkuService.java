package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.persistence.state.ProductSalesChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SellerSkuService {
    SellerSkuResponse find(UUID sellerId, String skuId);
    List<SellerSkuResponse> findAll(UUID sellerId, List<String> skuIds);
    SellerSkuResponse findOrderableSellerSku(UUID sellerId, String skuId, ProductSalesChannel channel);
    SellerSkuResponse findOrderableSellerSku(UUID sellerId, String skuId, ProductSalesChannel channel, Instant asOf);
    List<SellerSkuResponse> findOrderableSellerSkus(UUID sellerId, List<String> skuIds,
            ProductSalesChannel channel, Instant asOf);
    List<SellerSkuResponse> findOrderableSellerSkus(UUID sellerId, List<String> skuIds, ProductSalesChannel channel);
}
