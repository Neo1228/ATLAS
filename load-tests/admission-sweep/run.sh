#!/usr/bin/env bash
# EXP-086 K x TTL 격자 실행기. harness를 test 소스에 임시로 복사해 실행하고 반드시 지운다(일반 check에 넣지 않는다).
# 사용: load-tests/admission-sweep/run.sh [출력 jsonl]
# 환경 변수: SWEEP_KS, SWEEP_TTLS, SWEEP_SEEDS, SWEEP_BUYERS, SWEEP_STOCK, SWEEP_ARRIVAL_SECONDS
# 전제: JAVA_HOME(JDK 25), DOCKER_HOST(Testcontainers용). PostgreSQL 컨테이너는 label atlasexp=c2 로 만든다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/atlas-application/src/test/java/com/atlas/experiment"
export SWEEP_OUT="${1:-/tmp/atlas-c2-results/sweep.jsonl}"
mkdir -p "$DEST"
trap 'rm -rf "$DEST"' EXIT
cp "$ROOT/load-tests/admission-sweep/AdmissionKTtlSweepExperiment.java" "$DEST/"
cd "$ROOT"
./gradlew --no-daemon --no-build-cache --console=plain :atlas-application:cleanTest :atlas-application:test \
  --tests 'com.atlas.experiment.AdmissionKTtlSweepExperiment' -i
