package com.atlas.campaign;

import java.util.UUID;

public final class CampaignNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CampaignNotFoundException(UUID campaignId) {
        super("Unknown campaign: " + campaignId);
    }
}
