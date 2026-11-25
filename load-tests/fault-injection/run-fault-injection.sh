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
#   app-pause          docker pause of the first application for FAULT_FOR seconds (a JVM stall: the leases it holds age out)
#   order-db-pause     docker pause of the order database for FAULT_FOR seconds
#   order-db-wal-full  (WAL_FILL=0: the same volume and WAL burner without the filler, the control) ONLY pg_wal (a separate ext4 volume, WAL_SIZE_MB) is filled for FAULT_FOR seconds, while WAL_BURN_MB_S of WAL is written
#   location-b-syn-drop   iptables DROP of new TCP connections (SYN) into location B; established connections keep working
#   location-b-resp-drop  iptables DROP of everything location B sends to the application (requests arrive and take effect, answers vanish)
#   location-b-req-drop   iptables DROP of everything the application sends to location B (requests never arrive)
#   race               no fault: same-key pairs against two instances, variants c1 c2 c3 neg seq (reservation_load.py race; needs APPS=2 PROXY=1)
#   dns-kill / dns-restart / dns-recreate  order DB outage aligned to the JVM's last name lookup (DNS_OFFSET seconds after it, see EXP-095 (d))
# Options (EXP-087): LABEL (condition directory name, default the fault), APPS=2 (second application on the
#   same order DB, load round robin), PROXY=1 (Toxiproxy in front of location B, implied by
#   location-b-partition), RETRY=1 (resend failed requests with the same key, then every key once),
#   RECOVERY_LEASE (e.g. PT10S), IDLE_BEFORE_LOAD (seconds between warm-up and load),
#   LOAD_THREADS (client threads, default 64: with 40 requests/s a response slower than 1.6 s makes the
#   client thread-bound and requests leave late, see "sent later than scheduled" in the summary),
#   DB_BY_IP=1 (application connects to the order DB by container IP), APP_NEGATIVE_DNS_TTL0=1
#   (JVM networkaddress.cache.negative.ttl=0).
# Options (EXP-095): LOCATION_ACCESSLOG=1 (location JVMs log every request with source IP, begin/end ms and Idempotency-Key to stdout;
#   the runner then writes calls.json = duplicate remote calls per key), PLANT_DUP=1 (after the load the host sends one extra
#   reserve call with an existing key: the negative control of the duplicate-call detector), APP1_DELAY="delay 400ms" (netem on the
#   first application's path to both locations, from before the load until the end), MAILPIT=0 (no mailpit container, mail health off),
#   FI_DIR (directory the Docker VM can read that holds jvm-negative-dns-ttl0.security), WAL_SIZE_MB (default 128), WAL_BURN_MB_S,
#   DNS_TRACE=1 / DNS_OFFSET=<s> (tcpdump sidecar on the application's DNS traffic; the load starts so the fault lands DNS_OFFSET seconds
#   after the last observed successful lookup of order-postgres), DNS_REFRESH=1 (force a fresh lookup first), APP_IMAGE (default eclipse-temurin:25-jre).
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
load_threads="${LOAD_THREADS:-64}"
db_by_ip="${DB_BY_IP:-0}"
netem_delay="${NETEM_DELAY:-delay 300ms 50ms}"
netem_loss="${NETEM_LOSS:-loss 30% delay 20ms}"
nettools_image="${NETTOOLS_IMAGE:-ghcr.io/alexei-led/pumba-alpine-nettools:latest}"
mailpit="${MAILPIT:-1}"
app1_delay="${APP1_DELAY:-}"
plant_dup="${PLANT_DUP:-0}"
dns_trace="${DNS_TRACE:-0}"
dns_offset="${DNS_OFFSET:-}"
dns_refresh="${DNS_REFRESH:-0}"
wal_size_mb="${WAL_SIZE_MB:-128}"
wal_burn_mb_s="${WAL_BURN_MB_S:-0}"
wal_fill="${WAL_FILL:-1}"
sniffer_image="${SNIFFER_IMAGE:-atlasexp-h5-sniffer:1}"
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
    none|order-db-restart|order-db-kill|order-db-crash|app-cpu|app-cpu-throttle|app-memory|app-oom|location-b-kill|location-b-partition|net-delay|net-loss|order-db-disk-full|app-kill|order-db-recreate|app-pause|order-db-pause|order-db-wal-full|location-b-syn-drop|location-b-resp-drop|location-b-req-drop|dns-kill|dns-restart|dns-recreate|race) ;;
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
case "$faults" in *order-db-wal-full*) compose_files+=(-f "$here/compose.wal.yaml") ;; esac
case "$faults" in *location-b-partition*) proxy=1 ;; esac
profiles=""
[[ "$mailpit" == 1 ]] && profiles="mail"
[[ "$mailpit" == 1 ]] || export MAIL_HEALTH=false
[[ "$apps" == 2 ]] && profiles="${profiles:+$profiles,}second-app"
[[ "$proxy" == 1 ]] && profiles="${profiles:+$profiles,}toxiproxy"
export COMPOSE_PROFILES="$profiles"
case "${LOCATION_ACCESSLOG:-0}" in 1|true) export LOCATION_ACCESSLOG=true ;; *) export LOCATION_ACCESSLOG=false ;; esac
[[ -n "${FI_DIR:-}" ]] && export FI_DIR
[[ "$proxy" == 1 ]] && export LOCATION_B_URL="http://toxiproxy:8091"
export DB_PASSWORD INVENTORY_LOCATION_SERVICE_TOKEN ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN ATLAS_JWT_SIGNING_SECRET
project=""
toxiproxy_api=""
dc() { docker compose -p "$project" "${compose_files[@]}" "$@"; }

