# ADR-0079: PayPal 확정 거절은 (호출, 상태, 이슈) 쌍으로만 분류하고 authorization 만료 시각을 기록한다

- 상태: 채택(제안 PR, 병합은 통합 담당). Sandbox 전용 결정이다. Live 전환 전 아래 가정 표를 다시 확인한다.
- 날짜: 2026-11-25
- 관련: [ADR-0026](ADR-0026-paypal-sandbox-payments.md), [ADR-0030](ADR-0030-paypal-currency-boundary.md), [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md), [ADR-0047](ADR-0047-abandoned-approval-deadline.md), [EXP-081](../../03-experiments/EXP-081-paypal-double-vs-sandbox.md), [EXP-086](../../03-experiments/EXP-086-admission-k-ttl-and-authorize-window.md), [EXP-088](../../03-experiments/EXP-088-paypal-definitive-rejection-and-authorization-expiry.md)
- 추적: [Q-004](../../01-requirements/requirements-index.md), [D-147](../../00-governance/decision-log.md#d-147), [Issue #639](https://github.com/Neo1228/ATLAS/issues/639)

## 문제

운영 클라이언트는 PayPal의 모든 HTTP 오류를 장애 예외로 감싸 결제 시도를 결과 불명(UNKNOWN)으로 둔다(EXP-081 "바꾸지 않은 것"). 두 가지가 문제로 남았다.

- **G1.** 요청이 검증에서 거절됐거나 authorization이 이미 void·만료돼 "작업이 일어나지 않았음"이 분명한 응답도 UNKNOWN이라, 복구가 같은 Request-Id로 최대 8회 다시 부르고 운영자 검토로 넘긴다. 이 저장소의 대역으로 재현하면 주문 생성이 422로 거절되는 경우 PayPal 호출이 9회(제출 1회 + 재시도 8회)였다([EXP-088](../../03-experiments/EXP-088-paypal-definitive-rejection-and-authorization-expiry.md)).
- **G2.** 코드는 authorization의 `expiration_time`을 읽지 않고, void·만료된 authorization을 복구가 종결하지 못한다. 재현에서 capture 거절 뒤 주문 조회가 `authorizations[0]=VOIDED`를 주면 `captureResult`에 `VOIDED` 분기가 없어 `IllegalStateException: Provider result VOIDED is invalid for CAPTURE`가 났고, 복구는 예외로 재시도만 반복한다(코드 읽기로는 이후 운영자 검토, 도달은 단정하지 않았다).

## 결정

### G1: 확정으로 분류하는 호출은 아래 세 가지뿐이다

다음을 지킨다: 확정 분류는 **응답이 작업이 일어나지 않았음을 증명하는 경우**에만 하고, 나머지는 UNKNOWN으로 남긴다(ADR-0030의 "불명확한 결과는 UNKNOWN 유지", "CAPTURED는 조회 결과와 일치할 때만").

| 호출 | 거절 | 추가 확인 | 결과 | 근거 |
| --- | --- | --- | --- | --- |
| 주문 생성 | HTTP 400 또는 422, 그리고 PayPal 오류 본문의 `details[0].issue`가 있음 | 없음 | DECLINED(주문·재고는 기존 로직이 FAILED·반환) | EXP-081 Sandbox n=1씩(422 `DECIMALS_NOT_SUPPORTED`, `CURRENCY_NOT_SUPPORTED`, `CANNOT_BE_ZERO_OR_NEGATIVE`, `DECIMAL_PRECISION`; 400 `INVALID_PARAMETER_SYNTAX`, `MISSING_REQUIRED_PARAMETER`). 기록된 응답 재생은 없다 |
| capture | 422 `AUTHORIZATION_VOIDED`(Sandbox n=1) 또는 `AUTHORIZATION_EXPIRED`(OpenAPI 예시만) | 같은 호출 안에서 주문 조회: capture가 없고 authorization이 `VOIDED`(만료 거절이면 `CREATED`도) | VOIDED | EXP-081 23번, EXP-086 [P2]. capture가 조회에 있으면 그 결과(CAPTURED·DECLINED·UNKNOWN)를 따른다 |
| void | 422 `PREVIOUSLY_VOIDED` | 없음 | VOIDED(목표 상태에 이미 도달) | EXP-081 23번(Sandbox n=1) |

위 세 줄 밖은 모두 UNKNOWN이다.

- 401, 403, 404, 429, 5xx, 연결 실패, 본문이 PayPal 형식이 아닌 4xx(프록시 HTML 등).
- `AUTHORIZATION_ALREADY_CAPTURED`, `ORDER_ALREADY_AUTHORIZED`: 다른 요청이 이미 효과를 냈다는 뜻이라 "일어나지 않았다"가 아니다. 복구가 주문 조회로 대조한다(기존 경로).
- `ORDER_NOT_APPROVED`: 구매자 승인 대기이지 거절이 아니다.
- authorize 거절 전부: Sandbox로 구매자 쪽 결과(카드 거절, `INSTRUMENT_DECLINED`)를 만들 수 없어 근거가 없다.
- 거절 본문은 capture에서 "자금이 잡히지 않았다"를 단독으로 증명하지 않는다. 조회가 어긋나면(예: 거절은 void라는데 조회는 `CREATED`) UNKNOWN을 유지하고 복구가 재시도한다.

구현 위치: `PayPalRestClient`가 4xx를 `PayPalRejectedException`(상태, 이슈)으로 던지고, 이 예외는 기존 장애 예외의 하위 형식이다. 분류는 `PayPalPaymentProvider`가 위 세 호출에서만 한다. **분류하지 않는 호출부는 코드를 바꾸지 않아도 이전처럼 UNKNOWN으로 처리된다**(기본값이 안전한 쪽이다). 상태 기계는 `captureResult`에 `VOIDED`(상태 VOIDED, 단계 VOID, authorization 비활성) 한 줄을 더했다. 이 한 줄은 4xx 분류 없이 "복구 중 주문 조회가 VOIDED를 말해 주는" 경우의 `IllegalStateException`도 없앤다.

### G2: 만료 시각을 기록해 운영자에게 보이고, 만료·void는 capture 시점에 종결한다

- **기록.** V3 마이그레이션이 `payment_attempt.authorization_expires_at timestamptz`(NULL 허용)를 더한다. authorize 응답과 주문 조회의 `authorization.expiration_time`(RFC 3339)을 읽어 저장한다. 읽지 못하면 NULL이고 결제를 막지 않는다. **상태 전이에는 쓰지 않는다.**
- **노출.** 운영자 검토 목록(`PaymentRecoveryResponse.ReviewItem`)에 `authorizationExpiresAt`을 더했다. EXP-086이 남긴 질문 "NEEDS_REVIEW로 간 시도가 honor 기간을 넘겼는가"에 운영자가 값으로 답할 수 있다.
- **종결.** 만료·void된 authorization은 위 G1 capture 줄로 종결한다. 별도 만료 로직이 아니라 같은 규칙이다.
- **운영 기준(코드로 강제하지 않음).** 목록에서 `authorizationExpiresAt`이 있는 시도는 `authorizationExpiresAt - 26일`(= 생성 + 3일 honor, 가정 A1)까지 capture를 확정하거나 취소한다. 복구 window 24시간은 그 3일의 3분의 1이라 운영자 몫은 남은 2일이다.

### 하지 않는 것

| 하지 않은 것 | 이유 | 바꿀 조건 |
| --- | --- | --- |
| reauthorize | 새 authorization ID와 새 Request-Id, 새 honor 기간을 다루는 새 금전 흐름이다. 성공 경로(3일 뒤)를 Sandbox에서 관측하지 못했다 | 운영에서 honor 초과 capture가 실제로 실패한다는 관측(NEEDS_REVIEW 체류 시간 분포)이 생길 때 |
| 자동 void(방치 시) | UNKNOWN 시도를 자동으로 void하면 응답이 유실된 capture를 void로 덮는다. ADR-0047은 deadline 뒤에도 provider 상태를 먼저 보라고 했다. 방치 빈도와 운영자 처리 시간이 측정되지 않았다 | 방치 빈도가 측정돼 운영자 대응으로 감당이 안 될 때. 그때도 order 조회로 capture 없음을 확인한 뒤에만 |
| 만료·void webhook(`PAYMENT.AUTHORIZATION.VOIDED`) 처리 | Sandbox에서 이벤트 본문을 본 적이 없다. 위 종결 경로(capture 거절 + 조회)가 같은 사실을 요청 시점에 확인한다 | 이벤트 본문 관측 뒤. 단 webhook은 순서·중복이 보장되지 않아 상태를 직접 바꾸지 않고 복구를 깨우는 용도로만 |
| 만료 임박 알림(gauge) | 임계값을 정할 발생 빈도 근거가 없고 운영자가 보는 목록은 NEEDS_REVIEW뿐이다. 값은 목록에 보이므로 임계는 운영 정책이다 | 운영 대시보드·알림 정책이 정해질 때 |

## 대안 비교

### G1

| 대안 | 비용 | 위험 | 판정 |
| --- | --- | --- | --- |
| A. 변경 없음(문서화) | 0 | 확정 거절마다 호출 8회 낭비와 24시간 지연, 만료·void된 authorization에서 예외 반복(재현됨) | 기각: 재현된 결함이 있다 |
| B. 4xx 전부 확정 거절 | 작음 | 429, 401, `ALREADY_CAPTURED`가 거절로 처리돼 결제 오거절·이중 처리 | 기각 |
| C. 422만 확정 | 작음 | `AUTHORIZATION_ALREADY_CAPTURED`(이미 capture됨)와 `ORDER_NOT_APPROVED`(대기)가 422라 틀림 | 기각 |
| D. (호출, 상태, 이슈) 쌍 + 주문 조회 중재 | 중간(provider 약 60줄, 예외 1개) | 쌍의 근거가 n=1(EXP-081). 가정 A2~A4 | **채택** |
| E. 거절 뒤 항상 GET으로 확인 후 분류 | 호출 증가 | 주문 생성은 확인할 리소스가 없어 근거가 같다 | capture에만 적용 |

### G2

| 대안 | 비용 | 위험 | 판정 |
| --- | --- | --- | --- |
| A. 문서화만 | 0 | 운영자가 만료·honor를 알 수 없다 | 기각 |
| B. 기록 + 목록 노출 + capture 시점 종결 | V3 1건, 열 1개, DTO 필드 1개 | 마이그레이션 되돌리기 불가(앞으로만) | **채택** |
| C. B + gauge + 임계 알림 | B보다 많다(메트릭 스냅샷, 서비스, worker) | 임계값을 근거 없이 정함 | 보류 |
| D. B + 자동 void·reauthorize | 크다 | 금전 흐름 추가, Sandbox 미관측 | 기각(위 표) |

## 가정 (값, 이유, 바뀌는 조건, 되돌리는 법)

| 번호 | 가정 | 이유 | 바뀌는 조건 | 되돌리는 법 |
| --- | --- | --- | --- | --- |
| A1 | honor 3일 = `expiration_time - 26일`(만료 29일 가정) | 문서 P1·P2(3일, 29일), Sandbox n=3에서 696.00시간. honor는 응답 필드가 아니다 | 만료 길이나 honor 정책이 바뀐 PayPal 안내, Live 응답 | 운영 기준 문장만 고친다(코드 영향 없음) |
| A2 | 주문 생성이 400·422(+이슈)로 거절되면 주문이 만들어지지 않았다 | EXP-081 Sandbox n=1씩, 검증 거절은 리소스를 만들지 않는 규약. 같은 요청 재전송도 같은 거절 | 거절 뒤 주문이 존재하는 사례 | `PayPalPaymentProvider.createOrAuthorize`의 거절 분기 삭제(UNKNOWN으로 복귀) |
| A3 | capture가 `AUTHORIZATION_VOIDED`·`AUTHORIZATION_EXPIRED`로 거절되고 주문 조회에 capture가 없으면 자금은 잡히지 않았다 | EXP-081 23번(Sandbox n=1), OpenAPI 예시. 조회가 capture를 보이면 그쪽을 따르므로 경합이 안전하다. 만료 뒤 조회 모양은 관측하지 못했다(만료 거절이면 `CREATED`도 허용) | 만료된 authorization이 조회에서 capture를 가진 채 남는 관측 | `resolveUnusableAuthorization` 삭제 |
| A4 | void가 `PREVIOUSLY_VOIDED`면 void는 이미 된 것이다 | EXP-081 23번(Sandbox n=1). 안정적인 Request-Id를 쓰므로 정상 경로에서는 재생으로 204가 온다 | 이 이슈가 다른 의미로 쓰이는 응답 | void의 `PREVIOUSLY_VOIDED` 분기 삭제 |
| A5 | PayPal 오류 본문에 `details[0].issue`가 있으면 PayPal이 보낸 것이다 | 프록시 HTML 등은 이슈가 없어 분류에서 제외 | 이슈 없는 PayPal 거절 | 불필요(보수적 방향) |

## 결과와 영향

- 확정 거절은 재시도 없이 DECLINED·VOIDED에 한 번 도달하고 주문 FAILED, 재고 반환이 한 번이다(EXP-088, 로컬 대역, n=1).
- 결과 불명(429, `ALREADY_CAPTURED`, 어긋난 조회 등)은 이전과 같이 같은 Request-Id로 복구한다(통제 테스트 통과).
- API는 운영자 검토 항목에 필드 1개(`authorizationExpiresAt`, 없으면 null)가 늘었다. 결제 응답과 상태 열거는 바뀌지 않았다.
- 알려진 한계: Sandbox 미호출, 만료 뒤 응답 미관측, 구매자 쪽 거절과 authorize 거절은 분류 밖.

## 되돌리기

- 코드: PR을 되돌리면 모든 4xx가 UNKNOWN이 되는 이전 동작이다. A2~A4는 각각 한 분기를 지워 독립적으로 되돌린다.
- DB: V3은 앞으로만 간다. 되돌릴 때 NULL 허용 열이 남아도 무해하다. 지우려면 후속 마이그레이션에서 열을 삭제한다. V1·V2는 수정하지 않았다.
