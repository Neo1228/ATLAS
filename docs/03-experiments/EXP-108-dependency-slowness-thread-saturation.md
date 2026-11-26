# EXP-108: 외부 의존성 지연이 요청 스레드를 고갈시키는가, 그리고 breaker·bulkhead가 필요한가

- 상태: ✓ 완료(가짜 PayPal·위치 서버, 단일 호스트, 칸당 n=3). 가설과 반증 조건은 첫 측정 실행 전 커밋으로 고정했고 비교 칸 보강은 비교 칸 측정 전에 커밋했다.
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

### 사전 등록 보강 (2026-11-26 17:25, 비교 칸 측정 전, 요청 정정 반영)

부모 에이전트의 요청(느린 호출 비율로 열리는 breaker를 비교에 넣어 bulkhead 쪽으로 기울지 않게 한다)에 따라, 위 (A2)와 (A3)의 열림 조건이 **느린 호출 비율**임을 명시하고 (A3)의 기대 동작과 반증 조건을 비교 칸 측정 전에 여기에 고정한다. PayPal·위치 matrix(`pp` 19칸, `ctl` 3칸)는 이 보강보다 먼저 실행했고 보강은 그 해석을 바꾸지 않는다. 비교 칸(A1~A4)은 이 보강 커밋 뒤에 실행한다.

- 열림 조건(A2·A3 공통): 완료한 호출 최근 10개 중 5개 이상이 느림(2 s 이상) 또는 실패(Resilience4j의 `slowCallDurationThreshold` = 2 s, `slowCallRateThreshold` = 50%, 창 10개, 실패도 같은 창에 센다). 호출이 끝나야 창에 들어가므로 읽기 상한(10 s)이 첫 판단 시각의 하한이다.
- (A3) 기대 동작(점진 복구 slow-call-rate breaker, 도착률 8/s × 10 s): ① 장애 시작 뒤 첫 10 s에는 완료한 느린 호출이 없어 열리지 않고 busy가 38 이상에 도달한다(H4a). ② 열린 5 s 동안 요청 스레드 호출은 보내기 전에 거절되어 busy가 빠르게 내려간다(요청 스레드 대부분이 즉시 503). ③ 열림 해제 뒤 단계 33%는 8 × 0.33 ≈ 2.7/s × 10 s ≈ 27개, 단계 67%는 8 × 0.67 ≈ 5.3/s × 10 s ≈ 53개를 묶으므로, 67% 단계 안에 busy가 40에 닿아 카탈로그가 밀린다. 단계 안의 나쁜 결과는 호출 하나가 10 s를 묶은 뒤에야 알려져 단계 전환(5 s)보다 늦으므로 단계는 나쁜 결과를 보기 전에 올라간다. 따라서 장애 구간 25 s 안에 (열림 전이) ≥ 2회이고 정상 상태 구간(장애 시작 + 13 s부터)의 D는 (A1: bulkhead 20)보다 크다.
- (A3) 반증 조건: 3개 반복 중 2개 이상에서 (정상 상태 구간 D < 0.05)이면 "점진 복구 slow-call-rate breaker도 이 칸에서 카탈로그를 지킨다"로 기록하고 H4a·H4b 해석을 바꾼다. 또는 첫 열림이 장애 시작 후 7 s 이내인 반복이 2개 이상이면 H4a가 반증이다.
- 비교의 공정성 규칙: 선택은 정상 상태 구간 D(3개 반복 중 2개 이상이 D < 0.05)와 호출 전 거절 여부(가짜 서버가 받은 호출 수 = 수락 수)로 한다. (A4: 읽기 3 s)는 h = 3 s라 8/s에서 λ·h = 24 < 40이므로 H1에 따르면 포화하지 않는 것이 기대이며, 이것은 같은 입력에서 PayPal이 실제로 10 s 걸리는 경우를 막지 못함(응답이 있었을 호출을 버림)과 맞바꾼다는 점을 표에 같이 적는다. 어느 한 arm도 다른 arm의 기본값을 보고 조정하지 않는다.

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

## 결과

실행: 2026-11-26 16:58~17:55 KST, Apple Silicon Mac(Colima VM) 한 대, 호스트 잠금(`measure-lock.sh`)을 쥔 구간에서만 측정했다. 앱은 기준 커밋 `9a14fe4c` bootJar(비교 칸 A1·A2·A3은 같은 커밋에 임시 bulkhead·breaker를 얹은 jar), PostgreSQL 18.6, JDK 25. 칸마다 `uptime`의 load1을 기록했고 시작 load1 > 12이거나 다른 스택(라벨 있는 컨테이너)과 겹친 칸은 "오염"으로 표시했다. 시작 load1은 2.7~10.0였다. 오염 칸 7개(다른 에이전트의 컨테이너가 겹침)는 포화 칸 3개(`armB2`·`armB3` 반복 2, `loc` 반복 3)를 포함하지만 같은 칸의 오염되지 않은 반복과 같은 방향의 값이라 판정을 바꾸지 않는다: armB2-l8-d10000-r2, armB3-l8-d10000-r2, loc-l40-d2000-r3, locctl-l40-d0-r3, pp-l1-d10000-r1, pp-l2-d10000-r1, pp-l2-d3000-r1.

### PayPal 칸 (웹훅 검증 경로, 카탈로그 20/s 병행, 칸당 n=3)

