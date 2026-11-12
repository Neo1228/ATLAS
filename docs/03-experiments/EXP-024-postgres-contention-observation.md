# EXP-024 — PostgreSQL 경합 관측

- 상태: 4회 반복 관측 완료 · 성능 튜닝 근거로는 불충분
- 관련 작업: [Issue #246](https://github.com/Neo1228/ATLAS/issues/246), [ADR-0005](../02-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md), [ADR-0025](../02-decisions/adr/ADR-0025-limited-coupon-redemption.md), [ADR-0030](../02-decisions/adr/ADR-0030-bounded-application-scheduler.md)

## 질문과 범위

Admission expiry, inventory batch reservation, coupon reservation에서 의도한 PostgreSQL row contention이 실제 blocker/waiter 관계로 나타나는지 확인. 이 실험은 throughput·API latency·용량 한계 산정이나 운영 설정 변경을 목적으로 하지 않음.

- Admission: 서로 다른 Seller-SKU counter를 처리하는 expiry sweep
- Inventory: 2개 pool을 선택하는 `reserveAll` batch
- Coupon: cap이 설정된 `seller_coupon` row 예약
- 테스트 전용 blocker와 [`PostgresLockWaitProbe`](../../ATLAS-application/src/test/java/com/atlas/PostgresLockWaitProbe.java) 사용

## 환경과 실행

| 항목 | 값 |
|---|---|
| Host | macOS 27.2 arm64 · 11 logical CPU · 18 GiB RAM |
| Container | Colima · Docker Engine 29.5.2 · 4 CPU · 9.69 GiB RAM |
| Runtime / DB | JDK 25.0.5 · PostgreSQL 18.6 Testcontainers |
| Observer | `pg_stat_activity` + `pg_blocking_pids`; 100 ms poll interval |
| Repeats | 각 시나리오 4회 관측; 동일한 local test profile |

재현 명령:

```sh
./gradlew --no-daemon --rerun-tasks :application-module:test \
  --tests com.atlas.admission.AdmissionExpiryTransactionIntegrationTest \
  --tests com.atlas.SellerCouponRedemptionIntegrationTest \
  --tests com.atlas.inventory.InventoryLockWaitIntegrationTest
```

## 관측 결과

`query elapsed`는 observer가 waiter를 처음 발견한 시점의 `clock_timestamp() - query_start`. Statement가 시작된 뒤 lock 대기 전까지의 시간도 포함하므로 정확한 lock-held duration이나 endpoint latency가 아님.

| 경로 | 관측값 (ms, 4회) | 대기 이벤트 | 상태 불변조건 |
|---|---:|---|---|
| Admission expiry counter | 23.719 · 25.443 · 25.712 · 22.396 | `transactionid` 4/4 | 첫 key commit, 다음 key 대기; blocker 해제 후 due ticket 2건 정확히 만료 |
| Inventory selector | 93.747 · 100.952 · 96.688 · 103.212 | `transactionid` 4/4 | 대기 중 partial reservation 없음; 해제 후 2건 예약 및 pool capacity 보존 |
| Coupon reservation | 47.229 · 23.007 · 56.788 · 82.041 | `transactionid` 4/4 | blocker 전 checkout 미완료; 해제 후 reserved count와 redemption ledger 일치, cap 보존 |

- 각 경로의 sample 수는 4. 범위만 기술; percentile·평균·성능 순위 추론 없음.
- 세 경로 모두 지정 blocker와 waiter PID 관계를 확인하고 blocker 해제 후 invariant 통과.
- `transactionid`는 PostgreSQL에서 관측된 wait event. 이 결과만으로 lock 경합이 실제 서비스 병목이라고 결론 내릴 수 없음.
- 전체 검증: `./gradlew --no-daemon clean check :application-module:bootJar` 성공 · 57 test suites · 273 tests · failure/error/skip 0.

## 해석과 다음 판단

현재 증거는 테스트가 row contention과 transaction 결과를 관측할 수 있음을 보여줌. 네 번의 sampled query age만으로 p95, RPS/TPS, connection-pool 한계, index 변경 또는 인프라 도입을 정당화하지 않음. 다음 성능 비교는 최신 JWT/API 계약을 반영하는 [Issue #248](https://github.com/Neo1228/ATLAS/issues/248)의 k6 workload가 준비된 뒤 별도 통제 환경에서 수행.

- `pg_blocking_pids`는 blocker backend를 확인하는 데 사용. [PostgreSQL — System Information Functions](https://www.postgresql.org/docs/current/functions-info.html)
- 이전 inventory lock-wait 시나리오: [EXP-021](EXP-021-inventory-lock-wait.md)
