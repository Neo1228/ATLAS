package com.atlas.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentRecoveryRequeueRequest(
        @NotBlank @Size(max = 500) String reason) {
}
