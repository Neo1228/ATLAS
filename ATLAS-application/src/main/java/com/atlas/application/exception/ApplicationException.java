package com.atlas.application.exception;

/** application 모듈의 업무 규칙 위반. HTTP 상태는 {@code GlobalExceptionHandler}가 정한다. */
public class ApplicationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public ApplicationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ApplicationException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_REQUEST,
        NOT_FOUND,
        CONFLICT
    }
}
