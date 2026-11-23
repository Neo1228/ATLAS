# EXP-067: DB 안 이벤트 소비자가 기댈 수 있는 성질은 실제로 성립하는가

- 질문: [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md)은 중복 제거와 적용 순서를 소비자 책임으로 두었다. 소비자가 그 책임을 지려면 읽기 순서, 재읽기 동일성, cursor 단조 증가, 소비자 그룹별 독립이 성립해야 한다. 성립하는가?
- 상태: 확인 완료. 결정적 통합 테스트이며 저장소에 둔다(PostgreSQL 컨테이너 1개, 약 9초).
- 날짜: 2026-11-24
- 관련: [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [EXP-061](EXP-061-event-log-fanout-and-replay.md), 테스트 `ReservationEventConsumerContractIntegrationTest`

## 왜 했는가

기존 테스트는 이벤트가 유실되지 않는지(`ReservationEventReadIntegrationTest`), 상태 변화마다 정확히 한 건이 남는지(`ReservationEventTriggerIntegrationTest`), Kafka 전송 실패 뒤 재전달되는지(`ReservationEventKafkaIntegrationTest`)를 본다. 소비자 쪽 계약, 즉 같은 그룹이 다시 읽으면 무엇을 받는지와 cursor를 오래된 이벤트로 `advance`하면 어떻게 되는지는 확인한 적이 없다. `advance`의 `WHERE (old) < (new)` 가드가 이를 막아야 하는데 그 가드를 검증하는 테스트가 없었다.

## 방법

- PostgreSQL 18.6 하나에 Flyway 마이그레이션을 적용하고 workflow 6개를 만든 뒤 상태를 바꿔 이벤트를 쌓는다.
- 확인한 성질: (1) `readEvents`의 결과가 `event_seq` 증가 순서다. (2) `advance` 전에 같은 그룹이 다시 읽으면 같은 묶음을 받는다. (3) `advance` 뒤 다음 묶음은 cursor 이후부터 시작하고 이미 받은 이벤트가 다시 오지 않는다. (4) 오래된 이벤트로 `advance`를 다시 호출해도 cursor가 뒤로 가지 않는다. (5) 소비자 그룹마다 cursor가 독립이어서 느린 그룹은 모든 이벤트를 그대로 본다.

## 결과

| 성질 | 결과 |
| --- | --- |
| 읽기 순서 | 증가 순서 |
| 재읽기 동일성 | 같은 묶음 |
| 앞으로만 가는 cursor | 오래된 이벤트로 `advance`해도 이동 없음, 지연(lag) 0 |
| 이미 받은 이벤트의 재전달 | 없음 |
| 그룹별 독립 | 빠른 그룹은 따라잡힘, 느린 그룹은 전부 봄, lag는 이벤트 수와 같음 |

테스트 2건 통과.

## 이 테스트가 결함을 잡는가 (변이 확인)

통과만 하는 테스트는 결함을 볼 수 있는지 알려 주지 않는다. `ReservationEventRepository.advance`의 단조 증가 가드(`WHERE ... < ...` 두 줄)를 임시로 지우고 같은 테스트를 실행했다. 첫 테스트가 `cursor did not move backward`(비어 있어야 하는데 이벤트가 남음)로 실패했다. 가드를 되살린 상태(git 원본)에서 통과한다. 운영 코드는 변경하지 않았고 변이는 로컬에서만 실행했다.

변이는 로컬에서만 실행했고 이 PR의 변경은 새 테스트 1개와 이 문서뿐이다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 위 조건에서 DB 안 소비자가 순서, 재읽기, 단조 cursor, 그룹 독립에 기댈 수 있다.
- 증명하지 못한 것:
  - **중복 제거**: 같은 `event-id`가 두 번 전달되는 경우(Kafka 최소 한 번 전달)는 다루지 않았다. 이 테스트는 DB 안 소비자만 본다. 중복 처리는 여전히 소비자 구현의 책임이며 저장소에는 소비자 구현이 없다.
  - 서로 다른 workflow 이벤트 사이의 전역 순서. `(tx_id, event_seq)` 순서가 커밋 순서와 일치한다는 점은 [EXP-061](EXP-061-event-log-fanout-and-replay.md)과 `ReservationEventReadIntegrationTest`가 다룬다. ADR-0067이 밝힌 대로 같은 workflow 안에서도 드물게 뒤집힐 수 있어 소비자는 `aggregate_version`으로 맞춰야 하는데, 이 뒤집힘을 일으키는 입력은 만들지 않았다.
  - 동시에 여러 소비자 프로세스가 같은 그룹을 읽는 경우. 이 테스트는 단일 스레드다.
  - 소비자가 처리하다 죽었을 때 `advance` 이전 재시작. 같은 묶음을 다시 받는다는 성질(재읽기 동일성)만 확인했다.

## 재현

```sh
./gradlew :application-module:test --tests com.atlas.order.ReservationEventConsumerContractIntegrationTest
```
