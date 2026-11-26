# EXP-100: 첫 전달 순서 수정(#660)은 실제 앱·PostgreSQL·lease 조합에서도 역행을 없애고, 처리량을 해치지 않으며, 설명하지 못한 batch 안 순서 이탈 10건은 무엇이었는가

- 질문: [EXP-091](EXP-091-kafka-first-delivery-order.md)의 수정(덩어리 전송, [ADR-0086](../02-decisions/adr/ADR-0086-relay-chunked-send-for-first-delivery-order.md))은 DB·lease 없는 메모리 재현기에서만 측정했다. 실제 relay 경로(앱 2대, PostgreSQL 이벤트 로그, lease)에서 같은 효과가 나는가, relay 배출 처리량은 어떻게 변하는가, EXP-091이 설명하지 못한 "한 batch 안 10건"은 실제 현상인가 감사 오류인가.
- 상태: 완료(로컬, **계획보다 작은 표본**). 가설·표본·판정 규칙은 측정 전에 따로 커밋했고(커밋 `docs(experiment): 첫 전달 순서 수정의 실제 스택 검증 가설·표본·판정 규칙을 측정 전에 기록 (EXP-100)`) 결과 절은 측정 뒤에 덧붙였다. 계획한 팔당 45회 중 **35회(블록 7개)에서 멈췄다**(아래 "계획과 달라진 점"). 원자료는 저장소에 두지 않았다.
- 날짜: 2026-11-25
- 관련: [EXP-091](EXP-091-kafka-first-delivery-order.md), [EXP-089](EXP-089-kafka-outage-repeated.md), [ADR-0086](../02-decisions/adr/ADR-0086-relay-chunked-send-for-first-delivery-order.md), [ADR-0087](../02-decisions/adr/ADR-0087-consumer-contract-first-delivery-order-and-rebalance.md), [ADR-0082](../02-decisions/adr/ADR-0082-relay-batch-size-and-producer-log.md)

## 문제 한 문장

`aggregateVersion`을 확인하지 않는 소비자는 broker가 20초 멈춘 뒤 같은 workflow의 v1이 v2보다 늦게 도착하면 상태가 거꾸로 가는데(원래 관측 3/39 = 7.7%, 재현기 25/360 = 6.9%), 이 위반을 없앤 수정이 재현기(DB 없음, lease 없음)에서만 0/144로 확인됐고 실제 앱 경로, 처리량, 한 batch 안의 10건 이탈은 측정되지 않았다.

## 가설 (실행 전에 적음)

모든 시행은 broker를 20초 멈추는 `pause20` 한 번이다. "기준선 비율" p_ref는 EXP-091의 운영 클래스 수정 전 값과 R2 값을 합친 25/360 = 6.9%(가장 낮게 잡으면 14/216 = 6.5%)다.