wal_mount=""
vm() { colima ssh -- sh -c "$1" </dev/null; }  # one command string, run as root-capable shell inside the Docker VM
# A size-limited ext4 volume for pg_wal: an image file loop-mounted inside the Docker VM, bind-mounted into the container.
# -m 0: no reserved blocks, so "full" means full for every user.
wal_volume_up() {
  wal_mount="/mnt/atlasexp-$tag-${project##*-}"
  vm "sudo mkdir -p /var/lib/atlasexp-h5 $wal_mount && sudo dd if=/dev/zero of=$wal_mount.img bs=1M count=$wal_size_mb 2>/dev/null && sudo mkfs.ext4 -q -F -m 0 $wal_mount.img && sudo mount -o loop $wal_mount.img $wal_mount" >/dev/null 2>&1 \
    || { echo "could not create the pg_wal volume in the Docker VM" >&2; return 1; }
  export WAL_DEVICE="$wal_mount"
}
wal_volume_down() {
  [[ -n "$wal_mount" ]] || return 0
  vm "sudo umount $wal_mount; sudo rmdir $wal_mount; sudo rm -f $wal_mount.img" >/dev/null 2>&1 || true
  wal_mount=""
}
teardown() {
  if [[ -n "$project" ]]; then
    dc down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  wal_volume_down
}
trap 'teardown' EXIT INT TERM

# The published host port of a restarted container can change (127.0.0.1::8080 is assigned again), so
# this is asked again before the retries, like a client behind a load balancer or service discovery.
resolve_app_url() {
  local path="/api/internal/order-inventory/reservations" urls
  urls="http://127.0.0.1:$(dc port app 8080 | awk -F: '{print $NF}')$path"
  if [[ "$apps" == 2 ]]; then
    urls="$urls,http://127.0.0.1:$(dc port app2 8080 | awk -F: '{print $NF}')$path"
  fi
  echo "$urls"
}

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

# fw <container> <shell command>: run iptables/ss in a short-lived sidecar that shares the container's network namespace.
fw() {
  local container="$1"; shift
  docker run --rm --label "atlasexp=$tag" --network "container:$container" --cap-add NET_ADMIN --entrypoint sh "$nettools_image" -c "$*"
}

# netem_apply_paths <container> <netem arguments> <destination ip>...: impair only packets to those IPs.
netem_apply_paths() {
  local container="$1" netem="$2" cmd ip; shift 2
  cmd="tc qdisc add dev eth0 root handle 1: prio && tc qdisc add dev eth0 parent 1:3 handle 30: netem $netem"
  for ip in "$@"; do
    cmd="$cmd && tc filter add dev eth0 protocol ip parent 1:0 prio 1 u32 match ip dst $ip/32 flowid 1:3"
  done
  fw "$container" "$cmd && tc qdisc show dev eth0"
}

