#!/usr/bin/env bash
# Fault-injection runs against the order-inventory reservation workflow on a real stack:
# application JVM + order PostgreSQL + two inventory-location JVMs with their own PostgreSQL.
#
# For every (fault, repetition) it brings up a FRESH isolated compose project, warms the JVMs,
# sends a constant-rate load, injects one fault three seconds in, waits for the workflows to
# converge, audits the three databases (audit_invariants.py) and removes the project.
# The audit counts only this run's rows. See docs/03-experiments/EXP-085-fault-injection-repeated.md.
#
# Usage (build the jars once: ./gradlew :atlas-application:bootJar :atlas-inventory-location:bootJar):
#   load-tests/fault-injection/run-fault-injection.sh
#   FAULTS="none order-db-kill" RUNS=5 load-tests/fault-injection/run-fault-injection.sh
#
# FAULTS (default: all)
#   none               control, no fault
#   order-db-restart   docker restart (graceful stop, normally no WAL replay: the log decides)
#   order-db-kill      docker kill of the postmaster, start after FAULT_FOR seconds (crash recovery)
#   order-db-crash     SIGKILL the WAL writer; the postmaster restarts all backends in place
#   app-cpu            CPU-bound processes inside the application cgroup for FAULT_FOR seconds
#   app-cpu-throttle   docker update --cpus 0.2 on the application (hard CFS quota) for FAULT_FOR seconds
#   app-memory         allocate tmpfs inside the application cgroup up to ~64 MiB below its limit
#   app-oom            same, but 128 MiB over the limit: the kernel OOM-kills the JVM
#   location-b-kill    docker kill of location B, start after FAULT_FOR seconds
#   location-b-partition  Toxiproxy blackhole (timeout toxic) between the application and location B
#   net-delay          tc netem delay on the application<->location B path (NETEM_DELAY, default 300ms 50ms)
#   net-loss           tc netem loss on the same path (NETEM_LOSS, default 30% plus 20ms delay)
#   order-db-disk-full fill the (tmpfs, ORDER_DATA_SIZE) order database volume for FAULT_FOR seconds
#   app-kill           docker kill of the first application, start after FAULT_FOR seconds
#   order-db-recreate  docker compose up --force-recreate of the order database (IP may change)
# Options (EXP-087): LABEL (condition directory name, default the fault), APPS=2 (second application on the
#   same order DB, load round robin), PROXY=1 (Toxiproxy in front of location B, implied by
#   location-b-partition), RETRY=1 (resend failed requests with the same key, then every key once),
#   RECOVERY_LEASE (e.g. PT10S), IDLE_BEFORE_LOAD (seconds between warm-up and load),
#   DB_BY_IP=1 (application connects to the order DB by container IP), APP_NEGATIVE_DNS_TTL0=1
#   (JVM networkaddress.cache.negative.ttl=0).
# Variables: APP_JAR / LOCATION_JAR (jars the Docker VM can read; default: the bootJar outputs), RUNS=5 RATE=40 DURATION=14 FAULT_AT=3 FAULT_FOR=6 CONVERGE_TIMEOUT=90 TAG=fault-injection OUT=...
# Results: .local/fault-injection/<timestamp>/<fault>/run-<n>/ (git-ignored). No secret is written there:
# every secret is generated per invocation and lives only in this process's environment.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
here="$repo_root/load-tests/fault-injection"
compose_file="$here/compose.yaml"
faults="${FAULTS:-none order-db-restart order-db-kill order-db-crash app-cpu app-memory app-oom location-b-kill}"
runs="${RUNS:-5}"
rate="${RATE:-40}"
duration="${DURATION:-14}"
fault_at="${FAULT_AT:-3}"
fault_for="${FAULT_FOR:-6}"
converge_timeout="${CONVERGE_TIMEOUT:-90}"
tag="${TAG:-fault-injection}"
apps="${APPS:-1}"
retry_mode="${RETRY:-0}"
proxy="${PROXY:-0}"
idle_before_load="${IDLE_BEFORE_LOAD:-0}"
db_by_ip="${DB_BY_IP:-0}"
netem_delay="${NETEM_DELAY:-delay 300ms 50ms}"
netem_loss="${NETEM_LOSS:-loss 30% delay 20ms}"
nettools_image="${NETTOOLS_IMAGE:-ghcr.io/alexei-led/pumba-alpine-nettools:latest}"
timestamp="$(date +%Y%m%d-%H%M%S)"
out="${OUT:-$repo_root/.local/fault-injection/$timestamp}"

