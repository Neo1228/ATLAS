# EXP-081: 통합 테스트의 PayPal 대역은 실제 Sandbox와 같은 규칙으로 답하는가

- 질문: 결제 결과 불명 복구 테스트([EXP-068](EXP-068-stage-evidence-map.md) 5단계 근거)는 `MockWebServer`로 만든 PayPal 대역(`PayPalIntegrationTestSupport`)에 기댄다. 대역의 상태 코드, 필드 모양, 오류 본문, 재전송 규칙이 실제 PayPal Sandbox와 같은가. 다르면 어느 쪽이 위험한가.
- 상태: 탐색 실험과 수정. **애플리케이션을 거치지 않고 Sandbox API를 직접 호출한 관측**이다(운영 클라이언트 `PayPalRestClient`는 실제 Sandbox에 1회, 응답 기록 재생으로 별도 확인). 앱 전체 흐름(주문, 정산, worker, 실제 webhook 전달)의 end-to-end 확인이 아니다.
- 날짜: 2026-11-25
- 관련: [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md)(같은 Sandbox에서 capture 응답 유실 3건), [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md)(지급 쪽 대조; 이 문서는 결제 쪽), [D-139](../00-governance/decision-log.md)

## 왜 했는가

[EXP-068](EXP-068-stage-evidence-map.md)은 "PayPal 대역의 응답 규칙이 실제 Sandbox와 같은지는 비교하지 않았다"고 적었고, `.github/workflows/paypal-sandbox-contract.yml`은 주문 생성과 조회만 실제 Sandbox로 확인한다. 대역이 실제보다 너그러우면 "응답이 유실돼도 같은 Request-Id로 다시 보내면 안전하다", "알 수 없는 ID는 오류가 된다" 같은 주장이 대역 위에서만 참이 된다.

## 환경과 방법

- 대상: `api-m.sandbox.paypal.com`만. 스크립트가 다른 호스트면 중단하도록 했고 Live endpoint는 호출하지 않았다.
- 자격 증명: git 제외 `.env.local`에서 프로세스 안에서만 읽었고 출력하지 않았다. 구매자 승인은 Sandbox 구매자 계정으로 headless Chromium(Playwright)이 승인 화면을 눌러 수행했다(화면 순서는 EXP-050과 같다). 사람이 개입하지 않았다.
- 표본: HTTP 요청 108건(스크립트 6회 실행), 주문 생성 호출 24건(재전송 포함)과 그중 구매자가 승인한 주문 4건(승인 뒤 authorize, capture, refund, void 경로). 운영 Java 클라이언트로 잘못된 client 자격 증명 토큰 요청 1회. **조건별 n=1이다.** 같은 요청을 반복해 변동을 재지 않았다.
- 기록: 응답 본문은 로컬에만 두었다. 저장소에는 식별자(주문, 승인, capture, refund ID), 구매자와 판매자 정보, `debug_id`, 링크 주소를 문서 예시 값으로 치환한 18건만 올렸다(`atlas-payment/src/test/resources/paypal-sandbox/responses.json`). 치환 뒤 원본 ID, 이메일 문자열이 남지 않았는지 스크립트로 검사했다.
- 하네스(Python 요청 스크립트, Node 승인 스크립트)는 올리지 않았다. 자격 증명과 로컬 `.env.local`에 의존한다. 재현은 [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md)과 같은 방식이다.
- 이 머신은 다른 작업과 함께 쓰였으므로 시간은 재지 않았다.

## ATLAS가 부르는 PayPal endpoint(결제)

| 번호 | 호출 | 코드 |
| --- | --- | --- |
| 1 | `POST /v1/oauth2/token` | Spring OAuth2 client_credentials(`PayPalOAuth2ClientConfiguration`) |
| 2 | `POST /v2/checkout/orders` | `PayPalRestClient.createOrder` |
| 3 | `GET /v2/checkout/orders/{id}` | `getOrder` |
| 4 | `POST /v2/checkout/orders/{id}/authorize` | `authorizeOrder` |
| 5 | `POST /v2/payments/authorizations/{id}/capture` | `captureAuthorization` |
| 6 | `POST /v2/payments/authorizations/{id}/void` | `voidAuthorization` |
| 7 | `POST /v2/payments/captures/{id}/refund` | `refundCapture` |
| 8 | `GET /v2/payments/refunds/{id}` | `getRefund` |
| 9 | `POST /v1/notifications/verify-webhook-signature` | `verifyWebhook` |

