package com.atlas.settlement.service;

import com.atlas.settlement.model.PayoutOperatorActionType;
import java.time.Instant;
import java.util.UUID;

/** 지급 운영자 조치의 멱등 감사 이력 저장을 계약으로 노출합니다. SQL은 PayoutOperatorActionRepository에만 둔다. */
public interface PayoutOperatorActionStore {

    boolean record(UUID payoutId, String idempotencyKey, PayoutOperatorActionType action, String actor, String reason, Instant now);

    boolean matches(UUID payoutId, String idempotencyKey, PayoutOperatorActionType action, String actor,
            String reason);
}
