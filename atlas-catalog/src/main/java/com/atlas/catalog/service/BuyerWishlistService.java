package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.BuyerWishlistItemResponse;
import com.atlas.catalog.dto.response.BuyerWishlistPage;
import java.util.UUID;

/** 구매자가 저장한 상품 목록을 관리한다. */
public interface BuyerWishlistService {
    BuyerWishlistItemResponse save(String buyerRef, UUID productId);

    BuyerWishlistPage list(String buyerRef, int requestedLimit, int offset);

    void remove(String buyerRef, UUID productId);
}
