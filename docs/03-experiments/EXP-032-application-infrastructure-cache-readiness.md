# EXP-032: application infrastructure / local-cache readiness

**상태:** 기존 측정 재분석 + local PostgreSQL synthetic query probes · 운영 capacity claim 아님

## 목적

Redis/Caffeine이나 Hikari pool 변경 전에 현재 ATLAS의 scheduler, datasource, observability, local cache 상태를 수치로 정리한다. 목표는 새 인프라 도입이 아니라 현재 병목이 증명됐는지와 다음 load test gate를 분명히 하는 것이다.

## 현재 구현 현황

- Repository `application*.yml`에는 `spring.datasource.hikari.*` 명시 설정이 없다. 따라서 **deployment override가 없다는 조건에서만** Hikari defaults가 적용된다: [HikariCP 공식 설정 문서](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby)의 `maximumPoolSize` default 10, `connectionTimeout` default 30,000 ms. 실제 deployment env/config은 이 repository에서 확인하지 못했다.
- `SchedulingConfiguration`은 일반 scheduler 4 threads와 payment-recovery 전용 scheduler 1 thread를 둔다. `@Scheduled` worker는 5개이며 admission/order/checkout expiry와 webhook retry는 기본 1초 polling, payment recovery는 초기 지연 뒤 1초 polling이다. 동시에 모두 DB connection 한 개씩 들고 있다고 가정한 보수적 상한은 5/10 connections(50%)이며 실제 동시 점유는 관측하지 않았다.
- Caffeine, `@Cacheable`, `@CacheEvict` 등 application local-cache 구현은 검색상 0건이다.
- Actuator와 Prometheus registry는 이미 의존성에 포함된다. `application-observability.yml`은 management를 localhost:8081에 두고 `health,prometheus`만 노출한다. [Spring Boot metrics docs](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)는 datasource pool metrics와 Hikari-specific `hikaricp` prefix를 문서화한다. 새 exporter/service 도입 없이 기존 profile로 `hikaricp.connections.active`, `pending`, `max`, `timeout`과 `http.server.requests`를 측정할 수 있다.

## 기존 load evidence 재사용

[EXP-016](EXP-016-buyer-checkout-comparison.md)의 controlled 3-run 결과:

| Workload | Arrival input | HTTP rate | Checkout TPS | session p95 median | Errors/dropped | Hikari active max | Deadlocks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| one-seller buyer checkout | 2.78 sessions/s | 11.113 RPS | 2.778 | 47 ms | 0 / 0 | 1 | 0 |
| two-seller buyer checkout | 2.78 sessions/s | 11.115 RPS | 2.779 | 50 ms | 0 / 0 | 1 | 0 |

Runner host는 11 logical CPU / 18 GiB, Docker VM은 4 CPU / 약 9.7 GiB. 이는 해당 synthetic checkout workload의 120초 test이지 review/wishlist read mix, pool saturation ceiling, production traffic 대표성이 아니다. 도착률이 입력으로 고정돼 throughput ceiling을 찾은 시험도 아니다.

## Idle-query probes

Disposable PostgreSQL 18.6에서 due backlog가 0인 상태를 만들었다: 200,000 non-reserved order rows + 2,000 future-reserved orders, 20,000 future `WAITING` admission tickets. 기존 partial indexes와 같은 predicates로 `EXPLAIN (ANALYZE, BUFFERS)` 측정:

| Idle candidate query | Result | Execution time |
| --- | ---: | ---: |
| order expiry, `LIMIT 100` | 0 rows, expiry index scan | 0.038 ms |
| admission expiry, `LIMIT 200` | 0 rows, V41 composite index scan | 0.022 ms |

두 query가 각 1초마다 실행된다고 가정하면 PostgreSQL server execution time 합은 `0.060 ms/s × 86,400 s/day = 5.184 s/day`다. 이는 SQL execution만이며 client/connection checkout/network cost 제외. 다른 세 scheduled jobs는 이 probe에 포함되지 않는다. 두 측정만으로 polling 비용이 주요 DB bottleneck이라고 볼 근거는 없다.

## Durable resume workflow/cache 경계

