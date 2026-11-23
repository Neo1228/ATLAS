#!/usr/bin/env bash
# 사용법: queue.sh <큐 파일>   (한 줄: 라벨 gate [VAR=VAL ...])
# 줄마다 run.sh를 순서대로 실행한다. 결과는 git-ignore된 .local/redis-gate/<라벨>에 쌓는다.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
results="$(cd "$here/../.." && pwd)/.local/redis-gate"
mkdir -p "$results"
while read -r label gate envs; do
  [[ -z "$label" || "$label" == \#* ]] && continue
  # shellcheck disable=SC2086
  env $envs "$here/run.sh" "$results/$label" "$gate" >/dev/null 2>&1 </dev/null
  echo "$(date +%T) done $label" >> "$results/queue.log"
done < "$1"
