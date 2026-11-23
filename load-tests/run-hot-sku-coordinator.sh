#!/usr/bin/env bash
# Hot-SKU flash-sale baseline for the PostgreSQL-only order coordinator.
#
# For every offered rate it brings up a FRESH isolated compose project (own network and volumes, throwaway
# secrets, fixed CPU/memory limits, free loopback ports), warms the JVM, measures a constant-arrival-rate flood of
# single-unit orders against ONE seeded SKU (stock << orders, so most orders are rejected), collects the order-DB
# cost and the application saturation signals, checks the stock invariants, and tears the project down.
#
# Usage: load-tests/run-hot-sku-coordinator.sh            # rates 100 300 500 1000 2000, 20s each, stock 100
#        RATES="500 1000" STOCK=100 load-tests/run-hot-sku-coordinator.sh
# Results: .local/hot-sku-coordinator/<timestamp>/ (git-ignored). No secret is ever printed or written there.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/load-tests/compose-hot-sku-coordinator.yaml"
rates="${RATES:-100 300 500 1000 2000}"
duration="${DURATION:-20s}"
stock="${STOCK:-100}"
warmup_rate=100
warmup_duration=10s
idle_seconds=10
settle_timeout=60
# GATE=redis adds a Redis admission gate (redis:7.4-alpine) in front of the PostgreSQL workflow; default off.
# GATE=precheck uses the Redis-free availability pre-check gate (one location read per request, no order-DB write
# before admission, no redis service). redis and precheck are mutually exclusive.
gate="${GATE:-off}"
# REDIS_KILL_AFTER=<seconds>: stop the redis container that many seconds after the measured k6 run starts.
redis_kill_after="${REDIS_KILL_AFTER:-}"
# Redis gate의 command timeout과 breaker cooldown은 애플리케이션이 읽는 변수 이름 그대로 넘긴다.
# 설정하지 않으면 애플리케이션 기본값을 쓴다.
#   ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_COMMAND_TIMEOUT=PT0.05S
#   ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_BREAKER_COOLDOWN=PT10S
case "$gate" in
  off|redis|precheck) ;;
  *) echo "GATE must be off, redis or precheck" >&2; exit 2 ;;
esac
if [[ -n "$redis_kill_after" && ! "$redis_kill_after" =~ ^[0-9]+$ ]]; then
  echo 'REDIS_KILL_AFTER must be an integer number of seconds' >&2; exit 2
fi
if [[ -n "$redis_kill_after" && "$gate" != "redis" ]]; then
  echo 'REDIS_KILL_AFTER requires GATE=redis' >&2; exit 2
fi

[[ "$duration" =~ ^([0-9]+)s$ ]] || { echo 'DURATION must look like 20s' >&2; exit 2; }
duration_seconds="${BASH_REMATCH[1]}"
for rate in $rates; do
  [[ "$rate" =~ ^[1-9][0-9]*$ ]] || { echo "invalid rate: $rate" >&2; exit 2; }
done

timestamp="$(date +%Y%m%d-%H%M%S)"
out="${OUT:-$repo_root/.local/hot-sku-coordinator/$timestamp}"
mkdir -p "$out"
chmod 777 "$out"   # the k6 container runs as a non-root uid and writes its summary here

project="atlas-hotsku-coord-$(openssl rand -hex 3)"
# Throwaway secrets: generated per invocation, kept only in this process environment.
DB_PASSWORD="$(openssl rand -hex 16)"
INVENTORY_LOCATION_SERVICE_TOKEN="$(openssl rand -hex 24)"
ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN="$(openssl rand -hex 24)"
ATLAS_JWT_SIGNING_SECRET="$(openssl rand -hex 32)"
RESULTS_DIR="$out"
export DB_PASSWORD INVENTORY_LOCATION_SERVICE_TOKEN ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN \
  ATLAS_JWT_SIGNING_SECRET RESULTS_DIR

ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_ENABLED=false
ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_PRECHECK_ENABLED=false
gate_profile=()
services=(order-postgres order-mailpit location-postgres location app)
if [[ "$gate" == "redis" ]]; then
  ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_ENABLED=true
  gate_profile=(--profile gate)
  services=(redis "${services[@]}")
