package com.atlas.campaign.service.impl;

import com.atlas.campaign.service.CampaignService;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.persistence.state.CampaignStatus;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.persistence.entity.Campaign;
import com.atlas.campaign.persistence.repository.CampaignRepository;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignServiceImpl implements CampaignService {

    private final CampaignRepository campaigns;
    private final InventoryService inventory;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public CampaignServiceImpl(CampaignRepository campaigns, InventoryService inventory, Clock clock) {
        this(campaigns, inventory, clock, UUID::randomUUID);
    }

    CampaignServiceImpl(CampaignRepository campaigns, InventoryService inventory, Clock clock, Supplier<UUID> ids) {
        this.campaigns = campaigns;
        this.inventory = inventory;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    @Transactional
    public CampaignResponse create(CreateCampaignRequest request) {
        if (request == null || request.skuId() == null || request.skuId().isBlank()
                || request.skuId().length() > 128 || request.inventoryCapacity() <= 0
                || request.queueTicketTtl() == null || request.queueTicketTtl().isZero()
                || request.queueTicketTtl().isNegative() || request.queueTicketTtl().toSeconds() <= 0
                || request.maxActiveCheckouts() <= 0) {
            throw new CampaignException(CampaignException.Code.INVALID_CONFIGURATION, "Invalid campaign configuration");
        }
        UUID campaignId = ids.get();
        Campaign entity = campaigns.save(new Campaign(campaignId, request.skuId(), CampaignStatus.CLOSED,
                request.queueTicketTtl().toSeconds(), request.maxActiveCheckouts(), clock.instant()));
        inventory.createPool(new CreateInventoryPoolRequest(campaignId, request.skuId(), request.inventoryCapacity()));
        return toResponse(entity);
    }

    @Override
    @Transactional
    public CampaignResponse open(UUID campaignId) {
        Campaign entity = campaigns.findById(campaignId).orElseThrow(() ->
                new CampaignException(CampaignException.Code.NOT_FOUND, "Unknown campaign: " + campaignId));
        entity.setStatus(CampaignStatus.OPEN);
        return toResponse(campaigns.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignResponse requireOpen(UUID campaignId, String skuId) {
        CampaignResponse campaign = find(campaignId);
        if (campaign == null) throw new CampaignException(CampaignException.Code.NOT_FOUND,
                "Unknown campaign: " + campaignId);
        if (campaign.status() != CampaignStatus.OPEN || !campaign.skuId().equals(skuId)) {
            throw new CampaignException(CampaignException.Code.NOT_ELIGIBLE,
                    "Campaign is not open for SKU " + skuId + ": " + campaignId);
        }
        return campaign;
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignResponse find(UUID campaignId) {
        return campaigns.findById(campaignId).map(CampaignServiceImpl::toResponse).orElse(null);
    }

    private static CampaignResponse toResponse(Campaign entity) {
        return new CampaignResponse(entity.getCampaignId(), entity.getSkuId(), entity.getStatus(),
                Duration.ofSeconds(entity.getQueueTicketTtlSeconds()), entity.getMaxActiveCheckouts());
    }
}
