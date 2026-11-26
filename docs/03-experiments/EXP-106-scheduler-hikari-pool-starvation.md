# EXP-106: 요청 과부하에서 복구 worker가 Hikari 풀을 두고 굶는가

- 상태: ✓ 완료(사전 등록은 커밋 `8cd73168`, 측정은 그 뒤). 아래 "실행 편차" 이후가 실행 뒤에 채운 절이다.
- 날짜: 2026-11-26
- 관련: [#693](https://github.com/Neo1228/ATLAS/issues/693), [EXP-104](EXP-104-fault-injection-closeout.md)

## 문제 한 문장

앱에는 스케줄러 스레드가 10개(`atlas-scheduler` 4, `atlas-payment-recovery` 1, `atlas-order-inventory-recovery` 3, `atlas-paypal-webhook-retry` 1, `atlas-order-inventory-event-relay` 1)와 Tomcat 최대 40 스레드가 있고 모두 Hikari 풀 10개(`connection-timeout` 500 ms)를 나눠 쓴다. 요청이 풀 용량을 넘게 몰릴 때 (1) 복구 worker의 `getConnection`이 시간 초과로 실패해 결제·예약 복구가 진행을 멈추는지, (2) worker가 요청 성공률을 떨어뜨리는지, (3) 측정 가능한 영향이 없는지를 실제 앱과 PostgreSQL로 센다.

## 배경 값 (실행 전에 코드에서 읽은 것)

- 풀 크기: `spring.datasource.hikari.maximum-pool-size` 미설정이므로 Hikari 기본 10(`ConnectionPoolBudgetHealthIndicator.HIKARI_DEFAULT_POOL_SIZE`). `connection-timeout` 500 ms(`ATLAS_DB_CONNECTION_TIMEOUT_MS`), Tomcat 최대 40, accept-count 100(`application.yml`).
- worker 풀(`SchedulingConfiguration`, `ReservationEventConfiguration`): 4 + 1 + 3 + 1 + 1 = 10 스레드. 모든 worker는 `RuntimeException`을 잡아 실패 counter를 올리고 다음 주기를 기다린다(예외로 스케줄이 죽지 않음).
- 끄는 속성: `atlas.payment.recovery.worker.enabled`, `atlas.order-inventory-reservation.recovery.worker-enabled`(3 worker), `atlas.payment.paypal.webhook-retry.worker.enabled`, `atlas.order-inventory-reservation.event-relay.enabled`(기본 꺼짐, 켜면 Kafka 생산자 설정이 필요하지만 보낼 이벤트가 없으면 DB만 읽음), 만료 sweep 3개는 끄는 속성이 없어 `atlas.admission.expiry-poll-interval`·`atlas.orders.expiry-poll-interval`을 `PT24H`로 늘려 끈다.
- 주기: 결제 복구·예약 복구 3개·webhook 재시도·만료 sweep 3개는 `fixedDelay` PT1S, event relay는 PT0.2S.

## 측정 방법 (사전 등록)

- 대상: 이 저장소의 `atlas-application` bootJar를 별도 JVM(JDK 25, `-Xms1g -Xmx1g`)으로 띄우고 PostgreSQL 18.6 컨테이너(`max_connections=200`)에 붙인다. 카탈로그 시드는 `load-tests/seed-catalog-read.sql`(sellers=100, products_per_seller=400: 상품 40,000, SKU 80,000). 요청은 인증이 필요 없는 가장 무거운 읽기인 `GET /api/products?minPriceWon=1500&maxPriceWon=4000&limit=100`(응답 약 88 KB)이다. 예비 측정에서 이 요청은 k6 40 VU 닫힌 루프에서 약 73 req/s에 요청의 약 18%가 503(풀 대기 초과)이었다. 호스트가 공유돼 용량은 흔들리므로 수준은 고정 도착률로 정의한다.
- 수준: 낮음 20 req/s, 용량 부근 60 req/s, 크게 초과 200 req/s(k6 `constant-arrival-rate`, 30초). worker 켬 조건에만 부하 없음(idle) 30초 창을 더한다.
- 조건: worker 켬(모든 worker와 event relay 켬) 대 끔(위 속성으로 모두 끔). 반복 5회, 반복마다 조건 순서를 번갈아 새 JVM으로 시작하고 10초 워밍업(20 req/s)을 버린다.
- 표집: 창마다 1 Hz로 `/actuator/prometheus?includedNames=…`(Hikari active·idle·pending·timeout, `tasks.scheduled.execution`의 `code.namespace`별 횟수, worker별 실패 counter)를 읽고, 2초마다 `jcmd <pid> Thread.print`를 떠서 스레드를 이름 접두사별로 세고 스택에 `HikariPool.getConnection`이 있는 스레드를 센다.
- 정의: 성공 반복 = `tasks.scheduled.execution` 횟수 − worker 실패 counter. 정지 구간 = 성공 반복 사이 간격이 3초를 넘은 구간, 정지 초 = 그 간격의 합(1초 해상도). 요청 결과는 k6 counter로 2xx / 503 / 그 밖의 5xx / reset(`error_code` 12xx) / 시간 초과 / 그 밖으로 센다.
- 음성 대조군(탐지기가 우는가): 다른 세션에서 `product` 테이블에 `ACCESS EXCLUSIVE` 잠금을 25초 잡고 같은 요청을 흘리면 요청 10개가 연결을 쥔 채 잠금을 기다려 풀이 고갈된다. 이때 dump에서 `HikariPool.getConnection`에 막힌 스레드와 timeout counter가 보여야 한다.
- 교란 요인: 이 호스트에서 다른 에이전트(S4)가 컨테이너 장애 실험을 같이 돌린다. 창마다 `uptime` 부하 평균을 기록하고, 시간 값은 맥락으로만 쓰고 결론은 개수와 불변식으로 낸다. 표집(스크레이프와 `jcmd`의 safepoint)과 k6 자체도 같은 호스트의 부하다.

## 가설과 반증 조건 (실행 전 등록)

| # | 가설 | 반증 조건 | 지표 | 결정 |
|---|---|---|---|---|
| H1 | 낮음(20 req/s)과 idle에서 worker는 굶지 않는다: 켬 조건 낮음 5개 창에서 worker 실패 0건, 2xx 비율 99% 이상, 정지 초 0 | 낮음 창 5개 중 2개 이상에서 worker 실패 1건 이상 또는 정지 초 > 0 | worker별 실패 counter, 정지 초, k6 2xx | 반증이면 기준선이 이미 불안정하므로 아래 가설의 해석에 단서를 단다 |
| H2 | worker는 0으로 굶지 않는다(굶음 결함 없음): 크게 초과(200 req/s) 켬 조건 5개 창 모두에서 모든 worker의 성공 반복이 1건 이상이고, 요청 2xx가 계속되는 동안 10초 이상 이어지는 정지 구간이 없다. 기대 근거(검증 대상이지 확인된 사실이 아님): Hikari는 대기 스레드를 공정 모드 `SynchronousQueue`로 줄 세워 worker와 요청이 같은 순서로 연결을 받을 것이라는 추정이다. 유휴 연결을 직접 훔치는 경로가 있어 엄밀한 FIFO는 아니다 | 창 하나라도 어떤 worker의 정지 구간이 10초 이상이고 그 구간에 2xx가 계속됨(굶음 결함) | 정지 초와 최장 정지 구간(worker별), 같은 구간의 2xx 건수 | 반증이면 결함이다: 최소 수정과 PostgreSQL 회귀 시험을 넣는다(수정 전 실패·수정 후 통과 확인). 성립하면 "굶음 결함 없음"을 개수로 쓰고 설정을 그대로 둔다 |
| H3 | worker도 과부하에서 `getConnection` 시간 초과를 겪는다(진행이 느려질 뿐 멈추지는 않는다): 크게 초과 켬 조건 5개 창 중 4개 이상에서 worker 실패가 1건 이상 | 창 3개 이상에서 worker 실패 0건인데 Hikari timeout > 0 | worker 실패 counter, `hikaricp.connections.timeout` | 반증이면 worker가 요청보다 유리하게 연결을 얻는다는 뜻이므로 원인을 dump로 본다 |
| H4 | worker는 요청 성공에 측정 가능한 영향이 없다: 같은 수준에서 켬 대 끔의 2xx 건수가 같은 분포다. 근거: idle 10개 스레드의 질의가 풀 용량 대비 1% 안팎(초당 약 40건 × 수 ms)이다 | 한 수준에서 켬의 2xx 건수 중앙값이 끔보다 5% 넘게 낮고 정확 Mann-Whitney 양측 p < 0.05(n = 5 대 5) | k6 2xx·503 건수, 켬·끔의 `pg_stat_database` 트랜잭션 수 차이 | 반증이면 영향 크기를 적고 worker 간격·풀 분리를 대안으로 비교 |
| H5 | 탐지기가 유효하다(음성 대조군): 연결을 붙잡으면 dump 표본의 90% 이상에서 `http-nio` 스레드가 `HikariPool.getConnection`에 막혀 있고 `hikaricp.connections.timeout`이 늘며, worker 스레드가 막힌 표본이 1개 이상이다 | 위 중 하나라도 어긋남 | 접두사별 막힌 스레드 수, timeout counter, worker 실패 counter | 반증이면 탐지기가 못 믿을 값이므로 H1~H4의 dump 기반 해석을 버리고 counter만 쓴다 |
| H6 | 스레드 수 주장이 맞다: 켬 조건 워밍업 뒤 dump에서 `atlas-scheduler` 4, `atlas-payment-recovery` 1, `atlas-order-inventory-recovery` 3, `atlas-paypal-webhook-retry` 1, `atlas-order-inventory-event-relay` 1이 보이고 끔 조건에는 복구 풀 스레드(결제·예약·webhook·relay)가 0이다 | 개수가 다름 | dump 접두사별 스레드 수 | 다르면 문서·코드 주석의 "10 스레드"를 정정한다 |

판정 기준을 미리 적는다: 정지 구간은 3초 초과, 굶음은 10초 이상(주기 PT1S의 10배)이다. 표본은 수준·조건마다 n = 5(음성 대조군 n = 3)이다. 0건이면 rule of three 상한(3/n)을 쓴다.

## 통제

- 변경 없음 대조군: worker 끔 조건(같은 JVM·같은 요청·같은 도착률).
- 짝 비교: 반복마다 켬·끔 순서를 번갈아 호스트 부하 변화가 한 조건에 몰리지 않게 한다.
- 음성 대조군: H5. 결함이 나오면 수정 전 실패·수정 후 통과(변이 확인)를 PostgreSQL 시험으로 보인다.

## 대안

| 대안 | 평가 |
|---|---|
| 측정 없이 풀·스레드를 줄이거나 별도 worker 풀을 만든다 | 근거가 없다. 기각 |
| 시험 클래스 하나(`@SpringBootTest` + Testcontainers)로 같은 JVM에서 부하를 낸다 | 부하 생성기가 앱과 같은 JVM의 CPU·스레드를 써서 Tomcat·Hikari 경합이 왜곡된다. 별도 JVM과 jcmd가 정확하므로 기각 |
| 기존 `load-tests/fault-injection` 하네스 | 컨테이너화된 두 위치 JVM과 감사까지 포함해 한 칸이 무겁고, 이 질문은 앱 한 개와 DB 하나면 충분하다. 매개변수 확장 대신 일회용 구동을 쓰고 내용을 문서에 옮긴다 |

## 사전 부검(pre-mortem)과 되돌리기

- 실패 시나리오: (1) 공유 호스트 부하로 2xx 건수의 반복 간 폭이 커져 H4가 구별 못 한다 → 폭을 적고 켬·끔 건수의 겹침으로 결론, 구별 못 하면 "측정함, 호스트가 폭을 만듦"으로 종결한다. (2) 요청이 너무 가벼워 풀이 포화되지 않는다 → 예비 측정으로 503이 나오는 요청을 골랐고, 창마다 Hikari pending·timeout 최댓값을 기록한다. (3) 표집이 앱을 흔든다 → 켬·끔 모두에 같은 표집을 건다.
- 되돌리기: 문서만의 변경이면 PR revert. 결함 수정이 들어가면 해당 커밋 revert.

## 실행 편차 (사전 등록과 달라진 점, 실행 중 발견 순서)

1. **스크레이프도 풀을 쓴다(음성 대조군에서 발견).** 사전 등록은 1 Hz Prometheus 스크레이프 counter로 worker 진행을 재기로 했다. 그러나 `/actuator/prometheus`의 gauge(`PaymentRecoveryService.metricsSnapshot` 등)는 풀에서 연결을 빌려, 풀이 포화되면 스크레이프 자체가 실패하거나 수 초 걸린다(관찰 효과 표). 그래서 worker 진행의 1차 지표를 풀을 쓰지 않는 쪽으로 바꿨다: 별도 연결(`psql`)이 1초마다 `pg_stat_statements.calls`를 worker 표별로 읽어, 표별 statement 호출 수가 늘지 않은 구간을 "무진행"으로 센다. 사전 등록의 counter 기반 값(성공·실패 반복)은 그대로 쓰되 스크레이프가 성공한 시점 사이의 값이다.
2. **관찰 효과를 줄이는 프로토콜 B를 더했다.** 프로토콜 A(1 Hz 스크레이프 + 2초 `jcmd`)의 풀 점유가 요청·worker를 흔들 수 있어, B는 스크레이프를 창 시작·부하 끝·창 끝 세 번만 하고(`jcmd`와 DB 쪽 관측은 그대로) mid와 high만 돈다. 1차 결과는 B다.
3. **호스트 잠금.** 처음 실행(A, B 일부)은 호스트 잠금 없이 다른 에이전트의 장애 실험(5개 라벨 스택, load1 15~58)과 겹쳐 돌았다. 부모 지시로 잠금(`measure-lock.sh`)을 잡고 B를 새로 돌렸다. 잠금 없이 돈 A는 전 창 오염으로 표시하고 "이전 실행" 표에만 쓴다. 그 사이에 돈 B 두 번(표집기 `dump_loop` 오류로 덤프가 없는 6개 창, 잠금 없이 load1 29~58에서 멈춘 9개 창)은 표에 넣지 않았다.
4. **A 표집기가 `psql` 관찰자를 남겼다(내 버그).** `pgs.sql`(`\watch 1`)을 돌리는 `psql`을 창이 끝날 때 `docker exec` 클라이언트만 종료하고 컨테이너 안 `psql`은 남겨, A가 끝났을 때 유휴 관찰 연결이 38개 있었다(`pg_terminate_backend`로 정리). 관찰자는 초당 한 번 짧은 질의를 한다. 창이 뒤로 갈수록 DB 쪽 잔부하가 누적됐으므로 A의 DB 쪽 값은 반복 간 비교에 쓰지 않는다. 이후 `application_name`으로 정리하는 줄을 추가했고 B는 그 판으로 돈다.
5. `idle`과 `low` 창은 A에만 있다(잠금 하 재측정은 시간 예산으로 하지 않았다). A의 `idle`은 worker 켬 조건에만 있다(끔은 빈 창).

## 결과

정의: 성공 반복 = `tasks.scheduled.execution` 횟수 − worker 실패 counter(각 worker가 `RuntimeException`을 잡을 때 올리는 counter, 만료 sweep은 `outcome=failure`). 폴링 worker 5개 = 결제 복구, 체크아웃 완료, 예약 복구, 재입고 복구, webhook 재시도(각 `fixedDelay` PT1S, event relay는 PT0.2S라 따로 센다). 무진행 = 그 worker 군의 표를 건드리는 statement 호출 수(DB 쪽 `pg_stat_statements.calls`)가 늘지 않은 연속 구간이고, 3초 넘는 구간만 "정지"로 합친다. 값은 중앙값 [최소-최대]이고 창은 30초다. "오염"은 부모 규약(load1 > 12 또는 다른 라벨 스택과 겹침)이다.

### 1차: 잠금을 잡고 돈 프로토콜 B (worker 켬 대 끔, 반복마다 JVM 새로 시작)

| 조건 | 수준(req/s) | n | 보낸 요청 | 2xx | 503 | 클라이언트 시간 초과(5 s) | reset | Hikari getConnection 시간 초과 | 호스트 load1 |
|---|---|---|---|---|---|---|---|---|---|
| worker 끔 | 60 | 5 | 1801 [1800-1801] | 1800 [1491-1801] | 0 [0-310] | 0 [0-0] | 0 [0-0] | 0 [0-311] | 16.9 [5.3-21.8] |
| worker 켬 | 60 | 5 | 1801 [1800-1801] | 1800 [1676-1801] | 0 [0-125] | 0 [0-0] | 0 [0-0] | 0 [0-141] | 25.5 [7.6-53.5] |
| worker 끔 | 200 | 5 | 5696 [4747-6001] | 5611 [292-6001] | 85 [0-164] | 0 [0-4291] | 0 [0-0] | 85 [0-841] | 10.7 [4.7-33.6] |
| worker 켬 | 200 | 5 | 5079 [5002-6001] | 2333 [1977-6001] | 70 [0-108] | 2638 [0-2920] | 0 [0-0] | 243 [0-367] | 18.5 [7.8-47.2] |

| 수준(req/s) | n | 폴링 worker 5개 성공 반복(창 안) | 폴링 worker 5개 실패 반복 | worker 질의 호출/s(DB, 6군) | worker 군별 최장 무진행(초) | 3초 넘는 무진행 합(초, 5군) | 부하 끝난 뒤 첫 진행(초) | Tomcat 스레드 getConnection 대기(덤프 평균) | atlas-* 스레드 getConnection 대기(덤프 평균) | atlas-* 대기 덤프/전체 |
|---|---|---|---|---|---|---|---|---|---|---|
| 60 | 5 | 162 [137-165] | 0 [0-8] | 26.6 [20.2-27.8] | 2.0 [1.1-5.0] | 0.0 [0.0-5.0] | 1.8 [1.7-1.9] | 0.0 [0.0-5.8] | 0.00 [0.00-1.06] | 4/76 |
| 200 | 5 | 134 [132-162] | 9 [0-14] | 9.6 [8.7-26.0] | 3.1 [2.0-5.0] | 4.0 [0.0-12.1] | 2.7 [1.5-3.7] | 27.2 [4.3-30.5] | 3.00 [0.47-3.17] | 64/86 |

켬 조건 60 req/s 창별:

| 반복 | 2xx | 503 | Hikari 시간 초과 | 폴링 worker 성공/실패 | 최장 무진행(초) | worker 최소 성공 | load1 | 다른 라벨 스택 | 오염 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 1800 | 0 | 0.0 | 162/0 | 2.0 | 32.0 | 25.48 | dsx1 | 예 |
| 2 | 1676 | 125 | 141.0 | 137/8 | 5.0 | 26.0 | 53.50 | dsx1 | 예 |
| 3 | 1801 | 0 | 0.0 | 165/0 | 1.1 | 33.0 | 39.01 | dsx1 | 예 |
| 4 | 1801 | 0 | 0.0 | 165/0 | 2.0 | 33.0 | 7.58 | dsx1 | 예 |
| 5 | 1793 | 7 | 10.0 | 159/1 | 3.0 | 31.0 | 16.12 | dsx1 | 예 |

켬 조건 60 req/s, worker 군별 최장 무진행(초, DB 쪽):

| 반복 | payment-claim | payment-recovery | webhook-retry | event-relay | oi-reservation | oi-restock | checkout |
|---|---|---|---|---|---|---|---|
| 1 | 2.0 | 1.3 | 1.3 | 1.3 | 2.0 | 1.3 | 2.0 |
| 2 | 3.0 | 2.0 | 5.0 | 1.9 | 3.0 | 3.0 | 2.0 |
| 3 | 1.1 | 1.1 | 1.1 | 1.1 | 1.1 | 1.1 | 1.1 |
| 4 | 2.0 | 2.0 | 2.0 | 1.1 | 1.1 | 1.1 | 2.0 |
| 5 | 2.0 | 1.2 | 2.0 | 1.2 | 2.0 | 2.0 | 3.0 |

켬 조건 200 req/s 창별:

| 반복 | 2xx | 503 | Hikari 시간 초과 | 폴링 worker 성공/실패 | 최장 무진행(초) | worker 최소 성공 | load1 | 다른 라벨 스택 | 오염 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 5783 | 70 | 82.0 | 152/3 | 3.0 | 29.0 | 18.51 | dsx1 | 예 |
| 2 | 1977 | 105 | 243.0 | 134/9 | 3.1 | 22.0 | 47.25 | dsx1 | 예 |
| 3 | 6001 | 0 | 0.0 | 162/0 | 2.0 | 32.0 | 21.25 | dsx1 | 예 |
| 4 | 2070 | 68 | 293.0 | 134/11 | 5.0 | 23.0 | 7.76 | dsx1 | 예 |
| 5 | 2333 | 108 | 367.0 | 132/14 | 4.0 | 20.0 | 14.79 | dsx1 | 예 |

켬 조건 200 req/s, worker 군별 최장 무진행(초, DB 쪽):

| 반복 | payment-claim | payment-recovery | webhook-retry | event-relay | oi-reservation | oi-restock | checkout |
|---|---|---|---|---|---|---|---|
| 1 | 2.0 | 2.0 | 2.9 | 1.1 | 2.0 | 3.0 | 2.0 |
| 2 | 3.0 | 3.0 | 3.0 | 2.0 | 2.1 | 3.1 | 3.1 |
| 3 | 2.0 | 1.1 | 1.1 | 1.1 | 2.0 | 2.0 | 2.0 |
| 4 | 5.0 | 4.0 | 2.2 | 2.1 | 2.0 | 2.0 | 3.0 |
| 5 | 4.0 | 3.0 | 3.0 | 2.9 | 3.0 | 2.1 | 3.0 |

### 음성 대조군: `product` 표 ACCESS EXCLUSIVE 잠금 22초, 켬 조건, 60 req/s 20초 (A 표집기: 1 Hz 스크레이프)

| 반복 | 2xx | 503 | Hikari 시간 초과 | Tomcat 스레드 대기 덤프(≥1)/전체 | Tomcat 대기 평균 | atlas-* 대기 덤프(≥1)/전체 | atlas-* 대기 평균·최대 | worker 폴링 실패 합(6군) | 최장 무진행(초) |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 5 | 1156 | 1344.0 | 10/11 | 27.4 | 9/11 | 3.45·9 | 91.0 | 21.0 |
| 2 | 13 | 1147 | 1341.0 | 11/11 | 26.5 | 10/11 | 3.73·7 | 94.0 | 21.3 |
| 3 | 10 | 1151 | 1333.0 | 10/11 | 27.1 | 11/11 | 3.73·5 | 94.0 | 21.4 |

### 이전 실행: 잠금 없이 돈 프로토콜 A (전 창 load1 > 12로 오염 표시, 반복 4회)

맥락용이다. `idle`·`low` 수준은 이 표에만 있다.

| 조건 | 수준(req/s) | n | 보낸 요청 | 2xx | 503 | 클라이언트 시간 초과(5 s) | reset | Hikari getConnection 시간 초과 | 호스트 load1 |
|---|---|---|---|---|---|---|---|---|---|
| worker 끔 | 20 | 4 | 600 [600-601] | 600 [588-601] | 0 [0-12] | 0 [0-0] | 0 [0-0] | 0 [0-12] | 26.3 [20.2-29.1] |
| worker 켬 | 20 | 4 | 601 [601-601] | 598 [594-601] | 2 [0-7] | 0 [0-0] | 0 [0-0] | 4 [0-16] | 26.2 [21.8-41.6] |
| worker 끔 | 60 | 4 | 1801 [1800-1801] | 1782 [1462-1801] | 19 [0-339] | 0 [0-0] | 0 [0-0] | 19 [0-340] | 21.0 [15.4-22.3] |
| worker 켬 | 60 | 4 | 1800 [1800-1801] | 1724 [1499-1801] | 76 [0-302] | 0 [0-0] | 0 [0-0] | 90 [0-358] | 22.0 [18.2-32.3] |
| worker 끔 | 200 | 4 | 4749 [4736-4806] | 420 [24-897] | 178 [65-371] | 4151 [3844-4341] | 0 [0-0] | 1627 [371-2558] | 21.3 [16.2-27.0] |
| worker 켬 | 200 | 4 | 4726 [4720-4740] | 248 [130-352] | 246 [201-314] | 4231 [4169-4296] | 0 [0-0] | 1335 [623-3055] | 21.3 [16.9-32.8] |

| 수준(req/s) | n | 폴링 worker 5개 성공 반복(창 안) | 폴링 worker 5개 실패 반복 | worker 질의 호출/s(DB, 6군) | worker 군별 최장 무진행(초) | 3초 넘는 무진행 합(초, 5군) | 부하 끝난 뒤 첫 진행(초) | Tomcat 스레드 getConnection 대기(덤프 평균) | atlas-* 스레드 getConnection 대기(덤프 평균) | atlas-* 대기 덤프/전체 |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 4 | 146 [142-146] | 0 [0-0] | 36.1 [35.7-37.3] | 1.8 [1.2-2.0] | 0.0 [0.0-0.0] | 1.2 [1.2-1.5] | 0.0 [0.0-0.0] | 0.00 [0.00-0.07] | 1/60 |
| 20 | 4 | 147 [141-150] | 2 [0-3] | 33.1 [31.6-35.4] | 2.5 [1.9-3.2] | 0.0 [0.0-3.2] | 1.5 [1.2-1.9] | 0.5 [0.2-0.6] | 0.37 [0.12-0.75] | 8/62 |
| 60 | 4 | 123 [97-150] | 4 [0-24] | 25.3 [15.0-34.8] | 3.0 [2.0-7.7] | 6.0 [0.0-26.1] | 1.7 [1.3-1.9] | 8.9 [0.8-14.9] | 1.80 [0.07-2.50] | 30/61 |
| 200 | 4 | 61 [10-88] | 54 [20-93] | 6.0 [1.7-9.9] | 9.5 [8.0-15.1] | 46.0 [13.0-144.1] | 6.7 [1.5-15.0] | 30.1 [30.0-30.6] | 3.64 [3.56-4.06] | 72/72 |

프로토콜 A의 1 Hz 필터 스크레이프(`/actuator/prometheus?includedNames=...`, 5초 제한)가 부하 창(k6 구간) 안에서 성공했는지:

| 조건 | 수준 | 창 수 | 창당 시도 | 창당 실패(5초 초과) | 성공한 스크레이프의 소요 중앙값(초, 창별) | 최대(초, 창별) |
|---|---|---|---|---|---|---|
| worker 켬 | idle | 4 | [30, 30, 29, 30] | [0, 0, 0, 0] | [0.07, 0.12, 0.08, 0.07] | [0.28, 0.46, 0.34, 0.26] |
| worker 끔 | low | 4 | [30, 30, 31, 30] | [0, 0, 0, 0] | [0.02, 0.01, 0.01, 0.02] | [0.05, 0.02, 0.03, 0.05] |
| worker 켬 | low | 4 | [31, 30, 29, 29] | [0, 0, 0, 0] | [0.08, 0.11, 0.08, 0.1] | [1.05, 1.08, 2.28, 2.13] |
| worker 끔 | mid | 4 | [31, 31, 31, 31] | [0, 0, 0, 0] | [0.01, 0.01, 0.01, 0.01] | [0.01, 0.04, 0.03, 0.04] |
| worker 켬 | mid | 4 | [24, 29, 22, 31] | [0, 0, 1, 0] | [0.13, 0.19, 0.12, 0.06] | [3.56, 2.23, 4.0, 1.33] |
| worker 끔 | high | 4 | [35, 36, 36, 36] | [0, 0, 0, 0] | [0.01, 0.01, 0.01, 0.02] | [0.03, 0.11, 0.03, 0.05] |
| worker 켬 | high | 4 | [9, 10, 9, 14] | [5, 2, 2, 0] | [4.04, 3.46, 3.99, 2.67] | [4.43, 4.89, 4.56, 4.63] |
| worker 켬 | neg-lock | 3 | [6, 5, 6] | [3, 4, 3] | [1.26, 0.73, 1.61] | [4.05, 0.73, 4.07] |

## 가설별 판정

| # | 판정 | 근거(개수) |
|---|---|---|
| H1 낮음·idle에서 굶지 않음 | ✗ 반증(오염된 A, 잠금 하 재측정 없음) | idle 4창은 worker 실패 0·정지 0이었으나, 낮음(20 req/s) 4창 중 2창에서 worker 실패 5건과 3건(요청 503도 7건과 5건, load1 22~42). 반증 조건(5창 중 2창 이상)을 같은 비율로 충족한다. 이 호스트가 20 req/s에서도 풀을 포화시킨 창이라는 뜻이고 worker 탓이라는 근거는 없다. 잠금 하 재측정은 시간 예산으로 하지 않았다(범위 밖 결정, 재검토 조건은 결정 절) |
| H2 worker는 0으로 굶지 않고 10초 넘게 멈추지 않음 | B: 반증 안 됨(local-only). A: ✗ 반증 | 잠금 하 B 켬 10창(mid 5, high 5; 이 중 Hikari 시간 초과가 있었던 포화 창 6개)에서 최장 무진행 5초(10초 이상 0창), worker별 최소 성공 반복 20회/30초. 초점 실행 3창도 0창. 반면 잠금 없이 돈 A는 high 4창 중 2창(15.1초, 10.0초)에서 10초 이상 무진행이 있었고 그 창에서도 2xx는 계속됐다(130건, 238건). A와 B의 차이는 A의 풀 안 1 Hz 스크레이프와 더 큰 호스트 부하 중 어느 쪽 탓인지 가르지 못했다(원인 확정 못 함). 어느 창에서도 worker가 0으로 굶지는 않았다(A 최소 성공 반복 1회) |
| H3 worker도 포화에서 getConnection 시간 초과를 겪음 | ✓ 성립 | B high 켬 5창의 폴링 worker 실패 반복 [3.0, 9.0, 0.0, 11.0, 14.0] (4창에서 1건 이상). 반증 조건(실패 0인데 Hikari 시간 초과 > 0인 창 3개 이상)은 0창. 시간 초과가 없던 창(3번, Hikari 시간 초과 0)은 실패도 0 |
| H4 worker가 요청 성공에 영향 없음 | 반증 못 함, 구별도 못 함(not confirmed) | mid 2xx 중앙값 켬 1800 대 끔 1800(p=1.00, n=5 대 5), high 켬 2333 대 끔 5611(−58%, 정확 Mann-Whitney p=0.75). 같은 조건 안에서도 high 2xx가 292~6001로 흔들려(끔 [4806, 292, 6000, 6001, 5611], 켬 [5783, 1977, 6001, 2070, 2333]) 호스트 용량이 조건 차이보다 크다. 반증 조건(5% 넘게 낮고 p<0.05)은 충족하지 않았으나 검정력이 없다 |
| H5 탐지기가 유효함(음성 대조군) | ✓ 성립 | 잠금 하 n=3, 연결 점유 22초: Tomcat 스레드가 getConnection에서 막힌 덤프 31/33, atlas-* 스레드가 막힌 덤프 30/33, Hikari 시간 초과 [1344.0, 1341.0, 1333.0], 2xx ['5', '13', '10'], worker 폴링 실패 합 ['91.0', '94.0', '94.0'](성공 ['0.0', '0.0', '0.0']은 worker별 최소 성공), 최장 무진행 ['21.0', '21.3', '21.4']초(점유 22초와 일치) |
| H6 스레드 수 | ✓ 성립 | 켬 mid·high 10창 모두(`10`/10) `atlas-scheduler` 4, `atlas-payment-recovery` 1, `atlas-order-inventory-recovery` 3, `atlas-paypal-webhook-retry` 1, `atlas-order-inventory-event-relay` 1(합 10). 끔 10창 중 `10`창은 `atlas-scheduler` 4만(복구 풀 0). 끔에서도 `atlas-scheduler` 4는 남는다: 만료 sweep을 `PT24H`로 늘렸을 뿐 풀 자체는 만들어진다 |

## 주장 표

모든 창이 부모 규약의 오염 기준(다른 라벨 스택 `ds` 1개가 모든 창에 겹쳤고 대부분 load1 > 12)에 해당한다. 그래서 확신도의 `confirmed`는 "켬·끔·반복 안에서 신호가 호스트 잡음보다 한 자릿수 이상 크거나 개수·불변식이 같은 방향"일 때만 쓰고, 그렇지 않으면 `local-only`나 `not confirmed`로 쓴다.

| 주장 | 증거 | 확신도 |
|---|---|---|
| 앱의 스케줄러 스레드는 켬 10개(4+1+3+1+1)이고 끔은 복구 풀 스레드가 0이다 | 덤프 접두사별 개수, 켬 10/10창·끔 10/10창 | confirmed |
| 포화(Hikari 시간 초과 > 0)에서 복구 worker의 getConnection이 시간 초과로 실패한다 | B high 켬 4/5창에서 폴링 worker 실패 1건 이상(최대 14건/약 145회), 앱 로그에 `Connection is not available, request timed out after 50xms (total=10, active=10, idle=0, waiting=3x)`가 worker 스레드(`y-event-relay-1`, `tory-recovery-1`)에서도 찍힘 | local-only(오염된 호스트) |
| 복구 worker가 0으로 굶는 결함은 없다: 포화 중에도 worker 군마다 진행하고 부하가 끝나면 곧 재개한다 | B 켬 10창 worker별 최소 성공 반복 20/30초, 최장 무진행 5초, 부하 후 첫 진행 3.7초 이하. A에서도 최소 1회 이상 | local-only |
| 포화 중 worker의 처리량은 줄지만(DB 호출/s 합 8.7~9.6 대 포화 아닌 창 26~28) 멈추지 않는다 | B high 켬 포화 창 대 비포화 창 | local-only |
| 복구가 10초 넘게 멈추지 않는다 | B 켬 10창 최장 5초, 초점 3창 최장 3초. 단 A 4창 중 2창에서 10.0초와 15.1초(오염, 풀 안 스크레이프) | not confirmed(재검토 조건은 결정 절) |
| worker가 요청 성공률을 떨어뜨린다/떨어뜨리지 않는다 | mid 동일(1800 대 1800), high는 호스트 용량 편차(292~6001)가 조건 차이보다 커서 구별 못 함 | not confirmed(재검토 조건은 결정 절) |
| 탐지기(덤프 접두사별 getConnection 대기 수 + Hikari 시간 초과 + DB 쪽 무진행)는 연결 고갈에서 운다 | 음성 대조군 n=3, 위 H5 | confirmed |
| 포화 중에는 `/actuator/prometheus` 스크레이프도 풀 연결을 빌려 실패하거나 수 초 걸린다(관측 맹점) | A 켬 high 4창 시도 [9, 10, 9, 14] 중 실패 [5, 2, 2, 0], 성공한 것의 중앙값 2.7~4.0초, 음성 대조군 3창 실패 3·4·3회. 덤프에서 스크레이프 스레드가 `PaymentRecoveryRepository.metricsSnapshot`에서 `HikariPool.getConnection`에 막힘(1회) | local-only |
| 풀 용량을 넘는 부하의 실패는 reset이 아니라 503과 클라이언트 시간 초과로 나타난다 | 전 창 reset 0(켬·끔 B 20창과 A 창) | local-only |
| "200 req/s"가 항상 풀 용량 초과는 아니다 | 같은 구성의 high 창에서 2xx가 292~6001(30초 × 200 = 6000이 상한)로, 호스트 부하가 낮은 창(load1 5~8)은 6000건을 모두 처리했다 | confirmed(조건 정의의 한계) |

## 결정

- ✓ 결정(가정): 설정(Hikari 풀 10, `connection-timeout` 500 ms, Tomcat 40, worker 풀 10 스레드)과 코드는 바꾸지 않는다. 근거: 복구 worker가 0으로 굶는 결함이 어느 창에서도 나오지 않았고(B 10창 최소 성공 20/30초, A 최소 1회), 포화 중에도 worker가 진행하며, 요청에 대한 worker의 영향은 이 호스트 잡음 안에서 구별되지 않았다. 결함이 없어 수정과 회귀 시험은 만들지 않았다(변이 확인 대상 없음). H2의 사전 등록 결과("10초 이상 정지면 결함, 수정")는 A 2창에서 문자 그대로 충족됐지만, A는 오염되고 풀 안 스크레이프가 섞였으며 최장 15.1초는 복구 lease(PT30S)보다 짧고 부하가 끝난 뒤 15초 안에 재개했다. 이 판단은 실행 뒤에 한 것이다(사전 등록은 "결함이면 수정"이었다). 그래서 결함이 아니라 "지연이 유한하다"는 관찰로 적고, 아래 재검토 조건으로 닫는다.
- 재검토 조건(수치, 어느 하나라도 충족하면 같은 하네스를 `measure-lock.sh` 아래 load1 ≤ 12·다른 라벨 스택 없음으로 n ≥ 10 다시 돌린다): (1) 한 worker 군이 2xx가 이어지는 창에서 30초 이상(`lease-duration` PT30S) DB 쪽 진행이 없다, (2) 운영 지표 `atlas.payment.recovery.oldest_due_age_seconds` 또는 `atlas.order.inventory.recovery.oldest_due_age_seconds`가 30초를 60초 넘게 넘는다, (3) `maximum-pool-size`·Tomcat `threads.max`·worker 풀 크기·`connection-timeout`을 바꾸는 PR, (4) 새 `@Scheduled` worker가 기존 풀에 추가된다.
- 범위 밖 결정: H1(낮음 수준 잠금 하 재측정)과 H4(요청 영향)의 구별. 이유: 이 호스트에서 같은 조건의 2xx가 292~6001로 흔들려 n=5로 구별할 수 없고, 호스트 로드 5 이하·다른 스택 없음이 보장되는 구간을 잡지 못했다. 대안: 호스트를 전용으로 비우거나 부하를 용량보다 훨씬 낮게(20 req/s) 고정해 worker 켬·끔을 번갈아 n ≥ 10. 재검토 조건: 위 (1)~(4) 또는 load1 ≤ 5 구간 확보.
- 관측 맹점은 결함으로 분류하지 않고 기록만 한다: 포화 중 `/actuator/prometheus` 스크레이프가 실패하는 것은 gauge가 풀을 쓰기 때문이다. 이미 EXP-046이 스크레이프 질의 비용을 다뤘다. 재검토 조건: 운영에서 포화 중 Prometheus 스크레이프 실패율이 10%를 넘고 그 구간의 원인 분석이 필요해질 때(별도 관리 풀이나 캐시 gauge가 대안).

## 한계

- 모든 창이 오염 기준에 해당한다(다른 라벨 스택 겹침, load1 4.7~54). 시간 값은 맥락이고 결론은 개수와 불변식이다. 켬·끔은 반복마다 순서를 번갈아 호스트 부하가 한 조건에 몰리지 않게 했으나 같은 JVM·같은 호스트의 순차 비교이므로 짝 검정이 아니다.
- 재고 위치 JVM 없이 돌렸다: 복구 worker는 빈 폴링(일감 0)만 했다. 일감이 있어 provider·위치 호출이 끼면 worker가 연결을 쥐는 시간이 달라진다(이 실험이 답하지 못함).
- 요청은 인증 없는 읽기 한 종류(약 88 KB 응답)다. 쓰기 트랜잭션(연결을 길게 쥠)은 측정하지 않았다.
- worker의 연결 확보 순서(FIFO 가정)는 확인하지 못했다. 관찰된 것은 결과(진행 유지)뿐이다.
- 사전 등록 5창 대비 A는 4창(예산), B는 5창이다. `idle`·`low`는 A에만 있다.

## 삭제한 파일의 내용

이 PR은 구동·표집·분석 스크립트를 저장소에 올리지 않는다(`experiment-artifacts.md`: 일회성 실험 구동 파일). 아래에 방법, 정확한 명령, 열 설명, 스크립트 본문을 옮겨 적는다. 원자료(`*.jsonl`, `*.tsv`, k6 요약·CSV, 앱 로그)는 저장소 밖에 있고 올리지 않았다. 스크립트는 작업 폴더에서 가져오며 경로는 `$W`(작업 폴더), `$REPO`(이 저장소의 worktree), `$JDK25_HOME`, `$COLIMA_SOCK`, `$MEASURE_LOCK`(호스트 잠금 스크립트, 이 저장소에 없음)으로 바꿨다. 본문은 실행에 쓴 파일에서 이 환경의 경로 문자열만 치환한 것이고, 다른 변경은 없다(`make_appendix.py`가 치환 규칙 5개만 적용하고 로컬 경로·계정명이 남지 않았음을 확인했다).

### 환경과 정확한 명령

- 앱: `./gradlew :atlas-application:bootJar -x test`로 만든 `atlas-application.jar`(main `af7e6e10` 기준, 이 PR의 코드 변경 없음). JDK 25(`-Xms1g -Xmx1g`), 앱은 호스트에서 직접 실행, PostgreSQL 18.6은 Colima Docker 컨테이너(`max_connections=200`, `shared_preload_libraries=pg_stat_statements`).
- 부하 생성기: k6(`constant-arrival-rate`, `discardResponseBodies`, 요청 5초 제한). 호스트 공유이므로 부하 생성기·`jcmd`·`psql` 관찰자가 같은 CPU를 쓴다.
- 앱 기동 인자: `--spring.profiles.active=observability --atlas.order-inventory-reservation.enabled=true --atlas.order-inventory-reservation.location-endpoints=<닫힌 포트>`와 조건별 인자(`start-pg.sh`·`start-app.sh` 참고). 켬은 `event-relay.enabled=true`를 더하고, 끔은 worker 속성들을 끄고 만료 sweep 주기를 `PT24H`로 늘린다. 재고 위치 JVM은 띄우지 않았다(복구할 일감이 없어 worker는 빈 폴링만 한다).
- 1차 실행: `PoolStarvation` 이름으로 `measure-lock.sh acquire`를 잡은 뒤 `python3 neg_control.py 3 <out>`, `DUR=30 python3 run_matrix_b.py 5 <out>`, 이어서 `python3 focus_run.py 3 <out>`. 분석은 `python3 analyze.py <out> > L.tsv`, `python3 report.py L.tsv`, `python3 focus_analyze.py <out>`, `python3 scrape_stats.py <out>`.
- 파일 열 설명(`analyze.py` TSV): `reqs`·`s200`·`s503`·`s5xx`·`reset`·`to`(클라이언트 시간 초과)는 k6 counter, `hk_timeout`은 `hikaricp.connections.timeout`의 창 안 증가분, `<worker>:succ/fail`은 위 정의의 성공·실패 반복 증가분, `db:<군>:calls_s`·`:longest`·`:stall_s`는 `pgs.sql` 관측에서 구한 호출/s·최장 무진행·3초 넘는 무진행 합, `http_blocked_mean`·`atlas_blocked_mean`은 덤프당 `HikariPool.getConnection`에 막힌 Tomcat(`http-nio-<앱 포트>-exec-*`)·`atlas-*` 스레드 수의 평균, `contaminated`는 load1 > 12 또는 다른 라벨 스택과 겹침.

### 스크립트 본문

### `start-pg.sh`

PostgreSQL 18.6 컨테이너 기동.

```bash
#!/bin/bash
. $W/env.sh
docker rm -f atlasexp-pool-pg >/dev/null 2>&1
docker run -d --name atlasexp-pool-pg --label atlasexp=pool106 -e POSTGRES_USER=atlas -e POSTGRES_PASSWORD=atlas-local-only -e POSTGRES_DB=atlas -p 127.0.0.1:$PGPORT:5432 postgres:18.6 -c max_connections=200 -c fsync=on >/dev/null
for i in $(seq 1 40); do docker exec atlasexp-pool-pg pg_isready -U atlas -d atlas >/dev/null 2>&1 && { echo pg ready; exit 0; }; sleep 1; done
echo pg not ready; exit 1
```

### `start-app.sh`

앱 jar 기동(조건 on/off). 인자 하나가 조건이다.

```bash
#!/bin/bash
# start-app.sh <arm: on|off> [extra spring args...]  -> prints pid, writes $W/app.pid, log $W/app-<arm>.log
. $W/env.sh
arm=$1; shift
pkill -f 'atlas-application.jar --server.port' 2>/dev/null; sleep 1
COMMON=(--server.port=$APPPORT --management.server.port=$MGMT --spring.profiles.active=observability
 --atlas.order-inventory-reservation.enabled=true --atlas.order-inventory-reservation.api-token=exp106-api-token-0123456789abcdef0123456789
 "--atlas.order-inventory-reservation.location-endpoints=00000000-0000-0000-0000-000000000001=http://127.0.0.1:9"
 --atlas.order-inventory-reservation.service-token=exp106-service-token-0123456789abcdef012345)
if [ "$arm" = on ]; then
  ARM=(--atlas.order-inventory-reservation.event-relay.enabled=true)
else
  ARM=(--atlas.payment.recovery.worker.enabled=false --atlas.order-inventory-reservation.recovery.worker-enabled=false
       --atlas.payment.paypal.webhook-retry.worker.enabled=false
       --atlas.admission.expiry-poll-interval=PT24H --atlas.orders.expiry-poll-interval=PT24H)
fi
export ATLAS_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/atlas?options=-c%20statement_timeout%3D30000%20-c%20idle_in_transaction_session_timeout%3D60000%20-c%20lock_timeout%3D5000"
export ATLAS_DB_USER=atlas ATLAS_DB_PASSWORD=atlas-local-only
export ATLAS_JWT_SIGNING_SECRET=$(printf 'exp106-jwt-secret-0123456789abcdef0123456789' | base64)
export ATLAS_PAYMENT_PROVIDER=disabled SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=1025
nohup java -Xms1g -Xmx1g -jar $REPO/atlas-application/build/libs/atlas-application.jar "${COMMON[@]}" "${ARM[@]}" "$@" > $W/app-$arm.log 2>&1 &
echo $! > $W/app.pid
for i in $(seq 1 90); do
  curl -s -o /dev/null http://127.0.0.1:$MGMT/actuator/health && { echo "ready pid=$(cat $W/app.pid) after ${i}s"; exit 0; }
  kill -0 $(cat $W/app.pid) 2>/dev/null || { echo died; tail -30 $W/app-$arm.log; exit 1; }
  sleep 1
done
echo timeout; tail -20 $W/app-$arm.log; exit 1
```

### `load.js`

k6 부하(고정 도착률). 응답 결과를 2xx/503/기타 5xx/reset/시간 초과로 센다.

```javascript
import http from 'k6/http';
import { Counter } from 'k6/metrics';
const BASE = __ENV.BASE;
const PATHS = (__ENV.PATHS || '/api/products?minPriceWon=1500&maxPriceWon=4000&limit=100').split(',');
const MODE = __ENV.MODE || 'open';
const c = {
  s200: new Counter('s200'), s503: new Counter('s503'), s4xx: new Counter('s4xx'), s5xx_other: new Counter('s5xx_other'),
  s0_reset: new Counter('s0_reset'), s0_timeout: new Counter('s0_timeout'), s0_other: new Counter('s0_other'),
};
export const options = MODE === 'open' ? {
  scenarios: { load: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE), timeUnit: '1s',
    duration: __ENV.DURATION || '30s', preAllocatedVUs: Number(__ENV.VUS || 300), maxVUs: Number(__ENV.MAXVUS || 800) } },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'], discardResponseBodies: true,
} : {
  scenarios: { load: { executor: 'constant-vus', vus: Number(__ENV.VUS), duration: __ENV.DURATION || '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'], discardResponseBodies: true,
};
export default function () {
  const p = PATHS[Math.floor(Math.random() * PATHS.length)];
  const r = http.get(BASE + p, { timeout: '5s' });
  if (r.status === 200) c.s200.add(1);
  else if (r.status === 503) c.s503.add(1);
  else if (r.status >= 400 && r.status < 500) c.s4xx.add(1);
  else if (r.status >= 500) c.s5xx_other.add(1);
  else if (r.error_code >= 1200 && r.error_code < 1300) c.s0_reset.add(1);
  else if (r.error_code === 1050 || r.error_code === 1000 + 0 || /timeout|deadline/i.test(r.error || '')) c.s0_timeout.add(1);
  else c.s0_other.add(1);
}
```

### `pgs.sql`

DB 쪽 진행 관측: 1초마다 worker 표별 statement 호출 수(`pg_stat_statements.calls`).

```sql
\pset footer off
\pset title ''
select extract(epoch from clock_timestamp())::numeric(16,3) as t, grp, sum(calls) as calls from (select case when query ~ 'work\.recovery_job_id' then 'payment-claim' when query ~ 'payment_recovery_job' then 'payment-recovery' when query ~ 'paypal_webhook_retry' then 'webhook-retry' when query ~ 'order_inventory_event' then 'event-relay' when query ~ 'order_inventory_reservation_workflow' then 'oi-reservation' when query ~ 'order_inventory_restock' then 'oi-restock' when query ~ 'buyer_checkout' then 'checkout-finalizer+expiry' when query ~ 'admission_ticket' then 'expiry-admission' when query ~ 'purchase_order' then 'expiry-orders' else null end as grp, calls from pg_stat_statements where query not like '%pg_stat_statements%' and dbid=(select oid from pg_database where datname='atlas')) s where grp is not null group by grp order by grp \watch 1
```

### `sampler.py`

프로토콜 A 표집기: 1 Hz Prometheus 필터 스크레이프 + 2초마다 `jcmd Thread.print` + `pgs.sql`.

```python
#!/usr/bin/env python3
"""sampler.py <pid> <mgmt_port> <app_port> <out.jsonl> <stopfile>: 1 Hz Micrometer/Hikari + every 2 s jcmd Thread.print."""
import sys, os, re, json, time, subprocess, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
pid, mgmt, app, out, stop = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
NS = 'com.atlas.application.service.'
WORKERS = ['PaymentRecoveryWorker', 'CheckoutFinalizationWorker', 'LocationReservationWorker', 'LocationRestockWorker',
           'PayPalWebhookRetryWorker', 'ReservationEventWorker', 'ScheduledExpiryMetricsWorker']
NAMES = ('hikaricp_connections_active,hikaricp_connections_idle,hikaricp_connections_pending,hikaricp_connections_timeout,'
         'tasks_scheduled_execution_seconds,atlas_order_inventory_recovery_polls,atlas_paypal_webhook_retry_runs,'
         'atlas_expiry_sweep_runs,atlas_payment_recovery_poll_failures,atlas_order_inventory_event_relay_failed')
LINE = re.compile(r'^([a-z_]+?)(\{[^}]*\})? ([0-9.eE+-]+)$')
def scrape():
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{mgmt}/actuator/prometheus?includedNames={NAMES}', timeout=5) as r:
            txt = r.read().decode()
    except Exception:
        return None
    m = {}
    for ln in txt.split('\n'):
        mm = LINE.match(ln)
        if not mm or ln.startswith('#'):
            continue
        name, lab, val = mm.group(1), mm.group(2) or '', float(mm.group(3))
        if name.startswith('hikaricp'):
            m[name] = val
        elif name == 'tasks_scheduled_execution_seconds_count':
            fn = re.search(r'code_function="([^"]+)"', lab).group(1); ns = re.search(r'code_namespace="([^"]+)"', lab).group(1).split('.')[-1]
            oc = re.search(r'outcome="([^"]+)"', lab).group(1)
            k = f'ex|{ns}.{fn}'
            m[k] = m.get(k, 0) + val
        elif name in ('atlas_order_inventory_recovery_polls_total', 'atlas_paypal_webhook_retry_runs_total', 'atlas_expiry_sweep_runs_total'):
            if 'failure' in lab:
                st = re.search(r'(stage|workflow)="([^"]+)"', lab)
                m[f'fl|{name}|{st.group(2) if st else ""}'] = val
        elif name in ('atlas_payment_recovery_poll_failures_total', 'atlas_order_inventory_event_relay_failed_total'):
            k = f'fl|{name}|'
            m[k] = m.get(k, 0) + val
    return m
def prefix(name):
    m = re.match(r'http-nio-.*-(\d+)-exec-\d+$', name)
    if m:
        return 'http-app' if m.group(1) == app else 'http-mgmt'
    return re.sub(r'[-#]?\d+$', '', name)
def dump():
    t0 = time.time()
    try:
        txt = subprocess.run(['jcmd', pid, 'Thread.print'], capture_output=True, text=True, timeout=20).stdout
    except Exception:
        return None
    res = {}
    for blk in txt.split('\n\n'):
        if not blk.startswith('"'):
            continue
        name = blk.split('"')[1]
        p = prefix(name)
        d = res.setdefault(p, {'n': 0, 'getconn': 0, 'other_hikari_wait': 0})
        d['n'] += 1
        if 'HikariPool.getConnection' in blk:
            d['getconn'] += 1
    return {'dump_s': round(time.time() - t0, 3), 'threads': res}
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
pgs = subprocess.Popen(['docker', 'exec', '-i', '-e', 'PGAPPNAME=exp106pgs', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-AtF|', '-q'],
                       stdin=open('$W/pgs.sql'), stdout=open(out + '.pgs', 'w'), stderr=subprocess.DEVNULL)
f = open(out, 'w')
import threading
lock = threading.Lock()
def emit(rec):
    with lock:
        f.write(json.dumps(rec) + '\n'); f.flush()
def scrape_loop():
    while not os.path.exists(stop):
        t = time.time()
        emit({'t': round(t, 3), 'm': scrape(), 'kind': 'scrape', 't_end': round(time.time(), 3)})
        time.sleep(max(0.0, 1.0 - (time.time() - t)))
def dump_loop():
    while not os.path.exists(stop):
        t = time.time()
        emit({'t': round(t, 3), 'm': None, 'kind': 'dump', 'dump': dump()})
        time.sleep(max(0.0, 2.0 - (time.time() - t)))
ths = [threading.Thread(target=scrape_loop), threading.Thread(target=dump_loop)]
[th.start() for th in ths]
[th.join() for th in ths]
f.close()
pgs.terminate()
subprocess.run(['docker', 'exec', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-Atc', "select pg_terminate_backend(pid) from pg_stat_activity where application_name='exp106pgs'"], capture_output=True)
```

### `sampler_b.py`

프로토콜 B 표집기: `sampler.py`와 같지만 스크레이프를 창 시작·부하 끝·창 끝에 한 번씩만 한다.

```python
#!/usr/bin/env python3
"""sampler.py <pid> <mgmt_port> <app_port> <out.jsonl> <stopfile>: 1 Hz Micrometer/Hikari + every 2 s jcmd Thread.print."""
import sys, os, re, json, time, subprocess, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
pid, mgmt, app, out, stop = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
NS = 'com.atlas.application.service.'
WORKERS = ['PaymentRecoveryWorker', 'CheckoutFinalizationWorker', 'LocationReservationWorker', 'LocationRestockWorker',
           'PayPalWebhookRetryWorker', 'ReservationEventWorker', 'ScheduledExpiryMetricsWorker']
NAMES = ('hikaricp_connections_active,hikaricp_connections_idle,hikaricp_connections_pending,hikaricp_connections_timeout,'
         'tasks_scheduled_execution_seconds,atlas_order_inventory_recovery_polls,atlas_paypal_webhook_retry_runs,'
         'atlas_expiry_sweep_runs,atlas_payment_recovery_poll_failures,atlas_order_inventory_event_relay_failed')
LINE = re.compile(r'^([a-z_]+?)(\{[^}]*\})? ([0-9.eE+-]+)$')
def scrape():
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{mgmt}/actuator/prometheus?includedNames={NAMES}', timeout=5) as r:
            txt = r.read().decode()
    except Exception:
        return None
    m = {}
    for ln in txt.split('\n'):
        mm = LINE.match(ln)
        if not mm or ln.startswith('#'):
            continue
        name, lab, val = mm.group(1), mm.group(2) or '', float(mm.group(3))
        if name.startswith('hikaricp'):
            m[name] = val
        elif name == 'tasks_scheduled_execution_seconds_count':
            fn = re.search(r'code_function="([^"]+)"', lab).group(1); ns = re.search(r'code_namespace="([^"]+)"', lab).group(1).split('.')[-1]
            oc = re.search(r'outcome="([^"]+)"', lab).group(1)
            k = f'ex|{ns}.{fn}'
            m[k] = m.get(k, 0) + val
        elif name in ('atlas_order_inventory_recovery_polls_total', 'atlas_paypal_webhook_retry_runs_total', 'atlas_expiry_sweep_runs_total'):
            if 'failure' in lab:
                st = re.search(r'(stage|workflow)="([^"]+)"', lab)
                m[f'fl|{name}|{st.group(2) if st else ""}'] = val
        elif name in ('atlas_payment_recovery_poll_failures_total', 'atlas_order_inventory_event_relay_failed_total'):
            k = f'fl|{name}|'
            m[k] = m.get(k, 0) + val
    return m
def prefix(name):
    m = re.match(r'http-nio-.*-(\d+)-exec-\d+$', name)
    if m:
        return 'http-app' if m.group(1) == app else 'http-mgmt'
    return re.sub(r'[-#]?\d+$', '', name)
def dump():
    t0 = time.time()
    try:
        txt = subprocess.run(['jcmd', pid, 'Thread.print'], capture_output=True, text=True, timeout=20).stdout
    except Exception:
        return None
    res = {}
    for blk in txt.split('\n\n'):
        if not blk.startswith('"'):
            continue
        name = blk.split('"')[1]
        p = prefix(name)
        d = res.setdefault(p, {'n': 0, 'getconn': 0, 'other_hikari_wait': 0})
        d['n'] += 1
        if 'HikariPool.getConnection' in blk:
            d['getconn'] += 1
    return {'dump_s': round(time.time() - t0, 3), 'threads': res}
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
pgs = subprocess.Popen(['docker', 'exec', '-i', '-e', 'PGAPPNAME=exp106pgs', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-AtF|', '-q'],
                       stdin=open('$W/pgs.sql'), stdout=open(out + '.pgs', 'w'), stderr=subprocess.DEVNULL)
f = open(out, 'w')
import threading
lock = threading.Lock()
def emit(rec):
    with lock:
        f.write(json.dumps(rec) + '\n'); f.flush()
def scrape_once(kind, tries=3):
    t = time.time()
    m = None
    for _ in range(tries):
        m = scrape()
        if m:
            break
    emit({'t': round(t, 3), 'm': m, 'kind': kind, 't_end': round(time.time(), 3)})
def scrape_loop():
    scrape_once('scrape_start')
    mark_done = False
    while not os.path.exists(stop):
        if not mark_done and os.path.exists(stop + '.mark'):
            scrape_once('scrape_end')
            mark_done = True
        time.sleep(0.2)
    scrape_once('scrape_final')
def dump_loop():
    while not os.path.exists(stop):
        t = time.time()
        emit({'t': round(t, 3), 'm': None, 'kind': 'dump', 'dump': dump()})
        time.sleep(max(0.0, 2.0 - (time.time() - t)))
ths = [threading.Thread(target=scrape_loop), threading.Thread(target=dump_loop)]
[th.start() for th in ths]
[th.join() for th in ths]
f.close()
pgs.terminate()
subprocess.run(['docker', 'exec', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-Atc', "select pg_terminate_backend(pid) from pg_stat_activity where application_name='exp106pgs'"], capture_output=True)
```

### `run_matrix.py`

프로토콜 A 구동기: 조건 × 수준 × 반복.

```python
#!/usr/bin/env python3
"""run_matrix.py <reps> <outdir>: for rep in 1..reps, for arm in (on, off): fresh JVM, warmup, then windows (idle[on only], low, mid, high)."""
import sys, os, json, time, subprocess
os.environ['JAVA_HOME'] = '$JDK25_HOME'
os.environ['PATH'] = os.environ['JAVA_HOME'] + '/bin:' + os.environ['PATH']
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
reps, outdir = int(sys.argv[1]), sys.argv[2]
os.makedirs(outdir, exist_ok=True)
APP, MGMT = 47080, 47081
LEVELS = [('low', 20), ('mid', 60), ('high', 200)]
DUR = int(os.environ.get('DUR', '30'))
def load1():
    return subprocess.run("uptime | sed 's/.*averages*: //'", shell=True, capture_output=True, text=True).stdout.strip()
def k6(rate, dur, summary):
    cmd = ['k6', 'run', '--quiet', '-e', f'BASE=http://127.0.0.1:{APP}', '-e', 'MODE=open', '-e', f'RATE={rate}', '-e', f'DURATION={dur}s',
           '--summary-export=' + summary, '$W/load.js']
    t0 = time.time(); subprocess.run(cmd, capture_output=True); return t0, time.time()
def pgx():
    r = subprocess.run(['docker', 'exec', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-Atc',
        "select xact_commit+xact_rollback from pg_stat_database where datname='atlas'"], capture_output=True, text=True)
    try: return int(r.stdout.strip())
    except Exception: return None
def window(rep, arm, level, rate, pid):
    tag = f'{rep}-{arm}-{level}'
    stop = f'{outdir}/{tag}.stop'
    if os.path.exists(stop): os.remove(stop)
    sp = subprocess.Popen(['python3', '$W/sampler.py', str(pid), str(MGMT), str(APP), f'{outdir}/{tag}.samples.jsonl', stop])
    time.sleep(3)
    l = load1()
    x0 = pgx()
    if rate > 0:
        t0, t1 = k6(rate, DUR, f'{outdir}/{tag}.k6.json')
    else:
        t0 = time.time(); time.sleep(DUR); t1 = time.time()
    x1 = pgx()
    time.sleep(10)
    open(stop, 'w').close(); sp.wait(timeout=30)
    meta = dict(rep=rep, arm=arm, level=level, rate=rate, k6_start=t0, k6_end=t1, uptime=l, tag=tag, pid=pid, pg_x0=x0, pg_x1=x1)
    open(f'{outdir}/windows.jsonl', 'a').write(json.dumps(meta) + '\n')
    print('window done', tag, l, flush=True)
for rep in range(1, reps + 1):
    for arm in ('on', 'off') if rep % 2 else ('off', 'on'):
        r = subprocess.run(['$W/start-app.sh', arm], capture_output=True, text=True)
        print(r.stdout.strip().splitlines()[-1], flush=True)
        if 'ready' not in r.stdout: continue
        pid = int(open('$W/app.pid').read())
        k6(20, 10, f'{outdir}/warmup.k6.json')
        if arm == 'on': window(rep, arm, 'idle', 0, pid)
        for level, rate in LEVELS: window(rep, arm, level, rate, pid)
        subprocess.run(['kill', str(pid)]); time.sleep(3)
print('MATRIX DONE', flush=True)
```

### `run_matrix_b.py`

프로토콜 B 구동기: `run_matrix.py`에서 수준을 mid·high로 줄이고 idle 창을 빼고 표집기를 바꾼 사본.

```python
#!/usr/bin/env python3
"""run_matrix.py <reps> <outdir>: for rep in 1..reps, for arm in (on, off): fresh JVM, warmup, then windows (idle[on only], low, mid, high)."""
import sys, os, json, time, subprocess
os.environ['JAVA_HOME'] = '$JDK25_HOME'
os.environ['PATH'] = os.environ['JAVA_HOME'] + '/bin:' + os.environ['PATH']
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
reps, outdir = int(sys.argv[1]), sys.argv[2]
os.makedirs(outdir, exist_ok=True)
APP, MGMT = 47080, 47081
LEVELS = [('mid', 60), ('high', 200)]
DUR = int(os.environ.get('DUR', '30'))
def lock_status():
    return subprocess.run(['bash', '$MEASURE_LOCK', 'status'], capture_output=True, text=True).stdout.strip().replace('\n', ' | ')
def load1():
    return subprocess.run("uptime | sed 's/.*averages*: //'", shell=True, capture_output=True, text=True).stdout.strip()
def k6(rate, dur, summary):
    cmd = ['k6', 'run', '--quiet', '-e', f'BASE=http://127.0.0.1:{APP}', '-e', 'MODE=open', '-e', f'RATE={rate}', '-e', f'DURATION={dur}s',
           '--summary-export=' + summary, '$W/load.js']
    t0 = time.time(); subprocess.run(cmd, capture_output=True); return t0, time.time()
def pgx():
    r = subprocess.run(['docker', 'exec', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-Atc',
        "select xact_commit+xact_rollback from pg_stat_database where datname='atlas'"], capture_output=True, text=True)
    try: return int(r.stdout.strip())
    except Exception: return None
def window(rep, arm, level, rate, pid):
    tag = f'{rep}-{arm}-{level}'
    stop = f'{outdir}/{tag}.stop'
    if os.path.exists(stop): os.remove(stop)
    sp = subprocess.Popen(['python3', '$W/sampler_b.py', str(pid), str(MGMT), str(APP), f'{outdir}/{tag}.samples.jsonl', stop])
    time.sleep(3)
    l = load1()
    x0 = pgx()
    if rate > 0:
        t0, t1 = k6(rate, DUR, f'{outdir}/{tag}.k6.json')
        open(stop + '.mark', 'w').close()
    else:
        t0 = time.time(); time.sleep(DUR); t1 = time.time()
    x1 = pgx()
    time.sleep(10)
    open(stop, 'w').close(); sp.wait(timeout=30)
    meta = dict(lock_status=lock_status(), rep=rep, arm=arm, level=level, rate=rate, k6_start=t0, k6_end=t1, uptime=l, tag=tag, pid=pid, pg_x0=x0, pg_x1=x1)
    open(f'{outdir}/windows.jsonl', 'a').write(json.dumps(meta) + '\n')
    print('window done', tag, l, flush=True)
for rep in range(1, reps + 1):
    for arm in ('on', 'off') if rep % 2 else ('off', 'on'):
        r = subprocess.run(['$W/start-app.sh', arm], capture_output=True, text=True)
        print(r.stdout.strip().splitlines()[-1], flush=True)
        if 'ready' not in r.stdout: continue
        pid = int(open('$W/app.pid').read())
        k6(20, 10, f'{outdir}/warmup.k6.json')
        for level, rate in LEVELS: window(rep, arm, level, rate, pid)
        subprocess.run(['kill', str(pid)]); time.sleep(3)
print('MATRIX DONE', flush=True)
```

### `chain2.sh`

(잠금 없이) 음성 대조군 뒤 프로토콜 A 행렬을 잇는 래퍼.

```bash
#!/bin/bash
cd $W
python3 neg_control.py 3 $W/raw2 > $W/neg2.out 2>&1
DUR=30 python3 run_matrix.py 5 $W/raw2 > $W/matrix.out 2>&1
```

### `chain3.sh`

잠금을 잡고 음성 대조군과 프로토콜 B 행렬을 돌리는 래퍼(1차 결과).

```bash
#!/bin/bash
cd $W
L=$MEASURE_LOCK
bash $L acquire PoolStarvation 40 > lock3.out 2>&1
echo LOCKACQUIRED
python3 neg_control.py 3 $W/raw5 > neg5.out 2>&1
DUR=30 python3 run_matrix_b.py 5 $W/raw5 > matrix5.out 2>&1
bash $L release PoolStarvation >> lock3.out 2>&1
echo LOCKRELEASED
```

### `chain4.sh`

잠금을 다시 잡고 초점 실행을 돌리는 래퍼.

```bash
#!/bin/bash
cd $W
while ! grep -q LOCKRELEASED chain3.out; do sleep 3; done
L=$MEASURE_LOCK
timeout 300 bash $L acquire PoolStarvation 15 > lock4.out 2>&1
if grep -q '^acquired by PoolStarvation' lock4.out; then
  echo FOCUSLOCK
  python3 focus_run.py 3 $W/raw6 > focus6.out 2>&1
  bash $L release PoolStarvation >> lock4.out 2>&1
else
  echo FOCUSSKIPPED
fi
echo CHAIN4DONE
```

### `focus_run.py`

초점 실행: 200 req/s 켬 조건 30초, k6 요청 단위 CSV 출력.

```python
#!/usr/bin/env python3
"""focus_run.py <reps> <outdir>: workers-on JVM, 200 req/s for 30 s with a per-request k6 CSV so 2xx per second can be lined up against worker gaps."""
import sys, os, json, time, subprocess
os.environ['JAVA_HOME'] = '$JDK25_HOME'
os.environ['PATH'] = os.environ['JAVA_HOME'] + '/bin:' + os.environ['PATH']
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
reps, outdir = int(sys.argv[1]), sys.argv[2]
os.makedirs(outdir, exist_ok=True)
APP, MGMT = 47080, 47081
for rep in range(1, reps + 1):
    r = subprocess.run(['$W/start-app.sh', 'on'], capture_output=True, text=True); print(r.stdout.strip().splitlines()[-1], flush=True)
    pid = int(open('$W/app.pid').read())
    subprocess.run(['k6', 'run', '--quiet', '-e', f'BASE=http://127.0.0.1:{APP}', '-e', 'MODE=open', '-e', 'RATE=20', '-e', 'DURATION=10s', '$W/load.js'], capture_output=True)
    tag = f'{rep}-on-focus'
    stop = f'{outdir}/{tag}.stop'
    if os.path.exists(stop): os.remove(stop)
    sp = subprocess.Popen(['python3', '$W/sampler_b.py', str(pid), str(MGMT), str(APP), f'{outdir}/{tag}.samples.jsonl', stop])
    time.sleep(3)
    l = subprocess.run("uptime | sed 's/.*averages*: //'", shell=True, capture_output=True, text=True).stdout.strip()
    t0 = time.time()
    subprocess.run(['k6', 'run', '--quiet', '-e', f'BASE=http://127.0.0.1:{APP}', '-e', 'MODE=open', '-e', 'RATE=200', '-e', 'DURATION=30s',
                    f'--summary-export={outdir}/{tag}.k6.json', '--out', f'csv={outdir}/{tag}.k6.csv', '$W/load.js'], capture_output=True)
    t1 = time.time()
    open(stop + '.mark', 'w').close()
    time.sleep(10)
    open(stop, 'w').close(); sp.wait(timeout=30)
    open(f'{outdir}/windows.jsonl', 'a').write(json.dumps(dict(lock_status=subprocess.run(['bash', '$MEASURE_LOCK', 'status'], capture_output=True, text=True).stdout.strip().replace(chr(10), ' | '), rep=rep, arm='on', level='focus', rate=200, k6_start=t0, k6_end=t1, uptime=l, tag=tag, pid=pid)) + '\n')
    print('focus window done', tag, l, flush=True)
    subprocess.run(['kill', str(pid)]); time.sleep(3)
print('FOCUS DONE', flush=True)
```

### `focus_analyze.py`

초점 실행 분석: 초당 2xx와 worker 군별 10초 이상 무진행 구간을 맞춘다.

```python
#!/usr/bin/env python3
"""focus_analyze.py <rawdir>: per window, per-second 2xx from the k6 per-request CSV against each worker group's DB-side no-progress gaps.
Prints, per window: 2xx total, seconds with zero 2xx, the longest worker gap, and for every gap >= 10 s the 2xx count inside it."""
import sys, json, csv, collections
raw = sys.argv[1]
GROUPS = ['payment-claim', 'payment-recovery', 'webhook-retry', 'event-relay', 'oi-reservation', 'oi-restock', 'checkout-finalizer+expiry']
print('tag|2xx|503|seconds with 0 2xx (of 30)|longest gap by group (s)|groups with gap>=10 s|2xx inside each such gap|gap_start..gap_end (s after load start)')
for ln in open(f'{raw}/windows.jsonl'):
    w = json.loads(ln)
    tag = w['tag']
    per = collections.Counter(); st = collections.Counter()
    with open(f'{raw}/{tag}.k6.csv', newline='') as fh:
        rd = csv.DictReader(fh)
        for r in rd:
            if r['metric_name'] != 'http_reqs':
                continue
            t = int(float(r['timestamp'])); st[r['status']] += 1
            if r['status'] == '200':
                per[t] += 1
    t0 = w['k6_start']; t1 = w['k6_end']
    zero = sum(1 for s in range(int(t0), int(t0) + 30) if per[s] == 0)
    pg = collections.defaultdict(list)
    for l in open(f'{raw}/{tag}.samples.jsonl.pgs'):
        p = l.strip().split('|')
        if len(p) == 3:
            try: pg[p[1]].append((float(p[0]), float(p[2])))
            except ValueError: pass
    longest = {}; big = []; inside = []; spans = []
    for g in GROUPS:
        ser = [x for x in pg.get(g, []) if t0 <= x[0] <= t1]
        if len(ser) < 2: continue
        last = ser[0][0]; prev = ser[0][1]; gaps = []
        for t, c in ser[1:]:
            if c > prev:
                gaps.append((last, t)); last = t; prev = c
        gaps.append((last, ser[-1][0]))
        a, b = max(gaps, key=lambda x: x[1] - x[0])
        longest[g] = round(b - a, 1)
        for a, b in gaps:
            if b - a >= 10:
                n = sum(v for s, v in per.items() if a - 0.5 <= s <= b + 0.5)
                big.append(g); inside.append(n); spans.append(f'{a - t0:.0f}..{b - t0:.0f}')
    print(f"{tag}|{st['200']}|{st['503']}|{zero}|{longest}|{big}|{inside}|{spans}")
```

### `neg_control.py`

음성 대조군 구동기(`product` 표에 ACCESS EXCLUSIVE 잠금).

```python
#!/usr/bin/env python3
"""neg_control.py <reps> <outdir>: workers-on JVM; hold ACCESS EXCLUSIVE on product for 22 s so request queries pin all 10 pool connections."""
import sys, os, json, time, subprocess
os.environ['JAVA_HOME'] = '$JDK25_HOME'
os.environ['PATH'] = os.environ['JAVA_HOME'] + '/bin:' + os.environ['PATH']
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
reps, outdir = int(sys.argv[1]), sys.argv[2]
os.makedirs(outdir, exist_ok=True)
APP, MGMT = 47080, 47081
def lock_status():
    return subprocess.run(['bash', '$MEASURE_LOCK', 'status'], capture_output=True, text=True).stdout.strip().replace('\n', ' | ')
def load1():
    return subprocess.run("uptime | sed 's/.*averages*: //'", shell=True, capture_output=True, text=True).stdout.strip()
r = subprocess.run(['$W/start-app.sh', 'on'], capture_output=True, text=True); print(r.stdout.strip().splitlines()[-1], flush=True)
pid = int(open('$W/app.pid').read())
subprocess.run(['k6', 'run', '--quiet', '-e', f'BASE=http://127.0.0.1:{APP}', '-e', 'MODE=open', '-e', 'RATE=20', '-e', 'DURATION=10s', '$W/load.js'], capture_output=True)
for rep in range(1, reps + 1):
    tag = f'{rep}-neg-lock'
    stop = f'{outdir}/{tag}.stop'
    if os.path.exists(stop): os.remove(stop)
    sp = subprocess.Popen(['python3', '$W/sampler.py', str(pid), str(MGMT), str(APP), f'{outdir}/{tag}.samples.jsonl', stop])
    time.sleep(4)
    l = load1()
    holder = subprocess.Popen(['docker', 'exec', 'atlasexp-pool-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-c',
        'BEGIN; LOCK TABLE product IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(22); COMMIT;'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    t0 = time.time()
    subprocess.run(['k6', 'run', '--quiet', '-e', f'BASE=http://127.0.0.1:{APP}', '-e', 'MODE=open', '-e', 'RATE=60', '-e', 'DURATION=20s',
                    f'--summary-export={outdir}/{tag}.k6.json', '$W/load.js'], capture_output=True)
    t1 = time.time()
    holder.wait(timeout=30)
    time.sleep(10)
    open(stop, 'w').close(); sp.wait(timeout=30)
    open(f'{outdir}/windows.jsonl', 'a').write(json.dumps(dict(lock_status=lock_status(), rep=rep, arm='on', level='neg-lock', rate=60, k6_start=t0, k6_end=t1, uptime=l, tag=tag, pid=pid)) + '\n')
    print('neg window done', tag, l, flush=True)
    time.sleep(5)
subprocess.run(['kill', str(pid)])
print('NEG DONE', flush=True)
```

### `analyze.py`

창별 집계(TSV).

```python
#!/usr/bin/env python3
"""analyze.py <rawdir> : per-window table (tsv to stdout) from windows.jsonl + samples + k6 summaries."""
import sys, json, os, re, statistics
raw = sys.argv[1]
W = [json.loads(l) for l in open(f'{raw}/windows.jsonl')]
EX = {
 'payment-recovery': ('ex|PaymentRecoveryWorker.poll', ['fl|atlas_payment_recovery_poll_failures_total|']),
 'oi-finalizer': ('ex|CheckoutFinalizationWorker.poll', ['fl|atlas_order_inventory_recovery_polls_total|checkout-finalization']),
 'oi-reservation': ('ex|LocationReservationWorker.poll', ['fl|atlas_order_inventory_recovery_polls_total|reservation']),
 'oi-restock': ('ex|LocationRestockWorker.poll', ['fl|atlas_order_inventory_recovery_polls_total|restock']),
 'webhook-retry': ('ex|PayPalWebhookRetryWorker.retryDueEvents', ['fl|atlas_paypal_webhook_retry_runs_total|']),
 'event-relay': ('ex|ReservationEventWorker.poll', ['fl|atlas_order_inventory_event_relay_failed_total|']),
 'expiry-admission': ('ex|ScheduledExpiryMetricsWorker.expireAdmissionTickets', ['fl|atlas_expiry_sweep_runs_total|admission']),
 'expiry-checkouts': ('ex|ScheduledExpiryMetricsWorker.expireCheckouts', ['fl|atlas_expiry_sweep_runs_total|checkouts']),
 'expiry-orders': ('ex|ScheduledExpiryMetricsWorker.expireOrders', ['fl|atlas_expiry_sweep_runs_total|orders']),
}
RECOVERY = ['payment-recovery', 'oi-finalizer', 'oi-reservation', 'oi-restock', 'webhook-retry', 'event-relay']
STALL = 3.0

def f(x):
    try: return float(x)
    except Exception: return None

def k6val(d, name, f='count'):
    return d.get('metrics', {}).get(name, {}).get(f, 0) or 0

def worker_stats(recs, k, t0, t1, proto='A'):
    exk, flks = EX[k]
    series = []
    for r in recs:
        m = r['m']
        if exk not in m:
            continue
        series.append((r['t'], m[exk], sum(m.get(f, 0.0) for f in flks)))
    if proto == 'A':
        series = [x for x in series if x[0] <= t1 + 0.5]
    if len(series) < 2:
        return None
    iters = series[-1][1] - series[0][1]
    fails = series[-1][2] - series[0][2]
    last_prog = series[0][0]
    prev = series[0][1] - series[0][2]
    gaps = []
    for t, ex, fl in series[1:]:
        s_ = ex - fl
        if s_ > prev:
            gaps.append(t - last_prog)
            last_prog = t
            prev = s_
    gaps.append(series[-1][0] - last_prog)
    dur = series[-1][0] - series[0][0]
    stalls = [g for g in gaps if g > STALL]
    return dict(iters=iters, fails=fails, dur=dur, rate=(iters - fails) / dur if dur else 0, longest=max(gaps), stall_s=sum(stalls),
                succ=iters - fails)

rows = []
for w in W:
    tag = w['tag']
    recs = [json.loads(l) for l in open(f'{raw}/{tag}.samples.jsonl')]
    allrecs = recs
    recs = [r for r in recs if r.get('m')]
    recs.sort(key=lambda r: r['t'])
    recs = [r for r in recs if r.get('m')]
    proto = 'A'
    if any(r.get('kind') == 'scrape_start' for r in allrecs):
        proto = 'B'
        b0 = [r for r in recs if r.get('kind') == 'scrape_start']
        b1 = [r for r in recs if r.get('kind') == 'scrape_end'] or [r for r in recs if r.get('kind') == 'scrape_final']
        recs_all = b0[:1] + b1[:1]
        recs = recs_all
        row_proto_missing = len(recs_all) < 2
    else:
        base_i = max([i for i, r in enumerate(recs) if r['t'] <= w['k6_start'] + 0.5] or [0])
        recs_all = recs[base_i:]
        recs = [r for r in recs_all if r['t'] <= w['k6_end'] + 0.5]
    row = dict(proto=proto, tag=tag, rep=w['rep'], arm=w['arm'], level=w['level'], rate=w['rate'], uptime=w['uptime'].split(',')[0].strip() if w['uptime'] else '')
    ls = w.get('lock_status', '')
    mm = re.search(r'labelled containers:(.*)$', ls)
    others = []
    if mm:
        for n, lab in re.findall(r'(\d+)\s+([A-Za-z0-9_-]+)', mm.group(1)):
            if lab != 'pool106':
                others.append(f'{lab}x{n}')
    row['other_stacks'] = ','.join(others) if mm else 'unrecorded'
    row['load1'] = row['uptime'].split()[0] if row['uptime'] else ''
    row['contaminated'] = int((f(row['load1']) or 99) > 12 or row['other_stacks'] not in ('',))
    kp = f'{raw}/{tag}.k6.json'
    if os.path.exists(kp):
        d = json.load(open(kp))
        row.update(reqs=k6val(d, 'http_reqs'), s200=k6val(d, 's200'), s503=k6val(d, 's503'), s5xx=k6val(d, 's5xx_other'),
                   s4xx=k6val(d, 's4xx'), reset=k6val(d, 's0_reset'), to=k6val(d, 's0_timeout'), oth=k6val(d, 's0_other'),
                   dropped=k6val(d, 'dropped_iterations'), p95=round(k6val(d, 'http_req_duration', 'p(95)')))
    else:
        row.update(reqs=0, s200=0, s503=0, s5xx=0, s4xx=0, reset=0, to=0, oth=0, dropped=0, p95=0)
    if w.get('pg_x0') is not None and w.get('pg_x1') is not None:
        row['pgx_per_s'] = round((w['pg_x1'] - w['pg_x0']) / (w['k6_end'] - w['k6_start']), 1)
    else:
        row['pgx_per_s'] = ''
    # hikari
    hk = [r['m'] for r in recs_all]
    hk_win = [r['m'] for r in recs]
    row['scrapes_in_window'] = len(recs)
    row['final_scrape_after_end'] = int(bool(recs_all) and recs_all[-1]['t'] >= w['k6_end'])
    row['final_scrape_lag_s'] = round(recs_all[-1]['t'] - w['k6_end'], 1) if recs_all else ''
    row['hk_active_max'] = '' if proto == 'B' else max([m.get('hikaricp_connections_active', 0) for m in hk_win] or [0])
    row['hk_pending_max'] = '' if proto == 'B' else max([m.get('hikaricp_connections_pending', 0) for m in hk_win] or [0])
    row['hk_timeout'] = (hk[-1].get('hikaricp_connections_timeout_total', 0) - hk[0].get('hikaricp_connections_timeout_total', 0)) if len(hk) > 1 else 0
    # workers
    ws = {}
    for k in EX:
        ws[k] = worker_stats(recs_all, k, w['k6_start'], w['k6_end'], proto)
    for k in RECOVERY + ['expiry-admission']:
        s = ws.get(k)
        if s and proto == 'B':
            s = dict(s, longest=float('nan'), stall_s=float('nan'))
        row[f'{k}:succ'] = s['succ'] if s else ''
        row[f'{k}:fail'] = s['fails'] if s else ''
        row[f'{k}:longest'] = round(s['longest'], 1) if s else ''
        row[f'{k}:stall_s'] = round(s['stall_s'], 1) if s else ''
    row['rec_succ_per_s'] = round(sum(ws[k]['rate'] for k in RECOVERY if ws[k]), 2)
    row['rec_fail_total'] = sum(ws[k]['fails'] for k in RECOVERY if ws[k])
    row['all_fail_total'] = sum(ws[k]['fails'] for k in EX if ws[k])
    row['rec_stall_s_total'] = '' if proto == 'B' else round(sum(ws[k]['stall_s'] for k in RECOVERY if ws[k]), 1)
    row['rec_longest'] = '' if proto == 'B' else round(max([ws[k]['longest'] for k in RECOVERY if ws[k]] or [0]), 1)
    row['min_succ_worker'] = min([ws[k]['succ'] for k in RECOVERY if ws[k]] or [0]) if row['arm'] == 'on' else ''
    # DB-side progress (pg_stat_statements calls per worker table group), independent of the app pool and of the scrape
    pg = {}
    pf = f'{raw}/{tag}.samples.jsonl.pgs'
    if os.path.exists(pf):
        for ln in open(pf):
            parts = ln.strip().split('|')
            if len(parts) == 3:
                try:
                    pg.setdefault(parts[1], []).append((float(parts[0]), float(parts[2])))
                except ValueError:
                    pass
    row['pgs_samples'] = max([len(v) for v in pg.values()] or [0])
    PGW = {'payment-claim': 'payment-claim', 'payment-recovery': 'payment-recovery', 'webhook-retry': 'webhook-retry', 'event-relay': 'event-relay', 'oi-reservation': 'oi-reservation',
           'oi-restock': 'oi-restock', 'checkout': 'checkout-finalizer+expiry'}
    stall_tot = 0.0; longest_all = 0.0; zero_workers = 0
    for k, g in PGW.items():
        ser = [x for x in pg.get(g, []) if w['k6_start'] <= x[0] <= w['k6_end']]
        if len(ser) < 2:
            row[f'db:{k}:calls_s'] = ''; row[f'db:{k}:longest'] = ''; row[f'db:{k}:stall_s'] = ''; continue
        calls = ser[-1][1] - ser[0][1]
        last = w['k6_start']; prevc = ser[0][1]; gaps = []
        for t, c in ser[1:]:
            if c > prevc:
                gaps.append(t - last); last = t; prevc = c
        gaps.append(w['k6_end'] - last)
        st = sum(x for x in gaps if x > STALL)
        row[f'db:{k}:calls_s'] = round(calls / (ser[-1][0] - ser[0][0]), 2)
        row[f'db:{k}:longest'] = round(max(gaps), 1)
        row[f'db:{k}:stall_s'] = round(st, 1)
        stall_tot += st if k not in ('checkout', 'payment-claim') else 0
        longest_all = max(longest_all, max(gaps))
        if calls == 0: zero_workers += 1
    # tail: DB-side calls per second in the 10 s after the load ended (does progress resume?)
    tail_rates = []
    first_prog = []
    for k, g in PGW.items():
        ser = [x for x in pg.get(g, []) if x[0] >= w['k6_end']]
        if len(ser) >= 2:
            tail_rates.append((ser[-1][1] - ser[0][1]) / (ser[-1][0] - ser[0][0]))
            base = ser[0][1]
            fp = next((t - w['k6_end'] for t, c in ser if c > base), None)
            if fp is not None: first_prog.append(fp)
    row['db_tail_calls_s_sum'] = round(sum(tail_rates), 2)
    row['db_tail_first_progress_max_s'] = round(max(first_prog), 1) if first_prog else ''
    row['db_stall_s_total'] = round(stall_tot, 1)
    row['db_longest'] = round(longest_all, 1)
    row['db_zero_workers'] = zero_workers
    # dumps
    dumps = [r['dump']['threads'] for r in allrecs if r.get('dump') and w['k6_start'] <= r['t'] <= w['k6_end']]
    row['n_dumps'] = len(dumps)
    def agg(sel):
        tot = []
        for t in dumps:
            tot.append(sum(v['getconn'] for p, v in t.items() if sel(p)))
        return tot
    app = agg(lambda p: p == 'http-app')
    atl = agg(lambda p: p.startswith('atlas-'))
    row['http_blocked_mean'] = round(statistics.mean(app), 1) if app else ''
    row['http_blocked_max'] = max(app) if app else ''
    row['atlas_blocked_mean'] = round(statistics.mean(atl), 2) if atl else ''
    row['atlas_blocked_max'] = max(atl) if atl else ''
    row['dumps_http_ge1'] = sum(1 for x in app if x >= 1)
    row['dumps_http_ge8'] = sum(1 for x in app if x >= 8)
    row['dumps_atlas_ge1'] = sum(1 for x in atl if x >= 1)
    # thread counts (H6)
    if dumps:
        t = dumps[-1]
        row['thr'] = ','.join(f"{p.replace('atlas-', '').replace('order-inventory-', 'oi-')}={v['n']}" for p, v in sorted(t.items()) if p.startswith('atlas-'))
        row['http_threads_max'] = max(t.get('http-app', {}).get('n', 0) for t in dumps)
    rows.append(row)

cols = list(rows[0].keys())
for r in rows:
    for c in r:
        if c not in cols:
            cols.append(c)
print('\t'.join(cols))
for r in rows:
    print('\t'.join(str(r.get(c, '')) for c in cols))
```

### `agg.py`

수준·조건별 중앙값·범위와 정확 Mann-Whitney.

```python
#!/usr/bin/env python3
"""agg.py <analysis.tsv> : medians [min-max] per (arm, level) and exact Mann-Whitney on/off."""
import sys, csv, statistics, itertools
rows = list(csv.DictReader(open(sys.argv[1]), delimiter='\t'))
def f(x):
    try: return float(x)
    except Exception: return None
def mwu_exact(a, b):
    n, m = len(a), len(b)
    allv = a + b
    def U(x, y):
        return sum((1.0 if xi > yi else 0.5 if xi == yi else 0.0) for xi in x for yi in y)
    u0 = U(a, b)
    mu = n * m / 2
    dev0 = abs(u0 - mu)
    cnt = tot = 0
    for idx in itertools.combinations(range(n + m), n):
        x = [allv[i] for i in idx]; y = [allv[i] for i in range(n + m) if i not in idx]
        tot += 1
        if abs(U(x, y) - mu) >= dev0 - 1e-9: cnt += 1
    return cnt / tot
WK = ['payment-recovery', 'oi-finalizer', 'oi-reservation', 'oi-restock', 'webhook-retry', 'event-relay']
DBW = ['payment-claim', 'payment-recovery', 'webhook-retry', 'event-relay', 'oi-reservation', 'oi-restock', 'checkout']
COLS = ['reqs', 's200', 's503', 'reset', 'to', 'oth', 'dropped', 'p95', 'pgx_per_s', 'hk_timeout', 'rec_succ_per_s', 'rec_fail_total', 'all_fail_total',
        'db_stall_s_total', 'db_longest', 'db_zero_workers', 'http_blocked_mean', 'atlas_blocked_mean', 'atlas_blocked_max', 'dumps_atlas_ge1', 'n_dumps', 'dumps_http_ge1', 'uptime', 'min_succ_worker', 'db_tail_calls_s_sum', 'db_tail_first_progress_max_s', 'hk_active_max', 'hk_pending_max', 'scrapes_in_window']
COLS += [f'{k}:succ' for k in WK] + [f'{k}:fail' for k in WK] + [f'db:{k}:calls_s' for k in DBW] + [f'db:{k}:longest' for k in DBW] + [f'db:{k}:stall_s' for k in DBW]
groups = {}
for r in rows:
    groups.setdefault((r['arm'], r['level'] + ('/B' if r.get('proto') == 'B' else '')), []).append(r)
order = {'idle': 0, 'low': 1, 'mid': 2, 'mid/B': 2.5, 'high': 3, 'high/B': 3.5, 'neg-lock': 4}
for (arm, level), rs in sorted(groups.items(), key=lambda kv: (order.get(kv[0][1], 9), kv[0][0])):
    print(f'== arm={arm} level={level} n={len(rs)}')
    for c in COLS:
        v = [f(r.get(c)) for r in rs]; v = [x for x in v if x is not None]
        if not v: continue
        print(f'  {c}: med {statistics.median(v):g} [{min(v):g}-{max(v):g}]  all={[int(x) if x==int(x) else round(x,2) for x in v]}')
print('\n== on vs off exact Mann-Whitney (two-sided)')
for level in ('low', 'mid', 'high', 'mid/B', 'high/B'):
    on = [r for r in groups.get(('on', level), [])]; off = [r for r in groups.get(('off', level), [])]
    if len(on) < 2 or len(off) < 2: continue
    for c in ('s200', 's503', 'reset', 'to', 'dropped', 'reqs', 'p95'):
        a = [f(r[c]) for r in on]; b = [f(r[c]) for r in off]
        ma, mb = statistics.median(a), statistics.median(b)
        rel = (ma - mb) / mb * 100 if mb else float('nan')
        print(f'  {level} {c}: on med {ma:g} off med {mb:g} rel {rel:+.1f}%  p={mwu_exact(a, b):.3f} (n={len(a)} vs {len(b)})')
```

### `report.py`

머리표 요약표.

```python
#!/usr/bin/env python3
"""report.py <tsv...>: compact per-(arm, level, proto) medians [min-max] for the headline metrics."""
import sys, csv, statistics
rows = []
for p in sys.argv[1:]:
    rows += list(csv.DictReader(open(p), delimiter='\t'))
def f(x):
    try: return float(x)
    except Exception: return None
G = {}
for r in rows:
    G.setdefault((r['arm'], r['level'], r.get('proto', 'A')), []).append(r)
order = {'idle': 0, 'low': 1, 'mid': 2, 'high': 3, 'focus': 3.5, 'neg-lock': 4}
def cell(rs, c, nd=1):
    v = [f(r.get(c)) for r in rs]; v = [x for x in v if x is not None]
    if not v: return '-'
    fmt = (lambda x: f'{x:.{nd}f}'.rstrip('0').rstrip('.')) if nd else (lambda x: f'{x:.0f}')
    return f'{fmt(statistics.median(v))} [{fmt(min(v))}-{fmt(max(v))}]'
def wsum(rs, suffix):
    out = []
    for r in rs:
        v = [f(r.get(f'{k}:{suffix}')) for k in ('payment-recovery', 'oi-finalizer', 'oi-reservation', 'oi-restock', 'webhook-retry')]
        out.append(sum(x for x in v if x is not None))
    return out
print('arm|level|proto|n|offered|2xx|503|reset|timeout|hk_timeout|worker_succ_5(it)|worker_fail_5|db_payment_claim_calls_s|db_total_workers_calls_s|db_longest_gap_s|db_stall_s_total|db_tail_first_progress_s|http_blocked_mean|atlas_blocked_mean|dumps_atlas_ge1/n|uptime1')
for (arm, level, proto), rs in sorted(G.items(), key=lambda kv: (order.get(kv[0][1], 9), kv[0][0], kv[0][2])):
    ws = wsum(rs, 'succ'); wf = wsum(rs, 'fail')
    tot = []
    for r in rs:
        v = [f(r.get(f'db:{k}:calls_s')) for k in ('payment-recovery', 'webhook-retry', 'event-relay', 'oi-reservation', 'oi-restock', 'checkout')]
        tot.append(sum(x for x in v if x is not None))
    ddl = f"{sum(f(r.get('dumps_atlas_ge1')) or 0 for r in rs):.0f}/{sum(f(r.get('n_dumps')) or 0 for r in rs):.0f}"
    print('|'.join([arm, level, proto, str(len(rs)), cell(rs, 'reqs', 0), cell(rs, 's200', 0), cell(rs, 's503', 0), cell(rs, 'reset', 0), cell(rs, 'to', 0), cell(rs, 'hk_timeout', 0),
                    f'{statistics.median(ws):.0f} [{min(ws):.0f}-{max(ws):.0f}]', f'{statistics.median(wf):.0f} [{min(wf):.0f}-{max(wf):.0f}]',
                    cell(rs, 'db:payment-claim:calls_s', 2), f'{statistics.median(tot):.1f} [{min(tot):.1f}-{max(tot):.1f}]', cell(rs, 'db_longest'), cell(rs, 'db_stall_s_total'),
                    cell(rs, 'db_tail_first_progress_max_s'), cell(rs, 'http_blocked_mean'), cell(rs, 'atlas_blocked_mean', 2), ddl, cell(rs, 'uptime', 1)]))
```

### `scrape_stats.py`

창마다 스크레이프 시도·실패·소요(관찰 효과 점검).

```python
#!/usr/bin/env python3
"""scrape_stats.py <rawdir>: per (arm, level) the filtered /actuator/prometheus scrape outcome during the k6 window."""
import sys, json, statistics
raw = sys.argv[1]
W = [json.loads(l) for l in open(f'{raw}/windows.jsonl')]
agg = {}
for w in W:
    recs = [json.loads(l) for l in open(f"{raw}/{w['tag']}.samples.jsonl")]
    sc = [r for r in recs if r.get('kind') == 'scrape' and w['k6_start'] <= r['t'] <= w['k6_end']]
    if not any(r.get('kind') == 'scrape' for r in recs):
        continue
    ok = [r for r in sc if r.get('m')]
    durs = [r['t_end'] - r['t'] for r in ok]
    a = agg.setdefault((w['arm'], w['level']), dict(att=[], fail=[], med=[], mx=[]))
    a['att'].append(len(sc)); a['fail'].append(len(sc) - len(ok))
    a['med'].append(round(statistics.median(durs), 2) if durs else None)
    a['mx'].append(round(max(durs), 2) if durs else None)
order = {'idle': 0, 'low': 1, 'mid': 2, 'high': 3, 'neg-lock': 4}
print('프로토콜 A의 1 Hz 필터 스크레이프(`/actuator/prometheus?includedNames=...`, 5초 제한)가 부하 창(k6 구간) 안에서 성공했는지:')
print()
print('| 조건 | 수준 | 창 수 | 창당 시도 | 창당 실패(5초 초과) | 성공한 스크레이프의 소요 중앙값(초, 창별) | 최대(초, 창별) |')
print('|---|---|---|---|---|---|---|')
for (arm, level), a in sorted(agg.items(), key=lambda kv: (order.get(kv[0][1], 9), kv[0][0])):
    print(f"| worker {'켬' if arm == 'on' else '끔'} | {level} | {len(a['att'])} | {a['att']} | {a['fail']} | {a['med']} | {a['mx']} |")
```
