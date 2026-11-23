#!/usr/bin/env bash
# 사용법: run.sh <결과 디렉터리> <gate: precheck|redis>
# run-hot-sku-coordinator.sh를 2,000 req/s·30초로 한 번 돌리면서 sampler.py가 1초 간격으로 gate 카운터와
# Redis commandstats를 기록한다. K6_CPUS나 gate command timeout 같은 변수는 호출 환경에서 그대로 넘어간다.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
outdir="$1"; gate="$2"
mkdir -p "$outdir"
python3 "$here/sampler.py" "$outdir" &
spid=$!
uptime > "$outdir/uptime-start.txt"
GATE="$gate" RATES=2000 DURATION=30s LOCATION_CPUS=2 OUT="$outdir/r" \
  "$repo/load-tests/run-hot-sku-coordinator.sh" > "$outdir/runner.log" 2>&1
rc=$?
uptime > "$outdir/uptime-end.txt"
kill "$spid" 2>/dev/null; wait "$spid" 2>/dev/null
echo "rc=$rc" > "$outdir/rc.txt"
