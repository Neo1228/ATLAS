package com.atlas.application.dto.request;

import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.model.ReturnItemCondition;
import com.atlas.order.model.ReturnReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class OrderHttpRequest {
    private OrderHttpRequest() {
    }

    public record CartCheckout(List<@NotNull @Valid CheckoutSellerCoupon> sellerCoupons) {
    }

    public record CheckoutSellerCoupon(
            @NotNull UUID sellerId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,32}") String code) {
    }

    public record Refund(@NotEmpty List<@Valid Item> items) {
        public record Item(@NotNull UUID orderItemId, @Positive int quantity) {
        }
    }

    /** {@code reason}은 선택이고 {@code note}는 사유가 있을 때만 받는다(ADR-0110). */
    public record Return(@NotEmpty List<@NotNull @Valid Item> items, ReturnReason reason,
            @Size(max = 500) String note) {

        /** 분할 출고 반품은 출처 shipment를 지정하며, 생략은 유일한 출처가 확인될 때만 허용된다. */
        public record Item(@NotNull UUID orderItemId, @Positive int quantity, UUID shipmentId) {
            public Item(UUID orderItemId, int quantity) {
                this(orderItemId, quantity, null);
            }
        }
    }

    public record ReturnInspection(@NotEmpty List<@NotNull @Valid Item> items) {
        public record Item(@NotNull UUID orderItemId, @NotNull OrderRequest.ReturnInspection.Result result) { }
    }

    public record ReturnRecommendation(@NotEmpty List<@NotNull @Valid Item> items) {
        public record Item(@NotNull UUID orderItemId, @NotNull ReturnItemCondition condition) { }
    }

    public record Shipment(String carrierCode, String trackingNumber,
                                           @NotEmpty List<@Valid Item> items, UUID fulfillmentLocationId) {
        public record Item(@NotNull UUID orderItemId, @Positive int quantity) {
        }
    }

    public record Reorder(@NotNull Boolean confirmExistingCartMerge) {
    }

    public record SetCartItemQuantity(@Positive int quantity) {
    }

    /** 배송 완료 구매 상품에 남길 buyer review 요청입니다. */
    public record CreateProductReview(
            @NotNull UUID productId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 2000) String body) {
    }
}
