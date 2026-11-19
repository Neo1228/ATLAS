-- 운영자에 의한 계정 정지와 감사 기록.
--
-- 본인 폐쇄(#385)와 분리하는 이유: 폐쇄는 되돌릴 수 없고 이용자 의사에 따른다.
-- 정지는 운영 판단이며 해제될 수 있다. 두 흐름이 같은 status 컬럼만 쓰지만
-- 근거·책임·되돌림 가능성이 다르므로 감사 기록을 따로 남긴다.
--
-- 행을 지우지 않고 status 만 바꾸는 이유는 주문·결제 이력의 참조 무결성 때문이다.

CREATE TABLE account_suspension_action (
    suspension_action_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES user_account (account_id),
    -- SUSPEND | RELEASE. 한 행이 한 번의 조작을 나타낸다.
    action VARCHAR(16) NOT NULL,
    -- 중복 요청 흡수용. 같은 계정에 같은 키로 두 번 들어와도 한 번만 기록된다.
    idempotency_key VARCHAR(128) NOT NULL,
    -- 누가 했는가. 인증 주체에서 채우며 비어 있을 수 없다.
    actor VARCHAR(128) NOT NULL,
    -- 왜 했는가. 비어 있으면 감사 기록의 의미가 없으므로 공백만 있는 값도 막는다.
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_suspension_action_key_unique UNIQUE (account_id, idempotency_key),
    CONSTRAINT account_suspension_action_action_check CHECK (action IN ('SUSPEND', 'RELEASE')),
    CONSTRAINT account_suspension_action_reason_check CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT account_suspension_action_actor_check CHECK (length(trim(actor)) BETWEEN 1 AND 128)
);

CREATE INDEX ix_account_suspension_action_history
    ON account_suspension_action (account_id, created_at DESC);
