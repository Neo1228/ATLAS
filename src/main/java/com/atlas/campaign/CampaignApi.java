package com.atlas.campaign;

import java.util.UUID;

public interface CampaignApi {

    CampaignView create(CreateCampaignCommand command);

    CampaignView open(UUID campaignId);

    CampaignView requireOpen(UUID campaignId, String skuId);

    CampaignView find(UUID campaignId);
}
