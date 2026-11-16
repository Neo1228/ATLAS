package com.atlas.account.dto.response;

public record MfaEnrollmentResponse(String secret, String otpauthUri) {
}
