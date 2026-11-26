# EXP-089: Kafka broker 장애 10종을 조건별 5회 반복하고 하네스를 저장소에 올리면 relay는 이벤트를 잃거나 순서를 뒤집지 않는가

- 질문: [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md)의 relay(`ReservationEventWorker` → `KafkaReservationEventPublisher`)는 broker가 멈추거나 죽거나 느려져도 이벤트를 잃지 않고 키(workflowId)별 순서를 지키는가? 중복은 얼마나, 어떤 조건에서 생기는가? broker가 돌아오면 밀린 이벤트가 모두 전달되는가?
- 상태: 탐색 완료. #644 첫 전달 역행의 producer/broker mechanism은 [EXP-091](EXP-091-kafka-first-delivery-order.md)에서 broker dump로 규명하고, 실제 앱 경로는 [EXP-100](EXP-100-kafka-first-delivery-fix-real-stack.md)에서 별도 측정했다.
- 날짜: 2026-11-25
- 관련: [ADR-0082](../02-decisions/adr/ADR-0082-relay-batch-size-and-producer-log.md), [EXP-072](EXP-072-kafka-outage-relay.md)(n=1, 하네스 미공개), [EXP-078](EXP-078-relay-lease-length.md)(lease 길이), [EXP-082](EXP-082-event-consumer-contract.md)(소비자 참조 구현), [EXP-085](EXP-085-fault-injection-repeated.md)(같은 구조의 하네스), [EXP-068](EXP-068-stage-evidence-map.md)(6단계 표), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), 이슈 [#638](https://github.com/Neo1228/ATLAS/issues/638)

## 왜 했는가

네트워크 delay2s/delay6s/loss25%, 3-partition topic, pause45s(send-timeout 10s·lease 30s 초과)는 측정했다. 10분 outage, burst-loss, broker replication/failover, Kafka+DB 동시 장애와 application restart는 이 단일-node suite에서 실행하지 않았다; EXP-105 decision/revisit triggers를 아래에 기록한다.

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

## 하네스

`load-tests/kafka-outage/`: `run-kafka-outage.sh`(실행), `compose.yaml`(스택), `event_producer.py`(고정 속도 이벤트 생산), `outage_audit.py`(표본·감사·요약). 조건마다 새 compose 프로젝트(컨테이너·네트워크·볼륨 모두 `atlasexp=f3` 라벨)를 띄우고 같은 스택에서 5회 반복한 뒤 지운다. 생산자는 HTTP·location 서비스를 거치지 않고 order DB에 workflow를 직접 넣는다. 이벤트는 DB trigger(`order_inventory_record_workflow_event`)가 같은 트랜잭션에 쌓으므로 relay가 읽는 이벤트 원천은 같고, relay와 Kafka 경로는 손대지 않았다. 대신 이 실험은 예약 HTTP 요청이 broker 장애에 영향받는지(EXP-072가 본 항목)는 다시 보지 않았다. 네트워크 지연·손실은 broker 컨테이너의 네트워크 namespace에 붙인 일회용 sidecar(`pumba-alpine-nettools`)에서 `tc netem`으로 건다.

## 결과 1: 조건 10종 × n=5 (batch 500, 기본 설정)

| 조건 | n | 통과 | 생산 이벤트(실행당) | 토픽의 고유 이벤트 | 누락(합) | 중복 최소 / 중앙 / 최대 | 최대 사본 수 | 첫 전달 순서 위반(합) | 높은 버전 뒤 재전달 최소 / 중앙 / 최대 | 장애 중 cursor 이동(최대) | 복구 뒤 소진 s 최소 / 중앙 / 최대 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `control` | 5 | 5 | 1600 | 1600 / 1600 / 1600 | 0 | 0 / 0 / 0 | 1 | 0 | 0 / 0 / 0 | 0 | 0.6 / 1.3 / 1.5 |
| `stop10` | 5 | 5 | 1600 | 1600 / 1600 / 1600 | 0 | 0 / 0 / 8 | 2 | 0 | 0 / 0 / 0 | 0 | 0.6 / 0.8 / 1 |
| `kill10` | 5 | 5 | 1600 | 1600 / 1600 / 1600 | 0 | 0 / 0 / 0 | 1 | 0 | 0 / 0 / 0 | 0 | 0.7 / 0.9 / 1.6 |
| `pause8` | 5 | 5 | 1600 | 1600 / 1600 / 1600 | 0 | 0 / 0 / 0 | 1 | 0 | 0 / 0 / 0 | 0 | 0.5 / 0.7 / 1 |
| `pause20` | 5 | 5 | 2000 | 2000 / 2000 / 2000 | 0 | 156 / 316 / 385 | 3 | 0 | 55 / 135 / 169 | 0 | 0.5 / 0.5 / 0.5 |
| `pause45` | 5 | 5 | 3000 | 3000 / 3000 / 3000 | 0 | 151 / 234 / 256 | 3 | 0 | 55 / 95 / 108 | 0 | 0.5 / 0.9 / 1.4 |
| `kill45` | 5 | 5 | 3000 | 3000 / 3000 / 3000 | 0 | 0 / 0 / 0 | 1 | 0 | 0 / 0 / 0 | 0 | 0.5 / 0.6 / 1.2 |
| `delay2s` | 5 | 5 | 2000 | 2000 / 2000 / 2000 | 0 | 0 / 0 / 221 | 2 | 0 | 0 / 0 / 90 | 0 | 1.3 / 2.2 / 2.4 |
| `delay6s` | 5 | 5 | 2000 | 2000 / 2000 / 2000 | 0 | 3 / 39 / 130 | 3 | 0 | 0 / 0 / 40 | 0 | 0.7 / 1 / 2.7 |
| `loss25` | 5 | 5 | 2000 | 2000 / 2000 / 2000 | 0 | 0 / 0 / 0 | 1 | 0 | 0 / 0 / 0 | 0 | 0.5 / 0.6 / 0.8 |

- 검증 통과 = 누락 0, 예상 밖 메시지 0, 첫 전달 순서 위반 0, 키가 한 파티션에만 있음, 토픽 전체를 읽음, cursor가 로그 끝까지 도달, cursor가 거꾸로 가지 않음, 장애 중 cursor 이동 0. 모든 실행의 `event-id` 헤더·payload `eventId`는 `workflowId:aggregateVersion`과 일치했다.
- 같은 스택의 DB에는 이전 실행의 이벤트도 있다. 감사는 실행의 `idempotency_key` 접두사로 이 실행 workflow만 대조하고, 모든 실행의 DB 이벤트 전체도 토픽에 있는지 따로 확인했다(누락 0).
- `kill`·`stop`은 요청이 broker에 도달하지 못해 중복이 거의 없다(`stop10`의 8건은 5회 중 1회). `pause`는 연결이 열린 채 응답만 멈춰 send-timeout(10초)을 넘기면 한 묶음이 기록된 뒤 실패로 처리돼 같은 묶음을 다시 보낸다. `pause8`(시간 안)은 5회 모두 0이고 `pause20`·`pause45`는 5회 모두 151~385건이다.
- 실행함: `delay6s` 5/5에서 3~130 duplicates; `delay2s` 5회 중 2회에서 161·221 duplicates. 지연 2초 condition의 500ms jitter가 producer request timeout 5초 초과를 일으켰다는 원인 설명은 아래 `EXP-105 종결 결과` jitter 0/500ms A/B로 검증했다.
- 복구 뒤 소진 시간은 0.5~2.7초(맥락용)다. lease(30초)가 회복을 막은 실행은 없다.

## 결과 2: 발견 — 첫 전달의 순서 역행 3건

`first_delivery_violations`가 0이 아닌 실행이 3건 있다(모두 `pause20`, 누락 0). 증거(수치)는 아래 "삭제한 파일의 내용" 절에 옮겨 적었다. 같은 workflow의 v2가 v1의 첫 전달보다 앞선 offset에 있다(중복 재전달이 아니다). 같은 파티션 0의 offset 133~157에서 seq 387~415 다음에 seq 764~798이 오고(416~763을 건너뜀), offset 158에서 seq 387부터 다시 시작해 끝까지 전달됐다.

- 3건은 모두 새 스택에서 `pause20`의 **첫 실행(run-1)**이다. 중복이 생긴 실행 90건 중 첫 실행 19건에서 3건, 나머지 71건에서 0건이다(Fisher 정확 검정 양쪽 p≈0.008, 사후에 찾은 패턴이라 탐색 수준). 조건 10종 × n=5 표(50회)와 전후 비교·최종 실행 50회에서는 위반이 0이었고, 위반은 개발 중 실행과 보조 실행 39회 중 3건이다.
- A/B(`pause20`, 원래 코드 8회, `batch.size` 증가 4회)에서 원래 코드 0/8, 변경 쪽 1/4 위반이 발생했다. 따라서 **이 변경으로 위반이 사라졌다고 결론 낼 수 없다**. 원인의 큰 줄기는 이후 [EXP-091](EXP-091-kafka-first-delivery-order.md)에서 segment dump로 규명했다.
- 영향: 소비자가 `aggregateVersion`으로 적용 순서를 맞추면(ADR-0067 한계 절, [EXP-082](EXP-082-event-consumer-contract.md)의 참조 소비자) 상태는 거꾸로 가지 않는다. 누락은 없다. 이 실험의 H2(키별 첫 전달 순서)는 **일부 틀렸다**.

## 결과 3: 설정을 바꾼 전후 (batch 크기와 producer 로그)

[결정(D-148, ADR-0082)](../02-decisions/adr/ADR-0082-relay-batch-size-and-producer-log.md)에 쓴 두 변경의 측정이다. 조건은 같은 하네스·같은 호스트·n=5이고, 실행 시각이 달라 호스트 부하가 같지 않다.

| 조건과 설정 | n | 통과 | 누락(합) | 중복 최소 / 중앙 / 최대 | 첫 전달 순서 위반(합) | 앱 로그 줄 수 최소 / 중앙 / 최대 | 레코드별 producer ERROR 줄 최소 / 중앙 / 최대 | 소진 s 최소 / 중앙 / 최대 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `pause20` 전: batch 500 | 5 | 5 | 0 | 4 / 226 / 320 | 0 | 303 / 813 / 1725 | 4 / 191 / 420 | 0.5 / 0.5 / 0.7 |
| `pause20` 중간: batch 100 | 5 | 5 | 0 | 10 / 74 / 110 | 0 | 81 / 463 / 715 | 10 / 104 / 108 | 0.6 / 0.6 / 1 |
| `pause20` 후: batch 100, producer 로그 끔 | 5 | 5 | 0 | 4 / 104 / 108 | 0 | 28 / 34 / 274 | 0 / 0 / 0 | 0.5 / 0.7 / 0.7 |
| `pause45` 전: batch 500 | 5 | 5 | 0 | 73 / 232 / 249 | 0 | 1725 / 1801 / 2003 | 414 / 426 / 426 | 0.5 / 1.2 / 2.3 |
| `pause45` 중간: batch 100 | 5 | 5 | 0 | 84 / 108 / 135 | 0 | 501 / 518 / 1799 | 110 / 110 / 371 | 0.6 / 1.7 / 2.7 |
| `pause45` 후: batch 100, producer 로그 끔 | 5 | 5 | 0 | 24 / 108 / 108 | 0 | 55 / 57 / 311 | 0 / 0 / 0 | 0.5 / 0.6 / 1 |
| `delay6s` 후: batch 100, producer 로그 끔 | 5 | 5 | 0 | 108 / 108 / 110 | 0 | 31 / 32 / 285 | 0 / 0 / 0 | 0.6 / 0.7 / 1.4 |
| `delay2s` 후: batch 100, producer 로그 끔 | 5 | 5 | 0 | 0 / 0 / 0 | 0 | 0 / 0 / 244 | 0 / 0 / 0 | 0.6 / 0.8 / 1.1 |
| `control` 후: batch 100, producer 로그 끔 | 5 | 5 | 0 | 0 / 0 / 0 | 0 | 0 / 0 / 244 | 0 / 0 / 0 | 0.6 / 0.6 / 0.8 |
| `kill45` 후: batch 100, producer 로그 끔 | 5 | 5 | 0 | 0 / 0 / 0 | 0 | 1368 / 2370 / 2409 | 0 / 0 / 0 | 0.6 / 0.6 / 1.4 |

- 중복의 상한: 한 묶음이 실패하면 그 묶음 전체가 다시 간다. batch 100에서 `pause`·`delay6s`의 중복 최대는 108~110으로 batch 크기(100)에 가깝고, batch 500에서는 최대 385였다. `pause20` 중복 평균은 181(batch 500) → 71.8(100)이고(순열 검정 단측 p=0.048), `pause45`는 202.4 → 104.4(p=0.024)다. 두 검정 모두 n=5 대 5이고 다중 비교 보정을 하지 않았다.
- **이득이 없는 곳**: `delay6s`는 중앙값이 39(batch 500) → 108(batch 100)로 **늘었다**(각 n=5, 검정하지 않음). 묶음이 작아지면 시한을 넘기는 묶음이 더 자주 생기는 것으로 보이나 확인하지 않았다. batch 100이 모든 조건에서 중복을 줄이는 것은 아니다. 누락과 순서는 모든 설정에서 같다(누락 0, 위반 0).
- 로그: producer 기본 `LoggingProducerListener`는 실패한 레코드마다 payload를 포함한 ERROR를 남긴다. `pause45`에서 앱 로그가 batch 500에서 중앙 1,801줄(레코드별 ERROR 426줄), batch 100에서 518줄(110줄), listener를 끈 뒤 57줄(0줄)이다. 실패는 publisher의 예외, relay 실패 counter, 1분에 한 번의 WARN으로 계속 드러난다. `kill45`는 listener를 꺼도 로그가 1,368~2,409줄이다(Kafka client 자체의 연결 실패 로그라 이 변경으로 줄지 않는다).
- 소진 시간은 설정에 따라 달라지지 않았다(0.5~2.7초, 맥락용).

## 가설 대조

| 가설 | 결과 |
| --- | --- |
| H1 누락 0 | 맞음. 조건 10종 50회와 전후 비교·최종 실행을 합친 100회에서 누락 0(이벤트 22만 건 대조). |
| H2 키별 첫 전달 순서 | 일부 틀림. 위 100회에서는 위반 0이나 별도 39회 중 3건에서 위반(결과 2). |
| H3 중복은 조건에 따라 갈림 | 대체로 맞음: 대조군 0, `kill`·`stop` 거의 0, `pause8` 0, `pause20/45` 151~385. 사본 수 최대 3, 한 실행의 중복은 이벤트 수 이하. |
| H4 회복은 수렴 | 맞음. 모든 실행이 90초 안에(0.5~2.7초) 소진. |
| H5 장애 중 cursor 정지 | 맞음. `stop`·`kill`·`pause` 모든 실행에서 이동 0. |
| H6 네트워크 | 일부 틀림: `delay2s`에서 5회 중 2회 중복(예측 0). `delay6s` 중복과 `loss25` 누락 0은 맞음. |
| H7 생산 영향 없음 | 맞음. 모든 실행에서 DB 이벤트 수가 계획과 같았다(생산자는 broker에 의존하지 않음). |

## 편차

- 처음 계획에는 relay 설정 변경이 없었다. 데이터가 중복·로그에서 설정 변경을 가리켜(D-148) 전후를 따로 측정했다.
- 첫 전후 실행에서 compose가 `RELAY_BATCH_SIZE` 기본값 500을 직접 넣어 애플리케이션 기본값이 바뀐 것을 가렸다. 그 실행(중단한 것 포함 15회 안팎)은 버렸고 compose가 값을 지정하지 않으면 애플리케이션 기본값을 쓰게 고친 뒤 다시 돌렸다(`relay-env.txt`로 실제 값을 기록한다).
- `batch.size` 변경은 가설이 지지되지 않아 되돌렸다.
- 보조 증폭 실행은 원래 코드 8회, 변경 4회에서 중단했다(예산).
- 생산은 계획한 초당 20 workflow였고 생산자 틱 지연은 최대 0.083초였다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 이 스택과 조건에서 broker `stop`·`kill -9`·`pause`·지연·손실, send-timeout과 lease보다 긴 중단(45초)을 겪은 100회에서 이벤트 누락 0, 장애 중 cursor 정지, 복구 뒤 수렴. 중복은 `pause`·큰 지연에서 나오고 batch 크기가 한 번의 실패가 만드는 중복의 상한을 가른다.
- 증명하지 못한 것:
  - 첫 전달 순서 역행의 원인(발견 1건). 낮은 버전이 높은 버전 뒤에 오는 것이 아니라 높은 버전이 먼저 첫 전달되는 형태이므로 `aggregateVersion`을 보지 않는 소비자는 영향받는다.
  - 단일 broker 단일 노드이고 복제 1개다. broker 장애 시 리더 선출, `min.insync.replicas`, `acks=all`의 의미 있는 시험은 못 했다.
  - 단일 호스트, 공유 Docker VM, 다른 실험이 동시에 돌았다. 시간 값은 맥락용이고 판단은 개수와 불변식으로 했다.
  - 소비자 그룹 리밸런스는 이 실험에서 다루지 않았다([EXP-082](EXP-082-event-consumer-contract.md)가 파티션 2개·소비자 2개로 확인).
  - 장기 중단(10분 이상) 중 로그 증가와 DB 부하, 장애 중 application 재시작, Kafka와 DB 동시 장애는 하지 않았다.
  - 예약 HTTP 응답은 이 실험에서 보지 않았다(EXP-072가 응답에 영향이 없음을 확인).
  - delay2s duplicate는 jitter A/B 결과로 판정한다. `delay6s`에서 batch 100 중앙값이 batch 500보다 증가한 이유는 batch 단위의 시한·retry 횟수를 분리 측정하지 않았으므로 결정: causal mechanism은 범위 밖, batch 100은 이 조건에서 채택하지 않는다. 대안은 request별 send/timeout trace다. 재검토 조건: 운영에서 delay6s급 요청 timeout 또는 batch 100 중복 증가 1건이 관측되면 trace를 추가한다.
  - 중복 감소 검정은 n=5 대 5, 순열 검정이며 다중비교 보정은 하지 않았다. 이 수치는 탐색 신호로만 남긴다. 결정: 운영 기본 batch를 통계적 유의성 주장 없이 100으로 유지한다. 재검토 조건: 같은 조건에서 n≥20/arm을 사전 등록해 비교할 필요가 생기면 재검정한다.

## 재현

```sh
./gradlew :atlas-application:bootJar   # Colima 등 Docker VM이 jar 경로를 읽을 수 있어야 한다
CONDITIONS="pause20" RUNS=5 load-tests/kafka-outage/run-kafka-outage.sh
```

결과는 `.local/kafka-outage/<시각>/<조건>/run-<n>/`(git 제외)에 `result.json`, `samples.csv`, `topic-run.tsv`, 적용 로그 발췌가 남고 `summary.md`로 요약된다. 실행이 끝나면 compose 프로젝트를 라벨로 지운다. 실제 실행 후 `atlasexp=f3` 라벨의 컨테이너·볼륨·네트워크가 0개임을 확인했다. 호스트 부하(`uptime` load average)는 실행 전 13.5(첫 캠페인)에서 5.0(마지막)까지 달랐다.

## 후속 제안(적용하지 않음)

- [#644](https://github.com/Neo1228/ATLAS/issues/644)의 원인을 가린다: 위반이 난 실행에서 producer 로그 수준을 올리고 idempotent producer의 epoch 증가(`ProducerId set to 0 with epoch`)와 offset 순서를 맞춰 본다. 새 스택 첫 실행에서만 나오는 이유(콜드 producer?)를 `acks`·`max.in.flight.requests.per.connection=1`로 가려 본다.
- 장기 중단과 broker 재시작 중 application 재시작을 같은 하네스에 조건으로 더한다.

## 삭제한 파일의 내용

[EXP-091](EXP-091-kafka-first-delivery-order.md)의 PR이 `EXP-089-evidence/`의 파일 4개(JSON 3, txt 1, 합 약 2.6KB)를 저장소에서 지웠다. 결과 파일을 저장소에 두지 않는 방침 때문이고, 증거가 되는 값은 아래 표에 옮겼다. 위반 3건은 모두 `pause20`(부하 10초 뒤 broker `docker pause` 20초), relay 묶음 기본값(batch 500 이상, send-timeout PT10S, lease PT30S), workflow 1,000개(이벤트 2,000건)다. 열 의미: 토픽 사본 = 토픽에 실린 메시지 수, 고유 = `(workflowId, aggregateVersion)` 종류 수, 중복 = 사본 − 고유, 위반 = 키별 첫 전달 순서 위반 수(`outage_audit.py`의 `first_delivery_violations`), 높은 버전 뒤 재전달 = 이미 높은 버전을 받은 키에 낮은 버전이 다시 실린 수. 모든 실행에서 누락 0, 예상 밖 메시지 0, 파티션 변경 0, 최종 lag 0, 판정 `passed=false`(위반 때문).

| 증거 | run_id | 조건 | 토픽 사본 / 고유 | 중복 | 중복 키 / 최대 사본 | 위반 | 높은 버전 뒤 재전달 | 최대 lag | 장애 길이(s) | 소진(s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 위반 1 | `48a03f73` | batch 500 | 2,358 / 2,000 | 358 | 206 / 2 | 20 | 172 | 1,256 | 20.2 | 0.8 |
| 위반 2 | `8d5c714f` | batch 500, producer 로그 끔 | 2,070 / 2,000 | 70 | 62 / 3 | 15 | 15 | 802 | 20.1 | 0.8 |
| 위반 3 | `92ebe50d` | batch 2,000, producer `batch.size` 상향 | 2,262 / 2,000 | 262 | 163 / 3 | 14 | 111 | 808 | 20.1 | 0.5 |

위반 2(`8d5c714f`)의 파티션 0을 offset 순서로 읽었을 때 `eventSeq`가 오름차순으로 이어지는 구간(총 695건, 구간 3개):

| 구간 | offset | eventSeq 범위 | 건수 |
| --- | --- | --- | --- |
| 1 | 0~132 | 10~392 | 133 |
| 2 | 133~157 | 387~798 | 25 |
| 3 | 158~694 | 387~2000 | 537 |

구간 2에는 387~415와 764~798만 있고 416~763은 없다. 구간 3은 387부터 다시 시작해 끝까지 있다. 이 구간 경계는 이슈 #644의 설명과 같고 원인은 [EXP-091](EXP-091-kafka-first-delivery-order.md)에서 규명했다.

## EXP-105 종결 결과

재실행 표기: 아래 "delay2s 지터 A/B"는 이 에이전트가 직접 실행했다. 그 밖의 범위 결정 문장은 코드·설정을 읽은 결정이며 새 실행이 없다.

### 실행 범위 밖 결정과 재검토 조건

- 실행함: topic은 3 partitions, replication factor 1; `control`, stop/kill/pause, delay2s/delay6s/loss25 각 n=5; `pause45`는 send-timeout PT10S와 lease PT30S보다 길다. 따라서 “다중 파티션 미실행”은 정정한다: 3개 partition에서 workflows가 분산됐고, 키별 order는 partition 내부에서 평가했다.
- 결정: 10분 이상 outage와 application restart는 V1 startup/lease recovery scope 밖이다; 대안은 compose condition으로 `pause600`/app restart를 추가하는 것이나 공유 host와 90s drain limit로 이번 표본에 넣지 않았다. 재검토 조건: production outage SLO가 10분 이상이거나 outage 중 app restart가 운영 시나리오가 되면 해당 조건 n≥3으로 실행한다.
- 결정: broker replication/failover와 `min.insync.replicas`는 KRaft single-node RF=1에서 시험할 수 없고, 동시에 PostgreSQL 장애도 주입하지 않았다. 대안은 3-node broker/PG fault matrix다. 재검토 조건: production replication factor ≥3 또는 broker failover requirement가 확정되면 3-broker suite로 재실행한다.
- 결정: 이 harness는 DB trigger에 직접 입력해 HTTP/Location-service response를 포함하지 않는다. HTTP 경로 회귀를 이 결과로 주장하지 않는다. 대안은 HTTP-level test; 재검토 조건: 주문 API의 Kafka outage response가 운영 계약에 추가되면 end-to-end case를 추가한다.
- 실행함(유형 1): H6의 delay2s 지터 기전은 아래 "delay2s 지터 A/B"에서 반증됐다(지터 0에서도 중복이 같은 비율로 난다). 다른 지터 분포와 6s delay의 원인은 요청 단위 trace가 없어 범위 밖이다(결정, 대안: request별 send/timeout trace). 재검토 조건: delay6s의 batch 크기 효과를 운영에서 재현하거나 delay2s 조건에서 `first_delivery_violations`가 1건 이상 나오면 trace를 수집한다.
- 무변경/음성 대조와 `segments` path: direct broker synthetic-order injection previously detected 10/10 injected violations and 0 normal keys; 35+ actual stack runs called `segments`. 결정: separate unit test for `segments` is unnecessary for this docs/parameter-only change. 재검토 조건: direct-injection detector misses 1 case or future classification disagrees with topic audit.

### delay2s 지터 A/B (G15, 실행함)

가설(EXP-105 사전 등록 G15): 지터 500 ms가 produce 요청을 `request.timeout.ms` 5초 밖으로 밀어 낸 것이 delay2s 중복의 원인이다. 반증 조건: 지터 0에서도 같은 중복.

방법: 수정 전 코드(EXP-089 당시 relay) jar, 같은 compose, `delay2s` 조건(`tc netem delay 2000ms`, 지터 0 또는 500 ms)을 블록당 5회, 블록 순서 지터 0, 500, 500, 0(교차). `NETEM_JITTER_MS`를 하네스에 매개변수로 추가했다(기본 500, 원래 동작). 적용 확인: 블록마다 첫 실행의 `tc qdisc show`가 `delay 2s`(지터 0)와 `delay 2s 500ms`(지터 500)로 찍혔다. 부하 초당 20 workflow, 이벤트 2,000건/회, 호스트 load 18~36(맥락일 뿐 결론은 개수로 낸다).

| 지터 | 회 수 | 중복이 있는 회 | 그 회의 중복 건수 | 최대 사본 | 누락 | 첫 전달 위반 | 판정 `passed` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 ms | 10 | 4 | 164, 166, 168, 248 | 2 | 0 | 0 | 10/10 |
| 500 ms | 10 | 6 | 156, 159, 176, 178, 244, 264 | 2 | 0 | 0 | 10/10 |

중복이 있는 회의 비율은 4/10 대 6/10이고 양측 Fisher 정확 검정 p = 0.66이다. 지터 0에서도 중복이 났으므로 가설은 반증된다. 중복 건수 규모(150~270)도 두 팔이 같다. 이 실험은 중복의 원인이 지터가 아니라는 것까지만 말한다. 2초 고정 지연이 중복을 만드는 기전(요청 단위 재전송 여부)은 요청별 trace가 없어 주장하지 않는다(결정, 대안: request별 send/timeout trace, 재검토 조건은 위 범위 결정과 같다). 누락 0, 첫 전달 위반 0, `passed`는 20/20회에서 유지됐다.

이 실행의 원자료(`result.json`, `samples.csv`, 앱 로그)는 저장소에 올리지 않았다(`삭제한 파일의 내용`의 규칙과 같음). 집계 열은 `result.json`의 `duplicates`, `max_copies`, `lost`, `first_delivery_violations`, `passed`다.
