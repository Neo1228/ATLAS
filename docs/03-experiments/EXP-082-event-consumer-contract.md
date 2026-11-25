# EXP-082: Kafka 토픽을 읽는 소비자가 중복 전달, 같은 그룹의 두 인스턴스, 재시작, 브로커 중단에서 이벤트를 잃거나 두 번 적용하지 않는가

- 질문: [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md)은 중복 제거와 적용 순서를 소비자 책임으로 두었다. 소비자가 그 책임을 지면 relay가 실제로 쓴 토픽에서 (1) 중복 전달이 한 번만 적용되는가, (2) 같은 그룹의 두 인스턴스가 리밸런스를 거쳐도 겹쳐 적용하지 않는가, (3) 재시작이 commit한 offset부터 이어 읽고 commit 못 한 구간의 재전달을 한 번만 적용하는가, (4) 브로커가 멈췄다 돌아와도 유실과 중복 적용이 없는가?
- 상태: 확인 완료(로컬). 결정적 통합 테스트 4건이며 저장소에 둔다. 소비자는 테스트 전용 참조 구현이다([ADR-0076](../02-decisions/adr/ADR-0076-reference-consumer-in-tests-only.md)).
- 날짜: 2026-11-25
- 관련: [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [ADR-0076](../02-decisions/adr/ADR-0076-reference-consumer-in-tests-only.md), [EXP-067](EXP-067-event-consumer-contract.md), [EXP-072](EXP-072-kafka-outage-relay.md), [EXP-078](EXP-078-relay-lease-length.md), 테스트 `ReservationEventConsumerKafkaIntegrationTest`

## 왜 했는가

[EXP-068](EXP-068-stage-evidence-map.md) 6단계 표의 "중복 제거, 다중 프로세스 소비자, 브로커 중단" 행은 "없음"이었다. [EXP-067](EXP-067-event-consumer-contract.md)은 DB 안 소비자의 읽기 순서와 cursor만 확인했고 중복 제거는 "소비자 구현이 없어서" 증명하지 못했다고 적었다. [EXP-072](EXP-072-kafka-outage-relay.md)는 relay 쪽 중단(누락 0, 중복은 `pause`에서만)을 봤지만 소비자는 없었다. relay가 중복을 허용하는 설계라서([ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md)) 소비자 쪽 중복 제거가 성립하는지가 이 설계의 남은 전제였다.

## 방법

- 소비자는 `ReservationEventReferenceConsumer`(테스트 소스). `enable.auto.commit=false`, 레코드마다 `(workflowId, aggregateVersion)`로 PostgreSQL에서 `INSERT ... ON CONFLICT DO NOTHING`을 하고 1행이 들어갈 때만 부수 효과 행을 한 트랜잭션으로 넣는다. offset commit은 적용 뒤에 따로 한다. 부수 효과 테이블에는 유일 제약이 없어 중복 적용이 행 수로 드러난다.
- 생산 쪽은 모의가 아니라 실제 `ReservationEventWorker`, `ReservationEventRepository`, `KafkaReservationEventPublisher`다. 중복이 필요한 시나리오는 기존 `ReservationEventKafkaIntegrationTest`와 같은 방식으로 첫 묶음을 전송한 뒤 실패시켜 relay가 같은 묶음을 다시 보내게 한다(`FailingAfterSendPublisher`, 이 PR에서 두 테스트가 공유하도록 파일로 분리).
- 환경: `postgres:18.6` 1개, `apache/kafka:3.9.1`(KRaft 단일 노드) 1개(Testcontainers, Colima), 클라이언트 `kafka-clients` 4.2.2, 테스트마다 새 스키마와 새 토픽. 기반 main은 `546d8002`.
- 시나리오(workflow의 절반은 RESERVED로 바꿔 workflow당 이벤트 1~2건):

| 테스트 | 토픽 파티션 | 이벤트 수 | 조건 |
| --- | --- | --- | --- |
| `duplicateDeliveryLosesNothingAndEachEventIsAppliedOnce` | 1 | 18 | relay가 첫 묶음 4건을 두 번 전송, 소비자 1개 |
| `twoConsumersInOneGroupNeverApplyAnEventTwiceAcrossARebalance` | 2 | 36 | 소비자 A가 둘 다 읽고 적용만 한 뒤 B가 같은 그룹에 합류, commit 없이 리밸런스 |
| `restartResumesFromTheCommittedOffsetAndRedeliveryAfterACrashIsAppliedOnce` | 1 | 18 | 1번 소비자: 첫 poll(최대 5건) 적용 후 commit하고 종료. 2번: 이어서 적용만 하고 commit 없이 종료(crash). 3번: 같은 그룹으로 시작해 끝까지 |
| `consumerRecoversAfterABrokerPauseWithoutLossOrDoubleApply` | 1 | 18 | 첫 poll 적용 뒤 Kafka 컨테이너를 `docker pause`, 그 동안 poll과 commit(2초 제한), 해제 뒤 끝까지 |

## 결과

로컬에서 6회(관측용 출력 줄만 더한 판 6회, 단언은 같다, 빌드 캐시 끔) 실행해 4건 모두 통과했다. 원본 테스트 소스로도 1회 통과했다. 관측값:

| 시나리오 | 이벤트 | 소비자가 받은 전달 | 건너뛴 중복 | 부수 효과 행 |
| --- | --- | --- | --- | --- |
| 중복 전달 | 18 | 22(매 회 같음) | 4 | 18건 각 1행 |
| 두 인스턴스 | 36 | 72(A 50~59, B 13~22) | 36(매 회 같음) | 36건 각 1행 |
| 재시작 | 18 | 3번 소비자 13(5번째 뒤부터) | 5(2번 소비자가 적용만 한 구간) | 18건 각 1행 |
| 브로커 pause | 18 | 18 | 0 | 18건 각 1행 |

- 재시작: 첫 commit은 5건, 3번 소비자의 첫 레코드는 6번째였고 committed 앞은 다시 읽지 않았다. 종료 뒤 그룹의 commit offset 합은 18이었다.
- 두 인스턴스: 합류 뒤 A와 B에 파티션이 1개씩 나뉘었다. 36건이 모두 두 번 전달됐고(처음 A가 읽고, 리밸런스 뒤 commit이 없어 다시 읽음) 두 번째는 모두 건너뛰었다. 이는 다른 인스턴스가 이미 적용한 전달을 거르는 경우를 포함한다.
- 브로커 pause: pause 중에 commit은 시간 초과(false)로 실패했고 소비자는 죽지 않았다. 해제 뒤 끝까지 읽고 commit이 토픽 끝(18)까지 갔다. 관측에서는 pause 직후 poll에서 이미 받아 둔 5건이 처리됐다. 즉 이 시나리오는 "소비가 막혔다"가 아니라 "commit이 막혔다가 복구됨"을 확인한다.

## 이 테스트가 결함을 잡는가 (변이 확인)

운영 코드는 건드리지 않고 참조 소비자(테스트 소스)에만 임시 변이를 넣어 같은 테스트를 실행한 뒤 원본으로 되돌려 통과를 확인했다.

| 변이 | 결과 |
| --- | --- |
| 중복 제거 제거(`ON CONFLICT` 결과를 보지 않고 부수 효과를 항상 기록) | 4건 중 3건 실패: 중복 전달, 두 인스턴스, 재시작(`건너뜀 expected 5 but was 0`). pause 시나리오는 재전달이 없어 통과한다 |
| offset commit 제거 | 4건 중 2건 실패: 재시작(commit한 offset부터 읽지 않음), pause(commit이 시간 초과가 아니라 성공으로 보임) |

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 위 조건에서, 적용 표시를 PostgreSQL 행(기본 키 `(workflowId, aggregateVersion)`)에 두고 commit을 적용 뒤에 하는 소비자는 relay가 만든 중복 전달, 다른 인스턴스로의 파티션 이동, 재시작, commit 못 한 구간의 재전달, commit이 막혔던 브로커 중단에서 유실 없이 한 번만 적용한다. 토픽 JSON 필드와 `event-id` header가 소비자가 쓸 수 있는 계약이라는 것도 이 테스트가 고정한다.
- 증명하지 못한 것:
  - 운영 소비자의 동작. 만들지 않았다([ADR-0076](../02-decisions/adr/ADR-0076-reference-consumer-in-tests-only.md)). 실제 소비자의 부수 효과가 DB 밖(HTTP 호출, 다른 저장소)이면 적용 표시와 효과를 한 트랜잭션에 묶을 수 없고 이 테스트는 그 경우를 다루지 않는다.
  - 같은 workflow 이벤트의 순서 뒤집힘(ADR-0067 한계)과 그에 대한 `aggregateVersion` 재정렬. 입력을 만들지 않았고 참조 소비자는 중복 제거만 한다.
  - 규모. 파티션 최대 2개, 소비자 2개, 이벤트 18~36건이다. 부하, 대량 리밸런스, 긴 중단(세션 만료로 소비자가 그룹에서 빠지는 경우)은 보지 않았다. pause는 짧은 구간(commit 시간 제한 2초)만 줬다.
  - 브로커 `kill`/재시작. 시도하지 않았고 `docker pause`만 썼다.
  - 소요 시간. 테스트 클래스 28초는 다른 작업이 함께 도는 공유 호스트에서의 값이라 성능 근거가 아니다.
  - CI 안정성. 로컬 7회 연속 통과가 전부다. CI에서의 반복 실행은 확인하지 않았다.

## 재현

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/mz01-juwannam/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true
./gradlew --no-build-cache :atlas-application:cleanTest :atlas-application:test --tests 'com.atlas.order.ReservationEventConsumerKafkaIntegrationTest'
```
