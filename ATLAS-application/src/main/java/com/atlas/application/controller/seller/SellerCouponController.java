package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.CreateSellerCouponRequest;
import com.atlas.catalog.dto.response.SellerCouponResponse;
import com.atlas.catalog.service.SellerCouponService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/coupons")
public class SellerCouponController {
    private final SellerCouponService coupons;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerCouponResponse create(@PathVariable UUID sellerId,
                                       @Valid @RequestBody CreateSellerCouponRequest request) {
        return coupons.create(sellerId, request.code(), request.discountAmountWon(),
                request.startsAt(), request.endsAt(), request.maxRedemptions());
    }

    @GetMapping("/{code}")
    public SellerCouponResponse find(@PathVariable UUID sellerId, @PathVariable String code) {
        return coupons.find(sellerId, code);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID sellerId, @PathVariable String code) {
        coupons.deactivate(sellerId, code);
    }
}
