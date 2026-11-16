package com.atlas.account.domain;

import java.time.Instant;
import java.util.UUID;

/** 계정 소유의 아직 폐기되지 않은 refresh session family입니다. */
public record ActiveRefreshFamily(UUID familyId, Instant createdAt, Instant expiresAt) {
}
