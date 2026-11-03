package com.atlas.campaign.service.impl;

import com.atlas.campaign.dto.request.RegisterSellerSkuRequest;
import com.atlas.campaign.dto.response.SellerSkuResponse;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.persistence.entity.SellerSku;
import com.atlas.campaign.repository.SellerRepository;
import com.atlas.campaign.repository.SellerSkuRepository;
import com.atlas.campaign.service.SellerSkuService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerSkuServiceImpl implements SellerSkuService {

    private final SellerRepository sellers;
    private final SellerSkuRepository sellerSkus;
    private final InventoryService inventory;
    private final Clock clock;

    public SellerSkuServiceImpl(SellerRepository sellers, SellerSkuRepository sellerSkus,
            InventoryService inventory, Clock clock) {
        this.sellers = sellers;
        this.sellerSkus = sellerSkus;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SellerSkuResponse register(UUID sellerId, RegisterSellerSkuRequest request) {
        if (sellerId == null || request == null || request.skuId() == null || request.skuId().isBlank()
                || request.skuId().length() > 128 || request.initialStock() <= 0
                || request.maxActiveCheckouts() <= 0) {
            throw new CampaignException(CampaignException.Code.INVALID_CONFIGURATION,
                    "Invalid seller SKU configuration");
        }
        if (!sellers.existsById(sellerId)) {
            throw new CampaignException(CampaignException.Code.NOT_FOUND, "Unknown seller: " + sellerId);
        }
        if (sellerSkus.findBySellerIdAndSkuId(sellerId, request.skuId()).isPresent()) {
            throw new CampaignException(CampaignException.Code.INVALID_CONFIGURATION,
                    "Seller SKU is already registered");
        }

        SellerSku sellerSku = sellerSkus.save(new SellerSku(sellerId, request.skuId(),
                request.maxActiveCheckouts(), clock.instant()));
        inventory.createInventoryPool(new CreateInventoryPoolRequest(sellerId, request.skuId(), request.initialStock()));
        return toResponse(sellerSku);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerSkuResponse find(UUID sellerId, String skuId) {
        if (sellerId == null || skuId == null) {
            return null;
        }
        return sellerSkus.findBySellerIdAndSkuId(sellerId, skuId).map(SellerSkuServiceImpl::toResponse).orElse(null);
    }

    private static SellerSkuResponse toResponse(SellerSku sellerSku) {
        return new SellerSkuResponse(sellerSku.getSellerId(), sellerSku.getSkuId(), sellerSku.getMaxActiveCheckouts());
    }
}
