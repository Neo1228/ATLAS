package com.atlas.assistant.service.impl;

/** 정책 거절. 상태를 바꾸기 전에 던지고, 사유 코드는 모델과 구매자 응답에 그대로 쓴다. */
final class ToolRefusal extends RuntimeException {
    private static final long serialVersionUID = 1L;
    ToolRefusal(String reason) {
        super(reason, null, false, false);
    }

    String reason() {
        return getMessage();
    }
}
