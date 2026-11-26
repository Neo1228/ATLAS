# EXP-110: 복구 worker와 Hikari 풀: 깨끗한 창, 실제 복구 일감, 연결 획득 순서로 다시 측정

- 상태: ✓ 완료. 사전 등록은 별도 커밋으로 측정 전에 올렸고(그 뒤 추가 등록 세 번도 해당 수준 측정 전에 커밋), "실행 기록과 편차" 이후가 실행 뒤에 채운 절이다.
- 날짜: 2026-11-26
- 관련: [EXP-106](EXP-106-scheduler-hikari-pool-starvation.md)(같은 질문의 1차 측정), [EXP-108](EXP-108-dependency-slowness-thread-saturation.md), [D-174](../00-governance/decision-log.md), [#703](https://github.com/Neo1228/ATLAS/issues/703)

## 문제 한 문장

EXP-106은 모든 창이 오염(다른 에이전트의 라벨 스택과 겹침, load1 4.7~54)이었고, worker가 빈 폴링만 했으며(재고 위치 JVM 없음, 복구 일감 0), 연결 획득 순서(FIFO)를 결과로만 추정했다. 이 실험은 (1) 호스트 잠금을 잡고 `load1 ≤ 12`이며 다른 라벨 스택이 없을 때만 돈 창만 쓰고, (2) 실제 `payment_recovery_job`·`order_inventory_reservation_workflow`·`order_inventory_restock`·`paypal_webhook_retry` 행을 심어 worker가 쓰기 트랜잭션과 위치 API 호출을 하게 하며, (3) HikariCP 7.0.2의 소스와 같은 버전으로 짠 획득 순서 실험으로 worker와 요청 스레드의 연결 획득 순서를 확인해, 복구 worker가 요청 과부하에서 굶는지, 요청 성공률을 떨어뜨리는지 다시 센다.

## 배경 값

- 코드·설정은 EXP-106과 같다: Hikari 풀 10(`maximum-pool-size` 미설정 기본값), `connection-timeout` 500 ms, Tomcat 최대 40, worker 풀 4+1+3+1+1 = 10 스레드. main `201f6361` 기준이며 이 실험은 운영 코드를 바꾸지 않는다(결함이 나오면 예외).
- HikariCP 7.0.2 `ConcurrentBag`(소스 jar를 읽음): `borrow`는 (a) 스레드 지역 목록, (b) 공유 목록 CAS, (c) 둘 다 실패하면 `waiters`를 올리고 공정 모드 `SynchronousQueue handoffQueue.poll(timeout)`에서 대기한다. `requite`는 상태를 `NOT_IN_USE`로 바꾼 뒤 `waiters > 0`이면 `handoffQueue.offer`로 대기자에게 넘긴다. 따라서 대기자끼리는 FIFO이고, 새로 도착한 스레드가 `setState`와 `offer` 사이에 공유 목록 CAS로 끼어들 수는 있다(barging). 이것은 소스를 읽은 추정이며 H8이 실행으로 검증한다.
- 실제 일감(`seed.sql`, 창마다 유형당 3000행을 새로 심음): 결제 복구 job(주문이 없는 종료 시도 → 복구가 `OrderException`으로 실패하고 재예약하는 쓰기 경로), 예약 workflow(PENDING + 위치 명령 → 위치 API `reserve` 호출 → RESERVED 쓰기), 재입고(PENDING → 위치 API `restock` 호출 → COMPLETED 쓰기), webhook 재시도(미지원 유형 이벤트). 위치 API는 JVM이 아니라 20 ms 지연을 주는 스텁(`ThreadingHTTPServer`)이 답한다(`HttpLocationClient`가 쓰는 두 경로와 `Idempotency-Key` 헤더 형식만 응답). 체크아웃 완료 worker와 만료 sweep은 심을 수 있는 일감 경로를 구성하지 못해 빈 폴링이다(한계 절에 적는다).
- 이벤트 릴레이는 켬 조건에서 Kafka 없이(`localhost:9092` 닫힘) 돈다. 예약 workflow가 이벤트를 만들면 릴레이는 발행에 실패한다. 릴레이의 실패는 풀 시간 초과와 섞이므로 worker 실패 집계는 릴레이를 뺀 5개 폴링 worker로 한다. Kafka 발행이 닫힌 포트에서 막히는 시간이 DB 무진행으로 보이지 않도록 켬 조건에 `event-relay.send-timeout=PT1S`(허용 최소값)를 준다(기본 PT10S면 발행 대기만으로 10초 무진행이 생겨 R1이 거짓으로 발동한다). 이 값은 측정 실행 전에 정했다.

## 측정 방법 (사전 등록)

- 대상·부하·표집: EXP-106 프로토콜 B와 같다(앱 jar는 호스트 JVM `-Xms1g -Xmx1g`, PostgreSQL 18.6 컨테이너, 요청은 `GET /api/products?minPriceWon=1500&maxPriceWon=4000&limit=100`, k6 `constant-arrival-rate` 30초, 스크레이프는 창 시작·부하 끝·창 끝 3번, `jcmd Thread.print` 2초마다, 별도 `psql` 연결이 1초마다 `pg_stat_statements.calls`와 행 상태 개수를 읽음). 추가로 행 상태 개수(결제 복구 시도 합, RESERVED workflow 수, COMPLETED 재입고 수, 남은 webhook 수)를 1초마다 읽는다.
- 조건과 수준: worker 켬·끔 × 60·200 req/s, 켬에만 부하 없음(idle) 창. 창마다 같은 `seed.sql`을 먼저 돌린다(끔 조건에도 심으므로 DB 상태가 같다). JVM은 (반복, 조건)마다 새로 시작하고 10초 워밍업(20 req/s)을 버린다. 조건 순서는 반복마다 번갈아 한다.
- **깨끗한 창의 정의(실행 전 고정)**: 창 시작 직전의 `measure-lock.sh status`가 잠금 보유자 `PoolStarvation`, `load1 ≤ 12`, 라벨 `atlasexp=pool110` 말고 다른 라벨 컨테이너 0개이고, 창이 끝난 직후의 status에서도 다른 라벨 컨테이너가 0개일 때. 창마다 시작·끝 status 전문, 호스트 `ps` 기준 내 프로세스 밖의 CPU 합(공변량)을 기록한다. 이 정의를 못 지킨 창은 오염이며 머리표 집계에서 빼고 따로 표로 적는다. 깨끗한 창이 칸마다 n = 10에 도달할 때까지, 또는 예산(2026-11-26 23:00)까지 반복을 늘린다. 도달하지 못하면 도달한 n을 그대로 쓴다.
- 판정에 쓰는 정의: (a) 군별 무진행 = 그 군의 statement 호출 수 또는 행 상태 개수가 늘지 않은 연속 구간(DB 쪽, 풀을 쓰지 않는 연결). (b) 굶음 = 일감 잔량이 있고(심은 행이 남음) 요청 2xx가 같은 구간에 계속되는 동안 10초 이상 이어진 무진행. (c) 포화 창 = `hikaricp.connections.timeout` 증가분 > 0. (d) 요청 영향 = 켬 대 끔 2xx 건수의 중앙값 비와 정확 Mann-Whitney(양측)와 비의 부트스트랩 95% 구간(10000회 재표본).
- 음성 대조군: 켬 조건에서 `product` 표에 ACCESS EXCLUSIVE 잠금을 22초 잡아 요청이 연결 10개를 점유하게 한다(60 req/s 20초, n = 3, 같은 깨끗한 창 규칙).
- 연결 획득 순서 실험(H8): 앱과 같은 HikariCP 7.0.2·pgjdbc jar를 앱 jar에서 꺼내 단일 파일 Java 프로그램으로 같은 PostgreSQL에 붙는다(풀 10, 실제 연결). (a) 대기자 순서: 10개 연결을 모두 붙잡고 대기자 30개를 25 ms 간격으로 순서대로 도착시킨 뒤 50 ms마다 하나씩 반납, 연결을 받은 순서가 도착 순서와 같은지, 10회. (b) 끼어들기(barging)와 군 공정성: 50 스레드(worker 모사 10 + 요청 모사 40, 모두 `getConnection` → 5 ms `pg_sleep` → 반납을 반복, 획득 대기 30초)를 20초 돌리며 획득마다 호출 시각·획득 시각을 기록한다. "추월" = 같은 풀에서 먼저 호출했고 아직 기다리는 스레드가 있는데 나중에 호출한 스레드가 먼저 획득한 경우(호출 간격 1 ms 초과만 셈). 5회 반복. (d) 앱과 같은 500 ms 연결 대기 상한: 요청 모사 스레드 40개(`getConnection` → 5 ms `pg_sleep` → 반납을 쉬지 않고 반복)와 worker 모사 스레드 10개(한 번의 폴링 = 위 세 단계 3번 연속, 그 뒤 1초 쉼)를 30초, 5회 돌려 군별 시도당 시간 초과 확률을 센다. 대조로 요청 모사 8개(풀 용량 미만)로 같은 실험을 5회 돌려 시간 초과가 0임을 확인한다(0이 아니면 측정이 무효).

## 가설과 반증 조건 (실행 전 등록, 실행 뒤에 바꾸지 않는다)

규칙이 발동하면 "발동"이라고 쓰고 결과 절·결정에서 그대로 따른다. 임계는 EXP-106의 사전 등록 값을 그대로 가져온 것(R1·R2)과 이번에 새로 정한 것(나머지)을 구분해 적는다.

| # | 가설 | 반증(발동) 조건 | 지표 | 발동 시 |
|---|---|---|---|---|
| R1 (EXP-106 H2 이월) | 복구 worker는 굶지 않는다: 깨끗한 200 req/s 켬 창에서 어떤 worker 군도 굶음(위 정의 b, 10초 이상)이 없다 | 깨끗한 창 하나라도 굶음 | 군별 최장 무진행, 같은 구간 2xx 건수 | 결함이다. 최소 수정과 PostgreSQL 회귀 시험(수정 전 실패, 수정 후 통과, 변이 확인)을 이 PR에 넣고 `fullcheck.sh`를 돌린다 |
| R2 (EXP-106 H4 이월) | worker는 요청 성공에 영향이 없다 | 어느 수준(60, 200)에서 켬 2xx 중앙값이 끔보다 5% 넘게 낮고 정확 Mann-Whitney p < 0.05 | 2xx·503 건수, 중앙값 비, 부트스트랩 구간 | 영향 있음으로 쓰고 크기를 적는다. 대안(worker 간격, 풀 분리)은 결정에서 비교 |
| R2e | 영향 없음을 "확인"하려면 비의 95% 구간이 [0.9, 1.1] 안이어야 한다 | 구간이 그 밖 | 같은 지표 | R2가 발동하지 않아도 구간이 밖이면 "구별 못 함(not confirmed)"이고 재검토 조건을 같은 행에 쓴다 |
| H3 | 포화 창에서 worker도 `getConnection` 시간 초과로 실패한다 | 깨끗한 200 req/s 켬 포화 창의 20% 넘는 창에서 5개 폴링 worker 실패(`CannotGetJdbcConnectionException` 계열 포함 실패 counter) 합이 0 | worker 실패 counter, 앱 로그 시간 초과 줄의 스레드 접두사 | 반증이면 worker가 요청보다 유리하게 연결을 얻는다는 뜻이므로 H8 결과와 맞춰 원인을 쓴다 |
| H5 | 탐지기가 유효하다(음성 대조군) | 3창 중 하나라도: Tomcat 스레드가 getConnection에 막힌 덤프 비율 < 90%, `hikaricp.connections.timeout` 증가 0, atlas-* 스레드가 막힌 덤프 0, 또는 최장 무진행 < 15초 | 덤프 접두사별 개수, Hikari timeout, DB 쪽 무진행 | 탐지기를 못 믿는 값으로 보고 덤프 기반 해석을 버린다 |
| H6 | 스레드 수: 켬 `atlas-scheduler` 4, `atlas-payment-recovery` 1, `atlas-order-inventory-recovery` 3, `atlas-paypal-webhook-retry` 1, `atlas-order-inventory-event-relay` 1, 끔 복구 풀 0 | 개수가 다름 | 덤프 접두사별 스레드 수 | "10 스레드" 문구를 정정 |
| H7a | 일감이 있는 동안 모든 일감 유형(결제 복구 시도, 예약 workflow, 재입고, webhook)이 계속 진행한다: 200 req/s 켬 깨끗한 창마다 모든 유형의 행 상태 진행이 최소 1회 이상 | 어떤 유형이 창 안 진행 0 | 행 상태 개수 증가 | R1과 같음(굶음) |
| H7b | 부하가 있어도 worker 처리량이 유지된다: 켬 조건 유형별 창 안 완료 행 수 중앙값이 idle의 (60 req/s) ≥ 80%, (200 req/s) ≥ 25% | 중앙값이 그 아래 | 예약 workflow RESERVED·재입고 COMPLETED 증가분 | 값은 이번에 정한 예측이다(근거 없는 임의 값, 기준선은 idle). 발동하면 처리량 저하의 크기를 결과에 쓰고 결함 분류는 R1이 한다 |
| H8a | Hikari 대기자끼리는 FIFO다: 대기자 30개의 획득 순서가 도착 순서와 같은 시도가 10회 중 9회 이상 | 8회 이하 | 시도별 순서 일치 | FIFO 가정을 버리고 순서가 무엇인지 적는다 |
| H8b | 끼어들기는 드물다: 5회 합산 획득 중 대기 없이 즉시 얻은 것(호출 후 1 ms 이내)을 뺀 획득에서 추월 비율 < 5% | ≥ 5% | 추월 획득 수 / 대기 획득 수 | 끼어들기가 있다고 쓰고 worker 불이익 여부는 H8c로 본다 |
| H8d | 앱 설정(500 ms)에서 폴링형 worker와 쉬지 않는 요청 스레드는 시도당 시간 초과 확률이 비슷하다: 요청 모사 40개일 때 worker/요청 확률 비가 [0.5, 2] 안(요청 확률이 0이면 worker 확률도 0) | 5회 중앙값 비가 그 밖 | 군별 시도·시간 초과·폴링 중단 수 | worker가 요청 스레드보다 연결을 못 얻는다는 뜻이다. 비의 크기를 쓰고, 앱 창의 R1·H7과 함께 보며 R1이 발동하지 않으면 "worker 불이익은 있으나 굶음은 아님"으로 적는다 |
| H8c | 같은 루프를 도는 worker 모사 군과 요청 모사 군은 같은 대우를 받는다: 스레드당 획득 수 비(worker/요청)와 평균 대기 시간 비가 모두 [0.8, 1.25] 안 | 어느 하나라도 밖 | 군별 스레드당 획득 수, 평균·p95 대기 | 앱에서 worker와 요청이 다른 대우를 받는다는 뜻이므로 결과에 크기를 적는다 |

판정 표본은 칸마다 깨끗한 창 n = 10(idle 10, 음성 대조군 3). 0건이면 rule of three 상한(3/n)을 쓴다. 예산 안에 n에 도달하지 못하면 도달한 n으로 규칙을 그대로 적용하되 R2e는 평가하지 않고 "구별 못 함"으로 쓴다(검정력 부족).

## 실행 전 예비 실행의 공개

가설 표를 확정하기 전에 호스트가 오염된 상태에서 H8b·H8c·H8d 하네스를 5~10초 시험 실행했다(값은 쓰지 않고 하네스가 도는지만 봤다). 그 값은 H8b가 발동하고(대기 획득의 약 80%가 추월), 폴링형 worker의 시간 초과 확률이 요청 모사의 한 자릿수 배 이상일 것임을 시사했다. 그래서 H8b·H8d는 발동할 것을 알고 등록한 규칙이며, 임계는 위 표 값(5%, [0.5, 2])에서 바꾸지 않는다. H8d와 `Fifo.java` 모드 d는 이 예비 실행 뒤에 추가했다(측정 실행 전).

## 중간 결과에 따른 추가 사전 등록 (세 번째 커밋 시점, 포화 수준 측정 전)

60·200 req/s 깨끗한 창을 칸마다 4개 돌린 시점의 사실(이 값은 결과 절에도 있다): 호스트가 비어 있으면 200 req/s 30초(6000건)가 풀을 포화시키지 못한다(Hikari 시간 초과 0, 503 0, 켬·끔 2xx 비 1.000). 그래서 R1·H3·H7b가 시험하려던 "포화 중" 조건이 이 두 수준에서는 만들어지지 않았다. 아래는 그 사실을 본 뒤에 정한 추가 등록이며, 위 R1·R2·R2e·H3·H7b 규칙은 바꾸지 않고 포화 수준에 같은 임계로 한 번 더 적용한다.

- 60·200 req/s 칸은 n = 10까지 계속한다(부모 지시로 검증 칸을 줄이지 않음). 이 문서의 앞 판은 "n = 4에서 멈춘다"고 썼으나 철회한다. 첫 구동 구간(n = 4)과 이어 채운 구간을 합쳐 집계한다. 첫 구간의 idle 창 하나는 시드 실패(`seed failed`)로 판정에서 뺐고(깨끗한 idle n = 3), 이어 채우는 구간은 시드를 최대 4번 재시도한다.
- 포화 수준 "sat": 잠금과 깨끗한 창 규칙 아래에서 worker 끔 JVM으로 400, 800, 1600 req/s 30초를 한 창씩 시험해 Hikari 시간 초과 증가분이 처음으로 100 이상인 가장 낮은 수준을 sat으로 고른다(셋 다 아니면 1600). 그 뒤 sat에서 켬·끔 각 깨끗한 창 n = 10을 목표로 반복하고 예산(2026-11-26 22:55)에 닿으면 도달한 n으로 규칙을 적용한다(R2e는 n < 10이면 평가하지 않음). 두 단계는 한 구동기가 잠금 하에서 이어서 돌린다: sat 탐침과 sat 반복을 먼저, 그 뒤 60·200 req/s 채우기. 시험 창 3개는 판정에 쓰지 않는다.
- sat 수준에 R1, R2, R2e, H3, H7a, H7b를 위 표 그대로 적용한다(H7b는 sat에서 idle 대비 ≥ 25%, 기준선은 깨끗한 idle 창). 이전 EXP-106의 오염 창 값은 판정에 쓰지 않는다.
- 같은 문서의 H8b·H8d는 예비 실행으로 발동을 알고 등록했고(위 공개 절), 실제 실행에서도 발동했다. 이 사실이 앱에서 worker가 굶는다는 뜻은 아니며 sat 수준 R1이 그것을 시험한다.

## 두 번째 추가 사전 등록 (sat 수준 5개 창 뒤, "heavy" 수준 측정 전)

sat 탐침 결과(깨끗한 창, 끔 조건): 400·800·1600 req/s를 보내도 앱이 처리한 요청은 약 330~400 req/s에서 포화하고(보낸 만큼 못 받아 k6가 도착을 버림) `hikaricp.connections.timeout`은 세 수준 모두 0이다. 이 요청(가격 필터 목록, 약 88 KB)은 앱 CPU·직렬화가 병목이어서 연결 점유 시간이 짧아 풀이 포화되지 않는다(`pg_stat_statements` 평균 15.7 ms × 약 375 req/s ≈ 연결 6개). 사전 등록한 sat 규칙(첫 100 이상)에 해당 수준이 없어 규칙대로 1600을 sat으로 쓴다(이것은 "요청 과부하이지만 풀 포화는 아님" 칸이고, 그 사실을 결과에 적는다). 풀 포화를 요청 부하만으로 만들 수 있는지 보려고 DB를 더 오래 쥐는 요청을 하나 더 등록한다.

- 수준 "heavy": 요청 `GET /api/products?query=broad&limit=100`(부분 문자열 검색, 40,000개 상품 대상). 끔 조건으로 50, 100, 200, 400 req/s를 한 창씩 시험해 Hikari 시간 초과 증가분이 처음으로 100 이상인 가장 낮은 수준을 heavy로 고른다. 모두 아니면 "요청 부하만으로 풀 포화에 도달하지 못함(호스트·설정에서)"을 결과로 쓰고 heavy 칸은 400 req/s로 같은 규칙을 적용해 돌린다.
- heavy에서 켬·끔 각 깨끗한 창 n = 10을 목표로 하고 R1, R2, R2e, H3, H7a, H7b를 같은 임계로 적용한다.
- 실행 순서(잠금 효율): sat 반복 → 60·200 req/s 채우기 → heavy. 잠금 보유 한도(32분) 때문에 재대기가 끼어도 칸을 줄이지 않는다.

## 통제

- 변경 없음 대조군: worker 끔 조건(같은 JVM 구성·같은 요청·같은 도착률·같은 심은 행).
- 조건 순서를 반복마다 번갈아 호스트 변화가 한 조건에 몰리지 않게 한다.
- 음성 대조군은 H5, 순서 실험 안에는 군을 바꾼 대조가 없으므로(H8c는 두 군이 같은 루프) 대칭 구성 자체가 대조다.

## 대안

| 대안 | 평가 |
|---|---|
| EXP-106 값으로 닫는다 | 모든 창이 오염이고 worker가 일감이 없었다. 기각 |
| 재고 위치 JVM을 띄운다 | 위치 JVM의 DB·CPU 부하가 같은 호스트에 더해져 오염이 늘고, 이 질문은 위치 서비스가 아니라 앱 쪽 풀 경합이라 스텁이 충분하다. 스텁이 응답하는 범위는 위에 적었다 |
| 요청 쓰기 트랜잭션도 섞는다 | 인증·시드가 필요하고 변수가 늘어 R2를 흐린다. 이번에는 읽기 요청 + 쓰는 worker로 제한하고 한계에 적는다 |

## 사전 부검(pre-mortem)과 되돌리기

- 실패 시나리오: (1) 호스트가 계속 오염 상태여서 깨끗한 창이 n = 10에 못 도달한다 → 도달한 n으로 규칙을 적용하고 구별 못 함으로 쓴다(위 규칙). (2) 시드 일감이 창 안에서 소진돼 진행 0이 굶음처럼 보인다 → 잔량이 0인 구간은 굶음에서 제외하고(정의 b의 "잔량") 소진 시각을 적는다. (3) 결제 복구 job이 실패 경로라 처리량이 일감 처리량이 아니다 → 결제는 시도 횟수 합을 진행으로 쓰고 완료율을 주장하지 않는다.
- 되돌리기: 문서만의 변경이면 PR revert, 결함 수정이 들어가면 해당 커밋 revert.

## 실행 기록과 편차 (사전 등록과 달라진 점)

1. **깨끗한 창만 판정에 썼다.** 창마다 직전 `measure-lock.sh status`가 잠금 보유자 `PoolStarvation`, load1 ≤ 12, 다른 라벨 컨테이너 0개이고 끝 직후에도 다른 라벨 컨테이너가 0개일 때만 깨끗하다. 판정에 쓴 깨끗한 창의 시작 load1은 2.7~9.7(중앙값 약 5)이었다. 깨끗하지 못한 창 3개(아래 표)는 판정에서 뺐다. 잠금을 못 잡거나 다른 라벨 스택이 떠 있는 동안(다른 에이전트의 `res`, `t4`, `h3`, `t1` 스택) 구동기는 기다렸고 그 대기 시간은 이 문서의 값에 들어가지 않는다.
2. **앱 쪽 구성**: 앱 jar는 main `201f6361`(이 PR은 운영 코드를 바꾸지 않는다), 재고 위치 API는 JVM이 아니라 20 ms 지연을 주는 스텁이 `reserve`·`restock`에 답한다. 켬 조건의 이벤트 릴레이는 닫힌 Kafka 포트와 `send-timeout=PT1S`로 돈다. 체크아웃 완료 worker와 만료 sweep은 심을 수 있는 일감 경로가 없어 빈 폴링이다(결제 복구 job은 주문이 없는 종료 시도라 `OrderException` 실패 경로로 쓰기를 한다: 결제 "처리량"은 완료율이 아니라 시도 횟수다).
3. **포화 수준이 요청 부하만으로는 만들어지지 않았다.** 60·200 req/s는 풀을 포화시키지 못했고(Hikari 시간 초과 0), 추가 등록한 두 수준도 같았다: 가격 필터 요청은 400·800·1600 req/s를 보내도 앱이 약 330~400 req/s에서 포화하고(서버가 보낸 만큼 못 받아 k6가 도착을 버림) 시간 초과는 0, 부분 문자열 검색 요청은 50·100·200·400 req/s 모두 시간 초과 0이다(탐침 7개 창, 끔 조건). **정정**: 추가 등록 문서에서 "연결 점유 시간이 짧아 풀이 포화되지 않는다"고 쓴 것은 절반만 맞다. 1600 req/s 창에서 Tomcat 스레드 40개 중 평균 30.1개(28.1~30.4)가 `HikariPool.getConnection`에 막혀 있었으나 대기가 500 ms를 넘지 않아 시간 초과는 0이었다. 즉 풀은 대기열로 작동했고 시간 초과 한도에는 닿지 않았다.
4. **실행 구간**: 구동기를 나눠 돌렸다(순서대로: 연결 획득 순서 실험, 음성 대조군, 60·200 req/s 칸마다 4개, sat 탐침과 sat 반복, 60·200 채우기, heavy 탐침과 heavy 반복). 구간 사이에 데이터를 버리거나 바꾼 것은 없고 모든 구간의 창을 합쳐 집계했다. 60 req/s 켬 칸의 n이 11인 것은 채우기 목표(6)를 한 구동 단위가 넘겨서다. 첫 구간의 시드 실패 한 창은 위 표에 있다(이후 시드를 최대 4번 재시도하도록 고쳐 재발하지 않았다).
5. **60·200 req/s n = 4 중단은 철회했다**(사전 등록 절 참고): 모든 칸이 n = 10 이상이다.

## 결과 (깨끗한 창, 30초 창, 값은 중앙값 [최소-최대])

표본: 켬·끔 × 60·200·sat·heavy 각 칸 n = 10(60 req/s 켬 11), idle 10, 음성 대조군 3, 위 수준의 오염 창 3개는 제외. 연결 획득 순서 실험(H8)은 깨끗한 잠금 하에서 각 시도 상태를 확인한 4개 구동이다.

### 요청 쪽: 깨끗한 창, 켬 대 끔

| 수준(req/s) | 조건 | n | 보낸 요청 | 2xx | 503 | 클라이언트 시간 초과 | reset | Hikari getConnection 시간 초과 | load1(창 시작) |
|---|---|---|---|---|---|---|---|---|---|
| 60 | worker 끔 | 10 | 1801 [1800-1801] | 1801 [1800-1801] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 4.6 [3.6-8.8] |
| 60 | worker 켬 | 11 | 1801 [1800-1801] | 1801 [1800-1801] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 4.6 [3.9-6.4] |
| 200 | worker 끔 | 10 | 6001 [6000-6001] | 6001 [5999-6001] | 0 [0-2] | 0 [0-0] | 0 [0-0] | 0 [0-2] | 4.1 [3.0-7.0] |
| 200 | worker 켬 | 10 | 6001 [6000-6001] | 6001 [6000-6001] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 4.1 [2.7-5.5] |
| 1600(보냄) | worker 끔 | 10 | 11798 [10081-12803] | 11798 [10081-12803] | 0 [0-2] | 0 [0-0] | 0 [0-0] | 0 [0-2] | 7.4 [4.2-9.0] |
| 1600(보냄) | worker 켬 | 10 | 11526 [10661-12209] | 11526 [10661-12209] | 0 [0-1] | 0 [0-0] | 0 [0-0] | 0 [0-1] | 7.6 [6.5-9.7] |
| 400(무거운 요청) | worker 끔 | 10 | 12000 [11962-12001] | 12000 [11962-12001] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 0 [0-0] | 5.8 [4.9-6.8] |
| 400(무거운 요청) | worker 켬 | 10 | 12000 [11939-12001] | 12000 [11939-12001] | 0 [0-1] | 0 [0-0] | 0 [0-0] | 0 [0-1] | 5.1 [4.4-6.6] |

### worker 쪽: 켬 조건, 깨끗한 창 (일감은 창마다 유형당 3000행)

| 수준(req/s) | n | 폴링 worker 5개 성공 반복 | 폴링 실패 반복 | 결제 복구 시도(행/s) | 예약 RESERVED(행/s) | 재입고 COMPLETED(행/s) | webhook 처리(행/s) | 군별 최장 무진행(초, 7군 중 최대) | 굶음 창 | Tomcat 스레드 getConnection 대기(덤프 평균) | atlas-* 대기(덤프 평균) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0(idle) | 10 | 118 [117-119] | 0 [0-0] | 9.7 [9.7-9.7] | 23.9 [22.0-24.1] | 25.3 [24.2-25.5] | 0.97 [0.97-1.00] | 1.9 [1.7-2.0] | 0/10 | 0.0 [0.0-0.0] | 0.00 [0.00-0.07] |
| 60 | 11 | 120 [119-121] | 0 [0-0] | 9.7 [9.3-9.7] | 24.2 [23.3-25.0] | 25.5 [24.9-26.8] | 0.97 [0.97-1.00] | 2.0 [1.7-2.0] | 0/11 | 0.0 [0.0-0.1] | 0.00 [0.00-0.00] |
| 200 | 10 | 120 [104-121] | 0 [0-0] | 9.5 [7.6-9.7] | 23.7 [14.4-24.1] | 24.6 [18.5-26.0] | 0.97 [0.90-1.00] | 1.9 [1.6-2.7] | 0/10 | 0.5 [0.0-3.5] | 0.10 [0.00-0.80] |
| 1600(보냄) | 10 | 78 [76-80] | 0 [0-1] | 2.5 [2.4-2.6] | 3.8 [3.5-3.9] | 7.9 [7.3-8.2] | 0.74 [0.71-0.77] | 2.0 [2.0-2.0] | 0/10 | 30.1 [28.1-30.4] | 3.38 [2.94-4.25] |
| 400(무거운 요청) | 10 | 108 [99-117] | 0 [0-0] | 8.3 [6.3-9.7] | 18.7 [13.9-23.5] | 22.5 [17.4-26.4] | 0.93 [0.90-0.97] | 2.0 [1.7-2.7] | 0/10 | 5.6 [0.1-12.9] | 0.73 [0.00-1.27] |

### 음성 대조군 (켬, `product` 표 ACCESS EXCLUSIVE 22초, 60 req/s 20초, 깨끗한 창 3개)

| 창 | 2xx | 503 | Hikari 시간 초과 | Tomcat 대기 덤프(≥1)/전체 | atlas-* 대기 덤프(≥1)/전체 | 폴링 실패 반복 | 군별 최장 무진행(초) |
|---|---|---|---|---|---|---|---|
| neg1 | 17 | 1143 | 1276.0 | 10/10 | 9/10 | 66 | 20.9, 20.9, 16.2, 10.8, 20.9, 19.8, 20.9 |
| neg2 | 27 | 1133 | 1267.0 | 10/10 | 10/10 | 65 | 20.9, 20.9, 16.1, 20.9, 14.8, 19.8, 20.9 |
| neg3 | 29 | 1132 | 1263.0 | 10/10 | 8/10 | 63 | 20.9, 11.2, 15.2, 15.7, 5.0, 14.7, 15.2 |

### 판정에서 뺀 창

| 창 | 이유 | 시작 load1 | 시작 시 다른 라벨 스택 | 끝 시 다른 라벨 스택 |
|---|---|---|---|---|
| u07-on-idle | 시드 실패(DB 재시도 전 판): 일감이 심기지 않음 | 3.4 | 없음 | 없음 |
| s09-on-sat | 다른 라벨 스택과 겹침 | 7.5 | t1x1 | t1x1 |
| s10-off-sat | 다른 라벨 스택과 겹침 | 8.6 | h3x1 | h3x1,t1x1 |

이 표의 두 `sat` 창은 호스트 안에서 다른 에이전트의 스택이 떠 있어 오염으로 분류했다(헤드라인에서 제외). 두 창의 값은 켬 2xx 6958, 클라이언트 시간 초과 446, Hikari 시간 초과 5, 끔 2xx 1797, 클라이언트 시간 초과 3976, Hikari 시간 초과 24로 깨끗한 창과 크게 다르다: 풀 시간 초과와 요청 시간 초과는 호스트가 오염됐을 때만 나왔다.

### 연결 획득 순서 실험 (H8, HikariCP 7.0.2와 실제 PostgreSQL 18.6, 깨끗한 창 4개 구동)

| 실험 | 결과 | 규칙 | 판정 |
|---|---|---|---|
| H8a 대기자 30개의 획득 순서가 도착 순서와 같음(10시도) | 9/10 일치. 어긋난 1시도는 30개 중 인접한 두 개(13번과 14번)가 뒤바뀜 | ≥ 9/10 | 규칙 충족(발동 안 함) |
| H8b 끼어들기(추월) 비율, 5회 | 대기한 획득(전체의 0.8~2.2%)에서 추월 비율 90.1~93.7%(중앙 91.9%). 전체 획득(스레드당 약 600회 중 98% 이상이 1 ms 이내 즉시 획득) | < 5% | **발동**: 끼어들기가 흔하다 |
| H8c worker 모사 10 대 요청 모사 40(같은 루프) 대우 비, 5회 | 스레드당 획득 수 비 0.83, 0.99, 1.17, 1.25, 1.11(중앙 1.11), 평균 대기 비 1.26, 1.01, 0.82, 0.76, 0.88(중앙 0.88) | 두 비가 모두 [0.8, 1.25] | 중앙값으로는 충족, 반복마다 보면 3/5만 충족. 사전 등록이 집계 방식을 적지 않았다: 엄격하게 읽어 **발동**으로 취급하고 "같은 루프의 두 군은 평균적으로 비슷하지만 반복마다 20~25% 흔들림"으로 적는다 |
| H8d 500 ms 상한, 요청 모사 40(쉬지 않음) 대 worker 모사 10(폴링 3회 + 1초 쉼), 5회 | 시도당 시간 초과 확률 요청 2.7~3.6%, worker 31~70%: 비 13.6(중앙), 개별 11.5, 11.9, 13.6, 19.5, 19.7. worker 폴링 중단 129~184회(폴링 시도의 59~88%). 대조(요청 모사 8, 5회): 시간 초과 0, worker 평균 대기 0.6~0.8 ms | [0.5, 2] | **발동**: 쉬지 않는 요청 스레드와 경합하면 폴링 worker가 시도당 한 자릿수 배 이상 더 자주 시간 초과 난다 |

기전(소스와 일치하는 추정이며 이 실험은 결과까지만 보였다): `ConcurrentBag.borrow`는 스레드 지역 목록과 공유 목록 CAS를 먼저 보고 둘 다 실패해야 `waiters`를 올리고 공정 `SynchronousQueue`에서 기다린다. `requite`는 대기자가 있을 때만 `offer`로 넘기고 없으면 반납한 스레드의 지역 목록에 넣는다. 그래서 쉬지 않고 돌려 쓰는 스레드는 거의 대기열을 거치지 않고(98% 이상 즉시 획득), 대기열에 들어간 스레드끼리만 FIFO다(H8a). 폴링 worker는 1초마다 풀 앞에 새로 서므로 대기열에 들어갈 확률이 높다.

## 가설별 판정 (규칙은 사전 등록 그대로, 발동한 규칙은 발동으로 적는다)

| # | 판정 | 근거(개수) |
|---|---|---|
| R1 굶음 | 발동 안 함 | 깨끗한 창 켬 mid·high·sat·heavy 41개에서 10초 이상 무진행 구간을 가진 worker 군 0개(굶음 창 0/41). 군별 최장 무진행 최대 2.7초. sat에서도 모든 군이 진행했다. 음성 대조군(연결 고갈)에서만 군별 10~21초 무진행이 나왔고 그때 군별 무진행은 5~21초이고 2xx는 17~29건이라 R1의 "2xx 지속" 조건에 해당하지 않는다 |
| R2 요청 영향 | 발동 안 함 | 2xx 중앙값 켬/끔 비와 부트스트랩 95% 구간(10000회): 60 req/s 1.000 [0.999, 1.000](n 11 대 10, p=0.64), 200 req/s 1.000 [1.000, 1.000](p=0.72), sat 0.977 [0.947, 1.027](n=10 대 10, p=0.25), heavy 1.000 [0.999, 1.000](p=0.64) |
| R2e 구간이 [0.9, 1.1] 안 | 충족(n=10 칸 모두) | 위 구간이 모두 안에 있다. 단 60·200·heavy는 보낸 만큼 모두 처리돼(포화 없음) 비가 1일 수밖에 없는 칸이므로 "영향 없음"의 증거는 sat 칸뿐이다 |
| H3 포화 창에서 worker도 시간 초과 | **발동**(기계적 적용, 의미는 약함) | 포화 창(Hikari 시간 초과 > 0)이 켬 깨끗한 창 중 sat 2개, heavy 1개뿐이고(각 1~2건), 그 3개 중 폴링 실패가 있는 창은 1개라 20% 초과로 "실패 0"인 창이 있다. 시간 초과 1~2건짜리 창을 포화로 부르는 것이 규칙의 의도에 맞는지는 이 데이터로 가를 수 없다. 실제 고갈(음성 대조군)에서는 3/3 창에서 폴링 실패 63~66 |
| H5 탐지기(음성 대조군) | 발동 안 함(탐지기 유효) | Tomcat 대기 덤프 10/10, 10/10, 10/10(≥ 90%), Hikari 시간 초과 1263~1276, atlas-* 대기 덤프 9/10, 10/10, 8/10(≥ 1), 군별 최장 무진행 최대 20.9초(≥ 15초) |
| H6 스레드 수 | 발동 안 함 | 켬 창 32개 모두 `atlas-scheduler` 4, `payment-recovery` 1, `order-inventory-recovery` 3, `paypal-webhook-retry` 1, `order-inventory-event-relay` 1(합 10), 끔 창 31개 모두 `atlas-scheduler` 4만 |
| H7a 모든 일감 유형이 진행 | 발동 안 함 | 켬 깨끗한 창 41개 모두에서 결제 시도, RESERVED, COMPLETED 재입고, webhook 처리가 늘었다 |
| H7b 처리량 유지 | sat에서 **발동**, 나머지 발동 안 함 | idle 대비 처리량 비(RESERVED/COMPLETED/결제 시도/webhook): 60 req/s 1.01/1.01/1.00/1.00, 200 req/s 0.99/0.98/0.98/1.00, heavy 0.78/0.89/0.86/0.96, sat 0.16/0.31/0.26/0.76. sat의 예약 RESERVED 0.16이 25% 미만 |
| H8a | 발동 안 함 | 9/10 |
| H8b | **발동** | 대기한 획득의 추월 비율 중앙 91.9% |
| H8c | **발동**(엄격한 읽기) | 위 표 |
| H8d | **발동** | 비 중앙 13.6 |

## 주장 표

| 주장 | 증거 | 확신도 |
|---|---|---|
| 깨끗한 호스트에서 요청 부하만으로는 Hikari 시간 초과가 나지 않는다(이 앱·이 요청·이 호스트): 앱이 CPU·직렬화로 먼저 포화한다 | 깨끗한 창 81개(켬·끔 × 4수준, 칸마다 10~11)와 탐침 7개의 Hikari 시간 초과 합계가 대부분 0이고 최대 2 | local-only(이 호스트, 이 요청 두 종류) |
| 복구 worker는 굶지 않는다(어떤 군도 10초 이상 무진행 없음, 모든 일감 유형 진행) | 굶음 창 0/41, 최장 무진행 2.7초 | local-only(깨끗한 창이지만 이 호스트, 스텁, 읽기 요청) |
| worker 켬은 요청 2xx에 영향이 없다 | R2·R2e: 세 칸 비 1.000, sat 0.977 [0.947, 1.027] n=10 | local-only. 포화(sat)에서만 의미 있는 시험이고 그때 구간 폭이 ±5%다 |
| CPU 과부하(sat)에서 worker 처리량은 idle의 16~76%로 떨어지지만 멈추지 않고 실패도 거의 없다 | sat 켬 10창: 예약 3.8/s(idle 23.9), 재입고 7.9/s(25.3), 결제 시도 2.5/s(9.7), webhook 0.74/s(0.97), 폴링 실패 최대 1 | local-only |
| 같은 CPU 과부하에서 Tomcat 스레드 40개 중 평균 30개가 `getConnection` 대기이고 시간 초과는 0이다(풀은 대기열로 작동하나 한도에는 닿지 않음) | sat 켬 10창 덤프 160개 중 getConnection 대기 평균 30.1, 시간 초과 0 | local-only |
| 연결 대기자끼리는 FIFO다(HikariCP 7.0.2) | 30개 대기자 순서 일치 9/10(어긋남은 인접 쌍 하나) | local-only(이 하네스, 실제 PostgreSQL) |
| 끼어들기가 흔하다: 대기한 획득의 약 92%가 더 오래 기다린 호출자를 앞질렀다 | 5회 90.1~93.7% | local-only |
| 쉬지 않는 요청 스레드와 경합하면 폴링형 worker의 시도당 시간 초과 확률이 요청의 약 14배(11.5~19.7배)다(500 ms 상한, 40 대 10) | 5회, 대조 5회 0 | local-only(앱 밖 모형 하네스) |
| 그 불이익이 앱에서 실제 굶음으로 나타나는가 | 앱 창에서는 시간 초과가 나지 않아 시험하지 못했다. 음성 대조군은 요청과 worker가 모두 막힌다 | not confirmed. ✓ 결정(가정): 설정 유지. 재검토 조건: 깨끗한 창에서 켬 조건의 Hikari 시간 초과 증가분이 창당 100 이상이고 같은 창에서 어떤 worker 군이든 10초 이상 무진행 |
| 음성 대조군은 연결 고갈에서 탐지기가 운다 | 3/3 창 | confirmed(3/3 같은 방향, 깨끗한 창, 분산 없음) |
| 스레드 개수 10(켬)/복구 풀 0(끔) | 켬 32, 끔 31 창 | confirmed |
| 풀 포화 때 모든 실패가 reset이 아니라 503/시간 초과로 나타난다 | 깨끗한 창 reset 0(전 창) | confirmed(0건: 깨끗한 창과 오염 창 전부에서 reset 0, 기타 5xx 0) |

## 결정

- ✓ 결정(가정): 설정(Hikari 풀 10, `connection-timeout` 500 ms, Tomcat 40, worker 풀 10 스레드)과 코드는 바꾸지 않는다. 근거: 깨끗한 창 41개에서 굶음 0, 요청 2xx 영향 없음(sat 0.977 [0.947, 1.027]), worker 처리량 저하는 CPU 과부하에서만(최저 16%) 나타나고 실패 없이 느려지는 형태다. 발동한 규칙(H3의 기계적 발동, H7b의 sat, H8b·c·d)은 발동으로 기록했고 아래 재검토 조건으로 연결한다. 결함으로 분류한 것이 없어 수정과 회귀 시험은 만들지 않았다(변이 확인 대상 없음). 이 PR에는 컴파일되는 코드 변경이 없어 `fullcheck.sh`를 돌리지 않았다.
- 재검토 조건(수치, 어느 하나면 같은 하네스를 잠금 아래 load1 ≤ 12·다른 라벨 스택 없음으로 n ≥ 10 다시 돌린다): (1) 깨끗한 창에서 켬의 Hikari 시간 초과 증가분이 창당 100 이상이면서 어떤 worker 군이 10초 이상 무진행, (2) 운영 지표 `atlas.payment.recovery.oldest_due_age_seconds` 또는 `atlas.order.inventory.recovery.oldest_due_age_seconds`가 30초를 60초 넘게 초과, (3) `maximum-pool-size`·Tomcat `threads.max`·worker 풀 크기·`connection-timeout`을 바꾸는 PR, (4) 기존 worker 풀에 새 `@Scheduled` worker 추가, (5) 쓰기 트랜잭션이 요청 경로에서 연결을 오래 쥐는 기능 추가(이 실험이 시험하지 못한 조건).
- 대안과 비교: worker용 별도 연결 풀이나 우선순위 큐는 H8d의 모형 불이익(약 14배)을 줄이겠지만 앱 창에서 불이익이 실제 굶음으로 나타난 증거가 없어 도입하지 않는다(AGENTS.md: 근거 없는 thread pool·구조 도입 금지).

## 한계

- 요청은 인증 없는 읽기(목록 가격 필터 약 88 KB, 부분 문자열 검색 약 88 KB) 두 종류뿐이고 쓰기 트랜잭션으로 연결을 오래 쥐는 요청은 측정하지 않았다. 이 호스트에서는 두 요청 모두 앱 CPU가 풀보다 먼저 포화해 "풀 포화"를 요청 부하로 만들지 못했다. 풀 포화에서의 worker 거동은 음성 대조군(표 잠금)과 모형 하네스(H8)로만 본다.
- 재고 위치 API는 응답 스텁이고(JVM·DB 없음), 결제 복구 job은 실패 경로(주문 없음)다. 체크아웃 완료 worker와 만료 sweep은 일감 없이 빈 폴링이다.
- H3의 판정은 "포화 창"의 정의(시간 초과 > 0)를 기계적으로 적용한 것이라 의미가 약하다.
- H8은 앱이 아니라 같은 HikariCP 버전을 쓰는 모형이다. H8d의 요청 모사는 쉬지 않고 도는 40개로 앱의 Tomcat보다 가혹하다. 기전 문단은 소스를 읽은 추정이다.
- 대부분의 값이 단일 호스트·단일 실행 세션의 값이라 확신도는 local-only다.

## 삭제한 파일의 내용

이 PR은 구동·표집·분석 스크립트와 원자료(`*.jsonl`, `*.tsv`, k6 요약·CSV, 앱 로그)를 저장소에 올리지 않는다(`experiment-artifacts.md`). 방법과 정확한 명령은 아래에, 스크립트 본문은 그 뒤에 옮겼다. 본문은 실행에 쓴 파일에서 이 환경의 경로 문자열만 치환한 것이다(`$W2` 작업 폴더, `$W` EXP-106 작업 폴더에서 가져온 `load.js`, `$REPO` 이 저장소의 worktree, `$JDK25_HOME`, `$COLIMA_SOCK`, `$MEASURE_LOCK` 호스트 잠금 스크립트로 이 저장소에 없음). EXP-106의 `load.js`·`sampler_b.py`와 같은 파일을 쓰되 `sampler.py`는 DB 쪽 행 상태 관측을 더한 판이다. 로컬 경로와 계정명이 남지 않았음을 치환 뒤 검색으로 확인했다.

- 환경: 앱 jar는 `./gradlew :atlas-application:bootJar -x test`(main `201f6361`), JDK 25 `-Xms1g -Xmx1g`, PostgreSQL 18.6 컨테이너(`max_connections=200`, `pg_stat_statements`), 카탈로그는 `load-tests/seed-catalog-read.sql`(sellers=100, products_per_seller=400). 구동 순서: `start-pg.sh`, `stub.py`, 그리고 `run_clean.py <out> <HH:MM> fifo,neg,matrix`(첫 구간), `TOPUP=1 python3 run_sat.py <out> <HH:MM>`(sat 탐침·반복과 채우기), `HEAVY_PATHS='/api/products?query=broad&limit=100' SAT_LEVEL=heavy PROBE_RATES=50,100,200,400 python3 run_sat2.py <out> <HH:MM>`(`run_sat2.py`는 `run_sat.py`에서 수준 이름·탐침 목록·채우기 단계를 환경 변수로 바꾼 사본이라 별도로 옮기지 않았다). 분석: `SEEDFAIL=u07-on-idle python3 analyze2.py <out> > x.tsv`, `python3 agg2.py raw1.tsv raw2.tsv raw3.tsv`, 표는 같은 TSV에서 만든 요약이다. 연결 획득 순서 실험은 앱 jar에서 꺼낸 `HikariCP-7.0.2`·`postgresql-42.7.14` jar로 `java -cp <jars>:<classes> Fifo <jdbcUrl> a 10 | b 5 20 | d 5 30 40 | d 5 30 8`.
- 열 설명(`analyze2.py` TSV): `s200`·`s503`·`to`(클라이언트 5초 시간 초과)·`reset`은 k6 counter, `hk_timeout`은 창 시작·끝 스크레이프의 `hikaricp.connections.timeout` 차, `<worker>:iters/fail/succ`는 `tasks.scheduled.execution` 횟수와 worker 실패 counter 차, `sq:<군>:longest`는 `pg_stat_statements.calls`가 늘지 않은 최장 연속 초, `row:<유형>:per_s`는 행 상태 개수 증가율, `starved_n`은 일감이 남은 채 2xx가 이어진 10초 이상 무진행 구간 수, `http_blocked_mean`·`atlas_blocked_mean`은 덤프당 `HikariPool.getConnection`에 막힌 Tomcat·`atlas-*` 스레드 수 평균, `clean`은 위 깨끗한 창 정의.

### 스크립트 본문

### `load.js`

k6 부하(고정 도착률, EXP-106과 같은 파일).

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

### `start-pg.sh`

PostgreSQL 18.6 컨테이너 기동.

```bash
#!/bin/bash
. $W2/env.sh
docker rm -f $PGC >/dev/null 2>&1
docker run -d --name $PGC --label atlasexp=pool110 -e POSTGRES_USER=atlas -e POSTGRES_PASSWORD=atlas-local-only -e POSTGRES_DB=atlas -p 127.0.0.1:$PGPORT:5432 postgres:18.6 -c max_connections=200 -c shared_preload_libraries=pg_stat_statements >/dev/null
for i in $(seq 1 40); do docker exec $PGC pg_isready -U atlas -d atlas >/dev/null 2>&1 && { docker exec $PGC psql -U atlas -d atlas -qc "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" && echo pg ready; exit 0; }; sleep 1; done
echo pg not ready; exit 1
```

### `start-app.sh`

앱 jar 기동(켬/끔).

```bash
#!/bin/bash
# start-app.sh <on|off> : app jar against the experiment PG and the inventory-location stub. pid -> $W2/app.pid, log -> $W2/app-<arm>.log
. $W2/env.sh
arm=$1; shift
pkill -f "atlas-application.jar --server.port=$APPPORT" 2>/dev/null; sleep 1
COMMON=(--server.port=$APPPORT --management.server.port=$MGMT --spring.profiles.active=observability
 --management.endpoints.web.exposure.include=health,prometheus
 --atlas.order-inventory-reservation.enabled=true --atlas.order-inventory-reservation.api-token=exp110-api-token-0123456789abcdef0123456789
 "--atlas.order-inventory-reservation.location-endpoints=00000000-0000-0000-0000-000000000001=http://127.0.0.1:$STUBPORT"
 --atlas.order-inventory-reservation.service-token=exp110-service-token-0123456789abcdef012345)
if [ "$arm" = on ]; then
  ARM=(--atlas.order-inventory-reservation.event-relay.enabled=true --atlas.order-inventory-reservation.event-relay.send-timeout=PT1S)
else
  ARM=(--atlas.payment.recovery.worker.enabled=false --atlas.order-inventory-reservation.recovery.worker-enabled=false
       --atlas.payment.paypal.webhook-retry.worker.enabled=false
       --atlas.admission.expiry-poll-interval=PT24H --atlas.orders.expiry-poll-interval=PT24H)
fi
export ATLAS_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/atlas?options=-c%20statement_timeout%3D30000%20-c%20idle_in_transaction_session_timeout%3D60000%20-c%20lock_timeout%3D5000"
export ATLAS_DB_USER=atlas ATLAS_DB_PASSWORD=atlas-local-only
export ATLAS_JWT_SIGNING_SECRET=$(printf 'exp110-jwt-secret-0123456789abcdef0123456789' | base64)
export ATLAS_PAYMENT_PROVIDER=disabled SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=1025
nohup java -Xms1g -Xmx1g -jar $REPO/atlas-application/build/libs/atlas-application.jar --server.port=$APPPORT "${COMMON[@]:1}" "${ARM[@]}" "$@" > $W2/app-$arm.log 2>&1 &
echo $! > $W2/app.pid
for i in $(seq 1 90); do
  curl -s -o /dev/null http://127.0.0.1:$MGMT/actuator/health && { echo "ready pid=$(cat $W2/app.pid) after ${i}s"; exit 0; }
  kill -0 $(cat $W2/app.pid) 2>/dev/null || { echo died; tail -20 $W2/app-$arm.log; exit 1; }
  sleep 1
done
echo timeout; exit 1
```

### `stub.py`

재고 위치 API 스텁(20 ms 지연).

```python
#!/usr/bin/env python3
"""Stub of the inventory-location API used by the app's HttpLocationClient: reserve and restock answer after DELAY_MS."""
import json, os, re, time, uuid, datetime
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
DELAY = float(os.environ.get('DELAY_MS', '20')) / 1000.0
PORT = int(os.environ.get('STUBPORT', '47190'))
class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass
    def do_POST(self):
        if 'chunked' in (self.headers.get('Transfer-Encoding') or '').lower():
            while True:
                size = int(self.rfile.readline().strip().split(b';')[0] or b'0', 16)
                if size == 0:
                    self.rfile.readline(); break
                self.rfile.read(size); self.rfile.readline()
        else:
            n = int(self.headers.get('Content-Length') or 0)
            if n: self.rfile.read(n)
        time.sleep(DELAY)
        m = re.search(r'/reservations/([0-9a-f-]{36})/restock', self.path)
        rid = m.group(1) if m else str(uuid.uuid4())
        exp = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=30)).strftime('%Y-%m-%dT%H:%M:%S.%fZ')
        body = json.dumps({'reservationId': rid, 'status': 'RESERVED', 'expiresAt': exp}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        body = b'{"available":1000}'
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
```

### `seed.sql`

창마다 심는 실제 복구 일감.

```sql
-- seed.sql: :n rows of real recovery work per type; run before every window (psql -v n=3000 -f seed.sql)
\set ON_ERROR_STOP on
BEGIN;
TRUNCATE payment_recovery_job, payment_attempt, order_inventory_reservation_workflow, order_inventory_restock, paypal_webhook_retry, order_inventory_event CASCADE;
-- 1) payment recovery: a terminal attempt without owner; the worker claims the job (write), reads, marks NEEDS_REVIEW (write), completes (fenced write)
INSERT INTO payment_attempt (attempt_id, order_id, state, payment_phase, authorization_active, total_won)
SELECT gen_random_uuid(), gen_random_uuid(), 'VOIDED', 'AUTHORIZATION', false, 1000 FROM generate_series(1, :n);
INSERT INTO payment_recovery_job (recovery_job_id, payment_attempt_id, operation_type, status, available_at, first_enqueued_at, retry_window_started_at, updated_at)
SELECT gen_random_uuid(), attempt_id, 'PAYMENT_ATTEMPT', 'PENDING', now() - interval '1 minute', now(), now(), now() FROM payment_attempt;
-- 2) reservation workflow: PENDING workflow with one PENDING location command + item; the coordinator calls the location API (stub) and writes RESERVED
CREATE TEMP TABLE wf AS SELECT gen_random_uuid() AS workflow_id, gen_random_uuid() AS order_id, g FROM generate_series(1, :n) g;
INSERT INTO order_inventory_reservation_workflow (workflow_id, order_id, idempotency_key, workflow_state, attempt_count, next_attempt_at, created_at, updated_at)
SELECT workflow_id, order_id, 'exp110-wf-' || workflow_id, 'PENDING', 0, now() - interval '1 minute', now(), now() FROM wf;
INSERT INTO order_inventory_location_command (workflow_id, fulfillment_location_id, idempotency_key, command_state, updated_at)
SELECT workflow_id, '00000000-0000-0000-0000-000000000001', 'exp110-cmd-' || workflow_id, 'PENDING', now() FROM wf;
INSERT INTO order_inventory_location_item (workflow_id, fulfillment_location_id, sku_id, quantity, seller_id)
SELECT workflow_id, '00000000-0000-0000-0000-000000000001', 'sku-1-1-1', 1, '00000000-0000-0000-0000-0000000000aa' FROM wf;
-- 3) restock: parent workflow already finished (next attempt far in the future), PENDING restock due now; the worker calls the stub (restock) and writes COMPLETED
CREATE TEMP TABLE rs AS SELECT gen_random_uuid() AS workflow_id, gen_random_uuid() AS order_id, gen_random_uuid() AS reservation_id, gen_random_uuid() AS refund_id, g FROM generate_series(1, :n) g;
INSERT INTO order_inventory_reservation_workflow (workflow_id, order_id, idempotency_key, workflow_state, attempt_count, next_attempt_at, created_at, updated_at)
SELECT workflow_id, order_id, 'exp110-rs-' || workflow_id, 'CLAIMED', 0, now() + interval '100 years', now(), now() FROM rs;
INSERT INTO order_inventory_restock (restock_key, fulfillment_location_id, refund_id, order_id, workflow_id, remote_reservation_id, restock_state, attempt_count, next_attempt_at, created_at, updated_at)
SELECT 'exp110-rk-' || workflow_id, '00000000-0000-0000-0000-000000000001', refund_id, order_id, workflow_id, reservation_id, 'PENDING', 0, now() - interval '1 minute', now(), now() FROM rs;
INSERT INTO order_inventory_restock_item (restock_key, fulfillment_location_id, seller_id, sku_id, quantity)
SELECT 'exp110-rk-' || workflow_id, '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa', 'sku-1-1-1', 1 FROM rs;
-- 4) webhook retry: events of an unknown type; the worker claims (write) and settles them
INSERT INTO paypal_webhook_retry (event_id, event_type, resource_id, attempt_count, state, next_attempt_at, created_at)
SELECT 'WH-exp110-' || g, 'EXP110.UNKNOWN.EVENT', 'res-' || g, 0, 'PENDING', now() - interval '1 minute', now() FROM generate_series(1, :n) g;
COMMIT;
```

### `pgs.sql`

DB 쪽 관측(statement 호출 수, 행 상태 개수).

```sql
\pset footer off
\pset title ''
select extract(epoch from clock_timestamp())::numeric(16,3) as t, grp, sum(calls)::numeric as v from (select case when query ~ 'payment_recovery_job' and query ~ 'candidates' then 'sq:payment-claim' when query ~ 'payment_recovery_job' then 'sq:payment-recovery' when query ~ 'paypal_webhook_retry' then 'sq:webhook-retry' when query ~ 'order_inventory_event' then 'sq:event-relay' when query ~ 'order_inventory_reservation_workflow' then 'sq:oi-reservation' when query ~ 'order_inventory_restock' then 'sq:oi-restock' when query ~ 'buyer_checkout' then 'sq:checkout' else null end as grp, calls from pg_stat_statements where query not like '%pg_stat_statements%' and dbid=(select oid from pg_database where datname='atlas')) s where grp is not null group by grp
union all select extract(epoch from clock_timestamp())::numeric(16,3), 'row:pay_attempts', coalesce(sum(attempt_count),0) from payment_recovery_job
union all select extract(epoch from clock_timestamp())::numeric(16,3), 'row:wf_reserved', count(*) from order_inventory_reservation_workflow where workflow_state='RESERVED'
union all select extract(epoch from clock_timestamp())::numeric(16,3), 'row:rs_completed', count(*) from order_inventory_restock where restock_state='COMPLETED'
union all select extract(epoch from clock_timestamp())::numeric(16,3), 'row:wh_left', count(*) from paypal_webhook_retry
order by 2 \watch 1
```

### `sampler.py`

창 표집기(스크레이프 3번 + jcmd 2초 + pgs.sql).

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
pgs = subprocess.Popen(['docker', 'exec', '-i', '-e', 'PGAPPNAME=exp110pgs', 'atlasexp-pool110-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-AtF|', '-q'],
                       stdin=open('$W2/pgs.sql'), stdout=open(out + '.pgs', 'w'), stderr=subprocess.DEVNULL)
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
subprocess.run(['docker', 'exec', 'atlasexp-pool110-pg', 'psql', '-U', 'atlas', '-d', 'atlas', '-Atc', "select pg_terminate_backend(pid) from pg_stat_activity where application_name='exp110pgs'"], capture_output=True)
```

### `run_clean.py`

잠금 연동 구동기(fifo, neg, matrix 단계, 깨끗한 창 판정).

```python
#!/usr/bin/env python3
"""run_clean.py <outdir> <deadline HH:MM>: lock-gated EXP-110 runner.
Phases: fifo (H8 a/b/d with Fifo.java), neg (negative control, n=3), matrix (on/off x mid/high, on idle; adaptive until n clean windows per cell or deadline).
A window is clean iff the status read right before the load shows lock holder PoolStarvation, load1 <= 12 and no labelled container other than pool110,
and the status read right after the load shows no other labelled container. Unclean windows are kept and flagged."""
import sys, os, json, time, subprocess, re, datetime, random
os.environ['JAVA_HOME'] = '$JDK25_HOME'
os.environ['PATH'] = os.environ['JAVA_HOME'] + '/bin:' + os.environ['PATH']
os.environ['DOCKER_HOST'] = 'unix://$COLIMA_SOCK'
OUT = sys.argv[1]
hh, mm = sys.argv[2].split(':')
now = datetime.datetime.now()
DEADLINE = now.replace(hour=int(hh), minute=int(mm), second=0, microsecond=0).timestamp()
os.makedirs(OUT, exist_ok=True)
LOCK = os.environ.get('LOCK_SCRIPT', '$MEASURE_LOCK')
ME = 'PoolStarvation'
APP, MGMT, PGC = 47180, 47181, 'atlasexp-pool110-pg'
N_TARGET = int(os.environ.get('N_TARGET', '10'))
DUR = int(os.environ.get('DUR', '30'))
MAX_HOLD = 32 * 60
CP = '$W2/lib/HikariCP-7.0.2.jar:$W2/lib/postgresql-42.7.14.jar:$W2/lib/slf4j-api-2.0.20.jar:$W2/lib/checker-qual-3.55.1.jar:$W2/fifo-classes'
hold_since = None

def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw).stdout.strip()