| 칸 | 반복 | λ·h | busy 최댓값(장애 구간) | 묶인 의존성 스레드 평균(정상 상태) | 묶인 의존성 스레드 최댓값 | 정상 상태 D (요청 수, 저하 수) | 장애 전체 D | 카탈로그 p95(ms, 정상 상태) | 느린 호출 결과(장애+복구) | 복구 초 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ctl 8/s × 0s | 1 | 0 | 0 | 0 | 0 | 0.000 (339, 0) | 0.000 | 19 | 2xx=280 | 0.0 | 9.1 | 아니오 |
| ctl 8/s × 0s | 2 | 0 | 2 | 0.1 | 1 | 0.000 (341, 0) | 0.000 | 28 | 2xx=281 | 0.1 | 6.2 | 아니오 |
| ctl 8/s × 0s | 3 | 0 | 1 | 0 | 1 | 0.000 (341, 0) | 0.000 | 6 | 2xx=280 | 1.0 | 4.6 | 아니오 |
| pp 2/s × 3s | 1 | 6 | 7 | 6 | 6 | 0.000 (280, 0) | 0.000 | 16 | 2xx=71 | 1.9 | 7.2 | 예 |
| pp 2/s × 3s | 2 | 6 | 8 | 6 | 6 | 0.000 (279, 0) | 0.000 | 9 | 2xx=70 | 2.0 | 5.8 | 아니오 |
| pp 2/s × 3s | 3 | 6 | 8 | 6 | 6 | 0.000 (280, 0) | 0.000 | 9 | 2xx=70 | 2.0 | 4.1 | 아니오 |
| pp 4/s × 3s | 1 | 12 | 14 | 12 | 12 | 0.000 (281, 0) | 0.000 | 10 | 2xx=140 | 3.0 | 5.8 | 아니오 |
| pp 4/s × 3s | 2 | 12 | 13 | 12 | 12 | 0.000 (281, 0) | 0.000 | 13 | 2xx=141 | 2.1 | 5.8 | 아니오 |
| pp 4/s × 3s | 3 | 12 | 13 | 12 | 12 | 0.000 (279, 0) | 0.000 | 7 | 2xx=140 | 2.6 | 4.1 | 아니오 |
| pp 8/s × 3s | 1 | 24 | 26 | 24 | 24 | 0.000 (281, 0) | 0.000 | 13 | 2xx=280 | 3.0 | 9.1 | 아니오 |
| pp 8/s × 3s | 2 | 24 | 26 | 24 | 24 | 0.000 (279, 0) | 0.000 | 13 | 2xx=280 | 3.0 | 6.3 | 아니오 |
| pp 8/s × 3s | 3 | 24 | 25 | 24 | 24 | 0.000 (280, 0) | 0.000 | 6 | 2xx=280 | 3.0 | 5.4 | 아니오 |
| pp 1/s × 10s | 1 | 10 | 11 | 10 | 10 | 0.000 (240, 0) | 0.000 | 32 | 2xx=17 503=23 | 7.1 | 8.9 | 예 |
| pp 1/s × 10s | 2 | 10 | 11 | 9.8 | 10 | 0.000 (239, 0) | 0.000 | 10 | 2xx=15 503=24 | 7.0 | 10.0 | 아니오 |
| pp 1/s × 10s | 3 | 10 | 10 | 10 | 10 | 0.000 (240, 0) | 0.000 | 7 | 2xx=15 503=25 | 7.1 | 5.2 | 아니오 |
| pp 2/s × 10s | 1 | 20 | 22 | 19.8 | 20 | 0.000 (241, 0) | 0.000 | 9 | 2xx=31 503=50 | 9.0 | 8.6 | 예 |
| pp 2/s × 10s | 2 | 20 | 22 | 20 | 20 | 0.000 (241, 0) | 0.000 | 6 | 2xx=30 503=51 | 9.0 | 6.7 | 아니오 |
| pp 2/s × 10s | 3 | 20 | 22 | 20 | 20 | 0.000 (241, 0) | 0.000 | 9 | 2xx=30 503=51 | 9.1 | 3.3 | 아니오 |
| pp 4/s × 10s | 1 | 40 | 40 | 39.7 | 40 | 0.000 (240, 0) | 0.000 | 239 | 2xx=109 503=51 | 10.0 | 9.0 | 아니오 |
| pp 4/s × 10s | 2 | 40 | 40 | 39.7 | 40 | 0.000 (239, 0) | 0.000 | 226 | 2xx=108 503=51 | 10.0 | 7.8 | 아니오 |
| pp 4/s × 10s | 3 | 40 | 40 | 39.5 | 40 | 0.000 (241, 0) | 0.000 | 228 | 2xx=109 503=51 | 9.1 | 4.9 | 아니오 |
| pp 8/s × 10s | 1 | 80 | 40 | 40 | 40 | 1.000 (188, 188) | 0.783 | 14483 | 2xx=209 503=81 | 10.1 | 6.1 | 아니오 |
| pp 8/s × 10s | 2 | 80 | 40 | 40 | 40 | 1.000 (189, 189) | 0.781 | 14438 | 2xx=232 503=57 | 10.0 | 6.5 | 아니오 |
| pp 8/s × 10s | 3 | 80 | 40 | 40 | 40 | 1.000 (189, 189) | 0.781 | 14435 | 2xx=236 503=53 | 10.0 | 3.5 | 아니오 |

### 위치 서비스 칸 (내부 예약 API, 가짜 지연 5 s라 h = 읽기 상한 2 s)

