package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.SellerCouponResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.SellerCoupon;
import com.atlas.catalog.persistence.entity.SellerCouponRedemption;
import com.atlas.catalog.persistence.state.SellerCouponRedemptionState;
import com.atlas.catalog.repository.SellerCouponRedemptionRepository;
import com.atlas.catalog.repository.SellerCouponRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.service.SellerCouponService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final SellerCouponRedemptionRepository redemptions;

    @Override
    @Transactional
    public SellerCouponResponse create(UUID sellerId, String code, long discountAmountWon,
                                       Instant startsAt, Instant endsAt, Long maxRedemptions) {
        if (sellerId == null || discountAmountWon <= 0 || startsAt == null || endsAt == null
                || !startsAt.isBefore(endsAt) || maxRedemptions != null && maxRedemptions <= 0) {
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
            return response(coupons.saveAndFlush(new SellerCoupon(
                    sellerId, normalizedCode, discountAmountWon, startsAt, endsAt, maxRedemptions)));
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
    @Transactional(readOnly = true)
    public SellerCouponResponse find(UUID sellerId, String code) {
        if (sellerId == null) throw invalidRequest("Seller is required");
        SellerCoupon coupon = coupons.findBySellerIdAndCode(sellerId, normalizeCode(code))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller coupon not found"));
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

    @Override
    @Transactional
    public void reserveRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at) {
        if (sellerId == null || couponId == null || orderId == null || at == null) {
            throw invalidRequest("Coupon redemption facts are required");
        }
        SellerCoupon coupon = findCoupon(sellerId, couponId);
        if (coupon.getMaxRedemptions() == null) return;

        coupon = coupons.findWithLockBySellerIdAndCouponId(sellerId, couponId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller coupon not found"));
        var existing = redemptions.findBySellerIdAndCouponIdAndOrderId(sellerId, couponId, orderId);
        if (existing.isPresent()) {
            SellerCouponRedemptionState state = existing.get().state();
            if (state == SellerCouponRedemptionState.RESERVED || state == SellerCouponRedemptionState.REDEEMED) return;
            throw conflict("Released coupon redemption cannot be reserved again");
        }
        if (coupons.reserveIfCapacity(sellerId, couponId, at) != 1) {
            throw conflict("Seller coupon usage limit is exhausted or the coupon is inactive");
        }
        redemptions.saveAndFlush(new SellerCouponRedemption(
                sellerId, couponId, orderId, at.truncatedTo(ChronoUnit.MICROS)));
    }

    @Override
    @Transactional
    public void redeemRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at) {
        transitionRedemption(sellerId, couponId, orderId, at, SellerCouponRedemptionState.REDEEMED);
    }

    @Override
    @Transactional
    public void releaseRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at) {
        transitionRedemption(sellerId, couponId, orderId, at, SellerCouponRedemptionState.RELEASED);
    }

    private void transitionRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at,
            SellerCouponRedemptionState target) {
        if (sellerId == null || couponId == null || orderId == null || at == null) {
            throw invalidRequest("Coupon redemption facts are required");
        }
        SellerCoupon coupon = findCoupon(sellerId, couponId);
        if (coupon.getMaxRedemptions() == null) return;

        SellerCouponRedemption redemption = redemptions
                .findWithLockBySellerIdAndCouponIdAndOrderId(sellerId, couponId, orderId)
                .orElseThrow(() -> conflict("Limited coupon redemption reservation not found"));
        if (redemption.state() == target) return;
        if (redemption.state() != SellerCouponRedemptionState.RESERVED) {
            throw conflict("Coupon redemption is already in a terminal state");
        }
        int updated = target == SellerCouponRedemptionState.REDEEMED
                ? coupons.redeemReserved(sellerId, couponId)
                : coupons.releaseReserved(sellerId, couponId);
        if (updated != 1) throw conflict("Coupon redemption counters are inconsistent");
        Instant recordedAt = at.truncatedTo(ChronoUnit.MICROS);
        if (target == SellerCouponRedemptionState.REDEEMED) redemption.redeem(recordedAt);
        else redemption.release(recordedAt);
    }

    private SellerCoupon findCoupon(UUID sellerId, UUID couponId) {
        return coupons.findBySellerIdAndCouponId(sellerId, couponId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller coupon not found"));
    }

    private static String normalizeCode(String code) {
        if (code == null) throw invalidRequest("Coupon code is required");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{3,32}")) throw invalidRequest("Invalid coupon code");
        return normalized;
    }

    private static SellerCouponResponse response(SellerCoupon coupon) {
        Long maxRedemptions = coupon.getMaxRedemptions();
        Long reserved = maxRedemptions == null ? null : coupon.getReservedCount();
        Long redeemed = maxRedemptions == null ? null : coupon.getRedeemedCount();
        Long remaining = maxRedemptions == null ? null : Math.subtractExact(maxRedemptions,
                Math.addExact(reserved, redeemed));
        return new SellerCouponResponse(coupon.getCouponId(), coupon.getSellerId(), coupon.getCode(),
                coupon.getDiscountAmountWon(), coupon.getStartsAt(), coupon.getEndsAt(), coupon.isActive(),
                maxRedemptions, reserved, redeemed, remaining);
    }

    private static CatalogException invalidRequest(String message) {
        return new CatalogException(Code.INVALID_REQUEST, message);
    }

    private static CatalogException conflict(String message) {
        return new CatalogException(Code.CONFLICT, message);
    }
}