def status():
    txt = subprocess.run(['bash', LOCK, 'status'], capture_output=True, text=True).stdout
    holder = re.search(r'held by (\S+)', txt)
    load = re.search(r'load ([0-9.]+)', txt)
    lab = re.search(r'labelled containers:(.*)', txt)
    others = {}
    if lab:
        for n, name in re.findall(r'(\d+)\s+([A-Za-z0-9_-]+)', lab.group(1)):
            if name != 'pool110':
                others[name] = int(n)
    return dict(raw=txt.strip().replace('\n', ' | '), holder=holder.group(1) if holder else None,
                load1=float(load.group(1)) if load else 99.0, others=others)

def clean_now(st, need_load=True):
    return st['holder'] == ME and not st['others'] and (st['load1'] <= 12 or not need_load)

def log(msg):
    print(datetime.datetime.now().strftime('%H:%M:%S'), msg, flush=True)

def acquire():
    global hold_since
    out = subprocess.run(['bash', LOCK, 'acquire', ME, '40'], capture_output=True, text=True).stdout
    hold_since = time.time()
    log('lock: ' + out.strip().splitlines()[-1])

def release():
    global hold_since
    out = subprocess.run(['bash', LOCK, 'release', ME], capture_output=True, text=True).stdout
    hold_since = None
    log('lock: ' + out.strip())

