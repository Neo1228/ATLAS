package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.BuyerWishlistItemResponse;
import com.atlas.catalog.dto.response.BuyerWishlistPage;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.projection.SavedProduct;
import com.atlas.catalog.repository.BuyerWishlistRepository;
import com.atlas.catalog.service.BuyerWishlistService;
import com.atlas.catalog.service.ProductService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BuyerWishlistServiceImpl implements BuyerWishlistService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OFFSET = 10_000;
    private static final int MAX_BUYER_REF_LENGTH = 256;

    private final BuyerWishlistRepository wishlist;
    private final ProductService products;

    public BuyerWishlistServiceImpl(BuyerWishlistRepository wishlist, ProductService products) {
        this.wishlist = Objects.requireNonNull(wishlist, "wishlist");
        this.products = Objects.requireNonNull(products, "products");
    }

    @Override
    public BuyerWishlistItemResponse save(String buyerRef, UUID productId) {
        requireBuyerRef(buyerRef);
        Objects.requireNonNull(productId, "productId");
        if (!products.isPublicOrderable(productId)) {
            throw new CatalogException(Code.NOT_FOUND, "Unknown product: " + productId);
        }
        wishlist.saveIfAbsent(buyerRef, productId);
        return wishlist.find(buyerRef, productId)
                .map(item -> new BuyerWishlistItemResponse(item.productId(), item.savedAt()))
                .orElseThrow(() -> new IllegalStateException("Saved wishlist item was not persisted"));
    }

    @Override
    public BuyerWishlistPage list(String buyerRef, int requestedLimit, int offset) {
        requireBuyerRef(buyerRef);
        int limit = requestedLimit == 0 ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new CatalogException(Code.INVALID_REQUEST, "limit must be between 1 and 100");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new CatalogException(Code.INVALID_REQUEST, "offset must be between 0 and 10000");
        }
        List<SavedProduct> fetched = wishlist.list(buyerRef, limit + 1, offset);
        boolean hasMore = fetched.size() > limit;
        List<BuyerWishlistItemResponse> items = fetched.stream()
                .limit(limit)
                .map(item -> new BuyerWishlistItemResponse(item.productId(), item.savedAt()))
                .toList();
        return new BuyerWishlistPage(items, limit, offset, hasMore);
    }

    @Override
    public void remove(String buyerRef, UUID productId) {
        requireBuyerRef(buyerRef);
        Objects.requireNonNull(productId, "productId");
        wishlist.remove(buyerRef, productId);
    }

    private static void requireBuyerRef(String buyerRef) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > MAX_BUYER_REF_LENGTH) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid buyerRef");
        }
    }
}
