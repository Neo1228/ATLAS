-- 웹훅 재시도 이력 요약의 전체 스캔을 제거한다.
--
-- PayPalWebhookRetryHistoryService.summary() 는 최근 24시간 outcome 별 건수를 센다.
-- 기존 술어는 COALESCE(finished_at, claimed_at) >= ... 였다. COALESCE 는 인덱스를
-- 사용할 수 없어, 이력 테이블(append-only, 절대 줄지 않음) 전체를 매 조회마다 스캔했다.
--
-- 실측 (200,000행, EXP-047):
--   Seq Scan 2,705 버퍼 → Index Only Scan 31 버퍼 (약 87배 감소).
--
-- 왜 생성 컬럼인가: COALESCE 를 SQL 에서 없애야 인덱스가 동작한다. 저장 컬럼으로 두면
-- 애플리케이션 쓰기 경로를 바꾸지 않아도 되고, 기존 행에도 자동으로 채워진다.
--
-- 왜 (recorded_at, outcome) 인가: WHERE 는 recorded_at 범위, GROUP BY 는 outcome 이다.
-- 두 컬럼이 모두 인덱스에 있으면 heap 접근 없이 Index Only Scan 이 된다.
--
-- 롤백 시 되돌리기:
--   DROP INDEX IF EXISTS paypal_webhook_retry_history_recorded_idx;
--   ALTER TABLE paypal_webhook_retry_attempt_history DROP COLUMN IF EXISTS recorded_at;

ALTER TABLE paypal_webhook_retry_attempt_history
    ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMPTZ
        GENERATED ALWAYS AS (COALESCE(finished_at, claimed_at)) STORED;

CREATE INDEX IF NOT EXISTS paypal_webhook_retry_history_recorded_idx
    ON paypal_webhook_retry_attempt_history (recorded_at, outcome);
