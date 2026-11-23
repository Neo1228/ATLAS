package com.atlas.admission.dto.request;

import java.time.Duration;
import java.util.UUID;

public record CreateCampaignRequest(UUID sellerId, String skuId, Duration queueTicketTtl) {
}
