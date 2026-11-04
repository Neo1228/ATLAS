package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.SetCartItemQuantityHttpRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.service.CartCheckoutService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/carts/{buyerRef}")
public class CartController {

    private final CartService carts;
    private final CartCheckoutService checkout;

    @GetMapping
    public CartResponse find(@PathVariable UUID sellerId, @PathVariable String buyerRef) {
        return carts.find(sellerId, buyerRef);
    }

    @PutMapping("/items/{skuId}")
    public CartResponse setItemQuantity(@PathVariable UUID sellerId, @PathVariable String buyerRef,
            @PathVariable String skuId, @Valid @RequestBody SetCartItemQuantityHttpRequest request) {
        return carts.setItemQuantity(sellerId, buyerRef, skuId, request.quantity());
    }

    @DeleteMapping("/items/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable UUID sellerId, @PathVariable String buyerRef,
            @PathVariable String skuId) {
        carts.removeItem(sellerId, buyerRef, skuId);
    }

    @PostMapping("/checkout")
    public OrderResponse checkout(@PathVariable UUID sellerId, @PathVariable String buyerRef,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return checkout.checkout(sellerId, buyerRef, idempotencyKey);
    }
}
