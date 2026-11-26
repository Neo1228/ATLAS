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
- 기존 비교 표본: n=108 HTTP requests(스크립트 n=6회 실행), n=24 주문 생성 호출(재전송 포함), 구매자 승인 주문 n=4(승인 뒤 authorize, capture, refund, void 경로), 운영 Java 클라이언트의 잘못된 client 자격 증명 토큰 요청 n=1. 그 최초 비교는 조건별 n=1이었다. 반복 관측은 아래 `EXP-105 종결 결과`에 별도 집계했으며, 기존 수치는 최초 관측으로 보존한다.
- 기록: 응답 본문은 로컬에만 두었다. 저장소에는 식별자(주문, 승인, capture, refund ID), 구매자와 판매자 정보, `debug_id`, 링크 주소를 문서 예시 값으로 치환한 18건만 올렸다(`atlas-payment/src/test/resources/paypal-sandbox/responses.json`). 치환 뒤 원본 ID, 이메일 문자열이 남지 않았는지 스크립트로 검사했다.
- 하네스(Python 요청 스크립트, Node 승인 스크립트)는 올리지 않았다. 자격 증명과 로컬 `.env.local`에 의존한다. 재현은 [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md)과 같은 방식이다.
- 응답 지연 시간은 이 실험의 지표로 두지 않았다. 공유 호스트 부하가 섞여 Sandbox 처리 시간과 분리할 수 없기 때문이다. 속도 결론은 내리지 않으며, 재측정 조건은 아래 종결 결과의 confounder 항목에 둔다.

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

당시 결제 서비스는 `PAYMENT_PROVIDER_UNAVAILABLE`만 UNKNOWN으로 흡수하므로 이 예외가 UNKNOWN 처리를 건너뛰는 것은 코드 경로 판독이며, `PayPalRestClientFailureTest` 설명은 감싸지 않은 예외가 HTTP 500으로 새는 위험을 기록했다. **결정:** 이 실험은 API/token 예외 경계까지만 다루고 전체 checkout end-to-end는 범위 밖으로 둔다. 대안은 `PayPalRestClientFailureTest` n=5의 wrapper 검증과 기존 recovery 통합 테스트다. OAuth 토큰 발급 경로가 바뀌거나 앱 레벨에서 같은 실패가 재현되면 전체 flow를 검토한다.

### 운영 코드의 어긋남 2: 빈 서명 헤더가 PayPal 호출과 503을 만들었다

webhook 컨트롤러는 서명 헤더 5개가 있기만 하면(빈 값 포함) 통과시키고, 클라이언트는 그대로 PayPal에 보낸다. 실제 PayPal은 빈 값에 400을 준다(위 31번). 모든 `RestClientException`이 장애 예외가 되므로 위조 요청이 "서명 무효"가 아니라 503이 되고, 요청마다 PayPal 호출이 나간다. 이제 서명 필드가 비면 PayPal을 호출하지 않고 검증 실패로 끝낸다. 호출된 상태에서 검증 실패(`FAILURE`)가 나온 경우는 이미 서명 무효로 처리하고 있었다.

### 당시 바꾸지 않은 것: 확정 거절(4xx)도 UNKNOWN으로 둠

이 문단은 EXP-081 초기 구현 시점의 상태 기록이다. 당시에는 실제 404/422를 장애 예외로 감싸 결제 시도를 UNKNOWN으로 두고, 복구 worker가 같은 Request-Id로 최대 8회(`atlas.payment.recovery.max-attempts` 기본값) 재시도한 뒤 운영자 검토로 넘겼다. 즉시 replay 응답은 당시 13·18·25번에서 같았지만 보존 기간은 별개다. 후속 [EXP-088](EXP-088-paypal-definitive-rejection-and-authorization-expiry.md)과 [ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md)은 증거가 있는 일부 issue만 확정 분류하도록 바꿨고, 나머지 4xx는 UNKNOWN으로 남겼다(아래 종결 claims 표).

## 고친 것

운영 코드(작은 수정 2건, 둘 다 위 실측이 근거):

