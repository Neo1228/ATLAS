#!/usr/bin/env bash
# The EXP-087 matrix: network partition/delay/loss, disk full, two applications, same-key retry, the
# lease and DNS controls. Every condition is one run-fault-injection.sh invocation (one fault, a LABEL,
# some options) into the same results directory; the final table is printed by `audit_invariants.py
# summarize --results <dir>`.
#
#   RUNS=5 load-tests/fault-injection/run-expansion-matrix.sh                # everything
#   ONLY="location-b-partition net-delay" load-tests/fault-injection/run-expansion-matrix.sh
#   RUNS_DNS=3 / OUT=... also apply. Needs the jars (see run-fault-injection.sh).
#
# SET=deep runs the EXP-095 matrix instead (lease x two applications, pg_wal-only full, same-key races, DNS negative cache,
# SYN/asymmetric drops, DB pause). Every (condition, repetition) is one run-fault-injection.sh run; the whole list is
# shuffled with a fixed SEED so time of day and host load are not tied to a condition (the Docker VM is shared); the plan
# is written to <out>/plan.txt and finished jobs (<out>/done/) are skipped on a re-run.
#   SET=deep REPS=5 load-tests/fault-injection/run-expansion-matrix.sh
#   SET=deep ONLY='^d-' REPS=1 ...        # labels matching the regex
# Needs APP_JAR/LOCATION_JAR and FI_DIR (the directory holding jvm-negative-dns-ttl0.security) under a path the Docker VM can
# read (under $HOME), the image atlasexp-h5-sniffer:1 (alpine + tcpdump) and colima (the pg_wal volume).
# The per-condition tables: audit_invariants.py deep --root <out>.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

deep_matrix() {
  local reps="${REPS:-5}" seed="${SEED:-20261125}" only_re="${ONLY:-.}" out_dir="${OUT:-$(cd "$here/../.." && pwd)/.local/fault-injection/deep-$(date +%Y%m%d-%H%M%S)}"
  export TAG="${TAG:-h5}"
  export LOCATION_ACCESSLOG=1 MAILPIT=0
  mkdir -p "$out_dir/done"
  two="APPS=2 LOAD_THREADS=400 LEASE_AUDIT=1"
  delay='APP1_DELAY=delay~400ms'   # ~ stands for a space inside a value: the settings are split on spaces below
  dns="DNS_TRACE=1 DNS_REFRESH=1 FAULT_FOR=6 RATE=20 DURATION=36 FAULTS=dns-kill"
  dnslong="DNS_TRACE=1 DNS_REFRESH=1 DNS_OFFSET=5 RATE=20 DURATION=70 FAULTS=dns-kill"

  # label[@reps]|settings (VAR=value words; a ~ inside a value becomes a space)
  conditions=(
    "a-ctl-L7|$two FAULTS=none RECOVERY_LEASE=PT7S $delay PLANT_DUP=1"
    "a-pause12-L30|$two FAULTS=app-pause FAULT_FOR=12 RECOVERY_LEASE=PT30S $delay"
    "a-pause12-L15|$two FAULTS=app-pause FAULT_FOR=12 RECOVERY_LEASE=PT15S $delay"
    "a-pause12-L10|$two FAULTS=app-pause FAULT_FOR=12 RECOVERY_LEASE=PT10S $delay"
    "a-pause12-L7|$two FAULTS=app-pause FAULT_FOR=12 RECOVERY_LEASE=PT7S $delay"
    "a-pause12-L10-nodelay|$two FAULTS=app-pause FAULT_FOR=12 RECOVERY_LEASE=PT10S"
    "b-ctl-burn|FAULTS=order-db-wal-full WAL_FILL=0 WAL_BURN_MB_S=4 FAULT_FOR=6"
    "b-full-6s|FAULTS=order-db-wal-full WAL_FILL=1 WAL_BURN_MB_S=4 FAULT_FOR=6"
    "b-full-30s|FAULTS=order-db-wal-full WAL_FILL=1 WAL_BURN_MB_S=2 FAULT_FOR=30 RATE=20 DURATION=45"
    "c-race|FAULTS=race APPS=2 PROXY=1"
    "d-fresh|$dns DNS_OFFSET=5"
    "d-inside|$dns DNS_OFFSET=27"
    "d-expired|$dns DNS_OFFSET=40"
    "d-expired-fix|$dns DNS_OFFSET=40 APP_NEGATIVE_DNS_TTL0=1"
    "d-s15|$dnslong FAULT_FOR=15"
    "d-s27|$dnslong FAULT_FOR=27"
    "d-s36|$dnslong FAULT_FOR=36"
    "d-s36-fix|$dnslong FAULT_FOR=36 APP_NEGATIVE_DNS_TTL0=1"
    "d-restart@3|$dns DNS_OFFSET=40 FAULTS=dns-restart"
    "d-recreate@3|$dns DNS_OFFSET=40 FAULTS=dns-recreate"
    "e-ctl-none|FAULTS=none LOAD_THREADS=256"
    "e-syn-drop|FAULTS=location-b-syn-drop LOAD_THREADS=256"
    "e-resp-drop|FAULTS=location-b-resp-drop LOAD_THREADS=256"
    "e-req-drop|FAULTS=location-b-req-drop LOAD_THREADS=256"
    "e-db-pause|FAULTS=order-db-pause LOAD_THREADS=256"
  )

  plan="$out_dir/plan.txt"
  if [[ ! -f "$plan" ]]; then
    printf '%s\n' "${conditions[@]}" | python3 -c '
  import random, sys
  reps, seed = int(sys.argv[1]), int(sys.argv[2])
  jobs = []
  for line in sys.stdin:
      name, settings = line.rstrip("\n").split("|", 1)
      name, _, own = name.partition("@")   # label@3: this condition has 3 repetitions instead of REPS
      jobs += [(name, k, settings) for k in range(1, (int(own) if own else reps) + 1)]
  random.Random(seed).shuffle(jobs)
  for label, k, settings in jobs:
      print(f"{label}|{k}|{settings}")
  ' "$reps" "$seed" > "$plan"
  fi

  total="$(grep -c . "$plan")"
  index=0
  while IFS='|' read -r label k settings; do
    index=$((index + 1))
    [[ "$label" =~ $only_re ]] || continue
    [[ -f "$out_dir/done/$label-$k" ]] && continue
    {
      echo "=== $index/$total $label rep=$k $(date -u +%FT%TZ)"
      uptime
    } >> "$out_dir/uptime.log"
    env_words=()
    for word in $settings; do env_words+=("${word//\~/ }"); done
    echo "=== $index/$total $label rep=$k" | tee -a "$out_dir/progress.log"
    env "${env_words[@]}" LABEL="$label" RUNS=1 OUT="$out_dir/rep-$k" "$here/run-fault-injection.sh" > "$out_dir/$label-$k.log" 2>&1 < /dev/null || echo "job failed: $label $k" | tee -a "$out_dir/progress.log"
    { uptime; } >> "$out_dir/uptime.log"
    touch "$out_dir/done/$label-$k"
  done < "$plan"
  echo "matrix finished $(date -u +%FT%TZ)" | tee -a "$out_dir/progress.log"
}

