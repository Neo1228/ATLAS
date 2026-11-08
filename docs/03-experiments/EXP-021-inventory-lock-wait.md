# EXP-021 — 재고 lock-wait 관측

**상태:** 통합 테스트 완료 · production 동작 변경 없음

## 목적과 대상 경로

요청 시작 barrier만으로는 PostgreSQL 내부 대기를 증명할 수 없음. 실제 blocker가 보유한 `inventory_pool` 행 때문에 `reserveAll`의 location-selector `SELECT ... FOR UPDATE OF pool`이 대기하는지 DB metadata로 확인하고, blocker 해제 후 예약 원자성과 capacity 보존을 검증.

- 소스에서 확인한 대기 지점: [`JdbcLocationRepository.select`](../../ATLAS-inventory/src/main/java/com/atlas/inventory/repository/JdbcLocationRepository.java) → `SELECT ... FOR UPDATE OF pool`
- 호출 순서: `reserveAll`은 seller/location 선택을 마친 뒤 stock mutation을 수행
- 테스트: [`InventoryLockWaitIntegrationTest`](../../ATLAS-application/src/test/java/com/atlas/inventory/InventoryLockWaitIntegrationTest.java)
- 선행 근거: [EXP-019](EXP-019-inventory-batch-lock-order.md)는 요청 시작 barrier만 기록하며 DB lock wait는 관측하지 않음

## 시나리오

- PostgreSQL 18.6 Testcontainers · 판매자 2곳 · SKU 2개 · location별 pool capacity 1
- 별도 backend blocker가 UUID 순서상 두 번째 seller의 pool row를 잠금
- Worker가 `reserveAll`을 시작하고 해당 selector query에서 대기
- Observer는 100 ms 간격으로 최대 15초 `pg_stat_activity`·`pg_blocking_pids`를 조회
- 대기 중 두 pool의 committed counters는 변경 없음, 해당 Order reservation은 0건
- Blocker transaction commit 후 2건 `RESERVED`; 각 pool `available=0, reserved=1, claimed=0`
- 실패/timeout 시 blocker rollback/close 우선, worker/executor를 제한시간 내 정리

## 실행 결과

- `InventoryLockWaitIntegrationTest`: **1 test · 0 failure · 0 error · 0 skipped**
- 전체 `./gradlew --no-daemon check :application-module:bootJar`: **41 suites · 203 tests · 0 failure/error/skip · bootJar 성공**
- 실제 waiter query와 blocker PID 확인, blocker 해제 뒤 batch reservation 및 capacity invariant 통과
- Docker default context · PostgreSQL 18.6 · JDK 25.0.5

## 한계

단일 blocker와 한 번의 serial batch에 대한 lock-wait 관측. TPS/RPS·latency·connection capacity·universal deadlock freedom·production sizing을 입증하지 않음. 100ms polling은 이 test의 관측 주기이며 service setting이 아님. `pg_blocking_pids` 호출은 lock-manager shared state에 접근하므로 고빈도 polling으로 확장하지 않음.

## 참고자료

- [PostgreSQL — `pg_blocking_pids`](https://www.postgresql.org/docs/current/functions-info.html): lock 대기 backend의 blocker PID 확인. 고빈도 호출은 lock manager 상태에 영향을 줄 수 있음.
- [Shopify Engineering — Inventory reservations](https://shopify.engineering/scaling-inventory-reservations): MySQL 기반 사례. lock ordering·connection visibility 관점만 참고; DB 설계나 수치는 적용하지 않음.
- [Alibaba Cloud — PostgreSQL dispatch lock conflicts](https://www.alibabacloud.com/blog/postgresql-best-practices-overcoming-ride-hailing-conflicts-arising-in-a-trip-dispatch-system_597194): 다른 도메인의 lock-conflict 사례. synthetic TPS 수치는 본 프로젝트 기준으로 일반화하지 않음.
