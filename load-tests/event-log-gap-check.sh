#!/usr/bin/env bash
# identity event_seq를 offset으로 쓰는 cursor consumer가, 커밋 순서가 번호 순서와 어긋날 때 이벤트를 놓치는지 재현한다.
# writer: 번호를 먼저 받고 임의 시간 뒤에 커밋한다. consumer: 현재 방식(naive)과 xmin 안전 방식(safe)을 함께 돌려
# 끝난 뒤 각자 몇 건을 놓쳤는지 센다. 격리된 throwaway PostgreSQL만 쓴다.
set -euo pipefail

writers="${GAP_WRITERS:-8}"
per_writer="${GAP_PER_WRITER:-400}"
name="atlas-gapcheck-$(date +%s)"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$name" -e POSTGRES_PASSWORD="$(openssl rand -hex 12)" -e POSTGRES_DB=t postgres:18.6 >/dev/null
for _ in $(seq 1 60); do docker exec "$name" pg_isready -U postgres -d t >/dev/null 2>&1 && break; sleep 1; done
sql() { docker exec -i "$name" psql -X -q -U postgres -d t -v ON_ERROR_STOP=1 "$@"; }

sql >/dev/null <<'SQL'
CREATE TABLE ev (
  event_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tx_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
  payload INT NOT NULL
);
CREATE TABLE seen (consumer TEXT NOT NULL, event_seq BIGINT NOT NULL, PRIMARY KEY (consumer, event_seq));
CREATE TABLE cursor_state (consumer TEXT PRIMARY KEY, last_seq BIGINT NOT NULL DEFAULT 0);
INSERT INTO cursor_state VALUES ('naive', 0), ('safe', 0);

-- naive: 지금 EXP-061 방식. 마지막으로 본 번호보다 큰 것만 읽는다.
CREATE FUNCTION poll_naive(p_batch int) RETURNS int AS $$
DECLARE cur bigint; n int;
BEGIN
  SELECT last_seq INTO cur FROM cursor_state WHERE consumer = 'naive' FOR UPDATE;
  WITH b AS (SELECT event_seq FROM ev WHERE event_seq > cur ORDER BY event_seq LIMIT p_batch),
       i AS (INSERT INTO seen SELECT 'naive', event_seq FROM b RETURNING event_seq)
  SELECT count(*), COALESCE(max(event_seq), cur) INTO n, cur FROM i;
  UPDATE cursor_state SET last_seq = GREATEST(last_seq, cur) WHERE consumer = 'naive';
  RETURN n;
END; $$ LANGUAGE plpgsql;

-- safe: 아직 커밋되지 않은 트랜잭션이 남아 있을 수 있는 구간(xmin 이후)은 읽지 않는다.
CREATE FUNCTION poll_safe(p_batch int) RETURNS int AS $$
DECLARE cur bigint; n int; horizon xid8 := pg_snapshot_xmin(pg_current_snapshot());
BEGIN
  SELECT last_seq INTO cur FROM cursor_state WHERE consumer = 'safe' FOR UPDATE;
  WITH b AS (SELECT event_seq FROM ev WHERE event_seq > cur AND tx_id < horizon ORDER BY event_seq LIMIT p_batch),
       i AS (INSERT INTO seen SELECT 'safe', event_seq FROM b RETURNING event_seq)
  SELECT count(*), COALESCE(max(event_seq), cur) INTO n, cur FROM i;
  UPDATE cursor_state SET last_seq = GREATEST(last_seq, cur) WHERE consumer = 'safe';
  RETURN n;
END; $$ LANGUAGE plpgsql;
SQL

# writer: 번호 선점 후 0~150ms 임의 지연 뒤 커밋하도록 트랜잭션 안에서 sleep
write_batch() {
  sql >/dev/null <<SQL
DO \$\$ BEGIN FOR i IN 1..$per_writer LOOP
  BEGIN
    INSERT INTO ev (payload) VALUES (i);
    PERFORM pg_sleep(random() * 0.15);
  END;
  COMMIT;
END LOOP; END \$\$;
SQL
}
poll_loop() {
  local end=$((SECONDS + $1))
  while (( SECONDS < end )); do
    sql -Atc "SELECT poll_naive(50), poll_safe(50)" >/dev/null
    sleep 0.02
  done
}

for _ in $(seq 1 "$writers"); do write_batch & done
poll_loop "${GAP_SECONDS:-45}" &
wait

# 쓰기가 모두 끝난 뒤 한 번 더 비워서(최종 수렴) 그래도 못 읽은 이벤트만 '손실'로 센다.
for _ in 1 2 3 4 5 6; do sql -Atc "SELECT poll_naive(5000), poll_safe(5000)" >/dev/null; done
sql -Atc "
SELECT 'written=' || (SELECT count(*) FROM ev)
    || ' naive_missed=' || (SELECT count(*) FROM ev e WHERE NOT EXISTS (SELECT 1 FROM seen s WHERE s.consumer='naive' AND s.event_seq=e.event_seq))
    || ' safe_missed='  || (SELECT count(*) FROM ev e WHERE NOT EXISTS (SELECT 1 FROM seen s WHERE s.consumer='safe'  AND s.event_seq=e.event_seq));"
