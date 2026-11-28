package com.atlas.assistant.model;

public enum ToolCallOutcome {
    EXECUTED,
    /** 허용 목록 밖, 인자 검증 실패, 지출 상한 초과, 사람 확인 없음 같은 정책 거절. 상태를 바꾸지 않는다. */
    REFUSED,
    /** 정책은 통과했지만 기존 서비스가 거절했다(품절, 판매 중지 등). */
    FAILED
}
