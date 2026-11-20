-- 주문 환불 idempotency key 의 범위를 전역에서 주문 단위로 좁힌다.
--
-- 왜 필요한가: V11 은 order_refund_request.idempotency_key 를 전역 UNIQUE 로 만들었다.
-- key 는 판매자가 고르는 값이라, 판매자 S1 이 주문 A 에 "refund-1" 을 쓰면 무관한 판매자
-- S2 가 주문 B 에 같은 "refund-1" 을 보낼 때 409 CONFLICT 를 받았다. 다른 쓰기 경로는 모두
-- 소유 범위 안에서만 key 를 유일하게 본다(payment_refund_operation 의 (attempt_id,
-- idempotency_key), 정산 지급, 재고 이동). 환불도 같은 규칙으로 (order_id, idempotency_key)
-- 를 유일 범위로 둔다. 같은 주문 안에서 같은 key 로 다른 사실을 보내면 여전히 CONFLICT 다
-- (서비스가 advisory lock + 조회로 판정하고, 이 제약은 마지막 방어선이다).
--
-- V11 의 인라인 UNIQUE 는 PostgreSQL 기본 이름 order_refund_request_idempotency_key_key 를
-- 받았고, 이후 마이그레이션(V15, V17, V24, V47)은 이 제약을 바꾸지 않았다.
ALTER TABLE order_refund_request
    DROP CONSTRAINT order_refund_request_idempotency_key_key;

ALTER TABLE order_refund_request
    ADD CONSTRAINT order_refund_request_order_key_unique UNIQUE (order_id, idempotency_key);

-- payment 계층에 넘기는 key 를 환불 요청마다 고정한다.
--
-- 왜 필요한가: 한 checkout 에 여러 판매자 주문이 있으면 결제 attempt 하나를 공유하고,
-- payment_refund_operation 은 (attempt_id, idempotency_key) 로 유일하다. 주문 단위로 key 를
-- 허용한 뒤 판매자 key 를 그대로 넘기면, 같은 checkout 의 두 판매자가 같은 key 를 쓸 때
-- payment 계층에서 서로의 송금 작업으로 합쳐진다. 그래서 checkout 주문의 새 환불은 주문 id 로
-- 범위를 좁힌 key 를 이 컬럼에 저장하고 그대로 payment 계층에 넘긴다.
--
-- 기존 행은 이미 원래 key 로 payment 작업이 만들어졌다. 진행 중(PENDING/UNKNOWN) 환불을
-- 재시도할 때 key 가 바뀌면 새 송금 작업이 생겨 이중 환불이 되므로, 기존 행은 원래 key 로
-- 채운다. NULL 은 "원래 key 를 쓴다"는 뜻으로 읽는다(직접 INSERT 하는 기존 데이터 호환).
ALTER TABLE order_refund_request
    ADD COLUMN payment_idempotency_key VARCHAR(128);

UPDATE order_refund_request
SET payment_idempotency_key = idempotency_key;

-- 롤백 시 되돌리기(주의: 이미 다른 주문끼리 같은 key 를 쓴 행이 있으면 전역 UNIQUE 를 다시
-- 만들 수 없으므로, 그 행들의 key 를 먼저 정리해야 한다. payment_idempotency_key 가 원래
-- key 와 다른 행은 payment 작업이 그 값으로 만들어졌으므로 컬럼을 지우기 전에 보존해야 한다):
--   ALTER TABLE order_refund_request DROP CONSTRAINT order_refund_request_order_key_unique;
--   ALTER TABLE order_refund_request
--       ADD CONSTRAINT order_refund_request_idempotency_key_key UNIQUE (idempotency_key);
--   ALTER TABLE order_refund_request DROP COLUMN payment_idempotency_key;