if [[ "${SET:-}" == deep ]]; then deep_matrix; exit 0; fi
runs="${RUNS:-5}"
runs_dns="${RUNS_DNS:-3}"
out="${OUT:-$(cd "$here/../.." && pwd)/.local/fault-injection/expansion-$(date +%Y%m%d-%H%M%S)}"
only="${ONLY:-}"
export OUT="$out"

# condition <label> <fault> [VAR=value ...]; RUNS_OVERRIDE=<n> before it changes n for that condition.
condition() {
  local label="$1" fault="$2"; shift 2
  if [[ -n "$only" && " $only " != *" $label "* ]]; then return 0; fi
  echo "##### $label"
  env LABEL="$label" FAULTS="$fault" RUNS="${RUNS_OVERRIDE:-$runs}" "$@" "$here/run-fault-injection.sh" || true
}

# network
condition proxy-none none PROXY=1
condition location-b-partition location-b-partition
condition location-b-partition-long location-b-partition FAULT_FOR=40 CONVERGE_TIMEOUT=150
condition net-delay net-delay
condition net-loss net-loss
# disk
condition order-db-disk-full order-db-disk-full
# two applications on one order database
condition none+2apps none APPS=2
condition order-db-kill+2apps order-db-kill APPS=2
condition app-kill app-kill
condition app-kill+2apps app-kill APPS=2
# same-key retry after the fault
condition order-db-kill+retry order-db-kill RETRY=1
condition app-oom+retry app-oom RETRY=1
condition app-cpu-throttle+retry app-cpu-throttle RETRY=1
# lease length against the convergence tail
condition app-cpu-throttle@lease30s app-cpu-throttle RECOVERY_LEASE=PT30S
condition app-cpu-throttle@lease15s app-cpu-throttle RECOVERY_LEASE=PT15S
condition app-cpu-throttle@lease10s app-cpu-throttle RECOVERY_LEASE=PT10S
condition order-db-restart@lease30s order-db-restart RECOVERY_LEASE=PT30S
condition order-db-restart@lease10s order-db-restart RECOVERY_LEASE=PT10S
# DNS: the order database goes away and comes back at once (FAULT_FOR=0); idle = seconds between warm-up and load
dns=(FAULT_FOR=0 CONVERGE_TIMEOUT=120)
RUNS_OVERRIDE="$runs_dns"
condition dns:idle0 order-db-kill "${dns[@]}" IDLE_BEFORE_LOAD=0
condition dns:idle40 order-db-kill "${dns[@]}" IDLE_BEFORE_LOAD=40
condition dns:idle40+ip order-db-kill "${dns[@]}" IDLE_BEFORE_LOAD=40 DB_BY_IP=1
condition dns:idle40+ttl0 order-db-kill "${dns[@]}" IDLE_BEFORE_LOAD=40 APP_NEGATIVE_DNS_TTL0=1
condition dns:idle40+restart order-db-restart "${dns[@]}" IDLE_BEFORE_LOAD=40
condition dns:idle40+recreate order-db-recreate "${dns[@]}" IDLE_BEFORE_LOAD=40
unset RUNS_OVERRIDE

python3 "$here/audit_invariants.py" summarize --results "$out" | tee "$out/summary.md"
echo "results=$out"
