package com.atlas.campaign.exception;

import java.util.Objects;

public final class CampaignException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        NOT_FOUND,
        NOT_ELIGIBLE,
        INVALID_CONFIGURATION
    }

    private final Code code;

    public CampaignException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}