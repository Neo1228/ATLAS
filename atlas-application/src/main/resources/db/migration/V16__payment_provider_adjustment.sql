-- PayPal 쪽에서 시작된 금전 사건(대시보드 환불, capture 역전)과 검토 사건(capture 거절, 분쟁)을 기록한다(ADR-0111).
-- 금전 사건은 provider 자원 id 하나에 한 행이고, 원장 반영은 settlement_journal.source_refund_id = adjustment_id 로
-- 기존 환불 분개(V7 균형·append-only)를 그대로 쓴다. 이 표는 원장이 아니라 원장에 넣을 사실과 검토 대기열이다.
CREATE TABLE payment_provider_adjustment (
    adjustment_id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    provider_resource_id character varying(128) NOT NULL,
    attempt_id uuid REFERENCES payment_attempt (attempt_id),
    capture_id character varying(64),
    provider_amount_minor bigint,
    provider_currency character varying(3),
    amount_won bigint,
    provider_status character varying(64),
    status character varying(16) NOT NULL,
    review_reason character varying(64),
    first_event_id character varying(128) NOT NULL,
    last_event_id character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT payment_provider_adjustment_pk PRIMARY KEY (adjustment_id),
    CONSTRAINT payment_provider_adjustment_kind_check
        CHECK (kind IN ('REFUND', 'REVERSAL', 'DENIAL', 'DISPUTE')),
    CONSTRAINT payment_provider_adjustment_status_check
        CHECK (status IN ('POSTING', 'POSTED', 'REVIEW')),
    -- 원장에 넣는 행은 금전 사건뿐이고 금액·결제 시도가 확정돼 있어야 한다.
    -- (NOT IN 으로 쓴다: EnumCheckConstraintContractTest 는 "= ANY" 와 "= '값'" 만 값 집합으로 읽는다.)
    CONSTRAINT payment_provider_adjustment_posting_facts CHECK (
        status NOT IN ('POSTING', 'POSTED')
        OR (kind NOT IN ('DENIAL', 'DISPUTE') AND attempt_id IS NOT NULL AND amount_won > 0
            AND provider_amount_minor > 0 AND provider_currency IS NOT NULL)),
    CONSTRAINT payment_provider_adjustment_review_reason CHECK ((status = 'REVIEW') = (review_reason IS NOT NULL))
);

-- 환불과 역전은 같은 PayPal refund id 공간을 쓴다. 두 이벤트가 같은 id 를 가리켜도 판매자 차감은 한 번이다.
CREATE UNIQUE INDEX payment_provider_adjustment_money_once
    ON payment_provider_adjustment (provider_resource_id) WHERE kind IN ('REFUND', 'REVERSAL');
CREATE UNIQUE INDEX payment_provider_adjustment_kind_resource
    ON payment_provider_adjustment (kind, provider_resource_id);
CREATE INDEX payment_provider_adjustment_attempt
    ON payment_provider_adjustment (attempt_id) WHERE attempt_id IS NOT NULL;
CREATE INDEX payment_provider_adjustment_review
    ON payment_provider_adjustment (updated_at, adjustment_id) WHERE status = 'REVIEW';
