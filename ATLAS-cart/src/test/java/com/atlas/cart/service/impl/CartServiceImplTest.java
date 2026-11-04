package com.atlas.cart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
class CartServiceImplTest {
    @Mock
    private CartRepository carts;

    @Mock
    private SellerSkuService sellerSkus;

    @InjectMocks
    private CartServiceImpl service;

    @Test
    void findMissingCartReturnsNotFound() {
        UUID sellerId = UUID.randomUUID();
        String buyerRef = "buyer-1";
        when(carts.findBySellerIdAndBuyerRef(sellerId, buyerRef)).thenReturn(Optional.empty());

        CartException error = assertThrows(CartException.class, () -> service.find(sellerId, buyerRef));

        assertEquals(CartException.Code.NOT_FOUND, error.code());
    }

    @Test
    void setItemQuantityUsesAbsoluteValuesAndIsIdempotent() {
        UUID sellerId = UUID.randomUUID();
        String buyerRef = "buyer-1";
        Cart cart = new Cart(sellerId, buyerRef);
        SellerSkuResponse sku = new SellerSkuResponse(sellerId, UUID.randomUUID(), "sku-1", "Product", 1200L);
        when(carts.findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef)).thenReturn(Optional.of(cart));
        when(sellerSkus.find(sellerId, "sku-1")).thenReturn(sku);
        when(sellerSkus.findAll(sellerId, List.of("sku-1"))).thenReturn(List.of(sku));

        service.setItemQuantity(sellerId, buyerRef, "sku-1", 2);
        CartResponse updated = service.setItemQuantity(sellerId, buyerRef, "sku-1", 5);
        CartResponse repeated = service.setItemQuantity(sellerId, buyerRef, "sku-1", 5);

        assertEquals(1, updated.items().size());
        assertEquals(5, updated.items().getFirst().quantity());
        assertEquals(sellerId, cart.items().getFirst().sellerId());
        assertEquals(updated.items(), repeated.items());
        verify(carts, times(3)).findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef);
    }

    @Test
    void findUsesOneBatchCatalogLookupForAllCartItems() {
        UUID sellerId = UUID.randomUUID();
        String buyerRef = "buyer-1";
        Cart cart = new Cart(sellerId, buyerRef);
        cart.setItemQuantity("sku-1", 2);
        cart.setItemQuantity("sku-2", 3);
        SellerSkuResponse firstSku = new SellerSkuResponse(
                sellerId, UUID.randomUUID(), "sku-1", "Product A", 1200L);
        SellerSkuResponse secondSku = new SellerSkuResponse(
                sellerId, UUID.randomUUID(), "sku-2", "Product B", 1800L);
        when(carts.findBySellerIdAndBuyerRef(sellerId, buyerRef)).thenReturn(Optional.of(cart));
        when(sellerSkus.findAll(sellerId, List.of("sku-1", "sku-2")))
                .thenReturn(List.of(firstSku, secondSku));

        CartResponse response = service.find(sellerId, buyerRef);

        assertEquals(2, response.items().size());
        assertEquals("Product A", response.items().getFirst().productName());
        assertEquals("Product B", response.items().getLast().productName());
        verify(sellerSkus, times(1)).findAll(sellerId, List.of("sku-1", "sku-2"));
    }

    @Test
    void setItemQuantityKeepsDifferentSellersInSeparateCarts() {
        UUID sellerId = UUID.randomUUID();
        UUID otherSellerId = UUID.randomUUID();
        String buyerRef = "buyer-1";
        Cart sellerCart = new Cart(sellerId, buyerRef);
        Cart otherSellerCart = new Cart(otherSellerId, buyerRef);
        SellerSkuResponse sellerSku = new SellerSkuResponse(
                sellerId, UUID.randomUUID(), "sku-1", "Product A", 1200L);
        SellerSkuResponse otherSellerSku = new SellerSkuResponse(
                otherSellerId, UUID.randomUUID(), "sku-1", "Product B", 1500L);
        when(carts.findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef)).thenReturn(Optional.of(sellerCart));
        when(carts.findWithLockBySellerIdAndBuyerRef(otherSellerId, buyerRef))
                .thenReturn(Optional.of(otherSellerCart));
        when(sellerSkus.find(sellerId, "sku-1")).thenReturn(sellerSku);
        when(sellerSkus.find(otherSellerId, "sku-1")).thenReturn(otherSellerSku);
        when(sellerSkus.findAll(sellerId, List.of("sku-1"))).thenReturn(List.of(sellerSku));
        when(sellerSkus.findAll(otherSellerId, List.of("sku-1"))).thenReturn(List.of(otherSellerSku));

        CartResponse sellerResponse = service.setItemQuantity(sellerId, buyerRef, "sku-1", 2);
        CartResponse otherSellerResponse = service.setItemQuantity(otherSellerId, buyerRef, "sku-1", 4);

        assertEquals(sellerId, sellerResponse.sellerId());
        assertEquals(2, sellerResponse.items().getFirst().quantity());
        assertEquals(sellerId, sellerCart.items().getFirst().sellerId());
        assertEquals(otherSellerId, otherSellerResponse.sellerId());
        assertEquals(4, otherSellerResponse.items().getFirst().quantity());
        assertEquals(otherSellerId, otherSellerCart.items().getFirst().sellerId());
    }

    @Test
    void setItemQuantityRejectsNonpositiveQuantity() {
        CartException error = assertThrows(CartException.class,
                () -> service.setItemQuantity(UUID.randomUUID(), "buyer-1", "sku-1", 0));

        assertEquals(CartException.Code.INVALID_REQUEST, error.code());
        verifyNoInteractions(carts, sellerSkus);
    }

    @Test
    void setItemQuantityRejectsUnknownSellerSku() {
        UUID sellerId = UUID.randomUUID();
        when(sellerSkus.find(sellerId, "unknown-sku")).thenReturn(null);

        CartException error = assertThrows(CartException.class,
                () -> service.setItemQuantity(sellerId, "buyer-1", "unknown-sku", 1));

        assertEquals(CartException.Code.SKU_NOT_FOUND, error.code());
        verify(sellerSkus).find(sellerId, "unknown-sku");
        verifyNoInteractions(carts);
    }
}
