# EXP-089: Kafka broker 장애 10종을 조건별 5회 반복하고 하네스를 저장소에 올리면 relay는 이벤트를 잃거나 순서를 뒤집지 않는가

- 질문: [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md)의 relay(`ReservationEventWorker` → `KafkaReservationEventPublisher`)는 broker가 멈추거나 죽거나 느려져도 이벤트를 잃지 않고 키(workflowId)별 순서를 지키는가? 중복은 얼마나, 어떤 조건에서 생기는가? broker가 돌아오면 밀린 이벤트가 모두 전달되는가?
- 상태: **가설 단계(실행 전 커밋)**. 결과와 가설 대조는 실행 뒤에 이 문서에 추가한다.
- 날짜: 2026-11-25
- 관련: [EXP-072](EXP-072-kafka-outage-relay.md)(n=1, 하네스 미공개), [EXP-078](EXP-078-relay-lease-length.md)(lease 길이), [EXP-082](EXP-082-event-consumer-contract.md)(소비자 참조 구현), [EXP-085](EXP-085-fault-injection-repeated.md)(같은 구조의 하네스), [EXP-068](EXP-068-stage-evidence-map.md)(6단계 표), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), 이슈 [#638](https://github.com/Neo1228/ATLAS/issues/638)

## 왜 했는가

[EXP-072](EXP-072-kafka-outage-relay.md)는 시나리오마다 한 번 실행했다. "누락 0"은 8개 실행 모두에서 나왔지만 중복 89건(`pause30`)과 0건(`kill20`, `stop10x2`)이 우연인지 조건 때문인지 구별할 수 없고, 하네스가 저장소에 없어 다시 돌릴 수 없다. 네트워크 지연·손실, 토픽 파티션이 여러 개인 경우, send-timeout과 lease보다 긴 중단은 측정하지 않았다. 소비자 쪽은 [EXP-082](EXP-082-event-consumer-contract.md)가 결정적 테스트로 확인했으나 broker 장애를 길게 겪는 실제 relay 경로는 아니다.

## 가설 (실행 전에 적음)

판정에 쓰는 지표는 모두 개수와 불변식이다. 시간 값(소진 시간)은 공유 머신이라 맥락 정보일 뿐 판정에 쓰지 않는다.

- **H1 누락 0.** 모든 조건의 모든 실행에서 이벤트 로그 테이블의 `(workflow_id, aggregate_version)`이 전부 토픽에 있다. 근거: cursor는 모든 broker ack(`acks=all`) 뒤에만 전진한다(`KafkaReservationEventPublisher.publish`가 `allOf(...).get(timeout)`을 통과해야 `advanceFenced`가 호출된다).
- **H2 키별 순서 보존.** 각 workflowId의 첫 전달만 보면 `aggregateVersion`이 엄격히 증가한다(위반 0). 파티션을 3개로 하고 키가 같으면 같은 파티션이다. 중복 재전달이 높은 버전 뒤에 낮은 버전을 다시 싣는 것("역행 재전달")은 H2 위반이 아니며 따로 센다([EXP-072](EXP-072-kafka-outage-relay.md)에서 44건).
- **H3 중복은 있을 수 있고 조건에 따라 갈린다.**
  - 대조군은 0이다.
  - 연결이 끊겨 요청이 broker에 도달하지 못하는 `stop`과 `kill -9`는 중복이 0이거나 매우 적다([EXP-072](EXP-072-kafka-outage-relay.md)에서 0). 이 가설이 틀릴 수 있다: 중단 직전 요청이 broker에 기록되고 ack가 유실되면 중복이 생긴다.
  - 응답만 멈추는 `pause`는 send-timeout(10초)보다 짧으면(8초) 0이고 길면(20, 45초) 1건 이상이다.
  - 상한(느슨하게 정한 추정): 한 이벤트의 사본 수는 `1 +` 그 이벤트를 담은 묶음의 실패 횟수 이하이고, 한 실행의 중복 합계는 그 실행의 이벤트 수(=모든 이벤트가 한 번 더) 이하다. 이 상한이 깨지면 relay가 같은 묶음을 다른 이유로 되풀이해 보낸다는 뜻이므로 발견으로 기록한다.
- **H4 회복은 수렴한다.** broker가 돌아온 뒤 90초 안에 cursor가 로그 끝(최대 `event_seq`)에 도달하고 lag가 0이 된다. 두 application이 살아 있으므로 lease(30초)가 회복을 막지 않는다(실패한 묶음은 즉시 `releaseLease`한다). 이 가정이 틀리면 lease보다 긴 지연이 관찰된다.
- **H5 cursor는 장애 중 전진하지 않는다.** broker에 도달할 수 없는 구간(장애 시작 2초 뒤부터 복구까지)에 cursor는 움직이지 않는다. 움직이면 ack 없이 cursor가 전진했다는 뜻이다. 네트워크 지연·손실 조건은 broker에 닿으므로 이 검사 대상이 아니다.
- **H6 네트워크 지연·손실.** 응답이 2초 늦는 조건은 모든 묶음이 send-timeout 안에 끝나 중복 0이다. 6초 지연(producer `request.timeout.ms` 5초보다 큼)은 전송 실패와 재시도로 중복이 생기되 누락은 0이다. 손실 25%는 느려질 뿐 누락 0이다.
- **H7 이벤트 생산은 영향받지 않는다.** 이벤트는 workflow 상태와 같은 트랜잭션으로 DB에 쌓이므로 broker 장애와 상관없이 목표 개수가 생산된다(생산자는 broker에 의존하지 않는다).

## 조건

고정 입력(아래 값은 실행 전에 정했다):

| 항목 | 값 |
| --- | --- |
| 스택 | order PostgreSQL 1개(`postgres:18.6`), Kafka 1대(`apache/kafka:3.9.1`, KRaft 단일 노드, 토픽 파티션 3개·복제 1개), relay를 켠 application 2대(같은 jar, 같은 cursor lease를 경쟁) |
| relay 설정 | 기본값 그대로: batch-size 500, poll-interval 0.2초, send-timeout PT10S, lease-duration PT30S |
| 생산 | 초당 20 workflow 고정 속도. workflow마다 `PENDING` insert(v1)와 약 1초 뒤 `RESERVED` update(v2)로 이벤트 2건 |
| 장애 시점 | 부하 시작 10초 뒤. 부하 길이 = 10초 + 장애 길이 + 20초(최소 40초) |
| 반복 | 조건마다 n=5. 조건마다 스택을 새로 띄우고 같은 스택에서 5회 반복(각 회는 새 workflow 집합으로 구분) |

| 조건 | 장애 |
| --- | --- |
| `control` | 없음 |
| `stop10` | `docker stop`, 10초 뒤 `docker start` |
| `kill10` | `docker kill`(SIGKILL), 10초 뒤 `docker start` |
| `pause8` | `docker pause` 8초(send-timeout 10초보다 짧음) 뒤 `unpause` |
| `pause20` | `docker pause` 20초(send-timeout보다 길고 lease 30초보다 짧음) |
| `pause45` | `docker pause` 45초(send-timeout과 lease보다 김) |
| `kill45` | `docker kill`, 45초 뒤 `docker start`(send-timeout과 lease보다 긴 연결 거부) |
| `delay2s` | broker 컨테이너 송신 쪽에 `tc netem delay 2000ms 500ms`를 20초 |
| `delay6s` | `tc netem delay 6000ms 500ms`를 20초(producer `request.timeout.ms` 5초보다 큼) |
| `loss25` | `tc netem loss 25%`를 20초 |

## 감사 방법

실행마다 (1) 이벤트 로그 테이블에서 이 실행이 만든 workflow의 `(workflow_id, aggregate_version)` 집합을 읽고, (2) 토픽을 처음부터 끝까지 읽어 대조한다. 누락(DB에 있고 토픽에 없음), 예상 밖 메시지(토픽에 있고 DB에 없음), 중복(같은 키와 버전이 두 번 이상), 키별 첫 전달의 버전 역행(H2), 역행 재전달(정보용), 이벤트 로그 최대 `event_seq`와 cursor, 1초 간격 cursor·lag 표본으로 H5와 소진 시간을 본다.

## 이 문서의 현재 한계

결과는 아직 없다. 아래 절은 실행 뒤에 채운다.