| 번호 | 가설 | 반증 조건(falsifier) | 지표·기대값 | 이 결과가 정하는 결정 |
| --- | --- | --- | --- | --- |
| H1 | 수정 후 빌드(AFTER)는 실제 앱·DB·lease 경로에서 `pause20` n=45회 동안 첫 전달 역행이 0이다 | AFTER에서 1회라도 역행(`first_delivery_violations` ≥ 1) | AFTER 위반 시행 수, 기대 0. 0이면 95% 상한 3/45 = 6.7% | ≥ 1이면 수정이 실제 앱에서 불완전하다: 구간 분류(`segments`)로 원인을 찾고 ADR-0095로 추가 수정. 0이면 "재현기 결과가 실제 앱에서 반복됨"으로 기록 |
| H2 | 수정 전 빌드(BEFORE, #660의 main 변경 4개 파일을 되돌린 jar)는 같은 하네스에서 기준선 비율로 역행이 난다 | BEFORE n=45에서 0건. p ≥ 6.5%이면 0건일 확률은 (1−0.065)^45 = 4.9% 이하라 "실제 스택이 기준선을 재현하지 못함"으로 판정 | BEFORE 위반 시행 수, 기대 3.1(= 45 × 6.9%), 1건 이상 볼 확률 ≥ 95% | 0건이면 H1의 0은 증거가 아니다(검출기가 울릴 수 없는 상황). BEFORE만 15회(3 블록) 더 돌려 n=60(3/60 = 5%)에서 멈추고 "실제 스택에서 기준선 미재현"으로 쓴다 |
| H3 | 감사와 구간 분류가 알려진 역행에서 울린다(음성 대조군) | 알려진 역행 입력(브로커에 v2 다음 v1을 직접 실은 토픽)에서 위반 0 | 합성 키 N개에서 위반 N | 울리지 않으면 이 실험의 0은 전부 무효 |
| H4 | 두 빌드 모두 EXP-089의 불변식(누락 0, 예상 밖 0, 파티션 이동 0, 장애 중 cursor 정지, 제한 시간 안 배출)을 유지한다 | AFTER에서 BEFORE에 없는 불변식 실패 | 시행별 `passed` | 있으면 덩어리 전송이 lease·cursor 상호작용을 깬 것이므로 원인 추적 |
| H5 | 포화 부하에서 AFTER의 relay 배출 처리량(초당 이벤트)은 BEFORE의 80% 이상이다 | 같은 부하에서 AFTER 중앙값 < 0.8 × BEFORE 중앙값 | `samples.csv`의 cursor 기울기(이벤트/초), 기대 비율 0.9~1.1(덩어리 왕복이 묶음당 1회에서 2회로 늘지만 DB 읽기와 commit이 지배한다는 가정) | 미달이면 덩어리 크기를 64KB에서 키우는 안과 비용을 비교해 ADR-0095에 기록 |
| H6 | EXP-091이 설명하지 못한 "한 batch 안 10건"은 실제 broker 로그에 있는 현상이다(감사 오류가 아니다): relay의 연속된 두 시도에서 앞 시도 꼬리와 뒤 시도 머리가 한 producer batch에 담긴 것이다 | 보관된 원자료 10건 중 한 batch라도 eventSeq 단조 증가 구간 둘로 이뤄지지 않거나, 구간 경계가 시도 범위의 끝에서 시작으로 돌아가는 모양이 아님 | 10건 각각의 batch를 eventSeq 단조 구간으로 분해. 기대: 모두 2구간. 실제 스택에서는 `batch_event_seq_decreases`가 AFTER에서 0 | 반증이면 "원인 확정 못 함"으로 둔다. 2구간이면 수정이 이 틈을 막는지를 실제 스택 AFTER 0으로 확인(BEFORE는 드물어 0이어도 반증이 아니다) |
| H7 | 덩어리 상한 64KB와 batch 128KB는 바꿀 필요가 없다 | H1 반증이거나 H5 반증 | 실제 레코드 크기(batch 크기 ÷ 레코드 수)가 코드의 추정(payload 길이 + 128B)을 넘지 않음 | 넘거나 H1·H5가 반증이면 상수를 바꾸는 안을 비교한다. 아니면 바꾸지 않는다 |

## 설계

### 조건

- 앱·DB·broker: 하네스 `load-tests/kafka-outage/`의 compose(PostgreSQL 18.6, apache/kafka 3.9.1 KRaft, 앱 2대 모두 relay 활성). 스택마다 새로 띄우고 스택 하나에서 `pause20` 5회(EXP-089와 같은 방식). 부하는 초당 20 workflow(v1, 1초 뒤 v2), 시행 50초, 10초 뒤 broker `docker pause` 20초, 꼬리 20초, 배출 대기 최대 90초.
- relay 설정: `RELAY_BATCH_SIZE=500`(위반이 난 설정, EXP-089·EXP-091 R2·`base`와 같음). 다른 값은 앱 기본값(send-timeout PT10S, lease PT30S, poll PT0.2S). 운영 기본 묶음(100)은 탐색 조건으로만 둔다(아래).
- BEFORE: `origin/main`(3f734147)에서 #660 커밋(d6b4d4ed)의 `atlas-application/src/main` 변경 4개 파일(`KafkaReservationEventPublisher`, `ReservationEventConfiguration`, `ReservationEventWorker`, `application.yml` 주석)만 되돌려 별도 worktree에서 `bootJar`. AFTER: `origin/main` 그대로 `bootJar`. 두 jar의 해당 클래스 존재 여부(`CHUNK_BYTES`)로 구분을 확인한다.
- 짝짓기: 블록(= 새 스택 하나) 단위로 BEFORE와 AFTER를 번갈아 돌린다. 블록 순서는 B A A B / A B B A / B A A B / A B B A / B A(18블록, 각 9개)로 시간 흐름(호스트 부하 변화)이 한쪽에 쏠리지 않게 한다.
- 시행 수: 팔마다 9블록 × 5회 = 45회(아래 근거). 호스트가 공유돼 시간 값은 맥락용이고, 판정은 개수와 불변식으로만 한다.

### 표본 크기

- BEFORE가 기준선 비율 p ≥ 6.5%에서 1건 이상 위반을 보일 확률 95% 이상이 되려면 n ≥ ln(0.05)/ln(1−0.065) = 44.6이라 45회다(p = 6.9%이면 42회, 7.7%이면 38회).
- AFTER 0/45의 95% 상한은 rule of three로 3/45 = 6.7%다. AFTER가 BEFORE와 같은 6.9%라면 0/45가 나올 확률은 (1−0.069)^45 = 4.0%다. 이것이 H1의 "차이" 근거이고(정확 이항, 기준선 p는 BEFORE 관측값과 EXP-091 합산값을 둘 다 쓴다), 두 팔을 직접 Fisher 검정으로 구별하려면 팔마다 약 140회(기준선 6.5%, 검정력 95%, 한쪽 α = 0.05)가 필요해 이 호스트에서는 하지 않는다. 45회 설계의 Fisher 검정력은 약 17%라는 점을 결과에 그대로 쓴다.
- 중단 규칙: BEFORE가 0/45이면 위 H2대로 BEFORE와 AFTER를 3블록(15회)씩 더 돌려 n = 60에서 멈춘다(0/60이면 상한 5%, p = 6.5%에서 0/60일 확률 1.8%).
- 시행은 같은 스택 안에서 순서대로 돌므로 독립 단위는 시행과 스택 사이 어딘가다. 스택(블록) 단위 집계도 같이 적는다.

### 대조군

- 무변경 대조군: 두 빌드 각각 `control`(장애 없음) 3회 이상에서 위반·누락·중복이 0.
- 음성 대조군(H3): (a) 감사 함수 합성 입력(역행·정상 재전달·정상), (b) 실제 broker에 v2 다음 v1을 같은 키로 직접 싣고 `outage_audit.py segments`로 분류한 결과가 위반으로 나옴.
- 양성 대조군: BEFORE 팔 자체(H2). BEFORE가 울린 뒤에야 AFTER의 0이 의미를 가진다.
- 같은 환경 전후: 같은 호스트, 같은 compose, 같은 이미지, 블록 단위로 교차.

### 처리량·지연(H5, 맥락용)

- 같은 부하를 두 빌드에 준다. 포화 부하는 시험 시행으로 relay가 따라잡지 못하는 `RATE`를 고르고(둘에 같은 값), 판정 지표는 `control` 조건에서 `samples.csv`의 cursor 기울기(부하 구간 이벤트/초)다. 블록 3개씩(빌드당 9회)을 번갈아 돌린다.
- 정상 부하(초당 20 workflow)에서는 `lag`(이벤트) 중앙값·95퍼센타일과 장애 후 배출 시간을 두 빌드에서 비교한다.
- 호스트가 공유돼 시간 값은 맥락용이다. 비율 0.8을 결정 기준으로 쓰되 분산이 크면(변동 계수 > 20%) 결론을 "측정 불가"로 둔다.

### 한 batch 안 10건(H6)

- 1단계(원자료, 저장소 밖에 보관된 EXP-091 시행; **이 분석은 이 사전 등록을 커밋하기 전에 이미 실행했다**(탐색). 따라서 H6는 1단계에서 확증이 아니라 탐색이고, 사전 등록의 검증력은 2단계에만 있다): 10건 각각의 batch를 broker 세그먼트 덤프와 토픽 판독의 결합으로 eventSeq 단조 구간으로 분해하고 relay 시도 기록과 대조한다.
- 2단계(실제 스택): `SEGMENTS=1`로 모든 `pause20` 시행에서 batch 단위 분류(위반이 batch 안/같은 epoch 뒤 batch/뒤 epoch에 있는가, batch 안 eventSeq 감소 수)를 얻어 BEFORE와 AFTER를 비교한다. 이 분류는 하네스에 부가한 `outage_audit.py segments`(재사용 가능)로 얻는다.
- 층: relay 시도 기록 → 세그먼트 덤프(producer id, epoch, sequence) 까지 간다. 그 아래(tcpdump, producer 내부 로그)는 증거가 가리킬 때만 간다.

### 탐색 조건(결정에 쓰지 않음)

- 운영 기본 묶음(`RELAY_BATCH_SIZE` 미지정, 100)에서 BEFORE·AFTER `pause20`을 블록 3개씩(각 15회) 추가로 돌려 개수만 적는다. 0건이면 3/15 = 20% 상한이라 아무것도 증명하지 못한다고 그대로 쓴다.

## 사전 위험과 중단 조건

| 위험 | 대응 |
| --- | --- |
| BEFORE도 0건이라 AFTER의 0이 무의미해짐 | 위 중단 규칙(n = 60까지). 그래도 0이면 "미재현"으로 끝내고 H1을 판정하지 않는다 |
| 실제 스택에서 BEFORE 비율이 6.9%보다 훨씬 높아 한 번의 우연한 큰 값에 끌려감 | 시행 단위와 블록 단위 집계를 함께 적고, 두 빌드의 블록 순서를 교차했으므로 순서 효과는 상쇄 |
| Docker 호스트를 형제 작업이 함께 쓴다 | 컨테이너는 라벨 `atlasexp=p4`로만 만들고 한 번에 스택 하나(컨테이너 4개)만 띄운다. 끝에 라벨로 0개를 확인한다. 시작·종료 시 `uptime`을 남긴다 |
| 앱이 시작하지 못함(jar 경로가 VM 밖) | 연기 시험(통제 1회)에서 확인했다. 문제가 있으면 첫 블록을 버린다 |
| 시행이 도중 실패(스택·감사) | 실패한 시행은 표에 "무효"로 세고 분모에서 빼지 않고 사유를 적는다. 재시도로 대체하지 않는다 |

## 계획한 대안 비교(결과 절에서 채움)

| 대안 | 비용 | 위험 |
| --- | --- | --- |
| 아무것도 안 하고 재현기 결과만 믿음 | 0 | 실제 앱·lease 조합의 반례를 놓침 |
| 실제 스택 팔마다 45회(이 실험) | 이 호스트에서 약 2.5시간 | 팔 간 직접 검정은 검정력 약 17% |
| 팔마다 140회(Fisher 95% 검정력) | 약 8시간 | 공유 호스트에서 비현실적 |

## 계획과 달라진 점 (정직하게)

- 계획: 팔당 45회(블록 9개). 실제: **팔당 35회(팔마다 블록 7개)**. 블록 순서 B A A B A B B A B A A B A B(앞 14블록, 팔마다 7블록)까지 끝났고 15번째(BEFORE)를 시작했으나 호스트 부하(load average 100~200, 형제 작업)로 블록당 시간이 7분에서 14분으로 늘고 요청 예산이 소진돼 중단했다. 중단한 15번째 블록은 결과에 넣지 않았다(미완 원자료는 로컬에만 둠). 중단 결정은 BEFORE 결과를 본 뒤에 했지만 어느 쪽 결과가 나오든 같은 이유였고, 중단 시점의 BEFORE는 2건 위반이라 H2 중단 규칙(0건이면 연장)이 발동할 상황이 아니었다.
- 이 때문에 H1의 95% 상한은 3/45 = 6.7%가 아니라 **3/35 = 8.6%**다.
- 연기 시험(통제 1회, 분류 시험 2회)은 표본에 넣지 않았다. 포화 부하 처리량(H5의 본 측정), 운영 기본 묶음(100) 탐색 조건, `control` 장애 없음 반복은 이 실험의 첫 실행에서는 하지 않았고, 같은 날 아래 "EXP-105 종결 결과"에서 실행했다.

## 결과

모든 수치는 로컬 단일 Docker 호스트(Colima 8 CPU, 다른 작업이 동시에 돌아 load average 최대 약 200)의 결과다. 시간 값은 맥락이고 판정은 개수와 불변식으로 한다. 용어: 시행 = 한 스택 안의 `pause20` 한 번(장애 중에 relay가 실제로 시도하고 실패함), 블록 = 새로 띄운 스택 하나(시행 5회).

### 1. 첫 전달 역행(H1, H2)

| 팔 | 시행 | 블록 | 역행이 있는 시행 | 역행 키(합) | 누락 | `passed` | 첫 시행(스택에서 처음)의 역행 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BEFORE(#660 이전 코드) | 35 | 7 | **2 (5.7%)** | 40 | 0 | 33/35 (실패 2건은 위 역행 때문) | 2/7 |
| AFTER(main) | 35 | 7 | **0** (95% 상한 3/35 = 8.6%) | 0 | 0 | 35/35 | 0/7 |

- 두 팔의 시행 번호별 합은 첫 시행만 BEFORE 2/7이고 나머지(2~5번) BEFORE 0/28, AFTER 0/35였다(탐색, 사후 분할이라 확증 아님: Fisher 정확 검정 첫 시행 2/7 대 나머지 0/28, p = 0.035, 보정 없음). EXP-091의 캠페인 2(첫 9/180 대 이후 32/360, p=0.1225)와 캠페인 3 base(첫 0/48 대 이후 11/96, p=0.0161, 반대 방향)도 첫-run 우위를 재현하지 않았다. 결정: cold producer/“첫 실행만”을 일반 원인으로 채택하지 않고 워밍업을 추가하지 않는다. 대안은 새 스택별 첫·둘째 `pause20` 짝비교다. 재검토 조건: fresh stack 20개 이상에서 first-run 위반률이 later-run보다 높고 사전등록 Fisher p<0.05이면 재평가한다.
- BEFORE 비율의 점 추정 5.7%는 EXP-091의 6.5~7.6%와 같은 범위다(정확 이항 95% 구간은 0.7~19%로 매우 넓다). H2 충족: BEFORE에서 위반을 봤으므로 검출기가 이 스택에서 울릴 수 있다.
- **실제 스택 단독 비교는 측정상 null**: BEFORE 2/35 대 AFTER 0/35, 양측 Fisher p=0.49. AFTER 0/35 rule-of-three 95% 상한은 8.6%이고, BEFORE의 5.7% 점추정과 구분하지 못한다. EXP-091 재현기 pooled 결과(수정 전 13/179 대 후 0/179, p=0.0002)와 방향은 일치하지만 환경을 섞은 자료가 실제 스택 단독 p값을 대체하지 않는다. 정확 양측 Fisher에서 AFTER가 실제 0이고 기준선이 p=6.5~6.9%일 때 약 150~159회/팔이 95% 검정력이며, 관측 p=5.7%를 쓰면 약 182회/팔이 필요하다. 결정: 실제 스택 효과는 방향 보강 증거로만 두고 “확인”이라 단정하지 않는다. 재검토 조건: 같은 stack/조건에서 BEFORE·AFTER 각 n≥150(기준선 6.9%) 또는 n≥182(관측 5.7%)를 교차·독립 stack으로 채운다.
- 두 팔의 장애 중 relay 실패 시도와 epoch 증가는 AFTER에서도 일어났다(producer가 시도마다 epoch를 올림, 세그먼트 분류 기록에서 AFTER 시행도 producerId·epoch 2~6). 수정은 epoch 증가를 막는 것이 아니라 증가가 순서 역행으로 이어지지 않게 한다.

### 2. 역행 구간의 batch 분류(`SEGMENTS=1`, 세그먼트 덤프)

| 팔 | 분류한 레코드 | batch를 못 찾은 레코드 | 역행에서 낮은 버전이 같은 batch | 다른 producer의 batch | 같은 producer 같은 epoch의 뒤 batch | 같은 producer 뒤 epoch | batch 안 eventSeq 감소 | batch 안 키 역행 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BEFORE | 76,539 | 0 | 0 | 20 | 20 | 0 | 0 | 0 |
| AFTER | 74,807 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

역행 두 시행 중 하나(블록 06)는 20키 전부 같은 producer의 뒤 batch(replica 한 대 안), 다른 하나(블록 07)는 20키 전부 다른 producer(두 replica 사이)에서 나왔다. 둘 다 토픽 판독의 단조 구간이 파티션마다 3개(앞 구간, 첫 실패 시도의 일부가 기록된 구간, 뒤 구간)였다. 이는 EXP-091의 메커니즘(실패한 시도 안에서 앞뒤만 기록되고 재시도가 중간을 다시 보냄)과 같은 모양이고, 두 replica가 서로 겹쳐서 생긴다는 HC는 계속 기각되며 replica 사이에서도 일어난다는 것을 보탠다.

### 3. 불변식(H4)

| 팔 | 누락 | 예상 밖 메시지 | 장애 중 cursor 이동 | 제한 시간 안 배출 | 중복 중앙값(시행당 이벤트) | 배출 시간 중앙값(복구 뒤, 초) |
| --- | --- | --- | --- | --- | --- | --- |
| BEFORE | 0 | 0 | 0 | 35/35 | 232 | 0.8 |
| AFTER | 0 | 0 | 0 | 35/35 | 164 | 0.9 |

- 중복은 AFTER 중앙값 164 대 BEFORE 232였지만 시행 순서와 host load가 섞여 있고 유의성 검정은 등록하지 않았다. 결정: 중복 감소 효과는 범위 밖이며 코드 이득으로 주장하지 않는다. 대안은 사전등록된 같은 부하·교차 stack 비교다. 재검토 조건: 운영 의사결정이 중복량에 의존하거나 동일 조건에서 n≥20/arm을 확보할 때 비교한다. `passed`는 역행 2건을 제외하고 모두 참이며 두 빌드의 EXP-089 불변식 위반은 0이다.

### 4. 처리량·지연(H5, 일반 부하 및 포화 backlog drain)

| 팔 | 시행 | 부하 구간 relay cursor 기울기 중앙값(이벤트/초) | 최소~최대 | 생산 속도 중앙값 | lag 중앙값(이벤트) | lag 95퍼센타일의 중앙값 | lag 최댓값 | 부하 종료 뒤 lag 0까지 중앙값(초) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BEFORE | 35 | 39.9 | 38.3~43.8 | 39.8 | 8 | 692 | 1,296 | 0.27 |
| AFTER | 35 | 39.9 | 21.3~45.2 | 39.8 | 8 | 696 | 1,629 | 0.28 |

초당 40 event 부하(운영 SLO 대응은 측정하지 않아 가정하지 않음)에서 relay는 부하를 그대로 따라가고(기울기 = 생산 속도) 장애 뒤 밀린 이벤트를 1초 안에 비운다. 두 빌드의 값은 같다. **포화 backlog-drain 처리량은 아래 "EXP-105 종결 결과"의 G14에서 측정했다(AFTER/BEFORE 중앙값 비 1.00, 구간이 넓어 0.8 미만을 배제하지 못함).** 정상 부하의 같음은 "덩어리 왕복이 정상 부하에서 병목이 아니다"만 말한다.

### 5. 한 batch 안 10건 (H6)

방법: 보관된 EXP-091 원자료(시행마다 세그먼트 덤프와 토픽 판독)를 batch 단위로 다시 결합해 batch 안 이웃 레코드의 `eventSeq` 감소를 센다(분석 스크립트는 저장소에 올리지 않음, 아래 "삭제한 파일의 내용"). **이 분석은 사전 등록 커밋보다 먼저 실행한 탐색이다.**

- 보관 archive는 2,788,082 records와 468,193 broker batches다. batch 내 이웃 `eventSeq` 감소는 NEG 제외 정확히 10건(`R1` 4, `R2` 4, `R2mif1` 2), 모두 `c2A3`다. **정정:** EXP-091의 `batch 260,925개`는 전체 archive 분모가 아니라 사전 수정 여섯 mode 합계다: R1 36,565 + R2 85,194 + R2mif1 45,530 + R2chunk 38,343 + R2reset 37,810 + R2b100 17,483 = 260,925; 따라서 10/260,925 = 0.00383%. 이 10건 수는 full archive 재검산으로 재현됐다.
- 10건 모두 한 batch가 `eventSeq` 단조 증가 구간 **둘**로 이뤄졌고(앞 구간은 relay 시도의 꼬리, 뒤 구간은 다음 시도의 머리에서 시작, 예: 구간 (2694~2751, 11개) 뒤에 (2260~2334, 31개)), 같은 시행의 시도 기록에서 해당 `eventSeq`들은 모두 연속한 **실패한 시도 안**이었다(앞 시도의 레코드가 producer 안에 남아 있는 상태에서 다음 시도가 같은 batch에 이어 붙음). 10건 중 키 하나에서 높은 버전이 낮은 버전 앞에 오는 batch 안 역행은 1건이고 나머지 9건은 서로 다른 키라 첫 전달 위반이 아니다.
- 실제 broker dump는 10건 모두 한 batch의 두 개 monotonic eventSeq 구간과 앞 시도 꼬리+다음 시도 머리의 관계를 보여준다. 결정: producer `RecordAccumulator` 내부에서 batch가 합쳐지는 순간의 thread trace는 현재 원인 결정에 불필요하므로 범위 밖이다. 대안은 debug/JFR 계측이다. 재검토 조건: 미래 dump에서 두 구간·attempt 관계가 깨지는 감소 1건이 관찰되면 내부 trace를 추가한다.

### 6. 음성 대조군(H3)과 되돌리기 시험

| 대조 | 입력 | 결과 |
| --- | --- | --- |
| 감사 함수 합성 | 키별 `[2,1]`, `[1,2]`, `[1,2,1,2]`, `[2,1,2,1]` | 첫 전달 위반 2건, 재전달 4건(기대와 같음) |
| 실제 broker 직접 주입 | 같은 batch 역행 키 5개, 다른 batch 역행 키 5개, 정상 키 5개, 서로 다른 키의 `eventSeq` 감소 1건 | `segments`가 위반 10건(같은 batch 5, 같은 epoch 뒤 batch 5), batch 안 키 역행 5건, `eventSeq` 감소 1건. 정상 키는 0 |
| 장애 없음 `control` | 연기 시험 AFTER n=1 뒤, 아래 G14b에서 BEFORE·AFTER 각 n=5 | 실행함: 10/10 누락 0·중복 0·역행 0·`passed` 10/10 |
| 되돌리기 시험 | `origin/main`에서 `git revert d6b4d4ed`(충돌은 `decision-log.md`뿐, 문서)로 별도 worktree에서 `bootJar` 후 클래스 디렉터리를 수동 되돌린 BEFORE jar와 비교 | `com/atlas/application`과 `application.yml` 차이 없음(`diff -rq` 무출력). revert 한 번으로 BEFORE와 같은 jar가 된다 |

되돌리기 jar로 `pause20`을 돌리지는 않았다(위 비교로 BEFORE와 동일함을 확인했고 BEFORE는 실측함).

### 7. 상수 결정(H7)

- 실제 AFTER 스택의 레코드 크기: payload 값 285~289바이트(표본 400건 중 288 183, 287 166, 289 33, 286 14, 285 4), key 36바이트. batch 평균 약 397바이트/레코드(전 batch 2,057개 8,316건 평균, 헤더 포함). 코드의 추정은 payload 길이 + 128 = 약 417바이트라 실제(약 397)보다 5% 크다: 추정이 안전 쪽이다.
더 작거나 큰 덩어리의 처리량 비용은 이 비교에서 측정하지 않았다. 결정: 64KB/128KB 상수를 유지한다(실제 레코드 추정 417B가 관측 397B보다 5% 여유, 실제 AFTER 불변식 위반 0). 대안은 여러 chunk/batch 크기 sweep이며 현재 목적은 순서 회귀라 비용·호스트를 통제하는 추가 축을 넣지 않았다. 재검토 조건: 포화 처리량 AFTER/BEFORE ratio <0.8 또는 운영 처리량의 20% 이상 저하가 확인되면 chunk-size sweep을 실행한다.

## 대안 비교

| 대안 | 결과 | 결정 |
| --- | --- | --- |
| 아무것도 안 함(재현기 결과만) | 실제 스택 AFTER 0/35, BEFORE 2/35: 효과는 부정되지 않았으나 단독 증거는 약함 | 이 실험으로 보강, 코드는 그대로 |
| 덩어리 상수 조정 | H7 추정 417B 대 실측 397B; G14 포화 비 1.00 | 결정: 현재 64KB/128KB 유지. 다른 덩어리 크기의 처리량 비용은 측정하지 않는다(결정, 아래 G14 재검토 조건) |
| 팔당 140회로 Fisher 검정력 확보 | 이 호스트에서 비현실적 | 하지 않음, 한계로 기록 |

## 사전 부검과 되돌리기

EXP-100 포함 변경 묶음의 revert/restore drill을 별도 clean scratch worktree에서 실행했다: `git revert --no-commit e0a2660a`는 깨끗하게 적용됐고, 6개 touched paths가 parent commit과 일치(신규 EXP-100 삭제 포함), `bash -n` 통과, 이후 `git reset --hard HEAD`로 복원해 `git diff --quiet origin/main` 통과했다. 하네스 매개변수 변경(`NETEM_JITTER_MS`, `FAULT_LENGTH`, `SAMPLE_INTERVAL`)의 restore/cmp drill은 아래 "EXP-105 종결 결과"에 기록했다.

## 삭제한 파일의 내용

원자료(시행별 `result.json`, `segments.json`, `topic-run.tsv`, `samples.csv`, `segment-records.tsv`, 앱 로그)와 구동·분석 스크립트(블록 교차 구동, 집계, 처리량, 보관 원자료 재분석, 음성 대조군 주입)는 저장소에 올리지 않았다(`/tmp`와 홈 작업 영역에만 있음). 재현은 `run-kafka-outage.sh`와 `outage_audit.py`의 매개변수로 한다.

- 한 블록: `SEGMENTS=1 APP_JAR=<jar> CONDITIONS=pause20 RUNS=5 RELAY_BATCH_SIZE=500 TAG=p4 OUT=<dir> load-tests/kafka-outage/run-kafka-outage.sh`(스택 하나 새로 띄움). 블록 순서는 BEFORE·AFTER를 B A A B A B B A B A A B A B로 교차했다. BEFORE jar는 `origin/main`에서 `git show d6b4d4ed -- atlas-application/src/main`을 `git apply -R`로 되돌려 `:atlas-application:bootJar`, AFTER jar는 `origin/main` 그대로.
- 집계 열: 시행의 `first_delivery_violations`(역행 키 수, 위반 시행 = 1 이상), `lost`, `passed`, 처리량은 `samples.csv`(1초 간격 `max_seq`, `cursor_seq`, `lag`)에서 부하 시작 5초 뒤부터 부하 종료까지 `cursor_seq`의 증가량÷시간.
- 분류: `outage_audit.py segments`가 `kafka-dump-log.sh --print-data-log`로 batch(baseOffset, count, producerId, epoch)와 레코드 sequence를 읽고 토픽 판독 TSV와 offset으로 결합한다.
- 보관 원자료 재분석(H6): 라운드마다 `dumplog.txt`의 batch 범위와 `*.topic.tsv`를 결합해 같은 batch 안 이웃 레코드의 `eventSeq` 감소를 센다. 출력 요약: 라운드별 레코드 수 `c2A1` 232,701 · `c2A2` 236,770 · `c2A3` 301,061 · `c2B1~3` 234,715 · 245,256 · 236,421 · `c3` 8 라운드 75,958~163,747 · `main1~3` 127,080~131,290 · `ctl1` 24,000 · `rsmoke` 4,326, 모드별 감소 `R1` 4 · `R2` 4 · `R2mif1` 2(모두 `c2A3`).
- 10건 분해(표본): `R1-t13` 구간 (2694~2751, 11개)+(2260~2334, 31개), `R1-t20` (2268~2342, 24개)+(2268~2269, 2개), `R2-t10` 파티션 0 (3652~3752, 32개)+(3256~3267, 4개) 등 모두 2구간.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 실제 앱 2대·PostgreSQL·lease 경로에서 `pause20` 35회 동안 수정 후 첫 전달 역행 0, 불변식 유지, 정상 부하 처리량·배출 시간이 수정 전과 같음. 수정 전 코드에서는 같은 하네스가 2/35 시행에서 역행을 재현함(검출기가 울림). batch 안 이탈 10건은 감사 오류가 아니라 실제 broker 로그의 현상.
- 결정: 포화 relay 처리량은 아래 G14의 backlog-drain 측정으로 종결한다(중앙값 비 1.00, 구간 넓음). 운영 기본 batch 100은 G14c에서 AFTER n=2만 실행했으므로 100 대 500의 차이 크기는 주장하지 않는다; 대안은 같은 부하의 100/500 pair n≥6. 재검토 조건: 운영 batch 기본값 또는 throughput SLO를 변경할 때. 10분 이상 outage·multi-broker·replication도 단일 broker에서 평가하지 않아 범위 밖; 재검토 조건: 운영 HA에 replication factor 3 또는 10분 outage SLO가 채택될 때. first-run pattern은 EXP-091 재현기와 본 탐색의 방향이 일관되지 않아 일반 원인으로 채택하지 않는다; 재검토 조건은 독립 fresh stack 20개 이상에서 사전등록 Fisher p<0.05. EXP-091 denominator 260,925는 여섯 mode 합계로 재현됐다.

## 주장 표

| 주장 | 증거 | 확신 |
| --- | --- | --- |
| 실제 스택에서 수정 후 `pause20` 35회 역행 0 | AFTER 0/35(상한 3/35 = 8.6%), 분류 레코드 74,807 | confirmed(로컬, n이 작음) |
| 실제 스택에서 수정 전 코드가 역행을 재현 | BEFORE 2/35(키 40개), 두 스택에서 | confirmed(로컬) |
| 실제 stack fix effect | BEFORE 2/35 vs AFTER 0/35, Fisher p=0.49; pooled EXP-091 repro 13/179 vs 0/179 p=0.0002 | 결정: 실제 stack alone measured null; rule-of-three after upper bound 8.6%, required n/arm≈150–159 for 95% power at p=6.5–6.9%, ≈182 at observed 5.7%; pooled evidence is supportive, not a substitute |
| 정상 부하에서 relay 처리량·배출 시간이 같다 | 39.9 대 39.9 이벤트/초, 배출 0.27 대 0.28초, 각 n=35 | confirmed(로컬, 맥락) |
| 포화 backlog 배출 처리량 | 같은 stack 하네스, 초당 250 workflow, BEFORE·AFTER 각 n=6(교차 블록), G14 | 측정함(로컬): 중앙값 비 1.003, 정확 Mann-Whitney p=0.94, 부트스트랩 95% 구간 0.31~2.39. 0.8 미만을 배제하지 못함(결정, G14) |
| 한 batch 안 10건은 실제 현상(앞 시도의 미취소 레코드가 다음 시도와 같은 batch) | 10건 모두 2구간·실패한 시도 안 | confirmed(보관 원자료, 사후 탐색), producer 내부 로그는 안 봄 |
| EXP-091 denominator 260,925 | six pre-fix modes sum exactly to 260,925 batches; full archive is 468,193 | reproduced for six-mode subset, not an all-archive denominator; 10/260,925=0.00383% |
| 덩어리 64KB·batch 128KB 유지 | 417B 추정 대 397B 실측; G14 포화 결과 | 결정: 상수를 유지한다. 다른 덩어리 크기의 처리량 비용은 측정하지 않으며 재검토 조건은 G14에 있다 |
| #666/EXP-100 변경 묶음 revert/restore | clean scratch에서 `git revert --no-commit e0a2660a`, 6 files reversed, 신규 EXP-100 제거, bash -n 통과, reset 후 git diff --quiet origin/main | 실행함; parameter patch rollback/cmp는 아래 종결 결과 |

### 8. 추가 종결 결정 (EXP-105 inventory)

| 항목 | 실행 결과·대안 | 종결·재검토 조건 |
| --- | --- | --- |
| 운영 batch-size 100 | 아래 G14c에서 AFTER·batch 100 포화 n=2 실행 | 실행함: 역행 0·누락 0·`passed` 2/2, 배출 처리량 중앙 3,108/s(batch 500 AFTER 중앙 4,793/s와 같은 범위). 결정(2): 100 대 500의 처리량 차이 크기는 주장하지 않는다(n=2) |
| 반복 no-fault control | 연기 AFTER n=1 뒤 위 G14b에서 BEFORE·AFTER 각 n=5 실행 | 실행함: 10/10 누락 0·중복 0·역행 0. 별도 추가 실행은 하지 않는다(결정). 재검토 조건: no-fault invariant failure 1건 또는 no-fault throughput이 운영 결정에 필요해질 때 |
| 첫 장애에서만 현상 | 최초 3/19 vs 0/71 p_hypergeom=0.00825 사후; EXP-091 campaigns 2/3 및 EXP-100 2/7 vs 0/28 방향 불일치. 이 문서 2/7 대 0/28의 Fisher p=0.0353을 다시 계산해 일치 확인(실행) | 결정: 일반 cold-producer 원인으로 채택하지 않고 warm-up 추가하지 않음. 대안: fresh-stack first/second paired `pause20`. 재검토 조건: independent stack 20개 이상에서 first rate>later, prereg Fisher p<0.05 |
| producer-internal 10건 원인 | `kafka-dump-log` batch producerId/epoch/baseSequence와 topic offsets로 10건 모두 두 monotonic segments; 1건만 same-key reversal | 결정: RecordAccumulator/JFR thread instrumentation은 broker dump가 직접 ordering evidence라 범위 밖. 대안은 internal trace. 재검토 조건: 두-segment pattern이 깨지는 `eventSeq` 감소 1건 |
| `segments` harness logic test | 실제 broker 직접 주입 음성 대조군이 분류기를 실행: 알려진 위반 10/10 검출(같은 batch 5, 뒤 batch 5), 정상 키 5/5 0건. 이후 이 PR의 포화·control·batch 100 실행 stack 시행에서도 `SEGMENTS`는 꺼 두었다(결과 형식 비교용으로만 첫 실행의 70 시행에서 사용) | 결정(2): 단위 fixture를 추가하지 않는다. 이유: 분류기의 입력은 `kafka-dump-log` 출력이라 가짜 입력을 만들려면 broker가 필요하고, 위 직접 주입이 그 경로 전체를 이미 검증한다. 대안: dump-log 출력 샘플을 고정한 파이썬 단위 시험. 재검토 조건: 직접 주입이 10/10 미만이 되거나 정상 키 오탐 1건, 또는 `segments` 분류와 토픽 감사 사이 불일치 1건 |
| PR/revert 및 harness rollback | #666 revert drill above; parameter patch rollback/cmp/default check to be recorded below | 결정: docs+harness-only rollback; exact restore evidence below |

## EXP-105 종결 결과

이 절은 EXP-100이 "측정하지 않았다"로 남긴 항목을 같은 하네스(`load-tests/kafka-outage/`)로 실행해 닫은 결과다. 본문의 35+35 시행 값은 바꾸지 않는다. 도구 설치 0건(이미 있는 Docker Compose 스택과 파이썬 표준 라이브러리만 사용). 원자료는 저장소에 올리지 않았다.

| 항목 | 유형 | 한 줄 결과 |
| --- | --- | --- |
| G14 포화 처리량(AFTER/BEFORE) | 실행함 후 결정(1+2) | 중앙값 비 1.003, 구간 0.31~2.39: 0.8 미만을 배제하지 못함 |
| G14b 반복 무장애 `control` | 실행함(1) | BEFORE·AFTER 각 5/5: 누락 0·중복 0·역행 0 |
| G14c 운영 기본 batch 100 | 실행함(1)+결정(2) | AFTER n=2 역행 0, 처리량 차이 크기 주장 안 함 |
| 첫 시행 쏠림(G16 연결) | 결정(2) | 본문 결정 유지, 새 근거 없음 |
| producer 내부 원인 10건 | 결정(2) | 본문 5절 유지 |
| `segments` 자체 시험 | 결정(2) | 위 8절 표 |
| 하네스 매개변수 되돌리기 | 실행함(1) | restore `cmp` 일치 |

### G14: 포화 backlog 배출 처리량

방법: 하네스에 `FAULT_LENGTH`(조건 공통 장애 길이)와 `SAMPLE_INTERVAL`(표본 간격) 매개변수를 더했다(기본은 원래 동작). 조건 `pause45`, `FAULT_LENGTH=120`(120초 동안 broker `docker pause`), 초당 250 workflow(이벤트 500/s, 시행당 75,000 이벤트), `SAMPLE_INTERVAL=0.25`, `RELAY_BATCH_SIZE=500`, 스택당 2시행, 블록 순서 B A A B(첫 캠페인) 뒤 보충 A B A. 첫 캠페인의 A3 스택은 앱이 3분 안에 healthy가 되지 않아(`application not healthy after 3m0s`, compose 기동 실패 1건) 시행이 없고, 그 자리를 보충 블록으로 메웠다. 결과 지표: 복구 시점 backlog(lag 약 37,000~64,500 이벤트)에서 복구 뒤 cursor가 처음 움직인 때부터 lag가 2초분(생산 속도의 2배) 이하가 될 때까지의 평균 처리량(`samples.csv`의 `cursor_seq` 증가÷시간, 생산분 포함). BEFORE jar는 #660의 main 변경을 되돌린 것, AFTER는 main이다(본문과 같음).

| 팔 | 시행 | 처리량 이벤트/s(시행별, 블록 순) | 중앙값 | 최소~최대 | 중복 중앙값 | 첫 전달 역행 |
| --- | --- | --- | --- | --- | --- | --- |
| BEFORE | 6 | 2,816, 1,653, 4,154, 5,406, 7,509, 18,500 | 4,780 | 1,653~18,500 | 284 | 0 |
| AFTER | 6 | 2,035, 2,995, 6,882, 2,666, 8,103, 6,590 | 4,793 | 2,035~8,103 | 206 | 0 |

판정(H5, 사전 등록 기준 AFTER 중앙값 < 0.8 x BEFORE 중앙값이면 반증): 중앙값 비 4,793/4,780 = 1.003이라 반증되지 않았다. 그러나 시행 간 변동이 10배 이상(공유 호스트, 블록 첫 시행 시작 시점 load1 8~32)이라 정확 Mann-Whitney(양측) p = 0.94, 중앙값 비의 부트스트랩 95% 구간(20,000회, 팔 내 복원 추출) 0.31~2.39이고 0.8 미만일 확률은 0.38이다. 민감도: 생산기 틱 지연 1초 초과 시행을 빼면 5,406 대 6,736(비 1.25), 읽기 미완료 2시행을 빼면 4,154 대 6,590(비 1.59). 어느 제외 기준에서도 0.8 아래로 내려가지 않지만 구간이 넓다. 이 값은 속도가 아니라 방향의 맥락 자료이고 결론은 개수로 낸다: `passed`는 12시행 중 10건이다. 실패 2시행(`A4` 2번째, `A5` 2번째)은 `topic_read_complete=false`(감사 도구의 토픽 읽기 90초 제한에 걸림)로 `lost` 11,514와 25,993이 나왔고, 같은 시행의 `final_cursor == final_max_seq`, `final_lag = 0`, DB 이벤트 75,000이다. 즉 감사의 읽기가 끝나기 전에 집계한 도구 한계이며 relay가 이벤트를 잃은 것이 아니다(BEFORE 1건, AFTER 1건). 이 두 시행은 처리량 표에 포함했고 위 민감도에서 따로 뺐다.

결정(유형 2): 덩어리 전송이 포화 처리량을 해치지 않는다는 방향은 보이나(비 1.00, 두 제외 기준에서 1.25·1.59) 이 호스트의 변동으로 0.8 미만을 통계적으로 배제하지는 못한다. 따라서 "해치지 않는다"를 확인으로 쓰지 않고 "측정한 12시행에서 0.8 미만 신호 없음"으로 쓴다. 64KB/128KB 상수는 유지한다. 다른 덩어리 크기의 처리량 비용(상수 sweep)은 측정하지 않는다. 이유: 비 1.00에서 상수를 바꿔 얻을 이득이 없고 변동이 큰 호스트에서 sweep은 구별력이 없다. 대안: 부하가 낮은 전용 호스트에서 BEFORE/AFTER 각 n≥20 교차. 재검토 조건: 부하가 낮은 호스트에서 AFTER/BEFORE 중앙값 비가 0.8 미만이거나, 운영 relay 처리량이 배포 전보다 20% 넘게 떨어질 때.

### G14b: 반복 장애 없음 `control`

`RATE=20 CONDITIONS=control RUNS=5`, 새 스택에서 BEFORE jar와 AFTER jar 각 5회(시행당 이벤트 2,000, 배출 대기 90초).

| 팔 | 시행 | 누락 | 중복 | 첫 전달 역행 | `passed` | 배출 시간(복구 뒤 초) 중앙값(최대) |
| --- | --- | --- | --- | --- | --- | --- |
| BEFORE | 5 | 0 | 0 | 0 | 5/5 | 1.9 (23.7) |
| AFTER | 5 | 0 | 0 | 0 | 5/5 | 1.6 (2.6) |

장애가 없으면 두 빌드 모두 누락·중복·역행이 없고 `passed`다. BEFORE 시행 하나의 배출 23.7초는 같은 호스트의 부하 때문으로 보이며(시행 순서·load 외에는 기록이 없다, 원인은 주장하지 않는다) 판정과 무관하다.

### G14c: 운영 기본 batch 100

`RELAY_BATCH_SIZE=100`(환경 변수 적용은 스택의 `relay-env.txt`에서 `...RELAY_BATCH_SIZE=100`으로 확인), 나머지는 G14와 같다. AFTER 2시행: 처리량 3,793, 2,422 이벤트/s(중앙 3,108), 중복 200·197, 역행 0, 누락 0, `passed` 2/2. batch 500 AFTER 중앙값 4,793/s와 같은 범위이나 n=2라 100 대 500의 차이 크기는 주장하지 않는다(결정, 재검토 조건: 운영 기본 batch를 바꾸거나 처리량 SLO를 정할 때 같은 부하에서 100/500 각 n≥6 교차).

### 되돌리기 드릴(하네스 매개변수)

`git diff HEAD -- load-tests/kafka-outage/run-kafka-outage.sh`로 얻은 패치를 임시 복사본에서 `git apply --reverse`한 결과가 `HEAD`의 파일과 `cmp` 일치, 다시 적용한 결과가 현재 파일과 `cmp` 일치했다(2/2). `bash -n`은 통과한다. 기본값 동일성: `NETEM_JITTER_MS` 미지정이면 netem 명령이 변경 전과 같은 `delay 2000ms 500ms`로 전개된다(문자열 전개 확인).
