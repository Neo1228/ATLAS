package com.atlas.admission.dto.response;

import com.atlas.admission.persistence.state.CampaignStatus;
import java.time.Duration;
import java.util.UUID;

public record CampaignResponse(UUID campaignId, UUID sellerId, String skuId, CampaignStatus status,
        Duration queueTicketTtl) {
}
