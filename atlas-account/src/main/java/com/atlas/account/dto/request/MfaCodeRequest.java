package com.atlas.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaCodeRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
}
