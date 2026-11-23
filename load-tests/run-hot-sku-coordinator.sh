#!/usr/bin/env bash
# Hot-SKU flash-sale baseline for the PostgreSQL-only order coordinator.
#
# For every offered rate it brings up a FRESH isolated compose project (own network and volumes, throwaway
# secrets, fixed CPU/memory limits, free loopback ports), warms the JVM, measures a constant-arrival-rate flood of
# single-unit orders against ONE seeded SKU (stock << orders, so most orders are rejected), collects the order-DB
# cost and the application saturation signals, checks the stock invariants, and tears the project down.
#
# Usage: load-tests/run-hot-sku-coordinator.sh            # rates 100 300 500 1000 2000, 20s each, stock 100
#        HOTSKU_COORD_RATES="500 1000" HOTSKU_COORD_STOCK=100 load-tests/run-hot-sku-coordinator.sh
# Results: .local/hot-sku-coordinator/<timestamp>/ (git-ignored). No secret is ever printed or written there.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/load-tests/compose-hot-sku-coordinator.yaml"
rates="${HOTSKU_COORD_RATES:-100 300 500 1000 2000}"
duration="${HOTSKU_COORD_DURATION:-20s}"
stock="${HOTSKU_COORD_STOCK:-100}"
warmup_rate="${HOTSKU_COORD_WARMUP_RATE:-100}"
warmup_duration="${HOTSKU_COORD_WARMUP_DURATION:-10s}"
idle_seconds="${HOTSKU_COORD_IDLE_SECONDS:-10}"
settle_timeout="${HOTSKU_COORD_SETTLE_TIMEOUT_SECONDS:-60}"
# HOT_SKU_GATE=redis adds a Redis admission gate (redis:7.4-alpine) in front of the PostgreSQL workflow; default off.
# HOT_SKU_GATE=precheck uses the Redis-free availability pre-check gate (one location read per request, no order-DB write
# before admission, no redis service). redis and precheck are mutually exclusive.
gate="${HOT_SKU_GATE:-off}"
# HOTSKU_COORD_REDIS_KILL_AFTER=<seconds>: stop the redis container that many seconds after the measured k6 run starts.
redis_kill_after="${HOTSKU_COORD_REDIS_KILL_AFTER:-}"
# Redis gate hardening knobs (ISO-8601 durations, defaults equal the application defaults):
#   HOTSKU_COORD_GATE_COMMAND_TIMEOUT=PT0.2S  Redis command timeout
#   HOTSKU_COORD_GATE_BREAKER_COOLDOWN=PT2S   bypass window after a Redis failure
gate_command_timeout="${HOTSKU_COORD_GATE_COMMAND_TIMEOUT:-PT0.2S}"
gate_breaker_cooldown="${HOTSKU_COORD_GATE_BREAKER_COOLDOWN:-PT2S}"
for duration_value in "$gate_command_timeout" "$gate_breaker_cooldown"; do
  if [[ ! "$duration_value" =~ ^PT[0-9]+(\.[0-9]+)?[SM]$ ]]; then
    echo 'HOTSKU_COORD_GATE_COMMAND_TIMEOUT/BREAKER_COOLDOWN must be an ISO-8601 PT duration such as PT0.2S' >&2
    exit 2
  fi
done
export HOTSKU_COORD_GATE_COMMAND_TIMEOUT="$gate_command_timeout" HOTSKU_COORD_GATE_BREAKER_COOLDOWN="$gate_breaker_cooldown"
case "$gate" in
  off|redis|precheck) ;;
  *) echo "HOT_SKU_GATE must be off, redis or precheck" >&2; exit 2 ;;
esac
if [[ -n "$redis_kill_after" && ! "$redis_kill_after" =~ ^[0-9]+$ ]]; then
  echo 'HOTSKU_COORD_REDIS_KILL_AFTER must be an integer number of seconds' >&2; exit 2
fi
if [[ -n "$redis_kill_after" && "$gate" != "redis" ]]; then
  echo 'HOTSKU_COORD_REDIS_KILL_AFTER requires HOT_SKU_GATE=redis' >&2; exit 2
fi

