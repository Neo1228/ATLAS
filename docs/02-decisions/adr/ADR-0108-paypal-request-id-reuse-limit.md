# ADR-0108: PayPal 환불은 첫 요청 뒤 30일이 지나면 같은 PayPal-Request-Id로 다시 보내지 않는다

- 상태: Accepted(에이전트 결정. 값 30일은 가정이며 재검토 조건을 아래에 적는다)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0106](ADR-0106-payment-recovery-retry-budget-and-distributed-candidates.md)(결제 복구 24시간 창), [ADR-0107](ADR-0107-multi-instance-shared-state-guards.md)(다중 인스턴스 중복 호출은 PayPal 멱등이 흡수)
- 근거: [EXP-119](../../03-experiments/EXP-119-paypal-request-id-retention.md), 이슈 [#735](https://github.com/Neo1228/ATLAS/issues/735)

## 문제

결제 시도는 PayPal 요청 id 4개(주문 생성, authorize, capture, void)를 한 번 만들고(`PaymentServiceImpl.java:753-756`), 환불 작업은 1개를 만든다(`PaymentRefundWorkflow.java:181-183`). 재시도는 모두 같은 id를 다시 보낸다. PayPal은 이 id를 정해진 기간만 기억한다. 그 기간이 지난 뒤 같은 id로 다시 보낸 요청이 돈을 한 번 더 움직이는 엔드포인트가 있는지, 그리고 그렇게 늦게 다시 보내는 경로가 있는지 확인한 적이 없었다.

## PayPal 문서가 말하는 것 (2026-11-28에 연 문서)

| 엔드포인트(ATLAS가 부름) | 보관 기간 | 보관 기간이 지난 뒤 같은 요청에 대한 문서상 응답 | 돈이 두 번 움직일 수 있나 |
|---|---|---|---|
| `POST /v2/checkout/orders`(주문 생성) | 6시간, 계정 관리자 요청 시 최대 72시간: "The server stores keys for 6 hours. The API callers can request the times to up to 72 hours by speaking to their Account Manager."(PayPal GitHub OpenAPI `checkout_orders_v2.json` 2.32). 개발자 사이트의 현행 스키마 2.36과 페이지에는 이 문장이 없고 "A unique ID identifying the request header for idempotency purposes."만 있다 | 문서에 없다. 새 주문이 생긴다고 보고 다룬다 | ✗ 아니다. 새 주문은 구매자 승인 없이는 돈을 움직이지 않는다. ATLAS는 주문 id가 없을 때만 다시 만든다 |
| `POST /v2/checkout/orders/{id}/authorize` | 위와 같은 6시간 문장(2.32) | `ORDER_ALREADY_AUTHORIZED`: "Order already authorized.If 'intent=AUTHORIZE' only one authorization per order is allowed." | ✗ 아니다. 주문당 authorization은 하나다. ATLAS는 주문 조회가 `APPROVED`일 때만 authorize를 보낸다(`PayPalPaymentProvider.java` `createOrAuthorize`) |
| `POST /v2/checkout/orders/{id}/capture` | 위와 같은 6시간 문장(2.32) | `ORDER_ALREADY_CAPTURED` | ✓ 해당 없음. ATLAS는 `intent=AUTHORIZE`라 이 엔드포인트를 부르지 않는다 |
| `POST /v2/payments/authorizations/{id}/capture` | 문서에 값이 없다(스키마 2.12와 페이지 모두 "A unique ID identifying the request header for idempotency purposes.") | `AUTHORIZATION_ALREADY_CAPTURED`: "If `final_capture` is set to to `true`, additional captures are not possible against the authorization." | ✗ 아니다. ATLAS는 전액에 `final_capture: true`를 보낸다(`PayPalRestClient.java:121-130`) |
| `POST /v2/payments/authorizations/{id}/void` | 문서에 값이 없다(위와 같은 문장) | `PREVIOUSLY_VOIDED`, `PREVIOUSLY_CAPTURED` | ✗ 아니다. void는 돈을 움직이지 않고 `PREVIOUSLY_VOIDED`는 완료로 다룬다 |
| `POST /v2/payments/captures/{id}/refund` | 45일: "The user can make the call again with the same ID in the `PayPal-Request-Id` header for up to 45 days."(API requests 페이지의 refund 예) | 문서에 없다. 남은 환불 가능 금액이 모자랄 때만 `REFUND_AMOUNT_EXCEEDED`·`CAPTURE_FULLY_REFUNDED`로 거절한다 | ✓ 그렇다. ATLAS는 금액을 명시해 보내므로 부분 환불은 남은 금액 안에서 새 환불이 될 수 있다(EXP-119 H1, 키를 잊는 stub에서 3/3) |

공통 문장: "You can retry idempotent calls that fail with network timeouts or HTTP `5xx` status codes for as long as the server stores the ID.", "when you omit the `PayPal-Request-Id` header from a request, PayPal duplicates the request."([Idempotency](https://developer.paypal.com/api/rest/reference/idempotency/), 2026-08-11 갱신). 보관 기간이 지난 키가 "헤더를 빼고 보낸 요청"처럼 다뤄진다는 문장은 없다. 이 ADR은 그렇게 다뤄질 수 있다고 보수적으로 가정한다. 실제 PayPal에서 45일 뒤 재전송은 재현하지 않았다(sandbox에서 45일을 기다려야 한다).

출처 URL: [API requests](https://developer.paypal.com/api/rest/requests/#paypal-request-id), [Idempotency](https://developer.paypal.com/api/rest/reference/idempotency/), [Orders v2 schema(개발자 사이트)](https://developer.paypal.com/api/orders/v2/schema.json), [Payments v2 schema(개발자 사이트, `x-errors`)](https://developer.paypal.com/api/payments/v2/schema.json), [Orders v2 OpenAPI(GitHub)](https://github.com/paypal/paypal-rest-api-specifications/blob/main/openapi/checkout_orders_v2.json), [Orders v2 error messages](https://developer.paypal.com/api/orders/v2/error-messages), 엔드포인트 페이지 [orders-create](https://developer.paypal.com/api/orders/v2/orders-create/), [authorizations-capture](https://developer.paypal.com/api/payments/v2/authorizations-capture/), [authorizations-void](https://developer.paypal.com/api/payments/v2/authorizations-void/), [captures-refund](https://developer.paypal.com/api/payments/v2/captures-refund/).

## 같은 id를 다시 보내는 경로와 최대 나이 (현재 기본값)

| 경로 | 다시 보내는 POST | file:line | 같은 id의 최대 나이 |
|---|---|---|---|
| 결제 복구 worker | 주문 생성, authorize, capture, void | `PaymentRecoveryWorker.java:74-78`(202회, `PT24H`), `:232`, `:256`, `:258`; 창 검사 `PaymentRecoveryRepository.java:263` | 창 하나에 24시간. 창은 운영자 재큐(`PaymentRecoveryRepository.java:410-441`, 최대 3회 `PaymentRecoveryOperatorService.java:22`)와 구매자 복귀(`PaymentRecoveryRepository.java:73-90`)에 다시 시작한다. 재큐 시점에 상한이 없어 절대 나이에도 상한이 없다 |
| 구매자 복귀·클라이언트 reconcile·취소용 void | authorize, capture, void | `PaymentServiceImpl.java:164-199`, `:201-265`, `:329`, 공통 `performPhase` `:622-650` | 상한 없음. 복구 job 상태를 보지 않는다 |
| 같은 멱등 키 환불 재요청(판매자 환불, 반품 검수) | refund | `OrderRefundServiceImpl.java:150-155`, `PaymentRefundWorkflow.java:140-150` → `:206-217` | 수정 전 상한 없음, 수정 뒤 30일 |
| 환불 복구 worker | refund | `PaymentRecoveryWorker.java:265-277`(`:273`) | 수정 전 상한 없음(재큐), 수정 뒤 30일 |
| PayPal 웹훅 | 없음(상태 반영만) | `PaymentWebhookProcessor.java:126-160` | 해당 없음 |

돈이 두 번 움직일 수 있는 엔드포인트(refund)에 상한 없는 경로가 둘 있었다.

## 결정

1. `PayPalPaymentProvider.refund`는 환불 작업의 첫 요청 시각(`payment_refund_operation.created_at`)에서 30일(`REFUND_REQUEST_ID_REUSE_LIMIT`)이 지났으면 PayPal을 부르지 않고 결과 불명(`UNKNOWN`)을 돌려준다. 작업 상태는 그대로 남고(`updateResult`의 `COALESCE`), 복구 worker는 창 끝에 `NEEDS_REVIEW`로 넘긴다. 운영자는 PayPal에서 capture의 환불을 확인한다(운영 가이드).
2. 보관 기간이라는 PayPal 사실과 그 상한은 PayPal 어댑터 한 곳에 둔다. 환불 workflow는 첫 요청 시각만 넘긴다(`PaymentRefundRequest.firstRequestedAt`).
3. 결제 시도 쪽 POST(주문 생성·authorize·capture·void)에는 상한을 두지 않는다. 위 표대로 문서가 말하는 응답이 돈을 두 번 움직이지 않고, capture는 `final_capture: true`로 보낸다.

## 대안과 기각 이유

- 재전송 전에 PayPal에서 환불 목록을 조회해 맞춘다: 주문 조회(`GET /v2/checkout/orders/{id}`)의 환불 목록은 우리 작업과 맞출 식별자가 없다(ATLAS는 환불에 `invoice_id`·`custom_id`를 보내지 않는다). 같은 금액의 부분 환불이 둘이면 구분할 수 없다. ✗ 기각. 환불에 작업 id를 실어 보내면 다시 잰다.
- 상한을 45일에 가깝게(예: 44일) 둔다: 문서가 45일을 무엇부터 세는지(첫 요청, 마지막 요청) 말하지 않는다. ✗ 기각.
- 상한을 복구 창(24시간)과 같게 둔다: 같은 키 재요청이 정상적으로 쓰이는 기간(판매자의 재시도, 재큐 3회)을 막는다. ✗ 기각.
- 문서에만 적는다: H1이 3/3으로 재현됐다. ✗ 기각.

## 결과

- 첫 요청 뒤 30일이 지난 결과 불명 환불은 자동으로 끝나지 않는다. 그 수는 `NEEDS_REVIEW` 환불 job과 WARN 로그 `PayPal refund was not resent`로 보인다. 그런 환불 작업을 완료·실패로 닫는 운영자 API는 없어 PayPal 대시보드 확인 뒤 수동 처리한다(운영 가이드). 이전에는 같은 키 재전송이 이 경우를 자동으로 닫았지만 45일 뒤에는 이중 환불 위험과 맞바꾼 것이었다.
- 30일 안의 재시도는 지금처럼 같은 키로 다시 보낸다(EXP-119 H3).
- 다른 JVM 사이의 중복 capture 호출(EXP-118: 요청 id 100개에 110~126건)은 원 요청이 진행 중일 때 같은 키로 나간 것이라 어떤 보관 기간보다도 짧고, capture는 `final_capture: true`라 이 결정과 무관하다(ADR-0107 "PayPal 멱등 의존" 절).

## 롤백

스키마를 바꾸지 않는다. 이전 jar로 되돌리면 30일이 지난 결과 불명 환불을 같은 키로 다시 보낸다.

## 재검토 조건

- PayPal이 결제 API의 보관 기간을 문서에 적거나 바꾸면(현재 refund 45일, Orders v2 6시간(GitHub 2.32)): 표와 상한을 다시 맞춘다.
- 환불 요청에 작업 식별자(`invoice_id` 또는 `custom_id`)를 싣게 되면: 30일 뒤에도 조회로 맞출 수 있으므로 거절 대신 조회 후 결정을 다시 잰다.
- 30일이 지나 `NEEDS_REVIEW`가 된 환불이 한 달에 1건을 넘으면: 운영자 처리 부담이 커지므로 위 식별자안을 앞당긴다.
- capture를 `final_capture: false`나 부분 금액으로 보내게 되면: capture도 돈이 두 번 움직이는 엔드포인트가 되므로 같은 상한을 둔다.
