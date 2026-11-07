package com.atlas.inventory.persistence.projection;

import java.util.Objects;

public record InventoryTransferResult(InventoryTransferRecord transfer, Failure failure) {
    public InventoryTransferResult {
        Objects.requireNonNull(failure, "failure is required");
        if ((failure == Failure.NONE) != (transfer != null)) {
            throw new IllegalArgumentException("successful transfers require a record; failures cannot include one");
        }
    }

    public static InventoryTransferResult success(InventoryTransferRecord transfer) {
        return new InventoryTransferResult(Objects.requireNonNull(transfer), Failure.NONE);
    }

    public static InventoryTransferResult failure(Failure failure) {
        if (failure == Failure.NONE) {
            throw new IllegalArgumentException("failure must describe a non-success outcome");
        }
        return new InventoryTransferResult(null, failure);
    }

    public enum Failure {
        NONE,
        IDEMPOTENCY_CONFLICT,
        SOURCE_UNAVAILABLE,
        DESTINATION_UNAVAILABLE
    }
}
