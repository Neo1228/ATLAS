-- 인증 메일(이메일 확인·비밀번호 재설정) 전달 의도를 계정 transaction 안에 기록하는 outbox (#718, ADR-0102, EXP-113).
--
-- 이 파일은 V8(#717, 큐 폴링 부분 색인)의 내용에 기대지 않는다. 새 테이블 하나만 만들고 기존 객체를 바꾸지 않으므로
-- V8 앞뒤 어느 순서로 적용돼도 결과가 같다. 번호는 V8 뒤에 병합되도록 9를 쓴다.
--
-- 토큰은 이 테이블에 어떤 형태로도 두지 않는다(평문·해시·암호문 모두 없음). worker가 전송 직전에 토큰을 발급하고
-- 해시만 account_action_token에 쓴다. 수신 주소도 두지 않고 전송 시점에 user_account에서 읽는다.
-- (계정, 목적)마다 대기 행은 하나다. 같은 목적의 재요청은 기존 행을 다시 대기로 돌린다.
-- 성공한 행은 지운다. FAILED 행은 운영자가 볼 수 있도록 남기고, 사용자가 다시 요청하면 PENDING으로 돌아간다.
CREATE TABLE account_notification_outbox (
    account_id uuid NOT NULL REFERENCES user_account(account_id),
    purpose character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'PENDING' NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    -- 다음 시도 시각. 청구 중에는 임대 만료 시각으로 쓰여 죽은 worker의 행이 그 뒤에 다시 보인다.
    available_at timestamp with time zone NOT NULL,
    lease_owner uuid,
    -- 예외 클래스 이름만 둔다. 메시지에는 수신 주소가 섞일 수 있다.
    last_failure character varying(128),
    CONSTRAINT account_notification_outbox_pkey PRIMARY KEY (account_id, purpose),
    CONSTRAINT ck_account_notification_outbox_purpose
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_notification_outbox_status CHECK (status IN ('PENDING', 'FAILED')),
    CONSTRAINT ck_account_notification_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_account_notification_outbox_due
    ON account_notification_outbox (available_at)
    WHERE status = 'PENDING';
