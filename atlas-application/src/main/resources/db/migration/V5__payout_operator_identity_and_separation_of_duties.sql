-- 지급 운영자의 사람별 신원과 승인자 분리(maker-checker)를 DB에 둔다. 새 표 1개와 trigger 1개뿐이며 기존 행·열은 바꾸지 않는다.
-- 되돌림(EXP-096 "되돌리기 훈련"): DROP INDEX, DROP TRIGGER 2개, DROP FUNCTION 2개, DROP TABLE 순서. 앱은 되돌리기 전에
-- ATLAS_SETTLEMENT_PAYOUT_OPERATOR_IDENTITY=shared-credential 로 기동하면 기존 단일 자격 증명 동작으로 돌아간다.

-- 지급 운영자 권한을 가진 account. 첫 운영자는 운영 절차(docs/operations-guide.md)대로 DB 권한자가 INSERT 한다.
-- 해제는 삭제가 아니라 revoked_at 으로 한다: 권한이 있었던 사실과 부여자를 남기고, 요청마다 이 표를 읽어 해제가 바로 적용된다.
CREATE TABLE payout_operator_account (
    account_id uuid PRIMARY KEY REFERENCES user_account (account_id),
    granted_by character varying(128) DEFAULT session_user NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT payout_operator_account_granted_by_check CHECK ((length(btrim((granted_by)::text)) > 0)),
    CONSTRAINT payout_operator_account_revoked_check CHECK (((revoked_at IS NULL) OR (revoked_at >= granted_at)))
);

-- 같은 사람이 이어지는 두 단계를 모두 하지 못하게 한다. 규칙은 앱 코드가 아니라 감사 행이 저장되는 순간 DB가 검사한다.
--
-- 적용 대상: actor 가 UUID 모양인 행(사람별 신원은 account_id 문자열을 actor 로 쓴다). 기존 단일 자격 증명의
-- username 은 UUID 모양이 아니므로 규칙이 적용되지 않는다. 그래서 rollback flag 로 단일 자격 증명 모드로 돌아가면
-- 같은 문자열이 검증과 승인을 해도 막히지 않는다. 사람이 아닌 actor 에는 이 규칙이 없다는 뜻이기도 하다.
--
-- PAYOUT_APPROVED, PAYOUT_APPROVAL_REPLAYED: actor 는 이 지급의 outbox 가 묶은 profile_version 을 PROFILE_VERIFIED 한
-- actor 와 달라야 한다. "현재 profile 행의 verified_by" 가 아니라 outbox 가 묶은 버전의 감사 행을 읽는 이유는, 승인 도중
-- 프로필이 다음 버전으로 넘어가도 승인이 실제로 쓰는 버전의 검증자를 비교하기 위해서다(검증 행은 쓰인 뒤 바뀌지 않는다).
-- MANUAL_PAYOUT_SENT: 같은 지급의 MANUAL_PAYOUT_APPROVED actor 와 달라야 한다(승인한 사람이 송금했다고 기록할 수 없다).
-- MANUAL_PAYOUT_CONFIRMED: 같은 지급의 MANUAL_PAYOUT_SENT actor 와 달라야 한다(송금 기록을 같은 사람이 확인할 수 없다).
-- 검증 근거 행이 없으면(SQL 로 직접 심은 데이터) 거절하지 않는다. 근거가 없는 것을 사람이 한 일로 단정할 수 없고,
-- 앱 코드는 프로필 검증 시 항상 근거 행을 쓴다.
CREATE FUNCTION enforce_payout_operator_separation() RETURNS trigger
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
    END IF;
    IF conflicts THEN
        RAISE EXCEPTION 'settlement_payout_operator_action_separation_of_duties: the actor of the previous step cannot perform %', NEW.action
            USING ERRCODE = '23514', CONSTRAINT = 'settlement_payout_operator_action_separation_of_duties';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER settlement_payout_operator_action_separation
    BEFORE INSERT ON settlement_payout_operator_action
    FOR EACH ROW EXECUTE FUNCTION enforce_payout_operator_separation();

-- 한 지급에 승인(PAYOUT_APPROVED) 행은 한 건뿐이다. 두 운영자가 동시에 승인하면 한쪽은 이미 만들어진 outbox 를 보고
-- PAYOUT_APPROVAL_REPLAYED 만 남긴다(PayoutDispatchApprovalCommitService). 이 인덱스는 어떤 경로든 승인 행이 둘이 되지
-- 못하게 하는 DB 쪽 보장이다. 수동 지급(MANUAL_PAYOUT_APPROVED)과 다른 조치에는 적용하지 않는다.
CREATE UNIQUE INDEX uq_settlement_payout_operator_action_approved
    ON settlement_payout_operator_action (payout_id) WHERE action = 'PAYOUT_APPROVED';

-- Seller 쪽 maker 와 운영자 checker 가 같은 사람이면 안 된다: 프로필 검증자는 그 프로필의 수취 account 가 될 수 없다.
-- 운영자 account 는 일반 account 라서 Seller 에 연결될 수 있다. verify 는 profile 행을 UPDATE 한 같은 트랜잭션에서 이 행을
-- 쓰므로 행 잠금이 recipient_account_id 의 동시 변경을 막는다. 사람 신원이 아닌 actor 에는 적용하지 않는다.
CREATE FUNCTION enforce_profile_verifier_is_not_recipient() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.action = 'PROFILE_VERIFIED' AND EXISTS (
        SELECT 1 FROM seller_payout_profile profile
        WHERE profile.seller_id = NEW.seller_id AND profile.recipient_account_id::text = NEW.actor) THEN
        RAISE EXCEPTION 'seller_payout_profile_action_separation_of_duties: the recipient account cannot verify its own payout profile'
            USING ERRCODE = '23514', CONSTRAINT = 'seller_payout_profile_action_separation_of_duties';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER seller_payout_profile_action_separation
    BEFORE INSERT ON seller_payout_profile_action
    FOR EACH ROW EXECUTE FUNCTION enforce_profile_verifier_is_not_recipient();
