package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.SetCartItemQuantityHttpRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.service.CartCheckoutService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/buyers/{buyerRef}/cart")
public class CartController {
    private final CartService carts;
    private final CartCheckoutService checkout;

    @GetMapping
    public CartResponse find(@PathVariable String buyerRef) {
        return carts.find(buyerRef);
    }

    @PutMapping("/items/{sellerId}/{skuId}")
    public CartResponse setItemQuantity(@PathVariable String buyerRef,
                                        @PathVariable UUID sellerId,
                                        @PathVariable String skuId,
                                        @Valid @RequestBody SetCartItemQuantityHttpRequest request) {
        return carts.setItemQuantity(sellerId, buyerRef, skuId, request.quantity());
    }

    @DeleteMapping("/items/{sellerId}/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable String buyerRef,
                           @PathVariable UUID sellerId,
                           @PathVariable String skuId) {
        carts.removeItem(sellerId, buyerRef, skuId);
    }

    @DeleteMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearItems(@PathVariable String buyerRef) {
        carts.clearItems(buyerRef);
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(@PathVariable String buyerRef,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return checkout.checkout(buyerRef, idempotencyKey);
    }
}
