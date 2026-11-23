-- 예약 workflow 상태 변경을 같은 트랜잭션 안에서 이벤트 로그에 남긴다.
-- 소비자(정산 원장, 가용 재고 projection, 감사, Kafka relay)는 각자 cursor를 들고 이 로그를 100% 읽는다.
--
-- event_seq 같은 identity 값만으로 cursor를 만들면 커밋 순서와 시퀀스 발급 순서가 달라서 이벤트를 영구히 건너뛴다.
-- (load-tests/event-log-gap-check.sh: 3,200건 중 1,013건 유실). 그래서 이벤트마다 쓴 트랜잭션의 xid8을 같이 저장하고,
-- 소비자는 tx_id < pg_snapshot_xmin(pg_current_snapshot()) 인 행만, (tx_id, event_seq) 순서로 읽는다.
-- 비용: idle in transaction 세션이 xmin horizon을 잡으면 모든 소비자가 멈춘다(idle_in_transaction_session_timeout으로 제한).
CREATE TABLE order_inventory_event (
    event_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tx_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
    workflow_id UUID NOT NULL,
    order_id UUID NOT NULL,
    aggregate_version INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    last_error_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT order_inventory_event_version_unique UNIQUE (workflow_id, aggregate_version),
    CONSTRAINT order_inventory_event_version_positive CHECK (aggregate_version >= 1)
);

CREATE INDEX order_inventory_event_tx_idx
    ON order_inventory_event (tx_id, event_seq);

-- 소비자 그룹별 읽기 위치. 행이 없는 그룹은 로그의 처음부터 읽는다. Kafka relay는 consumer_group = 'kafka-relay'를 쓴다.
-- lease_token/lease_until은 relay replica 하나만 Kafka로 보내도록 하는 lease다. 전송 동안 DB 트랜잭션을 열어 두지 않고,
-- cursor 전진은 lease_token이 일치할 때만 허용해(fencing) lease를 빼앗긴 replica의 늦은 결과를 버린다.
CREATE TABLE order_inventory_event_cursor (
    consumer_group VARCHAR(64) PRIMARY KEY,
    last_tx_id xid8 NOT NULL,
    last_event_seq BIGINT NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 이 마이그레이션 이전에 이미 있던 workflow는 현재 상태를 version 1 스냅샷 이벤트로 남긴다.
-- 이후 상태 변경은 version 2부터 이어진다.
INSERT INTO order_inventory_event (workflow_id, order_id, aggregate_version, event_type, last_error_code, occurred_at)
SELECT workflow_id, order_id, 1, 'RESERVATION_' || workflow_state, last_error_code, updated_at
FROM order_inventory_reservation_workflow
ORDER BY created_at, workflow_id;

-- 상태가 실제로 바뀐 경우에만 이벤트를 남긴다. workflow 행 잠금이 같은 workflow의 UPDATE를 직렬화하므로
-- max + 1 이 workflow 안에서 빈틈없는 version이 된다.
CREATE FUNCTION order_inventory_record_workflow_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.workflow_state IS NOT DISTINCT FROM NEW.workflow_state THEN
        RETURN NULL;
    END IF;
    INSERT INTO order_inventory_event (workflow_id, order_id, aggregate_version, event_type, last_error_code)
    SELECT NEW.workflow_id, NEW.order_id, COALESCE(MAX(e.aggregate_version), 0) + 1,
           'RESERVATION_' || NEW.workflow_state, NEW.last_error_code
    FROM order_inventory_event e
    WHERE e.workflow_id = NEW.workflow_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER order_inventory_reservation_workflow_event
    AFTER INSERT OR UPDATE OF workflow_state ON order_inventory_reservation_workflow
    FOR EACH ROW EXECUTE FUNCTION order_inventory_record_workflow_event();