| 칸 | 반복 | λ·h | busy 최댓값(장애 구간) | 묶인 의존성 스레드 평균(정상 상태) | 묶인 의존성 스레드 최댓값 | 정상 상태 D (요청 수, 저하 수) | 장애 전체 D | 카탈로그 p95(ms, 정상 상태) | 느린 호출 결과(장애+복구) | 복구 초 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| loc 40/s × 2s | 1 | 80 | 40 | 40 | 40 | 1.000 (271, 271) | 0.949 | 7477 | 2xx=1219 | 1.1 | 4.1 | 아니오 |
| loc 40/s × 2s | 2 | 80 | 40 | 39.6 | 40 | 0.985 (271, 267) | 0.935 | 7282 | 2xx=1227 | 2.0 | 7.2 | 아니오 |
| loc 40/s × 2s | 3 | 80 | 40 | 39.6 | 40 | 0.989 (271, 268) | 0.938 | 7321 | 2xx=1227 | 1.9 | 5.0 | 예 |
| locctl 40/s × 0s | 1 | 0 | 2 | 0.1 | 1 | 0.000 (340, 0) | 0.000 | 10 | 2xx=1400 | 1.0 | 4.2 | 아니오 |
| locctl 40/s × 0s | 2 | 0 | 2 | 0 | 0 | 0.000 (341, 0) | 0.000 | 7 | 2xx=1401 | 1.0 | 8.1 | 아니오 |
| locctl 40/s × 0s | 3 | 0 | 2 | 0 | 1 | 0.000 (340, 0) | 0.000 | 8 | 2xx=1401 | 1.0 | 4.3 | 예 |

### 비교 칸 (PayPal 8/s × 10 s)

| arm | 반복 | busy 최댓값(첫 10 s) | 정상 상태 D | 장애 전체 D | 카탈로그 p95(ms) | 열림 전이 | 첫 열림(s) | 닫힘 전이 | PayPal에 도달한 호출 | k6 느린 호출 | 보내기 전 거절(추정) | load1 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 bulkhead 20 | 1 | 21 | 0.000 | 0.000 | 7 | 0 | - | 0 | 137 | 320 | 183 | 3.6 | 아니오 |
| A1 bulkhead 20 | 2 | 21 | 0.000 | 0.000 | 7 | 0 | - | 0 | 138 | 321 | 183 | 2.7 | 아니오 |
| A1 bulkhead 20 | 3 | 20 | 0.000 | 0.000 | 8 | 0 | - | 0 | 138 | 320 | 182 | 5.0 | 아니오 |
| A1 bulkhead 30 | 1 | 31 | 0.000 | 0.000 | 8 | 0 | - | 0 | 168 | 321 | 153 | 5.8 | 아니오 |
| A1 bulkhead 30 | 2 | 31 | 0.000 | 0.000 | 6 | 0 | - | 0 | 167 | 320 | 153 | 5.1 | 아니오 |
| A1 bulkhead 30 | 3 | 30 | 0.000 | 0.000 | 6 | 0 | - | 0 | 168 | 321 | 153 | 4.8 | 아니오 |
| A2 단순 breaker (상태 이월, 첫 실행) | 1 | 40 | 0.000 | 0.196 | 8 | 2 | 10.5 | 1 | 164 | 320 | 156 | 4.3 | 아니오 |
| A2 단순 breaker (상태 이월, 첫 실행) | 2 | 40 | 0.000 | 0.197 | 6 | 2 | 10.5 | 1 | 164 | 320 | 156 | 5.3 | 아니오 |
| A2 단순 breaker (상태 이월, 첫 실행) | 3 | 40 | 0.000 | 0.195 | 8 | 2 | 10.5 | 1 | 165 | 321 | 156 | 4.8 | 아니오 |
| A3 점진 복구 breaker (상태 이월, 무효) | 1 | 27 | 0.000 | 0.000 | 10 | 4 | 10.3 | 0 | 54 | 321 | 267 | 4.0 | 아니오 |
| A3 점진 복구 breaker (상태 이월, 무효) | 2 | 27 | 0.000 | 0.000 | 6 | 4 | 10.1 | 0 | 54 | 321 | 267 | 4.2 | 아니오 |
| A3 점진 복구 breaker (상태 이월, 무효) | 3 | 27 | 0.000 | 0.000 | 8 | 4 | 10.0 | 0 | 55 | 321 | 266 | 3.4 | 아니오 |
| A4 읽기 상한 3 s(지연 3 s 입력) | 1 | 25 | 0.000 | 0.000 | 7 | 0 | - | 0 | 321 | 321 | 0 | 3.4 | 아니오 |
| A4 읽기 상한 3 s(지연 3 s 입력) | 2 | 26 | 0.000 | 0.000 | 6 | 0 | - | 0 | 321 | 321 | 0 | 4.9 | 아니오 |
| A4 읽기 상한 3 s(지연 3 s 입력) | 3 | 25 | 0.000 | 0.000 | 6 | 0 | - | 0 | 320 | 320 | 0 | 5.4 | 아니오 |
| A2 단순 breaker (칸마다 새 프로세스) | 1 | 40 | 0.000 | 0.221 | 30 | 2 | 11.1 | 1 | 170 | 321 | 151 | 6.1 | 아니오 |
| A2 단순 breaker (칸마다 새 프로세스) | 2 | 40 | 0.000 | 0.217 | 19 | 2 | 11.1 | 1 | 171 | 321 | 150 | 7.6 | 예 |
| A2 단순 breaker (칸마다 새 프로세스) | 3 | 40 | 0.000 | 0.221 | 9 | 2 | 11.1 | 1 | 170 | 321 | 151 | 4.2 | 아니오 |
| A3 점진 복구 breaker (칸마다 새 프로세스) | 1 | 40 | 0.000 | 0.220 | 18 | 3 | 11.1 | 0 | 97 | 320 | 223 | 6.2 | 아니오 |
| A3 점진 복구 breaker (칸마다 새 프로세스) | 2 | 40 | 0.000 | 0.225 | 14 | 3 | 11.2 | 0 | 97 | 321 | 224 | 4.3 | 예 |
| A3 점진 복구 breaker (칸마다 새 프로세스) | 3 | 40 | 0.000 | 0.219 | 8 | 3 | 11.1 | 0 | 98 | 321 | 223 | 4.2 | 아니오 |

