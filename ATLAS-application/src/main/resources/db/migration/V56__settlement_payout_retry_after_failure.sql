-- 실패(FAILED)한 지급이 같은 판매자·기간의 재지급 요청을 막지 않게 한다.
--
-- 왜 필요한가: V52 의 settlement_payout_seller_period_unique 는 상태와 무관하게
-- (seller_id, period_start, period_end) 를 한 행으로 제한했다. 운영자가 지급을 FAILED 로
-- 전이하면 그 지급은 더 이상 잔액을 예약하지 않는다(pendingAmount 는 FAILED 를 제외).
-- 그런데 같은 기간의 새 요청은 unique 제약(ON CONFLICT DO NOTHING)과 기간 조회에 걸려
-- FAILED 행을 돌려받을 뿐이라, 잔액은 보이지만 그 기간으로는 영원히 지급할 수 없었다.
--
-- 바꾸는 것: 전체 unique 제약을 FAILED 를 제외한 partial unique index 로 바꾼다.
-- REQUESTED/APPROVED/SENT/CONFIRMED 지급은 여전히 같은 판매자·기간에 하나만 존재할 수
-- 있으므로(중복 지급 금지) 이중 지급 방어는 그대로다. FAILED 행은 이력으로 남고 개수 제한이 없다.
--
-- 애플리케이션 INSERT 는 partial index 추론을 위해
-- ON CONFLICT (seller_id, period_start, period_end) WHERE state <> 'FAILED' DO NOTHING
-- 을 쓴다. 이 인덱스의 WHERE 조건과 반드시 같아야 한다.
--
-- 롤백 시 되돌리기:
--   같은 (seller_id, period_start, period_end) 에 FAILED 행과 다른 행이 함께 있으면
--   전체 unique 제약을 다시 만들 수 없다. 그런 FAILED 행을 먼저 정리(보관 후 삭제)한 뒤
--   DROP INDEX IF EXISTS settlement_payout_seller_period_active_unique;
--   ALTER TABLE settlement_payout ADD CONSTRAINT settlement_payout_seller_period_unique
--       UNIQUE (seller_id, period_start, period_end);
--   를 실행하고, 애플리케이션 INSERT 의 ON CONFLICT 대상에서 WHERE 절을 제거한다.
ALTER TABLE settlement_payout DROP CONSTRAINT settlement_payout_seller_period_unique;

CREATE UNIQUE INDEX settlement_payout_seller_period_active_unique
    ON settlement_payout (seller_id, period_start, period_end)
    WHERE state <> 'FAILED';
