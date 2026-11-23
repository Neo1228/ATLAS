package com.atlas.account.persistence.projection;

import java.time.Instant;

public record AccountMfaSecret(String encryptedSecret, Instant enabledAt) {
}
