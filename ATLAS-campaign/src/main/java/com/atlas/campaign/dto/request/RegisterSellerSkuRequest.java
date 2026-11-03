package com.atlas.campaign.dto.request;

public record RegisterSellerSkuRequest(String skuId, int initialStock, int maxActiveCheckouts) {
}