지급 endpoint(`/v1/payments/payouts`)는 EXP-073이 다뤘다. 대역은 1~9와 운영이 부르지 않는 `POST /v2/checkout/orders/{id}/capture`를 낸다. 대역은 경로를 모르면 `404 {"error":"unexpected local PayPal path"}`를 준다.

## 결과: 실제 Sandbox와 대역(변경 전)의 차이

판정: **위험** = 대역이 실제와 달라 테스트가 거짓 확신을 줄 수 있음, **무해** = 운영 코드가 그 차이를 읽지 않음.

| 번호 | 경우 | 실제 Sandbox(n=1) | 대역(변경 전) | 판정 | 조치 |
| --- | --- | --- | --- | --- | --- |
| 1 | 토큰 발급 성공 | 200, `access_token`, `app_id`, `expires_in`, `nonce`, `scope`, `token_type` | 200, 3개 필드 | 무해 | 없음 |
| 2 | 토큰 발급 실패(잘못된 secret) | 401 `{error: invalid_client, error_description}` | 없음 | **위험(운영 결함)** | 아래 "운영 코드의 어긋남 1" |
| 3 | 잘못된 Bearer 토큰 | 401 `{error: invalid_token, error_description}` | 없음 | 위험(2와 같은 경로) | 2와 같음 |
| 4 | 주문 생성(ATLAS 본문, `payment_source` 포함) | **HTTP 200**, `status=PAYER_ACTION_REQUIRED`, `links=[self, payer-action]`, `payment_source` | 200, 기본 `CREATED` + `approve` 링크 | 위험(낮음): 운영은 두 형태 모두 처리하지만 기본값이 실제 경로가 아님 | 대역 기본값을 실제와 맞춤 |
| 5 | 주문 생성(`payment_source` 없음) | 201, `status=CREATED`, `links=[self, approve, update, authorize]` | 해당 없음 | 무해(ATLAS는 항상 `payment_source`를 보냄) | 없음 |
| 6 | 같은 `PayPal-Request-Id`로 생성 재전송 | 200, **같은 주문 ID**. 본문(금액 1.00 대 2.00)이 달라도 처음 주문을 돌려주고 오류 없음 | 호출마다 새 주문 ID | **위험** | 대역이 같은 Request-Id를 재생하도록 수정 |
| 7 | Request-Id 60자 | 201 수락 | 해당 없음 | 무해(운영 검증이 38자 초과를 먼저 거절해 더 엄격) | 없음 |
| 8 | 잘못된 금액·통화 | 422 `DECIMALS_NOT_SUPPORTED`(XXX, JPY 소수), `CURRENCY_NOT_SUPPORTED`(KRW), `CANNOT_BE_ZERO_OR_NEGATIVE`, `DECIMAL_PRECISION`; 400 `INVALID_PARAMETER_SYNTAX`(`abc`), `MISSING_REQUIRED_PARAMETER`; 소문자 `usd`와 숫자형 value는 **201로 수락**; 본문 없는 POST는 415 | 없음 | 무해: ATLAS는 USD 최소 단위 정수에서 금액 문자열을 만든다 | 없음 |
| 9 | 주문 조회(승인 전/후) | 200. 승인 전 `PAYER_ACTION_REQUIRED`, 승인 뒤 `APPROVED`(`payer` 추가) | 200, 필드 부분집합 | 무해 | 없음 |
| 10 | 알 수 없는 주문 ID 조회 | **404** `{name: RESOURCE_NOT_FOUND, message, debug_id, details[{issue: INVALID_RESOURCE_ID, description}], links[]}`. ID 형식(17자, `ORDER-<uuid>`, 1자)과 무관 | **200 `APPROVED`, 금액 0.00 USD** | **위험** | 404와 실제 본문 모양으로 수정 |
| 11 | 승인 전 주문 authorize | 422 `ORDER_NOT_APPROVED` | 항상 성공 | 위험(낮음): 구매자 승인 상태기계를 대역이 모른다 | 하지 않음(아래 "대역이 모르는 것") |
| 12 | authorize 성공 | **201**, `status=COMPLETED`, `purchase_units[].payments.authorizations[]{id,status=CREATED,amount,expiration_time}` | 200, 같은 핵심 필드 | 무해 | 없음 |
| 13 | 같은 Request-Id로 authorize 재전송 | 200, **같은 authorization ID** | 새 authorization ID | **위험** | Request-Id 재생 추가 |
| 14 | 다른 Request-Id로 두 번째 authorize | 422 `ORDER_ALREADY_AUTHORIZED` | 두 번째 authorization 성공 | **위험** | 422로 수정 |
| 15 | authorize 후 주문 조회 | 200, `COMPLETED`, `authorizations[0].status=CREATED` | 임의 ID `AUTH-<주문 ID>`(authorize가 준 ID와 다름) | 위험(낮음) | 발급한 ID를 돌려줌 |
| 16 | capture 성공(`Prefer: return=representation`) | 201, `id, status, amount, final_capture, seller_receivable_breakdown, ...` | 200, `id, status, amount` | 무해 | 없음 |
| 17 | capture 성공(`Prefer` 없음) | 201, `id, status, links`만(**금액 없음**) | 같은 모양 | 일치 | 없음(운영 코드가 `Prefer`를 보내는 이유와 같다) |
| 18 | 같은 Request-Id로 capture 재전송 | 200, 같은 capture ID | 같은 capture ID | 일치 | 없음 |
| 19 | 다른 Request-Id로 두 번째 capture(전액 capture 뒤) | 422 `AUTHORIZATION_ALREADY_CAPTURED` | 두 번째 capture 성공 | **위험** | 422로 수정 |
| 20 | capture 뒤 주문 조회 | 200, `authorizations[0].status=CAPTURED` **와** `captures[0]{id,status,amount}`. 부분 환불 뒤 `captures[0].status=PARTIALLY_REFUNDED`, `refunds[]` 추가 | `captures[]`만 | 위험(낮음) | `authorizations[]` CAPTURED 추가 |
| 21 | 승인 금액 초과·다른 통화 capture | 422 `MAX_CAPTURE_AMOUNT_EXCEEDED`, `AUTH_CAPTURE_CURRENCY_MISMATCH` | 항상 성공 | 무해: 운영은 승인 금액으로만 capture | 없음 |
| 22 | void | **204 본문 없음**, 같은 Request-Id 재전송도 204 | 200 JSON | 무해(운영은 본문을 읽지 않음) | 없음 |
| 23 | void 재시도·이후 capture | 422 `PREVIOUSLY_VOIDED`, `AUTHORIZATION_VOIDED`; capture 뒤 void는 422 `PREVIOUSLY_CAPTURED`; void 뒤 주문 조회 `authorizations[0].status=VOIDED`, 주문은 `COMPLETED` | 항상 성공 | 위험(낮음) | 알 수 없는 승인만 404로 수정 |
| 24 | refund 성공 | 201, 표현 본문은 `id, status, amount, seller_payable_breakdown, ...`; `Prefer` 없으면 `id, status, links`만 | 같은 핵심 필드 | 일치 | 없음 |
| 25 | 같은 Request-Id로 refund 재전송 | 200, 같은 refund ID. **금액을 바꿔 보내도 같은 refund** | 같은 refund ID | 일치 | 없음 |
| 26 | 환불 누계 초과, 통화 불일치, 전액 환불 뒤 | 422 `REFUND_AMOUNT_EXCEEDED`, `REFUND_CAPTURE_CURRENCY_MISMATCH`, `CAPTURE_FULLY_REFUNDED` | 항상 성공 | **위험**: 초과 환불 버그가 있어도 테스트가 통과 | 누계 검사 추가 |
| 27 | 알 수 없는 capture ID 환불 | 404 `RESOURCE_NOT_FOUND`(ATLAS식 ID 포함) | 200 `COMPLETED`, 금액 0.00 | **위험** | 404로 수정 |
| 28 | refund 조회 | 200, `amount` 항상 포함 | 200, 알 수 없는 ID도 `COMPLETED`(금액 없음) | **위험**(알 수 없는 ID) | 404로 수정 |
| 29 | 알 수 없는 승인·capture ID 조회·void·capture | 404 `RESOURCE_NOT_FOUND` | 200 | **위험** | 404로 수정(capture, void) |
| 30 | webhook 검증: 형식은 맞지만 가짜 서명, 잘못된 `webhook_id`, 이상한 `auth_algo`, 비 PayPal `cert_url` | 200 `{verification_status: FAILURE}` | 200 `SUCCESS`(또는 큐에 넣은 값) | 무해(큐로 `FAILURE`를 낼 수 있음) | 없음 |
| 31 | webhook 검증: 서명 필드가 빈 문자열이거나 null(공백만 있는 값은 재지 않음) | **400** `INVALID_REQUEST`(빈 문자열) 또는 `VALIDATION_ERROR`(null) | 없음 | **위험(운영 결함)** | 아래 "운영 코드의 어긋남 2" |
| 32 | webhook 검증: `webhook_event` 누락·비 객체 | 400 `VALIDATION_ERROR` / `MALFORMED_REQUEST_JSON` | 없음 | 무해(운영은 원문을 JSON으로 먼저 파싱) | 없음 |

