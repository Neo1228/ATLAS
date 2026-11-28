package com.atlas.llm.exception;

public class LlmException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code {
        /** {@code atlas.llm.enabled=false}이거나 필수 설정이 없다. */
        DISABLED,
        /** 호출자가 넘긴 대화 토큰 예산이 남지 않았다. */
        BUDGET_EXHAUSTED,
        /** 응답 시간 초과. 공급자가 처리했을 수 있으므로 재시도하지 않는다. */
        TIMEOUT,
        /** 연결 실패, 429, 5xx를 재시도한 뒤에도 실패했다. */
        UNAVAILABLE,
        /** 4xx 거절이나 해석할 수 없는 응답. */
        INVALID_RESPONSE
    }

    private final Code code;

    public LlmException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public LlmException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