[[ "$duration" =~ ^([0-9]+)s$ ]] || { echo 'HOTSKU_COORD_DURATION must look like 20s' >&2; exit 2; }
duration_seconds="${BASH_REMATCH[1]}"
for rate in $rates; do
  [[ "$rate" =~ ^[1-9][0-9]*$ ]] || { echo "invalid rate: $rate" >&2; exit 2; }
done

timestamp="$(date +%Y%m%d-%H%M%S)"
out="${HOTSKU_COORD_OUT:-$repo_root/.local/hot-sku-coordinator/$timestamp}"
mkdir -p "$out"
chmod 777 "$out"   # the k6 container runs as a non-root uid and writes its summary here

project="atlas-hotsku-coord-$(openssl rand -hex 3)"
free_port() {
  python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}
HOTSKU_COORD_APP_PORT="$(free_port)"
HOTSKU_COORD_MGMT_PORT="$(free_port)"
# Throwaway secrets: generated per invocation, kept only in this process environment.
HOTSKU_COORD_ORDER_DB_PASSWORD="$(openssl rand -hex 16)"
HOTSKU_COORD_LOCATION_DB_PASSWORD="$(openssl rand -hex 16)"
HOTSKU_COORD_LOCATION_TOKEN="$(openssl rand -hex 24)"
HOTSKU_COORD_API_TOKEN="$(openssl rand -hex 24)"
HOTSKU_COORD_JWT_SECRET="$(openssl rand -hex 32)"
HOTSKU_COORD_PROJECT="$project"
HOTSKU_COORD_RESULTS_DIR="$out"
export HOTSKU_COORD_APP_PORT HOTSKU_COORD_MGMT_PORT HOTSKU_COORD_ORDER_DB_PASSWORD \
  HOTSKU_COORD_LOCATION_DB_PASSWORD HOTSKU_COORD_LOCATION_TOKEN HOTSKU_COORD_API_TOKEN \
  HOTSKU_COORD_JWT_SECRET HOTSKU_COORD_PROJECT HOTSKU_COORD_RESULTS_DIR

HOTSKU_COORD_GATE_ENABLED=false
HOTSKU_COORD_GATE_PRECHECK_ENABLED=false
gate_profile=()
services=(order-postgres order-mailpit location-postgres location app)
if [[ "$gate" == "redis" ]]; then
  HOTSKU_COORD_GATE_ENABLED=true
  gate_profile=(--profile gate)
  services=(redis "${services[@]}")
fi
if [[ "$gate" == "precheck" ]]; then
  HOTSKU_COORD_GATE_PRECHECK_ENABLED=true
fi
export HOTSKU_COORD_GATE_ENABLED HOTSKU_COORD_GATE_PRECHECK_ENABLED
dc() { docker compose -p "$project" -f "$compose_file" --profile load ${gate_profile[@]+"${gate_profile[@]}"} "$@"; }
order_psql() { dc exec -T order-postgres psql -X -U atlas -d atlas_order -At -F '|' -c "$1"; }
location_psql() { dc exec -T location-postgres psql -X -U atlas_inventory -d atlas_inventory -At -F '|' -c "$1"; }
mgmt_url="http://127.0.0.1:${HOTSKU_COORD_MGMT_PORT}"

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
    if curl -fsS --max-time 2 "http://127.0.0.1:${HOTSKU_COORD_APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
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
    -e RUN_ID="$run_id" -e COORDINATOR_RATE="$rate" -e COORDINATOR_DURATION="$length" \
    -e COORDINATOR_STOCK="$stock" -e SUMMARY_FILE="/results/$summary" \
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
  printf 'command_timeout=%s breaker_cooldown=%s k6_cpus=%s\n' "$gate_command_timeout" "$gate_breaker_cooldown" "${HOTSKU_COORD_K6_CPUS:-1}" > "$run_dir/gate-config.txt"
  printf '%s %s\n' "$k6_start" "$k6_end" > "$run_dir/k6-window.txt"
  printf '%s\n' "$run_id" > "$run_dir/run-id.txt"
  printf '%s\n' "$duration_seconds" > "$run_dir/duration-seconds.txt"
  dc logs --no-color app 2>/dev/null | grep -ciE 'ERROR|Exception' > "$run_dir/app-error-lines.txt" || true
  dc down -v --remove-orphans >/dev/null 2>&1
done

python3 "$repo_root/load-tests/hot-sku-coordinator-report.py" "$out" "$stock"
printf 'results=%s\n' "$out"
