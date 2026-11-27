-- 결제 복구 job에서 결과를 쓰지 못하고 lease가 만료된 claim(프로세스 강제 종료, lease보다 긴 정지)을 따로 센다.
-- 결과를 기록한 재시도와 섞어 세면, 시도 한도를 창(max-age)에 맞추는 순간 같은 job을 잡을 때마다 죽는
-- 인스턴스를 멈추는 보호도 그만큼 늦어진다(EXP-117 H2, ADR-0106). 상수 기본값이라 기존 행을 다시 쓰지 않는다.
-- V11과 독립이다(다른 테이블, 다른 객체).
ALTER TABLE payment_recovery_job
    ADD COLUMN abandoned_claim_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT payment_recovery_job_abandoned_claim_count_check CHECK (abandoned_claim_count >= 0);
