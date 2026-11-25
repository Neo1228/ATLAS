# ADR-0082: relay 묶음 크기 기본값을 100으로 낮추고 producer의 레코드별 실패 로그를 끈다

- 상태: Accepted(구현 완료, 로컬 측정)
- 날짜: 2026-11-25
- 관련 결정: [ADR-0067](ADR-0067-event-log-kafka-relay.md), [ADR-0076](ADR-0076-reference-consumer-in-tests-only.md)
- 근거: [EXP-089](../../03-experiments/EXP-089-kafka-outage-repeated.md), [EXP-072](../../03-experiments/EXP-072-kafka-outage-relay.md), [EXP-078](../../03-experiments/EXP-078-relay-lease-length.md)

## 문제

relay는 broker가 응답만 멈추면(`pause`) 한 묶음을 기록한 뒤 실패로 처리하고 같은 묶음을 다시 보낸다. 이 중복의 크기는 묶음 크기에 걸린다. 기본 묶음 500건에서 `pause` 20초와 45초 중단은 실행마다 평균 181건과 202건의 중복을 만들었다. 또 Spring Kafka의 기본 `LoggingProducerListener`는 전송에 실패한 레코드마다 payload를 포함한 ERROR를 남겨, `ReservationEventWorker`가 실패 로그를 1분에 한 번으로 제한한 설계를 우회했다(45초 중단에서 앱 로그 중앙 1,801줄, 그중 레코드별 ERROR 426줄). 이 두 가지를 어떻게 줄이는가?

## 결정

1. `event-relay.batch-size` 기본값을 500에서 100으로 낮춘다(`application.yml`, `ReservationEventWorker`의 `@Value` 기본값, 운영 문서). 환경 변수 `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_BATCH_SIZE`로 되돌릴 수 있다.
2. relay의 `KafkaTemplate`에 아무것도 하지 않는 `ProducerListener`를 지정해 레코드별 실패 로그를 끈다. 실패는 publisher가 던지는 예외, `atlas.order.inventory.event.relay.failed` counter, throttle된 WARN으로 드러난다.
3. `send-timeout`(PT10S)과 `lease-duration`(PT30S)은 바꾸지 않는다.

## 근거

[EXP-089](../../03-experiments/EXP-089-kafka-outage-repeated.md) 결과 3(같은 하네스·호스트, 조건마다 n=5, 호스트 부하는 같지 않음):

- `pause20` 중복 평균 181 → 71.8(순열 검정 단측 p=0.048), `pause45` 202.4 → 104.4(p=0.024). batch 100에서 `pause`·`delay6s`의 중복 최대는 108~110으로 batch 크기에 가깝다. 누락과 순서는 모든 설정에서 바뀌지 않았다.
- `pause45` 앱 로그 줄 수 중앙값: batch 500에서 1,801, batch 100에서 518, listener를 끈 뒤 57. 레코드별 ERROR 줄은 426 → 110 → 0.
- 소진 시간(맥락용)은 설정과 관계없이 0.5~2.7초다.

## 가정

- 가정 1: 소비자가 중복을 `eventId`·`aggregateVersion`으로 걸러내므로 중복 건수는 정확성이 아니라 부하와 소음의 문제다(ADR-0067). 이 가정이 틀리면(중복을 거르지 못하는 소비자) 중복 감소는 더 중요해지고 값을 더 낮출 이유가 된다.
- 가정 2: 묶음이 작아지면 DB 읽기·lease 왕복이 늘지만 relay가 초당 수천 건을 나르는 수준은 아니다. 처리량은 이 실험에서 한계까지 재지 않았다(부하 초당 40건).
- 가정 3: 운영에서 Kafka 장애 알림은 counter와 WARN이 맡는다. 레코드별 payload ERROR를 보는 곳이 없다고 보았다(저장소 검색에서 이 로그를 참조하는 곳은 없음).

## 대안과 버린 이유

| 대안 | 판단 |
| --- | --- |
| batch 500 유지, 로그만 끔 | 중복 크기를 줄일 수 있는 가장 단순한 설정(값 하나)을 두고 측정이 있는 이득을 버린다. |
| batch를 더 작게(예: 20) | 이 실험에서 측정하지 않았다. 100은 측정한 값이고, `delay6s`에서 batch가 작을수록 중복이 오히려 늘 수 있음이 보였다. |
| `send-timeout`을 줄여 `pause` 중복을 줄임 | 중복은 시한을 넘긴 묶음이 이미 기록되어 생긴다. 시한을 줄이면 묶음이 더 자주 실패한다. 이 실험에서 측정하지 않았고 lease 제약(`send-timeout + 5초`)과 묶여 바꾸지 않았다. |
| producer `batch.size`를 키워 순서 역행(#644)을 막음 | 시험했으나 위반이 사라지지 않아 되돌렸다(EXP-089 결과 2). |
| `ReservationEventWorker`에 실패 로그 억제 추가 | 이미 있다. 문제는 producer 쪽 listener다. |

## 한계

- `delay6s`는 batch 100에서 중복 중앙값이 39 → 108로 늘었다(각 n=5). batch 100이 모든 장애에서 중복을 줄이지는 않는다.
- 첫 전달 순서 역행([#644](https://github.com/Neo1228/ATLAS/issues/644))은 이 변경과 무관하게 원인을 확정하지 못했고 이 결정이 해결하지 않는다.
- 로컬 단일 호스트, 단일 broker, 공유 머신에서 측정했다. 처리량 한계는 측정하지 않았다.

## 실패 경로와 되돌리기

- 되돌리기: 환경 변수 `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_BATCH_SIZE=500`으로 즉시, 또는 이 커밋을 되돌린다. 스키마·API 변경은 없다.
- listener를 끈 뒤 전송 실패를 알아차릴 수 없다면(counter·WARN이 수집되지 않는 환경) `ProducerListener` 지정 코드를 지운다.