APP_JAR="${APP_JAR:-$repo_root/atlas-application/build/libs/atlas-application.jar}"
LOCATION_JAR="${LOCATION_JAR:-$repo_root/atlas-inventory-location/build/libs/atlas-inventory-location.jar}"
for jar in "$APP_JAR" "$LOCATION_JAR"; do
  [[ -f "$jar" ]] || {
    echo "missing $jar; run ./gradlew :atlas-application:bootJar :atlas-inventory-location:bootJar" >&2
    exit 2
  }
done
export APP_JAR LOCATION_JAR
for fault in $faults; do
  case "$fault" in
    none|order-db-restart|order-db-kill|order-db-crash|app-cpu|app-cpu-throttle|app-memory|app-oom|location-b-kill|location-b-partition|net-delay|net-loss|order-db-disk-full|app-kill|order-db-recreate) ;;
    *) echo "unknown fault: $fault" >&2; exit 2 ;;
  esac
done
[[ -z "${LABEL:-}" || "$faults" != *" "* ]] || { echo "LABEL needs exactly one fault" >&2; exit 2; }
[[ "$apps" =~ ^[12]$ ]] || { echo "APPS must be 1 or 2" >&2; exit 2; }
[[ "$runs" =~ ^[1-9][0-9]*$ && "$rate" =~ ^[1-9][0-9]*$ && "$duration" =~ ^[1-9][0-9]*$ ]] || {
  echo "RUNS, RATE and DURATION must be positive integers" >&2; exit 2; }

export EXP_TAG="$tag"
export RECOVERY_LEASE="${RECOVERY_LEASE:-PT30S}"
export APP_JAVA_TOOL_OPTIONS=""
[[ "${APP_NEGATIVE_DNS_TTL0:-0}" == 1 ]] && \
  APP_JAVA_TOOL_OPTIONS="-Djava.security.properties=/app/jvm-negative-dns-ttl0.security"
compose_files=(-f "$compose_file")
case "$faults" in *order-db-disk-full*) compose_files+=(-f "$here/compose.disk-full.yaml") ;; esac
case "$faults" in *location-b-partition*) proxy=1 ;; esac
profiles=""
[[ "$apps" == 2 ]] && profiles="second-app"
[[ "$proxy" == 1 ]] && profiles="${profiles:+$profiles,}toxiproxy"
export COMPOSE_PROFILES="$profiles"
[[ "$proxy" == 1 ]] && export LOCATION_B_URL="http://toxiproxy:8091"
export DB_PASSWORD INVENTORY_LOCATION_SERVICE_TOKEN ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN ATLAS_JWT_SIGNING_SECRET
project=""
toxiproxy_api=""
dc() { docker compose -p "$project" "${compose_files[@]}" "$@"; }

