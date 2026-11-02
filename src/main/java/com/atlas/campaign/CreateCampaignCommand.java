package com.atlas.campaign;

import java.time.Duration;

public record CreateCampaignCommand(
        String skuId,
        int inventoryCapacity,
        Duration queueTicketTtl,
        int maxActiveCheckouts) {
}