### 운영 코드의 어긋남 1: 토큰 실패가 장애 예외로 감싸지지 않았다

`PayPalRestClient.api()`는 `RestClientException`만 `PayPalUnavailableException`으로 감쌌다. Spring Security의 토큰 발급 실패는 `RestClientException`이 아니라 `ClientAuthorizationException`으로 올라온다. 운영 클라이언트에 잘못된 client 자격 증명을 주고 **실제 Sandbox 토큰 endpoint**를 호출하자(n=1) 이 예외가 감싸지지 않은 채 나왔다. 로컬 서버가 401, 500, 200(`access_token` 없음)을 줄 때도 같았다(n=3).

결제 서비스는 `PAYMENT_PROVIDER_UNAVAILABLE`만 UNKNOWN으로 흡수하므로, 이 예외는 UNKNOWN 처리를 건너뛴다(코드 읽기. 기존 `PayPalRestClientFailureTest` 설명은 감싸지 않은 예외가 HTTP 500으로 샌다고 적는다. 앱 전체 흐름에서 재현하지는 않았다). `PayPalRestClientFailureTest`의 연결 실패 테스트가 막으려던 것과 같은 종류다.

### 운영 코드의 어긋남 2: 빈 서명 헤더가 PayPal 호출과 503을 만들었다

