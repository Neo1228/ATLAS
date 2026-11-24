package com.atlas.payment.exception;

import java.util.Objects;

public final class PaymentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        ATTEMPT_NOT_FOUND,
        INVALID_REQUEST,
        PAYMENT_PROVIDER_DISABLED,
        PAYMENT_PROVIDER_UNAVAILABLE,
        NOT_FOUND,
        CONFLICT
    }

    private final Code code;

    public PaymentException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public PaymentException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}