- "정상 상태 D"는 장애 시작 + h + 3 s부터 장애 끝까지다. breaker 칸에서는 그 구간에 이미 열려 있어 0이 되므로 포화 전환기를 포함한 "장애 전체 D"를 같이 적었다.
- "보내기 전 거절(추정)" = k6가 보낸 느린 호출 수 − 가짜 서버가 받은 호출 수(한 호출이 PayPal 호출 하나를 만들고 OAuth 토큰은 캐시되어 별도 호출이 없다). 거절 계수기 값은 표집기의 지표 이름 필터가 맞지 않아 칸에서는 수집하지 못했다. 계수기와 "보내기 전" 성질은 PostgreSQL 시험이 직접 확인한다.
- A2·A3 첫 실행은 같은 앱 프로세스에서 이어서 돌려 breaker 상태가 워밍업과 앞 칸에서 이월됐다. A3의 busy 27은 이월된 33% 단계가 통과시킨 호출(8 × 0.33 × 10 s ≈ 27)이지 새 장애를 만난 breaker의 동작이 아니다. 그래서 A3 첫 실행은 무효로 표시하고 칸마다 새 프로세스를 띄워 다시 측정했다(A2·A3 "칸마다 새 프로세스" 행). 이 재측정을 포함해 아래 판정에는 새 프로세스 행만 쓴다.

### 사전 등록 규칙 평가

| 가설 | 판정 | 개수 |
|---|---|---|
| H1 Little의 법칙 | 지지 | 비포화 칸(λ·h ≤ 24, 5칸 × 3)의 묶인 스레드 평균이 모두 λ·h의 ±25%(최소 ±2) 안(모두 안). 포화 칸 λ·h = 40(4/s × 10 s) 3/3에서 busy 40, 묶인 스레드 최댓값 40, 평균 39.5~39.7. 8/s × 10 s와 위치 40/s 3/3에서 busy 40, 묶인 스레드 최댓값 40 |
| H2 포화하면 무관한 GET이 밀린다 | 부분 지지 | 8/s × 10 s 3/3에서 D = 1.0(정상 상태 카탈로그 요청 188~189건 전부 저하, p95 14435~14483 ms). 위치 40/s 3/3에서 D = 0.985~1.000. **4/s × 10 s(λ·h = 40, 포화로 사전 등록)는 3/3에서 D = 0으로 반증됐다**: 40개가 거의 항상 묶이지만 호출이 0.25 s마다 끝나 스레드가 돌아오므로 카탈로그가 기다리지 않는다. 밀리는 것은 도착률이 용량을 넘을 때(λ·h > 40)다. 비포화 칸은 모두 D = 0.000(< 0.05) |
| H3 bulkhead(상한 20)가 무관한 요청을 지킨다 | 지지 | 3/3에서 D = 0, 묶인 PayPal 스레드 최댓값 20, busy 20~21, PayPal에 도달한 호출 137~138건(나머지는 보내기 전 거절), 느린 호출 응답은 503. 상한 30도 3/3에서 D = 0 |
| H4a 완료된 호출로 판단하는 breaker는 포화를 막지 못한다 | 지지 | 새 프로세스 A2·A3 각 3/3에서 첫 열림이 11.1~11.2 s(읽기 상한 10 s 뒤)이고 그 전에 busy가 40에 닿았다 |
| H4b 단순 breaker는 계속 느린 동안 열림-시험-열림을 반복한다 | 부분 지지 | 단순 breaker의 열림 전이 2~2회(3/3). 점진 복구 breaker가 단순 breaker보다 busy 최댓값이 큰 반복은 0/3이다(둘 다 40)이므로 "점진 단계가 더 많이 통과시킨다"는 반증됐다. 점진 복구 breaker는 열림 3~3회로 더 자주 열렸다 |
| H5 탐지기가 유효하다 | 지지 | 대조군(웹훅 8/s, 지연 0) 3/3에서 묶인 PayPal 스레드 최댓값 1, D 최대 0.000. 위치 대조군 3/3에서 묶인 위치 스레드 최댓값 1, D 최대 0.000. 포화 칸에서는 같은 탐지기가 40을 센다(양성) |

## 해석