pg_ready_wait() {  # <container>: print the epoch at which the database accepts connections (up to 150 s)
  local container="$1" i
  for i in $(seq 1 1500); do
    docker exec "$container" pg_isready -U atlas -d atlas_order -q >/dev/null 2>&1 && { now; return 0; }
    sleep 0.1
  done
  now
  return 1
}

# lease_audit_install: harness-side only (nothing is added to the application schema migrations). A trigger writes one row
# per lease claim: new token, old token and the old lease_until. A claim whose OLD token is not NULL took over a lease that was
# never released (the holder died, stalled or failed to finish): that is the only way a second instance acts on a workflow.
lease_audit_install() {
  order_psql "CREATE TABLE IF NOT EXISTS h5_lease_log(workflow_id uuid, key text, old_token uuid, new_token uuid, old_until timestamptz, at timestamptz);
    CREATE OR REPLACE FUNCTION h5_lease_trace() RETURNS trigger AS \$\$ BEGIN
      INSERT INTO h5_lease_log VALUES (NEW.workflow_id, NEW.idempotency_key, OLD.lease_token, NEW.lease_token, OLD.lease_until, clock_timestamp());
      RETURN NEW; END \$\$ LANGUAGE plpgsql;
    DROP TRIGGER IF EXISTS h5_lease_trace ON order_inventory_reservation_workflow;
    CREATE TRIGGER h5_lease_trace AFTER UPDATE ON order_inventory_reservation_workflow FOR EACH ROW
      WHEN (OLD.lease_token IS DISTINCT FROM NEW.lease_token AND NEW.lease_token IS NOT NULL) EXECUTE FUNCTION h5_lease_trace();" > /dev/null
}
lease_audit_collect() {  # <run id> <out file>
  order_psql "SELECT json_build_object('claims', count(*), 'workflows_claimed', count(DISTINCT workflow_id),
      'takeover_claims', count(*) FILTER (WHERE old_token IS NOT NULL),
      'takeover_workflows', count(DISTINCT workflow_id) FILTER (WHERE old_token IS NOT NULL),
      'takeover_max_per_workflow', coalesce(max(c), 0))
    FROM (SELECT workflow_id, old_token, count(*) OVER (PARTITION BY workflow_id, old_token IS NOT NULL) c FROM h5_lease_log WHERE key LIKE '$1-%') s" > "$2" || true
}
order_psql() { docker exec "$(cid order-postgres)" psql -X -U atlas -d atlas_order -At -c "$1" 2>/dev/null; }

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
    app-pause)
      # exposure: workflows that hold a live lease right before the stall (what could be taken over once it expires)
      order_psql "SELECT count(*) FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '$run_id-%' AND lease_until > now() AND workflow_state IN ('PENDING','RETRY_PENDING','COMPENSATING','COMPENSATION_REQUIRED','CANCEL_PENDING')" > "$run_dir/exposure-leased.txt"
      docker pause "$app" >/dev/null; sleep "$fault_for"; docker unpause "$app" >/dev/null ;;
    order-db-pause)
      docker pause "$order" >/dev/null; sleep "$fault_for"; docker unpause "$order" >/dev/null ;;
    location-b-syn-drop)
      local locb app_ip; locb="$(cid location-b)"
      fw "$locb" "iptables -I INPUT -p tcp --syn -j DROP" >/dev/null
      sleep "$(python3 -c "print($fault_for/2)")"
      fw "$app" "ss -tn state syn-sent | tail -n +2 | wc -l" > "$run_dir/syn-sent-sockets.txt" 2>&1 || true
      sleep "$(python3 -c "print($fault_for/2)")"
      fw "$locb" "iptables -L INPUT -v -n -x" > "$run_dir/iptables-counters.txt" 2>&1 || true
      fw "$locb" "iptables -D INPUT -p tcp --syn -j DROP" >/dev/null ;;
    location-b-resp-drop|location-b-req-drop)
      local locb app_ip rule_in rule_out; locb="$(cid location-b)"; app_ip="$(container_ip "$app")"
      if [[ "$fault" == location-b-resp-drop ]]; then rule_in="OUTPUT -p tcp -d $app_ip"; else rule_in="INPUT -p tcp -s $app_ip"; fi
      fw "$locb" "iptables -I $rule_in -j DROP" >/dev/null
      sleep "$fault_for"
      fw "$locb" "iptables -L -v -n -x" > "$run_dir/iptables-counters.txt" 2>&1 || true
      fw "$locb" "iptables -D $rule_in -j DROP" >/dev/null ;;
    order-db-wal-full)
      local lsn_before lsn_after running burned=0 failed=0 rows end_s
      lsn_before="$(order_psql "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint")"
      docker exec "$order" sh -c 'df -k /wal | tail -1' > "$run_dir/disk-before.txt" 2>&1 || true
      if [[ "$wal_fill" == 1 ]]; then
        docker exec "$order" sh -c 'dd if=/dev/zero of=/wal/filler bs=1M 2>/dev/null; df -k /wal | tail -1' > "$run_dir/disk-full.txt" 2>&1 || true
      fi
      rows=$(( wal_burn_mb_s * 250 ))
      end_s=$(( SECONDS + fault_for ))
      while (( SECONDS < end_s )); do
        if (( rows > 0 )); then
          if docker exec "$order" psql -X -q -U atlas -d atlas_order -c "INSERT INTO h5_wal_burn SELECT repeat('x',1000) FROM generate_series(1,$rows)" >/dev/null 2>>"$run_dir/burner.err"; then
            burned=$((burned+1)); else failed=$((failed+1)); fi
        fi
        sleep 0.2
      done
      running="$(docker inspect -f '{{.State.Running}}' "$order" 2>/dev/null || echo false)"
      printf '{"running_at_window_end":%s,"burn_ok":%s,"burn_failed":%s,"burn_rows":%s}\n' "$running" "$burned" "$failed" "$rows" > "$run_dir/wal-burner.json"
      vm "sudo rm -f $wal_mount/filler" >/dev/null 2>&1 || true
      [[ "$running" == true ]] || { docker start "$order" >/dev/null 2>&1 || true; }
      local ready_at; ready_at="$(pg_ready_wait "$order")"
      lsn_after="$(order_psql "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint" || true)"
      printf '{"lsn_before":%s,"lsn_after":%s}\n' "${lsn_before:-null}" "${lsn_after:-null}" > "$run_dir/wal-lsn.json"
      printf '{"ready":%s,"container_running_at_window_end":%s}\n' "$ready_at" "$running" > "$run_dir/fault-extra.json" ;;
    dns-kill)
      docker kill "$order" >/dev/null; sleep "$fault_for"; docker start "$order" >/dev/null
      printf '{"ready":%s}\n' "$(pg_ready_wait "$order")" > "$run_dir/fault-extra.json" ;;
    dns-restart)
      docker restart -t 10 "$order" >/dev/null
      printf '{"ready":%s}\n' "$(pg_ready_wait "$order")" > "$run_dir/fault-extra.json" ;;
    dns-recreate)
      docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$order" > "$run_dir/order-ip-before.txt"
      docker rm -f "$order" >/dev/null; dc up -d --no-deps --no-recreate order-postgres >/dev/null 2>&1
      docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(cid order-postgres)" > "$run_dir/order-ip-after.txt"
      printf '{"ready":%s}\n' "$(pg_ready_wait "$(cid order-postgres)")" > "$run_dir/fault-extra.json" ;;
  esac
  finish="$(now)"
  jq -n --arg fault "$fault" --argjson begin "$begin" --argjson finish "$finish" \
    '{fault:$fault, begin:$begin, end:$finish}' > "$run_dir/fault.json"
  if [[ -f "$run_dir/fault-extra.json" ]]; then
    jq -s '.[0] * .[1]' "$run_dir/fault.json" "$run_dir/fault-extra.json" > "$run_dir/fault.json.tmp" && mv "$run_dir/fault.json.tmp" "$run_dir/fault.json"
  fi
}

