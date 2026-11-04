package com.atlas.cart.exception;

import java.util.Objects;

public final class CartException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        NOT_FOUND,
        SKU_NOT_FOUND,
        INVALID_REQUEST
    }

    private final Code code;

    public CartException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
