package com.atlas.campaign.dto.response;

import com.atlas.campaign.persistence.state.CampaignStatus;

import java.time.Duration;
import java.util.UUID;

public record CampaignResponse(
        UUID campaignId,
        String skuId,
        CampaignStatus status,
        Duration queueTicketTtl,
        int maxActiveCheckouts) {
}
