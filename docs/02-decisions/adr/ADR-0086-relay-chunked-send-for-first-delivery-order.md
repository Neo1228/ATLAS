# ADR-0086: relay는 묶음을 batch 하나 크기의 덩어리로 나눠 하나씩 끝내고 보낸다

- 상태: Accepted(구현 완료, 로컬 측정)
- 날짜: 2026-11-25
- 관련 결정: [ADR-0067](ADR-0067-event-log-kafka-relay.md), [ADR-0082](ADR-0082-relay-batch-size-and-producer-log.md), [ADR-0087](ADR-0087-consumer-contract-first-delivery-order-and-rebalance.md)
- 근거: [EXP-091](../../03-experiments/EXP-091-kafka-first-delivery-order.md), [EXP-089](../../03-experiments/EXP-089-kafka-outage-repeated.md), 이슈 [#644](https://github.com/Neo1228/ATLAS/issues/644)

## 문제

broker가 20초 멈췄다 풀린 뒤 같은 workflow의 높은 `aggregateVersion`이 낮은 버전보다 먼저 첫 전달되는 일이 있다([#644](https://github.com/Neo1228/ATLAS/issues/644)). 재현기에서 운영 클래스 기준 144 시행-단계 중 11(7.6%)에서 나왔고 누락은 0이다. 원인은 한 파티션에 producer batch가 여럿 걸린 채 broker가 멈추면 batch마다 전송 시한이 따로 흘러 앞 batch는 만료되고 뒤 batch는 살아남아 먼저 기록된다는 것이다(producer는 epoch를 올리고 sequence를 다시 매김). relay는 실패한 묶음을 처음부터 다시 보내므로 낮은 버전이 높은 버전 뒤에 온다. `max.in.flight=1`, producer 교체, replica 1대로는 사라지지 않았다.

## 결정

1. `KafkaReservationEventPublisher.publish`는 묶음을 64KB(추정 크기) 이하의 덩어리로 나눠 덩어리마다 `flush`하고 모든 ack(또는 실패)를 받은 뒤에 다음 덩어리를 보낸다. 한 덩어리가 실패하면 뒤 덩어리는 보내지 않고 예외를 던져 cursor가 그대로 남는다.
2. producer `batch.size`는 덩어리의 두 배(128KB), `linger.ms`는 1000으로 둔다. 덩어리는 `flush`로 즉시 나가므로 linger만큼 기다리지 않는다. 덩어리 하나가 파티션마다 producer batch 하나에 담긴다.
3. 전송 한도(`send-timeout`)는 덩어리 하나에 적용된다. 시한 안에 시작한 마지막 덩어리가 끝나기까지 최악 `send-timeout`의 두 배가 걸리므로 `ReservationEventWorker`는 `lease-duration ≥ 2 × send-timeout + 5초`를 검사한다(기본 PT30S, PT10S는 통과, 환경 변수 기본값은 바뀌지 않음).

## 근거

[EXP-091](../../03-experiments/EXP-091-kafka-first-delivery-order.md): 운영 클래스를 같은 재현기·같은 부하에서 교차 라운드로 비교했다. 수정 전 11/144, 수정 후 0/144(95% 상한 2.1%, Fisher p=0.0008, 탐색). 대안은 막지 못했다: `max.in.flight=1` 3/108, producer 교체 10/108, replica 1대 14/108(원래 설정 14/216). 변이 시험: 덩어리 상한 제거와 덩어리 사이 ack 대기 제거 모두 `KafkaReservationEventPublisherTest`가 실패한다.

## 가정

- 가정 1: 현재 부하(초당 40건)에서 relay 묶음은 대부분 한 덩어리이므로 처리량 영향이 작다. 측정하지 않았다(처리량 한계 시험 없음).
- 가정 2: 이벤트 payload는 수백 바이트이므로 64KB 덩어리(추정 오버헤드 128바이트/레코드)는 파티션 하나에 몰려도 128KB batch 하나에 담긴다. payload가 크게 늘면 이 가정이 깨지므로 덩어리 크기를 같이 본다.
- 가정 3: 재현기(DB 없음, 메모리 이벤트 로그)의 결과가 실제 앱과 DB에서도 같다. 같은 publisher·producer 설정 클래스를 썼지만 실제 스택 반복은 하지 않았다.

## 한계

- 단일 broker, 로컬 호스트, 공유 머신, pause 20초 조건이다. 장기 중단, 여러 broker, `min.insync.replicas`는 보지 않았다.
- 같은 key 순서는 "첫 전달"에 대한 약속이다. 실패한 묶음의 재전송은 여전히 낮은 버전을 높은 버전 뒤에 다시 싣는다(재전달). 중복 제거와 정렬은 소비자 책임이다([ADR-0087](ADR-0087-consumer-contract-first-delivery-order-and-rebalance.md)).
- 한 batch 안에서 순서가 어긋난 경우가 batch 260,925개 중 10개 있었고 원인을 확인하지 못했다.

## 대안과 버린 이유

| 대안 | 버린 이유 |
| --- | --- |
| 아무것도 안 함 | relay가 문서화한 순서 약속(같은 key는 같은 파티션에서 순서 유지)과 실제가 어긋난 채로 둔다. 소비자 규칙은 별도로 남기되 relay 계약을 맞추는 비용이 작다 |
| `max.in.flight.requests.per.connection=1` | 위반이 남는다(3/108). 처리량만 줄인다 |
| 실패 시 producer 교체 | 위반이 남는다(10/108) |
| 묶음 100(ADR-0082) | 확률을 낮출 뿐 보장하지 않는다. 100건(약 11KB)은 batch 하나일 때가 많아 36 시행-단계에서 0이었으나 상한이 8.3%다 |
| 트랜잭션 producer | 이 문제에 필요한 보장(batch 하나)을 더 작은 변경으로 얻는다. 시험하지 않았다 |

## 실패 경로와 되돌리기

되돌리기는 이 PR의 revert다(migration·기능 플래그 없음). `lease-duration` 검사도 함께 되돌아가며 운영 설정 변경은 없다. 재현기에서 `fix` 클래스를 `base` 클래스로 교체한 교차 라운드가 되돌리기 동작을 확인했고(위반 재발) git revert 배포 자체는 시험하지 않았다. 탐지: 재현기의 위반 감사(`load-tests/kafka-outage/RelayOrderRepro.java`), relay lag·failed counter.
