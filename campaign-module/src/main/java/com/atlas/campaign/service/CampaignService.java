package com.atlas.campaign.service;

import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.dto.request.CreateCampaignRequest;

import java.util.UUID;

public interface CampaignService {

    CampaignResponse create(CreateCampaignRequest request);

    CampaignResponse open(UUID campaignId);

    CampaignResponse requireOpen(UUID campaignId, String skuId);

    CampaignResponse find(UUID campaignId);
}
