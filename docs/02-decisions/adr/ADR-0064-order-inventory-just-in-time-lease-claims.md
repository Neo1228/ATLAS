# ADR-0064: 주문 재고 recovery lease를 작업 직전에 부여·연장한다

- 상태: Accepted
- 날짜: 2026-11-22
- 관련 결정: [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md), [ADR-0062](ADR-0062-order-recovery-backlog-observability.md), [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md)

## 배경

기존 recovery poll은 최대 100개 workflow를 한 transaction에서 claim하면서 모두 같은 30초 `lease_until`을 부여한 뒤 순차 처리했다. inventory HTTP read timeout은 기본 2초이며 호출 위치에 따라 6초까지 설정된다. 느린 원격 응답이 여러 건 이어지면 아직 시작하지 않은 workflow의 lease가 처리 대기 중 만료될 수 있다. 다른 replica가 해당 workflow를 다시 claim하면 두 worker가 같은 idempotency key로 원격 요청을 보내고, lease 획득 횟수가 업무 처리 시도 횟수처럼 증가해 backoff·`max-attempts`를 소모할 수 있다. Token fencing은 오래된 결과의 DB 반영을 막지만, 이미 발행된 원격 호출과 불필요한 attempt 증가는 되돌리지 못한다.

한 poll 안에서 매번 현재 시각으로 due 조회를 반복하면, 처리 도중 새로 예약된 짧은 backoff retry가 같은 poll에서 즉시 다시 선택되어 연속 실패를 빠르게 소모할 수도 있다.

## 결정

1. `recoverDue(batchSize)`는 poll 시작 시 `dueThrough` cutoff를 한 번 고정한다. `batchSize`는 poll당 처리 상한이며 사전 claim 수가 아니다.
2. Repository는 `FOR UPDATE SKIP LOCKED`를 포함한 atomic CTE update로 due workflow **하나만** claim한다. Coordinator는 claim 직후 외부 호출을 수행하고 다음 workflow를 claim한다. 각 workflow lease는 remote work가 실제 시작되기 직전에 생기므로 순차 batch의 후속 작업이 대기 중 만료되지 않는다.
3. `next_attempt_at`이 poll 시작 cutoff보다 뒤인 workflow는 같은 poll에서 재선택하지 않는다. Backoff에 따라 다음 scheduler poll에서 다시 시도한다.
4. `WorkLease`는 lease expiry를 함께 보유한다. Remote call 전에 lease 잔여 시간이 절반 이하이면 동일 workflow/token 및 아직 유효한 lease 조건으로 `lease_until`만 연장한다. Token이 교체됐거나 이미 만료된 lease는 연장하지 않고 원격 호출을 중단한다. 만료 token을 되살리지 않는다.
5. Application startup은 `lease-duration / 2`가 inventory connect timeout + read timeout + 1초 safety margin보다 긴지 검증한다. 원격 호출은 계속 transaction 밖에서 수행한다.
6. Worker thread 수, scheduler cadence, batch 기본값은 바꾸지 않는다. Claim을 workflow별 transaction으로 나누므로 대량 backlog에서는 DB claim commit 비용이 늘 수 있다. 용량 변화는 [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md)로 다시 측정하며 1,000 order/s 운영 목표 달성을 주장하지 않는다.

## 대안과 절충

| 대안 | 결과 |
|---|---|
| 같은 expiry로 전체 batch를 계속 claim | batch 대기 시간 동안 lease가 만료될 수 있어 제외 |
| lease 기간을 `batchSize × timeout`만큼 늘림 | process crash 뒤 후속 workflow의 takeover 지연이 수십 분으로 길어질 수 있어 제외 |
| bulk claim 뒤 전체 batch를 병렬 executor로 처리 | 별도 bounded concurrency·shutdown·backpressure 계약이 필요하고, 현재는 thread pool을 도입할 구체적 운영 근거가 없어 보류 |
| 한 workflow씩 atomic claim 후 즉시 처리 | claim transaction 수가 늘지만 lease와 remote work 시작 시점을 맞추고, 새 retry가 같은 poll에서 재실행되지 않음; 현재 선택 |

## 검증과 한계

- PostgreSQL integration test는 batch의 첫 workflow가 원격 응답을 기다리는 동안 후속 workflow에 lease/attempt를 미리 부여하지 않는지, 다른 poller가 후속 workflow를 처리할 때 오래된 poller가 중복 remote call을 보내지 않는지 검증한다.
- 재번호·테스트 정리 전 시점에 `LocationReservationServiceIntegrationTest` 15건, `OrderInventoryReservationConfigurationTest` 2건, `AccountAuthenticationIntegrationTest` 12건, `FrankfurterExchangeRateProviderTest` 2건이 통과했고, full uncached `./gradlew clean check --no-build-cache --rerun-tasks`도 총 604 tests, failure/error 0, opt-in capacity probe 2건 skipped로 통과했다. 이후 `OrderInventoryReservationConfigurationTest`와 capacity probe는 저장소에서 제거했고 coordinator test 구성도 달라졌으므로 이 건수는 현재 값이 아니다. 제거한 recovery probe의 [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md) 24개 cell은 당시 모두 `RESERVED`로 끝났다.
- [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md)의 one-run two-location finite backlog matrix는 최고 775.00 workflow/s(32 poller objects, batch 50)이며 1,000 workflow/s에 미달했다. 측정은 같은 JVM, 즉시 성공 MockWebServer, tight-loop polling이며 실제 scheduler cadence·public ingress·inventory DB latency·multi-process capacity를 포함하지 않는다. 별도의 25-workflow, two-JVM correctness smoke는 [EXP-059](../../03-experiments/EXP-059-order-reservation-load-and-idempotent-replay.md)에 기록했으며 처리량 근거가 아니다.

## 재검토 조건

- 실제 다중 replica에서 `lease_until` 만료·claim 경쟁이 발생하면 DB lock wait, lease-loss 횟수, duplicate remote requests, attempt-count 상승을 함께 관측한다.
- Claim transaction이 새 병목으로 확인되면, worker per-poll budget·bounded parallelism·claim batch size를 같은 고정 자원과 지연 분포에서 비교한 뒤 변경한다. 처리량만을 이유로 긴 lease나 무제한 병렬화를 도입하지 않는다.
