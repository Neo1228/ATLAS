package com.atlas.order.service;

import com.atlas.order.dto.response.ProductReviewPage;
import com.atlas.order.dto.response.ProductReviewResponse;
import java.util.UUID;

/** 배송 완료한 구매자가 남기는 상품 리뷰를 관리한다. */
public interface ProductReviewService {
    ProductReviewResponse create(String buyerRef, UUID productId, int rating, String body);

    ProductReviewPage list(UUID productId, int requestedLimit, int offset);
}
