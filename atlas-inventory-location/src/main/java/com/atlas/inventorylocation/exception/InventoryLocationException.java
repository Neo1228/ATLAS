package com.atlas.inventorylocation.exception;

public final class InventoryLocationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public InventoryLocationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_REQUEST,
        INSUFFICIENT_STOCK,
        IDEMPOTENCY_CONFLICT,
        NOT_FOUND,
        INVALID_STATE,
        RESERVATION_EXPIRED,
        RESERVATION_CANCELLED
    }
}
