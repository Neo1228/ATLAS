package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.SellerSkuResponse;
import java.util.List;
import java.util.UUID;

public interface SellerSkuService {
    SellerSkuResponse find(UUID sellerId, String skuId);
    List<SellerSkuResponse> findAll(UUID sellerId, List<String> skuIds);
}
