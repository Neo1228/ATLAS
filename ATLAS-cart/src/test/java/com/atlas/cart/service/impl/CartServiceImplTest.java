package com.atlas.cart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.exception.CartException;
import com.atlas.cart.persistence.entity.Cart;
import com.atlas.cart.repository.CartRepository;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {
    @Mock
    private CartRepository carts;

    @Mock
    private SellerSkuService skus;

    @InjectMocks
    private CartServiceImpl service;

    @Test
    void buyerCartContainsDistinctSellerSkuLines() {
        String buyer = "buyer-1";
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        Cart cart = new Cart(buyer);
        when(carts.findWithLockByBuyerRef(buyer)).thenReturn(Optional.of(cart));
        when(skus.find(sellerA, "same")).thenReturn(sku(sellerA, "same"));
        when(skus.find(sellerB, "same")).thenReturn(sku(sellerB, "same"));

        service.setItemQuantity(sellerA, buyer, "same", 2);
        CartResponse result = service.setItemQuantity(sellerB, buyer, "same", 4);

        assertEquals(2, result.items().size());
        assertEquals(sellerA, result.items().get(0).sellerId());
        assertEquals(sellerB, result.items().get(1).sellerId());
        assertEquals(2, result.items().get(0).quantity());
        assertEquals(4, result.items().get(1).quantity());
    }

    @Test
    void rejectsUnknownSellerSku() {
        UUID seller = UUID.randomUUID();
        when(skus.find(seller, "missing")).thenReturn(null);

        CartException error = assertThrows(CartException.class,
                () -> service.setItemQuantity(seller, "buyer-1", "missing", 1));

        assertEquals(CartException.Code.SKU_NOT_FOUND, error.code());
    }

    private static SellerSkuResponse sku(UUID seller, String id) {
        return new SellerSkuResponse(seller, UUID.randomUUID(), id, "item", 100L, null, null, null, 100L);
    }
}
