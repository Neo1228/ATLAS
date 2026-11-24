package com.atlas.account.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenSession(UUID tokenId, UUID familyId, UUID accountId,
                                  Instant familyExpiresAt, Instant familyRevokedAt,
                                  Instant expiresAt, Instant consumedAt, Instant revokedAt) {
}
