package com.atlas.application.dto.request;

import com.atlas.payment.model.PaymentRefundState;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PaymentHttpRequest {
    private PaymentHttpRequest() {
    }

    public record RecoveryRequeue(@NotBlank @Size(max = 500) String reason) {
    }

    public record WebhookRetryRequeue(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * 운영자가 PayPal에서 확인한 결과 불명 환불의 결과. 증거는 PayPal refund id 또는 "PayPal에 그 환불이 없다"는
     * 명시 표식 중 정확히 하나다. 결과와 증거가 맞는지(완료에는 id, 실패에는 표식)는 payment 모듈이 판단한다.
     */
    public record ExpiredRefundResolution(
            @NotNull PaymentRefundState outcome,
            String providerRefundId,
            Boolean providerRefundNotFound) {
        @AssertTrue
        public boolean isEvidencePresent() {
            return Boolean.TRUE.equals(providerRefundNotFound) == (providerRefundId == null);
        }
    }
}