# dns_start_at <sniffer id> <app url> <run dir>: print the epoch at which the load must start so the fault (fault_at seconds later)
# lands dns_offset seconds after the JVM's last successful lookup of order-postgres. With DNS_REFRESH=1 it first waits until the JVM's
# cached entry has certainly expired (30 s + margin), drops the pool's connections and probes /actuator/health until the JVM has looked
# the name up again: that lookup is the new last lookup. The sniffer, not this code, is the source of truth for the actual offset.
dns_start_at() {
  local sniffer="$1" base="${2%/api/*}" run_dir="$3" last new="" i
  last="$(docker logs "$sniffer" 2>&1 | python3 "$here/audit_invariants.py" dns --last-success --log /dev/stdin --name order-postgres)"
  [[ -n "$last" ]] || { last="$(now)"; echo "no lookup captured before the load; assuming now" >> "$run_dir/dns-notes.txt"; }
  if [[ "$dns_refresh" == 1 ]]; then
    python3 -c "import time; time.sleep(max(0, $last + 32 - time.time()))"
    order_psql "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename='atlas' AND pid<>pg_backend_pid()" >/dev/null || true
    for i in $(seq 1 60); do
      curl -s -o /dev/null --max-time 2 "$base/actuator/health" || true
      new="$(docker logs "$sniffer" 2>&1 | python3 "$here/audit_invariants.py" dns --last-success --log /dev/stdin --name order-postgres)"
      [[ -n "$new" && "$new" != "$last" ]] && break
      sleep 0.5
    done
    if [[ -n "$new" && "$new" != "$last" ]]; then last="$new"; else echo "refresh did not produce a new lookup" >> "$run_dir/dns-notes.txt"; fi
    sleep 3  # let the pool refill from the cached address
  fi
  python3 -c "import time; print(f'{max(time.time() + 2, $last + $dns_offset - $fault_at):.3f}')"
}

