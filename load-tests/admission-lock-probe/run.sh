#!/usr/bin/env bash
# EXP-090 Part A: 잠금 실패 예외 종류와 한 Seller-SKU join 지연 측정.
# 사용: load-tests/admission-lock-probe/run.sh [출력 파일]
# 환경: JAVA_HOME(JDK 25), DOCKER_HOST(Testcontainers용). PostgreSQL 18.6 컨테이너를 Testcontainers가 띄운다.
# 일반 `check`에 넣지 않으려고 harness를 test 소스에 임시로 복사해 실행하고 지운다(EXP-086 admission-sweep과 같은 방식).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/atlas-application/src/test/java/com/atlas/experiment"
export PROBE_OUT="${1:-/tmp/atlas-admission-lock-probe.txt}"
mkdir -p "$DEST"
trap 'rm -rf "$DEST"' EXIT
cp "$ROOT/load-tests/admission-lock-probe/AdmissionLockProbeExperiment.java" "$DEST/"
cd "$ROOT"
./gradlew --no-daemon --no-build-cache --console=plain :atlas-application:cleanTest :atlas-application:test \
  --tests 'com.atlas.experiment.AdmissionLockProbeExperiment'
echo "결과: $PROBE_OUT"
