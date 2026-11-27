-- ADR-0101·EXP-112: 폴링마다 도는 질의 두 개가 이력 행 수에 비례해 읽던 것을 부분 인덱스로 끊는다.
-- 인덱스만 추가한다. 열·제약·행의 의미는 그대로이고 질의 결과도 같다. V7과 독립이라 적용 순서가 바뀌어도 된다.
-- 롤백: DROP INDEX IF EXISTS 두 문장(아래 이름). 이전 코드는 이 인덱스를 몰라도 동작한다.
-- 일반 CREATE INDEX는 만드는 동안 그 표의 쓰기를 막는다. 큰 운영 DB에서는 배포 전에 같은 이름·같은 정의로
-- CREATE INDEX CONCURRENTLY를 먼저 실행해 두면 아래 문장이 건너뛴다(IF NOT EXISTS). 실패해 INVALID로 남은 인덱스는
-- DROP INDEX CONCURRENTLY 후 다시 만든다(확인: SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid).

-- resumeExpiredApprovalWaits: status = 'WAITING_FOR_BUYER'인 행만 찾는 질의가 1초 폴링마다 도는데,
-- 이 상태를 위한 인덱스가 없어 완료된 이력 전체를 순차 스캔했다(EXP-112 Q9: 50만 행에서 호출당 약 9천 블록).
CREATE INDEX IF NOT EXISTS ix_payment_recovery_job_waiting_for_buyer
    ON payment_recovery_job (recovery_job_id)
    WHERE status = 'WAITING_FOR_BUYER';

-- JdbcPayoutDispatchStore.claim: (PENDING·WAITING_PROVIDER AND available_at) OR (LEASED AND lease_until)의
-- 두 번째 가지에 쓸 인덱스가 없어 OR 전체가 순차 스캔이 됐다(25만 행에서 호출당 약 9천 블록).
CREATE INDEX IF NOT EXISTS ix_settlement_payout_dispatch_leased
    ON settlement_payout_dispatch_outbox (lease_until)
    WHERE status = 'LEASED';