def ensure_clean(max_wait=600):
    """hold the lock and wait for load1 <= 12 and no other labelled stack; re-queue (release + sleep) if it does not clear."""
    global hold_since
    while time.time() < DEADLINE:
        st = status()
        if st['holder'] != ME:
            acquire(); continue
        if hold_since and time.time() - hold_since > MAX_HOLD:
            log('hold limit reached, re-queue'); release(); time.sleep(5); continue
        if clean_now(st):
            return st
        t0 = time.time()
        while time.time() - t0 < max_wait and time.time() < DEADLINE:
            time.sleep(10)
            st = status()
            if clean_now(st):
                return st
        log('not clean for %ds (%s); releasing the lock for others' % (max_wait, st['raw'][:120]))
        release(); time.sleep(60)
    return None

def cpu_line():
    return sh("top -l 2 -s 1 -n 0 | grep 'CPU usage' | tail -1")

def pg_ok():
    return sh("docker exec %s pg_isready -U atlas -d atlas" % PGC).startswith('/var/run') or 'accepting' in sh("docker exec %s pg_isready -U atlas -d atlas" % PGC)

def seed(n=3000):
    for attempt in range(4):
        r = subprocess.run("docker exec -i %s psql -q -v ON_ERROR_STOP=1 -U atlas -d atlas -v n=%d < $W2/seed.sql" % (PGC, n), shell=True, capture_output=True, text=True)
        if r.returncode == 0: return True
        open(OUT + '/seed.err', 'a').write(r.stderr[-400:] + '\n')
        time.sleep(2)
    return False

