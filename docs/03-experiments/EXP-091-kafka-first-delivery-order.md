# EXP-091: broker pause 뒤 같은 키의 높은 버전이 낮은 버전보다 먼저 첫 전달되는 원인은 무엇이고 어디서 막아야 하는가

- 질문: [#644](https://github.com/Neo1228/ATLAS/issues/644)에서 `pause20`을 새 스택에서 처음 도는 실행(run-1)에 한해 같은 workflow의 v2가 v1의 첫 전달보다 앞선 offset에 쌓였다(보조 실행 39회 중 3회, 누락 0). 원인(producer, relay, 감사 도구, broker 중 어느 층)은 무엇이고, 가장 단순한 올바른 층에서 어떻게 막는가? 그리고 같은 consumer group의 소비자 둘 사이에 리밸런스가 일어나도 `(workflowId, version)` 단위의 실효 1회 적용이 유지되는가?
- 상태: 사전 등록 단계(이 커밋은 아무것도 실행하기 전에 만들었다). 결과·결론 절은 실행 뒤 같은 파일에 덧붙이며, 아래 가설과 판정 기준은 바꾸지 않는다. 바꿔야 하면 "수정 이력"에 별도 커밋과 이유로 남긴다.
- 날짜: 2026-11-25
- 관련: [EXP-089](EXP-089-kafka-outage-repeated.md)(발견), [EXP-082](EXP-082-event-consumer-contract.md)(소비자 참조 구현), [EXP-072](EXP-072-kafka-outage-relay.md), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [ADR-0082](../02-decisions/adr/ADR-0082-relay-batch-size-and-producer-log.md)

## 문제 (한 문장)

relay가 보낸 이벤트를 `aggregateVersion` 없이 도착 순서대로 적용하는 소비자는, broker가 20초 멈췄다 풀린 뒤 상태를 거꾸로 되돌릴 수 있다. 관측된 빈도는 보조 실행 39회 중 3회(7.7%), 한 실행의 workflow 1,000개 중 14~20개(1.4~2%)가 영향을 받았고, 누락은 0이었다. 원인이 어느 층인지 모르는 채 `aggregateVersion` 검사에만 기대고 있다(수치는 모두 [EXP-089](EXP-089-kafka-outage-repeated.md)에서 가져왔고, "첫 실행에서만"이라는 패턴은 사후에 찾은 것이다).

## 이미 아는 것 (EXP-089 증거와 코드 읽기, 아직 새로 실행한 것은 없다)

- 위반 실행의 파티션 0은 offset 0~132가 seq 10~392, offset 133~157이 seq 387~415와 764~798, offset 158부터 seq 387~2000이다.
- 이 값을 시간으로 환산하면(초당 40건) 부하 10초 지점이 seq 약 400이다. 다시 말해 첫 시도의 묶음은 seq 387부터 시작하고, 전송 한도(10초) 뒤 재시도한 묶음은 약 seq 387~798이다. 764~798은 재시도 묶음의 끝 부분과 겹친다. 이 해석은 **추정**이며 가설로만 쓴다.
- relay 코드: 한 묶음을 `kafka.send`로 전부 낸 뒤 `allOf(...).get(전송한도+1초)`를 기다린다. 시간이 지나 예외를 던져도 이미 낸 레코드는 producer 안에 남아 있고 취소되지 않는다. producer는 `acks=all`, `enable.idempotence=true`, `delivery.timeout.ms=10000`, `request.timeout.ms=5000`, `linger.ms=5`이고 `max.in.flight.requests.per.connection`은 기본값(5)이다. kafka-clients 4.2.2, broker는 `apache/kafka:3.9.1`이다.

## 가설 (실행 전에 적음)

각 가설에는 틀렸다고 판정할 관측(반증 조건)을 같이 적는다. 판정에는 개수와 불변식만 쓰고 시간 값은 맥락으로만 쓴다.

| 가설 | 내용 | 반증 조건 | 지표 | 기대 | 결정에 미치는 영향 |
| --- | --- | --- | --- | --- | --- |
| HA | 감사가 거짓 경보를 낸다(감사 논리나 읽기 방식 오류). | (1) 합성 토픽에 역행을 넣었을 때 감사가 못 잡는다(그러면 감사가 믿을 수 없는 것이고, 반대로 잡으면 감사 논리는 맞다). (2) 재현 실행에서 broker 세그먼트 직접 덤프(`kafka-dump-log.sh`)가 감사와 다른 순서를 보인다. | 합성 대조군 검출 수, 세그먼트 덤프와 감사의 일치 | 합성 역행 N건 입력에 N건 검출, 정상 재전달만 있는 입력에 0건, 덤프와 감사 일치 | 반증되면 도구 문제로 닫고 relay는 건드리지 않는다. |
| HB | 한 producer 안에서 idempotent producer가 전송 시한 만료 뒤 epoch를 올리고 sequence를 다시 매기는 경로가, 한 파티션에 여러 batch가 걸려 있을 때(`max.in.flight`>1) batch 순서를 뒤집는다. | relay와 같은 설정에서 **producer를 하나만** 쓰는 조건이 N=36회 동안 위반 0이고, `max.in.flight=1`과 위반 수 차이가 없다. 또는 위반 구간의 세그먼트 덤프가 전 구간 같은 producerId·epoch에서 baseSequence가 오름차순이다. | 위반 실행 수(조건별 n), 위반 구간의 producerId·epoch·baseSequence | 1 producer, `max.in.flight=5`에서 위반이 나오고 `max.in.flight=1`에서는 0 | 맞으면 `max.in.flight.requests.per.connection=1`이 가장 작은 수정이다. |
| HC | 실패한 시도의 레코드가 producer 안에 남아(future.get 시한이 지나도 취소되지 않음) 다음 시도 및 다른 replica의 producer가 보낸 레코드와 broker에서 섞인다(전송이 겹침). | producer 하나(replica 1대)에서도 같은 빈도로 위반이 나오고, 위반 구간의 producerId가 하나뿐이다. 반대로 producer를 시도마다 새로 만들거나 실패 시 닫는 조건이 위반을 없애지 못한다. | 위반 실행 수(replica 1대 대 2대), 위반 구간의 producerId 수 | replica 2대에서 위반, producerId가 2개 | 맞으면 실패한 시도의 producer를 닫거나(재시작), 시도 사이에 이전 레코드가 모두 끝난 것을 확인한다. |
| HD | "새 스택 첫 실행에서만"은 producer가 차가운 상태(pid epoch 0, 메타데이터 미적재, 첫 InitProducerId)라서 생긴다. | 같은 producer가 이미 한 번 pause를 겪은 두 번째 장애에서 위반 빈도가 첫 장애와 같다(n 충분). | 첫 장애 위반 비율 대 두 번째 장애 위반 비율 | 첫 장애에서 위반, 두 번째에서 0 | 맞으면 cold producer 초기화(워밍업)가 아니라 같은 수정(HB 또는 HC 대응)이 원인에서 막아야 한다. 틀리면 "첫 실행에서만"은 표본 크기 탓으로 기록한다. |
| HE | relay의 cursor 전진·재읽기 로직(DB 쪽)이 순서가 어긋난 묶음을 만든다(예: 건너뛰고 읽음). | producer API 호출 순서(`send` 호출 전 순서)를 기록했을 때 위반 구간이 호출 순서에서도 이미 거꾸로다. 반대로 호출 순서는 키별로 단조인데 로그 순서만 거꾸로면 relay 논리는 무죄다. | `send` 호출 시점 순서의 키별 단조성 위반 수 | 0(호출 순서는 단조) | 맞으면(호출 순서 위반이 있으면) relay 쪽 수정이 우선이다. |
| HG | (스모크 실행의 producer 로그에서 나온 가설, 수정 이력 2) 부분 만료 경쟁. 실패한 시도의 묶음이 파티션마다 producer batch 여러 개로 나뉘면 batch마다 `delivery.timeout.ms`가 따로 흐른다. 전송 중이던 앞 batch는 broker가 돌아온 뒤 늦게 기록되고, 중간 batch는 만료돼 버려지고, 더 늦게 만든 뒤 batch는 broker가 돌아온 시점에 아직 살아 있어 전송된다. idempotence는 sequence 단위로만 빈틈을 막고 레코드 단위로는 막지 못한다(만료로 epoch가 오르면 sequence가 0부터 다시 시작). 그래서 시도의 앞과 뒤만 기록되고 낮은 버전이 있는 중간이 빠지며, 이어지는 재시도가 중간을 다시 보내면 뒤쪽 v2가 중간 v1보다 먼저 첫 전달된다. | (1) replica 1대(`R1`)에서 위반이 0이면 기각(producer 하나로 충분하다는 예측). (2) 파티션당 batch가 하나뿐인 `R2b100`(묶음 100건 약 11KB < `batch.size` 16KB)이 `R2`와 같은 빈도로 위반하면 기각. (3) pause 길이를 15·25초로 바꿔도 20초와 같은 빈도면 기각(예측은 위반이 두 번째 시도의 batch 만료 시각과 unpause가 겹치는 20초 부근에서만 나옴). (4) 위반 구간 세그먼트 덤프에서 앞부분과 뒷부분이 같은 producerId·epoch의 연속 baseSequence이면 기각(예측은 epoch가 다르거나 baseSequence가 0으로 되돌아감). | 위반 시행 수(조건·pause 길이별), 위반 구간의 producerId·epoch·baseSequence | `R1`은 `R2`와 같다, `R2b100`은 0, pause 15·25초는 0, 덤프에서 epoch 변화 | 맞으면 수정은 "한 시도가 파티션당 producer batch 하나를 넘지 않게 나눠 보내고 앞 묶음이 끝난 뒤 다음을 보낸다"(F3) 또는 "실패 시 producer를 닫는다"(F2)이며, `max.in.flight=1`(F1)은 막지 못할 것이다(예측). |

가설은 서로 배타적이지 않다. 반증 조건이 맞으면 해당 가설을 기각하고, 둘 이상이 같이 맞아 보이면 세그먼트 덤프의 producerId·epoch·baseSequence로 가린다. 원인이 이 목록에 없으면 "원인 미확정"으로 쓴다.

## 설계

### 재현기 (결정적 재현이 아니라 고빈도 재현)

실제 relay 경로(DB, lease, 앱 두 대)는 실행 한 번이 스택 기동까지 합쳐 수 분이라 위반 빈도 0.2 수준의 사건을 여러 번 보기에 비싸다. 같은 층(producer와 broker)만 떼어 내 재현기(Java 단일 파일, 저장소 `load-tests/kafka-outage/` 아래)를 만든다. 재현기는 relay의 읽기→전송→대기→cursor 전진 루프를 메모리 이벤트 로그 위에서 그대로 흉내 낸다.

- producer 설정: `ReservationEventConfiguration`과 같은 값을 그대로 쓴다(acks=all, idempotence, delivery.timeout 10초, request.timeout 5초, linger 5ms, max.in.flight 기본 5).
- relay 루프: 0.2초 간격 poll, 묶음 500, 전송 후 `allOf.get(11초)`, 실패하면 lease를 풀고 다음 poll에서 replica 중 하나가 무작위로 잡는다(replica 1대 조건은 항상 같은 producer).
- 부하: 초당 20 workflow, workflow마다 v1과 약 1초 뒤 v2(실행 한 번당 이벤트 2,000건), 부하 10초 뒤 broker를 `docker pause` 20초(EXP-089 `pause20`과 같다).
- broker: `apache/kafka:3.9.1` 단일 노드 KRaft, 토픽 파티션 3개·복제 1개, 컨테이너 하나(`atlasexp=h1` 라벨).
- 판정: 각 시행의 토픽 내용을 파티션·offset 순서로 읽어 `outage_audit.delivery_order`(하네스 감사와 같은 함수)에 넣는다. 위반 시행은 세그먼트를 `kafka-dump-log.sh`로 덤프해 batch 단위 producerId, epoch, baseSequence, offset을 본다.
- 시행(trial) 하나는 독립된 토픽과 producer(들)이다. 한 라운드에서 시행 여러 개를 한 broker 위에서 동시에 돌리고(broker pause는 라운드 단위로 공유), 라운드는 broker를 새로 띄운다. 시행마다 장애를 두 번 겪는다(첫 장애는 cold, 두 번째는 warm). 시행 사이는 서로 다른 토픽·producer라 독립이지만 같은 broker·같은 장애 시각을 공유하므로, 보고에는 시행 단위와 라운드 단위를 같이 쓴다.

### 조건

| 조건 | 내용 | 역할 |
| --- | --- | --- |
| `R2` | replica 2대, relay 설정 그대로 | 원래 조건의 재현 |
| `R1` | replica 1대, 나머지 같음 | HC 판별 |
| `R2mif1` | `R2`에 `max.in.flight.requests.per.connection=1` | HB 판별, 후보 수정 |
| `R2b100` | `R2`에서 relay 묶음만 100(현재 main 기본값, 수정 이력 1) | 현재 기본값에서도 나오는지 |
| 각 조건의 두 번째 장애 | 같은 producer에서 두 번째 pause | HD 판별 |

수정안 후보(원인이 밝혀진 뒤에 같은 재현기로 비교한다. 후보와 결정 기준은 이 문서의 "수정 이력"에 그 시점의 커밋으로 남긴다):

- F1 `max.in.flight.requests.per.connection=1`.
- F2 relay가 전송 실패 시 producer를 닫아 남은 레코드를 버린다(재시작).
- F3 묶음마다 이전 묶음의 모든 레코드가 완료(성공이나 실패)됐음을 확인한 뒤에만 다음 묶음을 보낸다.
- F0 아무것도 바꾸지 않고 소비자 계약(`aggregateVersion` 기준 적용)에만 기댄다(do nothing).

### 대조군

- 무변경 대조군: `R2` 원래 설정에서 장애를 넣지 않은 시행(위반 기대 0, 이것이 0이 아니면 재현기나 감사가 틀린 것이다).
- 음성 대조군(검출기가 반드시 울려야 하는 경우): (1) 합성 토픽에 키별 v2를 v1보다 앞에 쓴 입력을 넣어 감사가 위반으로 센다. (2) 정상 재전달(같은 묶음이 순서대로 다시 옴)만 있는 합성 입력은 첫 전달 위반 0이고 재전달 역행 수만 센다. (3) 실제 broker에 producer 두 개로 레코드를 일부러 엇갈려 보낸 토픽(알려진 역행)이 감사와 세그먼트 덤프에서 모두 위반으로 나온다.
- 전후 비교: 같은 재현기·같은 broker 이미지·같은 부하이고 시행 순서는 라운드마다 조건을 섞는다. 첫 20초(워밍업)는 판정에 쓰지 않는다.

### 표본 크기와 종료 기준

- 원래 관측은 첫 장애에서 중복이 생긴 16건 중 3건(약 0.19, 사후에 찾은 값이라 가정이다). 조건당 첫 장애 시행이 n=18이면 p=0.19일 때 1회 이상 볼 확률은 1−0.81^18≈0.98이다.
- 조건당 n=18에서 0건이면 3/n=0.167(95% 상한, rule of three)이라 0.19와 가를 수 없다. 이때는 라운드를 6까지 늘려 n=36(상한 0.083)에서 멈춘다. 그래도 0건이면 "재현하지 못함, 위반 확률 상한 8.3%"로 기록하고 원인을 확정하지 못했다고 쓴다.
- 수정안은 수정 전 조건에서 위반이 확인된 뒤에만 같은 n으로 시험하고, 위반 0이 나오면 3/n 상한을 같이 쓴다.
- 시행이 라운드 안에서 같은 pause를 공유하므로 효과적 독립 단위는 라운드와 시행 사이 어딘가다. 상한은 시행 단위와 라운드 단위(더 보수적)를 같이 적는다.

### 층별 조사 순서

앱 로그·relay 시도 기록 → producer debug 로그(epoch, sequence, batch id) → broker 로그·세그먼트 덤프 → 필요하면 tcpdump → 그 아래(런타임 thread dump, GC, cgroup)는 증거가 거기를 가리킬 때만 간다. 멈춘 층과 이유를 결과에 적는다.

### 소비자 리밸런스(두 번째 질문)

EXP-082의 시험용 참조 소비자(`ReservationEventReferenceConsumer`)를 확장해 같은 group에 소비자 둘을 두고, 전달 도중 하나를 죽이거나 하나를 추가한다. 불변식: (a) 모든 `(workflowId, version)`이 최종적으로 적용되고, (b) 어떤 workflow도 낮은 버전이 높은 버전 뒤에 적용되지 않으며, (c) 같은 `(workflowId, version)`이 두 번 적용되지 않는다(한계: 적용 기록과 offset commit이 한 트랜잭션이라는 전제). 반복 n≥5, 가설 HF는 "리밸런스를 가로질러도 위 세 불변식이 유지된다"이며 반증 조건은 하나라도 어긋난 시행이다. 첫 전달 역행이 있는 입력(합성)에서도 같은 불변식이 유지되는지 확인한다.

## 사전 위험과 중단 조건

- 재현기가 실제 relay와 다르게 동작해 현상이 안 나올 수 있다 → 재현기에서 안 나오면 실제 하네스(`run-kafka-outage.sh`)를 `pause20`으로 반복해 같은 조건을 시험한다(그때 n과 중단 기준은 같다).
- 호스트가 공유돼 pause 시각이 흔들린다 → 판정은 개수와 불변식으로만 한다. 시작·종료 시 `uptime`을 남긴다.
- 컨테이너는 라벨 `atlasexp=h1`로만 만들고 끝에 라벨로 0개를 확인한다.

## 수정 이력

- 수정 1(첫 라운드 실행 전, 같은 커밋): (a) 조건 `R2b100` 추가. 코드를 읽다가 `origin/main`의 relay 묶음 기본값이 이미 100으로 바뀌었음([ADR-0082](../02-decisions/adr/ADR-0082-relay-batch-size-and-producer-log.md))을 확인했다. 이슈의 위반은 모두 묶음 500·2,000에서 나왔으므로 현재 기본값에서의 상태를 따로 봐야 한다. 가설과 판정 기준은 바꾸지 않았다. (b) 재현기 기계 동작을 확인하려고 사전 등록 라운드 전에 스모크 실행을 한 번(`R2` 1시행, 장애 1회) 했다. 이 실행은 위반 19건이 나왔고 표본 n에 넣지 않는다(결정에 쓰지 않음). 그 실행에서 broker 세그먼트 덤프가 비어(로그 경로 설정 누락) 고쳤고, 시험 코드의 음성 대조군 기대값 하나가 틀려 고쳤다(감사 함수는 그대로).
- 수정 2(첫 라운드 실행 전): 스모크 실행의 producer DEBUG 로그에서 가설 HG를 세웠다. 근거 관찰 세 가지: 같은 시도의 batch 다섯 개가 요청 시한(5초)으로 한꺼번에 연결이 끊기고 `Reordered incoming batch with sequence ...`로 재정렬됐다. 그 뒤 `ProduceResponse returned SUCCEEDED ... after batch with base offset ... had already been FAILED`(실패 처리한 batch를 broker가 나중에 기록)가 나왔다. 두 번째 시도가 시작된 시각에서 전송 한도(10초)를 더한 시각이 unpause 시각(pause 20초)과 겹쳤다. 이 관찰들은 한 번의 스모크 실행에서 나온 것이라 가설일 뿐이고, 판정은 사전 등록된 라운드의 숫자로만 한다. 조건 `R2b100`에 "파티션당 batch 하나" 예측이 걸려 있어 새 조건은 필요 없다. pause 길이 스윕(15, 18, 20, 22, 25초)을 `R2`·`R2b100`에 추가한다(시행 n은 표와 같은 방식으로 라운드당 6).
