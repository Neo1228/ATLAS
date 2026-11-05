package com.atlas.admission.dto.request;

import java.util.UUID;

public record ConfigureSellerSkuPolicyRequest(
        UUID sellerId,
        String skuId,
        int maxActiveCheckouts) {
}
