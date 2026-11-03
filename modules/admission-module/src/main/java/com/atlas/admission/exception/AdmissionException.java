package com.atlas.admission.exception;

import java.util.Objects;

public final class AdmissionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        TICKET_NOT_FOUND,
        CHECKOUT_SLOT_UNAVAILABLE,
        IDEMPOTENCY_PAYLOAD_MISMATCH,
        INVALID_REQUEST
    }

    private final Code code;

    public AdmissionException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}