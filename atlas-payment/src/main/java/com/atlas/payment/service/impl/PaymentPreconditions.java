package com.atlas.payment.service.impl;

import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.repository.PaymentAttemptRepository;
import java.util.UUID;

/** 결제 서비스 구현들이 공유하는 오류 생성과 결제 시도 조회. 상태를 갖지 않는다. */
final class PaymentPreconditions {
    private PaymentPreconditions() {
    }

    static PaymentException invalidRequest(String message) {
        return new PaymentException(PaymentException.Code.INVALID_REQUEST, message);
    }

    static PaymentException notFound(String message) {
        return new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND, message);
    }

    static void validateOwnerAndKey(UUID ownerId, String idempotencyKey, String ownerName) {
        if (ownerId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw invalidRequest(ownerName + " and idempotencyKey required");
        }
    }

    static PaymentAttempt requireOrder(PaymentAttemptRepository repository, UUID orderId) {
        return repository.findByOrderId(orderId)
                .orElseThrow(() -> notFound("No payment attempt for order: " + orderId));
    }

    static PaymentAttempt requireCheckout(PaymentAttemptRepository repository, UUID checkoutId) {
        return repository.findByCheckoutId(checkoutId)
                .orElseThrow(() -> notFound("No payment attempt for checkout: " + checkoutId));
    }
}
