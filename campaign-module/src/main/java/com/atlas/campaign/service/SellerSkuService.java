package com.atlas.campaign.service;

import com.atlas.campaign.dto.request.RegisterSellerSkuRequest;
import com.atlas.campaign.dto.response.SellerSkuResponse;
import java.util.UUID;

public interface SellerSkuService {

    SellerSkuResponse register(UUID sellerId, RegisterSellerSkuRequest request);

    SellerSkuResponse find(UUID sellerId, String skuId);
}
