-- 원격 해제가 일시 실패해 CANCEL_PENDING으로 남아도 만료 sweep이 요청한 종료 결과(EXPIRED)를 복구 후에 보존한다.
-- NULL은 Buyer 취소(CANCELLED)를 뜻한다. V68 이전의 CANCEL_PENDING row는 목적을 소급 판별할 수 없어 NULL로 둔다.
ALTER TABLE buyer_checkout ADD COLUMN cancellation_target VARCHAR(32);
ALTER TABLE buyer_checkout
    ADD CONSTRAINT buyer_checkout_cancellation_target_valid
        CHECK (cancellation_target IS NULL OR cancellation_target = 'EXPIRED');