# run_race <run dir> <application urls> <location a url> <location b url>: EXP-095 (c). Same-key pairs against two instances, one
# variant after the other in the same stack (each with its own run id and seed), one audit per variant.
run_race() {
  local run_dir="$1" urls="$2" a_url="$3" b_url="$4" variant vdir rid pairs began finished
  for variant in c1 c2 c3 neg seq; do
    vdir="$run_dir/$variant"; mkdir -p "$vdir"
    pairs="${RACE_PAIRS:-800}"; [[ "$variant" == seq ]] && pairs="${RACE_SEQ_PAIRS:-100}"
    rid="$(uuidgen | tr '[:upper:]' '[:lower:]')"
    python3 "$here/reservation_load.py" seed --run-id "$rid" --capacity $(( pairs * 2 + 10 )) --location-a-url "$a_url" --location-b-url "$b_url"
    if [[ "$variant" == c3 && "$proxy" == 1 ]]; then  # a slow location B keeps the first request inside the workflow
      curl -fsS -X POST "$toxiproxy_api/proxies/location-b/toxics" \
        -d '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":1500,"jitter":0}}' >/dev/null
    fi
    began="$(now)"
    python3 "$here/reservation_load.py" race --run-id "$rid" --url "$urls" --variant "$variant" --pairs "$pairs" \
      --concurrency "${RACE_CONCURRENCY:-24}" --out "$vdir/requests.jsonl" > "$vdir/race.json"
    finished="$(now)"
    if [[ "$variant" == c3 && "$proxy" == 1 ]]; then curl -fsS -X DELETE "$toxiproxy_api/proxies/location-b/toxics/slow" >/dev/null; fi
    printf '{"begin":%s,"end":%s,"run_id":"%s","pairs":%s}\n' "$began" "$finished" "$rid" "$pairs" > "$vdir/window.json"
    python3 "$here/audit_invariants.py" audit --run-id "$rid" --requests "$vdir/requests.jsonl" \
      --order-container "$(cid order-postgres)" --location-a-container "$(cid location-a-postgres)" \
      --location-b-container "$(cid location-b-postgres)" --since "$finished" --converge-timeout "$converge_timeout" \
      --out "$vdir/result.json" >"$vdir/audit.log" 2>&1 || true
    cat "$vdir/audit.log"
  done
  docker logs "$(cid location-a)" >"$run_dir/location-a.log" 2>&1 || true
  docker logs "$(cid location-b)" >"$run_dir/location-b.log" 2>&1 || true
  docker logs "$(cid app)" >"$run_dir/app.log" 2>&1 || true
  docker logs "$(cid app2)" >"$run_dir/app2.log" 2>&1 || true
  for variant in c1 c2 c3 neg seq; do
    python3 "$here/audit_invariants.py" calls --log "a=$run_dir/location-a.log" --log "b=$run_dir/location-b.log" --ips "$run_dir/ips.json" \
      --since "$(jq -r .begin "$run_dir/$variant/window.json")" --until "$(jq -r .end "$run_dir/$variant/window.json")" \
      --out "$run_dir/$variant/calls.json" >"$run_dir/$variant/calls.log" 2>&1 || true
  done
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  project=""
}