def k6(rate, dur, summary, csv=None):
    cmd = ['k6', 'run', '--quiet', '-e', 'BASE=http://127.0.0.1:%d' % APP, '-e', 'MODE=open', '-e', 'RATE=%d' % rate, '-e', 'DURATION=%ds' % dur, '--summary-export=' + summary]
    if csv: cmd += ['--out', 'csv=' + csv]
    if os.environ.get('HEAVY_PATHS'): cmd[2:2] = ['-e', 'PATHS=' + os.environ['HEAVY_PATHS']]
    cmd.append('$W/load.js')
    t0 = time.time(); subprocess.run(cmd, capture_output=True); return t0, time.time()

def append(name, rec):
    open('%s/%s' % (OUT, name), 'a').write(json.dumps(rec) + '\n')

def window(unit, arm, level, rate, pid, kind='matrix'):
    tag = '%s-%s-%s' % (unit, arm, level)
    st0 = ensure_clean(120) or status()
    if not seed():
        log('seed failed'); append('seedfail.jsonl', dict(tag=tag)); 
    stop = '%s/%s.stop' % (OUT, tag)
    for f in (stop, stop + '.mark'):
        if os.path.exists(f): os.remove(f)
    sp = subprocess.Popen(['python3', '$W2/sampler.py', str(pid), str(MGMT), str(APP), '%s/%s.samples.jsonl' % (OUT, tag), stop])
    time.sleep(3)
    cpu0 = cpu_line()
    before = status()
    if rate > 0:
        t0, t1 = k6(rate, DUR, '%s/%s.k6.json' % (OUT, tag), '%s/%s.k6.csv' % (OUT, tag))
    else:
        t0 = time.time(); time.sleep(DUR); t1 = time.time()
    after = status()
    open(stop + '.mark', 'w').close()
    time.sleep(10)
    open(stop, 'w').close(); sp.wait(timeout=60)
    clean = clean_now(before) and not after['others'] and before['holder'] == ME and after['holder'] == ME
    rec = dict(kind=kind, tag=tag, unit=unit, arm=arm, level=level, rate=rate, k6_start=t0, k6_end=t1, clean=clean, pid=pid,
               status_before=before['raw'], status_after=after['raw'], load1_before=before['load1'], load1_after=after['load1'],
               others_before=before['others'], others_after=after['others'], cpu_before=cpu0)
    append('windows.jsonl', rec)
    log('window %s clean=%s load1 %.1f -> %.1f others=%s/%s' % (tag, clean, before['load1'], after['load1'], before['others'], after['others']))
    return clean

