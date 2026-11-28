# ADR-0115: 분쟁 중에는 분쟁 금액만큼 판매자 송금을 보류하고 판매자 패소일 때만 돌려준 금액을 차감하며, 30일 넘은 결과 불명 환불은 운영자가 PayPal 증거로 닫고, 취소 반복도 재checkout 쿨다운에 넣는다

- 상태: Accepted(사용자 지시 "남은 돈·운영 공백을 닫는다, 스스로 고른다"에 따른 에이전트 결정. 사용자 승인 여부는 확인하지 못함)
- 날짜: 2026-11-29
- 관련 결정: [ADR-0111](ADR-0111-provider-originated-refunds-and-disputes.md)(provider 발 환불·분쟁), [ADR-0112](ADR-0112-seller-suspension-stops-sales-and-holds-payouts.md)(단일 보류 계약 `PayoutHoldCheck`), [ADR-0108](ADR-0108-paypal-request-id-reuse-limit.md)(환불 30일 재전송 상한), [ADR-0113](ADR-0113-buyer-checkout-hold-limits.md)(Buyer checkout 보유 상한), [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md)(보류금 먼저 차감), [ADR-0092](ADR-0092-payout-operator-person-identity-and-maker-checker.md)(지급 운영자 계정·MFA)
- 근거: 이슈 [#757](https://github.com/Neo1228/ATLAS/issues/757), 회귀 시험 `ProviderOriginatedMoneyEventIntegrationTest`(분쟁 5건), `ExpiredRefundOperatorIntegrationTest`(4건), `CheckoutBuyerHoldLimitIntegrationTest`(취소 반복), D-196

## 1. 분쟁: 보류와 판매자 패소 차감

### 1.1 문제

`CUSTOMER.DISPUTE.*`는 `payment_provider_adjustment`에 `REVIEW` 행 하나만 남겼다(ADR-0111 결정 6). 그동안 판매자 송금은 그대로 나갔다. 수정 전 코드에서 분쟁이 열린 판매자의 몫 전부를 지급 요청해도 보류 사유가 비어 있었고(`openDisputeHoldsPayout...` 실패), 판매자 패소로 끝난 분쟁은 원장을 바꾸지 않았다(`lostDispute...` 실패, 상태 `REVIEW`).

### 1.2 PayPal 문서로 확인한 사실

| 문장 | 출처 |
|---|---|
| "PayPal holds the disputed payment until resolution." / "Creates a case for the dispute and holds the disputed payment." | [PayPal disputes overview](https://developer.paypal.com/disputes/overview) |
| "If resolved in the buyer's favor, PayPal refunds the transaction amount." / "If resolved in the merchant's favor, PayPal releases the hold on the transaction amount." | 같은 문서 |
| `dispute_outcome.outcome_code`: `RESOLVED_BUYER_FAVOUR`("resolved in the customer's favor"), `RESOLVED_SELLER_FAVOUR`, `RESOLVED_WITH_PAYOUT`("PayPal provided the merchant or customer with protection"), `CANCELED_BY_BUYER`, `NONE`, 폐기 예정 `ACCEPTED`·`DENIED` | [customer_disputes_v1.json](https://raw.githubusercontent.com/paypal/paypal-rest-api-specifications/main/openapi/customer_disputes_v1.json) |
| `dispute_outcome.amount_refunded`: "The amount that either the merchant or PayPal refunds the customer." | 같은 명세 |
| 분쟁 상태: `OPEN`, `WAITING_FOR_BUYER_RESPONSE`, `WAITING_FOR_SELLER_RESPONSE`, `UNDER_REVIEW`, `RESOLVED`, `OTHER` | 같은 명세 |

판단: PayPal이 분쟁 금액을 플랫폼 계정에 묶어 두는 동안 ATLAS가 판매자에게 그 몫까지 송금하면, 구매자 승소 때 차감할 판매자 몫이 남지 않고 손실이 플랫폼으로 넘어간다. 그래서 **보류해야 한다**. 판매자 패소 결과 코드가 하나(`RESOLVED_BUYER_FAVOUR`)로 정해져 있으므로 ADR-0111이 기각한 "결과 코드만으로는 차감 여부를 정할 수 없다"는 `RESOLVED_WITH_PAYOUT`을 차감 대상에서 빼면 풀린다. 분쟁 패소 뒤 PayPal이 capture 역전 사건을 반드시 보내는지는 문서에서 찾지 못했다. 그래서 패소 차감과 역전 사건이 둘 다 와도 같은 돈을 두 번 빼지 않는 규칙이 필요하다(결정 4).

### 1.3 결정

1. **분쟁 행의 상태를 늘린다(V18).** 진행 중인 분쟁은 `HOLD`, 차감 없이 끝난 분쟁은 `CLOSED`다. 판매자 패소면 같은 행이 `POSTING → POSTED`로 가며 기존 환불 분개(`source_refund_id = adjustment_id`, V7 균형·append-only)를 쓴다. `HOLD`·`CLOSED`는 분쟁에만, `HOLD`는 결제 시도와 보류 원화가 있을 때만 허용하는 CHECK를 둔다. V16이 `REVIEW`로 남긴 진행 중 분쟁은 결제 시도 원화 전부를 보류하는 `HOLD`로 옮긴다.
2. **보류 원화는 분쟁 금액을 capture 비율로 올림한 값**이고 capture 남은 원화를 넘지 않는다. 금액이나 통화를 읽지 못하면 남은 원화 전부를 보류한다. 분쟁 중 갱신 사건은 보류 원화를 다시 계산한다. 끝난 분쟁(`POSTING`·`POSTED`·`CLOSED`·`REVIEW`)은 늦게 온 사건이 되살리지 못하고 마지막 provider 상태만 남긴다.
3. **보류는 단일 계약 위의 빈 하나다.** `DisputePayoutHold implements PayoutHoldCheck`(application)가 `OrderRefundService.disputeHoldWon(sellerId)`로 그 판매자의 진행 중 분쟁 원화 합을 구하고, `SettlementPayoutService.summary`의 지급 가능액(미확정 지급과 환불 예약을 뺀 값) + 보류금이 그 합보다 작으면 `OPEN_DISPUTE`를 돌려준다. 송금 worker는 바뀌지 않는다(ADR-0112 결정 5). "분쟁 금액만큼"의 뜻은 이렇다: 분쟁 금액이 원장에 남는 한 다른 몫의 송금은 막지 않는다. 여러 판매자 checkout의 분쟁은 누구 몫인지 근거가 없어 담긴 판매자 모두에게 분쟁 금액 전부를 센다.
4. **판매자 패소만 차감한다.** `RESOLVED_BUYER_FAVOUR`이면 `amount_refunded`(통화가 결제 시도와 같아야 함)에서 그 결제 시도에 이미 원장에 넣은 capture 역전을 빼고 capture 남은 금액으로 자른 센트를 차감한다. 원화 환산은 ADR-0111 결정 3과 같다(남은 전부면 남은 원화 전부, 일부면 내림). 0 이하이면 `CLOSED`. 차감이 끝난 분쟁이 있는 결제 시도에 뒤늦게 온 역전은 같은 돈일 수 있어 `REVIEW`(`DISPUTE_ALREADY_DEBITED`)로 보낸다. `RESOLVED_SELLER_FAVOUR`·`RESOLVED_WITH_PAYOUT`·`CANCELED_BY_BUYER`·`NONE`·`DENIED`는 `CLOSED`, 결과 코드가 없거나 `ACCEPTED`(누가 졌는지 문서가 말하지 않음)면 `REVIEW`(`DISPUTE_OUTCOME_UNKNOWN`), 패소인데 금액·통화를 못 읽으면 `REVIEW`(`DISPUTE_LOSS_AMOUNT_UNKNOWN`)다. 이 셋 모두 보류는 풀린다.
5. **capture 남은 금액의 합(`refundedTotalsForAttempt`)에 차감한 분쟁을 넣는다.** 우리 환불과 대시보드 환불이 패소 분쟁 뒤 남은 capture를 넘지 않는다.
6. **웹훅 순서와 무관하다.** PayPal은 웹훅 순서를 보장하지 않는다. 같은 capture에서 (A) 역전 → 분쟁 생성 → 패소, (B) 분쟁 생성 → 패소 → 역전, (C) 분쟁 생성 → 역전 → 패소 세 순서 모두 판매자 차감은 한 번이다(`disputeLossAndReversalOfTheSameMoneyDebitOnceInEveryDeliveryOrder`, 일부 금액이라 capture 남은 금액 검사가 막아 주지 않는 경우). (A)·(C)는 결정 4의 역전 빼기가, (B)는 차감 뒤 역전 검토가 막는다.
7. 분쟁 사건은 결제 시도 행을 먼저 잠그고(환불·역전 사건과 같은 순서) adjustment 행을 잠근다. 웹훅 재시도 대기열은 분쟁 결과 필드를 싣지 않으므로 분쟁 경로는 자원 id가 없을 때 말고는 재시도로 돌리지 않는다.

### 1.4 대안과 기각 이유

| 안 | 근거 | 결정 |
|---|---|---|
| 지금처럼 보류 없이 역전 사건만 기다림(ADR-0111) | PayPal 문서가 분쟁 금액을 묶는다고 밝힌다. 그 사이 송금된 몫은 패소 때 회수할 곳이 없다 | ✗ 기각 |
| 분쟁이 열린 판매자의 송금을 전부 보류 | 분쟁 1건(예: 1만 원)이 판매자의 다른 정산금 전체를 막는다. 원장에 분쟁 금액이 남는지만 보면 같은 위험을 막는다 | ✗ 기각 |
| 분쟁 생성 때 분쟁 금액을 `SELLER_RESERVE`로 옮기는 분개 | 판매자 승소 때 되돌리는 분개가 하나 더 필요하고, V7 append-only 원장에 분쟁마다 두 쌍이 쌓인다. 보류 계약이 같은 일을 분개 없이 한다 | ✗ 기각 |
| 결과 코드를 보지 않고 `amount_refunded`가 있으면 차감 | `amount_refunded`는 "merchant or PayPal"이 돌려준 금액이다. `RESOLVED_WITH_PAYOUT`이면 PayPal 돈이다 | ✗ 기각 |
| 패소 차감 없이 역전 사건만 기다림 | 패소 뒤 역전이 꼭 온다는 문장을 찾지 못했다(ADR-0111 재검토 조건). 오지 않으면 판매자 몫이 그대로 남는다 | ✗ 기각 |
| 위 1~7 | 회귀 시험 6건이 수정 전 실패·수정 뒤 통과, 아래 변이 6건 모두 잡힘 | ✓ 채택 |

### 1.5 변이 시험

| 없앤 것 | 결과 |
|---|---|
| 차감한 분쟁 뒤 역전을 검토로 보내는 검사 | ✓ 잡힘. 일부 금액 분쟁에서 같은 돈이 두 번 빠짐(4,993원 두 건, "분쟁 생성 → 패소 → 역전" 순서도 같음) |
| 패소 차감에서 이미 넣은 역전을 빼는 식 | ✓ 잡힘. 기대 375원에 525원, 그리고 "역전 → 분쟁 생성 → 패소" 순서에서 같은 돈이 두 번 빠짐(4,993원 두 건) |
| 보류 판정이 미확정 지급을 뺀 지급 가능액을 씀 | ✓ 잡힘. 몫 전부를 지급 요청해도 보류 없음 |
| 진행 중(`HOLD`)일 때만 상태를 바꾸는 조건 | ✓ 잡힘. 끝난 분쟁이 다시 `HOLD`가 되고, 패소 결과 재전송이 한 번 더 차감(1만 원 두 건) |
| `RESOLVED_WITH_PAYOUT`을 무차감 결과에서 뺌 | ✓ 잡힘. `CLOSED` 대신 `REVIEW` |
| 패소 코드를 `RESOLVED_WITH_PAYOUT`으로 바꿈 | ✓ 잡힘. 패소 분쟁이 차감되지 않음 |

## 2. 30일 넘은 결과 불명 환불의 운영자 종료

### 2.1 문제

ADR-0108 뒤로 첫 요청 뒤 30일이 지난 결과 불명 환불은 PayPal에 다시 보내지 않고 복구 job이 `NEEDS_REVIEW`가 된다. 그 환불을 끝낼 경로가 없었다. 정산 환불 예약이 남아 판매자 지급 가능액을 계속 줄이고, 주문 환불 상태가 끝나지 않는다. 운영 가이드는 "운영자 API는 없다"고 적었다.

### 2.2 결정

1. `POST /api/internal/payment/refunds/{refundOperationId}/resolve-expired`. 본문은 `{"outcome":"COMPLETED","providerRefundId":"..."}` 또는 `{"outcome":"FAILED","providerRefundNotFound":true}`다. PayPal 대시보드에서 그 capture의 환불을 찾았으면 refund id를, 없으면 '없음'을 증거로 낸다. 증거가 없거나 결과와 어긋나면 400이다.
2. **인증은 지급 운영자 경계를 그대로 쓴다.** `SettlementPayoutOperatorSecurityConfiguration`의 경로 목록에 더해 계정 JWT, `payout_operator_account` 권한, MFA 세션 검사를 받는다(ADR-0092). 같은 `atlas.settlement.payout.operator.enabled`로 켜지므로 경계 없이 경로만 열리는 구성은 없다. 공용 Basic 로그인(결제 복구·계정 정지)은 사람 식별과 MFA가 없어 기각했다.
3. **대상은 정말 멈춘 환불뿐이다.** 끝나지 않은 작업(PENDING·UNKNOWN)이고 첫 요청 뒤 30일이 지나야 한다. 30일 판정은 PayPal 어댑터의 `REFUND_REQUEST_ID_REUSE_LIMIT` 하나를 `PaymentProvider.refundRetryExpired`로 노출해 재전송 거절과 같이 쓴다. 그 밖은 409다.
4. **같은 돈을 두 번 세지 않는다.** refund id가 다른 환불 작업이나 provider 발 환불·역전(`payment_provider_adjustment`)에 이미 있으면 409다. 이미 refund id를 가진 작업은 같은 id의 COMPLETED만 받는다.
5. **원장은 기존 경로로 맞춘다.** 결과를 기존 `updateResult`로 쓰고 복구 job을 APPLY 단계로 옮긴 뒤, `OrderRefundService.reconcilePayPalRefund`가 웹훅·복구와 같은 마무리를 한다. COMPLETED는 `settlement.recordRefund`로 환불 분개를 남기고, FAILED는 주문 환불을 실패로 두고 `releaseRefundReservation`으로 예약을 푼다. 두 번째 단계가 실패해도 job이 APPLY에 남아 복구 worker가 마무리한다.
6. **감사 행 하나.** 같은 트랜잭션에서 `domain_audit_event`에 `PAYMENT_REFUND_RESOLVED_BY_OPERATOR`(actor = 운영자 계정 id, payload `refund_operation_id`·`attempt_id`·`outcome`·`evidence`(refund id 또는 `NOT_FOUND`)·`amount_won`)를 남긴다. 자유 텍스트는 넣지 않는다(V9 규약). 같은 결과·증거의 반복은 200으로 다시 돌려주고 다시 반영하지 않으며, 다른 결과의 두 번째 표시는 409다.

### 2.3 대안과 기각 이유

| 안 | 근거 | 결정 |
|---|---|---|
| 운영자 SQL로 수동 정리(현행) | 정산 예약 해제와 분개를 손으로 맞춰야 하고 감사가 없다 | ✗ 기각 |
| 공용 Basic 운영자 경계(결제 복구) | 사람 식별·MFA가 없다. 돈을 움직이는 다른 운영자 조치(지급)는 계정·MFA 경계다 | ✗ 기각 |
| maker-checker 승인 두 단계 | 대기 조치 표(스키마)가 필요하다. 같은 성격의 `resolve-provider-review`도 한 사람이 증거로 닫는다 | ✗ 기각 |
| 30일 전에도 운영자 종료 허용 | 30일 안에는 같은 키 재전송이 PayPal 멱등으로 결과를 정한다(ADR-0108). 사람 판단이 자동 경로와 엇갈릴 수 있다 | ✗ 기각 |
| 위 1~6 | 시험 4건이 수정 전 404로 실패·수정 뒤 통과, 변이 2건 잡힘 | ✓ 채택 |

### 2.4 변이 시험

| 없앤 것 | 결과 |
|---|---|
| 30일 검사 | ✓ 잡힘. 30일 안 환불이 409 대신 200 |
| refund id 중복 검사 | ✓ 잡힘. provider 발 환불에 있는 id가 409 대신 200(판매자 이중 차감) |

## 3. 취소·재checkout 반복

### 3.1 문제

ADR-0113 결정 6의 SKU 재checkout 쿨다운은 만료(결제하지 않고 window를 넘긴 hold)만 셌다. 재고 5개 SKU를 통째로 잡고 window(10분) 끝 직전에 취소한 뒤 다시 checkout하면 쿨다운 없이 다시 잡혔다. 회귀 시험 `buyerWhoCancelsWholeStockHoldsJustBeforeTheWindowEndsCannotLoopThem`(운영 기본값, 9분 59초 보유 뒤 취소를 4번 되풀이)은 수정 전 코드에서 한 번도 거절되지 않았다.

### 3.2 결정

1. **취소한 hold의 보유 시간 합으로 막는다.** 지난 쿨다운 구간(`expired-hold-cooldown`, 기본 30분) 안에 Buyer가 취소한 checkout들이 이 cart의 SKU를 잡고 있던 시간(취소 시각 − `created_at`)의 합이 `atlas.checkout-buyer-limit.cancelled-hold-budget`(기본 `PT5M`) 이상이면 409 `CONFLICT`로 거절한다. 같은 cart 잠금 아래, 재고·쿠폰을 바꾸기 전에 확인하고, 같은 Idempotency-Key 재시도는 그보다 먼저 기존 checkout을 돌려준다.
2. **Buyer 취소만 센다.** `CANCELLED`는 Buyer 취소 요청으로만 생긴다(만료는 `EXPIRED`, 결제 실패는 `FAILED`, Seller 정지는 checkout을 취소하지 않음, `CheckoutPaymentServiceImpl`의 상태 기록 전부를 확인). 취소 시각은 `CANCEL_PENDING`을 거쳤으면 `cancellation_requested_at`, 곧바로 취소됐으면 `updated_at`이다. 스키마는 바꾸지 않는다.
3. **구간은 만료 쿨다운과 같은 값을 쓴다.** 한 SKU 쿨다운에 계기가 둘(만료, 취소 시간 합)이다. `expired-hold-cooldown=PT0S`면 둘 다 꺼진다. `cancelled-hold-budget=PT0S`면 취소 한 번에 쿨다운이 걸린다. 기본값은 `CartCheckoutServiceImpl` 생성자의 `@Value` 한 곳이고(ADR-0113 결정 5), Compose는 `ATLAS_CHECKOUT_BUYER_LIMIT_CANCELLED_HOLD_BUDGET`로 넘긴다.

### 3.3 안 비교

한 Buyer가 재고 전부를 1~10분 단위로 잡고 취소하거나 만료시키며, 허용될 때마다 다시 잡는 경우를 모두 세어(window 10분, 구간 30분) 가장 오래 잡는 시간을 구했다.

| 안 | 40분 중 최대 보유 | 120분 중 | 정상 "취소 뒤 구매" | 결정 |
|---|---|---|---|---|
| 수정 전 | 40분 | 120분 | 허용 | ✗ |
| (a) 취소 한 번에 30분 쿨다운 | 10분 | 30분 | **막힘**(마음을 바꿔 바로 다시 사는 Buyer가 30분 기다림) | ✗ 기각 |
| (b) 30분에 취소 2회면 거절 | 20분 | 60분 | 재checkout 1번만 | ✗ 기각 |
| (b) 30분에 취소 3회면 거절 | 30분 | — | 재checkout 2번 | ✗ 기각 |
| (c) 보유 시간 합 2분 | 11분 | 33분 | 고민 시간이 짧아 거의 막힘 | ✗ 기각 |
| **(c) 보유 시간 합 5분** | **약 15분**(5분 바로 아래 + window 한 번) | **42분** | 합이 5분 미만이면 몇 번이든 허용 | ✓ 채택 |
| (c) 보유 시간 합 10분 | 19분 | — | | ✗ 기각(만료 쿨다운의 10/40보다 두 배 가깝다) |

채택안의 한도는 만료 쿨다운(40분 중 10분)과 같은 크기다. 횟수 상한(b)은 짧게 여러 번 바꾸는 정상 Buyer와 window 끝까지 잡는 Buyer를 구분하지 못한다. 보유 시간은 독점의 실제 비용(다른 Buyer가 사지 못한 시간)을 그대로 잰다.

### 3.4 시험

- 수정 전: 취소 반복 시험 실패(4번 다 다시 잡힘), 정상 흐름 시험 통과. 수정 뒤: 두 시험 모두 통과(클래스 5건). 반복 시험은 거절 중에 다른 Buyer가 재고 5개를 모두 사고, 다른 SKU는 막히지 않으며, 같은 key 재시도는 409가 아니라 기존 `CANCELLED` checkout을 받고, 31분 뒤에는 다시 살 수 있음을 함께 본다.
- 정상 흐름(`buyerWhoQuicklyCancelsAndChecksOutTheSameSkuAgainCanStillPay`): 2분 보유 뒤 수량을 바꿔 두 번 취소하고 다시 checkout한 5개를 PayPal 승인까지 마쳐 `PAID`가 된다.
- 변이: 새 검사 호출을 지우면 반복 시험이 실패한다.
- k6 하네스(`buyer-checkout.js`, `coupon-redemption.js`)는 취소를 부르지 않아 영향이 없다.

## 결과

- 분쟁이 열리면 그 판매자는 분쟁 원화를 원장에 남기는 한도까지만 자동 송금된다. 보류된 지급은 `last_error_code = 'OPEN_DISPUTE'`, 지표 `payout.dispatch.outcomes{result=held}`로 보이고 분쟁이 끝나면 다음 worker 시도에 나간다. 시도 한도(`max-attempts`)를 넘기면 ADR-0112와 같이 `NEEDS_REVIEW`가 된다.
- 판매자 패소 분쟁은 돌려준 금액만큼 한 번 차감된다. 판매자 승소·PayPal 보호·구매자 취소는 원장을 바꾸지 않는다.
- 송금 worker는 지급마다 진행 중 분쟁 전체(부분 인덱스 `payment_provider_adjustment_dispute_hold`)를 읽고 분쟁마다 주문을 한 번 찾는다. 분쟁이 수백 건을 넘으면 판매자별로 거르는 질의가 필요하다(재검토 조건). 성능은 측정하지 않았다.
- 30일 넘은 결과 불명 환불을 운영자가 증거와 함께 닫고, 정산 예약과 주문 환불 상태가 같은 경로로 끝난다.
- 취소한 hold를 합 5분 넘게 잡았던 Buyer는 같은 SKU를 30분 동안 다시 잡지 못한다. PayPal 화면에서 5분 넘게 머문 뒤 취소한 Buyer도 여기에 걸린다. 만료시킨 Buyer와 같은 대우다.

## 롤백

- 코드: 이전 jar로 돌아가면 분쟁은 예전처럼 `REVIEW`로만 쌓이고 보류·차감이 없다. 이전 코드는 `HOLD`·`CLOSED` 행을 `ProviderAdjustmentStatus.valueOf`로 읽지 못하므로, 되돌리기 전에 `UPDATE payment_provider_adjustment SET status = 'REVIEW', review_reason = 'DISPUTE_NEEDS_OPERATOR' WHERE status IN ('HOLD', 'CLOSED')`를 먼저 돌린다. 이미 남긴 패소 차감 분개는 append-only라 지우지 않는다.
- V18의 CHECK는 새 값을 더하기만 한다. 운영자 환불 API는 `atlas.settlement.payout.operator.enabled=false`로 닫힌다.
- 취소 쿨다운은 `ATLAS_CHECKOUT_BUYER_LIMIT_CANCELLED_HOLD_BUDGET`를 크게(예: `PT24H`) 두면 사실상 꺼진다. 만료 쿨다운까지 끄려면 `ATLAS_CHECKOUT_BUYER_LIMIT_EXPIRED_HOLD_COOLDOWN=PT0S`.

## 재검토 조건

- 판매자 패소 분쟁 뒤 같은 capture의 역전이 와서 `DISPUTE_ALREADY_DEBITED` 검토가 한 달에 1건을 넘으면: 실제 payload로 역전과 분쟁을 잇는 필드를 찾아 자동 대조한다.
- 차지백에서 판매자가 이겨 PayPal이 역전했던 금액을 돌려준 사례가 확인되면: 지금은 되돌리는 분개가 없으므로(역전으로 빠진 몫이 그대로 빠져 있음) 재입금 사건 처리를 정한다.
- 진행 중 분쟁이 100건을 넘거나 송금 worker 한 시도가 1초를 넘으면: 보류 질의를 판매자별로 거르도록 판매자 열을 둔다.
- 30일 넘은 환불 운영자 종료가 한 달에 1건을 넘으면: ADR-0108의 작업 식별자(`custom_id`) 안을 앞당긴다.
- 취소 쿨다운 409 민원이 생기거나 409(취소 쿨다운)가 하루 checkout 시도의 0.5%를 넘으면: 합 상한을 늘리거나, 결제 화면 체류를 보유 시간에서 빼는 안을 잰다.
