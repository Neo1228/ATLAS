package com.atlas.cart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.persistence.entity.Cart;
import com.atlas.cart.repository.CartRepository;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceMergeItemsTest {
    @Mock
    private CartRepository carts;

    @Mock
    private SellerSkuService skus;

    @InjectMocks
    private CartServiceImpl service;

    @Test
    void reorderMergesSellerLinesWithoutDroppingExistingLines() {
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        String buyer = "buyer";
        Cart cart = new Cart(buyer);
        cart.setItemQuantity(sellerA, "kept", 7);
        cart.setItemQuantity(sellerA, "sku", 2);

        when(skus.find(sellerB, "sku"))
                .thenReturn(new SellerSkuResponse(sellerB, product, "sku", "item", 100L));
        when(skus.find(sellerA, "kept"))
                .thenReturn(new SellerSkuResponse(sellerA, UUID.randomUUID(), "kept", "kept", 100L));
        when(skus.find(sellerA, "sku"))
                .thenReturn(new SellerSkuResponse(sellerA, UUID.randomUUID(), "sku", "other", 100L));
        when(carts.findWithLockByBuyerRef(buyer)).thenReturn(Optional.of(cart));

        CartResponse response = service.mergeItems(new ReorderCartRequest(buyer, true, List.of(
                new ReorderCartRequest.Item(sellerB, product, "sku", 3))));

        assertEquals(3, response.items().size());
        assertEquals(7, quantity(response, sellerA, "kept"));
        assertEquals(2, quantity(response, sellerA, "sku"));
        assertEquals(3, quantity(response, sellerB, "sku"));
    }

    private static int quantity(CartResponse response, UUID sellerId, String skuId) {
        return response.items().stream()
                .filter(item -> item.sellerId().equals(sellerId) && item.skuId().equals(skuId))
                .findFirst()
                .orElseThrow()
                .quantity();
    }
}
