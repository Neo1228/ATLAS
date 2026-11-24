package com.atlas.account.persistence.projection;

import com.atlas.account.model.AccountSuspensionActionType;
import java.time.Instant;

/** 감사 기록 한 건입니다. */
public record AccountSuspensionAction(AccountSuspensionActionType action, String idempotencyKey, String actor,
        String reason, Instant createdAt) {
}
