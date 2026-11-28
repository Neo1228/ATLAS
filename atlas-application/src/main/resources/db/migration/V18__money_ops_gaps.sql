-- 분쟁 보류와 판매자 패소 차감(ADR-0115).
-- 진행 중인 분쟁은 HOLD 로 두고(판매자 송금 보류의 근거), 판매자 차감 없이 끝나면 CLOSED 로 둔다.
-- 판매자 패소로 끝난 분쟁은 같은 행이 POSTING -> POSTED 로 가며 기존 환불 분개(source_refund_id = adjustment_id)를 쓴다.
ALTER TABLE payment_provider_adjustment DROP CONSTRAINT payment_provider_adjustment_status_check;
ALTER TABLE payment_provider_adjustment ADD CONSTRAINT payment_provider_adjustment_status_check
    CHECK (status IN ('POSTING', 'POSTED', 'REVIEW', 'HOLD', 'CLOSED'));

-- 원장에 넣는 행은 금전 사건(환불·역전·패소한 분쟁)뿐이고 금액·결제 시도가 확정돼 있어야 한다.
-- (NOT IN 으로 쓴다: EnumCheckConstraintContractTest 는 "= ANY" 와 "= '값'" 만 값 집합으로 읽는다.)
ALTER TABLE payment_provider_adjustment DROP CONSTRAINT payment_provider_adjustment_posting_facts;
ALTER TABLE payment_provider_adjustment ADD CONSTRAINT payment_provider_adjustment_posting_facts CHECK (
    status NOT IN ('POSTING', 'POSTED')
    OR (kind NOT IN ('DENIAL') AND attempt_id IS NOT NULL AND amount_won > 0
        AND provider_amount_minor > 0 AND provider_currency IS NOT NULL));

-- 보류와 무차감 종료는 분쟁에만 있다(위와 같은 이유로 NOT IN). 보류 행은 어느 결제 시도(곧 판매자)를 막는지와 보류 원화를 가진다.
ALTER TABLE payment_provider_adjustment ADD CONSTRAINT payment_provider_adjustment_dispute_only_states CHECK (
    status NOT IN ('HOLD', 'CLOSED') OR kind NOT IN ('REFUND', 'REVERSAL', 'DENIAL'));
ALTER TABLE payment_provider_adjustment ADD CONSTRAINT payment_provider_adjustment_hold_facts CHECK (
    status NOT IN ('HOLD') OR (attempt_id IS NOT NULL AND amount_won > 0));

-- 송금 worker 가 지급마다 진행 중 분쟁을 찾는다.
CREATE INDEX payment_provider_adjustment_dispute_hold
    ON payment_provider_adjustment (attempt_id) WHERE status = 'HOLD';

-- V16 이 검토로만 남긴 분쟁 중 우리 결제 시도에 묶이고 아직 끝나지 않은 것은 보류로 옮긴다.
-- 보류 원화는 결제 시도 전체(분쟁 금액 환산을 다시 하지 않는 보수적인 값)로 둔다.
UPDATE payment_provider_adjustment adjustment
SET status = 'HOLD', review_reason = NULL, amount_won = attempt.total_won, updated_at = now()
FROM payment_attempt attempt
WHERE adjustment.kind = 'DISPUTE' AND adjustment.status = 'REVIEW'
  AND adjustment.attempt_id = attempt.attempt_id AND attempt.total_won > 0
  AND COALESCE(adjustment.provider_status, '') <> 'RESOLVED';
