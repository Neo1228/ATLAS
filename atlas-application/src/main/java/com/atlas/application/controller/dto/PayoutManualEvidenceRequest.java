package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** External bank/provider reference only; never include recipient email or authentication data. */
public record PayoutManualEvidenceRequest(
        @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9가-힣 .,_:/-]+") String evidenceReference) {
}
