package com.atlas.settlement.exception;

public class SettlementException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public SettlementException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_REQUEST,
        CONFLICT
    }
}
