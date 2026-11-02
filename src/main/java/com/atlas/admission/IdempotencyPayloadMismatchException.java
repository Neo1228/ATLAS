package com.atlas.admission;

import java.util.UUID;

public final class IdempotencyPayloadMismatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IdempotencyPayloadMismatchException(UUID campaignId, String key) {
        super("Idempotency key was already used with a different payload: " + campaignId + "/" + key);
    }
}
