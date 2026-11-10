package com.atlas.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(@NotBlank @Size(max = 128) String refreshToken) {
}
