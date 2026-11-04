package com.atlas.cart.service;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartResponse;
import java.util.UUID;

public interface CartService {
    CartResponse find(UUID sellerId, String buyerRef);

    CartResponse setItemQuantity(UUID sellerId, String buyerRef, String skuId, int quantity);

    CartResponse mergeItems(ReorderCartRequest request);

    CartResponse removeItem(UUID sellerId, String buyerRef, String skuId);

    CartResponse lockForCheckout(UUID sellerId, String buyerRef);

    void clearForCheckout(UUID sellerId, String buyerRef);
}
