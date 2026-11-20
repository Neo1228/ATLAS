-- 정산 원장을 3계정 구조로 확장하고 지급 실행을 별도 상태 기계로 관리한다.
--
-- 배경: 기존 원장은 PSP_CLEARING/SELLER_PAYABLE 두 계정만 기록해 gross 만 표현했다.
-- 수수료·보류금·순지급액·지급 실행이 없어(ADR-0011 미결, Q-006) 판매자는 "실제로 언제
-- 얼마를 받는가"를 알 수 없었다.
--
-- 설계 원칙:
-- - 수수료·보류 정책은 capture 시점 값을 journal 에 스냅샷한다(환율과 같은 원칙).
--   정책이 바뀌어도 과거 분개는 재계산하지 않는다.
-- - 지급은 journal 이 아니라 별도 상태 기계(settlement_payout)로 관리한다.
--   지급 실행 자체가 하나의 분개(SELLER_PAYABLE 차변 / PSP_CLEARING 대변)를 만든다.
-- - 모든 분개는 복식부기 항등식(차변 합 = 대변 합)을 만족한다.

-- 1) capture 시점 정책 스냅샷. gross 는 기존 컬럼을 그대로 쓰고, 분해된 금액만 추가한다.
ALTER TABLE settlement_journal
    ADD COLUMN fee_amount_won BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserve_amount_won BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN fee_rate_bps INT NOT NULL DEFAULT 0,
    ADD COLUMN reserve_rate_bps INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT settlement_journal_fee_nonnegative CHECK (fee_amount_won >= 0),
    ADD CONSTRAINT settlement_journal_reserve_nonnegative CHECK (reserve_amount_won >= 0),
    ADD CONSTRAINT settlement_journal_rates_valid CHECK (
        fee_rate_bps BETWEEN 0 AND 10000 AND reserve_rate_bps BETWEEN 0 AND 10000),
    -- 수수료 + 보류는 gross 를 넘을 수 없다. 넘으면 판매자 몫이 음수가 된다.
    ADD CONSTRAINT settlement_journal_split_within_gross CHECK (
        fee_amount_won + reserve_amount_won <= gross_amount_won);

-- 2) 수수료·보류는 seller 단위로 계산된다. checkout 은 한 journal 에 여러 seller 가
--    allocation 되므로, entry 에 seller 를 붙여야 seller 별 수수료를 분개할 수 있다.
--    기존 capture 는 journal.seller_id 와 같은 값을 채운다.
-- FK 를 걸지 않는다. 정산 원장은 결제·주문 사실을 기록하는 회계 장부이고, 기존 capture/checkout
-- 테스트와 운영 데이터가 seller 행 존재에 의존하지 않는다. seller_id 는 조회 키일 뿐
-- 참조 무결성의 대상이 아니다(주문·결제 이력이 이미 seller 를 참조한다).
ALTER TABLE settlement_entry
    ADD COLUMN seller_id UUID;

UPDATE settlement_entry AS entry
SET seller_id = journal.seller_id
FROM settlement_journal AS journal
WHERE entry.journal_id = journal.journal_id
  AND journal.seller_id IS NOT NULL;

-- checkout journal 은 seller_id 가 NULL 이고 seller 별 금액은 settlement_seller_allocation 에
-- 있다. 이 경로를 보완하지 않으면 과거 checkout 의 payable 이 seller 잔액에서 보이지 않아,
-- statement 흐름(gross)과 지급 가능 잔액이 어긋난다. allocation 의 seller 로 entry 를 채운다.
-- 한 journal 에 여러 seller 가 있으므로, account_code 별로 seller 수만큼 entry 를 만든다.
INSERT INTO settlement_entry (journal_id, seller_id, account_code, entry_side, amount_won, recorded_at)
SELECT journal.journal_id, allocation.seller_id, 'SELLER_PAYABLE', 'CREDIT',
       allocation.amount_won, journal.recorded_at
FROM settlement_journal AS journal
JOIN settlement_seller_allocation AS allocation ON allocation.journal_id = journal.journal_id
WHERE journal.checkout_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM settlement_entry AS existing
      WHERE existing.journal_id = journal.journal_id
        AND existing.seller_id = allocation.seller_id
        AND existing.account_code = 'SELLER_PAYABLE'
  );

-- checkout 의 PSP_CLEARING 은 이미 seller 없는 단일 entry 로 존재한다(전체 gross). 그대로 둔다.

