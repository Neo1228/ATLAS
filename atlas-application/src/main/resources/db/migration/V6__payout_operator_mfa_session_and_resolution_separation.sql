-- 지급 운영자 통제 두 가지를 DB에 둔다(ADR-0094). 열 1개를 더하고 V5의 trigger 함수 1개를 교체하며 기존 행은 바꾸지 않는다.
-- 되돌림(EXP-102 "되돌리기 훈련"): ALTER TABLE refresh_token_family DROP COLUMN mfa_verified_at 와
-- V5의 enforce_payout_operator_separation() 본문으로 CREATE OR REPLACE. 앱은 되돌리기 전에 코드를 먼저 되돌려야 한다.

-- 이 세션(refresh family)이 2차 인증(TOTP 또는 복구 코드)을 거쳐 만들어졌다는 표식이다. 비밀번호만으로 만든 세션은
-- NULL이다. 지급 운영자 경로는 이 표식을 요청마다 읽는다. 등록 시각과 세션 생성 시각을 비교하지 않는 이유: 등록을
-- 확정하는 순간 로그인이 겹치면 표식 없는 세션이 등록 뒤에 만들어질 수 있어, 비교는 좁은 경쟁 구간을 남긴다.
ALTER TABLE refresh_token_family ADD COLUMN mfa_verified_at timestamp with time zone;

-- 같은 사람이 이어지는 두 단계를 모두 하지 못하게 하는 규칙을 돈의 상태를 바꾸는 해소 조치로 넓힌다.
-- 추가 규칙(V5의 규칙은 그대로):
--   PROVIDER_REVIEW_PAID, PROVIDER_REVIEW_NOT_PAID: actor는 같은 지급의 PAYOUT_APPROVED actor와 달라야 한다.
--     NOT_PAID는 예약을 풀어 같은 잔액으로 새 지급을 만들 수 있게 하고, PAID는 지급을 장부에 확정한다.
--     승인한 사람이 결과도 단독 판정하면 이중 지급이나 허위 확정이 한 사람의 판단으로 가능하다.
--   MANUAL_NO_TRANSFER_CONFIRMED: actor는 같은 지급의 MANUAL_PAYOUT_SENT actor와 달라야 한다.
--     송금했다고 기록한 사람이 같은 지급을 "송금 없음"으로 뒤집으면 예약이 풀려 이중 지급이 가능하다.
-- 적용하지 않는 조치: PAYOUT_REQUEUED는 같은 provider ID와 같은 payload로 다시 시도할 뿐 돈의 수취인과 금액을 바꾸지
--   못하고, PAYOUT_CANCELLED와 MANUAL_PAYOUT_FAILED는 돈이 나가는 것을 막는 쪽이다(ADR-0094).
-- 근거 행이 없으면(SQL로 직접 심은 데이터) 거절하지 않는다. V5와 같은 이유다.
CREATE OR REPLACE FUNCTION enforce_payout_operator_separation() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    conflicts boolean := false;
BEGIN
    IF NEW.actor !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RETURN NEW;
    END IF;
    IF NEW.action IN ('PAYOUT_APPROVED', 'PAYOUT_APPROVAL_REPLAYED') THEN
        SELECT EXISTS (
            SELECT 1
            FROM settlement_payout payout
            JOIN settlement_payout_dispatch_outbox outbox ON outbox.payout_id = payout.payout_id
            JOIN seller_payout_profile_action verification
                ON verification.seller_id = payout.seller_id
               AND verification.profile_version = outbox.profile_version
               AND verification.action = 'PROFILE_VERIFIED'
            WHERE payout.payout_id = NEW.payout_id
              AND verification.actor = NEW.actor
        ) INTO conflicts;
    ELSIF NEW.action = 'MANUAL_PAYOUT_SENT' THEN
        SELECT EXISTS (
            SELECT 1 FROM settlement_payout_operator_action prior
            WHERE prior.payout_id = NEW.payout_id AND prior.action = 'MANUAL_PAYOUT_APPROVED' AND prior.actor = NEW.actor
        ) INTO conflicts;
    ELSIF NEW.action = 'MANUAL_PAYOUT_CONFIRMED' THEN
        SELECT EXISTS (
            SELECT 1 FROM settlement_payout_operator_action prior
            WHERE prior.payout_id = NEW.payout_id AND prior.action = 'MANUAL_PAYOUT_SENT' AND prior.actor = NEW.actor
        ) INTO conflicts;
    ELSIF NEW.action IN ('PROVIDER_REVIEW_PAID', 'PROVIDER_REVIEW_NOT_PAID') THEN
        SELECT EXISTS (
            SELECT 1 FROM settlement_payout_operator_action prior
            WHERE prior.payout_id = NEW.payout_id AND prior.action = 'PAYOUT_APPROVED' AND prior.actor = NEW.actor
        ) INTO conflicts;
    ELSIF NEW.action = 'MANUAL_NO_TRANSFER_CONFIRMED' THEN
        SELECT EXISTS (
            SELECT 1 FROM settlement_payout_operator_action prior
            WHERE prior.payout_id = NEW.payout_id AND prior.action = 'MANUAL_PAYOUT_SENT' AND prior.actor = NEW.actor
        ) INTO conflicts;
    END IF;
    IF conflicts THEN
        RAISE EXCEPTION 'settlement_payout_operator_action_separation_of_duties: the actor of the previous step cannot perform %', NEW.action
            USING ERRCODE = '23514', CONSTRAINT = 'settlement_payout_operator_action_separation_of_duties';
    END IF;
    RETURN NEW;
END;
$$;
