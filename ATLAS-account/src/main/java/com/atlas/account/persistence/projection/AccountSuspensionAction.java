package com.atlas.account.persistence.projection;

import java.time.Instant;

/** 감사 기록 한 건입니다. */
public record AccountSuspensionAction(String action, String idempotencyKey, String actor, String reason,
        Instant createdAt) {
}
