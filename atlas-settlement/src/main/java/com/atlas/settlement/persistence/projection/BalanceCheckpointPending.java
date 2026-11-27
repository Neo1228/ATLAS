package com.atlas.settlement.persistence.projection;

import java.util.UUID;

/** 앞 마감이 남긴 경계(ADR-0104). {@code settled}면 그 경계 아래 항목을 쓸 수 있는 트랜잭션이 모두 끝났다. */
public record BalanceCheckpointPending(UUID entryId, boolean settled) { }
