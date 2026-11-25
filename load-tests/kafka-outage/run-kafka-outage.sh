#!/usr/bin/env bash
# Kafka-outage runs against the reservation event relay on a real stack:
# order PostgreSQL + one Kafka broker (KRaft) + two application JVMs with the relay enabled.
#
# For every condition it brings up ONE isolated compose project, creates the topic, then runs
# RUNS repetitions in it. A repetition sends a constant-rate event stream (event_producer.py),
# injects one broker fault FAULT_AT seconds in, waits for the relay to drain, reads the whole
# topic and compares it with the event log table (outage_audit.py). The project is removed
# at the end of the condition. Repetitions of one condition share the application JVMs, the
# database and the topic; each repetition is told apart by its own workflow set.
# See docs/03-experiments/EXP-089-kafka-outage-repeated.md.
#
# Usage (build the jar once: ./gradlew :atlas-application:bootJar):
#   load-tests/kafka-outage/run-kafka-outage.sh
#   CONDITIONS="control pause20" RUNS=5 load-tests/kafka-outage/run-kafka-outage.sh
#
# CONDITIONS (default: all)
#   control   no fault
#   stop10    docker stop (graceful), docker start after 10 s
#   kill10    docker kill (SIGKILL), docker start after 10 s
#   pause8    docker pause 8 s (shorter than the 10 s send-timeout)
#   pause20   docker pause 20 s (longer than send-timeout, shorter than the 30 s lease)
#   pause45   docker pause 45 s (longer than send-timeout and lease)
#   kill45    docker kill, docker start after 45 s
#   delay2s   tc netem delay 2000ms 500ms on the broker's outgoing traffic for 20 s
#   delay6s   tc netem delay 6000ms 500ms for 20 s (above the producer request timeout of 5 s)
#   loss25    tc netem loss 25% for 20 s
# Variables: APP_JAR (jar the Docker VM can read; default: the bootJar output), RUNS=5 RATE=20
#   FAULT_AT=10 TAIL=20 DRAIN_TIMEOUT=90 TAG=kafka-outage OUT=... and the relay settings
#   RELAY_SEND_TIMEOUT / RELAY_LEASE_DURATION / RELAY_BATCH_SIZE / RELAY_POLL_INTERVAL.
# Results: .local/kafka-outage/<timestamp>/<condition>/run-<n>/ (git-ignored). No secret is
# written there: every secret is generated per invocation and lives only in this process.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
here="$repo_root/load-tests/kafka-outage"
compose_file="$here/compose.yaml"
conditions="${CONDITIONS:-control stop10 kill10 pause8 pause20 pause45 kill45 delay2s delay6s loss25}"
runs="${RUNS:-5}"
rate="${RATE:-20}"
fault_at="${FAULT_AT:-10}"
tail_seconds="${TAIL:-20}"
drain_timeout="${DRAIN_TIMEOUT:-90}"
tag="${TAG:-kafka-outage}"
topic="atlas.order-inventory.events"
timestamp="$(date +%Y%m%d-%H%M%S)"
out="${OUT:-$repo_root/.local/kafka-outage/$timestamp}"
nettools_image="ghcr.io/alexei-led/pumba-alpine-nettools:latest"

APP_JAR="${APP_JAR:-$repo_root/atlas-application/build/libs/atlas-application.jar}"
[[ -f "$APP_JAR" ]] || { echo "missing $APP_JAR; run ./gradlew :atlas-application:bootJar" >&2; exit 2; }
export APP_JAR
for condition in $conditions; do
  case "$condition" in
    control|stop10|kill10|pause8|pause20|pause45|kill45|delay2s|delay6s|loss25) ;;
    *) echo "unknown condition: $condition" >&2; exit 2 ;;
  esac
done
[[ "$runs" =~ ^[1-9][0-9]*$ && "$rate" =~ ^[1-9][0-9]*$ ]] || {
  echo "RUNS and RATE must be positive integers" >&2; exit 2; }

export EXP_TAG="$tag" TOPIC="$topic"
export DB_PASSWORD ATLAS_JWT_SIGNING_SECRET
project=""
dc() { docker compose -p "$project" -f "$compose_file" "$@"; }
sampler_pid=""

