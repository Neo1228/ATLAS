# EXP-108: 외부 의존성 지연이 요청 스레드를 고갈시키는가, 그리고 breaker·bulkhead가 필요한가

- 상태: 사전 등록(실행 전). 가설과 반증 조건은 첫 측정 실행 전에 이 커밋으로 고정한다. 결과·해석·주장 표는 실행 뒤 같은 문서에 채운다.
- 날짜: 2026-11-26
- 관련: [#696](https://github.com/Neo1228/ATLAS/issues/696), [ADR-0055](../02-decisions/adr/ADR-0055-extreme-load-boundaries-and-infra-gates.md), [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md), [ADR-0084](../02-decisions/adr/ADR-0084-checkout-ttl-authorize-slo-and-load-contract.md)

## 문제 한 문장

요청 스레드가 PayPal·위치 서비스를 동기로 기다리는 동안 Tomcat 스레드(최대 40)가 묶인다. 의존성이 느려질 때 (1) 묶이는 스레드 수가 도착률 × 지연으로 예측되는지, (2) 그 의존성을 쓰지 않는 요청(카탈로그 조회)이 실제로 밀리는지, (3) 밀린다면 bulkhead, 점진 복구 breaker, read timeout 단축 중 무엇이 증거로 정당화되는지를 실제 앱과 PostgreSQL로 센다. 코드를 바꿀지는 이 문서의 결과가 정한다.

## 코드 읽기 결과 (측정 전에 읽은 것, 기준 커밋 `9a14fe4c`)

### 요청 스레드가 PayPal을 동기 호출하는 경로

| 진입점 | 호출 체인 | provider 호출 위치 | DB transaction 안인가 |
|---|---|---|---|
| `POST /api/orders/{id}/payment` | `OrderServiceImpl.submitPayment` → `PaymentServiceImpl.submitInitial` | `OrderServiceImpl.java:199`(주석 196 "업무 DB transaction 밖") | 아니오. 준비 transaction은 194행에서 끝난다 |
| `POST /api/checkouts/{id}/payment` | `CheckoutPaymentServiceImpl` → `PaymentServiceImpl.submitCheckout*` | `CheckoutPaymentServiceImpl.java:147-148` | 아니오. transaction은 139행에서 끝난다 |
| `GET /api/checkouts/{id}/payment/return`, `/api/orders/{id}/payment/return` | `completeCheckoutApproval`/`completeApproval` → `PaymentServiceImpl.completeApprovalForOwner` | `PaymentServiceImpl.java:192` | 아니오. 조회 transaction(170-188)이 끝난 뒤 호출 |
| `POST …/reconcile`, `POST …/cancel` | `reconcile*`, `voidAuthorization*`, `reconcileForCancellation` → `performPhase` | `PaymentServiceImpl.java:625,627,630` | 아니오. `reservePhase` 520-563과 `finalizePhase` 580-601 사이에서만 호출 |
| `POST /api/webhooks/paypal`(인증 없음, `ApiSecurityConfiguration`의 `permitAll`) | `PayPalWebhookService.handle` → `PaymentWebhookProcessor.verifyPayPalWebhook` → `PayPalRestClient.postWebhook` | `PayPalWebhookService.java:39`, `PaymentWebhookProcessor.java:63` | 아니오. 검증은 transaction(`PayPalWebhookService.java:51`) 시작 전 |
| 환불 | `OrderRefundServiceImpl.refund` → `PaymentRefundWorkflow` | `OrderRefundServiceImpl.java:154-155`, `PaymentRefundWorkflow.java:214-216` | 아니오 |

- 모든 provider 호출은 `PayPalRestClient`를 지난다. 연결 2 s·읽기 10 s는 `PayPalRestClient.java:54-55`에서 읽고 `:305-312`에서 JDK `HttpClient`와 `setReadTimeout`으로 적용한다. 자동 재시도는 없다.
- OAuth 토큰 요청은 `PayPalRestClient.api()`(`:287`) 안에서 일어나며 실패하면 `PayPalUnavailableException`(`:301`)이다. 토큰이 캐시되어 있으면 호출이 없다.
- 첫 provider 호출 앞에 FX 스냅샷이 필요하면 `PaymentServiceImpl.ensureFxSnapshot`(`:433-464`)이 환율 API(읽기 3 s, `FrankfurterExchangeRateProvider.java:41`)를 같은 요청 스레드에서 부른다. 환율 API는 실패 뒤 15 s 재호출 억제(`application.yml`의 `failure-backoff`)라는 이미 있는 완화 장치를 갖는다.
- 같은 owner(주문·checkout)의 provider 호출은 `OwnerLockRegistry.java:20`의 `synchronized`로 직렬화된다(`PaymentServiceImpl.java:801`). 같은 owner의 중복 요청은 두 번째 스레드가 이 잠금에서 기다린다.
- 코드 읽기에서만 보이는 관찰(측정하지 않음): `OrderServiceImpl.java:187`과 `CheckoutPaymentServiceImpl.java:130`은 `payments.prepareInitial`/`prepareCheckout`을 호출자의 DB transaction 안에서 부르는데, 이 메서드는 `withOwnerLock`을 쓴다(`PaymentServiceImpl.java:94,105`). 같은 owner의 다른 요청이 PayPal 호출 중 잠금을 쥐고 있으면 이 스레드는 DB 연결을 쥔 채 `synchronized`에서 기다린다. 중복 제출 한 건당 연결 하나 규모이며 이번 실험의 범위 밖이다(후속 후보로만 기록).

### PayPal 예외의 의미와 상태 기계

- `PayPalUnavailableException`(`PayPalRestClient.java:452`)은 서로 다른 두 경우에 쓰인다: 아무것도 보내지 않은 실패(`api()`의 자격 증명 없음·OAuth 실패, `:283-302`)와 보낸 뒤 결과를 모르는 실패(`post()`의 `RestClientException`, `:246-248`). 호출자는 둘을 구분하지 않는다.
- `PayPalPaymentProvider.perform`/`completeApproval`은 이를 `PAYMENT_PROVIDER_UNAVAILABLE`로 감싼다(`:50,66,332-340`). `PaymentServiceImpl`은 이 코드를 받으면 `finalizePhase(start, null, UNKNOWN)`로 상태를 `UNKNOWN`으로 만들고(`:193-195`, `:632-634`) 구매자에게는 `GlobalExceptionHandler.java:146-147`의 503이 나간다. `UNKNOWN`은 구매자의 같은 요청 재시도(`canExecutePhase`가 `UNKNOWN`을 허용, `:566-577`)와 복구 worker가 PayPal-Request-Id 멱등성으로 다시 수행한다(EXP-050 capture 12 s 칸: PayPal이 capture를 commit했어도 3/3 중복 없이 수렴).
- 따라서 호출 전에 거절하면(아무것도 보내지 않음) 같은 경로로 `UNKNOWN`이 되어도 재시도가 중복 capture를 만들지 않는다. 다만 `UNKNOWN`은 필요 없이 복구 작업(최초 지연 30 s)을 남기는 비용이 있다.

### worker가 같은 provider를 부르는 경로

- `PaymentRecoveryWorker`: `resolveExpiredApproval`(`PaymentRecoveryWorker.java:217`), `orders.reconcilePayment`(`:241`), `retryRefundOperation`(`:258`)이 스케줄러 스레드에서 같은 `PaymentServiceImpl`/`PayPalRestClient` 경로를 지난다. 요청 경로와 같은 메서드이므로 호출 스택만으로는 요청 스레드인지 worker인지 구분되지 않는다.
- 위치 서비스 worker: `LocationReservationServiceImpl.recoverDue`(`:280`)가 `process`(`:302`) → `inventory.reserve`(`:345,411`)를, `LocationRestockServiceImpl.java:177`이 `restock`을 부른다.

### 요청 스레드가 위치 서비스를 동기 호출하는 경로

| 진입점 | 호출 | 위치 | transaction 안인가 |
|---|---|---|---|
| `POST /api/buyers/{ref}/cart/checkout`(`CartController.java:59-67`) | `CartCheckoutServiceImpl.remoteCandidates`: Seller의 활성 location마다, SKU마다 `availableStock`을 **순차** 호출 | `CartCheckoutServiceImpl.java:122,447-455`. location 하나가 실패하면 그 location은 건너뛴다(`:456-462`) | 아니오. `transactions.execute`(124) 앞 |
| 같은 요청의 후반 | `finishLocationReservation` → `CheckoutFinalizationServiceImpl.settle` → child order마다 `coordinator.retry`(순차) → `inventory.reserve` | `CartCheckoutServiceImpl.java:137`, `LocationReservationServiceImpl.java:345,411` | 아니오. `submit`의 transaction은 116행에서 끝나고 `process`는 121행 |
| 결제 직전 claim | `claimBeforeCapture` → `coordinator.claim` → `inventory.claim` | `CheckoutPaymentServiceImpl.java:180,224,243-244`, `LocationReservationServiceImpl.java:241` | 아니오 |
| 내부 예약 API | `LocationReservationController.reserve` → `coordinator.submit` → `process` | `LocationReservationController.java:36`, `LocationReservationServiceImpl.java:121` | 아니오 |
| 입장 gate(Redis 또는 precheck 사용 시) | `inventory.availableStock` | `PrecheckStockAdmissionControl.java:58`, `RedisStockAdmissionControl.java:220` | 해당 gate를 켠 경우만 |

- `HttpLocationClient`는 연결 250 ms, 읽기 2 s(`HttpLocationClient.java:39-40`, 적용 `:205-207`), 호출당 재시도 없음이다. 그러나 한 요청이 location 수 × SKU 수만큼 순차 호출하므로 한 요청 스레드가 묶이는 최악 시간은 호출 수 × (연결 + 읽기)이다. 이 곱셈은 이번 실험에서 호출 하나 단위(2 s)로만 측정한다.
- 이 경로에도 DB transaction 안에서 위치 서비스를 부르는 곳은 읽은 범위에서 없었다.

### 설정 값

- Tomcat 최대 스레드 40, 대기 큐 100(`application.yml:9-13`), Hikari 풀 10(기본값), 연결 대기 상한 500 ms(`application.yml:59`).
- 문서화된 부하 계약: 지속 전체 도메인 세션 10/s, 버스트 30/s, 결제 제출 p95 1.5 s(ADR-0084 54-58행). 세션마다 PayPal 호출이 하나 이상이므로 아래 도착률 1~8/s는 이 계약 범위 안이다.
- 이 저장소의 breaker는 `RedisStockAdmissionControl`의 최소 2 s breaker 하나뿐이고 bulkhead는 없다. ADR-0055(92-96행)는 점진 복구(33/67/100%) 없이 breaker를 넣지 않는다고 적었고 EXP-050(186, 191행)은 Tomcat 40과 200의 비교를 하지 않았고 bulkhead를 확정하지 않는다고 적었다.

## 측정 방법 (사전 등록)

- 대상: 이 저장소 `9a14fe4c`의 `atlas-application` bootJar를 별도 JVM(JDK 25, `-Xms1g -Xmx1g`)으로 띄운다. `ATLAS_PAYMENT_PROVIDER=paypal`, PayPal 기준 URL은 로컬 가짜 서버(`ATLAS_PAYPAL_LOCAL_PROXY_ENABLED=true`로 비표준 포트 허용), 위치 서비스 endpoint도 로컬 가짜 서버다. PostgreSQL 18.6 컨테이너(`max_connections=200`)에 `load-tests/seed-catalog-read.sql`(sellers=20, products_per_seller=100: 상품 2,000, SKU 4,000)을 넣는다.
- 가짜 PayPal·위치 서버(저장소에 올리지 않는 로컬 asyncio 서버 하나): PayPal 토큰 요청(`/v1/oauth2/token`)에 `expires_in` 32400의 토큰을 돌려주고, 웹훅 검증은 `SUCCESS`를, 주문 생성은 `PAYER_ACTION_REQUIRED`를 돌려준다. 데이터 호출의 동작을 실행 중에 `ok`, `delay`(고정 지연 뒤 응답), `hang`, `err5xx`, `refuse`(수신 소켓 닫음)로 바꿀 수 있고, 클라이언트가 먼저 끊은 호출 수·동시 처리 중 호출 최대 수를 센다.
- 느린 호출의 요청 경로: PayPal은 `POST /api/webhooks/paypal`(인증 없이 요청 스레드가 PayPal 검증을 동기 호출하고 그 동안 DB 연결을 쓰지 않는다), 위치 서비스는 `POST /api/internal/order-inventory/reservations`(요청 스레드가 `HttpLocationClient.reserve`를 호출한다. 서비스 token 헤더 필요).
- 관련 없는 요청: `GET /api/products?limit=20`을 20 req/s 고정 도착률(k6 `constant-arrival-rate`)로 실행 전체 동안 보낸다. 이 요청은 의존성을 부르지 않는다.
- 칸 구조: 기준 구간 5 s(카탈로그만) → 장애 구간 25 s(가짜 서버를 지연 모드로 바꾸고 동시에 느린 호출 스트림 시작) → 복구 구간 15 s(가짜 서버를 `ok`로 되돌리고 느린 호출 스트림은 같은 도착률로 계속). 칸마다 앱이 유휴(busy ≤ 2, 가짜 서버 처리 중 0)일 때 시작한다. 앱은 칸 사이에 재시작하지 않고 측정 전에 워밍업 칸 1개를 버린다.
- 표집: 1 Hz로 Micrometer(`tomcat.threads.busy`, `tomcat.threads.current`, Hikari active·pending·timeout, `http.server.requests` count 상태별), 2 s마다 `jcmd <pid> Thread.print`로 `http-nio-*-exec-*` 스레드를 스택으로 분류한다: `PayPalRestClient`·`PayPalPaymentProvider` 프레임 → paypal, `HttpLocationClient` 프레임 → location, `HikariPool.getConnection` → hikari, 유휴 대기 → idle, 그 밖 → other. 가짜 서버에서 처리 중 호출 최대 수와 클라이언트가 끊은 호출 수를 읽는다.
- 정의: 지연 h = 느린 호출 하나가 요청 스레드를 묶는 시간 = min(가짜 서버 지연, 클라이언트 읽기 상한). PayPal은 지연 3 s → h ≈ 3 s, 지연 10 s → 읽기 상한 10 s와 같아서 h ≈ 10 s(응답이 먼저 오든 시간 초과가 먼저든 묶이는 시간은 같다). 위치 서비스는 지연 5 s를 주면 읽기 상한 2 s가 h다. 정상 상태 구간 = 장애 시작 + h + 3 s부터 장애 끝까지. 저하된 카탈로그 요청 = 지연 1,000 ms 초과 또는 2xx가 아닌 응답(클라이언트 시간 초과 포함). D = 정상 상태 구간에서 저하된 카탈로그 요청의 비율. D_base = 기준 구간의 같은 비율(호스트 소음 보정용).
- 칸·표본: PayPal 지연 3 s와 10 s × 느린 호출 도착률 1, 2, 4, 8/s × n = 3. 위치 서비스: 도착률 10, 20, 40/s × 지연 5 s(h = 2 s) × n = 3. 장애 없는 대조군(탐지기 오탐 확인): 웹훅 8/s + 카탈로그 20/s, 지연 0, n = 3. 위치 대조군: 예약 40/s, 지연 0, n = 3. 같은 반복 번호 안에서 조건 순서를 무작위로 섞는다.
- 호스트 규약(부모 지시): 측정 칸은 호스트 잠금(`measure-lock.sh`)을 쥔 동안에만 실행한다. 칸마다 `measure-lock.sh status`(load와 다른 에이전트의 라벨 컨테이너)와 `uptime`을 기록하고, 시작 load1 > 12이거나 다른 스택의 컨테이너와 겹친 칸은 "오염"으로 표시해 별도 열로 보고한다. 평균에 조용히 섞지 않는다. 시간 값(p95, 복구 초)은 맥락이고 결론은 스레드 개수와 요청 개수로 낸다.
- 비교 칸(H2가 확인될 때만 실행): 같은 칸(PayPal 8/s × 10 s, 장애 구간 25 s) n = 3씩 (A1) bulkhead(요청 스레드 호출만 동시 20개로 제한, 가득 차면 대기 없이 즉시 거절), (A2) 단순 breaker(완료한 호출 10개 중 5개 이상이 2 s 초과이거나 실패면 5 s 열림, 그 뒤 시험 호출 1개가 성공하면 닫힘), (A3) 점진 복구 breaker(A2와 같은 열림 조건, 열린 뒤 33% → 67% → 100%를 각 5 s씩 통과시키고 단계 안에서 느린·실패 호출이 하나라도 나오면 다시 열림), (A4) 읽기 상한만 3 s로 낮춘 기존 jar(`atlas.payment.paypal.read-timeout=PT3S`). A1~A3는 측정용 임시 구현이며 저장소에 올리는 것은 증거가 지지하는 가장 작은 하나뿐이다.

## 가설과 반증 조건 (실행 전 등록)

| # | 가설 | 반증 조건 | 지표 |
|---|---|---|---|
| H1 | Little의 법칙: 정상 상태 구간에서 요청 스레드가 의존성 호출에 묶인 평균 개수 ≈ 느린 호출 도착률 λ × h. PayPal 10 s는 λ = 4/s에서, 위치 서비스 2 s는 λ = 20/s에서 40개를 채운다. 포화하지 않는 칸(λ·h ≤ 24)에서 `jcmd`로 센 paypal(또는 location) 스레드 평균이 λ·h의 ±25%(최소 ±2) 안이다. 포화 칸(λ·h ≥ 40)에서는 `tomcat.threads.busy` 최댓값이 38 이상이고 paypal 스레드 최댓값이 35 이상이다 | 포화하지 않는 칸 중 λ·h ≥ 6인 칸 2개 이상에서 3개 반복 중 2개 이상이 ±25% 밖이거나, 포화 칸 어느 하나에서 3개 반복 중 2개 이상이 위 최댓값에 못 미침 | `jcmd` 스택 분류, `tomcat.threads.busy`, 가짜 서버의 처리 중 호출 최대 수 |
| H2 | 포화하면 의존성을 쓰지 않는 카탈로그 GET이 밀린다: 포화 칸(PayPal 4/s와 8/s × 10 s, 위치 20/s와 40/s) 각각에서 3개 반복 중 2개 이상이 D ≥ 0.20이다. 포화하지 않는 칸에서는 D < 0.05이고 D_base 대비 증가가 0.05 미만이다 | 포화 칸 중 하나라도 3개 반복 중 2개 이상이 D < 0.05이면 그 칸에서 반증("이 범위에서는 굶음이 없다"). 포화하지 않는 칸에서 D ≥ 0.05가 2개 반복 이상이면 H1의 기대(비포화에는 영향 없음)가 틀림 | k6의 카탈로그 요청별 지연·상태, D, D_base, 시간 초과 수 |
| H3 | 요청 스레드 호출만 세는 in-flight 상한(bulkhead, 20)은 의존성과 무관한 요청을 지킨다: (A1)의 PayPal 8/s × 10 s 칸에서 3개 반복 모두 D < 0.05이고 paypal 스레드 최댓값이 22 이하이며, 거절은 호출 전에 일어나 가짜 서버가 받은 호출 수가 (수락된 호출 수)와 같고 거절 응답은 503이다 | 반복 하나라도 D ≥ 0.20이거나 paypal 스레드가 22를 넘거나 가짜 서버가 받은 호출 수가 수락 수보다 많음 | 같은 지표 + 거절 계수기 대 503 개수 + 가짜 서버 시작 호출 수 |
| H4a | 완료한 호출 결과로 판단하는 breaker는 포화를 막지 못한다: 지연 10 s에서 첫 느린 결과는 장애 시작 후 10 s 전에는 나올 수 없고, 8/s에서는 40 스레드가 40/8 = 5 s 만에 찬다. (A2)와 (A3)에서 장애 시작 뒤 첫 열림까지 걸린 시간이 10 s 이상이고 그 전에 `tomcat.threads.busy`가 38 이상에 도달한다 | 3개 반복 중 2개 이상에서 첫 열림이 7 s 이내이거나 첫 10 s 안의 busy 최댓값이 38 미만 | breaker 상태 전이 로그, busy 시계열 |
| H4b | 단순 breaker는 의존성이 계속 느린 동안 열림-시험-열림을 반복한다: (A2)의 장애 구간 25 s에서 열림 전이가 2회 이상이고 시험 호출 성공이 0회이다. 점진 복구 breaker는 각 단계에서 느린 호출을 통과시키므로 (A3)의 busy 최댓값이 (A2)보다 큰 반복이 3개 중 2개 이상이다 | (A2)의 열림 전이가 3개 반복 중 2개 이상에서 1회 이하, 또는 (A3)의 busy 최댓값이 (A2) 이하인 반복이 2개 이상 | 열림 전이 수, 시험 결과, busy 최댓값 |
| H5 | 탐지기가 유효하다: 장애 없는 대조군(지연 0, 웹훅 8/s)에서 모든 `jcmd` 표본의 paypal 스레드가 3 이하이고 D < 0.05. 포화 칸에서는 위 H1의 최댓값 조건으로 탐지기가 우는 것이 확인된다(양성 대조) | 대조군 한 표본이라도 paypal 스레드가 10 이상이거나 D ≥ 0.05이면 부하 생성기·탐지기가 신뢰할 수 없으므로 H1~H4를 해석하지 않고 그 이유만 적는다 | `jcmd` 분류, D |

판정 규칙: 표본은 칸마다 n = 3(대조군 n = 3). 비율 판정은 "3개 중 2개 이상"으로 하고, 0건이면 rule of three 상한(3/n)으로 쓴다. 오염 칸은 판정에서 제외하지 않고 표시해 두 집계(전체, 오염 제외)를 모두 적는다.

## 결정 규칙 (실행 전 등록)

- H2가 모든 포화 칸에서 반증되면: 코드를 바꾸지 않고, 이 범위에서 굶음이 없다고 쓰고, 재검토 조건을 수치로 적는다(예: 운영 지표에서 `tomcat.threads.busy`가 35 이상으로 10초 이상 유지되거나 PayPal read timeout 또는 도착률이 이 칸의 곱 λ·h = 40 이상으로 바뀔 때).
- H2가 확인되면 (a) bulkhead, (b) 점진 복구 breaker, (c) read timeout 단축을 위 비교 칸으로 비교하고, 증거가 지지하는 가장 작은 하나만 구현한다. 구현은 결제 모듈이 소유하는 단순 클래스이고, 기본값은 동작을 바꾸지 않거나 보수적이며, 설정 가능하고, 거절 Micrometer 계수기를 갖고, 거절은 아무것도 보내기 전에 일어나야 하며 worker 호출은 상한 대상이 아니다.

## 대안

| 대안 | 평가 |
|---|---|
| 측정 없이 Resilience4j를 붙인다 | 새 의존성이고 H2의 근거가 없다. 기각 |
| 시험 클래스 하나로 같은 JVM에서 스레드를 흉내 낸다 | 앱의 실제 Tomcat 스레드·Hikari·소켓을 지나지 않는다. 기각 |
| 실제 PayPal Sandbox에 지연을 건다 | 지연·도착률을 통제할 수 없고 계정·한도 위험이 있다. 이번 질문(스레드 점유)에는 가짜 서버로 충분하다. 기각 |

## 한계 (실행 전에 알고 있는 것)

- 가짜 서버는 용량 제한이 없다. 실제 PayPal은 과부하일 때 더 느려질 수 있어 breaker가 막으려는 "되돌아온 폭주" 진동은 이 가짜 서버로는 만들어지지 않는다. H4b는 의존성이 계속 느린 구간의 열림-시험-열림만 본다.
- 호스트가 공유되어 부하가 크고 흔들린다. 시간 값은 맥락이다.
- 웹훅·내부 예약 API는 대표 경로이고 구매자 checkout 전체 경로가 아니다. 구매자 경로(`POST /payment`)는 같은 `PayPalRestClient`를 쓰지만 이번 실험은 그 경로를 부하로 지나지 않는다.
- `jcmd Thread.print`는 safepoint를 만들어 앱을 흔든다. 모든 칸에 같은 방식으로 적용된다.
