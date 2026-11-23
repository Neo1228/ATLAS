# ADR-0063: 주문 재고 예약 workflow의 lease-fenced write batching

- 상태: Accepted — 구현·통합 검증 완료
- 날짜: 2026-11-22
- 결정 ID: [D-126](../../00-governance/decision-log.md#d-126)
- 관련 결정: [ADR-0046](ADR-0046-order-payment-transactional-outbox.md)(외부 결과와 owner 적용의 분리), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)(order inventory reservation workflow)
- 측정: [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md)(coordinator 병목 분해), [EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md)(submit service-level probe)

## 문제

[ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)의 분산 coordinator 처리량 측정([EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md)의 병목 분해, 같은 1,000 order/s × 30초 입력, 변형당 1회)에서 원격 inventory를 즉시 응답 stub으로 바꿔도 완료 처리율이 343.9 workflow/s로 포화했고, 변형 중 가장 높은 값도 470.9 workflow/s였다(변형 5종의 범위 311.7–470.9 workflow/s). order당 `pg_stat_database` transaction 진단값은 수정 전 7.79, 수정 후 7.86이었다. 외부 호출을 transaction 밖에 두는 원칙을 유지하면서 order DB의 commit 횟수를 줄일 필요가 있었다. 기존 lease token fencing과 location idempotency 계약은 그대로 보존해야 한다.

## 결정

1. `LocationReservationService.submit`은 durable workflow intent의 생성/replay와 첫 lease claim을 한 짧은 local transaction 안에서 처리한다. transaction commit 뒤에만 remote inventory HTTP 요청을 시작한다.
2. 새로 claim한 workflow의 모든 location command가 `PENDING`이면 coordinator는 각 remote 결과를 메모리에 모은다. 모든 remote 호출은 DB transaction 밖에서 수행한다.
3. remote 결과 수집 후 한 local transaction에서 lease-token 조건을 확인하며 location command 전이와 parent workflow 상태를 함께 저장한다. 어느 location write나 parent write라도 lease fencing 조건을 만족하지 못하면 transaction 전체를 rollback한다.
4. 원격 호출이 확정 실패·재시도 가능 실패·예약 만료를 반환하는 경우에도 이미 알려진 location 결과와 workflow 전이를 같은 local transaction에서 기록한 뒤 필요한 보상 호출을 transaction 밖에서 수행한다.
5. 취소, process crash, lease loss 뒤 원격 상태를 알 수 없으면 기존 workflow와 같은 location idempotency key로 재시도한다. 취소의 lease fencing이 먼저 적용되면 예전 worker의 buffered 결과는 commit되지 않으며, durable cancellation recovery가 원격 tombstone으로 마무리한다.
6. 일부 location 상태가 이미 durable한 기존/복구 workflow에는 기존 incremental processing 경로를 유지한다. 여러 workflow를 한 transaction에 섞는 cross-workflow batch는 추가하지 않는다.
7. recovery worker의 기본 batch/cadence와 동시성은 이 변경만으로 조정하지 않는다. 1,000 order/s 목표는 여전히 미달이며 운영 용량이나 SLO를 주장하지 않는다.

## 대안과 trade-off

| 대안 | 결과 |
|---|---|
| Remote HTTP 호출 전체를 DB transaction 안에 수행 | 응답 지연 동안 connection/transaction을 점유하고, 원격 성공 뒤 local rollback 시 불일치하므로 제외 |
| Location마다 독립 transaction으로 즉시 저장 | 복구는 단순하지만 기존 DB commit 비용을 줄이지 못하므로 fresh all-pending 경로에서 대체 |
| 여러 workflow 결과를 하나의 공통 transaction으로 묶기 | 개별 lease 만료·부분 실패의 격리와 rollback 범위가 복잡해져 제외 |
| Broker/outbox로 coordinator 결과 저장을 비동기화 | 동일한 DB 상태 전이를 다른 consumer로 옮길 뿐이며, 현재 실측 병목을 제거한다는 근거가 없어 보류 |

## 검증과 한계

- [EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md)의 PostgreSQL 18.6 Testcontainers·MockWebServer 단일 host 비교(workflow 1,000개/cell, 변경 전 1회·변경 후 2회 중 최근값)에서 concurrency 1·2·4·8의 `xact_commits / workflow` 진단값은 각각 6.811/6.795/6.724/5.902에서 3.959/3.919/3.604/3.602로 낮아졌다. 처리량은 52.19/96.18/172.00/339.44에서 59.03/113.77/199.73/358.84 workflow/s가 됐다(+5.7–18.3%). 실행별 측정치이며 일반적인 개선률을 보장하지 않는다.
- 시험한 최대 concurrency 32에서 528.99 workflow/s(합성 목표 1,000 workflow/s의 52.9%), p95 95.49 ms, p99 109.212 ms를 1회 실행에서 관측했다. 목표에는 미달했다. 이 service-level probe는 public HTTP ingress, 실제 inventory DB/network, multi-process replica를 측정하지 않았다.
- test 수의 시점 구분: 재번호·테스트 정리 전에 실행한 `./gradlew clean check --no-build-cache --rerun-tasks`는 전체 604 tests, failure/error 0, opt-in capacity probe 2건 skipped로 통과했다(2026-11-23 정리 이전, 정확한 실행일은 기록하지 않았다). 그 실행에서 coordinator·원격 checkout 통합 test와 Frankfurter provider test도 통과했다. 이 **604는 당시 기준선이며 현재 값이 아니다.** 아래 클래스는 이후 저장소에서 제거한 과거 harness·test로, 현재 test가 아니다: opt-in capacity probe 2건(`OrderInventoryReservationSubmitCapacityProbeTest`, `OrderInventoryRecoveryCapacityProbeTest`)과 `OrderInventoryReservationConfigurationTest`. 2026-11-23 no-cache `clean check`의 현재 값은 전체 746 tests, skipped·failure·error 0이다([ADR-0058](ADR-0058-distributed-fulfillment-stage-one-baseline.md) 검증 절). 코드 구성이 달라 604와 746은 비교하지 않는다. 제거한 submit probe의 6개 concurrency cell([EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md))과 recovery probe의 24개 two-location cell([EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md))은 당시 모두 성공했다.

## 재검토 조건

- 같은 조건의 반복 실험에서 더 많은 commit이 확인되거나, p95/p99·queue age가 예상과 다르게 악화되는 경우.
- 실제 다중 process/replica 실험에서 remote idempotency와 cancellation fence가 복구를 보장하지 못하는 경우.
- 1,000 order/s 목표를 다시 승인하려면 public API부터 order DB·inventory DB까지 고정 자원에서 end-to-end workload를 실행하고, 병목을 DB statement/lock, connection wait, remote latency로 분리한 근거가 필요하다.
