# ADR-0067: 독립 consumer를 위한 xmin 안전 이벤트 로그와 Kafka relay

- 상태: Accepted(구현 완료, Kafka relay는 옵트인·기본 꺼짐) — [EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)가 무손실을 검증하지 못했다는 지적을 재현으로 확인했고, xmin 안전 로그(V75)와 lease 기반 Kafka relay를 구현해 통합 테스트 3건으로 확인했다.
- 날짜: 2026-11-23
- 관련 결정: [ADR-0053](ADR-0053-shared-infra-adoption-gates.md), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md), [ADR-0066](ADR-0066-redis-admission-gate.md)
- 근거 실험: [EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)

## 정정(2026-11-23)

이 결정의 근거였던 [EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)은 lag만 쟀고 무손실을 검증하지 않았다. `event_seq > cursor` 방식은 커밋 순서가 번호 순서와 다를 때 이벤트를 놓친다. 재현 실험(쓰기 8개가 번호를 받은 뒤 0~150ms 임의 지연 후 커밋, 총 3,200건, 1회 실행)에서 현행 방식은 3,200건 중 1,013건을 놓쳤고 xmin 안전 방식은 0건을 놓쳤다. 놓친 비율(31.7%)은 합성 지연 1회의 값이라 일반화하지 않는다.

올바른 PostgreSQL 설계는 `tx_id xid8`와 `pg_snapshot_xmin` 상한(또는 PgQ식 snapshot tick)이 필요하다. 이는 장기·idle-in-transaction 트랜잭션이 모든 consumer를 멈추는 비용을 동반하고, `idle_in_transaction_session_timeout`과 lag 경보로 방어한다. 이 비용은 Kafka 쪽 논거를 정확성 면에서 약간 강화한다. 그래도 처리량 면(500건/s, consumer 3개)에서는 PostgreSQL 한계 아래다.

정정 직후에는 쓰기 16개(5% 롤백, 가끔 2초 지연 커밋), consumer 4개(하나는 `kill -9` 후 재시작, 하나는 offset 0에서 replay)로 재검증하는 기준을 세웠다. 이 기준은 저장소의 테스트와 스크립트에 구현돼 있지 않다. 실제로 확인한 것은 정정 재현 스크립트(쓰기 8개)와 아래 구현 절의 통합 테스트 3건이고, consumer 재시작과 replay 시나리오는 **미검증**이다.

## 구현(2026-11-23)

- V75: `order_inventory_event`(`tx_id xid8`, 전이마다 trigger가 같은 트랜잭션에서 한 건 기록), `order_inventory_event_cursor`(consumer group별 cursor와 relay lease).
- 읽기: `tx_id < pg_snapshot_xmin(pg_current_snapshot())` 아래만 `(tx_id, event_seq)` 순으로 읽는다. DB 안 consumer는 이 cursor로, 외부 consumer는 Kafka relay로 받는다.
- relay(`atlas.order-inventory-reservation.event-relay.enabled`, 기본 꺼짐): lease 선점 → 짧은 읽기 트랜잭션 → **트랜잭션 없이** Kafka 전송(acks=all) → 같은 lease일 때만 cursor 전진. 전송 실패 시 cursor는 그대로라 재시도로 전달되고, 중복은 `event-id`(`workflowId:aggregateVersion`) 헤더로 consumer가 걸러낸다. 키는 workflowId라 workflow 안의 순서가 보존된다. 기본값은 topic `atlas.order-inventory.events`, batch-size 500, poll-interval PT0.2S, send-timeout PT10S, lease-duration PT30S다.
- 통합 테스트 3건: `ReservationEventReadIntegrationTest`(쓰기 8개 × 40건 = 320건, 커밋 전 0~15ms 임의 지연, 열린 트랜잭션 1개를 둔 채 읽어 `consumed == committed`와 열린 트랜잭션의 행 비노출 확인), `ReservationEventTriggerIntegrationTest`(상태 변화당 정확히 1건, 연속 version), `ReservationEventKafkaIntegrationTest`(`apache/kafka:3.9.1` 컨테이너, 전송 실패 시 cursor 보존과 재시도 전달, stale lease 거부). 이 테스트들은 정정 재현(1,013건 손실)과 같은 조건이 아니다.
- 알려진 비용: 장기·idle-in-transaction 트랜잭션이 xmin을 붙잡으면 모든 consumer가 멈춘다. gauge `atlas.order.inventory.event.relay.stalled_seconds`/`lag_events`로 보고 `idle_in_transaction_session_timeout`으로 방어한다.
- 한계: 동일 workflow 이벤트의 순서가 드물게 뒤집힐 수 있다(낮은 xid가 행 잠금을 늦게 얻는 경우). consumer는 `aggregate_version`으로 적용 순서를 맞춘다.

## 문제

예약 상태 변경을 settlement ledger, availability projection, audit 세 소비자가 각자 offset으로 모두 받아야 하고, 소비자 버그를 고친 뒤 과거부터 다시 읽을 수 있어야 한다는 요구를 가정했다(소비자 3개, 초당 500건, 7일 재생은 제안값). 기존 큐는 [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)이 말한 대로 작업 항목 큐라 한 작업을 한 worker가 가져간다. 독립 offset과 재생은 이 큐에 없는 능력이다.

