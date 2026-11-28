package com.atlas.assistant.exception;

public class AssistantException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code {
        INVALID_REQUEST,
        CONVERSATION_NOT_FOUND,
        /** LLM이 꺼져 있다. 검색 API는 LLM 없이 계속 쓸 수 있다. */
        DISABLED,
        /** 대화 토큰 상한에 닿았다. 새 대화를 시작해야 한다. */
        BUDGET_EXHAUSTED,
        /** LLM 공급자 장애나 시간 초과. */
        UNAVAILABLE
    }

    private final Code code;

    public AssistantException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