webhook 컨트롤러는 서명 헤더 5개가 있기만 하면(빈 값 포함) 통과시키고, 클라이언트는 그대로 PayPal에 보낸다. 실제 PayPal은 빈 값에 400을 준다(위 31번). 모든 `RestClientException`이 장애 예외가 되므로 위조 요청이 "서명 무효"가 아니라 503이 되고, 요청마다 PayPal 호출이 나간다. 이제 서명 필드가 비면 PayPal을 호출하지 않고 검증 실패로 끝낸다. 호출된 상태에서 검증 실패(`FAILURE`)가 나온 경우는 이미 서명 무효로 처리하고 있었다.

### 바꾸지 않은 것: 확정 거절(4xx)도 UNKNOWN으로 둔다

실제 404/422는 확정 거절이지만 운영 클라이언트는 모든 HTTP 오류를 장애로 감싸 결제 시도를 UNKNOWN으로 둔다. 복구 worker는 같은 Request-Id로 최대 8회(`atlas.payment.recovery.max-attempts` 기본값, 코드 읽기) 재시도한 뒤 운영자 검토로 넘긴다. 위 13, 18, 25번이 보이듯 같은 Request-Id 재전송은 같은 결과를 돌려주므로 재시도는 안전하다. 비용은 확정 거절을 8회 다시 보내는 것이다. 4xx를 확정 거절로 분류하면 결제가 잘못 거절될 위험이 있어(예: 승인 반영 지연 중 조회) 이번에는 바꾸지 않고 현재 동작을 `PayPalSandboxResponseReplayTest`로 고정했다.

