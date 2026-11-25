#!/usr/bin/env bash
# Isolates the JVM name-lookup cache from the application (EXP-087, hypothesis H12): a Java probe resolves a
# container name once, stays quiet for IDLE seconds (default JVM caches a successful lookup for 30 s), then
# the runner kills and starts the target container at once (or restarts or recreates it) while the probe
# looks the name up every 100 ms from the moment the target is down. Prints, per run, how long after the target was started again the probe
# first got an address. Containers and the network carry the label atlasexp=$TAG and are removed at the end.
#
#   TAG=f1 RUNS=3 load-tests/fault-injection/dns-cache-probe.sh
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tag="${TAG:-fault-injection}"
runs="${RUNS:-3}"
image="${PROBE_IMAGE:-eclipse-temurin:25-jdk}"
# the Docker VM must be able to read the bind-mounted files (Colima shares the home directory, not /tmp)
probe_dir="${PROBE_DIR:-$here}"
out="${OUT:-$(cd "$here/../.." && pwd)/.local/fault-injection/dns-probe-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
network="atlasexp-$tag-dnsprobe-$(openssl rand -hex 3)"
trap 'docker rm -f "$network-target" "$network-probe" >/dev/null 2>&1 || true; docker network rm "$network" >/dev/null 2>&1 || true' EXIT
docker network create --label "atlasexp=$tag" "$network" >/dev/null

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
target_up() {
  docker run -d --name "$network-target" --network "$network" --label "atlasexp=$tag" alpine:3.21 sleep 3600 >/dev/null
}
# probe_run <label> <idle seconds> <action> <java options>
probe_run() {
  local label="$1" idle="$2" action="$3" options="$4" n="$5" file begin finish ip_before ip_after
  file="$out/$label-$n.txt"
  docker rm -f "$network-target" "$network-probe" >/dev/null 2>&1 || true
  target_up
  ip_before="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$network-target")"
  docker run -d --name "$network-probe" --network "$network" --label "atlasexp=$tag" \
    -v "$probe_dir/DnsCacheProbe.java:/probe/DnsCacheProbe.java:ro" \
    -v "$probe_dir/jvm-negative-dns-ttl0.security:/probe/ttl0.security:ro" \
    "$image" sh -c "java $options /probe/DnsCacheProbe.java $network-target $idle 30" >/dev/null
  for _ in $(seq 1 600); do  # the probe prints idle-over when its quiet time ends (at most 60 s + JVM start)
    docker logs "$network-probe" 2>&1 | grep -q '^idle-over' && break
    docker ps -q --filter "name=$network-probe" | grep -q . || { echo "probe did not start: $(docker logs "$network-probe" 2>&1 | head -3)" >&2; return 1; }
    sleep 0.2
  done
  begin="$(now)"
  case "$action" in  # the probe is told to start looking up as soon as the target is down
    kill) docker kill "$network-target" >/dev/null; docker exec "$network-probe" touch /tmp/go; docker start "$network-target" >/dev/null ;;
    restart) docker restart -t 1 "$network-target" >/dev/null & sleep 1.1; docker exec "$network-probe" touch /tmp/go; wait ;;
    recreate) docker rm -f "$network-target" >/dev/null; docker exec "$network-probe" touch /tmp/go; target_up ;;
  esac
  finish="$(now)"
  ip_after="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$network-target")"
  while docker ps -q --filter "name=$network-probe" | grep -q .; do sleep 0.5; done
  docker logs "$network-probe" > "$file" 2>&1
  python3 - "$label" "$n" "$begin" "$finish" "$ip_before" "$ip_after" "$file" <<'PY'
import sys
label, n, begin, finish, before, after, path = sys.argv[1:]
lines = [l.split() for l in open(path) if l[:1].isdigit()]
action_returned = float(finish) * 1000  # epoch ms when the kill/start (or restart, recreate) command returned
first_lookup = min(int(l[0]) for l in lines)
unknown = [int(l[0]) for l in lines if l[1] == "unknown"]
ok_after = [int(l[0]) for l in lines if l[1] == "ok" and int(l[0]) >= action_returned]
first_ok = min(ok_after) if ok_after else None
addresses = sorted({l[2] for l in lines if l[1] == "ok"})
started_ms = action_returned - first_lookup  # command returned this long after the probe started looking up
unknown = [u - first_lookup for u in unknown]
first_ok = None if first_ok is None else first_ok - first_lookup
print(f"{label} run={n} action_returned_ms={started_ms:.0f} unknown_lookups={len(unknown)} "
      f"unknown_until_ms={max(unknown) if unknown else '-'} first_ok_after_action_ms={first_ok} "
      f"seconds_without_address_after_action={'-' if first_ok is None else f'{max(0, first_ok - started_ms) / 1000:.1f}'} "
      f"ip_before={before} ip_after={after} addresses_seen={','.join(addresses)}")
PY
}
for n in $(seq 1 "$runs"); do
  probe_run idle5-default 5 kill "" "$n"
  probe_run idle40-default 40 kill "" "$n"
  probe_run idle40-ttl0 40 kill "-Djava.security.properties=/probe/ttl0.security" "$n"
  probe_run idle40-default-restart 40 restart "" "$n"
  probe_run idle40-default-recreate 40 recreate "" "$n"
done | tee "$out/summary.txt"
echo "results=$out leftover=$(docker ps -aq --filter "label=atlasexp=$tag" | wc -l | tr -d ' ')"
