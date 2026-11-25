#!/usr/bin/env bash
# The EXP-087 matrix: network partition/delay/loss, disk full, two applications, same-key retry, the
# lease and DNS controls. Every condition is one run-fault-injection.sh invocation (one fault, a LABEL,
# some options) into the same results directory; the final table is printed by `audit_invariants.py
# summarize --results <dir>`.
#
#   RUNS=5 load-tests/fault-injection/run-expansion-matrix.sh                # everything
#   ONLY="location-b-partition net-delay" load-tests/fault-injection/run-expansion-matrix.sh
#   RUNS_DNS=3 / OUT=... also apply. Needs the jars (see run-fault-injection.sh).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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
