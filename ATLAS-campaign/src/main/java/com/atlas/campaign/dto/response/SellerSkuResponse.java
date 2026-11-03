package com.atlas.campaign.dto.response;

import java.util.UUID;

public record SellerSkuResponse(UUID sellerId, String skuId, int maxActiveCheckouts) {
}