-- 새 계정(PLATFORM_FEE, SELLER_RESERVE)과 지급·보류 해제 방향을 허용한다.
ALTER TABLE settlement_entry DROP CONSTRAINT settlement_entry_account_side;
ALTER TABLE settlement_entry
    ADD CONSTRAINT settlement_entry_account_side CHECK (
        -- capture: 자산 증가 / 부채·수익 증가
        (account_code = 'PSP_CLEARING' AND entry_side IN ('DEBIT', 'CREDIT'))
        OR (account_code = 'SELLER_PAYABLE' AND entry_side IN ('DEBIT', 'CREDIT'))
        OR (account_code = 'PLATFORM_FEE' AND entry_side = 'CREDIT')
        OR (account_code = 'SELLER_RESERVE' AND entry_side IN ('DEBIT', 'CREDIT'))
    );

-- 2b) 계정별 유니크를 seller 단위로 확장한다. checkout 처럼 한 journal 에 여러 seller 가
--     있을 때 seller 당 PSP_CLEARING/SELLER_PAYABLE entry 가 하나씩 필요하다.
DROP INDEX settlement_entry_clearing_once;
DROP INDEX settlement_entry_payable_once;
CREATE UNIQUE INDEX settlement_entry_account_seller_once
    ON settlement_entry (journal_id, account_code, COALESCE(seller_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- 3) 지급 실행. 기간 중복 지급을 DB 제약으로 막는다.
CREATE TABLE settlement_payout (
    payout_id UUID PRIMARY KEY DEFAULT uuidv7(),
    seller_id UUID NOT NULL REFERENCES seller (seller_id),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    amount_won BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_payout_period_valid CHECK (period_end > period_start),
    CONSTRAINT settlement_payout_amount_nonnegative CHECK (amount_won >= 0),
    CONSTRAINT settlement_payout_state_valid CHECK (
        state IN ('REQUESTED', 'APPROVED', 'SENT', 'CONFIRMED', 'FAILED')),
    -- 지급은 음수 잔액을 상계할 수 있으므로 0 을 허용하되 초과 지급은 허용하지 않는다.
    CONSTRAINT settlement_payout_key_nonblank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT settlement_payout_seller_period_unique UNIQUE (seller_id, period_start, period_end),
    CONSTRAINT settlement_payout_key_unique UNIQUE (idempotency_key)
);

CREATE INDEX ix_settlement_payout_seller_state
    ON settlement_payout (seller_id, state, period_start DESC);

-- 지급이 만든 분개를 payout 과 연결한다. 1 payout = 1 journal.
ALTER TABLE settlement_journal
    ADD COLUMN source_payout_id UUID REFERENCES settlement_payout (payout_id);
CREATE UNIQUE INDEX settlement_journal_payout_unique
    ON settlement_journal (source_payout_id) WHERE source_payout_id IS NOT NULL;

-- 4) payout journal 은 order/checkout/refund 가 아니라 payout 을 source 로 갖는다.
--     owner_xor 제약에 payout 분기를 추가한다.
ALTER TABLE settlement_journal DROP CONSTRAINT settlement_journal_owner_xor;
ALTER TABLE settlement_journal ADD CONSTRAINT settlement_journal_owner_xor CHECK (
    (source_order_id IS NOT NULL AND checkout_id IS NULL AND source_refund_id IS NULL
        AND source_attempt_id IS NOT NULL AND source_payout_id IS NULL)
    OR (source_order_id IS NULL AND checkout_id IS NOT NULL AND source_refund_id IS NULL
        AND source_attempt_id IS NOT NULL AND source_payout_id IS NULL)
    OR (source_order_id IS NOT NULL AND checkout_id IS NULL AND source_refund_id IS NOT NULL
        AND source_attempt_id IS NULL AND source_payout_id IS NULL)
    -- 지급 분개: seller 와 금액만 있고 owner 3종은 비어 있다.
    OR (source_order_id IS NULL AND checkout_id IS NULL AND source_refund_id IS NULL
        AND source_attempt_id IS NULL AND source_payout_id IS NOT NULL AND seller_id IS NOT NULL)
);

-- 4b) payout journal 은 capture 스냅샷 컬럼(fee/reserve/rate)을 쓰지 않는다. 기본값 0 이 그대로
--     남아 "수수료 없는 지급 분개"가 되고, gross_amount_won > 0 제약과 KRW 제약은 그대로 적용된다.
--     지급 금액은 서비스가 원장 잔액에서 계산하므로 0원 지급 journal 은 생기지 않는다.
