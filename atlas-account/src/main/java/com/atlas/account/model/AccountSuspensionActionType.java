package com.atlas.account.model;

/**
 * 운영자의 계정 정지 조치 종류.
 *
 * <p>값 집합은 {@code account_suspension_action_action_check} 제약과 같다. SUSPEND 는 계정을 DISABLED 로,
 * RELEASE 는 ACTIVE 로 되돌린다.
 */
public enum AccountSuspensionActionType {
    SUSPEND,
    RELEASE
}
