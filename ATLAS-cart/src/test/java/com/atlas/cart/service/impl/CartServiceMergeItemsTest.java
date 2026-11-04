package com.atlas.cart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.exception.CartException;
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
    private SellerSkuService sellerSkus;

    @InjectMocks
    private CartServiceImpl service;

    @Test
    void confirmsMergeAndPreservesUnrelatedItems() {
        UUID sellerId = UUID.randomUUID();
        String buyerRef = "buyer-1";
        UUID productOne = UUID.randomUUID();
        UUID productTwo = UUID.randomUUID();
        Cart cart = new Cart(sellerId, buyerRef);
        cart.setItemQuantity("sku-1", 2);
        cart.setItemQuantity("sku-unrelated", 7);
        SellerSkuResponse sourceOne = sku(sellerId, productOne, "sku-1");
        SellerSkuResponse sourceTwo = sku(sellerId, productTwo, "sku-2");
        SellerSkuResponse unrelated = sku(sellerId, UUID.randomUUID(), "sku-unrelated");
        when(sellerSkus.findAll(sellerId, List.of("sku-1", "sku-2")))
                .thenReturn(List.of(sourceOne, sourceTwo));
        when(carts.findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef)).thenReturn(Optional.of(cart));
        when(sellerSkus.findAll(sellerId, List.of("sku-1", "sku-unrelated", "sku-2")))
                .thenReturn(List.of(sourceOne, unrelated, sourceTwo));

        CartResponse response = service.mergeItems(new ReorderCartRequest(
                sellerId, buyerRef, true, List.of(
                        new ReorderCartRequest.Item(productOne, "sku-1", 3),
                        new ReorderCartRequest.Item(productTwo, "sku-2", 1))));

        assertEquals(3, response.items().size());
        assertEquals(5, quantity(response, "sku-1"));
        assertEquals(1, quantity(response, "sku-2"));
        assertEquals(7, quantity(response, "sku-unrelated"));
        verify(sellerSkus).findAll(sellerId, List.of("sku-1", "sku-2"));
        verify(carts).findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef);
    }

    @Test
    void requiresConfirmationBeforeChangingNonemptyCart() {
        UUID sellerId = UUID.randomUUID();
        String buyerRef = "buyer-1";
        UUID productId = UUID.randomUUID();
        Cart cart = new Cart(sellerId, buyerRef);
        cart.setItemQuantity("sku-1", 2);
        when(sellerSkus.findAll(sellerId, List.of("sku-1")))
                .thenReturn(List.of(sku(sellerId, productId, "sku-1")));
        when(carts.findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef)).thenReturn(Optional.of(cart));

        CartException error = assertThrows(CartException.class, () -> service.mergeItems(new ReorderCartRequest(
                sellerId, buyerRef, false,
                List.of(new ReorderCartRequest.Item(productId, "sku-1", 3)))));

        assertEquals(CartException.Code.CONFLICT, error.code());
        assertEquals(2, cart.items().getFirst().quantity());
    }

    @Test
    void validatesAllSellerSkusBeforeLockingCart() {
        UUID sellerId = UUID.randomUUID();
        when(sellerSkus.findAll(sellerId, List.of("sku-1", "missing")))
                .thenReturn(List.of(sku(sellerId, UUID.randomUUID(), "sku-1")));

        CartException error = assertThrows(CartException.class, () -> service.mergeItems(new ReorderCartRequest(
                sellerId, "buyer-1", true, List.of(
                        new ReorderCartRequest.Item(UUID.randomUUID(), "sku-1", 1),
                        new ReorderCartRequest.Item(UUID.randomUUID(), "missing", 1)))));

        assertEquals(CartException.Code.SKU_NOT_FOUND, error.code());
        verifyNoInteractions(carts);
    }

    @Test
    void rejectsSkuReassignedToAnotherProductBeforeLockingCart() {
        UUID sellerId = UUID.randomUUID();
        when(sellerSkus.findAll(sellerId, List.of("sku-1")))
                .thenReturn(List.of(sku(sellerId, UUID.randomUUID(), "sku-1")));

        CartException error = assertThrows(CartException.class, () -> service.mergeItems(new ReorderCartRequest(
                sellerId, "buyer-1", true,
                List.of(new ReorderCartRequest.Item(UUID.randomUUID(), "sku-1", 1)))));

        assertEquals(CartException.Code.SKU_NOT_FOUND, error.code());
        verifyNoInteractions(carts);
    }

    private static SellerSkuResponse sku(UUID sellerId, UUID productId, String skuId) {
        return new SellerSkuResponse(sellerId, productId, skuId, "Product " + skuId, 100L);
    }

    private static int quantity(CartResponse response, String skuId) {
        return response.items().stream()
                .filter(item -> item.skuId().equals(skuId))
                .findFirst()
                .orElseThrow()
                .quantity();
    }
}