- **H1 지지.** 묶이는 요청 스레드 수는 도착률 × 지연으로 예측된다(비포화 15칸 평균이 모두 ±25% 안). PayPal 10 s는 4/s에서, 위치 2 s는 20/s에서 40개를 채운다는 산술은 4/s(40개 점유)와 40/s(80)에서 확인했다. 위치 20/s 칸은 시간 예산 때문에 측정하지 않았다(범위 밖 결정: 40/s가 포화를 보이고 PayPal 4/s 칸이 경계의 동작을 보이므로 같은 곱의 20/s는 PayPal 4/s와 같은 경계로 본다. 재검토 조건: 위치 호출을 보호할 때 이 칸을 먼저 측정한다).
- **H2는 도착률이 용량(40/h)을 넘을 때만 성립한다.** 8/s × 10 s와 위치 40/s에서 PayPal·위치를 쓰지 않는 카탈로그 GET이 전부 1 s를 넘었다(D ≈ 1.0, p95 7 s 이상). 4/s × 10 s는 스레드가 거의 항상 40개 묶이는데도 D = 0이었다. 따라서 "PayPal 10 s에서 4/s면 스레드 40개가 찬다"는 맞지만 "그래서 무관한 요청이 밀린다"는 λ·h > 40에서만 맞다. 문서화된 부하 계약(지속 10 세션/s, 버스트 30 세션/s)에서 세션마다 PayPal 호출이 하나 이상이므로 PayPal이 읽기 상한(10 s)까지 응답하지 않으면 계약 범위 안인 8/s에서 포화한다.
- **bulkhead(A1)가 이 결함을 막는다.** 상한 20과 30에서 카탈로그 저하가 0이었고(3/3) 상한을 넘은 호출은 PayPal에 도달하지 않았다(8/s × 40 s = 320건 중 137~138건만 도달, 상한 30은 167~168건).
- **breaker는 이 결함에 늦다.** 느린 호출 비율로 열리는 breaker(2 s 이상 또는 실패가 최근 10개 중 5개)는 호출이 끝나야 판단하므로 첫 열림이 11 s(읽기 상한 10 s 뒤)이고 그 전에 8/s가 40개를 5 s에 채웠다(busy 40, 6/6). 장애 전체 D는 단순 0.22, 점진 복구 0.22로 bulkhead 0이다. 점진 복구는 단순보다 나은 점이 이 측정에서 보이지 않았고(busy 최댓값 같음, 열림 3회 대 2회) 상태 기계만 늘었다.
- **읽기 상한 3 s(A4)는 입력을 바꾼 비교다.** 지연 10 s 입력에서 모든 호출이 3 s에 끊겨(321건 전부 PayPal에 도달, 느린 호출 응답 503 200건) λ·h = 24로 포화는 막았다(D = 0). 문턱이 40/3 ≈ 13/s로 옮겨 갈 뿐이고 PayPal이 3~10 s에 정상 응답하는 호출도 전부 끊어 UNKNOWN과 복구 작업을 늘린다. 이 칸은 (a)와 같은 목적을 서로 다른 비용으로 이룬다는 것만 보이고 문턱 이동을 측정하지는 않았다(13/s 칸은 하지 않았다: 산술).
- **H5.** 탐지기는 대조군에서 묶인 스레드 1 이하, D = 0이고 포화 칸에서 40을 센다.

## 결정

증거가 지지하는 가장 작은 것은 **PayPal 호출의 요청 스레드 동시 상한을 옵트인(기본 0 = 이전 동작)으로 두는 것**이다([ADR-0098](../02-decisions/adr/ADR-0098-paypal-call-bulkhead-opt-in.md), D-176). 결제 모듈이 소유하는 `PayPalCallBulkhead` 한 클래스(약 50줄)이며 `Semaphore`와 Micrometer 계수기 하나로 구현하고 Resilience4j는 들이지 않는다(필요한 기능이 세마포어 하나이고 요청 스레드만 세는 판별은 어차피 직접 써야 한다). breaker와 읽기 상한 변경은 도입하지 않는다(위 해석과 ADR 대안 표). 위치 서비스 호출은 같은 결함이 측정됐지만 호출 종류별 거절 경로를 따로 검증해야 해서 이번에는 바꾸지 않고 재검토 조건에 수치로 남겼다.

기본값을 켜지 않은 이유: 평상시 동시 PayPal 호출 수(실제 Sandbox·운영 지연)를 측정하지 못했다. 가짜 서버로는 포화의 기전과 상한의 효과만 확인했다.

## 회귀 시험과 변이 확인

`PayPalBulkheadIntegrationTest`(실제 PostgreSQL 18.6 Testcontainers, 실제 `PayPalRestClient`, 로컬 PayPal 대역, 상한 1) 3개를 추가했다. 불변식은 돈과 상태다.

1. `requestThreadOverTheLimitIsRejectedBeforeSendingAndItsPaymentCanBeRetried`: 상한을 쥔 호출이 있을 때 다른 요청 스레드의 create-order는 PayPal에 도달하지 않고(대역이 받은 create-order 1건) 결제는 `UNKNOWN`이며 주문 ID가 없고, 상한이 풀린 뒤 같은 요청의 재시도가 새 주문을 만든다(2건).
2. `rejectedCaptureSendsNothingAndTheRetryCapturesExactlyOnce`: authorize된 결제의 capture가 거절되면 PayPal capture 0건·결제 `UNKNOWN`이고, 재시도 뒤 `CAPTURED`이며 capture가 정확히 1건이다.
3. `callsFromNonRequestThreadsAreNotLimitedWhileRequestThreadsHoldTheLimit`: 서블릿 요청 밖의 스레드(복구 worker와 같은 조건)는 상한을 쥔 호출이 있어도 PayPal에 도달하고 거절 계수기가 늘지 않는다.

