-- 지급 운영자 조치와 판매자 지급 프로필 조치의 action 값 집합을 DB 제약으로 고정한다.
-- 값은 Java enum PayoutOperatorActionType, SellerPayoutProfileActionType 과 같다. 두 컬럼은 길이 제한만
-- 있었고 값 제약이 없어 코드가 쓰지 않는 문자열도 저장될 수 있었다.
-- 아직 배포하지 않아 기존 행을 정리할 필요가 없으므로 NOT VALID 없이 바로 검증한다.
ALTER TABLE settlement_payout_operator_action
    ADD CONSTRAINT settlement_payout_operator_action_action_check
    CHECK (action IN ('PAYOUT_APPROVED', 'PAYOUT_APPROVAL_REPLAYED', 'PAYOUT_CANCELLED', 'PAYOUT_REQUEUED',
        'MANUAL_PAYOUT_APPROVED', 'MANUAL_PAYOUT_SENT', 'MANUAL_PAYOUT_CONFIRMED', 'MANUAL_PAYOUT_FAILED',
        'MANUAL_NO_TRANSFER_CONFIRMED', 'PROVIDER_REVIEW_PAID', 'PROVIDER_REVIEW_NOT_PAID'));

ALTER TABLE seller_payout_profile_action
    ADD CONSTRAINT seller_payout_profile_action_action_check
    CHECK (action IN ('PROFILE_SUBMITTED', 'PROFILE_CONFIRMED', 'PROFILE_VERIFIED'));
