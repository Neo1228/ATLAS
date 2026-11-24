package com.atlas.application.controller.order;

import com.atlas.application.dto.request.OrderHttpRequest;
import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.dto.response.CartResponse;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import jakarta.validation.Valid;
import java.util.List;
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
                                        @Valid @RequestBody OrderHttpRequest.SetCartItemQuantity request) {
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
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody(required = false) OrderHttpRequest.CartCheckout request) {
        List<SellerCouponRequest> coupons = request == null || request.sellerCoupons() == null
                ? List.of()
                : request.sellerCoupons().stream()
                        .map(item -> new SellerCouponRequest(item.sellerId(), item.code())).toList();
        return checkout.checkout(buyerRef, idempotencyKey, coupons);
    }
}