`PayPalIntegrationTestSupport`에 다음 create-order 호출 하나를 붙잡는 훅(`holdNextCreateOrder`)을 더했다. 변이 확인(같은 시험을 변경 전·후 코드에 실행):

| 코드 | 시험 3개 결과 |
|---|---|
| 수정 후(실제) | 3 통과, 실패 0 |
| M0: 변경 전 운영 코드(`PayPalRestClient`를 `HEAD` 원본으로) | 2 실패(1, 2번): 거절이 없어 대역이 2건을 받고 capture가 나감 |
| M1: worker 스레드도 상한 대상 | 1 실패(3번) |
| M2: 거절하면서도 호출을 먼저 보냄 | 2 실패(1, 2번) |

변이마다 원본으로 복원한 뒤 `cmp`로 동일함을 확인했다. 기존 `atlas-payment` 시험은 그대로 통과한다(`:atlas-payment:test`).

## 후속

- 위치 서비스 호출의 동시 상한: 재검토 조건은 ADR-0098 ③.
- 코드 읽기에서 본 것(측정하지 않음): `OrderServiceImpl.java:187`·`CheckoutPaymentServiceImpl.java:130`의 `prepareInitial`/`prepareCheckout`이 호출자의 DB transaction 안에서 `OwnerLockRegistry`의 `synchronized`를 기다릴 수 있다(같은 주문의 중복 제출이 PayPal 호출 중인 스레드가 잠금을 쥔 경우). 연결을 쥔 채 기다리는 시간의 상한은 PayPal 읽기 상한이다. 측정 전에는 결함으로 보지 않는다.

## 한계

- 가짜 PayPal은 용량 제한이 없어 breaker가 막으려는 되돌아온 폭주 진동은 만들지 못했다. H4b는 의존성이 계속 느린 구간만 본다.
- 웹훅 검증 경로를 대표로 썼다. 구매자 `POST /payment` 경로를 부하로 지나지 않았다. 같은 `PayPalRestClient`를 쓰지만 OwnerLock 직렬화와 FX 조회가 앞에 있다.
- 호스트가 공유되어 시간 값(p95, 복구 초)은 맥락이다. 결론은 묶인 스레드 개수와 요청 개수로 냈다.
- 위치 서비스는 40/s 한 점만 측정했다(20/s 범위 밖). PayPal 8/s × 3 s는 포화하지 않아 읽기 상한 3 s 비교(A4)는 지연 10 s 입력으로만 했다.
- 가짜 서버가 응답을 보내기 전에 클라이언트가 끊으면 `aborted`로만 세고 PayPal에서 commit된 효과는 모사하지 않는다(PayPal 쪽 commit 후 응답 유실은 EXP-050이 실제 Sandbox로 다뤘다).
- 거절 계수기 값은 칸에서 수집하지 못했다(표집기 필터 이름 오류). "보내기 전 거절"은 k6와 가짜 서버의 호출 수 차이로 추정했고 시험이 직접 확인한다.
- 중간에 버린 실행을 숨기지 않는다: (1) 첫 실행을 17:00에 시작한 직후 중복 실행기를 발견해 끊었고(4개 칸 디렉터리를 `aborted-*`로 보존) 17:00부터 다시 시작했다. (2) 17:39에 중복 실행기가 앱을 바꿔 `armA1` 반복 3과 `armB2` 반복 1이 망가져 `aborted-*`로 보존하고 같은 조건으로 다시 실행했다. (3) A2·A3 첫 실행은 breaker 상태 이월로 A3가 무효여서 새 프로세스 재측정을 표에 같이 적었다. (4) 하네스 확인용 smoke 실행 3개의 출력 폴더는 정리하면서 지웠다(어떤 표에도 쓰지 않았다).
- 하네스 확인 실행 일부가 S4의 부하 구간(~16:35 이전)과 겹쳤을 수 있어 그 값은 쓰지 않았다. 표의 모든 칸은 호스트 잠금을 쥔 구간(16:58 이후)에서 새로 측정했다.

## 주장 표

| 주장 | 확신도 | 근거 |
|---|---|---|
| 묶인 요청 스레드 수 ≈ 도착률 × 지연 | confirmed(n=3, 칸 8개) | 위 PayPal·위치 칸 |
| PayPal 지연 10 s에서 8/s면 PayPal을 쓰지 않는 카탈로그 GET이 전부 저하된다 | confirmed(n=3, D = 1.0) | PayPal 칸 |
| 같은 현상이 위치 서비스(읽기 상한 2 s) 40/s에서 난다 | confirmed(n=3, D ≥ 0.985) | 위치 칸 |
| λ·h = 40(4/s × 10 s)에서도 무관한 요청이 밀린다 | not confirmed(반증, n=3, D = 0) | PayPal 칸 |
| 요청 스레드 동시 상한 20·30이 무관한 요청을 지킨다 | confirmed(n=3씩) | 비교 칸 |
| 거절은 PayPal에 아무것도 보내기 전에 일어나고 capture가 중복되지 않는다 | confirmed(PostgreSQL 시험, 변이 M0·M2로 실패 확인) | 회귀 시험 |
| 복구 worker 같은 요청 밖 스레드는 상한 대상이 아니다 | confirmed(PostgreSQL 시험, 변이 M1로 실패 확인) | 회귀 시험 |
| 느린 호출 비율 breaker(단순·점진 복구)는 이 결함을 막지 못한다(첫 열림 11 s, 열리기 전에 40개 점유) | confirmed(n=3씩, 가짜 서버 조건) | 비교 칸 |
| 점진 복구 breaker가 단순 breaker보다 오래 열려 있거나 덜 흔들린다 | not confirmed(반증: 열림 3회 대 2회, busy 최댓값 같음) | 비교 칸 |
| 읽기 상한 3 s가 포화를 같은 입력에서 막는다 | confirmed(n=3), 문턱 이동과 UNKNOWN 증가의 대가 | 비교 칸, 산술 |
| 실제 PayPal Sandbox·운영에서 같은 문턱이다 | not confirmed | 가짜 서버만 사용 |
| 평상시 PayPal 동시 호출 수가 상한 20~30보다 충분히 낮다 | not confirmed | 실제 지연 분포를 측정하지 못함 |
| 호스트 부하가 낮은 칸의 값만 쓴 결론이다 | local-only(오염 표시 칸 7개는 방향이 같음) | 칸별 load1·다른 스택 열 |

