# ADR-0111: PayPal 쪽에서 시작된 환불·capture 역전은 기존 환불 분개로 판매자에게서 차감하고, 분쟁·capture 거절은 돈을 움직이지 않고 검토로 둔다

- 상태: Accepted(사용자 지시 "핵심 문제를 제대로 고치고 최선안을 스스로 고른다"에 따른 에이전트 결정. 사용자 승인 여부는 확인하지 못함)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0011](ADR-0011-gross-capture-settlement-ledger.md)(capture 총액 원장), [ADR-0032](ADR-0032-paypal-webhook-retry-queue.md)(웹훅 재시도), [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md)(보류금 먼저 차감), [ADR-0100](ADR-0100-settlement-ledger-guards.md)(V7 균형·append-only), [ADR-0104](ADR-0104-seller-balance-checkpoint-and-bounded-statement-reads.md)(V10 잔액 마감)
- 근거: 이슈 [#741](https://github.com/Neo1228/ATLAS/issues/741), `ProviderOriginatedMoneyEventIntegrationTest`(수정 전 main에서 4건 실패, 수정 뒤 4건 통과)

## 문제

웹훅 처리는 우리가 시작한 capture와 환불만 다뤘다. PayPal 대시보드에서 낸 환불은 `payment_refund_operation`에 같은 refund id가 없어서 재시도 대기열로 갔다가 운영자 dead letter로 끝났다. capture 역전(`PAYMENT.CAPTURE.REVERSED`), capture 거절(`PAYMENT.CAPTURE.DENIED`), 분쟁(`CUSTOMER.DISPUTE.*`)은 처리 완료로 표시만 되고 아무 효과가 없었다. 그래서 구매자 돈은 PayPal에서 돌아가는데 판매자 지급 가능액(`SELLER_PAYABLE`)은 줄지 않았다. 보류율과 수수료율 기본값이 0 bps이고 지급 가능액은 capture 때 쌓이므로, 그 차액은 고스란히 플랫폼 손실이 된다.

main(f00412d3)에서 PostgreSQL 18.6과 실제 웹훅 경로(`PayPalWebhookService.handle`)로 재현했다. 대시보드 전액 환불은 환불 분개 0건이었고, 부분 환불 뒤 나머지 역전도 0건이었다. 분쟁은 아무 기록도 남지 않았다.

## PayPal 문서로 확인한 사건의 뜻

| 사건 | 공식 설명 | 자원 | 출처 |
|---|---|---|---|
| `PAYMENT.CAPTURE.REFUNDED` | "A merchant refunds a payment capture." | refund. capture는 `links[rel=up]`의 `/v2/payments/captures/{id}`로만 가리킨다(`capture_id` 필드 없음) | [Webhook event names](https://developer.paypal.com/api/rest/webhooks/event-names/), [payments_payment_v2.json](https://raw.githubusercontent.com/paypal/paypal-rest-api-specifications/main/openapi/payments_payment_v2.json)의 `capture_refunded` 예시와 `refund` 스키마 |
| `PAYMENT.CAPTURE.REVERSED` | "PayPal reverses a payment capture." 관련 메서드는 "Refund captured payment" | 위 문서는 refund 자원으로 안내한다. 명세에 payload 예시는 없다 | 같은 문서 |
| `PAYMENT.CAPTURE.DENIED` | "A payment capture is denied." | capture | 같은 문서 |
| `CUSTOMER.DISPUTE.CREATED/UPDATED/RESOLVED` | 분쟁 생성·갱신·해결 | dispute. `dispute_id`, `dispute_amount`, `disputed_transactions[].seller_transaction_id`. 결과 코드에 `RESOLVED_BUYER_FAVOUR`, `RESOLVED_SELLER_FAVOUR`, `RESOLVED_WITH_PAYOUT`(PayPal 보호로 해결) 등이 있다 | [customer_disputes_v1.json](https://raw.githubusercontent.com/paypal/paypal-rest-api-specifications/main/openapi/customer_disputes_v1.json) |

분쟁 결과 코드만으로는 판매자 계정에서 돈이 빠졌는지 정할 수 없다. `RESOLVED_WITH_PAYOUT`은 PayPal 보호로 끝난 경우다. 그래서 분쟁 사건으로는 돈을 움직이지 않는다. 판매자 계정의 돈이 움직이면 PayPal은 capture 역전이나 환불을 별도 사건으로 보내고, 원장은 그 사건으로 움직인다. 분쟁 결과가 언제나 역전 사건으로 이어진다는 문장은 위 문서에서 찾지 못했다. 그래서 분쟁 행을 검토 대기로 남겨 운영자가 대조하게 한다.

## 결정

1. 새 표 `payment_provider_adjustment`(V16)에 PayPal 쪽에서 시작된 사건을 기록한다. 환불과 역전은 같은 refund id 공간을 쓰므로 provider 자원 id 하나에 한 행만 둔다(부분 유일 인덱스). 이 표는 원장이 아니다. 원장에 넣을 사실과 검토 대기열을 담는다.
2. 우리 operation과 짝이 없는 환불은 다음 순서로 가린다. 우리 환불은 operation을 commit한 뒤에 PayPal을 부른다. 그래서 그 환불의 웹훅이 도착할 때 같은 결제 시도에 refund id 없는 우리 operation(FAILED 제외)이 있으면, 이 사건이 우리 환불일 수 있다. 그때는 재시도로 돌린다. 이어서 refund id로 한 번 더 찾아, 그 사이 id가 저장됐으면 재시도가 기존 짝짓기 경로를 탄다. 둘 다 아니면 외부 환불로 본다. 역전은 PayPal이 시작하므로 이 검사를 하지 않는다.
3. 금액은 결제 시도의 capture 비율로 원화로 바꾼다. capture에서 남은 금액(우리 환불과 이미 잡힌 외부 환불을 뺀 값) 전부면 남은 원화 전부를 쓰고, 일부면 내림한다. 판매자 차감은 실제보다 커지지 않고, 여러 번에 나눠도 마지막 건이 나머지를 맞춘다. 남은 금액을 넘거나 통화가 다르거나 결제 시도가 `CAPTURED`가 아니면 돈을 움직이지 않고 `REVIEW`로 둔다.
4. 원장 반영은 주문 계층(`OrderRefundService.applyProviderAdjustment`)이 웹훅과 같은 트랜잭션에서 한다. 우리 환불과 같은 두 단계를 그대로 쓴다. `settlement.reserveRefund`가 미지급 송금을 취소하고, `settlement.recordRefund`가 `PSP_CLEARING` 대변과 `SELLER_RESERVE`→`SELLER_PAYABLE` 차변을 남긴다. `source_refund_id`는 adjustment id다. 그래서 V7 균형 트리거·append-only 트리거, V10 마감 꼬리 합산, 정산서의 환불 합계가 바뀌지 않고 적용된다. 멱등은 세 겹이다. 웹훅 event id, provider 자원 id 유일 인덱스, `settlement_journal.source_refund_id` 유일과 사실 비교.
5. 결제 시도가 여러 판매자 주문을 담은 checkout이면 provider 금액을 누구에게서 뺄지 정할 근거가 사건에 없다. 그래서 `MULTI_SELLER_CAPTURE` 사유로 `REVIEW`에 둔다.
6. 분쟁과 capture 거절은 `REVIEW`로 기록하고 돈을 움직이지 않는다. 같은 분쟁의 후속 사건은 마지막 provider 상태만 갱신한다. (분쟁 부분은 [ADR-0115](ADR-0115-dispute-payout-hold-expired-refund-operator-and-cancel-cooldown.md) 1절이 대체했다: 진행 중에는 분쟁 금액만큼 송금을 보류하고, `RESOLVED_BUYER_FAVOUR`일 때만 돌려준 금액을 차감한다.)
7. capture를 우리 결제 시도로 찾지 못하는 환불·역전은 이전처럼 재시도 뒤 운영자 dead letter로 보낸다(ADR-0032).

## 대안과 기각 이유

| 안 | 근거 | 결정 |
|---|---|---|
| 외부 환불을 `payment_refund_operation`에 넣고 기존 `reconcilePayPalRefund`로 반영 | 그 경로는 `order_refund_request`(품목·수량)를 전제한다. 외부 환불에는 품목이 없고, 재고 복원이나 반품 상태를 꾸며 내야 한다 | ✗ 기각 |
| 응답이 끊긴 우리 환불과 외부 환불을 `custom_id`로 가르기(환불 요청에 우리 id를 실음) | 공식 `refund` 스키마에 `custom_id`가 있다. 하지만 배포 전에 낸 환불에는 이 값이 없고, 대시보드 환불에 값이 비어 있다는 보장을 문서에서 찾지 못했다. 2번의 순서 논리는 payload에 기대지 않는다 | ✗ 기각 |
| 분쟁 생성 때 판매자 지급을 보류하거나 보류금으로 옮김 | 결과 코드만으로 판매자 차감 여부가 정해지지 않는다(`RESOLVED_WITH_PAYOUT`). 돈을 움직이는 결정은 역전 사건이 확정한다 | ✗ 기각 |
| 여러 판매자 capture의 부분 환불을 배분액 비율로 나눔 | 어느 판매자의 상품이 환불됐는지 사건에 없다. 비율 배분은 다른 판매자에게 손실을 넘길 수 있다 | ✗ 기각(검토로 보냄) |
| 위 1~7 | `ProviderOriginatedMoneyEventIntegrationTest` 4건, 아래 변이 시험 | ✓ 채택 |

## 변이 시험

새 가드를 하나씩 없애고 같은 시험 클래스를 돌렸다.

| 없앤 것 | 결과 |
|---|---|
| id 없는 우리 operation이 있으면 재시도로 돌리는 검사 | ✓ 잡힘. 응답을 잃은 우리 환불이 외부 환불로 1건 기록됨 |
| capture 남은 금액 전부면 남은 원화 전부를 쓰는 규칙 | ✓ 잡힘. 부분 환불과 역전의 합이 capture 원화보다 1원 작음(9,998원) |
| refund 자원의 `links[rel=up]`에서 capture id를 읽는 파싱 | ✓ 잡힘. 환불 분개 0건(main과 같음) |
| 분쟁을 검토 경로로 보내는 분기 | ✓ 잡힘. 분쟁이 금전 경로로 감 |
| provider id 조회 하나만, 또는 유일 인덱스 하나만 | 살아남음. 같은 종류의 중복은 `(kind, provider_resource_id)` 유일 인덱스가, 전액 중복은 남은 금액 검사가 막는다 |
| provider id 조회와 두 유일 인덱스를 모두 | ✓ 잡힘. 같은 부분 환불이 두 번 차감됨(합 6,692원, 기대 9,999원) |

## 결과

- 대시보드 전액 환불은 판매자 몫(`SELLER_PAYABLE`+`SELLER_RESERVE`)에서 capture 원화만큼 한 번 빠진다. 같은 refund id를 두 번 받아도 한 번이다.
- 부분 환불 뒤 나머지 역전의 합은 capture 원화와 같다.
- 분쟁 생성은 원장을 바꾸지 않고 `REVIEW` 행 하나를 남긴다.
- 응답을 잃은 우리 환불의 웹훅은 외부 환불로 기록되지 않는다. 우리 환불이 확정된 뒤 환불 분개는 1건이다.
- 주문 화면의 환불 상태와 환불 요약은 외부 환불을 모른다. 운영 화면과 지표는 이 PR에 넣지 않았다. `REVIEW` 행은 `payment_provider_adjustment_review` 인덱스로 찾는다.

## 롤백

- V16은 새 표 하나만 만든다. 이전 jar로 돌아가면 새 표에 아무도 쓰지 않고, 외부 사건은 예전처럼 dead letter나 무효과 처리로 돌아간다. 이미 남긴 환불 분개는 V7 append-only라 지우지 않는다. 되돌릴 때는 반대 분개를 운영자가 남긴다.

## 재검토 조건

- `REVIEW` 행 중 `MULTI_SELLER_CAPTURE`가 한 달에 1건을 넘으면 품목 단위 배분 규칙을 정한다.
- 운영에서 `PAYMENT.CAPTURE.REVERSED` payload가 refund 자원이 아닌 모양(예: `links[rel=up]` 없음)으로 오면 그 사건이 dead letter로 가므로, 실제 payload로 파싱을 고친다.
- 분쟁이 판매자 패소로 끝났는데 역전·환불 사건이 오지 않은 사례가 1건이라도 확인되면 분쟁 결과로 원장을 움직이는 규칙을 다시 정한다.
- 응답을 잃은 우리 환불이 `PENDING`/`UNKNOWN`으로 웹훅 재시도 창(ADR-0032)보다 오래 남아 외부 환불이 dead letter로 간 사례가 생기면, 환불 요청에 `custom_id`를 실어 가르는 안을 다시 잰다.