teardown() {
  if [[ -n "$project" ]]; then
    dc down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap 'teardown' EXIT INT TERM

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
cid() { dc ps -aq "$1"; }

stack_snapshot() {
  { printf 'time=%s\n' "$(date -u +%FT%TZ)"; uptime; docker stats --no-stream \
      --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' $(docker ps -q --filter "label=atlasexp=$tag") 2>/dev/null || true; } > "$1"
}

pg_markers() {  # counts of crash-recovery evidence in a PostgreSQL log
  local file="$1"
  jq -n \
    --argjson not_properly_shut_down "$(grep -c 'not properly shut down' "$file" || true)" \
    --argjson redo_starts "$(grep -c 'redo starts at' "$file" || true)" \
    --argjson redo_done "$(grep -c 'redo done at' "$file" || true)" \
    --argjson shut_down_at "$(grep -c 'database system was shut down at' "$file" || true)" \
    --argjson crash_restart "$(grep -c 'terminating any other active server processes\|all server processes terminated' "$file" || true)" \
    --argjson no_space "$(grep -c 'No space left on device' "$file" || true)" \
    --argjson panic "$(grep -c 'PANIC' "$file" || true)" \
    '{not_properly_shut_down:$not_properly_shut_down, redo_starts:$redo_starts, redo_done:$redo_done, shut_down_at:$shut_down_at, crash_restart:$crash_restart, no_space_left:$no_space, panic:$panic}'
}

container_ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1"; }

# netem_apply <container> <destination ip> <netem arguments>: impair only packets TO that IP, from a
# short-lived sidecar that shares the container's network namespace (labelled like the stack, --rm).
netem_apply() {
  docker run --rm --label "atlasexp=$tag" --network "container:$1" --cap-add NET_ADMIN --entrypoint sh \
    "$nettools_image" -c "tc qdisc add dev eth0 root handle 1: prio && \
      tc qdisc add dev eth0 parent 1:3 handle 30: netem $3 && \
      tc filter add dev eth0 protocol ip parent 1:0 prio 1 u32 match ip dst $2/32 flowid 1:3 && \
      tc qdisc show dev eth0" > "$run_dir/netem-$(basename "$(docker inspect -f '{{.Name}}' "$1")").txt" 2>&1
}
netem_clear() {
  docker run --rm --label "atlasexp=$tag" --network "container:$1" --cap-add NET_ADMIN --entrypoint sh \
    "$nettools_image" -c "tc qdisc del dev eth0 root" >/dev/null 2>&1 || true
}

inject() {  # inject <fault> <run_dir>; writes fault.json with the window in epoch seconds
  local fault="$1" run_dir="$2" begin finish order app
  order="$(cid order-postgres)"; app="$(cid app)"
  begin="$(now)"
  case "$fault" in
    none) sleep "$fault_for" ;;
    order-db-restart) docker restart -t 10 "$order" >/dev/null ;;
    order-db-kill)
      docker kill "$order" >/dev/null; sleep "$fault_for"; docker start "$order" >/dev/null ;;
    order-db-crash)
      docker exec "$order" sh -c 'for d in /proc/[0-9]*; do c=$(tr "\0" " " < "$d/cmdline" 2>/dev/null); case "$c" in "postgres: walwriter"*) kill -9 "${d#/proc/}"; break;; esac; done' ;;
    app-cpu)
      docker exec "$app" sh -c "for i in 1 2 3 4 5 6; do timeout $fault_for sh -c 'while :; do :; done' & done; wait" 2>/dev/null || true ;;
    app-cpu-throttle)
      docker update --cpus 0.2 "$app" >/dev/null; sleep "$fault_for"; docker update --cpus 2 "$app" >/dev/null ;;
    app-memory|app-oom)
      local limit used mb margin=-64
      [[ "$fault" == "app-oom" ]] && margin=128
      limit="$(docker exec "$app" cat /sys/fs/cgroup/memory.max)"
      used="$(docker exec "$app" awk '/^(anon|shmem|kernel) /{s+=$2} END{print s}' /sys/fs/cgroup/memory.stat)"
      mb=$(( (limit - used) / 1048576 + margin ))
      printf '{"limit_bytes":%s,"unreclaimable_bytes":%s,"stress_mb":%s}\n' "$limit" "$used" "$mb" > "$run_dir/memory-stress.json"
      docker exec "$app" sh -c "head -c ${mb}M /dev/zero > /dev/shm/stress; sleep $fault_for; rm -f /dev/shm/stress" 2>/dev/null || true ;;
    location-b-kill)
      docker kill "$(cid location-b)" >/dev/null; sleep "$fault_for"; docker start "$(cid location-b)" >/dev/null ;;
    location-b-partition)
      for direction in upstream downstream; do  # timeout=0: the connection stays open and no data passes
        curl -fsS -X POST "$toxiproxy_api/proxies/location-b/toxics" \
          -d "{\"name\":\"blackhole-$direction\",\"type\":\"timeout\",\"stream\":\"$direction\",\"attributes\":{\"timeout\":0}}" >/dev/null
      done
      sleep "$fault_for"
      for direction in upstream downstream; do
        curl -fsS -X DELETE "$toxiproxy_api/proxies/location-b/toxics/blackhole-$direction" >/dev/null
      done ;;
    net-delay|net-loss)
      local netem="$netem_delay"; [[ "$fault" == net-loss ]] && netem="$netem_loss"
      local locb="$(cid location-b)"
      local app_ip locb_ip
      app_ip="$(container_ip "$app")"; locb_ip="$(container_ip "$locb")"
      printf '{"netem":"%s","app_ip":"%s","location_b_ip":"%s"}\n' "$netem" "$app_ip" "$locb_ip" > "$run_dir/netem.json"
      netem_apply "$app" "$locb_ip" "$netem"; netem_apply "$locb" "$app_ip" "$netem"
      sleep "$fault_for"
      netem_clear "$app"; netem_clear "$locb" ;;
    order-db-disk-full)
      docker exec "$order" sh -c 'df -k /var/lib/postgresql | tail -1' > "$run_dir/disk-before.txt" 2>&1 || true
      docker exec "$order" sh -c 'dd if=/dev/zero of=/var/lib/postgresql/filler bs=1M 2>/dev/null; df -k /var/lib/postgresql | tail -1' \
        > "$run_dir/disk-full.txt" 2>&1 || true
      sleep "$fault_for"
      docker exec "$order" sh -c 'rm -f /var/lib/postgresql/filler; df -k /var/lib/postgresql | tail -1' \
        > "$run_dir/disk-freed.txt" 2>&1 || true ;;
    app-kill)
      docker kill "$app" >/dev/null; sleep "$fault_for"; docker start "$app" >/dev/null ;;
    order-db-recreate)
      docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$order" > "$run_dir/order-ip-before.txt"
      dc up -d --force-recreate --no-deps order-postgres >/dev/null 2>&1
      docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(cid order-postgres)" > "$run_dir/order-ip-after.txt" ;;
  esac
  finish="$(now)"
  jq -n --arg fault "$fault" --argjson begin "$begin" --argjson finish "$finish" \
    '{fault:$fault, begin:$begin, end:$finish}' > "$run_dir/fault.json"
}

