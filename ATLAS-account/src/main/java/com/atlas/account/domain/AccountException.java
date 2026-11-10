package com.atlas.account.domain;

public final class AccountException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_CREDENTIALS,
        EMAIL_ALREADY_USED,
        INVALID_REFRESH_TOKEN,
        REFRESH_TOKEN_REUSED,
        ACCOUNT_DISABLED,
        RESOURCE_NOT_OWNED,
        INVALID_REQUEST
    }

    private final Code code;

    public AccountException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
