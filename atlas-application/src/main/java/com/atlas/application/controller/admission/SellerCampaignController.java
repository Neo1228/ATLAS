package com.atlas.application.controller.admission;

import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.service.CampaignService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sellers/{sellerId}/campaigns")
public class SellerCampaignController {
    private final CampaignService campaigns;

    public SellerCampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @PostMapping("/{campaignId}/close")
    public AdmissionResponse.Campaign close(@PathVariable UUID sellerId, @PathVariable UUID campaignId) {
        return campaigns.close(sellerId, campaignId);
    }
}
