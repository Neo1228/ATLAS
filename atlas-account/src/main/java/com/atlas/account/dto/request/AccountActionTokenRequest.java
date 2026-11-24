package com.atlas.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountActionTokenRequest(
        @NotBlank @Size(max = 128) String token) {
}
