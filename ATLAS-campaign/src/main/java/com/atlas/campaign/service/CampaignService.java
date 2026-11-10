package com.atlas.campaign.service;

import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.CampaignResponse;
import java.util.Optional;
import java.util.UUID;

public interface CampaignService {

    CampaignResponse create(CreateCampaignRequest request);

    CampaignResponse open(UUID campaignId);

    CampaignResponse close(UUID sellerId, UUID campaignId);

    Optional<CampaignResponse> findOpenForAdmission(UUID campaignId, String skuId);

    CampaignResponse find(UUID campaignId);
}
