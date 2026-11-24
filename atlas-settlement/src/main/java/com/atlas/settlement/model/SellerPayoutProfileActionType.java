package com.atlas.settlement.model;

/**
 * 판매자 지급 프로필 감사 이력({@code seller_payout_profile_action.action})에 남기는 조치 종류.
 *
 * <p>테이블에 action CHECK 제약이 없어 값 집합은 이 조치를 기록하는 코드 전체에서 모았다.
 * 새 프로필 등록은 PROFILE_SUBMITTED, 같은 계정·국가 재확인은 PROFILE_CONFIRMED,
 * 운영자 검증은 PROFILE_VERIFIED 다.
 */
public enum SellerPayoutProfileActionType {
    PROFILE_SUBMITTED,
    PROFILE_CONFIRMED,
    PROFILE_VERIFIED
}
