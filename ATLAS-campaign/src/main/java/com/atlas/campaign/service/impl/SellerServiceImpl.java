package com.atlas.campaign.service.impl;

import com.atlas.campaign.dto.response.SellerResponse;
import com.atlas.campaign.persistence.entity.Seller;
import com.atlas.campaign.repository.SellerRepository;
import com.atlas.campaign.service.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class SellerServiceImpl implements SellerService {

    private final SellerRepository sellers;

    @Override
    @Transactional
    public SellerResponse create() {
        Seller seller = sellers.save(new Seller());
        return new SellerResponse(seller.getSellerId());
    }
}
