# ADR-0112: 정지·폐쇄된 Seller는 판매·결제 제출·지급 송금을 막고, 결제 완료 주문은 운영자가 Seller 환불 경로로 돌려준다

- 상태: Accepted(에이전트 결정. 사용자 승인 여부는 확인하지 못함)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0102](ADR-0102-account-notification-outbox-and-audit-stream.md)(`domain_audit_event`), [ADR-0105](ADR-0105-audit-table-append-only-guards.md)(감사 테이블 추가 전용)
- 근거: 이슈 [#740](https://github.com/Neo1228/ATLAS/issues/740), 회귀 시험 `OrderRefundLifecycleIntegrationTest.suspendedSellerStopsSellingAndOperatorRefundsPaidOrderThroughSellerRefundPath`

## 문제

운영자 정지(`suspendAccount`)와 본인 폐쇄(`closeAccount`)는 `user_account.status`를 `DISABLED`로 바꾸고 refresh 세션과 MFA challenge만 폐기했다. catalog, admission, order는 계정 상태를 읽지 않았다(main `f00412d3`에서 `git grep`). 그래서 세 가지가 생겼다.

1. 정지된 Seller의 상품이 계속 checkout되고 결제됐다. 회귀 시험이 main에서 "정지 뒤 checkout이 예외 없이 성공"으로 실패한다.
2. 결제 완료(`PAID`) 주문의 취소·환불은 Seller JWT가 필요한 경로뿐이었다. 정지된 Seller는 로그인하지 못하므로 구매자와 운영자 모두 돈을 돌려줄 길이 없었다.
3. 지급 승인은 `requireActiveRecipient`가 계정 `ACTIVE`를 확인해 막았지만, 승인된 지급을 PayPal로 보내는 worker는 계정을 다시 보지 않았다. 승인 뒤 정지된 Seller에게도 송금이 나갔다.

## 결정

1. **판정은 한 곳이다.** `AccountService.isSellerAccountDisabled(sellerId)`가 `seller_account`에 연결된 계정의 `status = 'DISABLED'`를 본다. 정지와 폐쇄는 같은 값을 쓰므로 둘 다 걸린다. `seller_account.seller_id`는 unique라 Seller 하나에 계정은 하나다. 연결된 계정이 없는 Seller는 막지 않는다(정지할 계정이 없다).
2. **catalog가 포트를 갖고 application이 잇는다.** catalog는 account에 의존하지 않는다(`ModuleBoundaryTest`). `com.atlas.catalog.service.SellerAccountStanding`을 두고 application의 `AccountSellerAccountStanding`이 1의 판정으로 구현한다. 판매 경로(admission, order)가 모두 catalog에 의존하므로 catalog에 둔다. `order.service.LocationClient`를 application이 구현하는 것과 같은 형태다.
3. **판매는 서버 checkout에서 막는다.**
   - `SellerSkuServiceImpl.findOrderableSellerSkus`는 정지·폐쇄된 Seller에 `PRODUCT_NOT_ORDERABLE`(HTTP 409)을 던진다. 장바구니 checkout, 재주문, 입장권 주문, 캠페인 개설·입장이 모두 이 메서드를 지난다.
   - `CheckoutPaymentServiceImpl.submit`은 정지 전에 만든 `RESERVED` checkout의 결제 제출을 `CONFLICT`로 거절한다. 상태를 바꾸지 않으므로 예약은 checkout 만료 sweep이 푼다.
   - 상품 조회와 장바구니 담기는 막지 않는다. 돈이 움직이지 않고, 정본 판정은 checkout과 결제 제출에 있다.
4. **운영자 환불은 Seller 환불 경로를 그대로 쓴다.**
   - `POST /api/internal/accounts/{accountId}/sellers/{sellerId}/orders/{orderId}/refund`(Idempotency-Key 헤더)와 찾기용 `GET .../orders`를 둔다. 계정 정지 API와 같은 보안 체인(`/api/internal/accounts/**`, HTTP Basic, `ACCOUNT_SUSPENSION_OPERATOR`)과 같은 프로퍼티(`atlas.account.suspension.operator.enabled`)로 켠다. 계정에 연결되지 않은 Seller는 404다.
   - `OrderRefundService.refundForDisabledSeller`가 남은 수량 전부를 서버에서 정하고, Seller 환불과 같은 `reserveRefund`(멱등 key 잠금, 수량·출고 검사, PSP 환불 준비, 정산 예약)와 송금 단계를 지난다. 같은 key 재시도는 처음 예약한 품목을 다시 쓴다. Seller가 활성이면 `CONFLICT`다. 활성 Seller의 주문은 Seller 환불 경로가 맡는다.
   - 운영자 식별자는 예약과 같은 트랜잭션에서 `domain_audit_event`에 `ORDER_REFUND_BY_OPERATOR`로 한 번 남긴다. payload에는 `refund_id`, `order_id`, `seller_id`, `amount_won`만 넣고 자유 텍스트와 key는 넣지 않는다(V9 규약).
5. **지급은 송금 직전에 보류하고, 보류는 계약 하나로 둔다.** settlement의 `PayoutHoldCheck`(`Optional<String> holdReason(sellerId)`, 사유는 `[A-Za-z0-9_-]{1,64}` 코드)가 지급 보류의 유일한 메커니즘이다. `PayoutDispatchWorker`는 PayPal 배치를 처음 만들기 전에 등록된 판정을 모두 묻고, 사유가 하나라도 있으면 PayPal을 부르지 않고 outbox를 `PENDING`으로 미루며 사유를 `last_error_code`로 남긴다. 이 PR은 `SellerAccountPayoutHold`(코드 `SELLER_ACCOUNT_DISABLED`, 1의 판정) 하나를 둔다. 다른 보류 이유(예: 분쟁 `OPEN_DISPUTE`)는 worker를 고치지 않고 구현 빈을 더한다(제공자 기원 환불·분쟁 작업 #741과 합의). 지급 상태는 바꾸지 않는다. 해제되면 다음 시도에서 나가고, 계속 막혀 시도·나이 한도(기본 8회, 29일)를 넘기면 기존 만료 경로가 `NEEDS_REVIEW`로 넘긴다. 운영자는 기존 requeue나 취소로 처리한다.
6. **마이그레이션을 만들지 않는다.** V17은 쓰지 않는다. 기존 `user_account.status`, `seller_account`, `domain_audit_event`만 쓴다.

## 대안과 기각 이유

| 대안 | 판정 | 이유 |
|---|---|---|
| `seller.status` 열 추가(리뷰 보고서 제안) | ✗ 기각 | 같은 사실을 `user_account.status`와 두 곳에 두게 된다. 정지·해제·폐쇄 세 경로가 두 열을 함께 바꿔야 하고 어긋나면 판정이 갈린다 |
| catalog가 `seller_account`·`user_account`를 직접 SQL로 읽기 | ✗ 기각 | 두 테이블은 account 모듈만 쓴다(main에서 다른 모듈 사용처 0건). 테이블 소유가 흐려진다 |
| catalog → account Gradle 의존 추가 | ✗ 기각 | catalog의 허용 의존은 inventory뿐이다(`ModuleBoundaryTest`). 포트 하나로 같은 일을 한다 |
| `order_refund_request`에 감사 trigger 추가 | ✗ 기각 | Seller 환불도 같은 표에 쓰므로 Seller 환불까지 운영자 행위로 기록된다 |
| 운영자 환불 전용 감사 표 추가(V17) | ✗ 기각 | `record_domain_audit_event()` 함수를 다시 만들어야 하고, 같은 함수를 바꾸는 다른 마이그레이션과 병합 순서에 따라 매핑이 사라질 수 있다 |
| worker가 정지 Seller 지급을 바로 `NEEDS_REVIEW`로 | ✗ 기각 | `markProviderNeedsReview`는 외부 시도가 있었던 지급(`DISPATCHING` 등)만 받는다. `APPROVED`에서 호출하면 예외로 재시도·`UNKNOWN` 경로에 빠진다 |
| 수동 지급 승인(`PayoutManualOperatorService.approve`)도 막기 | ✗ 기각 | 수동 경로는 감사를 남기는 운영자 판단 경로다. 막으면 폐쇄된 Seller의 남은 정산금을 계정을 다시 열지 않고 지급할 방법이 없다. 자동 송금만 보류한다 |
| 미출고 주문 자동 환불 SLA | ✗ 범위 밖 | 계정 정지와 무관하게 모든 Seller에 걸리는 정책이라 별도 결정이 필요하다. 재검토 조건에 둔다 |

## 결과

- 정지·폐쇄 직후부터 새 checkout과 결제 제출이 409로 거절된다. 이미 결제를 제출한 checkout(`PAYMENT_PENDING`)은 그대로 매입될 수 있고, 그 주문은 4의 운영자 환불로 돌려준다.
- 판정 질의가 늘어난다. checkout과 결제 제출은 Seller마다 1회, 운영자 환불은 1회, 지급 송금은 첫 시도마다 2회(지급 조회와 판정)다. 성능은 측정하지 않았다.
- 판정과 동작 사이에 정지가 커밋되는 경쟁은 막지 않는다. 창은 한 요청(또는 한 worker 시도)이고, 그 사이 생긴 결제 완료 주문은 운영자 환불이 맡는다.
- 출고가 시작된 주문(`fulfillment`가 `UNFULFILLED`·`IN_PROGRESS`가 아님)은 기존 규칙("Refunds not allowed after shipment begins")으로 거절된다. 이 주문은 반품 경로가 Seller의 수령·검수를 요구하므로 정지된 Seller에서는 아직 끝낼 수 없다.

## 롤백

- 코드만 되돌리면 된다. 스키마 변경이 없다.
- 이미 남은 `ORDER_REFUND_BY_OPERATOR` 감사 행은 추가 전용이라 지우지 않는다. 환불 행과 정산 분개는 Seller 환불과 같은 형태라 되돌린 코드에서도 그대로 읽힌다.

## 재검토 조건

- 정지된 Seller의 출고 시작 주문에 대한 구매자 환불 요청이 1건이라도 접수되면: 운영자 반품 수령·환불 경로를 결정한다.
- 미출고 결제 주문의 자동 환불(SLA)을 도입하기로 하면: 이 ADR의 운영자 환불 명령을 그 worker가 재사용할 수 있는지 함께 본다.
- `seller_account.seller_id`의 unique 제약을 없애 Seller 하나에 계정 여럿을 허용하면: "연결된 계정 중 하나라도 `DISABLED`"인 현재 판정이 맞는지 다시 정한다.
- 운영자 자격증명을 여러 명으로 나누면: 감사의 `actor`가 사람을 가리키는지 다시 확인한다(지금은 계정 정지 API와 같은 단일 Basic 사용자).
