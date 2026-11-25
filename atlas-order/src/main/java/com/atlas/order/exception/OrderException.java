package com.atlas.order.exception;

import java.util.Objects;

public final class OrderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        NOT_FOUND,
        SHIPMENT_NOT_FOUND,
        CONFLICT,
        INVALID_REQUEST,
        RETURN_WINDOW_EXPIRED,
        INVENTORY_UNAVAILABLE
    }

    private final Code code;

    public OrderException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public OrderException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}