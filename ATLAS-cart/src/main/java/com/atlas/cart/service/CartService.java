package com.atlas.cart.service;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartResponse;
import java.util.UUID;

public interface CartService {
    CartResponse find(String buyerRef);
    CartResponse setItemQuantity(UUID sellerId, String buyerRef, String skuId, int quantity);
    CartResponse mergeItems(ReorderCartRequest request);
    CartResponse removeItem(UUID sellerId, String buyerRef, String skuId);
    void clearItems(String buyerRef);
    CartResponse lockForCheckout(String buyerRef);
    void clearForCheckout(String buyerRef);
}
