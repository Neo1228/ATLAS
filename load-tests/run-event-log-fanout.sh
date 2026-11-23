#!/usr/bin/env bash
# 이벤트 로그를 초당 RATE건 쓰는 동안 CONSUMERS개 consumer group이 각자 cursor로 폴링할 때의 lag와 DB 비용,
# 그리고 끝난 뒤 한 group이 처음부터 재생하는 시간을 잰다. 격리된 throwaway PostgreSQL만 쓴다.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rate="${FANOUT_RATE:-500}"
duration="${FANOUT_DURATION:-60}"
consumers="${FANOUT_CONSUMERS:-3}"
batch="${FANOUT_BATCH:-500}"
poll_ms="${FANOUT_POLL_MS:-200}"
cpus="${FANOUT_DB_CPUS:-2}"
run="fanout-$(date +%Y%m%d-%H%M%S)"
out="$repo_root/.local/event-log-fanout/$run"
mkdir -p "$out"

password="$(openssl rand -hex 16)"
name="atlas-$run"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$name" --cpus "$cpus" --memory 2g -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=atlas \
  postgres:18.6 >/dev/null
for _ in $(seq 1 60); do docker exec "$name" pg_isready -U postgres -d atlas >/dev/null 2>&1 && break; sleep 1; done
psql_in() { docker exec -i "$name" psql -X -q -U postgres -d atlas -v ON_ERROR_STOP=1 "$@"; }

# 스키마: 이벤트 로그(event_seq = 단조 증가 offset)와 consumer group별 cursor. 이 실험 전용이며 애플리케이션 마이그레이션이 아니다.
psql_in >/dev/null <<'SQL'
CREATE TABLE order_inventory_event (
    event_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id UUID NOT NULL,
    order_id UUID NOT NULL,
    aggregate_version INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    last_error_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_inventory_event_aggregate_unique UNIQUE (workflow_id, aggregate_version)
);
CREATE TABLE order_inventory_event_cursor (
    consumer_group VARCHAR(64) PRIMARY KEY,
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);
SQL

# consumer 폴링 함수: batch만큼 읽고 cursor를 전진시킨다. 읽은 최대 seq와 현재 최대 seq의 차이가 lag이다.
psql_in <<'SQL'
CREATE FUNCTION poll(p_group text, p_batch int) RETURNS int AS $$
DECLARE cur bigint; got int; top bigint;
BEGIN
  INSERT INTO order_inventory_event_cursor (consumer_group, last_event_seq, updated_at) VALUES (p_group, 0, now())
  ON CONFLICT DO NOTHING;
  SELECT last_event_seq INTO cur FROM order_inventory_event_cursor WHERE consumer_group = p_group FOR UPDATE;
  SELECT count(*), COALESCE(max(event_seq), cur) INTO got, top
  FROM (SELECT event_seq FROM order_inventory_event WHERE event_seq > cur ORDER BY event_seq LIMIT p_batch) s;
  UPDATE order_inventory_event_cursor SET last_event_seq = top, updated_at = now() WHERE consumer_group = p_group;
  RETURN got;
END; $$ LANGUAGE plpgsql;
SQL

# pgbench 스크립트: writer는 고정 속도로 1건씩 insert, consumer는 poll 후 대기.
cat > "$out/writer.sql" <<'SQL'
INSERT INTO order_inventory_event (workflow_id, order_id, aggregate_version, event_type, occurred_at)
VALUES (gen_random_uuid(), gen_random_uuid(), 1, 'RESERVED', now());
SQL
cat > "$out/consumer.sql" <<SQL
SELECT poll('group-' || (:client_id)::text, ${batch});
\\sleep ${poll_ms} ms
SQL

before="$(psql_in -Atc "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = 'atlas'")"

# writer: -R 속도 제한(초당 트랜잭션), 4 연결. consumer: CONSUMERS 연결.
docker exec -i "$name" bash -c "cat > /tmp/w.sql" < "$out/writer.sql"
docker exec -i "$name" bash -c "cat > /tmp/c.sql" < "$out/consumer.sql"
docker exec "$name" pgbench -n -U postgres -c 4 -j 2 -R "$rate" -T "$duration" -f /tmp/w.sql atlas >"$out/writer.log" 2>&1 &
writer_pid=$!
docker exec "$name" pgbench -n -U postgres -c "$consumers" -j 1 -T "$duration" -f /tmp/c.sql atlas >"$out/consumers.log" 2>&1 &
consumer_pid=$!

# 측정 중 1초마다 lag 샘플링
: > "$out/lag.csv"
for _ in $(seq 1 "$duration"); do
  sleep 1
  psql_in -Atc "SELECT now(), (SELECT COALESCE(max(event_seq),0) FROM order_inventory_event), (SELECT COALESCE(min(last_event_seq),0) FROM order_inventory_event_cursor)" \
    >> "$out/lag.csv" 2>/dev/null || true
done
wait "$writer_pid" "$consumer_pid" || true

after="$(psql_in -Atc "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = 'atlas'")"
written="$(psql_in -Atc "SELECT count(*) FROM order_inventory_event")"
size_line="$(psql_in -Atc "SELECT pg_total_relation_size('order_inventory_event') || ' ' || count(*) FROM order_inventory_event")"
final_lag="$(psql_in -Atc "SELECT (SELECT max(event_seq) FROM order_inventory_event) - min(last_event_seq) FROM order_inventory_event_cursor")"

# 재생: 새 group이 처음부터 batch씩 끝까지 읽는 시간
replay_start=$(date +%s.%N)
psql_in -Atc "DO \$\$ DECLARE n int; BEGIN LOOP SELECT poll('replay', 2000) INTO n; EXIT WHEN n = 0; END LOOP; END \$\$" >/dev/null
replay_end=$(date +%s.%N)

python3 - "$out/lag.csv" "$rate" "$duration" "$consumers" "$written" "$final_lag" "$before" "$after" "$replay_start" "$replay_end" "$batch" "$poll_ms" "$size_line" <<'PY'
import csv, sys
lag_file, rate, duration, consumers, written, final_lag, before, after, rs, re_, batch, poll, size_line = sys.argv[1:]
lags = []
for row in csv.reader(open(lag_file), delimiter='|'):
    if len(row) == 3:
        lags.append(int(row[1]) - int(row[2]))
lags.sort()
p = lambda q: lags[min(len(lags) - 1, int(len(lags) * q))] if lags else 'n/a'
seconds = float(re_) - float(rs)
written = int(written)
print(f'offered={rate}/s duration={duration}s consumers={consumers} batch={batch} poll={poll}ms written={written}')
print(f'lag_events: p50={p(0.5)} p99={p(0.99)} max={lags[-1] if lags else "n/a"} final={final_lag}')
size_bytes, size_rows = (int(x) for x in size_line.split())
print(f'table_total_bytes={size_bytes} bytes_per_event={size_bytes / max(size_rows, 1):.0f} (index 포함)')
print(f'db_xact_total={int(after) - int(before)} replay_all_{written}_events_s={seconds:.1f} replay_events_per_s={written / seconds:.0f}')
PY
printf 'results=%s\n' "$out"