## 고친 것

운영 코드(작은 수정 2건, 둘 다 위 실측이 근거):

- `PayPalRestClient.api()`: `OAuth2AuthorizationException`도 `PayPalUnavailableException`으로 감쌌다.
- `PayPalRestClient.verifyWebhook`: 서명 필드가 비어 있으면 PayPal을 호출하지 않고 `verified=false`.

테스트와 대역:

- `PayPalSandboxResponseReplayTest`(atlas-payment, 10건): 실제 응답 기록을 로컬 HTTP 서버로 재생해 `PayPalRestClient`가 읽는 필드와 상태를 고정한다. 주문 승인 전, 승인 뒤, authorize 뒤, capture 뒤, 부분 환불 뒤 조회, authorize·capture·refund의 표현형과 최소형 응답, 가짜 서명의 `FAILURE`, 실제 404/422/400 본문이 장애 예외가 되는 것(6건).
- `PayPalRestClientFailureTest`(2건 추가): 토큰 실패 감싸기, 빈 서명 헤더가 닫힌 포트에서도 호출 없이 검증 실패로 끝남.
- `PayPalIntegrationTestSupport`(대역): 같은 Request-Id 재생(생성, authorize), 알 수 없는 ID의 404, 중복 authorize와 capture의 422, 환불 누계 검사(초과, 전액 환불 뒤), 기본 생성 응답을 `PAYER_ACTION_REQUIRED`와 `payer-action`으로, capture 뒤 주문 조회에 `authorizations[]` CAPTURED 추가.
- `PayPalDoubleContractTest`(atlas-application, 3건): 대역이 위 규칙을 따르는지 Spring context 없이 고정한다.
- `.github/workflows/paypal-sandbox-contract.yml`: 6번 단계로 "알 수 없는 주문 ID 조회 → 404 `RESOURCE_NOT_FOUND`" 추가. 비용은 GET 1회이고 결정적이다. 대역이 의존하는 가정 하나를 실제로 확인한다. 이 워크플로는 `workflow_dispatch`(수동)만 받고 PR 이벤트가 없으므로 포크 PR이 secret을 받을 일이 없다. secret이 없으면 전체를 건너뛴다. 필수 검사 세 개(`Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks`)와 무관하다. 추가한 단계를 `run` 스크립트째 실제 Sandbox에 1회 돌려 통과를 확인했다(GitHub Actions에서는 돌리지 않았다).

## 성공 기준별로 확인한 정도

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 운영 수정 2건이 변경 전에는 실패 | 운영 수정만 되돌리고 `PayPalRestClientFailureTest` 실행 | 5건 중 새 2건 실패(`oauthTokenFailureIsProviderUnavailable`, `blankWebhookSignatureIsRejectedWithoutCallingPayPal`) |
| 변경 후 통과 | `./gradlew :atlas-payment:test --tests '*PayPalSandboxResponseReplayTest' --tests '*PayPalRestClientFailureTest'` | 15건(10건 + 5건) 통과 |
| 대역 변경이 기존 통합 테스트를 깨지 않음 | `PayPalIntegrationTestSupport`를 상속하는 35개 클래스를 `./gradlew :atlas-application:test --tests ...`로 실행(대역 변경 뒤) | 238건 통과, 실패 0, 건너뜀 0(1회 실행) |
| 대역이 실제 규칙을 따름 | 대역만 되돌리고 `PayPalDoubleContractTest` 실행 | 변경 전 대역: 3건 모두 실패(생성 재전송이 새 주문 ID, 알 수 없는 ID가 200, 두 번째 authorize가 200). 변경 후 대역: 3건 통과 |
| 체크스타일, `-Xlint:all -Werror` | `compileJava compileTestJava checkstyleMain checkstyleTest` | 통과 |
| 워크플로 6번 단계 | 워크플로 `run` 스크립트를 실제 Sandbox에 1회 실행 | 1~6번 통과(`GITHUB_STEP_SUMMARY`는 Actions 밖이라 없음) |

## 대역 변경의 영향

