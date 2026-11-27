package com.atlas.account.model;

/** 알림 outbox 행 하나를 처리한 결과. 지표 태그 값으로도 쓴다. */
public enum AccountNotificationOutcome {
    /** SMTP가 받았고 행을 지웠다. */
    SENT,
    /** 전송이 실패해 backoff 뒤 다시 시도한다. */
    RETRY_SCHEDULED,
    /** 최대 시도 횟수를 넘겨 FAILED로 남겼다. 사용자가 다시 요청하면 대기로 돌아간다. */
    FAILED,
    /** 계정이 비활성이거나 이미 확인돼 보낼 필요가 없어 지웠다. */
    SKIPPED,
    /** 처리 중에 임대가 끝났거나 사용자가 다시 요청해 이 worker의 기록이 반영되지 않았다. */
    STALE_CLAIM
}
