package com.atlas.application.service;

import com.atlas.application.dto.response.BuyerWishlistItemResponse;
import com.atlas.application.dto.response.BuyerWishlistPage;
import com.atlas.application.repository.BuyerWishlistRepository;
import com.atlas.catalog.service.ProductService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuyerWishlistService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OFFSET = 10_000;
    private static final int MAX_BUYER_REF_LENGTH = 256;

    private final BuyerWishlistRepository wishlist;
    private final ProductService products;

    public BuyerWishlistService(BuyerWishlistRepository wishlist, ProductService products) {
        this.wishlist = Objects.requireNonNull(wishlist, "wishlist");
        this.products = Objects.requireNonNull(products, "products");
    }

    public BuyerWishlistItemResponse save(String buyerRef, UUID productId) {
        requireBuyerRef(buyerRef);
        Objects.requireNonNull(productId, "productId");
        if (!products.isPublicOrderable(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown product: " + productId);
        }
        wishlist.saveIfAbsent(buyerRef, productId);
        return wishlist.find(buyerRef, productId)
                .map(item -> new BuyerWishlistItemResponse(item.productId(), item.savedAt()))
                .orElseThrow(() -> new IllegalStateException("Saved wishlist item was not persisted"));
    }

    public BuyerWishlistPage list(String buyerRef, int requestedLimit, int offset) {
        requireBuyerRef(buyerRef);
        int limit = requestedLimit == 0 ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be between 0 and 10000");
        }
        List<BuyerWishlistRepository.SavedProduct> fetched = wishlist.list(buyerRef, limit + 1, offset);
        boolean hasMore = fetched.size() > limit;
        List<BuyerWishlistItemResponse> items = fetched.stream()
                .limit(limit)
                .map(item -> new BuyerWishlistItemResponse(item.productId(), item.savedAt()))
                .toList();
        return new BuyerWishlistPage(items, limit, offset, hasMore);
    }

    public void remove(String buyerRef, UUID productId) {
        requireBuyerRef(buyerRef);
        Objects.requireNonNull(productId, "productId");
        wishlist.remove(buyerRef, productId);
    }

    private static void requireBuyerRef(String buyerRef) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > MAX_BUYER_REF_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid buyerRef");
        }
    }
}
