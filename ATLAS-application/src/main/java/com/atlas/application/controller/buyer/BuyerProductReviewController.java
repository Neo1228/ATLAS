package com.atlas.application.controller.buyer;

import com.atlas.application.controller.dto.CreateProductReviewRequest;
import com.atlas.order.dto.response.ProductReviewPage;
import com.atlas.order.dto.response.ProductReviewResponse;
import com.atlas.order.service.ProductReviewService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BuyerProductReviewController {
    private final ProductReviewService reviews;

    public BuyerProductReviewController(ProductReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/buyers/{buyerRef}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductReviewResponse create(@PathVariable String buyerRef,
            @Valid @RequestBody CreateProductReviewRequest request) {
        return reviews.create(buyerRef, request.productId(), request.rating(), request.body());
    }

    @GetMapping("/products/{productId}/reviews")
    public ProductReviewPage list(@PathVariable UUID productId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return reviews.list(productId, limit, offset);
    }
}