## 삭제한 파일의 내용

아래 구동·분석 파일은 저장소에 올리지 않았고 작업 폴더에 그대로 남아 있다(지우지 않았다). 결과의 정본은 위 표이고, 이 절은 같은 측정을 다시 하는 데 필요한 동작·매개변수·명령을 적는다. 파일 자체가 필요하면 이 PR 이력에는 없으므로 아래 명세로 다시 만든다.

### 가짜 PayPal·위치 서버 (Python asyncio 약 190줄)

- 인자: `<PayPal 포트> <위치 포트> <제어 포트>`(모두 127.0.0.1). 키 입력은 HTTP/1.1 keep-alive, chunked 본문과 `Content-Length` 본문을 모두 읽는다(Spring `RestClient`가 JSON 본문을 chunked로 보낸다).
- PayPal 응답: `POST /v1/oauth2/token` → `{"access_token":"fake-…","token_type":"Bearer","expires_in":32400}`. `POST /v1/notifications/verify-webhook-signature` → `{"verification_status":"SUCCESS"}`. `POST /v2/checkout/orders` → `PAYER_ACTION_REQUIRED`와 `payer-action` 링크. 그 밖의 경로는 `{}`.
- 위치 응답: `/availability/` 경로는 `{"available":100}`, `POST …/reservations`는 `RESERVED`와 2분 뒤 `expiresAt`.
- 제어 API(제어 포트, POST): `/pp?mode=<m>&delay_ms=<n>`, `/loc?mode=<m>&delay_ms=<n>`, `/token?mode=ok|delay|hang`, `/reset`, `GET /stats`. 데이터 호출 모드: `ok`, `delay`(지연 뒤 응답하되 그 사이 클라이언트가 끊으면 `aborted`를 센다), `hang`(3,600 s), `err5xx`(503), `refuse`(수신 소켓을 닫음). 토큰 호출은 데이터 호출과 독립 모드다.
- `GET /stats`: 서버별 `started`(경로 종류별 시작한 호출 수), `completed`, `aborted`, `inflight`, `max_inflight`.

### 부하 생성기 (k6 스크립트 약 70줄)

- 카탈로그: `constant-arrival-rate`, `GET /api/products?limit=20`, 20/s, 실행 전체 구간, timeout 60 s, 최대 VU 1,500.
- 느린 호출: 같은 실행기로 기준 구간 뒤에 시작해 장애 구간과 복구 구간 동안 지속. 웹훅 스트림은 `POST /api/webhooks/paypal`(필수 서명 헤더 5개와 `{"id":"WH-1","event_type":"X.Y"}`), 위치 스트림은 `POST /api/internal/order-inventory/reservations`(헤더 `X-Atlas-Service-Token`, 호출마다 새 `Idempotency-Key`, 본문 `{"orderId":<UUID>,"allocations":[{"fulfillmentLocationId":<위치 ID>,"items":[{"sellerId":<UUID>,"skuId":"sku-1","quantity":1}]}]}`).
- 모든 요청의 지연·상태·`error_code`를 `Trend` 지표 `lat`에 `kind`(cat, slow)·`phase`·`status`·`err` 태그로 기록하고 JSON으로 내보낸다(`--out json`).

### 표집기 (Python 약 100줄)

- 1 Hz로 `/actuator/prometheus?includedNames=…`를 읽어 `tomcat_threads_busy_threads`, `tomcat_threads_current_threads`, `hikaricp_connections_active|pending|timeout_total`, `http_server_requests_seconds_count`(uri별·상태별), `atlas_payment_paypal_bulkhead_rejected_total`을 JSON 줄로 기록한다.
- 2 s마다 `jcmd <pid> Thread.print`의 `http-nio-*-<앱 포트>-exec-*` 스레드를 스택 문자열로 분류한다: `PayPalRestClient` 또는 `PayPalPaymentProvider` 포함 → `paypal`; `HttpLocationClient` 포함 → `location`; `HikariPool.getConnection` 포함 → `hikari`; `ThreadPoolExecutor.getTask`·`TaskQueue.take|poll` 포함 → `idle`; 나머지 → `other`. 분류는 이 순서의 첫 일치다.

### 칸 실행기와 매트릭스 (Python 약 200줄)

