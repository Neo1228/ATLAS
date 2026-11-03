package com.atlas.campaign.service.impl;

import com.atlas.campaign.dto.response.SellerResponse;
import com.atlas.campaign.persistence.entity.Seller;
import com.atlas.campaign.persistence.repository.SellerRepository;
import com.atlas.campaign.service.SellerService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerServiceImpl implements SellerService {

    private final SellerRepository sellers;

    public SellerServiceImpl(SellerRepository sellers) {
        this.sellers = sellers;
    }

    @Override
    @Transactional
    public SellerResponse create() {
        Seller seller = sellers.save(new Seller(UUID.randomUUID()));
        return new SellerResponse(seller.getSellerId());
    }
}
