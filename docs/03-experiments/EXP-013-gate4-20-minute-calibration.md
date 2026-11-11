# EXP-013 — Gate 4 20분 로컬 보정

**상태:** 완료(보고서 전용). **추적:** [이슈 #81](https://github.com/Neo1228/ATLAS/issues/81).

## 목적과 범위

- 1× 균일 arrival rate의 10분 run 두 개에서 Buyer flow 오류와 inventory invariant 비교: 제약 없는 baseline, inventory 10%·K=1 queue saturation.
- PayPal integration 이전의 local workflow 실험. Provider network latency는 포함하지 않음. 현재 payment contract는 [D-081](../00-governance/decision-log.md), [ADR-0026](../02-decisions/adr/ADR-0026-paypal-sandbox-payments.md) 참고.

## 환경·데이터·workload

- Windows 10 호스트, Docker Desktop Linux 엔진 `29.7.2`, CPU 4개, 메모리 `4,109,647,872`바이트. `compose.yaml`의 PostgreSQL `18.6`; 애플리케이션과 생성기는 로컬 Compose 네트워크에서 실행했습니다. 격리 벤치마크가 아닌 공유 자원 진단입니다.
- 애플리케이션: `eclipse-temurin:25-jre`에서 빌드한 `application-module.jar` (`sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0`). 호스트 포트는 공개하지 않았습니다.
- 생성기: 공식 `grafana/k6:2.3.0` (`sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba8`). 호스트에 k6를 설치하거나 애플리케이션 런타임 의존성을 추가하지 않았습니다.
- 데이터베이스 격리: 일회용 `atlas_gate4`; Flyway V1–V5를 적용하고 Hibernate `ddl-auto=validate`를 통과했습니다. 최종 PostgreSQL 데이터베이스 목록에는 `atlas_gate4`가 없었습니다. 기존 `atlas` 데이터베이스는 부하 기록에 사용하지 않았습니다.
- 스크립트: [`load-tests/gate4.js`](../../load-tests/gate4.js). 캠페인을 만들고 연 뒤 대기열 참가 1회, 결제 시도 1회를 수행하며 재고를 예약한 경우에만 결제합니다. 폴링, 재시도, 대기 시간, 인위적인 PSP 지연은 없습니다.
- 실행 ID: `gate4-pilot-20261103`, `gate4-baseline-20261103`, `gate4-queue-backlog-20261103`. 도착률: `10,000 sessions / 60m = 2.7778 sessions/s`, 균일 분포. 두 부하 프로필은 총 20분이며, 별도 1-VU 사전 검증은 60초입니다. 각 부하 프로필은 10분간 실행했습니다(예정 세션 약 1,667개). 기준 구성 재고/K=`1,667/1,667`; 대기열 구성 재고/K=`167/1`; 티켓 TTL=`7,200s`.
- 별도 1-VU 60초 전체 흐름 사전 실행에서 세션 p95 `97.56ms`를 측정했습니다. 고정 `preAllocatedVUs = ceil(2.7778 × 0.09756) = 1`, `maxVUs=1`로 설정했습니다. VU/자원 자동 확장은 없습니다.
## 재현 방법

Windows의 Git Bash에서 실행:

```sh
repoRoot="$(pwd -W)"
docker compose up -d --wait postgres
docker compose exec -T postgres createdb -U atlas atlas_gate4
./gradlew :application-module:bootJar

MSYS_NO_PATHCONV=1 docker run --rm --name atlas-gate4-app --network atlas_default \
  -e ATLAS_SERVER_ADDRESS=0.0.0.0 \
  -e ATLAS_DB_URL=jdbc:postgresql://postgres:5432/atlas_gate4 \
  -e ATLAS_DB_USER=atlas -e ATLAS_DB_PASSWORD=atlas-local-only \
  -v "$repoRoot/ATLAS-application/build/libs/application-module.jar:/app.jar:ro" \
  eclipse-temurin:25-jre java -jar /app.jar
```

두 번째 터미널에서 고정한 k6 이미지로 사전 검증과 부하 프로필을 실행합니다.

```sh
mkdir -p ATLAS-application/build/gate4-results
MSYS_NO_PATHCONV=1 docker run --rm --network atlas_default \
  -v "$repoRoot/load-tests/gate4.js:/gate4.js:ro" \
  -v "$repoRoot/ATLAS-application/build/gate4-results:/results" \
  -e ATLAS_BASE_URL=http://atlas-gate4-app:8080 \
  -e GATE4_PROFILE=baseline -e GATE4_MODE=pilot \
  -e GATE4_RUN_ID=gate4-pilot-20261103 \
  grafana/k6:2.3.0 run --summary-export=/results/gate4-pilot-20261103.json /gate4.js

MSYS_NO_PATHCONV=1 docker run --rm --network atlas_default \
  -v "$repoRoot/load-tests/gate4.js:/gate4.js:ro" \
  -v "$repoRoot/ATLAS-application/build/gate4-results:/results" \
  -e ATLAS_BASE_URL=http://atlas-gate4-app:8080 \
  -e GATE4_PROFILE=baseline -e GATE4_MODE=load \
  -e GATE4_RUN_ID=gate4-baseline-20261103 -e PREALLOCATED_VUS=1 \
  grafana/k6:2.3.0 run --summary-export=/results/gate4-baseline-20261103.json /gate4.js

MSYS_NO_PATHCONV=1 docker run --rm --network atlas_default \
  -v "$repoRoot/load-tests/gate4.js:/gate4.js:ro" \
  -v "$repoRoot/ATLAS-application/build/gate4-results:/results" \
  -e ATLAS_BASE_URL=http://atlas-gate4-app:8080 \
  -e GATE4_PROFILE=queue-backlog -e GATE4_MODE=load \
  -e GATE4_RUN_ID=gate4-queue-backlog-20261103 -e PREALLOCATED_VUS=1 \
  grafana/k6:2.3.0 run --summary-export=/results/gate4-queue-backlog-20261103.json /gate4.js

docker stop atlas-gate4-app
docker compose exec -T postgres dropdb -U atlas atlas_gate4
```

출력 디렉터리는 Gradle `build/` 아래에 있으며 Git에서 제외됩니다. 요약 보고서만 저장소에 포함합니다.

## 결과

| 실행 ID / 프로필 | 시작 / 누락 반복 | Ticket | 결제 완료 | 재고 부족 | WAITING / checkout 409 |
|---|---:|---:|---:|---:|---:|
| `gate4-pilot-20261103` (60초, 1 VU) | 1,265 / 0 | 1,265 | 1,265 | 0 | 0 / 0 |
| `gate4-baseline-20261103` (10분) | 1,661 / 6 | 1,661 | 1,661 | 0 | 0 / 0 |
| `gate4-queue-backlog-20261103` (10분) | 1,667 / 0 | 1,667 | 167 | 1,500 | 0 / 0 |

| 실행 ID / 프로필 | HTTP 요청 / 실패 | 세션 p50 / p95 |
|---|---:|---:|
| `gate4-pilot-20261103` | 3,797 / 0 | 36.97 / 97.56 ms |
| `gate4-baseline-20261103` | 4,985 / 0 | 39.69 / 99.61 ms |
| `gate4-queue-backlog-20261103` | 3,503 / 0 | 21 / 57 ms |

기준 구성은 세션 1,667개를 예약했으며 k6에서 `dropped_iterations` 6건(0.36%)을 기록했습니다. HTTP 요청 실패는 없었습니다. 시작된 세션 1,661개는 모두 PAID 상태에 도달했습니다. 대기열 프로필은 HTTP 실패 없이 반복 1,667회를 모두 완료했습니다. 의도적으로 제한한 재고는 소진했지만 대기열 밀림은 발생하지 않았습니다.

### 데이터베이스 스냅샷

| 프로필 | 초기 재고 | 사용 가능 | 예약됨 | 확정됨 | 보존식 |
|---|---:|---:|---:|---:|---|
| 사전 실행 | 1,667 | 402 | 0 | 1,265 | true |
| 기준 구성 | 1,667 | 6 | 0 | 1,661 | true |
| 대기열 구성 | 167 | 0 | 0 | 167 | true |

세 스냅샷 모두 `available + reserved + claimed = initial_capacity`를 만족합니다. 최종 상태는 부하 출력과 일치했습니다. 기준 구성에는 `CHECKOUT_SUBMITTED` 티켓 1,661개, `PAID` 주문, `CAPTURED` 결제가 있었습니다. 대기열 구성에는 `CHECKOUT_SUBMITTED`/`PAID`/`CAPTURED` 흐름 167개와 재고 부족으로 인한 `CHECKOUT_FAILED`/`FAILED` 흐름 1,500개가 있었습니다. `WAITING` 티켓과 checkout `409`는 없었습니다.

### Docker 순간 측정값

| 측정 구간 | 앱 CPU / 메모리 | PostgreSQL CPU / 메모리 | k6 CPU / 메모리 |
|---|---:|---:|---:|
| 사전 실행 | 50.55% / 345.8 MiB | 12.96% / 88.19 MiB | 2.03% / 27.94 MiB |
| 기준 구성 | 10.91% / 335.1 MiB | 3.85% / 86.57 MiB | 2.09% / 32.14 MiB |
| 대기열 구성 | 4.62% / 335.6 MiB | 3.29% / 87.42 MiB | 1.74% / 11.04 MiB |

이 값은 `docker stats --no-stream` 순간 스냅샷이며, 최대값이나 시계열 측정값이 아닙니다.

## 해석과 한계

선택한 1× 속도에서는 세션 도착 간격이 약 `360ms`이며, 측정한 세션 p95는 `57–100ms`였습니다. 고정 VU 하나로 세션은 대개 다음 도착 전에 끝났으므로 K=1 대기열 프로필은 동시 대기열 경합을 시험하지 않았습니다. 낮은 도착률에서 재고가 소진되는 상황과 재고 보존을 확인했습니다. 실제 경합 프로필에는 별도로 정한 더 높은 도착률이나 더 긴 checkout 슬롯 점유가 필요합니다. 여기서는 어느 쪽도 추론하거나 추가하지 않았습니다.

Baseline의 `dropped_iterations` 6건은 원인을 분리하지 못함. test provider adapter가 success를 즉시 반환하므로 network latency와 provider unknown result는 workload에 포함되지 않음. Daily run, 2×/4× matrix, queue polling delay, cross-machine comparison은 실행하지 않음. 결과는 Windows/Docker 조건에 한정.
