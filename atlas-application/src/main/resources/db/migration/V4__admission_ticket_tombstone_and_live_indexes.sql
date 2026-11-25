-- ADR-0088·EXP-092: admission ticket 정리와 구매자별 live ticket 규칙이 쓰는 열 하나와 부분 인덱스 두 개.
-- 제약은 만들지 않고 기존 열·행의 의미도 바꾸지 않는다. 롤백은 정리 작업 속성을 끄는 것이며 이 파일은 되돌리지 않는다
-- (되돌려도 이전 코드는 쓰지 않는 열과 인덱스라 영향이 없다: EXP-092 되돌리기 시연).
-- 일반 CREATE INDEX는 만드는 동안 이 표의 쓰기를 막는다(EXP-092: 1,000,000행 0.5초, 5,000,000행 5초).
-- 이미 큰 운영 DB에 올릴 때는 CREATE INDEX CONCURRENTLY로 같은 이름의 인덱스를 먼저 만들어 두면 아래 문장이 건너뛴다.

-- checkout이 시작돼 주문이 만들어진 시각. 주문이 가리키는 ticket은 영영 못 지우는데 정리 후보 인덱스의 앞쪽(오래된 쪽)에
-- 쌓이면 정리 문장의 읽기가 그 수에 비례해 늘어난다(EXP-092 H2: 100,000개에서 buffer 약 50만). 그래서 인덱스에서 뺀다.
ALTER TABLE admission_ticket ADD COLUMN IF NOT EXISTS checkout_started_at timestamp with time zone;

-- 이 열이 생기기 전에 주문이 만들어진 ticket. 이 backfill을 건너뛰어도 정리 문장의 NOT EXISTS가 지켜 정확성은 같고 읽기만 늘어난다.
UPDATE admission_ticket t
SET checkout_started_at = t.created_at
FROM purchase_order o
WHERE o.ticket_id = t.ticket_id AND t.checkout_started_at IS NULL;

-- 정리 작업이 "오래된 종료 ticket" 후보를 created_at 순서로 batch만큼만 읽게 한다.
-- 인덱스가 없으면 정상 상태(지울 행 없음)마다 표 전체를 훑는다(EXP-090: 550k행에서 1.4~1.8초).
CREATE INDEX IF NOT EXISTS admission_ticket_tombstone_idx
    ON admission_ticket (created_at, ticket_id)
    WHERE status IN ('EXPIRED', 'CHECKOUT_FAILED') AND checkout_started_at IS NULL;

-- join이 counter 잠금 안에서 "이 구매자가 이 Seller-SKU에 가진 live ticket 수"를 세는 데 쓴다.
-- live 행은 K와 대기열 길이로 묶여 있어 작다. 유일(UNIQUE) 인덱스로 만들지 않은 이유는 ADR-0088.
CREATE INDEX IF NOT EXISTS admission_ticket_live_buyer_idx
    ON admission_ticket (seller_id, sku_id, buyer_ref)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE', 'CHECKOUT_STARTED');
