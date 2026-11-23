package com.atlas.order.dto.request;

import java.util.UUID;

public record StockClaimRequest(UUID fulfillmentLocationId, UUID sellerId, String skuId, int quantity) {}