fi
if [[ "$gate" == "precheck" ]]; then
  ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_PRECHECK_ENABLED=true
fi
export ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_ENABLED ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_PRECHECK_ENABLED
dc() { docker compose -p "$project" -f "$compose_file" --profile load ${gate_profile[@]+"${gate_profile[@]}"} "$@"; }
order_psql() { dc exec -T order-postgres psql -X -U atlas -d atlas_order -At -F '|' -c "$1"; }
location_psql() { dc exec -T location-postgres psql -X -U atlas_inventory -d atlas_inventory -At -F '|' -c "$1"; }

sampler_pid=""
cleanup() {
  local status=$?
  set +e
  if [[ -n "$sampler_pid" ]]; then kill "$sampler_pid" 2>/dev/null; wait "$sampler_pid" 2>/dev/null; fi
  dc down -v --remove-orphans >/dev/null 2>&1
  exit "$status"
}
trap cleanup EXIT INT TERM

stack_ready() {
  for _ in $(seq 1 180); do
    if curl -fsS --max-time 2 "$mgmt_url/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    if curl -fsS --max-time 2 "$app_url/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    sleep 1
  done
  return 1
}

# Snapshot of the order DB: transactions, WAL, per-table tuple counters, exact row counts of the workflow tables.
snapshot() {
  local label="$1" file="$2"
  sleep 2   # pg_stat_* counters are flushed asynchronously
  {
    printf 'epoch|%s\n' "$(python3 -c 'import time; print(time.time())')"
    order_psql "SELECT 'xact|' || (xact_commit + xact_rollback) FROM pg_stat_database WHERE datname = 'atlas_order'"
    order_psql "SELECT 'wal|' || wal_records || '|' || wal_bytes FROM pg_stat_wal"
    order_psql "SELECT 'tbl|' || relname || '|' || n_tup_ins || '|' || n_tup_upd || '|' || n_tup_del FROM pg_stat_user_tables ORDER BY relname"
    local t
    for t in $(order_psql "SELECT relname FROM pg_stat_user_tables WHERE relname LIKE 'order_inventory%' ORDER BY relname"); do
      printf 'rows|%s|%s\n' "$t" "$(order_psql "SELECT count(*) FROM $t")"
    done
    location_psql "SELECT 'locxact|' || (xact_commit + xact_rollback) FROM pg_stat_database WHERE datname = 'atlas_inventory'"
    curl -fsS --max-time 2 "$mgmt_url/actuator/prometheus" 2>/dev/null \
      | awk '/^hikaricp_connections_timeout_total/ {print "hikari_timeout_total|" $NF}
             /^atlas_order_admission_gate_total/ { r = $0; sub(/.*result="/, "", r); sub(/".*/, "", r); print "gate|" r "|" $NF }'
  } > "$file"
  echo "snapshot $label ok" >/dev/null
}

run_k6() {
  local run_id="$1" rate="$2" length="$3" summary="$4" log="$5"
  dc run --rm --no-deps -T \
    -e RUN_ID="$run_id" -e RATE="$rate" -e DURATION="$length" \
    -e STOCK="$stock" -e SUMMARY_FILE="/results/$summary" \
    k6 run /scenario.js >"$log" 2>&1 || true
}

sample_loop() {
  local flag="$1" dir="$2" ts
  while [[ -e "$flag" ]]; do
    ts="$(python3 -c 'import time; print(int(time.time()))')"
    curl -fsS --max-time 2 "$mgmt_url/actuator/prometheus" 2>/dev/null \
      | awk -v ts="$ts" '/^(hikaricp_connections_(pending|active|max|idle|timeout_total)|process_cpu_usage|system_cpu_usage|tomcat_threads_busy_threads|jvm_threads_live_threads)[ {]/ {print ts, $0}' \
      >> "$dir/actuator.log" || true
    # shellcheck disable=SC2046
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
      $(docker ps -q --filter "label=com.docker.compose.project=$project") 2>/dev/null \
      | sed "s/^/$ts /" >> "$dir/docker-stats.log" || true
    sleep 1
  done
}

