package com.atlas.inventory.exception;

import java.util.Objects;

public final class InventoryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        UNAVAILABLE,
        CONFLICT,
        NOT_FOUND,
        INVALID_REQUEST
    }

    private final Code code;

    public InventoryException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}