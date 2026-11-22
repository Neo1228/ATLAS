package com.atlas.application.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifySellerPayoutProfileRequest(
        @Min(1) int profileVersion,
        @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,499}") String evidenceReference) {
}