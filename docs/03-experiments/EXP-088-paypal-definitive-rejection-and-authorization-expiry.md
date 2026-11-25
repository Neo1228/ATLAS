# EXP-088: PayPal 확정 거절과 authorization 만료·void가 결제 시도를 어떻게 끝내는가

- 질문: [EXP-081](EXP-081-paypal-double-vs-sandbox.md)은 PayPal 4xx를 모두 결과 불명(UNKNOWN)으로 두어 복구가 최대 8회 재시도한다고 적었고, [EXP-086](EXP-086-admission-k-ttl-and-authorize-window.md)은 코드가 authorization `expiration_time`을 읽지 않고 만료·void를 처리하지 않는다고 적었다. 이 두 빈 곳은 실제로 어떻게 실패하고, 어디까지 안전하게 고칠 수 있는가.
- 상태: **로컬 재현과 수정 검증**이다. 응답 모양은 EXP-081·EXP-086이 실제 Sandbox에서 본 것을 로컬 PayPal 대역(`PayPalIntegrationTestSupport`)에 옮긴 것이고, **이번 실험에서 Sandbox를 새로 호출하지 않았다.** 실제 PayPal이 만료된 authorization의 capture와 조회에 어떻게 답하는지는 관측하지 못했다.
- 날짜: 2026-11-25
- 관련: [ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md), [ADR-0030](../02-decisions/adr/ADR-0030-paypal-currency-boundary.md), [ADR-0038](../02-decisions/adr/ADR-0038-durable-payment-recovery-workflow.md), [ADR-0047](../02-decisions/adr/ADR-0047-abandoned-approval-deadline.md), [D-147](../00-governance/decision-log.md), [Issue #639](https://github.com/Neo1228/ATLAS/issues/639)

## 가설 (측정 전에 적음, 2026-11-25)

코드 읽기(`PayPalRestClient`, `PayPalPaymentProvider`, `PaymentServiceImpl.finalizePhase`)만으로 세운 예측이다. 실행 전에 적었고 아래 "가설 대조"에서 맞춰 본다.

- **H1.** 주문 생성(`POST /v2/checkout/orders`)이 422(검증 오류)를 받으면 시도는 UNKNOWN이 되고, 복구가 돌 때마다 같은 Request-Id로 PayPal을 다시 부른다(호출 수가 복구 횟수만큼 늘고 끝나지 않는다).
- **H2.** capture가 422 `AUTHORIZATION_VOIDED`를 받고 주문 조회가 `authorizations[].status=VOIDED`를 주면(PayPal이 만료·void한 authorization의 모양), 복구는 이 결과를 capture 단계에 적용하지 못한다. `captureResult`에 `VOIDED` 분기가 없어 `IllegalStateException`이 나고 복구 worker가 재시도·운영자 검토로 넘길 것이다.
- **H3.** void가 422 `PREVIOUSLY_VOIDED`를 받으면 UNKNOWN이 된다(목표 상태인 "void됨"에 이미 도달했는데도).
- **H4(통제).** 401, 429, 5xx, 연결 끊김, 그리고 422 `AUTHORIZATION_ALREADY_CAPTURED`는 확정 거절이 아니므로 수정 전후 모두 UNKNOWN이고 같은 Request-Id로 복구한다.
- **H5(수정 뒤 기대).** 확정으로 분류한 응답은 PayPal 호출 1회(capture는 호출 1회와 주문 조회)로 종결 상태(주문 생성 거절 DECLINED, 만료·void된 capture와 void 거절 VOIDED)에 도달하고, 주문은 FAILED로 한 번만 바뀌며 재고는 한 번만 반환된다.

## 환경과 방법

- 코드: 수정 전은 `origin/main` a9c41a55에 새 테스트와 대역 확장만 얹은 작업 트리, 수정 후는 이 브랜치.
- 테스트: `PayPalDefinitiveRejectionIntegrationTest`(atlas-application, 실제 PostgreSQL 18.6 Testcontainers, PayPal은 MockWebServer 대역). 복구는 `PaymentRecoveryWorker`를 운영 값 그대로(최대 시도 8회, 24시간 window) 만들고 재시도 간격만 1~5ms로 줄여 같은 스레드에서 `poll()`을 20회 돌렸다. 주문·재고·체크아웃은 실제 서비스다.
- 대역 확장: `queueRejection(operation, status, issue)`로 다음 요청 하나에 EXP-081 모양의 PayPal 오류 본문(`name`, `details[].issue`)을 준다. authorization 응답과 주문 조회에 `expiration_time`을 넣었다(Sandbox 값 `2026-12-24T01:19:22Z`).
- 표본: 칸마다 **n=1**이다. 같은 실행을 반복해 변동을 재지 않았다. 대역과 같은 JVM 안의 결정적 흐름이라 같은 입력은 같은 결과를 낸다고 기대하지만 반복으로 확인하지 않았다.
- 이 머신은 다른 실험과 함께 쓰였다(실행 시작 시 `uptime` load average 5~17, 8코어 VM). **시간은 재지 않았고 아래 결론은 호출 수와 상태 불변식에만 근거한다.**

## 결과 1: 수정 전 동작 (재현)

수정 전 작업 트리에서 같은 테스트를 돌렸다. 결과 불명 통제 2건은 통과해야 하고 나머지는 실패해야 한다.

| 시나리오 | 수정 전 관측 |
| --- | --- |
| 주문 생성이 422 `DECIMALS_NOT_SUPPORTED`로 거절(대역이 10번 거절하도록 큐) → 체크아웃 제출 → worker 20회 | PayPal 주문 생성 호출 **9회**(제출 1회 + worker 재시도 8회)가 모두 같은 거절. 테스트가 기대한 1회와 다름 |
| capture 422 `AUTHORIZATION_VOIDED` 뒤 복구에서 주문 조회가 `authorizations[0]=VOIDED` | `IllegalStateException: Provider result VOIDED is invalid for CAPTURE` (H2와 일치) |
| capture 422 `AUTHORIZATION_EXPIRED` | UNKNOWN |
| capture 거절이지만 주문 조회에는 capture가 있음 | UNKNOWN(조회 결과를 쓰지 않는다) |
| void 422 `PREVIOUSLY_VOIDED` | UNKNOWN |
| 429 주문 생성 거절 → 복구 | 통과: UNKNOWN 뒤 같은 `PayPal-Request-Id`로 재전송해 진행 |
| capture 422 `AUTHORIZATION_ALREADY_CAPTURED`, 주문 조회와 어긋나는 `AUTHORIZATION_VOIDED` | 통과: UNKNOWN 유지 뒤 복구로 CAPTURED |

- 단일 테스트 클래스 8건(G2 확인 제외) 중 6건 실패, 2건 통과(통제). 첫 실행(payment 수준 테스트만, 7건)은 5건 실패, 2건 통과.
- 만료·void 체크아웃 흐름(주문·재고까지)은 수정 전에 `capture` 호출 수 단정(기대 1회, 관측 2회)에서 먼저 실패해 최종 상태를 해석하지 않았다. 대역의 큐가 소진되어 세 번째 복구에서 capture가 성공하는 흐름이라 "수정 전에는 주문이 어떻게 끝났는가"의 근거로 쓰지 않는다. 만료·void된 authorization의 수정 전 동작 근거는 위 `IllegalStateException` 한 건(payment 수준)이다.
- **H1 보정.** "끝나지 않는다"가 아니라 복구 최대 시도 8회에서 멈춘다(제출 1회 + 8회 = 9회). 그 뒤는 운영자 검토다(`PaymentRecoveryWorker.process`가 예외마다 `reschedule`, `maxAttempts` 초과 시 NEEDS_REVIEW — 코드 읽기, NEEDS_REVIEW 도달은 이 실험에서 직접 단정하지 않았다).

## 결과 2: 수정 후

| 시나리오 | 수정 후 관측 |
| --- | --- |
| 주문 생성 422 확정 거절 + worker 20회(체크아웃, 주문, 재고 실제 서비스) | PayPal 호출 **1회**, 결제 DECLINED, 주문 FAILED, 재고 available 1·reserved 0·claimed 0, 복구 job COMPLETED |
| 만료·void된 authorization의 capture 거절 + 조회에 capture 없음 (`AUTHORIZATION_VOIDED`, `AUTHORIZATION_EXPIRED` 각 1) | 결제 VOIDED, authorization 비활성, capture 호출 1회. 같은 시도에 두 번째 `reconcile`을 해도 PayPal을 다시 부르지 않음 |
| 위 흐름을 체크아웃·worker로 | 주문 FAILED, 재고 반환 1회, job COMPLETED, capture 호출 1회 |
| capture 거절이지만 조회에 capture 있음 | CAPTURED |
| void 422 `PREVIOUSLY_VOIDED` | VOIDED, void 호출 1회(두 번째 요청은 호출 없음) |
| 429 주문 생성, `AUTHORIZATION_ALREADY_CAPTURED`, 조회와 어긋난 `AUTHORIZATION_VOIDED` | UNKNOWN 유지 뒤 복구(수정 전과 같음) |
| authorize 응답의 `expiration_time` | `payment_attempt.authorization_expires_at`에 기록, NEEDS_REVIEW 목록 행에 노출 |

`PayPalDefinitiveRejectionIntegrationTest` 9건 통과(실패 0, 건너뜀 0). 실행: `./gradlew :atlas-application:test --tests '*PayPalDefinitiveRejectionIntegrationTest'`.

## 결과 3: 변이 검증

규칙을 일부러 깨 해당 테스트가 실패하는지 확인했다(각 1회, 확인 뒤 복원하고 `cmp`로 원본과 같음을 확인).

| 변이 | 실패한 테스트 |
| --- | --- |
| capture 거절 뒤 "주문에 capture가 있으면 그 결과를 따른다" 확인 삭제 | `captureRejectedButOrderShowsACaptureEndsCaptured`만 |
| 주문 생성 거절 분류에서 상태 조건(400·422) 삭제(429도 확정 거절) | `throttledOrderCreationStaysUnknownAndRecoversWithTheSameRequestId`만 |
| `authorization_expires_at` 기록 줄 삭제 | `authorizationExpirationIsRecordedAndShownInTheReviewList`만 |

앞의 두 변이는 한 번에 같이 넣고 한 번 실행했고 두 테스트가 각자 실패했다(변이와 실패의 대응은 테스트 이름과 규칙으로 판단했다).

## 가설 대조

| 가설 | 판정 |
| --- | --- |
| H1 | 부분 일치. 재시도는 8회에서 멈춘다(호출 9회). 같은 Request-Id로 보내는지는 429 통제 테스트에서만 확인했고 거절 반복 시나리오에서 헤더를 비교하지 않았다 |
| H2 | 일치(`IllegalStateException`) |
| H3 | 일치 |
| H4 | 일치(통제 2건 수정 전후 모두 통과) |
| H5 | 일치(주문 생성 호출 1회, 주문 FAILED 1회, 재고 반환 1회, capture 호출 1회). capture 뒤 주문 조회 횟수는 세지 않았다 |

## 해석

- 재현된 결함 두 가지는 확정 거절 때문에 낭비되는 호출(주문 생성 8회 재시도)과, 이미 void·만료된 authorization이 복구에서 예외만 반복하다 운영자 검토로 가는 경로다. 후자는 4xx 분류와 무관하게 "주문 조회가 VOIDED라고 말해도 capture 단계가 받지 못한다"는 상태 전이 구멍이다.
- 이번에 고친 범위는 "응답이 작업이 일어나지 않았음을 증명한다"고 말할 수 있는 쌍만이다([ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md)의 표). capture 거절은 본문만 믿지 않고 주문 조회로 한 번 더 확인한다.
- 돈 경로 안전성은 두 가지로 지켰다. 분류하지 않은 모든 4xx, 5xx, 연결 실패, 본문이 PayPal 형식이 아닌 4xx는 이전처럼 UNKNOWN이다(통제 테스트). 확정은 DECLINED·VOIDED로만 가고, CAPTURED로 가는 경로는 주문 조회가 capture를 보여 줄 때뿐이다.

## 한계

- **Sandbox 미호출.** 응답 본문은 EXP-081·EXP-086 문서에 적힌 관측(조건별 n=1, 만료 값 n=3)을 대역에 옮긴 것이다. 저장소의 기록된 Sandbox 본문(`responses.json`)에는 `AUTHORIZATION_VOIDED`, `PREVIOUSLY_VOIDED`, 주문 생성 검증 오류가 **없다**(`ORDER_NOT_APPROVED`, `AUTHORIZATION_ALREADY_CAPTURED` 등 6건만). 이 규칙들의 근거는 EXP-081의 문장이고 기록된 응답 재생이 아니다.
- **`AUTHORIZATION_EXPIRED`와 만료 뒤 주문 조회는 관측하지 못했다.** 29일을 기다릴 수 없었다. 이슈 이름은 PayPal OpenAPI v2.12 예시(`authorizations_capture_422_expired`)에서 읽었고, 조회가 만료된 authorization을 `VOIDED`로 보이는지 `CREATED`로 보이는지 모른다(그래서 만료 거절은 둘 다 받는다).
- 같은 `PayPal-Request-Id` 재생의 보존 기간(idempotency)은 여전히 재지 않았다. 확정 분류는 재생에 의존하지 않도록 정했다(주문 생성 검증 거절은 재전송해도 같다).
- 호출 수는 단일 스레드, 한 번의 실행이다. 복구 간격을 1~5ms로 줄였고 실제 24시간 window, 15분 최대 backoff 동작은 보지 않았다. 다중 replica 경쟁은 이 실험이 건드리지 않는다.
- 주문 생성 400·422를 확정 거절로 본 근거는 "검증 거절은 리소스를 만들지 않는다"는 PayPal 오류 규약에 대한 가정이다. 400·422에서 주문이 만들어진 경우를 관측하지 못했고 반대 사례도 보지 못했다.
- 구매자 쪽 거절(카드 거절, `INSTRUMENT_DECLINED` 등)과 authorize 거절은 Sandbox로 만들 수 없어 분류하지 않았고 계속 UNKNOWN이다.
- reauthorize, 자동 void, 만료·void webhook, 알림 임계는 구현하지 않았다. 이유와 되돌릴 조건은 ADR-0079에 적었다.
- Live 환경 응답은 Sandbox와 같다는 보장이 없다.

## 재현

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/mz01-juwannam/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true
./gradlew :atlas-application:test --tests '*PayPalDefinitiveRejectionIntegrationTest'
./gradlew :atlas-payment:test --tests '*PayPalSandboxResponseReplayTest'
```

수정 전 동작은 `origin/main`에 대역 확장(`PayPalIntegrationTestSupport`)과 같은 테스트 클래스(G2 확인 제외)만 얹어 같은 명령으로 돌리면 실패한다.

## 되돌리는 법

- 코드 변경은 provider 분류, `captureResult`의 `VOIDED` 분기, 만료 시각 기록과 목록 노출이다. PR을 되돌리면 이전 동작(모든 4xx UNKNOWN)으로 돌아간다.
- V3 마이그레이션은 이미 적용된 DB에서 지울 수 없다(Flyway는 앞으로만 간다). 되돌릴 때는 NULL 허용 열 `authorization_expires_at`이 사용되지 않은 채 남는다. 필요하면 후속 마이그레이션으로 열을 삭제한다.

## 후속 제안(적용하지 않음)

- 수동 실행 계약 검사(`.github/workflows/paypal-sandbox-contract.yml`)에 주문 생성 검증 거절(422)과 void 재전송 거절의 `details[].issue`를 확인하는 단계 추가: 이번 규칙이 기대는 두 응답을 실제 Sandbox로 확인한다.
- 29일을 기다려 만료 뒤 capture와 주문 조회 응답을 기록하고 `AUTHORIZATION_EXPIRED` 가정을 확정한다.
