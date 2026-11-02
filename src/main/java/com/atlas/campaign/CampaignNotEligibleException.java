package com.atlas.campaign;

import java.util.UUID;

public final class CampaignNotEligibleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CampaignNotEligibleException(UUID campaignId, String skuId) {
        super("Campaign is not open for SKU " + skuId + ": " + campaignId);
    }
}