- `PayPalRestClient.api()`: `OAuth2AuthorizationException`도 `PayPalUnavailableException`으로 감쌌다.
- `PayPalRestClient.verifyWebhook`: 서명 필드가 비어 있으면 PayPal을 호출하지 않고 `verified=false`.

테스트와 대역:

- `PayPalSandboxResponseReplayTest`(atlas-payment, 10건): 초기 실험 당시 저장한 실제 응답을 로컬 HTTP 서버로 재생해 `PayPalRestClient`가 읽는 필드와 상태를 고정했다. 주문 승인 전/후, authorize 뒤, capture 뒤, 부분 환불 뒤 조회, 표현형/최소형 응답, 가짜 서명의 `FAILURE`, 초기 구현의 404/422/400 예외 처리(6건)를 포함한다. 4xx 처리의 후속 변경은 EXP-088/ADR-0079에 기록했다.
- `PayPalRestClientFailureTest`(2건 추가): 토큰 실패 감싸기, 빈 서명 헤더가 닫힌 포트에서도 호출 없이 검증 실패로 끝남.
- `PayPalIntegrationTestSupport`(대역): 같은 Request-Id 재생(생성, authorize), 알 수 없는 ID의 404, 중복 authorize와 capture의 422, 환불 누계 검사(초과, 전액 환불 뒤), 기본 생성 응답을 `PAYER_ACTION_REQUIRED`와 `payer-action`으로, capture 뒤 주문 조회에 `authorizations[]` CAPTURED 추가.
- `PayPalDoubleContractTest`(atlas-application, n=3 tests): 대역이 위 규칙을 따르는지 Spring context 없이 고정한다.
- `.github/workflows/paypal-sandbox-contract.yml`: 6번 단계로 "알 수 없는 주문 ID 조회 → 404 `RESOURCE_NOT_FOUND`" 추가. 비용은 GET 1회이고 결정적이다. 대역이 의존하는 가정 하나를 실제로 확인한다. 이 워크플로는 `workflow_dispatch`(수동)만 받고 PR 이벤트가 없으므로 포크 PR이 secret을 받을 일이 없다. secret이 없으면 전체를 건너뛴다. 필수 검사 세 개(`Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks`)와 무관하다. 추가한 단계를 `run` 스크립트째 실제 Sandbox에 1회 돌려 통과를 확인했다(GitHub Actions에서는 돌리지 않았다).

## 성공 기준별로 확인한 정도

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 운영 수정 2건이 변경 전에는 실패 | 운영 수정만 되돌리고 `PayPalRestClientFailureTest` 실행 | n=5건 중 새 n=2건 실패(`oauthTokenFailureIsProviderUnavailable`, `blankWebhookSignatureIsRejectedWithoutCallingPayPal`) |
| 변경 후 통과 | `./gradlew :atlas-payment:test --tests '*PayPalSandboxResponseReplayTest' --tests '*PayPalRestClientFailureTest'` | n=15 tests(10건 + 5건) 통과 |
| 대역 변경이 기존 통합 테스트를 깨지 않음 | `PayPalIntegrationTestSupport`를 상속하는 35개 클래스를 `./gradlew :atlas-application:test --tests ...`로 실행(대역 변경 뒤) | n=238 tests 통과, 실패 0, 건너뜀 0(1회 실행) |
| 대역이 실제 규칙을 따름 | 대역만 되돌리고 `PayPalDoubleContractTest` 실행 | 변경 전 대역: 3건 모두 실패(생성 재전송이 새 주문 ID, 알 수 없는 ID가 200, 두 번째 authorize가 200). 변경 후 대역: 3건 통과 |
| 체크스타일, `-Xlint:all -Werror` | `compileJava compileTestJava checkstyleMain checkstyleTest` | 통과 |
| 워크플로 6번 단계 | 워크플로 `run` 스크립트를 실제 Sandbox에 1회 실행 | 1~6번 통과(`GITHUB_STEP_SUMMARY`는 Actions 밖이라 없음) |

## 대역 변경의 영향

