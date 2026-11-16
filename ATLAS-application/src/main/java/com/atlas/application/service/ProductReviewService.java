package com.atlas.application.service;

import com.atlas.application.dto.response.ProductReviewPage;
import com.atlas.application.dto.response.ProductReviewResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.order.service.OrderService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductReviewService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE_OFFSET = 10_000;
    private static final int MAX_BUYER_REF_LENGTH = 256;
    private static final int MAX_BODY_LENGTH = 2_000;

    private final ProductReviewRepository reviews;
    private final ProductService products;
    private final OrderService orders;

    public ProductReviewService(ProductReviewRepository reviews, ProductService products, OrderService orders) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.products = Objects.requireNonNull(products, "products");
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    public ProductReviewResponse create(String buyerRef, UUID productId, int rating, String body) {
        requireBuyerRef(buyerRef);
        Objects.requireNonNull(productId, "productId");
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be between 1 and 5");
        }
        String cleanBody = body == null ? "" : body.trim();
        if (cleanBody.isEmpty() || cleanBody.length() > MAX_BODY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review body must contain 1 to 2000 characters");
        }
        UUID orderItemId = orders.findDeliveredOrderItemId(buyerRef, productId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "A delivered purchase of this product is required to review it"));
        try {
            return reviews.create(buyerRef, productId, orderItemId, rating, cleanBody);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This buyer has already reviewed this product", exception);
        }
    }

    public ProductReviewPage list(UUID productId, int requestedLimit, int offset) {
        Objects.requireNonNull(productId, "productId");
        products.findProduct(productId);
        int limit = requestedLimit == 0 ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        if (offset < 0 || offset > MAX_PAGE_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be between 0 and 10000");
        }
        ProductReviewRepository.ReviewSummary summary = reviews.summary(productId);
        List<ProductReviewResponse> fetched = reviews.list(productId, limit + 1, offset);
        boolean hasMore = fetched.size() > limit;
        List<ProductReviewResponse> items = fetched.stream().limit(limit).toList();
        return new ProductReviewPage(productId, summary.averageRating(), summary.reviewCount(), items, limit, offset,
                hasMore);
    }

    private static void requireBuyerRef(String buyerRef) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > MAX_BUYER_REF_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid buyerRef");
        }
    }
}
