package com.atlas.application.controller.campaign;

import com.atlas.application.controller.dto.CreateCampaignHttpRequest;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.service.CampaignService;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaigns;

    public CampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignResponse create(@Valid @RequestBody CreateCampaignHttpRequest request) {
        return campaigns.create(new CreateCampaignRequest(request.skuId(), request.inventoryCapacity(),
                Duration.ofSeconds(request.queueTicketTtlSeconds()), request.maxActiveCheckouts()));
    }

    @PostMapping("/{campaignId}/open")
    public CampaignResponse open(@PathVariable UUID campaignId) {
        return campaigns.open(campaignId);
    }

    @GetMapping("/{campaignId}")
    public CampaignResponse find(@PathVariable UUID campaignId) {
        CampaignResponse campaign = campaigns.find(campaignId);
        if (campaign == null) throw new CampaignException(CampaignException.Code.NOT_FOUND,
                "Unknown campaign: " + campaignId);
        return campaign;
    }
}
