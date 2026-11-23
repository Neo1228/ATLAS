# ADR-0067: 독립 consumer를 위한 xmin 안전 이벤트 로그와 Kafka relay

- 상태: Accepted(구현 완료, 옵트인) — EXP-061가 무손실을 검증하지 못했다는 지적을 재현으로 확인했고, xmin 안전 로그(V75)와 lease 기반 Kafka relay를 옵트인으로 구현해 통합 테스트로 확인했다.
- 날짜: 2026-11-23
- 관련 결정: [ADR-0053](ADR-0053-shared-infra-adoption-gates.md), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md), [ADR-0066](ADR-0066-redis-admission-gate.md)
- 근거 실험: [EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)

## 정정(2026-11-23)

이 결정의 근거였던 [EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)는 lag만 쟀고 무손실을 검증하지 않았다. `event_seq > cursor` 방식은 커밋 순서가 번호 순서와 다를 때 이벤트를 놓치며, 재현 실험에서 3,200건 중 1,013건을 놓쳤다(xmin 안전 방식은 0건). 올바른 PostgreSQL 설계는 `tx_id xid8`와 `pg_snapshot_xmin` 상한(또는 PgQ식 snapshot tick)이 필요하고, 이는 장기·idle-in-transaction 트랜잭션이 모든 consumer를 멈추는 비용(`idle_in_transaction_session_timeout`과 lag 경보로 방어)을 동반한다. 이 비용이 Kafka 쪽 논거를 정확성 면에서 약간 강화한다. 처리량 면(500건/s, consumer 3개)에서는 여전히 PostgreSQL 한계 아래다. 그래서 "도입하지 않음"은 잠정 유지하고, 확정은 아래 재검증 뒤에 한다.

재검증 기준: 쓰기 16개(5% 롤백, 가끔 2초 지연 커밋), consumer 4개(하나는 kill -9 후 재시작, 하나는 offset 0에서 replay), 종료 뒤 consumer별 `소비 id 집합 == 커밋 id 집합`, 중복은 inbox 제거 후 0. 현행 방식을 양성 대조군으로 함께 돌려 손실이 재현되는지 먼저 확인한다.

## 구현(2026-11-23)

- V75: `order_inventory_event`(`tx_id xid8`, 전이마다 trigger가 같은 트랜잭션에서 한 건 기록), `order_inventory_event_cursor`(consumer group별 cursor와 relay lease).
- 읽기: `tx_id < pg_snapshot_xmin(pg_current_snapshot())` 아래만 `(tx_id, event_seq)` 순으로 읽는다. DB 안 consumer는 이 cursor로, 외부 consumer는 Kafka relay로 받는다.
- relay(`atlas.order-inventory-reservation.event-relay.enabled`, 기본 꺼짐): lease 선점 → 짧은 읽기 트랜잭션 → **트랜잭션 없이** Kafka 전송(acks=all) → 같은 lease일 때만 cursor 전진. 전송 실패 시 cursor는 그대로라 재시도로 전달되고 중복은 `event-id`(`workflowId:aggregateVersion`) 헤더로 consumer가 걸러낸다. 키는 workflowId라 workflow 안의 순서가 보존된다.
- 테스트 3건: 쓰기 8개와 장기 트랜잭션에서 `consumed == committed`, trigger가 상태 변화당 정확히 1건, relay 실패 시 cursor 보존과 stale lease 거부.
- 알려진 비용: 장기·idle-in-transaction 트랜잭션이 xmin을 붙잡으면 모든 consumer가 멈춘다. gauge `atlas.order.inventory.event.relay.stalled_seconds`/`lag_events`로 보고 `idle_in_transaction_session_timeout`으로 방어한다.
- 한계: 동일 workflow 이벤트의 순서가 드물게 뒤집힐 수 있다(낮은 xid가 행 잠금을 늦게 얻는 경우). consumer는 `aggregate_version`으로 적용 순서를 맞춘다.

## 문제

예약 상태 변경을 settlement ledger, availability projection, audit 세 소비자가 각자 offset으로 모두 받아야 하고, 소비자 버그를 고친 뒤 과거부터 다시 읽을 수 있어야 한다는 요구를 가정했다(소비자 3개, 초당 500건, 7일 재생은 제안값). 기존 큐는 [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)이 말한 대로 작업 항목 큐라 한 작업을 한 worker가 가져간다. 독립 offset과 재생은 이 큐에 없는 능력이다.

## 결정

Kafka를 도입하지 않는다(잠정). 그 능력이 필요해지면 먼저 PostgreSQL의 append-only 이벤트 로그와 consumer group별 cursor로 구현하되, **cursor는 xmin 안전 방식이어야 한다**. 이번에는 **소비자가 아직 없으므로 로그 테이블과 trigger도 도입하지 않았다.** 측정 하네스(`load-tests/run-event-log-fanout.sh`)만 저장소에 둔다.

## 근거

[EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md): 합성 이벤트로 쓰기 10,000건/s·consumer 12개에서도 lag이 최대 1,229건이었고(공정한 batch 설정), 제안한 500건/s·3개에서는 lag 98건이었다. 재생은 행 수 기준 수십만 건/s 이상이었다. 중간 측정(5,000건/s)에서 lag이 커진 것은 harness의 소비자 설정 한계였고 PostgreSQL 한계가 아니었다.

## 대안과 절충

| 대안 | 판단 |
| --- | --- |
| Kafka(outbox → relay → topic) | 요구 수준에서는 PostgreSQL로 해결되므로 운영 부담(브로커, 파티션 키 설계, dual-write를 막는 relay, DLQ)을 정당화하지 못한다. partition key는 order 기준이면 SKU 간 순서가, SKU 기준이면 주문 단위 순서가 약해지는 절충이 있어 이 요구에 더 복잡도를 더한다. |
| 기존 `payment_recovery_job`식 큐를 재사용 | 한 행의 상태를 덮어쓰고 완료 처리하는 작업 큐라 이벤트 이력과 aggregate version을 보존하지 못한다. |
| PostgreSQL 이벤트 로그 + cursor | 선택(필요해지면). 측정 근거가 있다. |

## Kafka가 필요해지는 조건(다음에 이 결정을 바꾸는 기준)

아래 중 하나가 **측정으로 확인될 때** 재검토한다.

1. 소비자가 다른 서비스·DB에 있어 order DB를 직접 읽을 수 없고, cursor 기반 pull API로는 지연·운영 요구를 못 맞출 때(5단계 이후 결제 등을 독립 시스템으로 분리한 경우를 포함).
2. 7일 이상 보관이 OLTP DB에 부담을 준다. 이벤트당 약 171바이트로 7일×500건/s는 약 52 GB로 추정되며, 파티션 삭제로도 vacuum·백업 비용이 가용 범위를 넘는다고 측정될 때.
3. 쓰기·소비 규모가 10,000건/s·12개를 넘어 단일 PostgreSQL 노드의 CPU 한도에 닿는다고 측정될 때.

## 한계

앱 경로(예약 상태 전이)에서 이벤트를 적재하는 오버헤드, 실제 payload 크기, consumer 장애·재시작, 다중 노드는 측정하지 않았다. 이 결정은 "요구 수치와 합성 이벤트에서 충분했다"는 범위에서만 유효하다.