teardown() {
  [[ -n "$sampler_pid" ]] && kill "$sampler_pid" 2>/dev/null || true
  # sidecars only live for one tc command (--rm); remove strays by label as well
  local strays
  strays="$(docker ps -aq --filter "label=atlasexp=$tag" --filter "label=atlasexp-role=sidecar" 2>/dev/null || true)"
  [[ -z "$strays" ]] || docker rm -f $strays >/dev/null 2>&1 || true
  if [[ -n "$project" ]]; then
    dc down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap 'teardown' EXIT INT TERM

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
cid() { dc ps -aq "$1"; }

# tc command in a throwaway sidecar that shares the broker's network namespace
tc_in_broker() {
  docker run --rm --label "atlasexp=$tag" --label atlasexp-role=sidecar --cap-add NET_ADMIN \
    --net "container:$(cid kafka)" --entrypoint sh "$nettools_image" -c "$1"
}

# condition -> fault length in seconds
fault_length() {
  case "$1" in
    control) echo 10 ;; stop10|kill10) echo 10 ;; pause8) echo 8 ;; pause20) echo 20 ;;
    pause45|kill45) echo 45 ;; delay2s|delay6s|loss25) echo 20 ;;
  esac
}

# inject <condition> <run_dir>: writes fault.json. down_begin/down_end are the instants the
# broker could not have answered a request (absent for network shaping, where it still answers).
inject() {
  local condition="$1" run_dir="$2" kafka begin finish down_begin="" down_end="" length
  kafka="$(cid kafka)"; length="$(fault_length "$condition")"
  begin="$(now)"
  case "$condition" in
    control) sleep "$length" ;;
    stop10)
      docker stop -t 30 "$kafka" >/dev/null; down_begin="$(now)"
      docker inspect -f '{{.State.Status}}' "$kafka" >"$run_dir/state-during-fault.txt"
      sleep "$length"; down_end="$(now)"; docker start "$kafka" >/dev/null ;;
    kill10|kill45)
      docker kill "$kafka" >/dev/null; down_begin="$(now)"
      docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' "$kafka" >"$run_dir/state-during-fault.txt"
      sleep "$length"; down_end="$(now)"; docker start "$kafka" >/dev/null ;;
    pause8|pause20|pause45)
      docker pause "$kafka" >/dev/null; down_begin="$(now)"
      docker inspect -f '{{.State.Status}} paused={{.State.Paused}}' "$kafka" >"$run_dir/state-during-fault.txt"
      sleep "$length"; down_end="$(now)"; docker unpause "$kafka" >/dev/null ;;
    delay2s)
      tc_in_broker 'tc qdisc add dev eth0 root netem delay 2000ms 500ms && tc qdisc show dev eth0' >"$run_dir/state-during-fault.txt"
      sleep "$length"; tc_in_broker 'tc qdisc del dev eth0 root' ;;
    delay6s)
      tc_in_broker 'tc qdisc add dev eth0 root netem delay 6000ms 500ms && tc qdisc show dev eth0' >"$run_dir/state-during-fault.txt"
      sleep "$length"; tc_in_broker 'tc qdisc del dev eth0 root' ;;
    loss25)
      tc_in_broker 'tc qdisc add dev eth0 root netem loss 25% && tc qdisc show dev eth0' >"$run_dir/state-during-fault.txt"
      sleep "$length"; tc_in_broker 'tc qdisc del dev eth0 root' ;;
  esac
  finish="$(now)"
  jq -n --arg fault "$condition" --argjson begin "$begin" --argjson end "$finish" \
    --arg db "$down_begin" --arg de "$down_end" \
    '{fault:$fault, begin:$begin, end:$end}
     + (if $db != "" then {down_begin:($db|tonumber), down_end:($de|tonumber)} else {} end)' \
    >"$run_dir/fault.json"
}

