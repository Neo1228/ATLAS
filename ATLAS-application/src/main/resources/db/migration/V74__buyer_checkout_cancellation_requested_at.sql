-- 반복 취소 재시도는 updated_at을 덮으므로 CANCEL_PENDING 최초 진입 시각을 별도로 보존한다.
ALTER TABLE buyer_checkout ADD COLUMN cancellation_requested_at TIMESTAMPTZ;

-- 기존 pending 취소 행은 최초 시각이 없어 당시의 마지막 갱신 시각을 근사값으로 사용한다.
UPDATE buyer_checkout
SET cancellation_requested_at = updated_at
WHERE state = 'CANCEL_PENDING';

ALTER TABLE buyer_checkout
    ADD CONSTRAINT buyer_checkout_cancellation_timestamp_required
    CHECK (state <> 'CANCEL_PENDING' OR cancellation_requested_at IS NOT NULL);

-- 취소 age gauge에 필요한 CANCEL_PENDING 행만 인덱싱한다.
CREATE INDEX buyer_checkout_cancel_pending_age_idx
    ON buyer_checkout (cancellation_requested_at, checkout_id)
    WHERE state = 'CANCEL_PENDING' AND cancellation_requested_at IS NOT NULL;
