package com.atlas.payment.dto.response;

import java.util.UUID;

/**
 * 판매자 송금을 보류해야 하는 진행 중 분쟁(ADR-0115). 판매자는 결제 시도의 주인(order 또는 checkout)으로 주문 계층이 정한다.
 *
 * @param amountWon 보류할 원화. 분쟁 금액을 capture 비율로 올림한 값이며 capture 남은 원화를 넘지 않는다.
 */
public record PaymentDisputeHoldResponse(UUID adjustmentId, UUID orderId, UUID checkoutId, long amountWon) {
}
