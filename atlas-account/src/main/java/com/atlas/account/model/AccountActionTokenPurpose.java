package com.atlas.account.model;

/**
 * 계정 작업 토큰의 목적.
 *
 * <p>값 집합은 {@code ck_account_action_token_purpose} 제약과 같다. 토큰은 발급한 목적으로만 조회되므로
 * 이메일 확인 토큰으로 비밀번호를 재설정할 수 없다.
 */
public enum AccountActionTokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