- Payment recovery job/lease 상태는 PostgreSQL에 저장되고 worker는 durable due rows를 claim합니다. `PaymentRecoveryWorker`는 `atlas.payment.recovery.duration` timer와 outcome counters를 이미 등록합니다.
- Admission expiry ticket state도 PostgreSQL source-of-truth입니다. `(expires_at, ticket_id)` progress cursor는 process-local이므로 restart 때 초기화되지만 idempotent expiry가 DB due rows를 다시 scan합니다.
- Recovery state/lease/결제 상태는 local cache에 두지 않습니다. Cache 후보가 있다면 최종 도메인 전이와 분리된 read-only catalogue projection이어야 합니다.
- Payment recovery duration/outcome metrics already exist. This follow-up adds expiry `duration`, `runs{workflow,outcome}`, and `changed{workflow}` metrics using the current Prometheus registry. Backlog age and per-pair failure/query-timeout detail remain unmeasured; do not introduce a new monitoring service for those signals.

## Local-cache break-even과 no-go

[EXP-031](EXP-031-public-product-existence-check.md)의 `EXISTS` query는 100k product / 500k SKU synthetic DB에서 SQL **0.064 ms / round trip 1회**였다. SQL server CPU만 계산하면:

- 1,000 cacheable checks/s → 약 `0.064` CPU-seconds/second (= 0.064 core).
- 4,000 checks/s → 약 `0.256` core.
- 15,625 checks/s → 약 1 full core (`1 / 0.000064`).

이 산식은 SQL execution only이며 production DB RTT, ORM/HTTP cost, actual endpoint QPS/hit distribution을 포함하지 않는다. 현재 review/wishlist QPS와 repeated-key hit ratio는 미측정이다. Availability는 `Product.status` 및 `product_channel_availability`에 의해 결정되고 여러 instance 간 invalidation channel도 없다. 따라서 현재 **Caffeine local cache는 no-go**다: 절대 SQL cost는 작고 stale availability/read semantics와 cache invalidation risk를 정량화할 workload evidence가 없다. 상세 product/price cache는 mutable surface가 더 넓어 더더욱 보류한다.

**Hikari pool을 10보다 키우거나 Redis/Kafka를 추가하는 것도 no-go**다. EXP-016에서는 11.1 HTTP RPS에 Hikari active max 1이었지만, 단일 checkout workload는 saturation test가 아니다. Pending/acquisition latency, p95/p99 under rising load, deployment DB connection headroom이 확인되지 않았다.

## 다음 도입 gate (새 infra 없이 먼저 측정)

1. The remote checkout capacity matrix is complete; see [EXP-033](EXP-033-remote-capacity-matrix.md) and its raw JSON summaries. At the achieved ~130 HTTP RPS, Hikari sampled active max was 2/10 with pending/timeouts 0; the top offer still dropped 26/5,338 iterations, so do not infer a throughput ceiling or change the pool.
2. Local-cache evaluation still needs a separate read-heavy review/wishlist workload with hot-key distribution and request QPS. The checkout workload cannot establish cache hit ratio, staleness tolerance, or invalidation behavior.
3. Pool-change gate: sustained `hikaricp.connections.pending > 0` or acquire timeout plus DB CPU/`max_connections` headroom; size via Little's Law `required connections ≈ DB arrival rate × connection hold time`. Include up to five concurrent scheduled workers in the estimate but do not treat them as a fixed reserved allocation.
4. Local-cache gate: measure endpoint QPS, hot-key hit ratio, DB RTT, and stale-read tolerance; compare cache on/off under the same read workload. Adopt only with repeatable p95/connection-pressure or DB CPU improvement and valid invalidation semantics.
5. Consider Redis/Kafka only if a demonstrated shared-cache/queue requirement remains after using PostgreSQL, existing Hikari, and the current Prometheus exporter. The remote run provides no such evidence.

## 재현 SQL

`docs/03-experiments/EXP-032-application-infrastructure-cache-readiness.sql`은 위 idle query cardinality/plan 재현용 disposable PostgreSQL script다. 본 측정은 운영 DB에서 실행하지 않았다.
