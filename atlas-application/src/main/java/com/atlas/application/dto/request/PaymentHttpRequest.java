package com.atlas.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PaymentHttpRequest {
    private PaymentHttpRequest() {
    }

    public record RecoveryRequeue(@NotBlank @Size(max = 500) String reason) {
    }

    public record WebhookRetryRequeue(@NotBlank @Size(max = 500) String reason) {
    }
}
