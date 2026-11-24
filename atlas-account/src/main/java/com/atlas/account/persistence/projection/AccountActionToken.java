package com.atlas.account.persistence.projection;

import com.atlas.account.model.AccountActionTokenPurpose;
import java.time.Instant;
import java.util.UUID;

public record AccountActionToken(UUID tokenId, UUID accountId, AccountActionTokenPurpose purpose,
        Instant expiresAt, Instant consumedAt) {
}