run_one() {  # run_one <fault> <n>
  local fault="$1" n="$2" run_dir run_id warm_id start_at load_end since replay_args=() sniffer_id="" probe_id=""
  run_dir="$out/${LABEL:-$fault}/run-$n"; mkdir -p "$run_dir"
  unset ORDER_DB_HOST
  project="atlasexp-$tag-$(openssl rand -hex 3)"
  DB_PASSWORD="$(openssl rand -hex 16)"
  INVENTORY_LOCATION_SERVICE_TOKEN="$(openssl rand -hex 24)"
  ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN="$(openssl rand -hex 24)"
  ATLAS_JWT_SIGNING_SECRET="$(openssl rand -hex 32)"
  echo "=== fault=$fault run=$n project=$project"
  if [[ "$fault" == order-db-wal-full ]]; then
    wal_volume_up || { teardown; project=""; return 1; }
  fi
  if [[ "$db_by_ip" == 1 ]]; then  # the application connects to the order database by container IP
    dc up -d --wait --wait-timeout 120 --no-deps order-postgres >"$run_dir/compose-up-order.log" 2>&1 || true
    export ORDER_DB_HOST="$(container_ip "$(cid order-postgres)")"
  fi
  if [[ "$dns_trace" == 1 ]]; then  # start the DNS sniffer before the JVM resolves anything, so its first lookup is captured
    dc up -d --wait --wait-timeout 300 --no-deps order-postgres location-a-postgres location-b-postgres location-a location-b >"$run_dir/compose-up-deps.log" 2>&1 || true
    dc up -d --no-deps app >"$run_dir/compose-up-app.log" 2>&1 || true
    sniffer_id="$(docker run -d --label "atlasexp=$tag" --network "container:$(cid app)" --cap-add NET_ADMIN --cap-add NET_RAW \
      "$sniffer_image" tcpdump -l -tt -n -i lo -xx udp)"
    # ground truth of the resolver, from its OWN container on the same network (a probe in the application's namespace
    # would show up in the capture above): when does Docker's DNS answer order-postgres, when NXDOMAIN
    probe_id="$(docker run -d --label "atlasexp=$tag" --network "${project}_default" "$sniffer_image" sh -c \
      'while :; do if getent hosts order-postgres >/dev/null 2>&1; then r=ok; else r=fail; fi; echo "$(date +%s.%N) $r"; sleep 0.1; done')"
  fi
  dc up -d --wait --wait-timeout 300 >"$run_dir/compose-up.log" 2>&1 || {
    echo "stack did not become healthy; see $run_dir/compose-up.log" >&2
    dc logs --no-color >"$run_dir/stack-failure.log" 2>&1 || true
    teardown; project=""
    return 1; }
  local app_url loc_a_url loc_b_url
  app_url="$(resolve_app_url)"
  loc_a_url="http://127.0.0.1:$(dc port location-a 8090 | awk -F: '{print $NF}')"
  loc_b_url="http://127.0.0.1:$(dc port location-b 8090 | awk -F: '{print $NF}')"

  jq -n --arg app "$(container_ip "$(cid app)")" --arg app2 "$([[ "$apps" == 2 ]] && container_ip "$(cid app2)" || true)" \
    --arg a "$(container_ip "$(cid location-a)")" --arg b "$(container_ip "$(cid location-b)")" --arg order "$(container_ip "$(cid order-postgres)")" \
    '{app:$app, location_a:$a, location_b:$b, order:$order} + (if $app2 == "" then {} else {app2:$app2} end)' > "$run_dir/ips.json"
  [[ "$fault" == order-db-wal-full ]] && order_psql "CREATE TABLE IF NOT EXISTS h5_wal_burn(c text)" >/dev/null
  if [[ "${LEASE_AUDIT:-0}" == 1 ]]; then lease_audit_install; fi

  if [[ "$proxy" == 1 ]]; then
    toxiproxy_api="http://127.0.0.1:$(dc port toxiproxy 8474 | awk -F: '{print $NF}')"
    curl -fsS -X POST "$toxiproxy_api/proxies" -d @"$here/toxiproxy-location-b.json" >"$run_dir/toxiproxy-proxy.json"
  fi
  warm_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  python3 "$here/reservation_load.py" seed --run-id "$warm_id" --capacity 1000 \
    --location-a-url "$loc_a_url" --location-b-url "$loc_b_url"
  python3 "$here/reservation_load.py" load --run-id "$warm_id" --url "$app_url" --rate 20 --duration 5 \
    --out "$run_dir/warmup.jsonl" >/dev/null

  if [[ "$fault" == race ]]; then run_race "$run_dir" "$app_url" "$loc_a_url" "$loc_b_url"; return 0; fi
  run_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  python3 "$here/reservation_load.py" seed --run-id "$run_id" --capacity $(( rate * duration * 2 )) \
    --location-a-url "$loc_a_url" --location-b-url "$loc_b_url"
  [[ "$idle_before_load" -gt 0 ]] && sleep "$idle_before_load"
  if [[ -n "$app1_delay" ]]; then  # first application only: its calls to both locations take longer, so more workflows hold a lease at any moment
    netem_apply_paths "$(cid app)" "$app1_delay" "$(container_ip "$(cid location-a)")" "$(container_ip "$(cid location-b)")" > "$run_dir/netem-app1.txt" 2>&1
  fi
  stack_snapshot "$run_dir/env-before.txt"
  # State.OOMKilled is reset when the restart policy restarts the container, so count kernel OOM events instead.
  docker events --since "$(date +%s)" --filter event=oom --filter "label=com.docker.compose.project=$project" \
    >"$run_dir/oom-events.txt" 2>/dev/null &
  local events_pid=$!
  start_at="$(python3 -c 'import time; print(f"{time.time()+1.5:.3f}")')"
  if [[ "$dns_trace" == 1 && -n "$dns_offset" ]]; then start_at="$(dns_start_at "$sniffer_id" "$app_url" "$run_dir")"; fi
  ( sleep "$(python3 -c "import time; print(max(0, $start_at + $fault_at - time.time()))")"; inject "$fault" "$run_dir" ) &
  local fault_pid=$!
  load_end="$(python3 "$here/reservation_load.py" load --run-id "$run_id" --url "$app_url" \
    --rate "$rate" --duration "$duration" --threads "$load_threads" --start-at "$start_at" --out "$run_dir/requests.jsonl" \
    | jq -r '.end_at')"
  wait "$fault_pid" || true
  since="$load_end"
  [[ -n "$app1_delay" ]] && netem_clear "$(cid app)"
  if [[ -n "$sniffer_id" ]]; then
    docker logs "$sniffer_id" > "$run_dir/dns.tcpdump.txt" 2>&1 || true
    docker rm -f "$sniffer_id" >/dev/null 2>&1 || true
    docker logs "$probe_id" > "$run_dir/dns-probe.txt" 2>&1 || true
    docker rm -f "$probe_id" >/dev/null 2>&1 || true
    python3 "$here/audit_invariants.py" dns --name order-postgres --log "$run_dir/dns.tcpdump.txt" --fault "$run_dir/fault.json" \
      --probe "$run_dir/dns-probe.txt" --out "$run_dir/dns.json" >/dev/null 2>&1 || true
  fi
  docker logs "$(cid location-a)" > "$run_dir/location-a.log" 2>&1 || true
  if [[ "$plant_dup" == 1 ]]; then  # negative control for the duplicate-call detector: the host repeats one existing reserve call
    local planted_key
    planted_key="$(grep 'POST .*/reservations 201' "$run_dir/location-a.log" | tail -1 | awk '{print $NF}')"
    curl -s -o /dev/null -X POST "$loc_a_url/internal/v1/fulfillment-locations/00000000-0000-0000-0000-000000000001/reservations" \
      -H "X-Atlas-Service-Token: $INVENTORY_LOCATION_SERVICE_TOKEN" -H "Idempotency-Key: $planted_key" -H 'Content-Type: application/json' \
      -d '{"orderId":"00000000-0000-4000-8000-00000000f00d","items":[{"sellerId":"00000000-0000-0000-0000-000000000011","skuId":"planted","quantity":1}]}' || true
    printf '%s\n' "$planted_key" > "$run_dir/planted-key.txt"
  fi
  if [[ "$retry_mode" == 1 ]]; then  # same-key retries after the fault, then every key once more
    for _ in $(seq 1 180); do  # a restarted application needs ~20 s; the retries start when it is healthy
      [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(cid app)" 2>/dev/null)" == healthy ]] && break
      sleep 1
    done
    app_url="$(resolve_app_url)"
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
  [[ "${LEASE_AUDIT:-0}" == 1 ]] && lease_audit_collect "$run_id" "$run_dir/lease-claims.json"
  stack_snapshot "$run_dir/env-after.txt"

  kill "$events_pid" 2>/dev/null || true
  dc logs --no-color order-postgres >"$run_dir/order-postgres.log" 2>&1 || true
  dc logs --no-color app >"$run_dir/app.log" 2>&1 || true
  [[ "$apps" == 2 ]] && { dc logs --no-color app2 >"$run_dir/app2.log" 2>&1 || true; }
  docker logs "$(cid location-b)" >"$run_dir/location-b.log" 2>&1 || true
  docker logs "$(cid location-a)" >"$run_dir/location-a.log" 2>&1 || true
  if [[ "$LOCATION_ACCESSLOG" == true ]]; then  # duplicate remote calls per Idempotency-Key, from the locations' own access logs
    python3 "$here/audit_invariants.py" calls --log "a=$run_dir/location-a.log" --log "b=$run_dir/location-b.log" --ips "$run_dir/ips.json" \
      --since "$start_at" --fault "$run_dir/fault.json" --out "$run_dir/calls.json" >"$run_dir/calls.log" 2>&1 || true
  fi
  local containers='{}' service state
  for service in app app2 order-postgres location-a location-b; do
    state="$(docker inspect -f '{"restart_count":{{.RestartCount}},"oom_killed":{{.State.OOMKilled}},"exit_code":{{.State.ExitCode}},"running":{{.State.Running}}}' "$(cid "$service")" 2>/dev/null || echo '{}')"
    containers="$(jq -c --arg s "$service" --argjson v "$state" '. + {($s):$v}' <<<"$containers")"
  done
  jq -n --arg fault "$fault" --arg label "${LABEL:-$fault}" --argjson n "$n" --arg run_id "$run_id" --argjson rate "$rate" \
    --argjson duration "$duration" --argjson fault_at "$fault_at" --argjson fault_for "$fault_for" \
    --argjson apps "$apps" --argjson retry "$retry_mode" --argjson proxy "$proxy" --arg lease "$RECOVERY_LEASE" \
    --argjson idle "$idle_before_load" --argjson load_threads "$load_threads" --argjson db_by_ip "$db_by_ip" --arg negative_ttl0 "${APP_NEGATIVE_DNS_TTL0:-0}" \
    --arg app1_delay "$app1_delay" --arg dns_offset "$dns_offset" --argjson plant_dup "$plant_dup" --argjson wal_size_mb "$wal_size_mb" \
    --argjson wal_burn_mb_s "$wal_burn_mb_s" --argjson mailpit "$mailpit" \
    --argjson containers "$containers" --argjson oom_events "$(wc -l <"$run_dir/oom-events.txt" | tr -d ' ')" --argjson pg "$(pg_markers "$run_dir/order-postgres.log")" \
    '{fault:$fault, label:$label, run:$n, run_id:$run_id, rate:$rate, duration:$duration, fault_at:$fault_at,
      fault_for:$fault_for, apps:$apps, retry:$retry, proxy:$proxy, recovery_lease:$lease,
      idle_before_load:$idle, load_threads:$load_threads, db_by_ip:$db_by_ip, negative_dns_ttl0:$negative_ttl0,
      app1_delay:$app1_delay, dns_offset:$dns_offset, plant_dup:$plant_dup, wal_size_mb:$wal_size_mb, wal_burn_mb_s:$wal_burn_mb_s, mailpit:$mailpit, containers:$containers, oom_events:$oom_events, order_postgres_log:$pg}' > "$run_dir/meta.json"
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
