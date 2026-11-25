# ADR-0090: ADR-0079의 확정 거절 가정 A2~A5를 실제 Sandbox 관측으로 확인하고 만료 거절 종결에 만료 시각 조건을 더한다

- 상태: 채택(제안 PR, 병합은 통합 담당). Sandbox 전용 결정이다. Live 전환 전 아래 미확인 항목을 다시 확인한다.
- 날짜: 2026-11-25
- 관련: [ADR-0079](ADR-0079-paypal-definitive-rejection-and-authorization-expiry.md), [ADR-0030](ADR-0030-paypal-currency-boundary.md), [ADR-0026](ADR-0026-paypal-sandbox-payments.md), [EXP-094](../../03-experiments/EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md), [EXP-081](../../03-experiments/EXP-081-paypal-double-vs-sandbox.md)
- 추적: [Q-004](../../01-requirements/requirements-index.md), [D-160](../../00-governance/decision-log.md#d-160), [Issue #650](https://github.com/Neo1228/ATLAS/issues/650)

## 문제

ADR-0079는 PayPal 거절 세 가지를 재시도 없이 종결하는 근거를 실제 Sandbox 관측 n=1씩과 일반 규약에 두었고, 가정 A2(주문 생성 400·422는 주문을 만들지 않았다)와 A3(만료 거절 규칙)는 확인하지 못했다고 적었다. 규칙이 틀리면 돈이 잡힌 결제를 거절·void로 끝낸다. authorization void·만료 webhook은 본문을 본 적이 없어 처리하지 않았다.

## 결정

실제 Sandbox 관측([EXP-094](../../03-experiments/EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md), 사전 등록 후 실행) 결과로 ADR-0079 가정을 아래처럼 확인·수정한다.

| 가정 | 관측 | 결정 |
| --- | --- | --- |
| A2 주문 생성 400·422(+이슈)는 주문이 없다 | 변이 14개·42건 모두 거절 본문에 주문 ID 없음, 같은 Request-Id 교정 호출이 새 주문 42/42, 반례 0. 판별기 반응 확인. PayPal 쪽 부재 증명은 불가(목록 API 없음) | **유지.** 근거 n=1이 n=14로 늘었다. 상한 3/14(약 21%)로만 말한다 |
| A3(voided) void된 authorization의 capture 422 `AUTHORIZATION_VOIDED`는 자금 미확보 | authorization 8개·16건 모두 422, 조회 `VOIDED`·capture 0. **단독 반례 1건**: 부분 capture 뒤 void하면 새 Request-Id capture가 422 `AUTHORIZATION_VOIDED`인데 주문에 capture가 있다 | **유지하되 "주문 조회에 capture 없음" 조건이 필수 방어선임을 명시.** 코드는 이미 그렇게 하며 테스트로 고정했다 |
| A3(expired) 만료 거절이면 조회 `CREATED`도 종결 | 실제 만료는 관측하지 못함. 모의 헤더는 `AUTHORIZATION_EXPIRED` 거절을 만들되 authorization을 `CREATED`·만료 29일 전으로 두고 이후 capture가 성공(n=2) | **수정:** 만료 거절이고 조회가 `CREATED`이면 조회의 `expiration_time`이 현재 시각 이전일 때만 `VOIDED`로 종결한다. 조회가 `VOIDED`이면 종전대로 종결. 시각이 없거나 미래이면 UNKNOWN 유지 |
| A4 void 422 `PREVIOUSLY_VOIDED`는 이미 void됨 | 16/16, 조회 `VOIDED` 8/8, 같은 Request-Id 재전송은 204 8/8. `PREVIOUSLY_CAPTURED`는 별개(n=1, capture됨) | **유지** |
| A5 오류 본문에 `details[0].issue`가 있으면 PayPal이 보낸 것 | 주문 생성 거절 42/42. 이슈 없는 PayPal 4xx(401 토큰 오류) 확인. 다중 위반 400은 `details`에 이슈 2개 | **유지** |
| A1 honor 3일 | 이번에 새로 본 것 없음(`expiration_time` 생성+696.00시간 8/8만 재확인) | 변경 없음 |

### webhook

- 실제 Sandbox에서 void하면 `PAYMENT.AUTHORIZATION.VOIDED`가 온다: 3회 중 2회 6~7초, 1회는 244초 안에 오지 않았다. 만료 전용 이벤트 유형은 없고 문서는 `VOIDED`가 만료도 포함한다고 쓴다(관측 못 함). 이벤트 순서는 보장되지 않았고 본문 `resource.status`는 도착 시점 상태다.
- **처리를 추가하지 않는다.** 현재 코드는 서명 검증 뒤 알 수 없는 유형을 수신 기록만 하고 상태를 바꾸지 않으며(실제 본문 테스트로 고정), 같은 사실은 capture 거절 + 조회가 요청 시점에 확인한다. 이득이 측정되지 않았고 복구를 깨우는 신호로도 3회 중 1회 미도착이라 믿기 어렵다. ADR-0030대로 webhook만으로 상태를 바꾸거나 돈을 움직이지 않는다.
- 파서는 실제 `VOIDED` 본문에서 `eventType`·`resourceId`(authorization ID)·`resourceStatus`·`providerOrderId`를 읽는다(테스트).

### 변경

- `PayPalPaymentProvider`에 `Clock`을 주입하고 `resolveUnusableAuthorization`의 만료 분기에 `hasExpired(expirationTime)`을 더했다(약 10줄).
- 테스트 자원 `responses.json`에 실제 본문을 더하고(쓰이는 것만) 재생 테스트 2종(`PayPalRejectionReplayTest`, `PaymentWebhookProcessorAuthorizationEventTest`)을 더했다. 변이 9개로 실패함을 확인했다([EXP-094](../../03-experiments/EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md#변이-검증-규칙을-깨면-테스트가-실패하는가)).

## 대안 비교

[EXP-094 "결정 대안 비교"](../../03-experiments/EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md#결정-대안-비교-webhook분류)에 표로 있다. 변경 없음(기각: 모의로 위험한 조합이 만들어짐), 테스트만(부족), 만료 분기 삭제(기각), 이슈 허용 목록(기각, 반례가 없다), webhook 상태 변경(기각), webhook 복구 깨우기(보류), 채택은 시각 조건 추가다.

## 가정과 한계

| 번호 | 가정·한계 | 바뀌는 조건 | 되돌리는 법 |
| --- | --- | --- | --- |
| B1 | 실제 만료된 authorization의 조회는 `VOIDED`이거나 과거 `expiration_time`을 가진다 | 만료 뒤 조회가 `expiration_time`을 주지 않거나 새 상태값을 준다(그 경우 종결 못 하고 UNKNOWN, 안전한 방향) | 관측이 생기면 조건을 그 모양으로 바꾼다 |
| B2 | 부분 capture 뒤 void 경로는 운영자가 PayPal에서 직접 부분 capture할 때만 생긴다 | 앱이 부분 capture를 하게 되면 | 조회의 capture 대조가 이미 방어 |
| B3 | 이 Sandbox 계정의 중복 `invoice_id` 허용 설정이 운영에서도 같다 | 계정이 `DUPLICATE_INVOICE_ID`를 강제하면(422 확정이 될 이슈, 주문 생성 거절 규칙이 그대로 적용됨) | 변경 불필요 |
| B4 | webhook 지연·유실은 PayPal 쪽 문제일 수 있고 무료 터널도 영향을 줬다 | 관측을 더하면 | webhook 의존 코드가 없어 영향 없음 |

## 결과와 영향

- 확정 거절 세 규칙은 실제 Sandbox 관측으로 n이 늘었고 반례는 찾지 못했다. 만료 분기는 더 보수적이 되었다(UNKNOWN이 늘 수 있고 돈은 안전).
- 알려진 한계: 실제 만료·honor 이후·Live 미관측, PayPal 쪽 부재 증명 불가, 표본이 작다([EXP-094](../../03-experiments/EXP-094-paypal-sandbox-rejection-rules-and-authorization-webhooks.md#주장과-증거-주장-표)).

## 되돌리기

- `git revert`로 `Clock` 인자와 `hasExpired` 조건이 사라지고 ADR-0079의 `expired && CREATED → VOIDED`로 돌아간다. 마이그레이션·플래그는 없다. 훈련으로 되돌린 코드에서 기존 PayPal 단위 테스트 14건이 통과함을 확인했다(EXP-094).
