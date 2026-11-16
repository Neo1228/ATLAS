package com.atlas.account.domain;

import java.time.Instant;

public record AccountMfaSecret(String encryptedSecret, Instant enabledAt) {
}
