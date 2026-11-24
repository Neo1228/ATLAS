package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.BuyerWishlistResponse;
import java.util.UUID;

/** 구매자가 저장한 상품 목록을 관리한다. */
public interface BuyerWishlistService {
    BuyerWishlistResponse.Item save(String buyerRef, UUID productId);

    BuyerWishlistResponse.Page list(String buyerRef, int requestedLimit, int offset);

    void remove(String buyerRef, UUID productId);
}