- 칸 실행기 인자: `<라벨> <느린 호출 도착률/s> <h(ms)> <반복 번호> [--fake-delay-ms N] [--mode delay|ok] [--kind webhook|loc] [--t-base 5] [--t-fault 25] [--t-post 15] [--cat-rate 20]`. 순서: 가짜 서버를 `ok`로 되돌림 → (위치 칸이면 `order_inventory_reservation_workflow`를 비움) → 앱이 유휴일 때까지 대기(3 s 간격으로 가짜 서버 시작 호출 수 변화 없음, busy ≤ 2, 처리 중 0) → 표집기 시작 → k6 시작 → 기준 구간 끝에 가짜 서버를 지연 모드로 전환 → 장애 구간 끝에 `ok`로 복귀 → k6 종료 대기 → 가짜 서버 통계와 `uptime`, `measure-lock.sh status`를 칸 메타에 기록.
- 매트릭스: PayPal 칸은 (지연 3 s: 도착률 2, 4, 8/s, 장애 구간 20 s), (지연 10 s: 1, 2, 4, 8/s, 장애 구간 25 s), 대조군(8/s, 지연 0, 20 s); 위치 칸은 (10, 20, 40/s, 가짜 지연 5 s, h = 2 s, 20 s), 대조군(40/s, 지연 0, 20 s). 반복 번호마다 조건 순서를 고정 시드로 섞고 이미 끝난 칸은 건너뛴다. 맨 앞에 워밍업 칸 1개(도착률 0, 5 s)를 버린다.
- 분석: 칸마다 k6 JSON을 요청 시작 시각(완료 시각 − 지연)으로 기준 구간·정상 상태 구간·복구 구간에 나눠 D와 D_base, 상태별 개수를 세고, `jcmd` 표본에서 정상 상태 구간의 의존성 스레드 평균과 장애 구간 최댓값을 구한다. 복구 초 = 장애 끝 이후 busy가 (기준 구간 busy 최댓값 + 2) 이하가 되는 첫 1 Hz 표본까지의 시간(복구 구간 15 s 안에 오지 않으면 값 없음). 오염 표시 = 칸 시작 load1 > 12 또는 칸 시작·끝의 `measure-lock.sh status`에 `ds` 아닌 라벨의 컨테이너가 있음.

### 비교용 임시 breaker (Java 약 70줄, 사용 후 폐기)

- `ExperimentBreaker`는 `PayPalCallBulkhead.call` 안에서 요청 스레드 호출에만 적용했다. 호출 하나가 2 s 이상이거나 예외로 끝나면 나쁜 결과다.
- 단순(A2): CLOSED에서 완료한 호출 최근 10개 중 5개 이상이 나쁘면 OPEN. OPEN은 5 s 동안 모든 호출을 보내기 전에 거절. 5 s 뒤 HALF_OPEN에서 시험 호출 1개만 통과, 성공이면 CLOSED(창 초기화), 나쁘면 다시 OPEN.
- 점진 복구(A3): 열림 조건과 5 s 대기는 같다. 대기 뒤 3개 중 1개(33%) → 2개(67%) → 전부(100%)를 단계마다 최소 5 s씩 통과시키고, 단계 안에서 나쁜 결과가 나오면 다시 OPEN. 단계는 나쁘지 않은 완료 결과가 1건 이상 있어야 올라간다.
- 상태 전이는 시각(ms)과 함께 앱 로그에 한 줄씩 남겼고 칸별로 센다.

### 임시 bulkhead·breaker 구성

- A1은 이 PR의 `PayPalCallBulkhead`와 같은 구현(상한 20, 30)이다. A2·A3·A4는 위 임시 breaker와 `--atlas.payment.paypal.read-timeout=PT3S`로 구성했다. 칸마다 새 프로세스로 띄우는 실행기는 `start-app` → 카탈로그 60회 워밍업 → 칸 실행기 순서다.

### 실행 명령 (삭제했으므로 이력에서 복원하지 않고 위 명세로 다시 만들어 실행한다)

```sh
# PostgreSQL 18.6 컨테이너(라벨 atlasexp=ds)와 카탈로그 시드
docker run -d --name <이름> --label atlasexp=ds -e POSTGRES_USER=atlas -e POSTGRES_PASSWORD=<로컬 전용> \
  -e POSTGRES_DB=atlas -p 127.0.0.1:<포트>:5432 postgres:18.6 -c max_connections=200
docker exec -i <이름> psql -U atlas -d atlas -v sellers=20 -v products_per_seller=100 < load-tests/seed-catalog-read.sql
# 앱(기준 jar: 이 PR의 기준 커밋 9a14fe4c의 bootJar)
ATLAS_PAYMENT_PROVIDER=paypal PAYPAL_BASE_URL=http://127.0.0.1:<PayPal 포트> ATLAS_PAYPAL_LOCAL_PROXY_ENABLED=true \
PAYPAL_CLIENT_ID=<가짜> PAYPAL_CLIENT_SECRET=<가짜> PAYPAL_WEBHOOK_ID=<가짜> ATLAS_EXCHANGE_RATE_PROVIDER=frankfurter \
java -Xms1g -Xmx1g -jar atlas-application.jar --server.port=<앱 포트> --management.server.port=<관리 포트> \
  --spring.profiles.active=observability --atlas.order-inventory-reservation.enabled=true \
  --atlas.order-inventory-reservation.api-token=<32자 이상> --atlas.order-inventory-reservation.service-token=<32자 이상> \
  "--atlas.order-inventory-reservation.location-endpoints=<위치 ID>=http://127.0.0.1:<위치 포트>"
```
