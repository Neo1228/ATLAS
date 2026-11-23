# ADR-0063: 주문 재고 예약 workflow의 lease-fenced write batching

- 상태: Accepted
- 날짜: 2026-11-22
- 관련 결정: [ADR-0046](ADR-0046-order-payment-transactional-outbox.md), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md), [EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md)

## 배경

[ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)의 분산 coordinator 처리량 측정에서 원격 inventory 응답과 무관하게 처리량이 약 350–470 workflow/s 구간에서 포화했고, order당 `pg_stat_database.xact_commit` 진단값은 약 7.8이었다. 외부 호출을 transaction 밖에 두는 원칙을 유지하면서 order DB의 commit 횟수를 줄일 필요가 있었다. 기존 lease token fencing과 location idempotency 계약은 그대로 보존해야 한다.

## 결정

1. `submit`은 durable workflow intent의 생성/replay와 첫 lease claim을 한 짧은 local transaction 안에서 처리한다. transaction commit 뒤에만 remote inventory HTTP 요청을 시작한다.
2. 새로 claim한 workflow의 모든 location command가 `PENDING`이면 coordinator는 각 remote 결과를 메모리에 모은다. 모든 remote 호출은 DB transaction 밖에서 수행한다.
3. remote 결과 수집 후 한 local transaction에서 lease-token 조건을 확인하며 location command 전이와 parent workflow 상태를 함께 저장한다. 어느 location write나 parent write라도 lease fencing 조건을 만족하지 못하면 transaction 전체를 rollback한다.
4. 원격 호출이 확정 실패·재시도 가능 실패·예약 만료를 반환하는 경우에도 이미 알려진 location 결과와 workflow 전이를 같은 local transaction에서 기록한 뒤 필요한 보상 호출을 transaction 밖에서 수행한다.
5. 취소, process crash, lease loss 뒤 원격 상태를 알 수 없으면 기존 workflow와 같은 location idempotency key로 재시도한다. 취소의 lease fencing이 먼저 적용되면 예전 worker의 buffered 결과는 commit되지 않으며, durable cancellation recovery가 원격 tombstone으로 마무리한다.
6. 일부 location 상태가 이미 durable한 기존/복구 workflow에는 기존 incremental processing 경로를 유지한다. 여러 workflow를 한 transaction에 섞는 cross-workflow batch는 추가하지 않는다.
7. recovery worker의 기본 batch/cadence와 동시성은 이 변경만으로 조정하지 않는다. 1,000 order/s 목표는 여전히 미달이며 운영 용량이나 SLO를 주장하지 않는다.

## 고려한 대안

| 대안 | 결과 |
|---|---|
| Remote HTTP 호출 전체를 DB transaction 안에 수행 | 응답 지연 동안 connection/transaction을 점유하고, 원격 성공 뒤 local rollback 시 불일치하므로 제외 |
| Location마다 독립 transaction으로 즉시 저장 | 복구는 단순하지만 기존 DB commit 비용을 줄이지 못하므로 fresh all-pending 경로에서 대체 |
| 여러 workflow 결과를 하나의 공통 transaction으로 묶기 | 개별 lease 만료·부분 실패의 격리와 rollback 범위가 복잡해져 제외 |
| Broker/outbox로 coordinator 결과 저장을 비동기화 | 동일한 DB 상태 전이를 다른 consumer로 옮길 뿐이며, 현재 실측 병목을 제거한다는 근거가 없어 보류 |

## 결과와 검증

- [EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md)의 PostgreSQL 18.6 Testcontainers·MockWebServer 단일 실행 비교에서 concurrency 1·2·4·8의 `xact commits / workflow` 진단값은 각각 6.811/6.795/6.724/5.902에서 3.959/3.919/3.604/3.602로 낮아졌다. 같은 조건의 최신 처리량은 59.03/113.77/199.73/358.84 workflow/s였다. 실행별 측정치이며 일반적인 개선률을 보장하지 않는다.
- 시험한 최대 concurrency 32에서 528.99 workflow/s, p95 95.49 ms, p99 109.212 ms를 관측했다. 합성 1,000 workflow/s 목표에는 미달했다. 이 service-level probe는 public HTTP ingress, 실제 inventory DB/network, multi-process replica를 측정하지 않았다.
- 재번호·테스트 정리 전 시점의 `./gradlew clean check --no-build-cache --rerun-tasks`는 전체 604 tests, failure/error 0, opt-in capacity probe 2건 skipped로 통과했다. 그 실행에서 coordinator·원격 checkout 통합 test와 Frankfurter provider test도 통과했다. 이후 opt-in capacity probe 2건과 `OrderInventoryReservationConfigurationTest`는 저장소에서 제거했으므로 이 수치는 현재 test 구성의 결과가 아니다. 제거한 submit probe의 6개 concurrency cell([EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md))과 recovery probe의 24개 two-location cell([EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md))은 당시 모두 성공했다.

## 재검토 조건

- 같은 조건의 반복 실험에서 더 많은 commit이 확인되거나, p95/p99·queue age가 예상과 다르게 악화되는 경우.
- 실제 다중 process/replica 실험에서 remote idempotency와 cancellation fence가 복구를 보장하지 못하는 경우.
- 1,000 order/s 목표를 다시 승인하려면 public API부터 order DB·inventory DB까지 고정 자원에서 end-to-end workload를 실행하고, 병목을 DB statement/lock, connection wait, remote latency로 분리한 근거가 필요하다.
