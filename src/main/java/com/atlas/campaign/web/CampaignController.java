package com.atlas.campaign.web;

import com.atlas.campaign.CampaignApi;
import com.atlas.campaign.CampaignView;
import com.atlas.campaign.CreateCampaignCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    public record CreateCampaignRequest(
            @NotBlank @Size(max = 128) String skuId,
            @Positive int inventoryCapacity,
            @Positive long queueTicketTtlSeconds,
            @Positive int maxActiveCheckouts) { }

    private final CampaignApi campaigns;

    public CampaignController(CampaignApi campaigns) {
        this.campaigns = campaigns;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignView create(@Valid @RequestBody CreateCampaignRequest request) {
        return campaigns.create(new CreateCampaignCommand(request.skuId(), request.inventoryCapacity(),
                Duration.ofSeconds(request.queueTicketTtlSeconds()), request.maxActiveCheckouts()));
    }

    @PostMapping("/{campaignId}/open")
    public CampaignView open(@PathVariable UUID campaignId) {
        try {
            return campaigns.open(campaignId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/{campaignId}")
    public CampaignView find(@PathVariable UUID campaignId) {
        CampaignView campaign = campaigns.find(campaignId);
        if (campaign == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found");
        return campaign;
    }
}