for rate in $rates; do
  run_dir="$out/rate-$rate"
  mkdir -p "$run_dir"
  echo "=== rate=$rate stock=$stock duration=$duration project=$project"
  dc up -d "${services[@]}" >"$run_dir/compose-up.log" 2>&1
  app_url="http://127.0.0.1:$(dc port app 8080 | awk -F: '{print $NF}')"
  mgmt_url="http://127.0.0.1:$(dc port app 8081 | awk -F: '{print $NF}')"
  stack_ready || { echo "stack did not become healthy; see $run_dir/app.log" >&2; dc logs --no-color app >"$run_dir/app.log" 2>&1 || true; exit 1; }

  # Warm-up on a different SKU: JIT, connection pools, Hibernate/JDBC caches.
  warm_id="$(uuidgen | tr 'A-Z' 'a-z')"
  run_k6 "$warm_id" "$warmup_rate" "$warmup_duration" "rate-$rate/warmup.json" "$run_dir/k6-warmup.log"

  # Idle background cost (recovery pollers etc.) so it can be subtracted from the measured window.
  snapshot idle-start "$run_dir/idle-start.txt"
  sleep "$idle_seconds"
  snapshot idle-end "$run_dir/idle-end.txt"

  run_id="$(uuidgen | tr 'A-Z' 'a-z')"
  snapshot before "$run_dir/before.txt"
  flag="$run_dir/.sampling"; : > "$flag"
  sample_loop "$flag" "$run_dir" &
  sampler_pid=$!
  k6_start="$(python3 -c 'import time; print(time.time())')"
  killer_pid=""
  if [[ -n "$redis_kill_after" ]]; then
    ( sleep "$redis_kill_after"; python3 -c 'import time; print(time.time())' > "$run_dir/redis-stop-epoch.txt"; dc stop -t 1 redis >/dev/null 2>&1 ) &
    killer_pid=$!
  fi
  run_k6 "$run_id" "$rate" "$duration" "rate-$rate/summary.json" "$run_dir/k6.log"
  if [[ -n "$killer_pid" ]]; then wait "$killer_pid" 2>/dev/null || true; fi
  k6_end="$(python3 -c 'import time; print(time.time())')"
  rm -f "$flag"; wait "$sampler_pid" 2>/dev/null || true; sampler_pid=""

  # Let asynchronous workflow work (recovery worker) converge before the final snapshot.
  nonterminal=-1
  for _ in $(seq 1 "$settle_timeout"); do
    nonterminal="$(order_psql "SELECT count(*) FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '${run_id}-%' AND workflow_state IN ('PENDING','RETRY_PENDING','COMPENSATING','COMPENSATION_REQUIRED','CANCEL_PENDING')")"
    [[ "$nonterminal" == "0" ]] && break
    sleep 1
  done
  snapshot after "$run_dir/after.txt"
  order_psql "SELECT workflow_state || '|' || count(*) FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '${run_id}-%' GROUP BY workflow_state ORDER BY workflow_state" > "$run_dir/workflow-states.txt"
  printf '%s\n' "$nonterminal" > "$run_dir/nonterminal.txt"
  printf '%s %s\n' "$gate" "${redis_kill_after:-none}" > "$run_dir/gate-mode.txt"
  printf 'command_timeout=%s breaker_cooldown=%s k6_cpus=%s\n' "${ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_COMMAND_TIMEOUT:-app-default}" "${ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_BREAKER_COOLDOWN:-app-default}" "${K6_CPUS:-1}" > "$run_dir/gate-config.txt"
  printf '%s %s\n' "$k6_start" "$k6_end" > "$run_dir/k6-window.txt"
  printf '%s\n' "$run_id" > "$run_dir/run-id.txt"
  printf '%s\n' "$duration_seconds" > "$run_dir/duration-seconds.txt"
  dc logs --no-color app > "$run_dir/app.log" 2>&1 || true
grep -ciE 'ERROR|Exception' "$run_dir/app.log" > "$run_dir/app-error-lines.txt" || true
  dc down -v --remove-orphans >/dev/null 2>&1
done

python3 "$repo_root/load-tests/hot-sku-coordinator-report.py" "$out" "$stock"
printf 'results=%s\n' "$out"
