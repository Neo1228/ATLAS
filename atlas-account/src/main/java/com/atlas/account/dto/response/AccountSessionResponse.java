package com.atlas.account.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 현재 계정의 refresh family 상태를 토큰 비밀 없이 보여줍니다. */
public record AccountSessionResponse(UUID familyId, Instant createdAt, Instant expiresAt, boolean current) {
}
