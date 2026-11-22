package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Audit reason only; never include credentials or recipient email. */
public record PayoutOperatorActionRequest(
        @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9가-힣 .,/_:-]+") String reason) {
}
