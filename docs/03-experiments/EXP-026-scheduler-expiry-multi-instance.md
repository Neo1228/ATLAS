# EXP-026 — 만료 작업 scheduler·다중 context 경합

- 상태: Testcontainers에서 4회 반복 · runtime scheduler 설정 변경 없음
- 관련 작업: [Issue #247](https://github.com/Neo1228/ATLAS/issues/247), [ADR-0030](../02-decisions/adr/ADR-0030-bounded-application-scheduler.md), [Issue #95](https://github.com/Neo1228/ATLAS/issues/95), [EXP-024](EXP-024-postgres-contention-observation.md)

## 질문과 범위

- 현재 pool 3이 독립 scheduler task를 동시에 시작하는지, pool 1에서 queued task가 대기하는지 검증.
- 두 Spring application context의 독립 Hikari pool이 같은 PostgreSQL Seller-SKU counter에서 동시에 만료를 수행할 때 duplicate state change가 없는지 확인.
- Multi-context 경합은 Admission의 per-Seller-SKU counter 경로만 측정. Standalone Order와 grouped BuyerCheckout은 기존 단일 context 상태 전이 테스트 범위.
- Scheduler pool control: [`SchedulingConcurrencyTest`](../../ATLAS-application/src/test/java/com/atlas/application/config/SchedulingConcurrencyTest.java).
- Multi-context scenario: [`ScheduledExpiryMultiInstanceIntegrationTest`](../../ATLAS-application/src/test/java/com/atlas/ScheduledExpiryMultiInstanceIntegrationTest.java).
- [OrderServiceImpl.expireDueOrders](../../ATLAS-order/src/main/java/com/atlas/order/service/impl/OrderServiceImpl.java): `checkout_id IS NULL` standalone Order만 처리; misleading method name 교정.
- [CheckoutPaymentServiceImpl.expireDueCheckouts](../../ATLAS-order/src/main/java/com/atlas/order/service/impl/CheckoutPaymentServiceImpl.java): `checkout_id IS NOT NULL` BuyerCheckout 처리. 두 SQL 대상은 서로 겹치지 않음.

## 환경과 workload

| 항목 | 값 |
|---|---|
| Host | macOS 27.2 arm64 · 11 logical CPU · 18 GiB RAM |
| Container | Colima · Docker Engine 29.5.2 · 4 CPU · 9.69 GiB RAM |
| Runtime / DB | JDK 25.0.5 · PostgreSQL 18.6 Testcontainers |
| Spring contexts | 2 · 같은 JVM · 독립 DataSource/Hikari pool · pool max 12 each |
| Repeats | 4 · 3 focused runs + final full-suite run · profile당 10/100/300 due tickets |
| Observer | `pg_stat_activity` + `pg_blocking_pids`; 100 ms polling |

각 profile은 새 Seller/SKU/Campaign과 due ticket rows를 생성. Scheduler는 빈 DB 상태에서 초기 scan을 마친 뒤 테스트에서 정지; 실제 `expireDueTickets()` method를 두 context에서 barrier 동시 호출. 한 counter row를 blocker connection이 잠그고, direct waiter와 pending invocation 수를 관찰한 뒤 blocker 해제.

### 재현 방법

```sh
./gradlew --no-daemon :application-module:test \
  --tests com.atlas.application.config.SchedulingConcurrencyTest \
  --tests com.atlas.ScheduledExpiryMultiInstanceIntegrationTest
```

## 결과

### Scheduler pool control

| Pool size | Blocker release 전 시작 | 최대 동시 작업 | 시작 지연 sample (ms, 2 runs) |
|---:|---:|---:|---:|
| 1 | 1/3 | 1 | `[0, 505, 505]` · `[0, 500, 500]` |
| 3 | 3/3 | 3 | `[1, 0, 0]` · `[0, 0, 0]` |

500 ms는 blocker 관찰을 위한 test synchronization window. 운영 latency 목표나 scheduler lag SLO가 아님. 실제 application `taskScheduler`의 core pool size 3도 테스트에서 확인.

### Multi-context Admission profile

단위는 observer가 waiter를 처음 발견한 시점의 `query_start` 경과 시간(ms). 각 profile 4회 모두 direct waiter 1개와 `transactionid` event 확인; 두 context invocation 모두 blocker 해제 전 미완료.

| Due ticket rows | Query elapsed samples (ms, n=4) | Changed rows | Poll interval |
|---:|---|---:|---:|
| 10 | 103.566 · 107.720 · 111.740 · 104.257 | 4회 모두 10 | 100 ms |
| 100 | 12.391 · 104.070 · 104.856 · 103.610 | 4회 모두 100 | 100 ms |
| 300 | 104.210 · 102.412 · 108.265 · 101.105 | 4회 모두 300 | 100 ms |

각 run에서 해당 campaign의 due ticket이 전부 `EXPIRED`, `checkout_slot_held=false`; Seller-SKU sequence 값 불변. Concurrent invocations는 한 번만 상태를 변경.

- 전체 검증: `./gradlew --no-daemon clean check :application-module:bootJar` 성공 · 59 test suites · 277 tests · failure/error/skip 0.

## 해석과 한계

- 100 ms는 observer 재조회 간격. Waiter가 재조회 직전에 발생하면 query-age sample은 100 ms보다 작을 수 있음. `query_start` 경과값은 lock duration이나 API latency가 아님.
- `pg_blocking_pids`는 직접 blocker 관계를 반환. 같은 lock queue의 후속 waiter가 최초 blocker PID를 직접 가리키지 않을 수 있어 direct waiter 1개와 두 invocation pending 상태를 함께 기록.
- Testcontainers 반복은 local synthetic concurrency test. Throughput·RPS/TPS·p50/p95·capacity 결론 없음.
- Issue #95의 300건 Order median 5.225초와 Admission 20.1 ms는 재사용 fixture가 없는 과거 run. Source revision·fixture provenance 차이로 직접 비교하지 않음.
- Pool 1/3 비교는 one-shot test scheduler 작업이며 실제 fixed-delay 만료 작업의 운영 lag 측정이 아님. Host/container 자원 allocation만 기록; 실제 CPU·memory 사용량은 측정하지 않음.
- 두 Spring context는 같은 JVM 안에서 실행. 별도 OS process의 startup/resource isolation은 검증하지 않음.
- Multi-context Order/BuyerCheckout 중복 실행은 측정하지 않음. 해당 경로는 기존 단일 context integration test와 query scope 분리로만 검증.
- 결과로 pool size·fixed-delay interval 변경이나 distributed lock 도입을 결정하지 않음.

## 외부 참고

- [PostgreSQL `pg_blocking_pids`](https://www.postgresql.org/docs/current/functions-info.html): blocker backend 관측.
- [Issue #95](https://github.com/Neo1228/ATLAS/issues/95): 이전 10/100/300-row report-only measurement와 한계.
