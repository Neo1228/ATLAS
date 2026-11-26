# EXP-106: 요청 과부하에서 복구 worker가 Hikari 풀을 두고 굶는가

- 상태: 사전 등록(실행 전). 결과 절은 실행 뒤에 같은 문서에 채운다.
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
