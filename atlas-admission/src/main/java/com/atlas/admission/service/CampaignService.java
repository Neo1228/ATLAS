package com.atlas.admission.service;

import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import java.util.Optional;
import java.util.UUID;

public interface CampaignService {

    AdmissionResponse.Campaign create(AdmissionRequest.CreateCampaign request);

    AdmissionResponse.Campaign open(UUID campaignId);

    AdmissionResponse.Campaign close(UUID sellerId, UUID campaignId);

    Optional<AdmissionResponse.Campaign> findOpenForAdmission(UUID campaignId, String skuId);

    AdmissionResponse.Campaign find(UUID campaignId);
}
