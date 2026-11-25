# ADR-0076: 이벤트 소비자 계약은 테스트 전용 참조 소비자로 검증하고 운영 소비자는 만들지 않는다

- 상태: Accepted(구현 완료, 테스트 범위 한정)
- 날짜: 2026-11-25
- 관련 결정: [ADR-0067](ADR-0067-event-log-kafka-relay.md), [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)
- 근거: [EXP-082](../../03-experiments/EXP-082-event-consumer-contract.md), [EXP-067](../../03-experiments/EXP-067-event-consumer-contract.md), [EXP-068](../../03-experiments/EXP-068-stage-evidence-map.md)

## 문제

[ADR-0067](ADR-0067-event-log-kafka-relay.md)은 예약 이벤트를 Kafka로 relay하고 중복 제거와 적용 순서를 소비자 책임으로 두었다. 그런데 저장소에는 소비자가 없어서 그 책임이 성립하는지 확인한 적이 없다. [EXP-068](../../03-experiments/EXP-068-stage-evidence-map.md) 6단계 표의 "중복 제거, 다중 프로세스 소비자, 브로커 중단" 행이 "없음"인 이유다. relay 쪽은 [EXP-072](../../03-experiments/EXP-072-kafka-outage-relay.md)와 [EXP-078](../../03-experiments/EXP-078-relay-lease-length.md)이 다뤘고 DB 안 소비자 쪽은 [EXP-067](../../03-experiments/EXP-067-event-consumer-contract.md)이 다뤘지만, Kafka 소비자 쪽의 중복 전달, 같은 그룹의 여러 인스턴스, 재시작, 브로커 중단은 비어 있다. 이 빈 곳을 가장 단순한 방법으로 메우려면 어떻게 해야 하는가?

## 결정

테스트 범위에만 참조 소비자를 둔다. 운영 소비자는 만들지 않는다.

- `ReservationEventReferenceConsumer`(`atlas-application` 테스트 소스, 새 의존성 없음)가 소비자 계약을 구현한다. 적용은 `(workflowId, aggregateVersion)`마다 한 번이고, 적용 표시와 부수 효과는 한 트랜잭션이며, offset commit은 적용 뒤에 따로 한다(at-least-once).
- `ReservationEventConsumerKafkaIntegrationTest`가 실제 relay(worker, repository, Kafka publisher)가 쓴 토픽을 이 소비자로 읽어 네 가지 불변식을 확인한다: 중복 전달은 한 번만 적용되고 유실이 없다, 같은 그룹의 두 인스턴스가 리밸런스를 거쳐도 겹쳐 적용하지 않는다, 재시작은 commit한 offset부터 이어 읽고 commit 못 한 구간의 재전달은 한 번만 적용된다, 브로커를 `docker pause`했다 풀어도 유실과 중복 적용이 없다.
- 소비자 계약(토픽 JSON 필드, `event-id` header, 같은 키는 같은 파티션)은 이 테스트가 고정한다. 토픽 계약을 바꾸면 이 테스트가 깨진다.

## 대안과 버린 이유

비용은 이 PR에서 새로 쓴 줄 수와 이후 유지 부담, 위험은 범위를 넓히거나 거짓 안심을 주는 정도다.

| 대안 | 비용 | 위험 | 판단 |
| --- | --- | --- | --- |
| (a) 소비자 없이 계약만 문서화하고 6단계 행을 범위 밖으로 표시 | 문서 몇 줄 | 중복 제거, 다중 인스턴스, 재시작, 중단이 계속 가정으로 남는다. 문서와 토픽 형식이 어긋나도 알 수 없다 | 근거 공백을 메우지 못해 버림 |
| (b) 테스트 전용 참조 소비자와 통합 테스트 | 테스트 소스 클래스 1개와 테스트 클래스 1개(컨테이너는 기존 `KafkaIntegrationTest`와 같은 PostgreSQL 1개, Kafka 1개) | 참조 소비자가 실제 소비자가 아니다. 통과해도 제품 소비자가 맞다는 뜻은 아니다 | 채택 |
| (c) 운영 소비자 모듈 | 새 모듈, 배포 단위, 설정, 알림, 소유자 | 소비자가 무엇을 하는지(원장, 가용성 projection, 감사)는 이 저장소에 정해진 제품 요구가 없다. 근거 없이 제품 범위를 만든다 | 버림 |

(b)가 증거 공백을 닫는 가장 작은 방법이다. (a)는 같은 공백을 남기고, (c)는 공백보다 훨씬 큰 범위를 도입한다. 참조 소비자는 한 가지 역할만 맡는다. 소비자를 만들 사람이 따라 할 수 있는 계약 구현과, 그 계약이 relay가 실제 쓰는 토픽에서 성립한다는 증거다.

## 결과와 한계

- 확인한 것과 조건은 [EXP-082](../../03-experiments/EXP-082-event-consumer-contract.md)에 적었다.
- 참조 소비자의 적용 효과는 PostgreSQL 행이고, 실제 소비자의 부수 효과(다른 DB, HTTP 호출)에서는 적용 표시와 효과를 한 트랜잭션에 묶을 수 없다. 그 경우의 멱등성은 효과 쪽 키로 따로 보장해야 하며 이 테스트는 그 부분을 증명하지 않는다.
- 같은 workflow 이벤트의 순서 뒤집힘([ADR-0067](ADR-0067-event-log-kafka-relay.md) 한계)을 일으키는 입력은 만들지 않았고, 참조 소비자는 버전 순서 재정렬을 하지 않는다. 중복 제거만 한다.
- 파티션 2개, 소비자 2개, 이벤트 18~36건 규모다. 규모 확장 때 거동은 검증하지 않았다.

## 실패 경로와 되돌리기

- 참조 소비자 테스트가 CI에서 불안정하면 테스트 클래스를 삭제한다. 운영 코드, 스키마, 설정은 바뀌지 않아 되돌릴 대상이 없다.
- 이후 운영 소비자를 만들기로 하면 이 참조 소비자와 테스트를 계약 테스트로 가져가 같은 불변식을 먼저 통과시킨다.

## 후속

- 파티션이 여러 개이고 relay를 가진 application을 kill하는 시나리오는 이 결정의 범위가 아니다([EXP-068](../../03-experiments/EXP-068-stage-evidence-map.md) 후속 4번).
- 운영 소비자가 필요해지는 조건(소비자 서비스가 생기거나 외부 시스템이 이 토픽을 구독)이 확인되면 별도 ADR로 다룬다.
