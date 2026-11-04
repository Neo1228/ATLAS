package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.persistence.entity.Seller;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.service.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerServiceImpl implements SellerService {
    private final SellerRepository sellers;

    @Override
    @Transactional
    public SellerResponse createSeller() {
        Seller seller = sellers.save(new Seller());
        return new SellerResponse(seller.getSellerId());
    }
}
