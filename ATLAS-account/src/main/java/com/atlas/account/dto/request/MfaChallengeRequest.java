package com.atlas.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MfaChallengeRequest(
        @NotBlank @Size(max = 128) String challengeToken,
        @Pattern(regexp = "^$|^[0-9]{6}$") String code,
        @Size(max = 64) String recoveryCode) {
}
