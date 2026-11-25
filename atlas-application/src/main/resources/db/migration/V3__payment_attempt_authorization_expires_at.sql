-- PayPal 이 authorization 응답에 담아 주는 expiration_time(EXP-086: Sandbox n=3, 모두 생성 후 29일)을
-- 결제 시도에 기록한다. 값이 없는 기존 행과 응답에 값이 없는 경우는 NULL 로 둔다.
-- 운영자 검토 목록이 "이 authorization 이 언제 만료되는가"를 보여 주는 데 쓰며, 결제 상태 전이에는 쓰지 않는다.
ALTER TABLE payment_attempt ADD COLUMN authorization_expires_at timestamp with time zone;
