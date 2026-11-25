# EXP-094: PayPal 확정 거절 규칙은 실제 Sandbox에서 성립하는가, authorization void·만료는 webhook으로 오는가

- 질문: [ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md)는 세 가지 PayPal 거절을 "작업이 일어나지 않았다"는 증거로 보고 확정 분류한다. 근거는 [EXP-081](EXP-081-paypal-double-vs-sandbox.md)의 실제 Sandbox n=1씩이다. 이 규칙은 서로 다른 유발 방법에서도 성립하는가, 규칙을 깨는 반례(4xx인데 작업은 일어난 경우)가 있는가, authorization void·만료는 webhook으로 오는가.
- 상태: **실제 Sandbox 관측 완료**(실행 일시 2026-11-25 07:06Z~08:14Z). "가설과 반증 조건"까지는 측정 전에 `docs(exp-094): ... 사전 등록한다` 커밋으로 먼저 올렸고, "결과" 이후는 측정 뒤에 썼다. 사전 등록 문장은 고치지 않았다. 사전 등록 H4의 반증 조건은 실제로 충족됐고, 그 결과는 아래 "결과"에 그대로 적었다.
- 날짜: 2026-11-25
- 관련: [ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md), [ADR-0030](../02-decisions/adr/ADR-0030-paypal-currency-boundary.md), [EXP-086](EXP-086-admission-k-ttl-and-authorize-window.md), [EXP-088](EXP-088-paypal-definitive-rejection-and-authorization-expiry.md), [Issue #650](https://github.com/Neo1228/ATLAS/issues/650)

## 문제

운영 코드는 PayPal 4xx 세 가지를 재시도 없이 종결하는데, 그 근거는 각 규칙 n=1과 "검증 거절은 리소스를 만들지 않는다"는 일반 지식이다. 틀리면 돈이 잡힌(authorize·capture된) 결제를 DECLINED·VOIDED로 끝내고 재고를 돌려주는 방향으로 틀린다(구매자 돈은 잡히고 주문은 실패). 피해 규모는 측정된 적이 없다(가정: Sandbox 전용이고 Live 결제는 없다). 만료·void webhook은 본문을 본 적이 없어, 운영자가 authorization이 사라졌다는 사실을 capture 시도가 실패해야 알게 된다.

## 사전 등록에 앞서 이미 읽은 것 (측정이 아니라 문서 읽기, 2026-11-25)

가설을 세우기 전에 아래를 열어 읽었고, 이 사실이 가설 모양에 영향을 줬다. 읽은 것과 읽지 못한 것을 구분해 적는다.

| 자료 | 읽은 것 | 영향 |
| --- | --- | --- |
| `developer.paypal.com/negative-testing/request-headers.md` | Sandbox에서 `PayPal-Mock-Response: {"mock_application_codes":"<오류>"}`로 Orders v2(생성·조회·authorize·capture), Payments v2(authorization 조회·capture·void·captures·refund)의 오류를 시뮬레이션한다. 오류 이름은 두 오류 문서를 가리킨다. 예시는 `DUPLICATE_INVOICE_ID` | 만료를 기다리지 않고 `AUTHORIZATION_EXPIRED` 경로를 시험해 볼 수 있을 가능성(H6) |
| `developer.paypal.com/api/rest/webhooks/event-names.md` | `PAYMENT.AUTHORIZATION.VOIDED`: "authorization이 30일 유효 기간에 도달했거나 Void API로 수동 void됨". `PAYMENT.AUTHORIZATION.CREATED`도 있다. **만료 전용 이벤트 이름은 이 문서에 없다** | 만료와 void가 같은 이벤트라는 주장(H7). 단 만료 쪽은 Sandbox에서 29일 기다려 관측하지 못하므로 문서 주장으로만 남는다 |
| Orders v2·Payments v2 오류 문서(HTML) | `ORDER_ALREADY_AUTHORIZED`, `DUPLICATE_INVOICE_ID`는 Orders 오류 목록에 있다. `AUTHORIZATION_VOIDED`, `AUTHORIZATION_EXPIRED`, `PREVIOUSLY_VOIDED`는 두 오류 문서의 텍스트에서 찾지 못했다 | 이 세 이슈는 문서로 계약되지 않은 값이다. Sandbox 관측이 유일한 근거라 n을 늘릴 이유가 더 크다 |
| PayPal OpenAPI(`payments_payment_v2.json`, `checkout_orders_v2.json`) | authorization `status` 열거: `CREATED, CAPTURED, DENIED, PARTIALLY_CAPTURED, VOIDED, PENDING`(EXPIRED 없음). `PREVIOUSLY_CAPTURED`는 Payments 스펙에 있고 `AUTHORIZATION_EXPIRED`는 스펙에 한 번 나온다 | 만료된 authorization의 조회 `status`가 무엇인지 문서로 알 수 없다(H6) |

## 가설과 반증 조건

모든 가설은 실제 Sandbox(`api-m.sandbox.paypal.com`)에서만 측정한다. "유발 방법"은 서로 다른 입력 변이를 뜻한다(같은 변이 반복은 n으로 세지 않는다).

| 번호 | 가설 | 지표 | 기대값 | 반증 조건(이 관측이 나오면 가설이 틀림) | 결정 |
| --- | --- | --- | --- | --- | --- |
| H1 | A2: 주문 생성이 400·422(+`details[0].issue`)로 거절되면 주문은 만들어지지 않았다 | 거절 응답 본문의 `id` 유무, 같은 Request-Id로 교정 본문을 보냈을 때 돌아온 주문의 `custom_id`가 거절된 본문의 것인지 | 유발 방법 ≥10개 중 4xx가 나온 모든 경우에서 본문에 `id` 없음, 교정 호출이 돌려준 주문의 `custom_id`는 항상 교정 본문의 값 | 4xx 본문에 `id`·`links[rel=approve]`가 있다. 또는 교정 호출이 거절 본문의 `custom_id`를 가진 주문을 돌려준다 | 규칙 유지. 반증되면 반증된 이슈를 제외하는 허용 목록으로 좁힌다 |
| H1b | 거절은 Request-Id에 묶이는가(복구가 같은 Request-Id로 재시도하므로 중요) | 같은 Request-Id·같은 본문 재전송 응답, 같은 Request-Id·교정 본문 응답 | 두 경우 모두 동일 거절을 재생하거나 새 주문(200·201)을 만든다. 어느 쪽인지는 기록만 한다 | 재시도가 2xx인데 첫 호출이 만든 주문과 같은 ID(첫 호출이 주문을 만들었다는 신호) | 거절 재생이면 "확정 거절 재시도는 같은 거절"이 증명되고 호출 낭비 제거가 안전하다는 근거가 늘어난다 |
| H2 | A3(voided): void된 authorization의 capture는 422 `AUTHORIZATION_VOIDED`이고, 직후 주문 조회는 `authorizations[0].status=VOIDED`이며 capture가 없다 | 상태·이슈, 직후 GET의 상태와 `captures` 개수 | 서로 다른 authorization n≥8개, capture 변이(전액, 일부, `Prefer` 없음, 새·재사용 Request-Id) 각각에서 422 `AUTHORIZATION_VOIDED`, GET은 `VOIDED`, captures 0 | 2xx가 오거나, 이슈가 다르거나, GET이 `CREATED`·`CAPTURED`이거나 `captures`가 있다 | 규칙 유지. 어느 하나라도 있으면 조회 대조 조건을 강화하거나 규칙을 삭제 |
| H3 | A4: void가 `PREVIOUSLY_VOIDED`이면 void는 이미 된 것이다. `PREVIOUSLY_CAPTURED`(capture된 authorization을 void)는 void된 것이 아니다 | void 재호출 응답 이슈, GET 상태 | void 뒤 void ≥5회(새 Request-Id) 모두 422 `PREVIOUSLY_VOIDED`, GET `VOIDED`. capture 뒤 void는 422 `PREVIOUSLY_CAPTURED`, GET `CAPTURED` | `PREVIOUSLY_VOIDED`인데 GET이 `VOIDED`가 아니다 | 규칙 유지. 반증 시 분기 삭제 |
| H4 | 음성 통제 사냥: "4xx인데 작업은 일어난" 응답이 존재한다. 있으면 그 이슈가 확정 집합에 없어야 한다 | 아래 사냥 목록의 각 응답에서 (a) 작업이 실제로 일어났는지 GET으로, (b) 그 `(상태, 이슈)`가 운영의 확정 집합에 속하는지 | 존재하며(예: capture 두 번째 호출 `AUTHORIZATION_ALREADY_CAPTURED`, capture 뒤 void `PREVIOUSLY_CAPTURED`, 두 번째 authorize `ORDER_ALREADY_AUTHORIZED`) 모두 확정 집합 밖이다 | 확정 집합 안의 `(상태, 이슈)`인데 작업이 일어났다(예: 일부 capture 뒤 void한 authorization을 같은 Request-Id로 capture 재시도해 422 `AUTHORIZATION_VOIDED`인데 capture가 존재) | 후자가 나오면 운영 코드의 "조회에 capture가 없을 때만" 조건이 방어선이라는 사실을 문서화하고 그 조건을 테스트로 고정한다 |
| H5 | A5: PayPal이 보낸 4xx JSON은 `details[0].issue`가 있다. 여러 위반이 동시에 있어도 확정 이슈가 `details[0]`에 온다 | 전 측정에서 4xx 본문의 `details` 존재율, 다중 위반 본문의 이슈 순서 | PayPal 4xx(401·404 포함) 중 이슈 없는 본문의 비율을 관측한다. 주문 생성 거절은 100% | 주문 생성 400·422 거절 본문에 이슈가 없거나, `details[0]`이 아닌 `details[1]`에만 있다 | 이슈 없는 거절은 이미 UNKNOWN(보수적)이라 틀려도 안전하다. 순서만 의미를 가진다 |
| H6 | 만료 시뮬레이션: `PayPal-Mock-Response`로 `AUTHORIZATION_EXPIRED` 거절을 만들 수 있다. 시뮬레이션은 authorization 실제 상태를 바꾸지 않는다(조회 `CREATED`, 이후 일반 capture 성공) | 헤더 호출 응답, 직후 GET, 헤더 없는 capture 성공 여부 | 모의 거절이 오면 GET은 `CREATED`이고 만료 시각은 미래이며 헤더 없는 capture는 성공한다 | 모의가 거절하지 않거나(헤더 무시), 모의 뒤 실제 상태가 바뀐다 | 모의가 되면: 운영의 "만료 거절이면 조회 `CREATED`도 허용" 분기가 만료되지 않은 authorization을 VOIDED로 종결할 수 있다는 위험을 분리해 보인다(운영 코드에 실제로 위험한지는 별도 판단) |
| H7 | webhook: void를 일으키면 Sandbox에서 `PAYMENT.AUTHORIZATION.VOIDED`가 수신기에 도착한다. 본문은 `resource.id`=authorization ID, `resource.status=VOIDED`를 갖고 주문과 이어지는 ID가 있다 | 수신 여부와 지연, 본문 키 집합, PayPal 서명 검증 결과 | 10분 안 도착, 서명 검증 SUCCESS | 10분 안에 도착하지 않는다(미도착으로 기록), 또는 본문이 위 모양이 아니다 | 도착과 모양이 확인되고 현재 처리(기록만)가 부족하면 최소 처리를 설계한다. 도착하지 않으면 처리하지 않고 이유를 적는다 |
| H8 | 이미 있는 처리: 서명 검증을 통과한 알 수 없는 이벤트 유형은 `webhook_events`에 기록되고 상태를 바꾸지 않는다 | 코드와 기존 테스트 | 알 수 없는 유형은 insertReceived 뒤 markProcessed만 실행 | 알 수 없는 유형이 상태를 바꾼다 | 기록만 하는 현재 처리가 이미 "상태를 직접 바꾸지 않는다"를 만족하므로 webhook 처리를 추가하는 비용 대비 이득을 따진다 |

### 유발 방법 (H1, 실행 전 확정)

주문 생성(`intent=AUTHORIZE`, 운영 본문 모양: `purchase_units[0].amount`, `custom_id`, `payment_source.paypal.experience_context`)의 변이 15개. 각 변이는 서로 다른 입력 오류다. 어떤 변이가 2xx로 수락되면 그것도 결과로 기록하고(4xx가 아니므로 H1의 분모에서 뺀다) 수락 비율을 같이 적는다.

| 번호 | 변이 |
| --- | --- |
| T01 | 통화 `KRW`(PayPal 미지원) |
| T02 | 통화 `JPY`에 소수 값 |
| T03 | 금액 `0.00` |
| T04 | 금액 `-1.00` |
| T05 | 금액 소수 3자리 `1.001` |
| T06 | 금액 문자열 `abc` |
| T07 | `amount` 누락 |
| T08 | `intent` 값 `STEAL`(열거 외) |
| T09 | `purchase_units` 빈 배열 |
| T10 | 통화 코드 `ZZZ` |
| T11 | 금액 한도 초과 `99999999999.00` |
| T12 | `custom_id` 256자 초과 |
| T13 | `invoice_id` 128자 초과 |
| T14 | `items` 합계와 `amount.breakdown` 불일치 |
| T15 | `PayPal-Mock-Response` `DUPLICATE_INVOICE_ID`(모의, 실제 중복 `invoice_id` 두 건 생성은 대조로 별도 실행) |

### 음성 사냥 목록 (H4)

1. 같은 authorization에 다른 Request-Id로 capture 두 번째 호출(EXP-081에서 `AUTHORIZATION_ALREADY_CAPTURED`).
2. capture된 authorization을 void(`PREVIOUSLY_CAPTURED`).
3. 이미 authorize된 주문에 다른 Request-Id로 authorize(`ORDER_ALREADY_AUTHORIZED`).
4. 일부 capture(`final_capture=false`) 뒤 남은 부분을 void하고, 처음 capture와 같은 Request-Id로 capture를 재시도: 재생(2xx)인지 422인지, 422이면 주문 조회에 capture가 있는지.
5. capture와 void를 동시에 호출(레이스): 둘 다 성공하는가, 둘 다 실패하면서 돈이 잡히는가.
6. void된 authorization의 capture를 같은 Request-Id로 재시도(첫 호출이 이미 422): 재생인가.

## 통제

| 대조 | 내용 | 무엇을 증명하는가 |
| --- | --- | --- |
| 변경 없음 | 정상 본문 주문 생성·authorize·capture를 같은 하네스 코드로 실행 | 하네스가 정상 경로에서 2xx를 읽고 `id`를 읽는다 |
| 음성(탐지기가 반응함) | (a) 첫 호출이 **성공**한 Request-Id로 교정 본문을 재전송하면 `custom_id`가 첫 본문의 것으로 돌아와야 한다(재생). 반응하지 않으면 H1의 `custom_id` 판별기는 쓸모가 없다 (b) capture 두 번째 호출이 `AUTHORIZATION_ALREADY_CAPTURED`로 오고 GET이 capture를 보여야 한다. 이 응답이 "4xx인데 작업은 일어난" 경우를 탐지하는 감지기의 양성 사례다 | 판별기·감지기가 실제로 반응한다는 증거. 이게 없으면 H1·H2의 "반례 없음"은 의미가 없다 |
| 전후 | 운영 코드 분류 변경이 있으면 같은 `responses.json` 재생 테스트를 변경 전·후에 실행 | 변경이 재생 결과를 어떻게 바꾸는가 |

## 표본 크기와 한계

- H1: 유발 방법 15개, 4xx가 나온 방법을 분모로 쓴다. 방법마다 3회 반복해 호출 수를 늘리되 독립 표본은 방법 수다. 반례 0건이면 3/n(규칙의 3, 95%) 상한을 방법 수 n으로 적는다(예: n=10이면 30%). 이 상한은 크므로 "없음의 증명"이 아니라 "관측 가능한 반례가 없었다"로만 쓴다.
- H2: authorization 인스턴스 n≥8(각각 구매자 승인이 필요해 브라우저 약 30~60초). 변이는 인스턴스에 고르게 배정한다.
- H3: void 재호출 ≥5, 인스턴스 n≥5.
- 한계: 주문 생성이 "만들어지지 않았다"를 PayPal 쪽에서 직접 증명할 방법이 없다(Orders v2에는 목록 API가 없고, 거래 검색은 capture된 거래만 보인다). 아래 판별기는 간접 증거이고, 강한 증명이 아니다. 이 한계는 결과와 ADR에 그대로 쓴다.
- 이 Mac은 공유 호스트다. 시간 값은 맥락으로만 적고 결론은 개수와 불변식으로 낸다. 조건 순서는 고정 시드로 섞어 실행하고 실행 전후 `uptime`을 남긴다.

## 교란 요인과 배제 방법

| 교란 요인 | 배제 방법 |
| --- | --- |
| PayPal Sandbox 상태(속도·일시 장애·계정 상태)가 시점마다 다르다 | 실행 전후 같은 정상 경로 한 번씩(변경 없음 대조), 5xx·429가 섞이면 기록하고 그 시도는 분모에서 제외하되 개수를 적는다 |
| 같은 Request-Id 재생이 결과를 가린다 | 변이마다 새 Request-Id, 재생은 의도한 대조에서만 쓴다 |
| 하네스 버그(교정 본문이 사실은 거절될 본문과 같음 등) | 음성 대조 (a)로 판별기를 증명하고, 요청 본문을 결과 JSON에 같이 남긴다(비밀 제외) |
| 구매자 승인 자동화 실패(타임아웃)가 거절로 오인됨 | 승인 실패 시도는 별도 표시하고 분모에서 제외, 개수 보고 |
| 응답 ID 노출 | 증거 파일의 ID는 가림(해시 앞 4자리 대체) |
| 자격 증명 노출 | 값은 프로세스 안에서만 읽고 출력·저장하지 않는다. 증거 파일에 헤더·토큰이 없음을 `grep`으로 확인 |

## 층 이동 계획

앱 로그·상태 응답(HTTP 본문) → 필요할 때 조회로 서버 상태 대조 → webhook이면 네트워크(터널·수신)와 PayPal 서명 검증. DB·런타임·OS 층은 이 질문의 증거가 가리키지 않으면 내려가지 않는다. 멈춘 층과 이유는 결과에 적는다.

## 결정 규칙 (실행 전 확정)

- H1이 반증되면(반례 1건이라도) 주문 생성 거절 분류를 허용 목록(이슈 열거)으로 좁히거나 삭제한다.
- H2·H3가 반증되면 해당 분기를 삭제한다. 하나도 반증되지 않으면 운영 분류는 바꾸지 않고 증거만 더한다("규칙을 억지로 바꾸지 않는다").
- H7이 도착을 보여도 현재 처리(기록만)로 충분하면 운영 코드는 바꾸지 않는다. 최소 처리는 "기록과 복구 깨우기까지"이고 돈 이동은 하지 않는다(ADR-0030).
- 운영 코드·마이그레이션이 바뀌면 되돌리기를 한 번 실제로 실행해 기록한다. 바뀌지 않으면 "되돌릴 것이 없다"고 쓴다.

## 실행 방법 (이 저장소에 있는 것과 없는 것)

- 저장소에 둔 재현 도구는 하나다: `load-tests/paypal-real-fault/rejection-rules.mjs`(주문 생성 거절, void된 authorization의 capture·void, 음성 사냥, 모의 헤더). `REAL_SANDBOX_OPT_IN=YES PHASES=orders,auth,hunt,mock node rejection-rules.mjs`로 실행하고 `.env.local`에서 자격 증명을 프로세스 안으로만 읽는다. 결과 파일은 `/tmp`에 쓰고 저장소에는 두지 않는다. 변이 15개와 반복 횟수(`REPS=3`), authorization 수(`AUTH_N=8`), 조건 순서 시드(`SEED=94`)가 매개변수다.
- 저장소에 두지 않은 것: webhook 관측 도구(공개 터널을 열어 임시 webhook을 등록·삭제하는 로컬 전용 스크립트), 집계 스크립트, 실행별 원본 JSON. 이 문서의 표가 그 내용을 직접 옮긴 것이다(아래 "저장소에 두지 않은 기록의 내용"). webhook 관측은 이 저장소만으로 재현할 수 없다. 방법은 "webhook 관측 방법"에 말로 적었다.
- 호출 대상은 `api-m.sandbox.paypal.com` 뿐이다. 자격 증명 값은 어디에도 출력·저장하지 않았고, 커밋하는 `responses.json`과 이 문서에서 자격 증명 값과 계정 식별자(이메일, 계정 ID, 가맹점 ID, 구매자 이름·주소)가 나오지 않는지 값으로 대조했다(일치 0건).

## 결과

### H1: 주문 생성 400·422는 주문을 만들지 않았다 (판정: 관측 범위에서 확인, 증명은 아님)

변이마다 3회씩 실제 Sandbox에 `PayPal-Request-Id` 새 값으로 보냈다. 공통 본문은 운영과 같은 모양(`intent=AUTHORIZE`, `purchase_units[0]`에 `reference_id`·`amount`·`custom_id`, `payment_source.paypal.experience_context`)이고 변이만 바꿨다. T15를 뺀 14개 변이 모두 4xx였고(5xx·429 0건) 응답 본문 키 집합은 모두 `debug_id, details, links, message, name`이다(`id`, `status`, 승인 링크 없음).

| 변이 | 본문 변이 내용 | 상태 | `details[0].issue` | n | 같은 Request-Id·같은 본문 재전송 | 같은 Request-Id·교정 본문 |
| --- | --- | --- | --- | --- | --- | --- |
| T01 | 통화 `KRW` | 422 | `CURRENCY_NOT_SUPPORTED` | 3 | 같은 거절 3/3 | 200 새 주문 3/3 |
| T02 | 통화 `JPY`에 `1.50` | 422 | `DECIMALS_NOT_SUPPORTED` | 3 | 3/3 | 3/3 |
| T03 | 금액 `0.00` | 422 | `CANNOT_BE_ZERO_OR_NEGATIVE` | 3 | 3/3 | 3/3 |
| T04 | 금액 `-1.00` | 422 | `CANNOT_BE_ZERO_OR_NEGATIVE` | 3 | 3/3 | 3/3 |
| T05 | 금액 `1.001` | 422 | `DECIMAL_PRECISION` | 3 | 3/3 | 3/3 |
| T06 | 금액 문자열 `abc` | 400 | `INVALID_PARAMETER_SYNTAX` | 3 | 3/3 | 3/3 |
| T07 | `amount` 누락 | 400 | `MISSING_REQUIRED_PARAMETER` | 3 | 3/3 | 3/3 |
| T08 | `intent=STEAL` | 400 | `INVALID_PARAMETER_VALUE` | 3 | 3/3 | 3/3 |
| T09 | `purchase_units` 빈 배열 | 400 | `INVALID_ARRAY_MIN_ITEMS` | 3 | 3/3 | 3/3 |
| T10 | 통화 `ZZZ` | 422 | `INVALID_CURRENCY_CODE` | 3 | 3/3 | 3/3 |
| T11 | 금액 `99999999999.00` | 422 | `TRANSACTION_LIMIT_EXCEEDED` | 3 | 3/3 | 3/3 |
| T12 | `custom_id` 300자 | 400 | `INVALID_STRING_LENGTH` | 3 | 3/3 | 3/3 |
| T13 | `invoice_id` 200자 | 400 | `INVALID_STRING_LENGTH` | 3 | 3/3 | 3/3 |
| T14 | `items` 합계와 `breakdown.item_total` 불일치(9.00 대 1.00) | 422 | `ITEM_TOTAL_MISMATCH` | 3 | 3/3 | 3/3 |
| T15 | `PayPal-Mock-Response: DUPLICATE_INVOICE_ID`(모의, 분모에서 제외) | 422 | `DUPLICATE_INVOICE_ID` | 3 | 해당 없음 | 해당 없음 |

- 서로 다른 (상태, 이슈) 쌍 12개, 변이 14개(본문 필드를 바꾼 서로 다른 위반 유형), 호출 42건. 400이 6개 변이·422가 8개 변이.
- 판별기: 같은 Request-Id로 교정 본문을 다시 보내 돌아온 주문을 조회해 `custom_id`가 거절됐던 본문의 값인지, 교정 본문의 값인지 봤다. 42건 모두 교정 본문의 값이었고 거절 본문의 값은 0건이다. 거절된 호출이 주문을 만들었다면 같은 Request-Id 재전송은 그 주문을 돌려줬을 것이다(음성 통제에서 이 판별기가 반응함을 확인).
- 실제 중복 `invoice_id`(같은 `invoice_id`로 Request-Id만 다른 두 주문)는 둘 다 200으로 수락됐다. 이 계정은 `DUPLICATE_INVOICE_ID`를 실제로 내지 않아 이 이슈는 모의 응답으로만 관측했다(운영에서 그 계정 설정이 켜질 수 있으므로 모의 본문도 재생 테스트에 포함하지 않고, 실제로 오는 것만 넣었다).
- 반례 0건. 변이 14개(독립 시행으로 센 것)에서 0건이므로 규칙의 3 기준 상한은 3/14 = 약 21%(95%)다. 호출 42건은 변이 안에서 독립이 아니라 분모로 쓰지 않았다.
- **증명하지 못한 것:** Orders v2에는 목록 조회가 없어 "PayPal 쪽에 주문이 하나도 없다"를 직접 보지 못했다. 같은 Request-Id 판별기는 간접 증거다(PayPal이 실패한 요청에도 Request-Id를 기록하지 않는다는 가정이 남는다. 실패 요청에 Request-Id가 안 묶인다는 것은 "교정 본문이 새 주문을 만든다"로 관측했고, 그래서 판별기의 능력은 "성공한 첫 호출은 재생된다"에 기댄다).

### H1b: 확정 거절은 Request-Id에 묶이는가 (판정: 확인)

- 같은 Request-Id·같은 본문을 다시 보내면 상태와 이슈가 같은 거절이 42/42 돌아왔다(성공이 아니라 같은 거절). 운영 복구는 같은 Request-Id·같은 본문으로 최대 8회 재시도하므로, 확정 거절에서 재시도는 결정적으로 낭비다.
- 같은 Request-Id라도 본문을 교정하면 새 주문이 만들어졌다(42/42). 거절은 Request-Id를 소비하지 않는다. 운영은 거절 뒤 본문을 바꾸지 않으므로(금액은 서버가 고정) 이 경로는 쓰이지 않는다.

### H2: void된 authorization의 capture는 422 `AUTHORIZATION_VOIDED`이고 조회는 `VOIDED`·capture 0 (판정: 확인)

authorization 8개(각각 구매자 승인 후 authorize, 만료 시각은 모두 생성 +696.00시간), 각각 void(204 8/8) 뒤에 capture 변이 2개씩, 변이별 4회.

| capture 변이 | n | 상태·이슈 | 직후 주문 조회 `authorizations[0].status` | 조회의 `captures` 개수 | 같은 Request-Id 재전송 |
| --- | --- | --- | --- | --- | --- |
| 전액, `Prefer: return=representation` | 4 | 422 `AUTHORIZATION_VOIDED` | `VOIDED` | 0 | 422 `AUTHORIZATION_VOIDED` 4/4 |
| 부분 0.50, `final_capture=false` | 4 | 422 `AUTHORIZATION_VOIDED` | `VOIDED` | 0 | 422 4/4 |
| 전액, `Prefer` 없음 | 4 | 422 `AUTHORIZATION_VOIDED` | `VOIDED` | 0 | 재전송 안 함 |
| 빈 본문 | 4 | 422 `AUTHORIZATION_VOIDED` | `VOIDED` | 0 | 재전송 안 함 |

- 16건 모두 규칙대로. 오류 본문 키 집합은 `debug_id, details, links, message, name`이고 이슈 설명은 "Authorization has been voided."다. authorization 8개 기준 반례 0건이라 3/8 = 37.5%(95% 상한)다. n이 작아 "지금 운영에서 반례가 없다"는 말은 하지 못한다.
- **이 규칙 단독으로는 거짓이 되는 사례를 H4에서 찾았다.** 아래 H4.

### H3: void 재호출 `PREVIOUSLY_VOIDED`는 이미 void됨 (판정: 확인)

- void 뒤 새 Request-Id로 void를 두 번 더 보냈다: 16/16(authorization 8개) 모두 422 `PREVIOUSLY_VOIDED`(본문에 `field=authorization_id`, `location=path` 포함), 이후 조회 `VOIDED` 8/8.
- 같은 Request-Id로 void를 재전송하면 204가 8/8 돌아왔다(재생).
- capture된 authorization을 void하면 422 `PREVIOUSLY_CAPTURED`(n=1)이고 조회는 `CAPTURED`·capture 1개였다. 이 이슈는 확정이 아니며 코드는 `PREVIOUSLY_VOIDED`만 완료로 본다.

### H4: "4xx인데 작업은 일어난" 응답 사냥 (판정: 반례 있음 = 사전 등록 반증 조건 충족)

| 사냥 | 응답 | 조회로 본 실제 상태 | 확정 집합 안인가 |
| --- | --- | --- | --- |
| capture 성공 뒤 다른 Request-Id로 capture(n=1) | 422 `AUTHORIZATION_ALREADY_CAPTURED` | `CAPTURED`, captures 1 | 아니오(UNKNOWN 유지가 옳다) |
| 같은 Request-Id로 첫 capture 재전송(n=1) | 200(성공 재생, 같은 capture) | captures 1 | 해당 없음 |
| capture된 authorization을 void(n=1) | 422 `PREVIOUSLY_CAPTURED` | `CAPTURED`, captures 1 | 아니오 |
| 이미 authorize된 주문에 authorize(n=2: 캡처된 주문 1, 새 주문 1) | 422 `ORDER_ALREADY_AUTHORIZED` | authorization 1개(`CREATED` 또는 `CAPTURED`) | 아니오 |
| **부분 capture(0.50) → 남은 부분 void → 같은 Request-Id로 부분 capture 재전송(n=1)** | 200(재생) | captures 1, authorization `VOIDED` | 해당 없음 |
| **같은 상태에서 새 Request-Id로 capture(n=1)** | **422 `AUTHORIZATION_VOIDED`** | **captures 1(COMPLETED), authorization `VOIDED`** | **예: 확정 이슈인데 돈은 잡혀 있다** |
| capture와 void 동시 호출 3회 | capture 3/3 422 `AUTHORIZATION_VOIDED`, void 3/3 204 | captures 0 | 규칙대로(둘 다 성공한 경우는 0) |

- 음성 통제(감지기가 반응함): `AUTHORIZATION_ALREADY_CAPTURED`가 "4xx인데 작업은 일어난" 응답이며 조회가 captures 1을 보여 감지기가 반응했다.
- **결론:** 응답 `AUTHORIZATION_VOIDED` 하나는 "자금이 잡히지 않았다"를 증명하지 않는다(일부 capture 뒤 void한 authorization). 운영 코드는 이미 거절 뒤 주문 조회에서 capture가 있으면 그 결과를 따르므로 이 반례에서 VOIDED로 끝내지 않는다. 이 조건이 방어선임을 코드 변경 없이 테스트로 고정했다(`recordedVoidedCaptureThatFollowsAPartialCaptureIsNeverVoided`, 변이 M2로 확인). n=1이다.
- 운영 흐름은 전액 capture 한 번이라 부분 capture 뒤 void 경로는 운영자가 PayPal 쪽에서 직접 부분 capture한 경우에만 생긴다(가정).

### H5: 오류 본문의 이슈 (판정: 확인)

- 주문 생성 4xx 42/42는 `details[0].issue`가 있었다.
- 위반 둘이 겹친 400(`intent=STEAL`과 `amount.value=abc`와 `KRW`): `details`에 이슈 2개, 순서 `INVALID_PARAMETER_VALUE`, `INVALID_PARAMETER_SYNTAX`(n=1). `details[0]`이 첫 위반이다. 422 다중 위반은 만들지 못했다.
- 이슈가 없는 PayPal 4xx: 잘못된 Bearer 토큰 401 `{error: invalid_token}`(본문에 `details` 없음, n=1). 코드가 이를 확정에서 제외하므로 맞다.
- 404 `RESOURCE_NOT_FOUND`/`INVALID_RESOURCE_ID`(알 수 없는 주문 조회, 알 수 없는 authorization capture, n=2씩): `details[0].issue`가 있지만 주문 생성이 아니라 다른 호출이다. 확정 집합 밖.

### H6: `PayPal-Mock-Response`로 만료 거절을 만들 수 있다 (판정: 확인, 단 실제 만료는 아님)

- 공식 문서(`developer.paypal.com/negative-testing/request-headers.md`)대로 `PayPal-Mock-Response: {"mock_application_codes":"AUTHORIZATION_EXPIRED"}`를 capture에 붙이면 422 `AUTHORIZATION_EXPIRED`("An expired authorization cannot be captured.")가 왔다(n=2 authorization). 같은 방식으로 `AUTHORIZATION_VOIDED`, `AUTHORIZATION_ALREADY_CAPTURED`도 422로 왔다. capture에 `PREVIOUSLY_VOIDED`와 `INSTRUMENT_DECLINED`를 붙이면 403 빈 본문(각 n=2)이었고, void에 `PREVIOUSLY_VOIDED`를 붙이면 422였다.
- 모의 거절은 authorization 상태를 바꾸지 않았다: 직후 조회 `CREATED`, `expiration_time`은 29일 뒤, 헤더 없는 실제 capture는 201로 성공했다(2/2).
- 따라서 실제 만료 거절은 관측하지 못했다. 만료 뒤 조회가 `VOIDED`인지 `CREATED`+과거 `expiration_time`인지 `EXPIRED` 같은 새 값인지는 여전히 모른다. OpenAPI 열거에 `EXPIRED`는 없다.
- 이 모의는 "만료 거절 + 조회 `CREATED`" 조합이 만들어질 수 있음을 보였고(실제 만료에서 같은 조합이 나올지는 모름), ADR-0079의 `expired && CREATED → VOIDED` 분기가 살아 있는 authorization을 VOIDED로 끝낼 수 있음을 뜻한다. 그래서 이 분기만 조회의 `expiration_time`이 이미 지났을 때로 좁혔다(코드 변경, 아래).

### H7·H8: webhook (판정: H7 부분 확인, H8 확인)

webhook 관측 방법: 로컬 수신기와 SSH 역터널(`localhost.run`)로 공개 HTTPS 주소를 얻고, 같은 앱의 Sandbox에 `PAYMENT.AUTHORIZATION.CREATED`·`PAYMENT.AUTHORIZATION.VOIDED`·`PAYMENT.CAPTURE.COMPLETED`를 구독하는 임시 webhook을 등록했다. authorization 두 개(A, B)를 만들어 A는 void, B는 capture하고, PayPal 시뮬레이터(`simulate-event`)로 `PAYMENT.AUTHORIZATION.VOIDED`도 한 번 보냈다. 끝에 webhook을 삭제했다(204). 도착한 본문마다 운영 `PayPalRestClient.verifyWebhook`과 같은 요청 모양으로 `verify-webhook-signature`를 호출하고, 본문을 한 글자 바꾼 복사본도 호출했다(음성 통제).

- 하네스 결함 1건: 첫 실행에서 터널 주소를 잡는 정규식이 안내 문구의 `admin.localhost.run`을 잡아 PayPal이 엉뚱한 곳으로 보냈고 도착 0건이었다. 같은 실행의 터널 양성 대조(공개 주소로 직접 POST가 로컬에 닿는가)가 "닿지 않음"이라 이 결과는 분모에서 뺐다. 정규식을 고치고 양성 대조를 실패 시 중단으로 바꾼 뒤 3회 실행했다.
- 3회 실행(터널 양성 대조 통과) 관측:

| 실행 | 도착 이벤트 (void 호출 기준 지연) | 서명 검증 | 본문을 바꾼 복사본 |
| --- | --- | --- | --- |
| 1 | `AUTHORIZATION.CREATED`(A, 1초), **`AUTHORIZATION.VOIDED`(A, 7초)**, 시뮬레이터 `VOIDED`(8초), `AUTHORIZATION.CREATED`(B, 11초, 도착 시점 `status=CAPTURED`), `CAPTURE.COMPLETED`(B, 21초) | 5/5 `SUCCESS` | 5/5 `FAILURE` |
| 2 | `AUTHORIZATION.CREATED`(A, 1초), **`AUTHORIZATION.VOIDED`(A, 6초)**, 시뮬레이터 `VOIDED`(12초), `CAPTURE.COMPLETED`(B, 13초), `AUTHORIZATION.CREATED`(B, 20초, `status=CAPTURED`) | 5/5 `SUCCESS` | 5/5 `FAILURE` |
| 3 | 시뮬레이터 `VOIDED`(36초) 한 건만. **실제 `VOIDED`·`CAPTURE.COMPLETED`·`CREATED`는 244초 동안 도착하지 않았다** | 1/1 `SUCCESS` | 1/1 `FAILURE` |

- 사실:
  - **실제 void는 `PAYMENT.AUTHORIZATION.VOIDED`를 보낸다.** 3회 중 2회 6~7초 안에 도착했고 1회는 244초 안에 오지 않았다(원인 미확정: PayPal 쪽 지연·유실인지 무료 터널 문제인지 가리지 못했다. 같은 터널로 시뮬레이터 이벤트는 36초에 왔다).
  - 실제 `VOIDED` 본문(2건)의 `resource` 키는 `amount, create_time, custom_id, expiration_time, id, links, payee, seller_protection, status, supplementary_data, update_time`이고 `resource.id`=authorization ID, `resource.status=VOIDED`, `supplementary_data.related_ids.order_id`가 있다. 이벤트 키 집합: `create_time, event_type, event_version, id, links, resource, resource_type, resource_version, summary`(`resource_version=2.0`).
  - **순서·시점:** 도착한 `AUTHORIZATION.CREATED`(B)의 `resource.status`는 도착 시점 상태인 `CAPTURED`였다(생성 시점 값이 아님). `CAPTURE.COMPLETED`와 B의 `CREATED`는 실행 1에서 CREATED가 먼저, 실행 2에서 `CAPTURE.COMPLETED`가 먼저 왔다. 순서는 보장되지 않는다.
  - 시뮬레이터 `VOIDED`는 2014년 날짜의 v1 모양 샘플 본문(`state:voided`, `amount.total`, `parent_payment`, `valid_until`, `supplementary_data` 없음)이고 같은 서명 검증도 `SUCCESS`였다. 시뮬레이터 이벤트로는 주문 ID를 얻지 못한다(운영 파서는 `providerOrderId=null`). 실제 본문과 같은 모양이 아니므로 재생 테스트는 실제 이벤트만 썼다.
  - **만료 전용 이벤트는 없다.** 이 앱이 구독할 수 있는 이벤트 유형 205개 중 authorization 관련은 `PAYMENT.AUTHORIZATION.CREATED`·`PAYMENT.AUTHORIZATION.VOIDED`뿐이다(`IDENTITY.AUTHORIZATION-CONSENT.*`는 다른 것). 문서(`developer.paypal.com/api/rest/webhooks/event-names.md`)는 `VOIDED`를 "authorization이 30일 유효 기간에 도달했거나 Void API로 void됨"이라고 쓴다. 만료로 `VOIDED`가 오는지는 29일을 기다리지 않아 **관측하지 못했고 문서 주장으로만 남는다**(문서의 "30일"은 이 저장소 문서의 "29일 유효"와도 다르다).
- 현재 코드: 서명 검증을 통과한 `PAYMENT.AUTHORIZATION.VOIDED`는 `PaymentWebhookProcessor`에서 수신 기록(`insertReceived`)과 처리 완료 표시(`markProcessed`)만 하고 결제 시도·복구·환불 상태를 건드리지 않는다. 단위 테스트(`recordedAuthorizationVoidedEventIsOnlyRecorded`)가 실제 본문으로 이를 고정한다(H8). 운영 파서는 이 본문에서 `eventType`, `resourceId`(authorization ID), `resourceStatus`, `providerOrderId`를 읽는다(`recordedAuthorizationVoidedWebhookIsParsedToTheAuthorizationAndItsOrder`).

## 결정 대안 비교 (webhook·분류)

| 대안 | 비용 | 위험 | 근거 | 판정 |
| --- | --- | --- | --- | --- |
| A. 변경 없음 | 0 | 만료 거절+조회 `CREATED`가 살아 있는 authorization을 VOIDED로 끝낼 수 있음(모의로 조합 확인) | 모의 헤더 n=2 | 기각 |
| B. 재생 테스트만 보강(규칙 코드 변경 없음) | 작음 | 위 조합 위험이 남음 | 확정 규칙 3개는 42/42·16/16·16/16로 성립 | 부족 |
| C. B + 만료 분기를 조회 만료 시각 경과로 좁힘 | 코드 약 10줄, 생성자에 `Clock` 1개 | 조회에 만료 시각이 없으면 UNKNOWN으로 남아 복구가 반복(안전한 방향) | 위 H6, 결과 불명은 기존 경로 | **채택** |
| D. 만료 분기 삭제(스틸맨: 만료 이슈는 어느 오류 문서에도 없고 실제 관측이 0건이라 근거 없는 코드) | 코드 삭제 | 실제 만료는 운영자 검토로 감(종결 못 함) | OpenAPI 예시에만 있음. 이슈가 문서화되지 않았다는 점은 맞다 | 기각: C가 같은 안전성을 주면서 종결을 유지한다 |
| E. webhook `VOIDED`로 시도 상태 변경 | 중간 | 순서 비보장(관측), 6/7초 지연·1회 244초 미도착, 도착 시점 상태가 본문에 들어감, 구매자 돈 이동 금지(ADR-0030) | H7 관측 | 기각 |
| F. webhook `VOIDED`로 복구만 깨움 | 중간(매핑·큐 변경) | 이득이 측정되지 않음(운영자·복구 경로가 capture 거절로 같은 사실을 요청 시점에 확인), 3회 중 1회 미도착이라 보조 신호도 못 믿음 | 이득 근거 없음 | 보류 |
| G. 주문 생성 거절을 이슈 허용 목록으로 제한(스틸맨: 모르는 이슈는 위험) | 목록 유지 | 12개 쌍 밖의 새 이슈가 오면 불필요하게 UNKNOWN(안전한 방향)이지만 목록 갱신 부담 | 14개 변이 모두 규칙 성립, 반례 0 | 기각: 반례가 없어 복잡도만 늘어난다. 반례가 생기면 이 대안으로 간다 |

## 사전 부검 (이 변경이 운영에서 실패하는 방법)

| 실패 방식 | 탐지 | 방향 |
| --- | --- | --- |
| 실제 만료된 authorization의 조회가 `CREATED`이고 `expiration_time`이 과거인데 서버 시계가 PayPal보다 뒤처져 아직 안 지난 것으로 봄 | 운영자 검토 목록의 `authorizationExpiresAt`이 과거인 항목이 NEEDS_REVIEW에 남음 | 종결이 늦어질 뿐 돈은 안전(UNKNOWN 유지) |
| 만료 뒤 조회에 `expiration_time`이 없음 | 같은 목록에서 `authorizationExpiresAt`이 null | UNKNOWN 유지(안전) |
| 만료 뒤 조회가 `EXPIRED` 같은 새 상태값 | `PayPalAuthorizationStatus.UNKNOWN`으로 읽혀 분기 조건 불충족 → 거절을 그대로 던짐 | UNKNOWN 유지(안전) |
| `PayPalPaymentProvider`에 `Clock` 빈이 없어 기동 실패 | `ClockConfiguration`이 `Clock` 빈을 제공하고 `./gradlew check`의 컨텍스트 테스트가 기동 | 배포 전 CI에서 잡힘 |
| 녹화한 응답이 PayPal 변경으로 낡음 | `paypal-sandbox-contract.yml`은 OAuth·주문 생성·조회·404만 실제 Sandbox와 대조하고 거절 본문과 void·capture 응답은 대조하지 않는다(이 PR은 건드리지 않음) | CI가 이 녹화의 낡음을 잡지 못한다. 다시 녹화하려면 `rejection-rules.mjs`를 돌려 본문을 바꾼다 |

## 변이 검증 (규칙을 깨면 테스트가 실패하는가)

운영 코드에 변이 하나씩 넣고 새 테스트(`PayPalRejectionReplayTest`, `PaymentWebhookProcessorAuthorizationEventTest`)를 돌린 뒤 원본을 복원하고 `cmp`로 일치를 확인했다. 변이 전 기준선은 통과.

| 변이 | 실패한 테스트 | 복원(`cmp`) |
| --- | --- | --- |
| M1 만료 거절의 시각 확인 삭제 | `expiredRejectionWithALiveAuthorizationIsNotEndedBeforeTheExpiryTime` | 일치 |
| M2 조회에 capture가 있어도 무시하고 종결 | `recordedVoidedCaptureThatFollowsAPartialCaptureIsNeverVoided` | 일치 |
| M3 `AUTHORIZATION_ALREADY_CAPTURED`를 확정으로 취급 | `recordedAlreadyCapturedIsNotDefinitive` | 일치 |
| M4 `PREVIOUSLY_CAPTURED`를 void 완료로 취급 | `recordedPreviouslyVoidedIsDoneButPreviouslyCapturedIsNot` | 일치 |
| M5 주문 생성 모든 4xx를 확정으로 취급 | 404 기록, 429, 이슈 없는 400, HTML 422 네 건 | 일치 |
| M5b 주문 생성에서 이슈 없는 거절도 확정 | 이슈 없는 400, HTML 422 두 건 | 일치 |
| M6 `VOIDED` 조회 조건을 무시하고 항상 종결 | 만료 거절·`VOIDED` 거절 모두 살아 있는 조회에서 종결을 막는 테스트 두 건 | 일치 |
| M7 webhook `VOIDED` 처리가 결제 시도 저장소를 조회 | `recordedAuthorizationVoidedEventIsOnlyRecorded` | 일치 |
| M8 파서가 `order_id`를 읽지 않음 | `recordedAuthorizationVoidedWebhookIsParsedToTheAuthorizationAndItsOrder` | 일치 |

- 하네스 결함 두 건을 변이 검증으로 찾았다: 처음에는 M5가 어느 테스트도 실패시키지 못했고(주문 생성에서 확정이 아닌 응답 테스트가 없었다), M6은 `VOIDED` 거절+살아 있는 조회 테스트가 없어 하나만 실패했다. 두 경우 모두 테스트를 더해 위 표의 결과가 됐다.
- 새 테스트가 소수 기록 하나(12개 주문 생성 거절 기록, `VOIDED` 거절 기록, 부분 capture 뒤 void 기록 등)를 재생하므로 `responses.json`에 실제로 쓰이는 본문만 더했다(이름이 `orderCreateError…`, `error422…`, `mockError422AuthorizationExpired`, `getOrder…`, `webhookAuthorizationVoided`인 항목). `AUTHORIZATION.CREATED` 본문 등 쓰이지 않는 관측은 넣지 않았다.

## 되돌리기 훈련

- 변경은 마이그레이션도 플래그도 없다. 되돌림은 `git revert <fix 커밋>`이다.
- 실제로 실행했다: 임시 브랜치에서 fix 커밋을 `git revert`한 뒤(`hasExpired` 0건 확인) 기존 `PayPalSandboxResponseReplayTest`(11건), `PayPalPaymentProviderTest`(1건), `PayPalPaymentProviderStatusTest`(2건)를 돌려 14건 모두 통과했고 `atlas-application` 테스트가 컴파일됨을 확인했다. 임시 브랜치는 지웠다. 이 훈련은 단위 테스트까지다. PostgreSQL 통합 테스트로 되돌린 코드를 돌려 보지는 않았다.

## 교란 요인

| 요인 | 대응 | 남은 한계 |
| --- | --- | --- |
| Sandbox 일시 장애·429 | 호출 중 5xx·429는 0건이었다(주문 생성 42건, void·capture 전부). 변경 없음 대조로 실행 앞뒤에 정상 주문 생성 200/200 | 호출 사이 지연은 측정하지 않았다(결론은 개수로 냈다) |
| 호스트 부하(공유 Mac, 부하 평균 5.4~29.9) | 단계 전후 `uptime`을 남겼다(auth 5.4→7.7, hunt 13.8→11.6, 첫 webhook 9.6→29.9). 결론은 지연이 아니라 상태·이슈 개수에 기댔다 | webhook 도착 지연(6~36초, 1회 미도착)은 호스트 부하와 무료 터널의 영향을 가르지 못했다 |
| Request-Id 재생이 거절을 가림 | 변이·반복마다 새 Request-Id. 재생은 H1b 대조에서만 의도해 사용 | 없음 |
| 조건 순서 효과 | 변이×반복 42건과 authorization 순서를 시드 94로 섞음 | 순서가 결과를 바꾸는 증거는 없었다(재현 순서만 고정) |
| 하네스 결함 | 첫 webhook 실행의 잘못된 터널 주소(음성 결과)를 터널 양성 대조로 찾아 분모에서 제외 | 없음 |
| 승인 자동화 실패 | 대기 중 승인 실패는 별도 표시: 이번 실행에서 0건 | 없음 |
| 자격 증명·식별자 노출 | 값 대조 일치 0건, 응답의 ID는 해시 4자로 가리고 이메일·이름·주소는 `REDACTED` | 없음 |

멈춘 층: HTTP 요청·응답 본문과 PayPal 상태 조회(앱의 PayPal 호출 층), webhook은 공개 터널과 서명 검증까지. 증거가 DB·런타임·OS 층을 가리키지 않아 내려가지 않았다.

## 저장소에 두지 않은 기록의 내용

사용자 지침에 따라 원본 JSON·집계 파일·webhook 도구는 저장소에 올리지 않았다. 위 표에 풀어 적은 것 외의 요약이다.

| 실행 | 단계 | 호출 | 내용 |
| --- | --- | --- | --- |
| 주문 생성 | 변이 15개×3 + 중복 `invoice_id` 2 + 판별기 대조 2 + 4xx 형태 4 + 정상 대조 2 | 190 | 42건 거절(상태·이슈는 H1 표), 정상 대조 200/200, 판별기 대조: 성공한 Request-Id로 교정 본문 재전송 → 같은 주문 ID·`custom_id`는 첫 본문 값(반응함) |
| authorization | 8개 | 104 | H2·H3 표. `authorize` 8/8 `CREATED`, 만료 시각 생성 +696.00시간 8/8 |
| 사냥·모의 | 사냥 6종 + 모의 2개 | 59 | H4·H6 표 |
| webhook | 4회(첫 회 터널 결함으로 제외) | 이벤트 11건 | H7 표. 서명 검증 `SUCCESS` 11/11, 본문 변조 `FAILURE` 11/11 |

- 오류 본문 키 집합은 전 4xx(주문 생성 42건, capture·void 거절 모두. 모의 헤더의 403은 빈 본문)에서 `debug_id, details, links, message, name` 하나뿐이었다. 401 토큰 오류만 `{error, error_description}` 모양이다.
- 모든 응답 ID는 기록에서 해시 4자로 가렸고, `responses.json`에는 기존 항목과 같은 상수 ID로 치환해 넣었다.

## 주장과 증거 (주장 표)

| 주장 | 증거 | 확신 |
| --- | --- | --- |
| 주문 생성 400·422(+이슈)는 거절 본문에 주문 ID가 없고 같은 Request-Id 교정 호출이 새 주문을 만든다(A2) | 변이 14개·42건 반례 0, 판별기 반응 확인, 상한 3/14 | 확인(Sandbox, 간접 증거). PayPal 쪽 부재 증명은 아님 |
| 확정 거절을 같은 Request-Id·본문으로 재시도하면 같은 거절이다 | 42/42 | 확인(Sandbox) |
| void된 authorization의 capture는 422 `AUTHORIZATION_VOIDED`, 조회 `VOIDED`·capture 0(A3 voided) | authorization 8개·16건 | 확인(Sandbox, n=8) |
| 응답 `AUTHORIZATION_VOIDED` 단독으로는 "자금 미확보"가 아니다(일부 capture 뒤 void) | 사냥 1건(captures 1, 422 `AUTHORIZATION_VOIDED`) | 확인(n=1). 조회 대조가 필요하다는 근거 |
| `PREVIOUSLY_VOIDED`는 이미 void됨(A4) | 16/16, 조회 `VOIDED` 8/8 | 확인(Sandbox) |
| `AUTHORIZATION_ALREADY_CAPTURED`·`PREVIOUSLY_CAPTURED`·`ORDER_ALREADY_AUTHORIZED`는 작업이 일어난 뒤의 4xx다 | 각 n=1·1·2 | 확인(n 작음) |
| PayPal이 보낸 400·422 주문 생성 거절은 `details[0].issue`가 있다(A5) | 42/42, 다중 위반 400 n=1 | 확인(Sandbox). 422 다중 위반은 미측정 |
| 모의 헤더로 `AUTHORIZATION_EXPIRED` 거절은 만들 수 있으나 authorization을 만료시키지 않는다 | n=2 | 확인(Sandbox 모의) |
| 실제 만료된 authorization의 capture·조회 모양 | 관측 불가(29일 대기 안 함) | **미확인** |
| 만료·void 시 `PAYMENT.AUTHORIZATION.VOIDED`가 온다 | void 3회 중 2회 6~7초 도착, 1회 244초 미도착 | void는 확인(n=3, 신뢰성은 낮음). **만료는 문서 주장만, 미확인** |
| 만료 전용 webhook 이벤트가 있다 | 이벤트 유형 205개에 없음, 문서도 `VOIDED`에 합침 | 확인(없음) |
| webhook 순서가 보장되지 않는다 | 두 실행에서 `CAPTURE.COMPLETED`와 `CREATED`의 순서가 반대 | 확인(n=2) |
| 도착 `AUTHORIZATION.CREATED`의 `status`는 도착 시점 상태다 | n=2 모두 `CAPTURED` | 확인(n=2) |
| `PAYMENT.AUTHORIZATION.VOIDED` 처리는 수신 기록만 하고 상태를 바꾸지 않는다 | 실제 본문 단위 테스트 + 변이 M7 | 확인(로컬 테스트) |
| 만료 거절을 조회 만료 시각 경과로 좁혀도 살아 있는 authorization을 종결하지 않는다 | 단위 테스트 + 변이 M1·M6, 되돌리기 훈련 | 확인(로컬 테스트). 실제 만료 모양은 가정 |
| 운영에서 이 규칙들이 틀릴 빈도 | 측정 못 함 | 가정 |
| Live에서도 같은 응답이다 | Sandbox만 호출 | 미확인 |
| `./gradlew clean check` 통과 | 아래 PR 본문 | PR 본문에 결과를 적는다 |

## 하지 않은 것

- 실제 29일 만료와 honor 기간 이후 capture. 만료 거절의 실제 본문과 만료 뒤 조회 모양.
- authorize 거절(구매자 쪽 거절, `INSTRUMENT_DECLINED`): Sandbox로 만들 수 없다고 판단해 근거가 없다(ADR-0079 그대로).
- 429, 실제 `DUPLICATE_INVOICE_ID`(이 계정에서는 중복 `invoice_id`가 수락된다), 422 다중 위반 순서.
- Live 환경 호출.
- webhook으로 상태를 바꾸는 처리, 복구를 깨우는 처리. 이득을 측정할 근거가 없다.
