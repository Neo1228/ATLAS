package com.atlas.settlement.service;

import java.util.Optional;
import java.util.UUID;

/**
 * 지급 송금 보류 판정 계약이다(ADR-0112).
 *
 * <p>송금 worker는 PayPal 배치를 처음 만들기 전에 등록된 판정을 모두 묻고, 하나라도 사유를 돌려주면 보내지 않고
 * outbox를 미룬다. 사유는 outbox의 {@code last_error_code}로 남으므로 {@code [A-Za-z0-9_-]{1,64}} 코드여야 한다.
 * 보류 이유가 새로 생기면(예: 분쟁) 이 계약을 구현하는 빈을 더하고 worker는 고치지 않는다.
 */
public interface PayoutHoldCheck {
    /** 이 Seller에게 지금 송금하면 안 되는 이유. 없으면 비어 있다. */
    Optional<String> holdReason(UUID sellerId);
}