def start_app(arm):
    r = subprocess.run(['$W2/start-app.sh', arm], capture_output=True, text=True)
    ok = 'ready' in r.stdout
    log('app %s: %s' % (arm, r.stdout.strip().splitlines()[-1] if r.stdout.strip() else 'no output'))
    return ok, (int(open('$W2/app.pid').read()) if ok else None)

def stop_app(pid, name=None, arm=None):
    subprocess.run(['kill', str(pid)]); time.sleep(3)
    if name:
        subprocess.run('cp $W2/app-%s.log %s/%s-%s.app.log' % (arm, OUT, name, arm), shell=True)

def run_fifo():
    log('phase fifo')
    url = 'jdbc:postgresql://127.0.0.1:55433/atlas'
    for mode, args, name in [('a', ['10'], 'fifo-a'), ('b', ['5', '20'], 'fifo-b'), ('d', ['5', '30', '40'], 'fifo-d40'), ('d', ['5', '30', '8'], 'fifo-d8')]:
        st0 = ensure_clean(300)
        if not st0: return
        cpu0 = cpu_line()
        out = subprocess.run('java -cp %s Fifo %s %s %s 2>&1 | grep -v SLF4J' % (CP, url, mode, ' '.join(args)), shell=True, capture_output=True, text=True).stdout
        st1 = status()
        open('%s/%s.txt' % (OUT, name), 'w').write(out)
        append('windows.jsonl', dict(kind='fifo', tag=name, clean=clean_now(st0) and not st1['others'] and st1['holder'] == ME, status_before=st0['raw'], status_after=st1['raw'],
                                     load1_before=st0['load1'], load1_after=st1['load1'], others_before=st0['others'], others_after=st1['others'], cpu_before=cpu0))
        log('fifo %s done clean=%s' % (name, clean_now(st0) and not st1['others']))

