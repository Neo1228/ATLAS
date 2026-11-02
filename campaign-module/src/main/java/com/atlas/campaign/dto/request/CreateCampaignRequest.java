package com.atlas.campaign.dto.request;

import java.time.Duration;

public record CreateCampaignRequest(
        String skuId,
        int inventoryCapacity,
        Duration queueTicketTtl,
        int maxActiveCheckouts) {
}
