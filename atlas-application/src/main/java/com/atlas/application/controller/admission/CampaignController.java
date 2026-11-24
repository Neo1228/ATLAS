package com.atlas.application.controller.admission;

import com.atlas.account.service.AccountService;
import com.atlas.application.controller.dto.CreateCampaignHttpRequest;
import com.atlas.admission.dto.request.CreateCampaignRequest;
import com.atlas.admission.dto.response.CampaignResponse;
import com.atlas.admission.service.CampaignService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private final AccountService accounts;

    public CampaignController(CampaignService campaigns, AccountService accounts) {
        this.campaigns = campaigns;
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCampaignHttpRequest request) {
        UUID accountId = UUID.fromString(jwt.getSubject());
        accounts.requireSellerOwnership(accountId, request.sellerId());
        return campaigns.create(new CreateCampaignRequest(request.sellerId(), request.skuId(),
                Duration.ofSeconds(request.queueTicketTtlSeconds())));
    }

    @PostMapping("/{campaignId}/open")
    public CampaignResponse open(@PathVariable UUID campaignId) {
        return campaigns.open(campaignId);
    }

    @GetMapping("/{campaignId}")
    public CampaignResponse find(@PathVariable UUID campaignId) {
        return campaigns.find(campaignId);
    }
}
