package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.SellerCouponResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.SellerCoupon;
import com.atlas.catalog.repository.SellerCouponRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.service.SellerCouponService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerCouponServiceImpl implements SellerCouponService {
    private final SellerCouponRepository coupons;
    private final SellerRepository sellers;

    @Override
    @Transactional
    public SellerCouponResponse create(UUID sellerId, String code, long discountAmountWon,
                                       Instant startsAt, Instant endsAt) {
        if (sellerId == null || discountAmountWon <= 0 || startsAt == null || endsAt == null
                || !startsAt.isBefore(endsAt)) {
            throw invalidRequest("Invalid seller coupon");
        }
        String normalizedCode = normalizeCode(code);
        if (!sellers.existsById(sellerId)) {
            throw new CatalogException(Code.NOT_FOUND, "Seller not found");
        }
        if (coupons.existsBySellerIdAndCode(sellerId, normalizedCode)) {
            throw conflict("Seller coupon code already exists");
        }
        try {
            return response(coupons.saveAndFlush(
                    new SellerCoupon(sellerId, normalizedCode, discountAmountWon, startsAt, endsAt)));
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("Seller coupon code already exists");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SellerCouponResponse findActive(UUID sellerId, String code, Instant at) {
        if (sellerId == null || at == null) throw invalidRequest("Seller and checkout time are required");
        SellerCoupon coupon = coupons.findBySellerIdAndCode(sellerId, normalizeCode(code))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller coupon not found"));
        if (!coupon.isUsableAt(at)) throw conflict("Seller coupon is not active at checkout time");
        return response(coupon);
    }

    @Override
    @Transactional
    public SellerCouponResponse deactivate(UUID sellerId, String code) {
        if (sellerId == null) throw invalidRequest("Seller is required");
        SellerCoupon coupon = coupons.findBySellerIdAndCode(sellerId, normalizeCode(code))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller coupon not found"));
        coupon.deactivate();
        return response(coupon);
    }

    private static String normalizeCode(String code) {
        if (code == null) throw invalidRequest("Coupon code is required");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{3,32}")) throw invalidRequest("Invalid coupon code");
        return normalized;
    }

    private static SellerCouponResponse response(SellerCoupon coupon) {
        return new SellerCouponResponse(coupon.getCouponId(), coupon.getSellerId(), coupon.getCode(),
                coupon.getDiscountAmountWon(), coupon.getStartsAt(), coupon.getEndsAt(), coupon.isActive());
    }

    private static CatalogException invalidRequest(String message) {
        return new CatalogException(Code.INVALID_REQUEST, message);
    }

    private static CatalogException conflict(String message) {
        return new CatalogException(Code.CONFLICT, message);
    }
}
