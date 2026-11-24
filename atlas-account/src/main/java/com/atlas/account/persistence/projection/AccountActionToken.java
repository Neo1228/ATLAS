package com.atlas.account.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record AccountActionToken(UUID tokenId, UUID accountId, String purpose,
        Instant expiresAt, Instant consumedAt) {
}
