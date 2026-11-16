package com.atlas.account.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountActionToken(UUID tokenId, UUID accountId, String purpose,
        Instant expiresAt, Instant consumedAt) {
}