run_one() {  # run_one <condition> <n>
  local condition="$1" n="$2" run_dir run_id duration length producer_pid run_started
  run_dir="$out/$condition/run-$n"; mkdir -p "$run_dir"
  run_id="$(openssl rand -hex 4)"
  length="$(fault_length "$condition")"
  duration=$((fault_at + length + tail_seconds)); (( duration < 40 )) && duration=40
  echo "--- condition=$condition run=$n run_id=$run_id duration=${duration}s"
  run_started="$(now)"
  { printf 'time=%s\n' "$(date -u +%FT%TZ)"; uptime; } >"$run_dir/host-before.txt"
  python3 "$here/outage_audit.py" sample --pg "$(cid order-postgres)" --out "$run_dir/samples.csv" &
  sampler_pid=$!
  python3 "$here/event_producer.py" --container "$(cid order-postgres)" --rate "$rate" \
    --duration "$duration" --run-id "$run_id" --plan "$run_dir/plan.json" &
  producer_pid=$!
  sleep "$fault_at"
  inject "$condition" "$run_dir"
  wait "$producer_pid" || echo "producer exited non-zero (see plan.json)" >&2
  python3 "$here/outage_audit.py" audit --pg "$(cid order-postgres)" --kafka "$(cid kafka)" \
    --topic "$topic" --plan "$run_dir/plan.json" --fault "$run_dir/fault.json" \
    --samples "$run_dir/samples.csv" --drain-timeout "$drain_timeout" --out "$run_dir/result.json"
  kill "$sampler_pid" 2>/dev/null || true; wait "$sampler_pid" 2>/dev/null || true; sampler_pid=""
  { printf 'time=%s\n' "$(date -u +%FT%TZ)"; uptime
    docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
      $(docker ps -q --filter "label=atlasexp=$tag") 2>/dev/null || true; } >"$run_dir/host-after.txt"
  # application log volume of this run: how many lines, bytes and per-record producer errors
  # (LoggingProducerListener) the two applications wrote, plus a sample of relay/Kafka lines
  local log_file lines=0 bytes=0 record_errors=0 relay_warns=0
  for app in app1 app2; do
    log_file="$run_dir/$app-all.log"
    docker logs --since "${run_started%.*}" "$(cid "$app")" >"$log_file" 2>&1 || true
    lines=$((lines + $(wc -l <"$log_file")))
    bytes=$((bytes + $(wc -c <"$log_file")))
    record_errors=$((record_errors + $(grep -c 'Exception thrown when sending a message' "$log_file" || true)))
    relay_warns=$((relay_warns + $(grep -c 'event relay failed' "$log_file" || true)))
    grep -i -E 'relay|kafka|producer' "$log_file" | head -100 >"$run_dir/$app-relay.log" || true
    rm -f "$log_file"
  done
  jq -n --argjson lines "$lines" --argjson bytes "$bytes" --argjson record_errors "$record_errors" \
    --argjson relay_warns "$relay_warns" \
    '{app_log_lines:$lines, app_log_bytes:$bytes, producer_record_error_lines:$record_errors, relay_failed_warn_lines:$relay_warns}' \
    >"$run_dir/log-volume.json"
  docker inspect -f 'kafka restarts={{.RestartCount}} started={{.State.StartedAt}} oom={{.State.OOMKilled}}' \
    "$(cid kafka)" >"$run_dir/kafka-state-after.txt" || true
}

for condition in $conditions; do
  project="atlasexp-$tag-$condition-$(openssl rand -hex 3)"
  DB_PASSWORD="$(openssl rand -hex 16)"
  ATLAS_JWT_SIGNING_SECRET="$(openssl rand -hex 32)"
  mkdir -p "$out/$condition"
  echo "=== condition=$condition project=$project"
  dc up -d --wait --wait-timeout 180 order-postgres kafka >"$out/$condition/compose-up.log" 2>&1 \
    || { echo "stack failed to start; see $out/$condition/compose-up.log" >&2; teardown; project=""; continue; }
  docker exec "$(cid kafka)" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --topic "$topic" --partitions 3 --replication-factor 1 >"$out/$condition/topic.txt" 2>&1
  docker exec "$(cid kafka)" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic "$topic" >>"$out/$condition/topic.txt" 2>&1
  dc up -d --wait --wait-timeout 240 app1 app2 >>"$out/$condition/compose-up.log" 2>&1 \
    || { echo "applications failed to start; see $out/$condition/compose-up.log" >&2; teardown; project=""; continue; }
  sleep 5  # let the relay of both applications claim its first lease
  for ((n = 1; n <= runs; n++)); do
    run_one "$condition" "$n" || echo "run $n of $condition failed" >&2
  done
  teardown; project=""
done
echo "results: $out"
python3 "$here/outage_audit.py" summarize "$out" | tee "$out/summary.md"
