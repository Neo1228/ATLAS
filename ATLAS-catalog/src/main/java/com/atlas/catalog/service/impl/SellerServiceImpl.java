package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.persistence.entity.Seller;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.service.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerServiceImpl implements SellerService {
    private final SellerRepository sellers;
    private final ProductCategoryRepository categories;

    @Override
    @Transactional
    public SellerResponse createSeller() {
        Seller seller = sellers.save(new Seller());
        categories.save(new ProductCategory(seller.getSellerId(), ProductCategory.UNCATEGORIZED_CODE,
                ProductCategory.UNCATEGORIZED_NAME));
        return new SellerResponse(seller.getSellerId());
    }
}
