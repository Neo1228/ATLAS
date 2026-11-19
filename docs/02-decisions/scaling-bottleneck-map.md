# ATLAS 확장 병목 지도

**추적 이슈:** [#360](https://github.com/Neo1228/ATLAS/issues/360)

**상태:** 실측 기반 · 로컬 3–5 replica 환경

## 이 문서의 목적

개별 실험이 "이 설정을 바꾸니 좋아졌다"를 기록했다면, 이 문서는 **부하가 커질 때 어느 계층이 어떤 순서로 무너지는지**를 하나의 지도로 정리합니다.

목적은 두 가지입니다.

1. **다음 병목을 예측한다.** 지금 고친 계층이 아니라 그 다음을 미리 안다.
2. **인프라 도입 시점을 정한다.** 측정이 지목하지 않은 계층은 건드리지 않는다.

## 측정된 확장 곡선

3–5 replica, PostgreSQL 2 vCPU, PgBouncer, 2M 상품 기준입니다.

| Offered it/s | HTTP RPS | 완료 | 드롭 | avg | p95 | 지배 병목 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 40 | 240 | 4,801 | 0% | — | 6.5 ms | 없음 |
| 80 | 480 | 9,601 | 0% | — | 7.3 ms | 없음 |
| 160 | 480–960 | 24,001 | **0%** | 2 ms | 4 ms | 없음 |
| 320 | 960 | 38,400 | **0%** | 2 ms | 5 ms | 없음 (여유) |
| 640 | 1,920 | 24,076 | **68.7%** | 1,356 ms | 3,428 ms | **PostgreSQL + 앱 CPU** |

**검증된 안전 구간은 320 it/s(960 RPS)**이고, 640 it/s에서 붕괴합니다.

## 병목의 순서

부하를 올리며 계층이 차례로 드러났습니다. 각 단계는 **측정으로 확인된 뒤에만** 다음으로 넘어갔습니다.

```text
1. 잘못된 구성        pooler를 안 거치는 replica  → 측정 자체가 틀림
2. 커넥션 예산 초과    pool 20 × 3 = max_connections 60 → 즉시 붕괴
3. 로드밸런서 연결     worker_connections 4096 vs 6.5천 연결 → p95 12.8s
4. PostgreSQL CPU     640 it/s에서 130–159% → 현재 벽
5. (미측정) 앱 CPU    5대 모두 75–82%, 여유 있음
```

### 1단계: 잘못된 구성이 측정을 오염시켰다

`compose.capacity-distributed.yaml`이 replica에 `ATLAS_DB_URL: jdbc:postgresql://postgres:5432/atlas`를 하드코딩해, 뒤에 붙인 pooler 오버레이를 덮어썼습니다. 이 상태의 "pooler 측정"은 pooler를 한 번도 거치지 않았습니다.

**교훈:** 인프라를 추가했는데 성능이 그대로라면, 그것이 **실제로 쓰이고 있는지** 먼저 확인해야 합니다. 설정 파일의 우선순위는 성능보다 먼저 검증할 대상입니다.

### 2단계: 연결 예산은 즉시 무너진다

| replica당 pool | 총 연결 | 드롭 | 달성 |
| ---: | ---: | ---: | ---: |
| 5 | 15 | 4.9% | 152 it/s |
| 20 | **60** | **73.1%** | 43 it/s |

`max_connections=60`에 3×20=60이 맞닿자 `FATAL: sorry, too many clients already`가 261회 발생했습니다. **연결 예산은 선형이 아니라 절벽입니다.**

### 3단계: 로드밸런서가 앱보다 먼저 죽는다

640 it/s에서 Tomcat executor가 `active 0, queued 3–4`를 보였습니다. 앱은 요청을 **받지도 못하고** 있었습니다. LB는 `ESTABLISHED 3,511 + TIME_WAIT 3,012`를 감당하려 했고 `worker_connections 4096`을 넘겼습니다.

`worker_connections 16384` + `keepalive_requests 10000`으로 고친 뒤:

| 항목 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| p95 | 12,841 ms | **3,428 ms** |
| 드롭 | 67.6% | 68.7% |

**지연은 3.7배 개선됐지만 드롭은 그대로**입니다. LB는 원인의 일부였고, 진짜 벽은 그 뒤에 있었습니다.

**교훈:** "앱이 느리다"는 관측이 항상 앱 문제는 아닙니다. 유휴 상태의 앱(`active 0`)은 **요청이 도착하지 않는다**는 신호입니다.

### 4단계: 현재 벽은 PostgreSQL이다

LB를 고친 뒤 5 replica로 640 it/s를 측정했습니다.

| 시각 | 앱 CPU (5대) | PostgreSQL | Tomcat queued |
| ---: | --- | ---: | ---: |
| 17초 | 76–82% | **130%** | 343 |
| 34초 | 71–89% | **141%** | 211 |
| 51초 | 46–86% | **140%** | 441 |
| 68초 | 76–78% | **159%** | 332 |

앱 5대는 75–82%로 **균등하게 분산**됐고(1단계의 keepalive 편중이 해소됨), PostgreSQL이 130–159%로 **2코어를 넘어서려는 상태**입니다. Tomcat queued 200–440은 앱이 DB 응답을 기다리며 쌓인 것입니다.

**여기서 확장 방향이 바뀝니다.** 앱 replica를 더 늘려도 DB 부하만 비례해 증가합니다.

## 지금 시점의 판단

| 계층 | 상태 | 다음 조치 |
| --- | --- | --- |
| 로드밸런서 | 16,384 연결, 편중 해소 | 유지 |
| 앱 (5 replica) | 75–82%, 여유 | **증설해도 효과 없음** (DB가 벽) |
| PgBouncer | active 9–14, waiting 0 | 유지 (병목 아님) |
| **PostgreSQL** | **130–159%, 2 vCPU** | **다음 투자 지점** |

## PostgreSQL이 벽일 때 선택지

측정이 DB를 지목했으므로, 이제 인프라 검토가 정당화됩니다. 다만 순서가 있습니다.

| 선택지 | 조건 | 예상 효과 | 검증 방법 |
| --- | --- | --- | --- |
| **쿼리·인덱스 개선** | 요청당 6.02 쿼리 | DB 시간 자체 감소 | 같은 부하에서 DB CPU |
| **DB 코어 확장** | 2 vCPU 포화 | 상한 상승 | 4 vCPU에서 640 재측정 |
| **read replica** | 읽기 비중 확인 필요 | 읽기 부하 분산 | replica lag + p95 |
| **캐시** | 반복 읽기 확인 필요 | DB 읽기 제거 | hit ratio + DB CPU |

**우선순위는 쿼리·인덱스 → DB 코어 → replica/캐시**입니다. 앞의 둘은 측정 근거가 이미 있고(요청당 6.02 쿼리, 2 vCPU 130–159%), 뒤의 둘은 **읽기/쓰기 비중과 반복 패턴**을 먼저 측정해야 합니다.

## 아직 측정하지 않은 것

정직하게 남깁니다.

| 항목 | 이유 |
| --- | --- |
| 읽기 대 쓰기 비중 | `pg_stat_statements`로 쿼리 수는 쟀지만 부하 유형 분리는 안 함 |
| DB 코어 4 vCPU에서의 확장 | 미측정 |
| read replica lag | replica를 띄우지 않음 |
| 캐시 hit ratio | 캐시를 도입하지 않음 |
| 640 초과 구간 | 붕괴 지점이라 측정 불가 |

## 관측 공백

측정 중 발견한 모니터링 문제입니다.

- Prometheus scrape target이 `app-a/b/c`로 고정되어 **app-d/e는 수집되지 않습니다.** replica를 늘려도 그 replica의 지표는 보이지 않습니다.
- nginx 에러 로그가 파일로 남지 않아 연결 고갈을 로그로 확인할 수 없었습니다(이번에 `error_log` 추가).
- PgBouncer 지표는 exporter를 붙인 뒤에야 확보됐습니다(`admin_users` 미지정으로 첫 시도 실패).

**replica를 늘리는 순간 관측도 함께 늘려야** 확장이 검증됩니다. 그렇지 않으면 "늘렸는데 왜 안 빨라지지"를 진단할 수 없습니다.

## 재현

```bash
# 5 replica, pooler 경유, LB 16384
ATLAS_POOL_SIZE=5 \
ATLAS_DB_URL="jdbc:postgresql://pgbouncer:5432/atlas" \
ATLAS_POOLER_SIZE=48 ATLAS_POOLER_MAX_DB=52 \
docker compose -f compose.yaml \
  -f .local/compose.capacity-node.yaml \
  -f .local/compose.capacity-distributed.yaml \
  -f .local/compose.capacity-pooler.yaml \
  --profile observability --profile loadtest up -d
```

## 이 문서의 한계

- 단일 호스트(Docker VM 8 CPU / 13.5 GiB) 측정입니다. 앱·DB·LB·k6가 같은 커널을 공유하므로 절대 수치는 프로덕션과 다릅니다.
- 2M 상품이라는 합성 데이터이며, 실제 접근 분포(핫키·캐시 적중)와 다릅니다.
- `docker stats`는 5초 샘플이므로 순간 최대가 아닙니다.
- 640 it/s의 드롭 68.7%는 **여러 계층이 동시에 포화**된 결과이며, 단일 원인으로 귀속되지 않습니다.
