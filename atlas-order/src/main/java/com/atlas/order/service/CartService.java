package com.atlas.order.service;

import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.CartResponse;
import java.util.UUID;

public interface CartService {
    CartResponse find(String buyerRef);
    CartResponse setItemQuantity(UUID sellerId, String buyerRef, String skuId, int quantity);
    CartResponse mergeItems(OrderRequest.ReorderCart request);
    CartResponse removeItem(UUID sellerId, String buyerRef, String skuId);
    void clearItems(String buyerRef);
    CartResponse lockForCheckout(String buyerRef);
    void clearForCheckout(String buyerRef);
}
