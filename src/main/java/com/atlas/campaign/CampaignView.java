package com.atlas.campaign;

import java.time.Duration;
import java.util.UUID;

public record CampaignView(
        UUID campaignId,
        String skuId,
        CampaignStatus status,
        Duration queueTicketTtl,
        int maxActiveCheckouts) {
}
