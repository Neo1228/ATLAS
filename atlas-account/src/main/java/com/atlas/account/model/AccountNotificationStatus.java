package com.atlas.account.model;

/** 알림 outbox 행 상태. 값 집합은 {@code ck_account_notification_outbox_status} 제약과 같다. */
public enum AccountNotificationStatus {
    /** 보낼 차례를 기다리거나 임대 중이다. */
    PENDING,
    /** 최대 시도 횟수를 넘겼다. 사용자가 다시 요청하면 PENDING으로 돌아간다. */
    FAILED
}
