package com.atlas.payment.dto.response;

import com.atlas.payment.model.ProviderAdjustmentKind;
import com.atlas.payment.model.ProviderAdjustmentStatus;
import java.util.UUID;

/** 판매자 원장에 반영할 provider 발 사건. 결제 시도의 주인(order 또는 checkout)을 함께 준다. */
public record PaymentProviderAdjustmentResponse(
        UUID adjustmentId,
        ProviderAdjustmentKind kind,
        ProviderAdjustmentStatus status,
        UUID orderId,
        UUID checkoutId,
        Long amountWon) {
}