대역 변경 뒤 기존 통합 테스트 n=238 tests(35개 클래스)는 통과했다. 그 238건 실행 당시에는 알 수 없는 ID, 중복 authorize·capture, 초과 환불 경로를 검증하지 않았다. 이후 추가된 `PayPalDoubleContractTest` 3건이 이 경로를 명시적으로 고정한다. 어느 경로가 어느 테스트에 있는지는 아래 `EXP-105 종결 결과`의 claims 표에 적었다. 전체 `clean check`는 PR 본문에 따로 적는다.

## 롤백 재현 결과

문서 변경 전부의 실제 diff를 별도 scratch worktree에 한 번 적용한 뒤 reverse-apply했다(n=1 round-trip). 적용 단계 `git apply --check "$PATCH"`와 `git apply "$PATCH"`가 통과했고, `cmp "$SRC" "$DST"`가 수정본 두 개의 일치를 확인했다. 복구 단계 `git apply -R "$PATCH"` 뒤 `git diff --exit-code -- "$REL"`은 exit 0, `cmp <(git show "HEAD:$REL") "$DST"`도 exit 0이었다. scratch worktree는 복구 뒤 clean이었고 stage/commit하지 않았다. 이 결과는 문서 파일의 적용/복구만 확인하며 애플리케이션 동작 검증을 뜻하지 않는다.

## EXP-105 종결 결과

재실행 표기: 이 절의 Sandbox 호출 결과(구매자 흐름 probe, mock 오류 코드 n=3, 429 후보 이름 등)는 이전 에이전트가 같은 날 실행해 보고한 값이며 이 문서를 이어받은 에이전트는 Sandbox 호출을 다시 실행하지 않았다: local-only(이전 에이전트 보고). 이 에이전트가 직접 확인한 것은 문서 안의 외부 차단 문장이 "입력과 V1의 동작"을 모두 적었는지와 금지 낱말이 남지 않았는지다.

### 실행 범위와 재현 명령

실행일: 2026-11-26. 이번 종결은 `https://api-m.sandbox.paypal.com`만 호출했다. 자격 증명은 프로세스 안에서 저장소 밖 `.env.local`에서 읽었고, 출력하지 않았다. 응답은 실행 중 status, `name`, `details[].issue`, `status`, JSON 최상위 키만 집계했다. 응답 본문, credentials, account/order/authorization/capture/refund IDs는 이 문서에 넣지 않았다. Node.js `v26.13.1`과 이미 설치된 Playwright `1.64.0`을 사용했다. 도구를 설치하지 않았고 저장소 harness나 workflow를 수정하지 않았다.

기존 `load-tests/paypal-real-fault/real-flow.mjs`는 앱·DB·Toxiproxy 전용 구성의 통합 장애 runner라서 단건 REST 계약 반복에 재사용하지 않았다. 호출 모양과 입력은 아래 셀 표에 적었으며, probe는 ATLAS식 `POST /v2/checkout/orders` 본문·실제 buyer approval·Payments v2 authorize/capture/refund을 사용했다. 아래 `node <script>` 명령은 각 스크립트와 helper가 놓인 저장소 밖 임시 디렉터리를 현재 작업 디렉터리로 두고 실행했다. 스크립트는 일회성/raw probe라 저장소에 커밋하지 않았다. 명령 인자와 추가 환경변수 override는 없었고, 자격 증명·buyer 입력은 `.env.local`에서 프로세스 안에 읽었다.

| 범위 | 실행 명령 | 실행 수 |
| --- | --- | ---: |
| 비브라우저 반복: 생성/재생, 알 수 없는 ID, 미승인 authorize/capture, 검증 오류, webhook 형식 | `node probeA.mjs` | 각 조건 n=5 |
| mock 오류, 실제 승인 후 authorize/capture/refund | `node probeB2.mjs` | mock 코드별 n=3 |
| PENDING/DENIED 상태 이름 mock 및 정확히 60자 Request-Id | `node probeStatus.mjs` | 각 상태명·endpoint n=3, Request-Id n=5 |
| 구매자 취소 | `node probeCcancel.mjs` | n=3 |
| 호스트 교란 요인 | `uptime` | probe batch 전후 |

### 반복 셀과 상태 코드