기존 통합 테스트 238건(35개 클래스)은 대역이 더 엄격해진 뒤에도 그대로 통과했다. 어느 테스트도 알 수 없는 ID, 중복 authorize·capture, 초과 환불에 기대지 않았다는 뜻이다. 반대로 이 테스트들은 그 경로를 한 번도 시험하지 않았다는 뜻이기도 하다. 전체 `clean check`는 PR 본문에 따로 적는다.

## 대역이 모르는 것(비교하지 못했거나 대역에 넣지 않았다)

- **구매자 승인 상태기계**: 승인 전 authorize는 실제로 422 `ORDER_NOT_APPROVED`지만 대역은 승인 여부를 추적하지 않고 `queueOrderStatus`로 조회 상태만 정한다. 대역에 넣으면 승인 단계를 흉내 내는 상태가 늘어 대역이 복잡해지고, 운영 코드는 승인 전에 authorize를 부르지 않는다(조회가 `APPROVED`일 때만 호출). 넣지 않았다.
- **구매자 쪽 결과**: 구매자 취소, 결제 수단 거절, `PENDING` 승인·capture, `DENIED`, 승인 만료(29일)는 Sandbox 구매자 계정으로 만들 수 없었다. 이 값들은 대역의 큐로만 낸다. 실제 응답 모양은 확인하지 못했다.
- **실제 webhook 전달**: 서명 검증 성공(`SUCCESS`)은 PayPal이 보낸 실제 이벤트와 헤더가 필요하다. 이번에는 `FAILURE`와 400만 확인했다.
- **응답 유실, 지연, 429, 5xx**: 결정적으로 만들 수 없다. 응답 유실은 EXP-050의 Toxiproxy 실험(capture 3건)이 있고, 이번 실측은 "같은 Request-Id 재전송이 같은 결과를 준다"는 전제를 authorize, capture, refund, void, 생성에서 확인했을 뿐이다. 재전송 가능한 기간(idempotency 보존 기간)은 재지 않았다.
- **HTTP 상태 코드의 200/201 구분**: 실제는 첫 호출 201, 재전송 200이고 대역은 항상 200이다. 운영은 2xx를 구분하지 않아 무해하다고 판단했다.
- **KRW**: 실제 PayPal은 KRW를 422로 거절한다(`CURRENCY_NOT_SUPPORTED`). ATLAS는 USD로 환산해 보내므로 이 경로는 쓰이지 않는다.
- **Live 환경**: Sandbox 응답이 Live와 같다는 보장은 없다.
- **지급(Payouts)**: 이 문서의 범위 밖이다(EXP-073). 지급 클라이언트도 `api()`가 토큰 실패를 감싸지 않지만 이번에 수정하지 않았다.

## 되돌리는 법

- 대역과 테스트 변경은 테스트 코드뿐이다. 이 PR을 되돌리면 이전 대역으로 돌아간다.
- 운영 수정 2건은 각각 몇 줄이다. 되돌리면 토큰 실패가 감싸지지 않고, 빈 서명 헤더가 다시 PayPal 호출과 503이 된다.
- 워크플로 6번 단계는 해당 블록만 지우면 된다.

## 한계

- 조건별 n=1, 하루 동안의 단발 관측이다. Sandbox가 같은 요청에 다른 응답을 줄 수 있는지 반복해 재지 않았다.
- 구매자 승인은 headless 브라우저가 Sandbox 화면을 누른 것이다. 화면이 바뀌면 이 방식은 깨질 수 있다.
- 대역의 새 규칙은 위 실측 규칙을 옮긴 것이다. 실측하지 않은 규칙(승인 만료, 부분 capture 여러 번, 통화 환산)은 대역에 넣지 않았다.
- 4xx를 UNKNOWN으로 두는 설계는 바꾸지 않았다.

## 후속 제안(적용하지 않음)

- 확정 거절(404, 422 중 일부)을 구분해 불필요한 8회 재시도를 줄이는 방안. 결제가 잘못 거절될 위험과 비교해야 한다.
- 지급 클라이언트의 토큰 실패 감싸기.
- 구매자 승인이 필요한 경로를 다루는 수동 실행 계약 검사(자격 증명과 구매자 계정이 필요하고 화면 의존이라 CI에 넣을지는 보류).