## 결정

- 독립 offset·재생이 필요한 consumer의 기본 경로는 order DB의 append-only 이벤트 로그(V75 `order_inventory_event`, V11부터 DB가 UPDATE·DELETE·TRUNCATE를 거절: [ADR-0105](ADR-0105-audit-table-append-only-guards.md))와 consumer group별 cursor(`order_inventory_event_cursor`)다. **cursor는 xmin 안전 방식이어야 한다.** 이 로그, trigger, cursor는 구현됐고 위 통합 테스트로 확인했다.
- Kafka relay는 옵트인(`atlas.order-inventory-reservation.event-relay.enabled`, 기본 꺼짐)으로 구현했다. 소비자가 다른 서비스·DB에 있어 order DB를 직접 읽을 수 없는 경우(아래 조건 1)를 위해 둔 것이고, 기본 구성에는 브로커 의존이 없다.
- Kafka를 기본 경로로 올리지 않는 근거는 아래 측정이다. 아래 세 조건 중 하나가 측정으로 확인되면 이 결정을 다시 본다.
- 측정 하네스(`load-tests/run-event-log-fanout.sh`)와 정정 재현(`load-tests/event-log-gap-check.sh`)은 저장소에 둔다.
- 이 문서의 이전 판은 "Kafka를 도입하지 않고 로그 테이블과 trigger도 만들지 않는다(잠정)"였다. 같은 날 정정과 구현으로 로그·trigger·cursor·옵트인 relay를 갖추도록 바꿨다([decision-log](../../00-governance/decision-log.md) D-132 → D-134).

## 근거

[EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)(합성 이벤트, 단일 호스트, 구성당 1~2회, 쓰기 pgbench 연결 4개, PostgreSQL 2 CPU/2 GiB):

- 500건/s·consumer 3개(batch 500·poll 200ms)에서 lag 최대는 2회 모두 98건과 104건, 종료 시 lag은 5건과 29건이었다.
- 10,000건/s·consumer 12개(batch 5,000·poll 100ms)에서 lag 최대는 2회 모두 1,229건과 968건(약 0.12초치 이하), 종료 시 lag은 951건과 90건이었다. 같은 쓰기 속도에서 batch 500·poll 200ms이면 lag이 226,209건까지 늘었다.
- 중간 측정(5,000건/s 이상)에서 lag이 커진 것은 harness의 소비자 설정 한계였다. 연결당 500건/0.2초 = 2,500건/s가 상한이고, consumer를 3개에서 12개로 늘려도 lag이 75,276건과 76,284건으로 거의 같았다. PostgreSQL 한계가 아니다.
- 재생은 행 수 기준 약 80만~330만 건/s였다. payload 전송을 뺀 상한 추정이고 원본 출력은 보관하지 않았다.
- 이 수치는 trigger·앱 경로 오버헤드, Kafka relay의 처리량·지연을 포함하지 않는다.

## 대안과 절충

| 대안 | 판단 |
| --- | --- |
| Kafka(outbox → relay → topic)를 기본 경로로 | 요구 수준에서는 PostgreSQL로 해결되므로 운영 부담(브로커, 파티션 키 설계, dual-write를 막는 relay, DLQ)을 정당화하지 못한다. partition key는 order 기준이면 SKU 간 순서가, SKU 기준이면 주문 단위 순서가 약해지는 절충이 있어 이 요구에 복잡도를 더한다. 옵트인 relay로만 구현했다. |
| 기존 `payment_recovery_job`식 큐를 재사용 | 한 행의 상태를 덮어쓰고 완료 처리하는 작업 큐라 이벤트 이력과 aggregate version을 보존하지 못한다. |
| PostgreSQL 이벤트 로그 + cursor | 선택. 측정 근거가 있고 구현·통합 테스트까지 마쳤다. |

## Kafka가 필요해지는 조건(다음에 이 결정을 바꾸는 기준)

아래 중 하나가 **측정으로 확인될 때** Kafka를 기본 경로로 올리는 것을 다시 검토한다.

1. 소비자가 다른 서비스·DB에 있어 order DB를 직접 읽을 수 없고, cursor 기반 pull API로는 지연·운영 요구를 못 맞출 때(5단계 이후 결제 등을 독립 시스템으로 분리한 경우를 포함).
2. 7일 이상 보관이 OLTP DB에 부담을 준다. 이벤트당 약 171바이트로 7일×500건/s는 약 52 GB(500 × 86,400초 × 7일 × 171바이트 ≈ 51.7 GB)로 **추정**되며, 파티션 삭제로도 vacuum·백업 비용이 가용 범위를 넘는다고 측정될 때.
3. 쓰기·소비 규모가 10,000건/s·12개를 넘어 단일 PostgreSQL 노드의 CPU 한도에 닿는다고 측정될 때.

## 한계

앱 경로(예약 상태 전이)에서 이벤트를 적재하는 오버헤드, 실제 payload 크기, consumer 장애·재시작과 replay, Kafka relay의 처리량·지연, 다중 노드는 측정하지 않았다. 이 결정은 "요구 수치와 합성 이벤트에서 충분했다"는 범위에서만 유효하다.
