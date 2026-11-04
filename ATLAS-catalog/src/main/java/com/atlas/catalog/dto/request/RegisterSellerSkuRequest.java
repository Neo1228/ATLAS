package com.atlas.catalog.dto.request;

public record RegisterSellerSkuRequest(String skuId, long priceWon, int initialStock) {
}
