# EXP-035: 단일 노드 한계와 분산 확장 비교

> **원자료 보관 정책:** 이 문서의 표가 측정 결과의 정본이다. k6 summary·EXPLAIN JSON 같은 원자료는 재현 시 로컬에서 다시 생성하며 저장소에 커밋하지 않는다(용량과 노이즈 때문).

**추적 이슈:** [#342](https://github.com/Neo1228/ATLAS/issues/342)

**상태:** Measured — 로컬 2 vCPU 노드 한계와 replica 확장 결과를 기록. 프로덕션 SLA 아님.

## 목적

EXP-033은 원격 4 vCPU 러너에서 체크아웃 흐름의 대략적인 상한(**130.25 HTTP RPS**, 드롭 0.49%)을 보여줬지만 다음 질문에 답하지 못했습니다.

1. 병목이 **애플리케이션 CPU**인지, **JDBC pool**인지, **PostgreSQL**인지.
2. 노드가 포화될 때 **어떤 지표가 먼저 움직이는지**.
3. 애플리케이션을 늘리면 실제로 개선되는지, 아니면 DB가 즉시 다시 병목이 되는지.

## 왜 이 순서인가

- **먼저 단일 노드**: 분산을 먼저 재면 "replica를 늘렸더니 빨라졌다"만 남고, 무엇이 병목이었는지 알 수 없습니다.
- **DB는 그대로, 앱만 증설**: 앱과 DB를 동시에 키우면 원인 귀속이 불가능합니다. 이 실험은 application replica 수만 바꿉니다.
- **per-container 스펙 고정**: 단일 노드 앱은 2 vCPU / 2 GiB, 분산 단계의 각 replica도 동일합니다.
- **로컬 자원을 전부 쓰지 않음**: 11 CPU / 18 GiB 전체를 쓰면 그 수치는 서비스가 아니라 개발 장비를 설명합니다.

기각한 대안:

| 대안 | 기각 이유 |
| --- | --- |
| 로컬 맥 전체 자원 사용 | 재현 불가. 다른 장비·다른 날의 결과와 비교 불가 |
| Redis 캐시 먼저 | EXP-032에서 필요성 근거 부족. 인프라부터 늘리면 원인을 가림 |
| Kafka/외부 큐 먼저 | DB claim이 병목이라는 측정이 없음 |
| DB read replica 먼저 | 읽기 병목 증거가 없고 replica lag 실패 모드가 추가됨 |
| Hikari pool 확대 | DB 병목 시 대기를 옮길 뿐 처리량 증가 아님 |

## 측정 환경

| 항목 | 값 |
| --- | --- |
| 호스트 | Apple Silicon 11 CPU / 18 GiB, 의도적으로 일부만 사용 |
| 애플리케이션 | `cpus: 2.0`, `mem_limit: 2g`, Tomcat max threads 64 |
| PostgreSQL | `postgres:18.6`, `cpus: 2.0`, `mem_limit: 2g`, `max_connections=60`, `shared_buffers=256MB` |
| 분산 단계 | 동일 스펙 replica 3개 + nginx round-robin, PostgreSQL은 그대로 |
| 관측 | Prometheus 5 s scrape, 인스턴스별 target(`instance_name`), Grafana 12.1.1 + image renderer |
| 데이터셋 | seller 200 · product 100,000 · SKU 200,000 · ONLINE_STORE 100,000 |
| 시드 | `load-tests/seed-catalog-read.sql` (stack 재생성마다 1회) |
| 시나리오 | `load-tests/catalog-read-capacity.js` — 1 iteration = 6 GET |

한 iteration은 공개 GET 6개입니다: 전역 첫 페이지, 두 번째 페이지(cursor), 가격 범위 필터, `needle` 검색, `broad` 검색, 상품 상세. 따라서 **HTTP RPS = iteration rate × 6**입니다.

## 결과

원자료: ``results/exp-035/exp-035-summary.json``. 패널 캡처는 각 실행 디렉터리의 `manifest.json`(창·크기·SHA-256)으로 추적합니다.

### 단일 노드 (2 vCPU / 2 GiB 앱 1대)

| Offered it/s | HTTP RPS | 완료/계획 | 드롭 | p95 / max (ms) | App CPU mean/peak | PG CPU mean/peak | Hikari pending | PG backends |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 30 | 601/601 | 0 (0.0%) | 77 / 200 | 21 / 66% | 82 / 88% | 0 | 12 |
| 10 | 60 | 1200/1201 | 1 (0.1%) | 72 / 381 | 26 / 65% | 155 / 164% | 0 | 12 |
| 20 | 120 | 1144/2400 | 1256 (52.3%) | 1820 / 3792 | 43 / 127% | 201 / 207% | 57 | 12 |

**관찰:** 10 → 20 it/s에서 p95가 72 ms에서 1,820 ms로 **25배** 악화되고 계획의 52%가 드롭됩니다. 이때 애플리케이션 CPU는 평균 43%(2코어 중)로 **여유가 남아 있는데도** PostgreSQL은 200%(= 2코어 한계)에 붙습니다. Hikari pending이 57까지 올라가 애플리케이션이 DB 연결을 기다린 것입니다.

즉 단일 노드의 첫 병목은 **애플리케이션 CPU가 아니라 PostgreSQL**입니다. 이 상태에서 pool을 키우면 대기 위치만 바뀌고 처리량은 늘지 않습니다.

### 분산 (동일 스펙 replica × 3 + nginx)

| Offered it/s | HTTP RPS | 완료/계획 | 드롭 | p95 / max (ms) | App CPU (max replica mean) | PG CPU mean/peak | Hikari pending | PG backends |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 30 | 599/601 | 2 (0.3%) | 82 / 699 | 15% | 86 / 93% | 0 | 32 |
| 10 | 60 | 1115/1201 | 86 (7.2%) | 1398 / 2434 | 26% | 200 / 202% | 8 | 32 |
| 20 | 120 | 1098/2401 | 1303 (54.3%) | 2437 / 4552 | 30% | 199 / 201% | 30 | 32 |

**관찰:** replica를 3배로 늘렸지만 **10 it/s에서 오히려 처리량이 줄었고**(1200 → 1115 완료), p95는 72 ms에서 1,398 ms로 악화됐습니다. PostgreSQL은 이미 200%에 붙어 있고, 애플리케이션 CPU는 26%로 대부분 유휴입니다. PostgreSQL backend 수는 12 → 32로 늘었지만 **DB를 확장하지 않았으므로** 동일한 2코어 한계에 더 많은 경쟁자가 붙은 형국입니다.

### 단일 vs 분산 비교

| Offered it/s | 완료 단일 → 분산 | p95 ms 단일 → 분산 | PG CPU 단일 → 분산 |
| ---: | ---: | ---: | ---: |
| 5 | 601 → 599 | 77 → 82 | 82% → 86% |
| 10 | 1200 → 1115 | 72 → 1398 | 155% → 200% |
| 20 | 1144 → 1098 | 1820 → 2437 | 201% → 199% |

**결론:** 애플리케이션 replica만 늘리는 것으로는 개선되지 않습니다. 이 워크로드에서 확장 단위는 **PostgreSQL**이며, 앱 증설은 DB 경쟁만 심화시킵니다. 이것이 "무엇을 늘릴지 먼저 측정해야 한다"는 이 실험의 핵심 결과입니다.

## 왜 분산이 더 느렸는가

세 가지가 겹쳤고, 모두 측정으로 확인됩니다.

1. **DB가 이미 포화**: 단일 노드에서 PG CPU가 200%(2코어 상한)였습니다. replica를 늘려도 총 DB 용량은 그대로입니다.
2. **연결 경쟁 증가**: backend 12 → 32. 각 replica의 Hikari pool이 같은 `max_connections=60` 예산을 나눠 쓰므로 개별 요청의 대기 시간이 늘어납니다.
3. **백그라운드 작업 중복**: `@Scheduled` expiry sweep이 replica마다 실행됩니다. `expiry_runs`가 분산 r10·r20에서 174·162로 감소한 것은 scheduler thread가 밀렸다는 신호입니다.

이 중 3번은 분산 환경에서 반드시 다뤄야 할 설계 문제입니다. 현재 구조는 **다중 인스턴스에서 백그라운드 작업이 중복 실행**되며, DB 기반 lease로 일부 완화되지만 모든 sweep이 그런 것은 아닙니다.

## 재현

```bash
# 단일 노드
docker compose -f compose.yaml -f .local/compose.capacity-node.yaml \
  --profile observability --profile loadtest build application
LOAD_COMPOSE_FILES="compose.yaml .local/compose.capacity-node.yaml" \
LOAD_STACK_SERVICES="application postgres-exporter prometheus grafana grafana-image-renderer" \
LOAD_WORKLOAD=reads LOAD_PROBE_MODE=matrix LOAD_RATES="5 4;10 8;20 16" \
LOAD_RUN_PREFIX=exp035single GRAFANA_CAPTURE=1 \
bash load-tests/run-exp033-capacity-matrix.sh

# 단일 → 분산 전체 비교
ATLAS_JWT_SIGNING_SECRET=... bash load-tests/run-exp035-single-to-distributed.sh
```

## 해석 규칙과 한계

- 드롭이 0이 아니면 그 지점을 **용량 상한으로 부르지 않습니다**. "이 rate에서 지연이 무너지기 시작했다"로만 기록합니다.
- `rate()` 기반 값은 최소 2회 scrape가 필요합니다. 캡처 창은 부하 구간 + 직전 60초입니다.
- 컨테이너 CPU는 5초 간격 `docker stats` 샘플이며 순간 최대가 아닙니다. 200%는 2코어 상한에 해당합니다.
- **시드 타이밍**: 초기 실행에서 시드(약 70만 행)가 첫 rate의 지표 창 안에 들어가 첫 행이 오염됐습니다. 최종 측정은 시드를 각 stack 생성 직후·부하 시작 전에 수행하고, 카탈로그가 비어 있으면 실패하도록 했습니다.
- 이 결과는 로컬 단일 호스트·고정 스펙의 비교이며, 프로덕션 토폴로지나 SLA가 아닙니다.
- 로컬 호스트는 다른 프로세스와 자원을 공유합니다. cgroup 제한으로 영향은 줄였지만 완전히 제거하지는 못합니다.

## 후속 작업

1. **PostgreSQL이 실제 병목인지 쿼리 단위로 분해**: 이 워크로드에서 `product_listing`의 keyword predicate(`position(... in lower(name))`)는 정렬 index를 타며 **전체 100k 행을 스캔**합니다. 실측 계획에서 409 ms, trigram index를 써도 플래너는 정렬 index를 선택했습니다. 별도 이슈로 분리합니다.
2. **다중 인스턴스 백그라운드 작업 조정**: replica마다 expiry sweep이 도는 것을 lease/leader election으로 정리해야 합니다.
3. **DB 읽기 확장 검토**: 이제 read replica 또는 connection multiplexing의 근거가 생겼습니다. 다만 1번(쿼리 형태)을 먼저 고치는 것이 비용 대비 효과가 큽니다.