run_one() {  # run_one <fault> <n>
  local fault="$1" n="$2" run_dir run_id warm_id start_at load_end since replay_args=()
  run_dir="$out/${LABEL:-$fault}/run-$n"; mkdir -p "$run_dir"
  unset ORDER_DB_HOST
  project="atlasexp-$tag-$(openssl rand -hex 3)"
  DB_PASSWORD="$(openssl rand -hex 16)"
  INVENTORY_LOCATION_SERVICE_TOKEN="$(openssl rand -hex 24)"
  ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN="$(openssl rand -hex 24)"
  ATLAS_JWT_SIGNING_SECRET="$(openssl rand -hex 32)"
  echo "=== fault=$fault run=$n project=$project"
  if [[ "$db_by_ip" == 1 ]]; then  # the application connects to the order database by container IP
    dc up -d --wait --wait-timeout 120 --no-deps order-postgres >"$run_dir/compose-up-order.log" 2>&1 || true
    export ORDER_DB_HOST="$(container_ip "$(cid order-postgres)")"
  fi
  dc up -d --wait --wait-timeout 300 >"$run_dir/compose-up.log" 2>&1 || {
    echo "stack did not become healthy; see $run_dir/compose-up.log" >&2
    dc logs --no-color >"$run_dir/stack-failure.log" 2>&1 || true
    teardown; project=""
    return 1; }
  local app_url loc_a_url loc_b_url
  app_url="http://127.0.0.1:$(dc port app 8080 | awk -F: '{print $NF}')/api/internal/order-inventory/reservations"
  if [[ "$apps" == 2 ]]; then
    app_url="$app_url,http://127.0.0.1:$(dc port app2 8080 | awk -F: '{print $NF}')/api/internal/order-inventory/reservations"
  fi
  loc_a_url="http://127.0.0.1:$(dc port location-a 8090 | awk -F: '{print $NF}')"
  loc_b_url="http://127.0.0.1:$(dc port location-b 8090 | awk -F: '{print $NF}')"

  if [[ "$proxy" == 1 ]]; then
    toxiproxy_api="http://127.0.0.1:$(dc port toxiproxy 8474 | awk -F: '{print $NF}')"
    curl -fsS -X POST "$toxiproxy_api/proxies" -d @"$here/toxiproxy-location-b.json" >"$run_dir/toxiproxy-proxy.json"
  fi
  warm_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  python3 "$here/reservation_load.py" seed --run-id "$warm_id" --capacity 1000 \
    --location-a-url "$loc_a_url" --location-b-url "$loc_b_url"
  python3 "$here/reservation_load.py" load --run-id "$warm_id" --url "$app_url" --rate 20 --duration 5 \
    --out "$run_dir/warmup.jsonl" >/dev/null

  run_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  python3 "$here/reservation_load.py" seed --run-id "$run_id" --capacity $(( rate * duration * 2 )) \
    --location-a-url "$loc_a_url" --location-b-url "$loc_b_url"
  [[ "$idle_before_load" -gt 0 ]] && sleep "$idle_before_load"
  stack_snapshot "$run_dir/env-before.txt"
  # State.OOMKilled is reset when the restart policy restarts the container, so count kernel OOM events instead.
  docker events --since "$(date +%s)" --filter event=oom --filter "label=com.docker.compose.project=$project" \
    >"$run_dir/oom-events.txt" 2>/dev/null &
  local events_pid=$!
  start_at="$(python3 -c 'import time; print(f"{time.time()+1.5:.3f}")')"
  ( sleep "$(python3 -c "import time; print(max(0, $start_at + $fault_at - time.time()))")"; inject "$fault" "$run_dir" ) &
  local fault_pid=$!
  load_end="$(python3 "$here/reservation_load.py" load --run-id "$run_id" --url "$app_url" \
    --rate "$rate" --duration "$duration" --start-at "$start_at" --out "$run_dir/requests.jsonl" \
    | jq -r '.end_at')"
  wait "$fault_pid" || true
  since="$load_end"
  if [[ "$retry_mode" == 1 ]]; then  # same-key retries after the fault, then every key once more
    since="$(python3 "$here/reservation_load.py" replay --run-id "$run_id" --url "$app_url" \
      --requests "$run_dir/requests.jsonl" --out "$run_dir/replay.jsonl" | jq -r '.end_at')"
    replay_args=(--replay "$run_dir/replay.jsonl")
  fi

  python3 "$here/audit_invariants.py" audit --run-id "$run_id" --requests "$run_dir/requests.jsonl" \
    --order-container "$(cid order-postgres)" --location-a-container "$(cid location-a-postgres)" \
    --location-b-container "$(cid location-b-postgres)" --since "$since" ${replay_args[@]+"${replay_args[@]}"} \
    --converge-timeout "$converge_timeout" --fault-file "$run_dir/fault.json" \
    --out "$run_dir/result.json" >"$run_dir/audit.log" 2>&1 || true  # a violated invariant is data, not a runner failure
  cat "$run_dir/audit.log"
  stack_snapshot "$run_dir/env-after.txt"

  kill "$events_pid" 2>/dev/null || true
  dc logs --no-color order-postgres >"$run_dir/order-postgres.log" 2>&1 || true
  dc logs --no-color app >"$run_dir/app.log" 2>&1 || true
  [[ "$apps" == 2 ]] && { dc logs --no-color app2 >"$run_dir/app2.log" 2>&1 || true; }
  dc logs --no-color location-b >"$run_dir/location-b.log" 2>&1 || true
  local containers='{}' service state
  for service in app app2 order-postgres location-a location-b; do
    state="$(docker inspect -f '{"restart_count":{{.RestartCount}},"oom_killed":{{.State.OOMKilled}},"exit_code":{{.State.ExitCode}},"running":{{.State.Running}}}' "$(cid "$service")" 2>/dev/null || echo '{}')"
    containers="$(jq -c --arg s "$service" --argjson v "$state" '. + {($s):$v}' <<<"$containers")"
  done
  jq -n --arg fault "$fault" --arg label "${LABEL:-$fault}" --argjson n "$n" --arg run_id "$run_id" --argjson rate "$rate" \
    --argjson duration "$duration" --argjson fault_at "$fault_at" --argjson fault_for "$fault_for" \
    --argjson apps "$apps" --argjson retry "$retry_mode" --argjson proxy "$proxy" --arg lease "$RECOVERY_LEASE" \
    --argjson idle "$idle_before_load" --argjson db_by_ip "$db_by_ip" --arg negative_ttl0 "${APP_NEGATIVE_DNS_TTL0:-0}" \
    --argjson containers "$containers" --argjson oom_events "$(wc -l <"$run_dir/oom-events.txt" | tr -d ' ')" --argjson pg "$(pg_markers "$run_dir/order-postgres.log")" \
    '{fault:$fault, label:$label, run:$n, run_id:$run_id, rate:$rate, duration:$duration, fault_at:$fault_at,
      fault_for:$fault_for, apps:$apps, retry:$retry, proxy:$proxy, recovery_lease:$lease,
      idle_before_load:$idle, db_by_ip:$db_by_ip, negative_dns_ttl0:$negative_ttl0, containers:$containers, oom_events:$oom_events, order_postgres_log:$pg}' > "$run_dir/meta.json"
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  project=""
  return 0
}

mkdir -p "$out"
for fault in $faults; do
  for n in $(seq 1 "$runs"); do
    run_one "$fault" "$n" || echo "run failed: fault=$fault run=$n" >&2
  done
done

leftover="$(docker ps -aq --filter "label=atlasexp=$tag" | wc -l | tr -d ' ')"
echo "leftover_containers=$leftover"
python3 "$here/audit_invariants.py" summarize --results "$out" | tee "$out/summary.md"
echo "results=$out"
