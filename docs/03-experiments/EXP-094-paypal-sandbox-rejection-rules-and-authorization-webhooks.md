# EXP-094: PayPal 확정 거절 규칙은 실제 Sandbox에서 성립하는가, authorization void·만료는 webhook으로 오는가

- 질문: [ADR-0079](../02-decisions/adr/ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md)는 세 가지 PayPal 거절을 "작업이 일어나지 않았다"는 증거로 보고 확정 분류한다. 근거는 [EXP-081](EXP-081-paypal-double-vs-sandbox.md)의 실제 Sandbox n=1씩이다. 이 규칙은 서로 다른 유발 방법에서도 성립하는가, 규칙을 깨는 반례(4xx인데 작업은 일어난 경우)가 있는가, authorization void·만료는 webhook으로 오는가.
- 상태: **사전 등록 단계**(이 문서의 이 커밋은 어떤 측정도 실행하기 전에 만들었다. 결과 절은 이후 커밋이 채운다).
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