| 조건 | 반복과 결과 | 안정성 |
| --- | --- | --- |
| ATLAS 형식 주문 생성(`payment_source` 포함) | n=5, HTTP 200 `PAYER_ACTION_REQUIRED`; 최상위 키 `id,links,payment_source,status` | 관측 변이 0 |
| 같은 Request-Id 생성 재생, 본문 금액 1.00→2.00 | n=5, HTTP 200, 매회 최초 주문 ID와 같음 | 관측 변이 0; 응답의 금액 필드는 없음 |
| `payment_source` 없는 주문 생성 | n=5, HTTP 201 `CREATED`; 키 `id,links,status` | 관측 변이 0 |
| 알 수 없는 주문 ID GET(기존 입력 형식 17자, `ORDER-UUID`, 1자 각각) | 각 n=5(총 15), HTTP 404 `RESOURCE_NOT_FOUND`, `details[0].issue=INVALID_RESOURCE_ID`; 키 `debug_id,details,links,message,name` | 각 입력 형식 관측 변이 0. 최초 EXP-081 셀을 재현한 no-change control |
| 알 수 없는 authorization GET/capture/void, capture 환불, refund GET | 각 n=5, 모두 HTTP 404 `RESOURCE_NOT_FOUND` / `INVALID_RESOURCE_ID` | 각 endpoint 관측 변이 0 |
| 미승인 주문 authorize / 주문 capture | 각각 n=5, HTTP 422 `ORDER_NOT_APPROVED` / `ACTION_DOES_NOT_MATCH_INTENT`; 같은 오류 키 집합 | 관측 변이 0 |
| 금액 검증(각 n=5) | KRW 422 `CURRENCY_NOT_SUPPORTED`; JPY 소수 422 `DECIMALS_NOT_SUPPORTED`; 0·음수 각각 422 `CANNOT_BE_ZERO_OR_NEGATIVE`; 소수 3자리 422 `DECIMAL_PRECISION`; `abc` 400 `INVALID_PARAMETER_SYNTAX`; amount 누락 400 `MISSING_REQUIRED_PARAMETER`; 본문 없는 POST 415 `UNSUPPORTED_MEDIA_TYPE`; 소문자 `usd`는 201 | 각 오류 응답의 최상위 키는 `debug_id,details,links,message,name`, 수락 응답은 `id,links,status`; 각 입력의 status/name/issue/keyset 변이 0 |
| 정확히 60자 Request-Id | n=5, HTTP 201 `CREATED`; 키 `id,links,status` | 관측 변이 0 |
| webhook fake signature / 빈 signature / null signature / `webhook_event` 누락 | 각 n=5: 200 `FAILURE`; 400 `INVALID_REQUEST`; 400 `VALIDATION_ERROR`; 400 `VALIDATION_ERROR` | keyset은 차례로 `{verification_status}`, `{debug_id,details,links,message,name}`, `{debug_id,details,information_link,links,message,name}`(null·event 누락); 각 조건 관측 변이 0 |
| 승인 뒤 실제 authorize / 같은 Request-Id 재생 | 각각 n=3: 201 `COMPLETED` / 200 `COMPLETED` | 관측 변이 0; 본문 키 `id,links,payer,payment_source,purchase_units,status` |
| 실제 capture / 같은 Request-Id 재생 | 각각 n=3: 201 `COMPLETED` / 200 `COMPLETED` | 관측 변이 0; 본문 키 `amount,create_time,custom_id,final_capture,id,links,seller_protection,seller_receivable_breakdown,status,update_time` |
| 실제 환불 / 같은 Request-Id 재생 | 각각 n=3: 201 `COMPLETED` / 200 `COMPLETED` | 관측 변이 0; 본문 키 `amount,create_time,custom_id,id,links,seller_payable_breakdown,status,update_time` |
| 환불 한도 초과 | n=3, 422 `REFUND_AMOUNT_EXCEEDED` | 키 `debug_id,details,links,message,name`, 관측 변이 0 |

모든 반복은 같은 날 같은 Sandbox 앱에서 순차 실행했다. `n=5`에서 응답 변이가 0이어도 독립 반복 5회뿐이다(희귀 변이의 rule-of-three 95% 상한은 약 60%); `n=3`의 상한은 100%다. 이는 일반 안정성 보증이 아니다. 기존 수치와 새 반복 수치는 합산하지 않았다.

