package com.atlas.order.service.impl;

import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductService;
import com.atlas.order.dto.response.ProductReviewPage;
import com.atlas.order.dto.response.ProductReviewResponse;
import com.atlas.order.exception.OrderException.Code;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.projection.ProductReview;
import com.atlas.order.persistence.projection.ReviewSummary;
import com.atlas.order.repository.ProductReviewRepository;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.ProductReviewService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ProductReviewServiceImpl implements ProductReviewService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE_OFFSET = 10_000;
    private static final int MAX_BUYER_REF_LENGTH = 256;
    private static final int MAX_BODY_LENGTH = 2_000;

    private final ProductReviewRepository reviews;
    private final ProductService products;
    private final OrderService orders;

    public ProductReviewServiceImpl(ProductReviewRepository reviews, ProductService products, OrderService orders) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.products = Objects.requireNonNull(products, "products");
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    @Override
    public ProductReviewResponse create(String buyerRef, UUID productId, int rating, String body) {
        requireBuyerRef(buyerRef);
        Objects.requireNonNull(productId, "productId");
        if (rating < 1 || rating > 5) {
            throw new OrderException(Code.INVALID_REQUEST, "rating must be between 1 and 5");
        }
        String cleanBody = body == null ? "" : body.trim();
        if (cleanBody.isEmpty() || cleanBody.length() > MAX_BODY_LENGTH) {
            throw new OrderException(Code.INVALID_REQUEST, "review body must contain 1 to 2000 characters");
        }
        UUID orderItemId = orders.findDeliveredOrderItemId(buyerRef, productId)
                .orElseThrow(() -> new OrderException(Code.CONFLICT, "A delivered purchase of this product is required to review it"));
        try {
            return toResponse(reviews.create(buyerRef, productId, orderItemId, rating, cleanBody));
        } catch (DuplicateKeyException exception) {
            throw new OrderException(Code.CONFLICT, "This buyer has already reviewed this product", exception);
        }
    }

    @Override
    public ProductReviewPage list(UUID productId, int requestedLimit, int offset) {
        Objects.requireNonNull(productId, "productId");
        if (!products.isPublicOrderable(productId)) {
            throw new CatalogException(CatalogException.Code.NOT_FOUND, "Unknown product: " + productId);
        }
        int limit = requestedLimit == 0 ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new OrderException(Code.INVALID_REQUEST, "limit must be between 1 and 100");
        }
        if (offset < 0 || offset > MAX_PAGE_OFFSET) {
            throw new OrderException(Code.INVALID_REQUEST, "offset must be between 0 and 10000");
        }
        ReviewSummary summary = reviews.summary(productId);
        List<ProductReview> fetched = reviews.list(productId, limit + 1, offset);
        boolean hasMore = fetched.size() > limit;
        List<ProductReviewResponse> items = fetched.stream().limit(limit)
                .map(ProductReviewServiceImpl::toResponse).toList();
        return new ProductReviewPage(productId, summary.averageRating(), summary.reviewCount(), items, limit, offset,
                hasMore);
    }

    private static ProductReviewResponse toResponse(ProductReview review) {
        return new ProductReviewResponse(review.reviewId(), review.rating(), review.body(), review.createdAt());
    }

    private static void requireBuyerRef(String buyerRef) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > MAX_BUYER_REF_LENGTH) {
            throw new OrderException(Code.INVALID_REQUEST, "Invalid buyerRef");
        }
    }
}