def run_neg(n=3):
    log('phase neg')
    ok, pid = start_app('on')
    if not ok: return
    k6(20, 10, OUT + '/warmup.json')
    done = 0; tries = 0
    while done < n and tries < n + 3 and time.time() < DEADLINE:
        tries += 1
        tag = 'neg%d-on-neg-lock' % tries
        st0 = ensure_clean(300)
        if not st0: break
        seed()
        stop = '%s/%s.stop' % (OUT, tag)
        sp = subprocess.Popen(['python3', '$W2/sampler.py', str(pid), str(MGMT), str(APP), '%s/%s.samples.jsonl' % (OUT, tag), stop])
        time.sleep(4)
        cpu0 = cpu_line(); before = status()
        holder = subprocess.Popen(['docker', 'exec', PGC, 'psql', '-U', 'atlas', '-d', 'atlas', '-c', 'BEGIN; LOCK TABLE product IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(22); COMMIT;'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.5)
        t0, t1 = k6(60, 20, '%s/%s.k6.json' % (OUT, tag), None)
        after = status()
        open(stop + '.mark', 'w').close()
        holder.wait(timeout=40); time.sleep(10)
        open(stop, 'w').close(); sp.wait(timeout=60)
        clean = clean_now(before) and not after['others']
        append('windows.jsonl', dict(kind='neg', tag=tag, unit='neg%d' % tries, arm='on', level='neg-lock', rate=60, k6_start=t0, k6_end=t1, clean=clean, pid=pid,
                                     status_before=before['raw'], status_after=after['raw'], load1_before=before['load1'], load1_after=after['load1'],
                                     others_before=before['others'], others_after=after['others'], cpu_before=cpu0))
        log('neg %s clean=%s' % (tag, clean))
        if clean: done += 1
    stop_app(pid, 'neg%d' % tries, 'on')

def clean_counts():
    c = {}
    if os.path.exists(OUT + '/windows.jsonl'):
        for l in open(OUT + '/windows.jsonl'):
            w = json.loads(l)
            if w['kind'] == 'matrix' and w['clean']:
                c[(w['arm'], w['level'])] = c.get((w['arm'], w['level']), 0) + 1
    return c

def run_matrix():
    log('phase matrix')
    unit = int(os.environ.get('UNIT_BASE', '0'))
    while time.time() < DEADLINE - 150:
        c = clean_counts()
        T = {('on', 'mid'): 6, ('on', 'high'): 6, ('off', 'mid'): 6, ('off', 'high'): 6, ('on', 'idle'): 7} if os.environ.get('TOPUP') else {k: N_TARGET for k in [('on', 'mid'), ('on', 'high'), ('off', 'mid'), ('off', 'high'), ('on', 'idle')]}
        need = {k: T[k] - c.get(k, 0) for k in T}
        if all(v <= 0 for v in need.values()):
            log('all cells reached n=%d clean' % N_TARGET); return
        unit += 1
        # arm with the larger remaining need among mid/high; idle rides along on 'on' units while needed
        n_on = need[('on', 'mid')] + need[('on', 'high')]
        n_off = need[('off', 'mid')] + need[('off', 'high')]
        arm = 'on' if (n_on > n_off or (n_on == n_off and unit % 2 == 1)) else 'off'
        levels = [('mid', 60), ('high', 200)]
        if unit % 2 == 0: levels.reverse()
        if arm == 'on' and need[('on', 'idle')] > 0: levels = [('idle', 0)] + levels
        if ensure_clean(600) is None: return
        ok, pid = start_app(arm)
        if not ok: continue
        k6(20, 10, OUT + '/warmup.json')
        for level, rate in levels:
            if time.time() > DEADLINE - 90: break
            window('u%02d' % unit, arm, level, rate, pid)
        stop_app(pid, 'u%02d' % unit, arm)

if __name__ == '__main__':
    phases = (sys.argv[3] if len(sys.argv) > 3 else 'fifo,neg,matrix').split(',')
    log('start; deadline %s; phases %s' % (sys.argv[2], phases))
    try:
        if 'fifo' in phases: run_fifo()
        if 'neg' in phases: run_neg()
        if 'matrix' in phases: run_matrix()
    finally:
        subprocess.run('pkill -f "atlas-application.jar --server.port=%d"' % APP, shell=True)
        if hold_since is not None or status()['holder'] == ME: release()
        log('runner finished')
```

### `run_sat.py`

포화 수준 단계(탐침 후 켬·끔 반복).

```python
#!/usr/bin/env python3
"""run_sat.py <outdir> <deadline HH:MM>: EXP-110 saturation-level phase. Probe (workers off) 400/800/1600 req/s, pick the lowest with hk_timeout >= 100, then on/off units at that rate."""
import sys, json, time, subprocess
sys.path.insert(0, '$W2')
import run_clean as rc
OUT = rc.OUT

def probe_timeouts(tag):
    r = subprocess.run(['python3', '-c', '''
import json,sys
tag=sys.argv[1]; raw=sys.argv[2]
recs=[json.loads(l) for l in open(f"{raw}/{tag}.samples.jsonl")]
sc=sorted([r for r in recs if r.get("kind") in ("scrape_start","scrape_end","scrape_final") and r.get("m")],key=lambda r:r["t"])
a=next((r for r in sc if r["kind"]=="scrape_start"),None); b=next((r for r in sc if r["kind"]=="scrape_end"),None) or next((r for r in sc if r["kind"]=="scrape_final"),None)
print(b["m"].get("hikaricp_connections_timeout_total",0)-a["m"].get("hikaricp_connections_timeout_total",0) if a and b else -1)
''', tag, OUT], capture_output=True, text=True)
    try: return float(r.stdout.strip())
    except Exception: return -1

def run_sat():
    rc.log('phase sat probe')
    if rc.ensure_clean(900) is None: return
    ok, pid = rc.start_app('off')
    if not ok: return
    subprocess.run(['$W/../pool2/../pool2/start-app.sh', 'off'], capture_output=True) if False else None
    rc.k6(20, 10, OUT + '/warmup.json')
    chosen = 1600
    for rate in (400, 800, 1600):
        rc.window('probe', 'off', 'p%d' % rate, rate, pid, kind='probe')
        hk = probe_timeouts('probe-off-p%d' % rate)
        rc.log('probe %d hk_timeout=%s' % (rate, hk))
        rc.append('probe.jsonl', dict(rate=rate, hk_timeout=hk))
        if hk >= 100:
            chosen = rate; break
    rc.stop_app(pid, 'probe', 'off')
    rc.log('sat level = %d' % chosen)
    open(OUT + '/sat_level.txt', 'w').write(str(chosen))
    unit = 0
    def counts():
        c = {}
        for l in open(OUT + '/windows.jsonl'):
            w = json.loads(l)
            if w['kind'] == 'matrix' and w['clean'] and w['level'] == 'sat':
                c[w['arm']] = c.get(w['arm'], 0) + 1
        return c
    while time.time() < rc.DEADLINE - 120:
        c = counts()
        if c.get('on', 0) >= rc.N_TARGET and c.get('off', 0) >= rc.N_TARGET:
            rc.log('sat n reached'); break
        unit += 1
        arm = 'on' if (c.get('on', 0) < c.get('off', 0) or (c.get('on', 0) == c.get('off', 0) and unit % 2 == 1)) else 'off'
        if rc.ensure_clean(600) is None: return
        ok, pid = rc.start_app(arm)
        if not ok: continue
        rc.k6(20, 10, OUT + '/warmup.json')
        rc.window('s%02d' % unit, arm, 'sat', chosen, pid)
        rc.stop_app(pid, 's%02d' % unit, arm)
        # same JVM would reuse a warm pool; a fresh JVM per window keeps windows comparable to the matrix phase
    rc.log('sat phase done')

try:
    run_sat()
    if rc.time.time() < rc.DEADLINE - 150:
        rc.run_matrix()
finally:
    subprocess.run('pkill -f "atlas-application.jar --server.port=%d"' % rc.APP, shell=True)
    if rc.status()['holder'] == rc.ME: rc.release()
    rc.log('sat runner finished')
```

### `Fifo.java`

H8 연결 획득 순서 실험(HikariCP 7.0.2 + 실제 PostgreSQL).

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** EXP-110 H8: acquisition order of HikariCP 7.0.2 against real PostgreSQL. Usage: java Fifo.java <jdbcUrl> a|b */
public class Fifo {
    static HikariDataSource pool(String url, long timeoutMs) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url); c.setUsername("atlas"); c.setPassword("atlas-local-only");
        c.setMaximumPoolSize(10); c.setMinimumIdle(10); c.setConnectionTimeout(timeoutMs);
        return new HikariDataSource(c);
    }

    // (a) 30 waiters arrive 25 ms apart while all 10 connections are held; released one per 50 ms
    static boolean trialA(String url) throws Exception {
        try (HikariDataSource ds = pool(url, 60_000)) {
            Thread.sleep(500);
            List<Connection> held = new ArrayList<>();
            for (int i = 0; i < 10; i++) held.add(ds.getConnection());
            int waiters = 30;
            ConcurrentLinkedQueue<Integer> got = new ConcurrentLinkedQueue<>();
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < waiters; i++) {
                final int id = i;
                Thread t = new Thread(() -> {
                    try (Connection c = ds.getConnection()) { got.add(id); Thread.sleep(200); } catch (Exception e) { got.add(-1); }
                });
                t.start(); ts.add(t);
                Thread.sleep(25);
            }
            // each released connection goes to the oldest waiter; the waiter holds it 200 ms so it is not recycled to the next waiter
            for (Connection c : held) { c.close(); Thread.sleep(50); }
            for (Thread t : ts) t.join();
            List<Integer> order = new ArrayList<>(got);
            boolean ok = true;
            for (int i = 0; i < order.size(); i++) if (order.get(i) != i) ok = false;
            if (!ok) System.out.println("  order=" + order);
            return ok;
        }
    }

    record Ev(long callNs, long gotNs, boolean worker, String thread) {}

    // (b)+(c) 50 looping threads (10 worker-like, 40 request-like), identical loop: getConnection, 5 ms pg_sleep, close
    static void trialB(String url, int rep, int seconds) throws Exception {
        try (HikariDataSource ds = pool(url, 30_000)) {
            Thread.sleep(500);
            int nw = 10, nr = 40;
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            ConcurrentLinkedQueue<Ev> evs = new ConcurrentLinkedQueue<>();
            AtomicInteger errors = new AtomicInteger();
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < nw + nr; i++) {
                final boolean worker = i < nw;
                Thread t = new Thread(() -> {
                    while (System.nanoTime() < end) {
                        long call = System.nanoTime();
                        try (Connection c = ds.getConnection()) {
                            long got = System.nanoTime();
                            evs.add(new Ev(call, got, worker, Thread.currentThread().getName()));
                            try (Statement s = c.createStatement()) { s.execute("select pg_sleep(0.005)"); }
                        } catch (Exception e) { errors.incrementAndGet(); }
                    }
                }, worker ? "worker-" + i : "request-" + i);
                t.start(); ts.add(t);
            }
            for (Thread t : ts) t.join();
            List<Ev> all = new ArrayList<>(evs);
            // overtakes: for each acquisition, count older callers (call earlier by > 1 ms) that were still waiting (got later)
            all.sort(Comparator.comparingLong(Ev::callNs));
            int n = all.size();
            // waiting acquisitions only (waited > 1 ms)
            long waited = all.stream().filter(e -> e.gotNs - e.callNs > 1_000_000L).count();
            // Fenwick tree over ranks of gotNs to count, for each event a, how many events b with call_b < call_a - 1ms have got_b > got_a
            long[] gots = all.stream().mapToLong(Ev::gotNs).sorted().toArray();
            int[] bit = new int[n + 2];
            long overtakes = 0, overtakingAcq = 0;
            int ptr = 0;
            for (int i = 0; i < n; i++) {
                Ev a = all.get(i);
                while (ptr < i && all.get(ptr).callNs < a.callNs - 1_000_000L) {
                    int r = Arrays.binarySearch(gots, all.get(ptr).gotNs) + 1;
                    for (int x = r; x <= n; x += x & -x) bit[x]++;
                    ptr++;
                }
                int ra = Arrays.binarySearch(gots, a.gotNs) + 1;
                int le = 0; for (int x = ra; x > 0; x -= x & -x) le += bit[x];
                int older = ptr - le; // older callers that got after a
                if (older > 0 && a.gotNs - a.callNs > 1_000_000L) { overtakingAcq++; overtakes += older; }
            }
            Map<String, long[]> per = new TreeMap<>();
            for (Ev e : all) { long[] v = per.computeIfAbsent(e.thread(), k -> new long[2]); v[0]++; if (e.gotNs - e.callNs > 1_000_000L) v[1]++; }
            long[] cnt = per.values().stream().mapToLong(v -> v[0]).sorted().toArray();
            long[] fast = per.values().stream().mapToLong(v -> v[0] - v[1]).sorted().toArray();
            System.out.printf("T rep=%d per-thread acquisitions min=%d p25=%d median=%d p75=%d max=%d | instant(<1ms) per thread min=%d median=%d max=%d | threads with >90%% instant=%d%n",
                    rep, cnt[0], cnt[cnt.length / 4], cnt[cnt.length / 2], cnt[cnt.length * 3 / 4], cnt[cnt.length - 1], fast[0], fast[fast.length / 2], fast[fast.length - 1],
                    per.values().stream().filter(v -> v[0] > 0 && (v[0] - v[1]) * 10 > v[0] * 9).count());
            double[] wW = all.stream().filter(Ev::worker).mapToDouble(e -> (e.gotNs - e.callNs) / 1e6).sorted().toArray();
            double[] wR = all.stream().filter(e -> !e.worker).mapToDouble(e -> (e.gotNs - e.callNs) / 1e6).sorted().toArray();
            long cw = wW.length, cr = wR.length;
            double meanW = Arrays.stream(wW).average().orElse(0), meanR = Arrays.stream(wR).average().orElse(0);
            System.out.printf("B rep=%d acquisitions=%d waiting_acq=%d overtaking_acq=%d overtaken_pairs=%d overtake_frac=%.4f errors=%d%n",
                    rep, n, waited, overtakingAcq, overtakes, waited == 0 ? 0.0 : (double) overtakingAcq / waited, errors.get());
            System.out.printf("C rep=%d worker: acq/thread=%.1f mean_wait_ms=%.2f p95=%.2f | request: acq/thread=%.1f mean_wait_ms=%.2f p95=%.2f | ratio acq/thread=%.3f ratio mean_wait=%.3f%n",
                    rep, (double) cw / nw, meanW, wW[(int) (wW.length * 0.95)], (double) cr / nr, meanR, wR[(int) (wR.length * 0.95)],
                    ((double) cw / nw) / ((double) cr / nr), meanW / meanR);
        }
    }


    // (d) 500 ms timeout like the app: nreq tight-loop request-like threads vs 10 poll-like worker threads (3 x [getConnection, 5 ms, close], then sleep 1 s)
    static void trialD(String url, int rep, int seconds, int nreq) throws Exception {
        try (HikariDataSource ds = pool(url, 500)) {
            Thread.sleep(500);
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            AtomicLong reqOk = new AtomicLong(), reqTo = new AtomicLong(), wOk = new AtomicLong(), wTo = new AtomicLong();
            AtomicLong pollsDone = new AtomicLong(), pollsAborted = new AtomicLong();
            DoubleAdder reqWait = new DoubleAdder(), wWait = new DoubleAdder();
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < nreq; i++) {
                Thread t = new Thread(() -> {
                    while (System.nanoTime() < end) {
                        long call = System.nanoTime();
                        try (Connection c = ds.getConnection()) {
                            reqWait.add((System.nanoTime() - call) / 1e6); reqOk.incrementAndGet();
                            try (Statement s = c.createStatement()) { s.execute("select pg_sleep(0.005)"); }
                        } catch (Exception e) { reqTo.incrementAndGet(); reqWait.add((System.nanoTime() - call) / 1e6); }
                    }
                }, "request-" + i);
                t.start(); ts.add(t);
            }
            for (int i = 0; i < 10; i++) {
                Thread t = new Thread(() -> {
                    while (System.nanoTime() < end) {
                        boolean aborted = false;
                        for (int k = 0; k < 3 && !aborted; k++) {
                            long call = System.nanoTime();
                            try (Connection c = ds.getConnection()) {
                                wWait.add((System.nanoTime() - call) / 1e6); wOk.incrementAndGet();
                                try (Statement s = c.createStatement()) { s.execute("select pg_sleep(0.005)"); }
                            } catch (Exception e) { wTo.incrementAndGet(); wWait.add((System.nanoTime() - call) / 1e6); aborted = true; }
                        }
                        if (aborted) pollsAborted.incrementAndGet(); else pollsDone.incrementAndGet();
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    }
                }, "worker-" + i);
                t.start(); ts.add(t);
            }
            for (Thread t : ts) t.join();
            long ra = reqOk.get() + reqTo.get(), wa = wOk.get() + wTo.get();
            double pr = ra == 0 ? 0 : (double) reqTo.get() / ra, pw = wa == 0 ? 0 : (double) wTo.get() / wa;
            System.out.printf("D rep=%d nreq=%d request: attempts=%d timeouts=%d p_timeout=%.4f mean_wait_ms=%.1f | worker: attempts=%d timeouts=%d p_timeout=%.4f mean_wait_ms=%.1f polls_done=%d polls_aborted=%d | ratio p_timeout worker/request=%s%n",
                    rep, nreq, ra, reqTo.get(), pr, reqWait.sum() / Math.max(1, ra), wa, wTo.get(), pw, wWait.sum() / Math.max(1, wa), pollsDone.get(), pollsAborted.get(),
                    pr == 0 ? "n/a" : String.format("%.2f", pw / pr));
        }
    }

    public static void main(String[] a) throws Exception {
        String url = a[0];
        if (a[1].equals("a")) {
            int ok = 0, trials = Integer.parseInt(a[2]);
            for (int i = 1; i <= trials; i++) { boolean r = trialA(url); System.out.println("A trial=" + i + " in_order=" + r); if (r) ok++; }
            System.out.println("A summary in_order=" + ok + "/" + trials);
        } else if (a[1].equals("d")) {
            int reps = Integer.parseInt(a[2]);
            for (int i = 1; i <= reps; i++) trialD(url, i, Integer.parseInt(a[3]), Integer.parseInt(a[4]));
        } else {
            int reps = Integer.parseInt(a[2]);
            for (int i = 1; i <= reps; i++) trialB(url, i, Integer.parseInt(a[3]));
        }
    }
}
```

### `analyze2.py`

창별 집계(TSV).

```python
#!/usr/bin/env python3
"""analyze2.py <rawdir>: one TSV row per window of windows.jsonl (matrix and neg kinds)."""
import sys, json, os, re, csv, statistics, collections
raw = sys.argv[1]
W = [json.loads(l) for l in open(f'{raw}/windows.jsonl')]
W = [w for w in W if w['kind'] in ('matrix', 'neg')]
SEEDFAIL = set(os.environ.get('SEEDFAIL', '').split(','))
STALL = 3.0
BIG = 10.0
POLL = {'payment-recovery': ('ex|PaymentRecoveryWorker.poll', ['fl|atlas_payment_recovery_poll_failures_total|']),
        'oi-finalizer': ('ex|CheckoutFinalizationWorker.poll', ['fl|atlas_order_inventory_recovery_polls_total|checkout-finalization']),
        'oi-reservation': ('ex|LocationReservationWorker.poll', ['fl|atlas_order_inventory_recovery_polls_total|reservation']),
        'oi-restock': ('ex|LocationRestockWorker.poll', ['fl|atlas_order_inventory_recovery_polls_total|restock']),
        'webhook-retry': ('ex|PayPalWebhookRetryWorker.retryDueEvents', ['fl|atlas_paypal_webhook_retry_runs_total|'])}
RELAY = ('ex|ReservationEventWorker.poll', ['fl|atlas_order_inventory_event_relay_failed_total|'])
SQ = ['payment-claim', 'payment-recovery', 'webhook-retry', 'event-relay', 'oi-reservation', 'oi-restock', 'checkout']

def k6val(d, name, f='count'):
    return d.get('metrics', {}).get(name, {}).get(f, 0) or 0

def gaps_of(series, t0, t1, backlog=None):
    """series: [(t, v)] sorted. returns (longest gap, sum of gaps > STALL, list of (a, b) gaps >= BIG)."""
    ser = [x for x in series if t0 <= x[0] <= t1]
    if len(ser) < 2:
        return None
    last = t0; prev = ser[0][1]; gaps = []
    for t, v in ser[1:]:
        if v != prev:
            gaps.append((last, t)); last = t; prev = v
    gaps.append((last, t1))
    lens = [b - a for a, b in gaps]
    return max(lens), sum(x for x in lens if x > STALL), [(a, b) for a, b in gaps if b - a >= BIG]

def grp_of_thread(name):
    if 'exec-' in name: return 'http'
    for k, g in (('ment-recovery', 'payment'), ('payment-recovery', 'payment'), ('tory-recovery', 'oi-recovery'), ('inventory-recovery', 'oi-recovery'), ('webhook', 'webhook'),
                 ('event-relay', 'relay'), ('scheduler', 'scheduler')):
        if k in name: return g
    return 'other'

rows = []
for w in W:
    tag = w['tag']; t0, t1 = w['k6_start'], w['k6_end']
    row = dict(kind=w['kind'], tag=tag, unit=w.get('unit'), arm=w['arm'], level=w['level'], rate=w['rate'], clean=int(w['clean'] and w['tag'] not in SEEDFAIL), seedfail=int(w['tag'] in SEEDFAIL),
               load1_before=w['load1_before'], load1_after=w['load1_after'], others_before=','.join(f'{k}x{v}' for k, v in w['others_before'].items()),
               others_after=','.join(f'{k}x{v}' for k, v in w['others_after'].items()))
    kp = f'{raw}/{tag}.k6.json'
    if os.path.exists(kp):
        d = json.load(open(kp))
        row.update(reqs=k6val(d, 'http_reqs'), s200=k6val(d, 's200'), s503=k6val(d, 's503'), s5xx=k6val(d, 's5xx_other'), reset=k6val(d, 's0_reset'),
                   to=k6val(d, 's0_timeout'), oth=k6val(d, 's0_other'), dropped=k6val(d, 'dropped_iterations'))
    else:
        row.update(reqs=0, s200=0, s503=0, s5xx=0, reset=0, to=0, oth=0, dropped=0)
    recs = [json.loads(l) for l in open(f'{raw}/{tag}.samples.jsonl')]
    sc = sorted([r for r in recs if r.get('kind') in ('scrape_start', 'scrape_end', 'scrape_final') and r.get('m')], key=lambda r: r['t'])
    first = next((r for r in sc if r['kind'] == 'scrape_start'), None)
    last = next((r for r in sc if r['kind'] == 'scrape_end'), None) or next((r for r in sc if r['kind'] == 'scrape_final'), None)
    if first and last:
        a, b = first['m'], last['m']
        row['hk_timeout'] = b.get('hikaricp_connections_timeout_total', 0) - a.get('hikaricp_connections_timeout_total', 0)
        for k, (ek, fk) in list(POLL.items()) + [('event-relay', RELAY)]:
            ex = b.get(ek, 0) - a.get(ek, 0)
            fl = sum(b.get(f, 0) - a.get(f, 0) for f in fk)
            row[f'{k}:iters'] = ex; row[f'{k}:fail'] = fl; row[f'{k}:succ'] = ex - fl
    # DB-side series
    pg = collections.defaultdict(list)
    pf = f'{raw}/{tag}.samples.jsonl.pgs'
    if os.path.exists(pf):
        for ln in open(pf):
            p = ln.strip().split('|')
            if len(p) == 3:
                try: pg[p[1]].append((float(p[0]), float(p[2])))
                except ValueError: pass
    big = []
    for g in SQ:
        gp = gaps_of(pg.get('sq:' + g, []), t0, t1)
        if gp:
            row[f'sq:{g}:longest'] = round(gp[0], 1); row[f'sq:{g}:stall_s'] = round(gp[1], 1)
            for a, b in gp[2]: big.append(('sq:' + g, a, b))
    # row progress and backlog
    prog = {}
    for name in ('pay_attempts', 'wf_reserved', 'rs_completed', 'wh_left'):
        ser = [x for x in pg.get('row:' + name, []) if t0 <= x[0] <= t1]
        if len(ser) >= 2:
            delta = abs(ser[-1][1] - ser[0][1])
            row[f'row:{name}:delta'] = delta
            row[f'row:{name}:per_s'] = round(delta / (ser[-1][0] - ser[0][0]), 2)
            gp = gaps_of(pg['row:' + name], t0, t1)
            row[f'row:{name}:longest'] = round(gp[0], 1)
            fin = ser[-1][1]
            # backlog remains if the type did not reach the seeded total (3000) by the window end
            remaining = (3000 - fin) if name in ('wf_reserved', 'rs_completed') else (fin if name == 'wh_left' else 1)
            row[f'row:{name}:backlog_at_end'] = remaining
            for a, b in gp[2]:
                if remaining > 0: big.append(('row:' + name, a, b))
    # 2xx during each >=10 s gap
    per = collections.Counter()
    cp = f'{raw}/{tag}.k6.csv'
    if os.path.exists(cp):
        with open(cp, newline='') as fh:
            for r in csv.DictReader(fh):
                if r['metric_name'] == 'http_reqs' and r['status'] == '200':
                    per[int(float(r['timestamp']))] += 1
        row['sec_zero_2xx'] = sum(1 for s in range(int(t0), int(t1)) if per[s] == 0) if w['rate'] > 0 else ''
    starv = []
    if w['arm'] != 'on':
        big = []
    for g, a, b in big:
        n2 = sum(v for s, v in per.items() if a - 0.5 <= s <= b + 0.5)
        starv.append((g, round(a - t0, 1), round(b - t0, 1), n2))
    row['gaps_ge10'] = len(big)
    row['starved'] = ';'.join(f'{g}[{a}-{b}s,2xx={n}]' for g, a, b, n in starv if n > 0 and w['rate'] > 0)
    row['starved_n'] = sum(1 for g, a, b, n in starv if n > 0 and w['rate'] > 0)
    # dumps
    dumps = [r['dump']['threads'] for r in recs if r.get('dump') and t0 <= r['t'] <= t1]
    row['n_dumps'] = len(dumps)
    def blocked(sel):
        return [sum(v['getconn'] for p, v in t.items() if sel(p)) for t in dumps]
    app = blocked(lambda p: p == 'http-app'); atl = blocked(lambda p: p.startswith('atlas-'))
    row['http_blocked_mean'] = round(statistics.mean(app), 1) if app else ''
    row['atlas_blocked_mean'] = round(statistics.mean(atl), 2) if atl else ''
    row['dumps_http_ge1'] = sum(1 for x in app if x >= 1)
    row['dumps_atlas_ge1'] = sum(1 for x in atl if x >= 1)
    if dumps:
        t = dumps[-1]
        row['thr'] = ','.join(f"{p.replace('atlas-', '').replace('order-inventory-', 'oi-')}={v['n']}" for p, v in sorted(t.items()) if p.startswith('atlas-'))
    # pool-timeout log lines by thread group (JPA path only: hibernate logs "Connection is not available")
    lp = f"{raw}/{w['unit']}-{w['arm']}.app.log"
    if os.path.exists(lp):
        cnt = collections.Counter()
        import datetime
        for ln in open(lp, errors='replace'):
            if 'Connection is not available' not in ln: continue
            m = re.match(r'(\d{4}-\d\d-\d\dT[\d:.]+)\+09:00\s+\w+\s+\d+ --- \[atlas\] \[([^\]]*)\]', ln)
            if not m: continue
            ts = datetime.datetime.fromisoformat(m.group(1) + '+09:00').timestamp()
            if t0 <= ts <= t1 + 2: cnt[grp_of_thread(m.group(2).strip())] += 1
        row['log_timeouts'] = json.dumps(dict(cnt), sort_keys=True)
    rows.append(row)
cols = []
for r in rows:
    for c in r:
        if c not in cols: cols.append(c)
print('\t'.join(cols))
for r in rows: print('\t'.join(str(r.get(c, '')) for c in cols))
```

### `agg2.py`

칸별 집계, 부트스트랩, 순열 검정.

```python
#!/usr/bin/env python3
"""agg2.py <tsv> [--md]: per-cell medians over clean windows, R2 (ratio + bootstrap CI + exact MWU), R1/H7 counts, H3, H5, H6, H7b."""
import sys, csv, statistics, itertools, random, json
rows = []
for p in sys.argv[1:]:
    rows += list(csv.DictReader(open(p), delimiter='\t'))
LV = [l for l in ('mid', 'high', 'sat', 'heavy') if any(r['level'] == l for r in rows)]
def f(x):
    try: return float(x)
    except Exception: return None
def vals(rs, c):
    return [f(r.get(c)) for r in rs if f(r.get(c)) is not None]
def med(rs, c):
    v = vals(rs, c)
    return statistics.median(v) if v else None
def fmt(x, nd=0):
    return '-' if x is None else (f'{x:.{nd}f}')
def cell(rs, c, nd=0):
    v = vals(rs, c)
    return '-' if not v else f'{fmt(statistics.median(v), nd)} [{fmt(min(v), nd)}-{fmt(max(v), nd)}]'
def mwu_p(a, b):
    n, m = len(a), len(b)
    allv = sorted(a + b)
    def rank_sum(x):
        # average ranks
        ranks = {}
        i = 0
        while i < len(allv):
            j = i
            while j + 1 < len(allv) and allv[j + 1] == allv[i]: j += 1
            ranks[allv[i]] = (i + j) / 2 + 1; i = j + 1
        return sum(ranks[v] for v in x)
    r0 = rank_sum(a); mu = n * (n + m + 1) / 2; d0 = abs(r0 - mu)
    rk = {}
    i = 0
    while i < len(allv):
        j = i
        while j + 1 < len(allv) and allv[j + 1] == allv[i]: j += 1
        rk[i] = (i + j) / 2 + 1
        rk.update({k: (i + j) / 2 + 1 for k in range(i, j + 1)}); i = j + 1
    rank_list = [rk[i] for i in range(n + m)]
    cnt = tot = 0
    if n + m > 22:
        random.seed(1)
        for _ in range(200000):
            idx = random.sample(range(n + m), n)
            tot += 1
            if abs(sum(rank_list[i] for i in idx) - mu) >= d0 - 1e-9: cnt += 1
        return cnt / tot
    for idx in itertools.combinations(range(n + m), n):
        tot += 1
        if abs(sum(rank_list[i] for i in idx) - mu) >= d0 - 1e-9: cnt += 1
    return cnt / tot
def boot_ratio(a, b, B=10000):
    random.seed(7)
    out = []
    for _ in range(B):
        ma = statistics.median(random.choices(a, k=len(a))); mb = statistics.median(random.choices(b, k=len(b)))
        if mb > 0: out.append(ma / mb)
    out.sort()
    return out[int(0.025 * len(out))], out[int(0.975 * len(out))]
POLL = ['payment-recovery', 'oi-finalizer', 'oi-reservation', 'oi-restock', 'webhook-retry']
def poll_sum(r, suf):
    return sum(f(r.get(f'{k}:{suf}')) or 0 for k in POLL)
def sel(rs, arm, level, clean=True):
    return [r for r in rs if r['kind'] == 'matrix' and r['arm'] == arm and r['level'] == level and (r['clean'] == '1') == clean]
out = []
def P(s=''):
    out.append(s); print(s)
P('## n (clean / unclean windows per cell)')
for arm, lv in [('on', 'idle')] + [(a, l) for l in LV for a in ('on', 'off')]:
    P(f'{arm} {lv}: clean={len(sel(rows, arm, lv))} unclean={len(sel(rows, arm, lv, False))}')
neg = [r for r in rows if r['kind'] == 'neg']
P(f"neg: clean={sum(1 for r in neg if r['clean'] == '1')} unclean={sum(1 for r in neg if r['clean'] != '1')}")
for clean in (True, False):
    P(f'\n## request side, {"clean" if clean else "UNCLEAN"} windows')
    for lv in LV:
        for arm in ('off', 'on'):
            rs = sel(rows, arm, lv, clean)
            if rs:
                P(f"{arm} {lv} n={len(rs)} reqs {cell(rs, 'reqs')} 2xx {cell(rs, 's200')} 503 {cell(rs, 's503')} client_to {cell(rs, 'to')} reset {cell(rs, 'reset')} hk_timeout {cell(rs, 'hk_timeout')} load1 {cell(rs, 'load1_before', 1)}")
        a, b = vals(sel(rows, 'on', lv, clean), 's200'), vals(sel(rows, 'off', lv, clean), 's200')
        if len(a) >= 3 and len(b) >= 3:
            lo, hi = boot_ratio(a, b)
            ma, mb = statistics.median(a), statistics.median(b)
            P(f"R2 {lv}: median on {ma:g} off {mb:g} ratio {ma / mb:.3f} bootstrap95 [{lo:.3f}, {hi:.3f}] exact/perm MWU p={mwu_p(a, b):.3f} n={len(a)} vs {len(b)}")
            for c in ('s503', 'to'):
                x, y = vals(sel(rows, 'on', lv, clean), c), vals(sel(rows, 'off', lv, clean), c)
                P(f"   {c}: on med {statistics.median(x):g} off med {statistics.median(y):g} p={mwu_p(x, y):.3f}")
    P(f'\n## worker side (on arm), {"clean" if clean else "UNCLEAN"} windows')
    for lv in ['idle'] + LV:
        rs = sel(rows, 'on', lv, clean)
        if not rs: continue
        P(f"on {lv} n={len(rs)} poll_succ5 {fmt(med(rs, 'payment-recovery:succ') and sum(med(rs, f'{k}:succ') or 0 for k in POLL))} "
          f"poll_fail5 med {statistics.median([poll_sum(r, 'fail') for r in rs]):g} max {max(poll_sum(r, 'fail') for r in rs):g} | "
          f"rows/s pay_attempts {cell(rs, 'row:pay_attempts:per_s', 1)} wf_reserved {cell(rs, 'row:wf_reserved:per_s', 1)} rs_completed {cell(rs, 'row:rs_completed:per_s', 1)} wh_done {cell(rs, 'row:wh_left:per_s', 2)} | "
          f"longest sq gap: " + ' '.join(f"{g}={cell(rs, f'sq:{g}:longest', 1)}" for g in ('payment-claim', 'payment-recovery', 'webhook-retry', 'event-relay', 'oi-reservation', 'oi-restock', 'checkout'))
          + f" | starved windows {sum(1 for r in rs if (f(r.get('starved_n')) or 0) > 0)}/{len(rs)} | http_blocked {cell(rs, 'http_blocked_mean', 1)} atlas_blocked {cell(rs, 'atlas_blocked_mean', 2)} dumps_atlas_ge1 {sum(f(r['dumps_atlas_ge1']) or 0 for r in rs):.0f}/{sum(f(r['n_dumps']) or 0 for r in rs):.0f}")
    # H7b throughput retention vs idle (clean idle only)
    idle = sel(rows, 'on', 'idle', True)
    for lv in LV:
        rs = sel(rows, 'on', lv, clean)
        if rs and idle:
            for name in ('wf_reserved', 'rs_completed', 'pay_attempts', 'wh_left'):
                mi = med(idle, f'row:{name}:per_s'); ml = med(rs, f'row:{name}:per_s')
                if mi: P(f"H7b {lv} {name}: idle {mi:.2f}/s load {ml:.2f}/s ratio {ml / mi:.2f}")
    for lv in LV:
        P(f"H3 {lv} saturated windows (on, hk_timeout>0): {sum(1 for r in sel(rows, 'on', lv, clean) if (f(r.get('hk_timeout')) or 0) > 0)}; of those with poll_fail>0: {sum(1 for r in sel(rows, 'on', lv, clean) if (f(r.get('hk_timeout')) or 0) > 0 and poll_sum(r, 'fail') > 0)}")
    starved = [(r['tag'], r['starved']) for lv in LV for r in sel(rows, 'on', lv, clean) if (f(r.get('starved_n')) or 0) > 0]
    P(f'R1/H7a starved windows (on, mid+high): {len(starved)}')
    for t, s in starved: P(f'   {t}: {s[:300]}')
P('\n## H6 thread strings')
import collections
c = collections.Counter((r['arm'], r.get('thr', '')) for r in rows if r['kind'] == 'matrix' and r['level'] != 'idle')
for k, v in c.items(): P(f'{k}: {v}')
P('\n## H5 negative control rows')
for r in neg:
    P(f"{r['tag']} clean={r['clean']} 2xx={r['s200']} 503={r['s503']} hk_timeout={r.get('hk_timeout')} http_dumps_ge1={r.get('dumps_http_ge1')}/{r.get('n_dumps')} atlas_dumps_ge1={r.get('dumps_atlas_ge1')}/{r.get('n_dumps')} poll_fail5={poll_sum(r, 'fail'):g} longest sq gaps={[r.get(f'sq:{g}:longest') for g in ('payment-claim','payment-recovery','webhook-retry','event-relay','oi-reservation','oi-restock','checkout')]}")
```