### 구매자 경로와 negative-testing 헤더

| 조건 | n과 결과 | ATLAS에 미치는 사실 |
| --- | --- | --- |
| PayPal 구매자 로그인·승인 positive control | 서로 다른 주문 n=3, 브라우저 승인 뒤 GET 200 `APPROVED` | 승인 화면과 buyer 자격 증명이 이번 실행에서 작동함 |
| 구매자 취소 링크 클릭 후 돌아오기 | n=3, 실제 화면의 `cancel-link` 클릭이 각각 cancel URL 요청으로 이어짐. 이 probe는 외부 callback을 앱에 전달하지 않고 브라우저에서 204로 가로챘다. PayPal GET은 매회 200 `PAYER_ACTION_REQUIRED`(키 `create_time,id,intent,links,payment_source,purchase_units,status`); 이후 authorize는 매회 422 `ORDER_NOT_APPROVED`(키 `debug_id,details,links,message,name`) | 취소 뒤 PayPal order는 `CREATED`가 아니라 `PAYER_ACTION_REQUIRED`에 남음. ATLAS의 취소는 provider order가 자동 `CANCELLED`가 된다고 가정하면 안 됨 |
| authorize의 mock `INSTRUMENT_DECLINED`, `PAYER_ACTION_REQUIRED`, `TRANSACTION_REFUSED` | 각 n=3, 422 `UNPROCESSABLE_ENTITY`와 해당 `details[0].issue`; 키 `debug_id,details,links,message,name` | mock 뒤 GET은 3/3 `APPROVED`; no-mock authorize는 3/3 201 `COMPLETED` |
| authorization capture mock `INSTRUMENT_DECLINED`, `PAYER_ACTION_REQUIRED` | 각 n=3, HTTP 403, 본문 없음 | error name/key set 없음; authorization GET은 3/3 `CREATED` |
| authorization capture mock `TRANSACTION_REFUSED`, `AUTHORIZATION_EXPIRED` | 각 n=3, 422 `UNPROCESSABLE_ENTITY`와 같은 issue | authorization GET은 3/3 `CREATED`; `AUTHORIZATION_EXPIRED`가 실제 만료를 뜻하지 않음 |
| `INTERNAL_SERVER_ERROR` mock | 생성·authorize·capture·refund 각 n=3, HTTP 500 `INTERNAL_SERVER_ERROR`; 각 endpoint 내 키 집합 변이 0(생성/authorize `{message,name}`, capture/refund `{links,message,name}`) | negative control이 아닌 문서화된 5xx error mock이 작동함 |
| PENDING/DENIED 상태 이름을 mock error code로 create/authorize/capture에 전달 | 각 이름·endpoint n=3, 모두 HTTP 403, 본문 없음 | 상태 `PENDING`/`DENIED`를 만들지 못함. mock 뒤 실제 authorize는 201이고 authorization은 `CREATED`; status 이름 mock capture 뒤 GET도 `CREATED` |
| 429 대용 mock 이름 `RATE_LIMIT_REACHED`, `TOO_MANY_REQUESTS`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT`을 주문 생성에 전달 | 각 n=3, 모두 HTTP 403, 본문 없음; 429는 0/12 | rate-limit/5xx mock이 아니라 유효하지 않은 mock 코드 응답 |
| no-mock positive control | 실제 승인 주문 authorize n=3, capture n=3, 환불 n=3이 각각 201 `COMPLETED`; replay는 각 n=3 200 | buyer 승인 및 후속 API 호출은 mock 외 정상 경로에서 완료됨 |

공식 negative-testing 문서는 `mock_application_codes`가 시험할 **오류 코드**와 일치해야 한다고 설명한다([PayPal 문서](https://developer.paypal.com/negative-testing/request-headers.md)). PayPal Orders 오류 페이지에서 `INTERNAL_SERVER_ERROR`는 확인했고 `RATE_LIMIT_REACHED`, `TOO_MANY_REQUESTS`, `429` 문자열 검색 결과는 0건이었다. 미등록 이름의 403 응답을 429/5xx로 해석하지 않는다.

### 기존 관측과 남은 경계의 종결 분류

| 주장 | 증거와 terminal 결과 | confidence |
| --- | --- | --- |
| 실제 webhook `SUCCESS` 모양 | [EXP-094 H7](EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md): 실제 webhook 이벤트 11건 모두 서명 검증 `SUCCESS` 11/11, 변조 본문은 `FAILURE` 11/11. 이 문서에서 재측정하지 않음 | confirmed |
| 실제 응답 유실·지연 | [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md): capture response-loss n=3, 12초 지연 n=3/worker-on; 응답 유실과 recovery는 local Toxiproxy/App 경계 측정이지 PayPal의 429/5xx 생성 증거가 아님 | local-only |
| 5xx 생성 | 이 문서의 `INTERNAL_SERVER_ERROR` mock이 생성·authorize·capture·refund에서 각 n=3 HTTP 500으로 응답 | confirmed |
| 429 생성 | 4개 후보 이름은 각각 n=3 HTTP 403 빈 본문; 공식 mock 설명은 오류 코드만 받으며 해당 429 코드는 오류 목록에서 찾지 못함. **외부 차단:** PayPal이 지원하는 429 mock code 또는 Sandbox 측 rate-limit 유발 조건이 필요하다. V1에서는 실제 provider 429를 주장하지 않고, 앱 측 429 응답 처리가 필요해지면 결정적 local HTTP 429 control을 별도 등록한다 | external-blocked (with proof) |
| 구매자 취소 | cancel link 클릭 n=3; cancel URL 요청 3/3, GET 상태는 3/3 `PAYER_ACTION_REQUIRED`, authorize 3/3 `ORDER_NOT_APPROVED` | confirmed |
| 실제 구매자 결제수단 거절 | mock header authorize는 `INSTRUMENT_DECLINED` 422 n=3, capture는 403 빈 본문 n=3; 실제 승인 flow는 n=3 모두 `APPROVED`였다. **외부 차단:** 재현 가능한 거절 응답을 내는 전용 Sandbox buyer/funding instrument 또는 PayPal 제공 decline 시나리오가 필요하다. V1은 실제 funding 거절을 계약 사실로 쓰지 않고 대역 queue로만 표현한다 | external-blocked (with proof) |
| 실제 buyer-side `PENDING`/`DENIED` 상태 | 상태 이름을 authorize/capture mock에 각각 n=3 전달했으나 모두 403 빈 본문; 정상 authorize는 `CREATED`, 정상 capture는 `COMPLETED`. 문서화된 header는 오류 코드를 시뮬레이션하며 성공 상태를 주입하지 않는다. **외부 차단:** PayPal이 제공하는 PENDING/DENIED 유발 buyer/payment source 또는 성공 상태 주입 수단이 필요하다. V1은 대역 queue만 사용 | external-blocked (with proof) |
| 실제 authorization 만료 | [EXP-086 Q-004](EXP-086-admission-k-ttl-and-authorize-window.md#q-004-paypal-authorize-facts-and-repo-window)의 [P1] 문구: “An authorization places a hold on the funds and is valid for 29 days.” [EXP-094 H6](EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md) mock `AUTHORIZATION_EXPIRED` n=2는 실제 authorization을 만료시키지 않았고 직후 상태 `CREATED`, expiry 29일 뒤, 정상 capture 2/2였다. 여기서 같은 mock을 capture에 n=3 추가했지만 실제 만료 관측은 아니다. **외부 차단:** 29일 지난 Sandbox authorization 또는 PayPal의 만료 시계/상태 주입이 필요하다. V1은 actual-expiry 응답 모양을 assert하지 않는다 | external-blocked (with proof) |
| 여러 번의 partial capture | [EXP-094 H4](EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md): partial capture→void→same Request-Id replay n=1, 새 Request-Id 재시도는 422 `AUTHORIZATION_VOIDED`인데 captures 1; 동시 capture/void n=3. 여기서는 재측정하지 않음. **결정:** ATLAS의 실제 호출은 전체 금액 단일 capture이므로 여러 번의 성공 partial capture는 범위 밖. 대안은 위 EXP-094 경계 재현을 사용. `PayPalRestClient`가 부분 금액 또는 `final_capture=false`를 보내는 변경이 생기면 n≥3 부분 capture matrix로 재검토 | decided |
| PayPal 통화 환산 | `PayPalRestClient.createOrder`는 인자로 받은 `amount`와 `currency`를 그대로 `purchase_units.amount`에 넣는다(코드 65–72행); PayPal-side 환산 요청이 없다. **결정:** 범위 밖, 최종 USD 금액을 보내는 현재 계약으로 충분. 대안은 앱 측 FX 경계 테스트; PayPal 환산 설정/통화가 실제 요청에 추가될 때 재검토 | decided |
| Request-Id replay 보존 기간 | immediate replay는 생성 n=5 및 authorize/capture/refund 각 n=3에서 각각 200. 이 문서의 측정 경계는 즉시 replay까지이며, 시간 경과 horizon은 [EXP-086 Q-004](EXP-086-admission-k-ttl-and-authorize-window.md#q-004-paypal-authorize-facts-and-repo-window) 담당자 범위로 넘긴다. **결정:** 중복 측정하지 않고 즉시 재시도만 현재 대안으로 둔다. Q-004가 보존 horizon을 정하면 그 시점 직전·직후를 비교한다 | decided |
| 200/201 | ATLAS 형식 create 최초/재생 200/200; `payment_source` 없는 create 201; authorize/capture/refund 최초 201·즉시 재생 200(각 n=3). 운영 파서는 2xx 응답을 본문으로 읽고 200/201 분기 로직은 없다. | confirmed |
| 238개 기존 통합 테스트의 gap | 기존 n=238 tests/35개 클래스는 알 수 없는 ID·중복 write·초과 환불을 검증하지 않았다. 이후 `PayPalDoubleContractTest`의 `unknownIdsAreAnsweredWithTheRealNotFoundBody`가 GET unknown order, POST unknown-order authorize, unknown authorization capture/void, unknown capture refund, unknown refund GET을 검증한다. `repeatingARequestIdReplaysTheFirstResultEvenWithAnotherBody`는 create/authorize/capture/refund replay를 검사한다. `secondWritesUnderANewRequestIdAreRejectedLikeSandbox`는 duplicate authorize/capture, 초과 환불 `REFUND_AMOUNT_EXCEEDED`, 전액환불 뒤 `CAPTURE_FULLY_REFUNDED`를 검사한다. 모두 double contract이지 real Sandbox 호출은 아님 | local-only |
| 4xx의 UNKNOWN 분류 | EXP-081 당시 “4xx도 UNKNOWN”으로 적었으나 이후 [EXP-088](EXP-088-paypal-definitive-rejection-and-authorization-expiry.md)·[ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md)에서 증거가 있는 특정 issue만 확정 분류하도록 바뀌었다. 분류되지 않은 4xx·본문 불량은 여전히 UNKNOWN이다. | local-only |
| 29일보다 짧은 expiry 외 규칙 / Payouts | `KRW` 422는 반복 확인했다. Payouts는 이 문서 범위가 아니며 [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md)에서 다룬다. 지급 token failure는 이 문서에서 수정·재현하지 않았다. **결정:** 결제 API와 지급 API 분리 유지; 결제 클라이언트가 지급 endpoint를 호출하게 되는 변경이 있으면 재검토 | decided |
| Live 동등성 | Sandbox 전용 host만 호출했고 Live 응답을 비교하지 않았다. **외부 차단:** 승인된 Live merchant/test-buyer, 안전한 실제 결제·환불 절차, 명시적 Live test permission이 필요하다. V1은 Sandbox 결과를 Live 계약으로 일반화하지 않는다 | external-blocked (with proof) |
| browser buyer flow의 CI 안정성 | 이번 승인·취소 browser flow는 성공했다. 화면 변경 및 runner별 재현성은 이 문서 범위가 아니다. **결정:** secret 및 외부 UI 의존 때문에 PR 필수 CI가 아닌 수동 계약 실행으로 유지한다. 대안은 double contract test. Sandbox buyer secret이 CI에 제공되고 서로 다른 runner에서 연속 3회 승인·취소가 각각 n=3/3 통과하면 자동화를 재검토 | decided |
| 응답 지연 시간 | 지연은 목표 변수가 아니고 공유 호스트에서 시간 비교를 하지 않았다. **결정:** 이 문서에서는 latency 결론을 내리지 않음. 대안은 응답 shape만 비교. 전용 runner에서 실행 내내 `uptime` 1분 load average <2.0을 만족하는 별도 latency 실험을 등록할 때 재검토 | decided |

### Confounder와 음성 통제

- `uptime`을 probe 전후 기록했다. 1분 load average는 실행 중 23.12–45.82, 5분은 19.34–37.60, 15분은 11.48–34.43이었다. 공유 호스트 부하가 높아 응답 시간은 비교하지 않았고 status/body shape만 사용했다.
- no-change control은 기존 17자/`ORDER-UUID`/1자 unknown-order GET 셀을 반복한 것이다(각 n=5, 404 동일).
- 음성/정상 통제는 mock 없는 승인 주문 authorize/capture/refund가 각각 n=3 HTTP 201 `COMPLETED`, replay가 n=3 HTTP 200인 셀이다. 모의 거절 뒤 GET `APPROVED` 또는 authorization `CREATED`여서 거절 mock이 자원을 바꾸지 않았음을 확인했다.
- `INTERNAL_SERVER_ERROR`는 의도한 500 양성 통제다. 임의 이름의 status/rate mock은 403 빈 본문이므로 429/PENDING/DENIED 응답으로 세지 않았다.
- Sandbox 부수 효과: PENDING/DENIED status-name mock 뒤 no-mock authorize를 수행한 6개 control 주문(n=6, 각각 USD 1.00)은 authorization `CREATED` 상태로 남았다. capture/refund는 하지 않았고 IDs는 저장·출력하지 않아 이 실행에서 void cleanup도 하지 않았다. 실제 결제나 Live 호출은 없었다.

## 후속 제안 결정

- 특정 4xx의 확정 분류는 이미 EXP-088/ADR-0079에서 범위를 정했다. 여기서 추가 분류를 제안하지 않는다.
- 지급 클라이언트 token 실패 wrapping은 결제 범위 밖으로 결정한다. 대안은 지급 API 전용 실패·응답 실험(EXP-073)이며, 결제 클라이언트가 payout endpoint를 호출하는 코드 변경이 생기면 재검토한다.
- 구매자 계약 검사는 PR 필수 CI가 아니라 수동 실행으로 결정했다. 자격 증명과 UI를 쓰는 대안이며, 재검토 조건은 서로 다른 runner 3회 연속 승인·취소 성공이다.

## 재실행 결과 (2026-11-26, [Issue #707](https://github.com/Neo1228/ATLAS/issues/707))

위 "재실행 표기"가 이전 에이전트 보고로 적은 mock 오류 코드 행 중 주문 생성 쪽을 같은 Sandbox 앱으로 다시 실행했다(`POST /v2/checkout/orders`, `PayPal-Mock-Response: {"mock_application_codes": "<이름>"}`, 이름마다 n=3, 금액 USD 0.50). 호출은 토큰 1번과 주문 생성 21번이다.

| 조건 | 관측(n) | 판정 |
| --- | --- | --- |
| 429 대용 mock 이름 `RATE_LIMIT_REACHED`, `TOO_MANY_REQUESTS`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT`을 주문 생성에 전달 | 각 n=3, 모두 HTTP 403, 본문이 JSON이 아님(키 집합 없음). 429는 0/12 | 재현 ✓ |
| `PENDING`, `DENIED` 상태 이름을 mock 오류 코드로 주문 생성에 전달 | 각 n=3, 모두 HTTP 403 | 재현 ✓(주문 생성 endpoint만 다시 실행) |
| `INTERNAL_SERVER_ERROR` mock(양성 통제) | 주문 생성 n=3, 모두 HTTP 500, 키 `{message, name}` | 재현 ✓ |

다시 실행하지 않은 것: authorize·capture·refund endpoint의 mock 행, 구매자 승인·취소 링크 probe, no-mock 양성 통제(승인된 주문이 필요한 흐름). 이 행들의 근거는 이전 에이전트 보고 그대로다.
