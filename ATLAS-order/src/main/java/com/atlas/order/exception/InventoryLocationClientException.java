package com.atlas.order.exception;

public final class InventoryLocationClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Failure failure;
    private final String providerCode;

    public InventoryLocationClientException(Failure failure, String providerCode, String message) {
        super(message);
        this.failure = failure;
        this.providerCode = providerCode;
    }

    public Failure failure() {
        return failure;
    }

    public String providerCode() {
        return providerCode;
    }

    public enum Failure {
        DEFINITIVE_REJECTION,
        RETRYABLE_UNKNOWN,
        PERMANENT
    }
}
