package com.atlas.admission.service.impl;

import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.exception.CampaignException;
import com.atlas.admission.persistence.entity.Campaign;
import com.atlas.admission.repository.CampaignRepository;
import com.atlas.admission.model.CampaignStatus;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class CampaignServiceImpl implements CampaignService {

    private final CampaignRepository campaigns;
    private final SellerSkuService sellerSkus;
    private final Clock clock;

    @Override
    @Transactional
    public AdmissionResponse.Campaign create(AdmissionRequest.CreateCampaign request) {
        if (request == null || request.sellerId() == null || request.skuId() == null
                || request.skuId().isBlank() || request.skuId().length() > 128
                || request.queueTicketTtl() == null || request.queueTicketTtl().isZero()
                || request.queueTicketTtl().isNegative() || request.queueTicketTtl().toSeconds() <= 0) {
            throw new CampaignException(CampaignException.Code.INVALID_CONFIGURATION,
                    "Invalid campaign configuration");
        }
        if (sellerSkus.findOrderableSellerSku(request.sellerId(), request.skuId(),
                ProductSalesChannel.CAMPAIGN) == null) {
            throw new CampaignException(CampaignException.Code.NOT_FOUND,
                    "Unknown seller SKU: " + request.sellerId() + "/" + request.skuId());
        }

        Campaign entity = campaigns.save(new Campaign(request.sellerId(), request.skuId(),
                CampaignStatus.CLOSED, request.queueTicketTtl().toSeconds(), clock.instant()));
        return toResponse(entity);
    }

    @Override
    @Transactional
    public AdmissionResponse.Campaign open(UUID campaignId) {
        Campaign entity = campaigns.findWithWriteLockByCampaignId(campaignId).orElseThrow(() ->
                new CampaignException(CampaignException.Code.NOT_FOUND, "Unknown campaign: " + campaignId));
        if (sellerSkus.findOrderableSellerSku(entity.getSellerId(), entity.getSkuId(),
                ProductSalesChannel.CAMPAIGN) == null) {
            throw new CampaignException(CampaignException.Code.NOT_FOUND,
                    "Unknown seller SKU: " + entity.getSellerId() + "/" + entity.getSkuId());
        }
        entity.setStatus(CampaignStatus.OPEN);
        return toResponse(campaigns.save(entity));
    }

    @Override
    @Transactional
    public AdmissionResponse.Campaign close(UUID sellerId, UUID campaignId) {
        if (sellerId == null || campaignId == null) {
            throw new CampaignException(CampaignException.Code.INVALID_CONFIGURATION,
                    "Seller and campaign are required");
        }
        Campaign entity = campaigns.findWithWriteLockByCampaignId(campaignId).orElseThrow(() ->
                new CampaignException(CampaignException.Code.NOT_FOUND, "Unknown campaign: " + campaignId));
        if (!sellerId.equals(entity.getSellerId())) {
            throw new CampaignException(CampaignException.Code.NOT_FOUND, "Unknown campaign: " + campaignId);
        }
        if (entity.getStatus() == CampaignStatus.CLOSED) {
            return toResponse(entity);
        }
        entity.setStatus(CampaignStatus.CLOSED);
        return toResponse(campaigns.save(entity));
    }

    /** Admission join transaction이 끝날 때까지 campaign row의 read lock을 유지합니다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AdmissionResponse.Campaign> findOpenForAdmission(UUID campaignId, String skuId) {
        Campaign campaign = campaigns.findForAdmission(campaignId).orElseThrow(() ->
                new CampaignException(CampaignException.Code.NOT_FOUND, "Unknown campaign: " + campaignId));
        if (campaign.getStatus() != CampaignStatus.OPEN || !campaign.getSkuId().equals(skuId)) {
            return Optional.empty();
        }
        return Optional.of(toResponse(campaign));
    }

    @Override
    @Transactional(readOnly = true)
    public AdmissionResponse.Campaign find(UUID campaignId) {
        return campaigns.findById(campaignId).map(CampaignServiceImpl::toResponse)
                .orElseThrow(() -> new CampaignException(CampaignException.Code.NOT_FOUND,
                        "Unknown campaign: " + campaignId));
    }

    private static AdmissionResponse.Campaign toResponse(Campaign entity) {
        return new AdmissionResponse.Campaign(entity.getCampaignId(), entity.getSellerId(), entity.getSkuId(),
                entity.getStatus(), Duration.ofSeconds(entity.getQueueTicketTtlSeconds()));
    }
}
