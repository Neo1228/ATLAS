-- Recovery finalizer는 reservation-pending checkout을 created_at 순으로 bounded 조회한다.
CREATE INDEX buyer_checkout_recovery_state_created_idx
    ON buyer_checkout (state, created_at, checkout_id);
