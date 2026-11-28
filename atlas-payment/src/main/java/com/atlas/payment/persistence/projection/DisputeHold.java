package com.atlas.payment.persistence.projection;

import java.util.UUID;

/** 진행 중인 분쟁 한 건과 그 결제 시도의 주인. 주문 하나면 orderId, checkout 이면 checkoutId 다. */
public record DisputeHold(UUID adjustmentId, UUID orderId, UUID checkoutId, long amountWon) {
}
