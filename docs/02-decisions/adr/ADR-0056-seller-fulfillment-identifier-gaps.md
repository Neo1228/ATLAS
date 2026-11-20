# ADR-0056: 판매자 이행 흐름의 식별자 공백을 API 계약으로 메운다

- 상태: Accepted
- 날짜: 2026-11-20
- 관련: [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md), [ADR-0055](ADR-0055-extreme-load-boundaries-and-infra-gates.md)
- 추적: [#437](https://github.com/Neo1228/ATLAS/issues/437)

## 배경

DB 성능·동시성 작업을 마친 뒤 **제품과 API 표면**을 점검했다. 성능이 아니라 "고객이 실제로 흐름을 완주할 수 있는가"를 봤다.

발견은 세 가지였고, 모두 성능과 무관한 **계약 공백**이다.

## 1. 판매자가 orderId·orderItemId 를 알 수 없다

모든 이행 API 가 이 식별자를 요구한다.

| 엔드포인트 | 요구 식별자 |
|---|---|
| `POST /sellers/{sellerId}/orders/{orderId}/shipments` | `orderId` + 본문 `orderItemId` |
| `POST /sellers/{sellerId}/orders/{orderId}/refund-records` | `orderId` + `orderItemId` |
| `PUT .../returns/{returnId}/inspection` | `orderItemId` |

그런데:

- `OrderItemResponse` 에 **`orderItemId` 가 없었다**
- **판매자 주문 목록 API 가 없었다** (구매자 목록만 존재)

증거는 명확하다. 이 저장소의 통합 테스트 3곳이 이 값을 **JDBC 로 직접 조회**하고 있었다.

```java
// LocationRoutedCheckoutIntegrationTest.java:270
jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id=?", UUID.class, ...);
```

테스트가 우회한다는 것은 API 로는 불가능하다는 뜻이다. 판매자는 HTTP 만으로 **첫 이행 호출조차 만들 수 없었다.**

## 2. 지급이 REQUESTED 에 영구히 머문다

`SettlementPayoutService` 는 `approve`/`markSent`/`confirm`/`fail` 을 구현했지만, **컨트롤러가 하나도 노출하지 않았다.**

그런데 지급 가능 잔액은 이렇게 계산된다:

```java
available = balance.payableAmountWon − payouts.pendingAmount(sellerId)
// pendingAmount = SUM(REQUESTED, APPROVED, SENT)
```

따라서 요청된 지급이 영원히 `REQUESTED` 면, 그 판매자의 **잔액이 영구히 줄어든 채로 묶인다.** 돈은 지급 불가능한 상태가 된다.

`SellerPayoutController` 의 javadoc 은 이 누락을 인정하고 있었다("지급 실행 주체가 정해지면 추가한다"). 인정은 했지만 **돈이 묶이는 결과**는 남아 있었다.

## 3. 환불 실패가 "환불 완료"로 기록된다

`OrderReturnServiceImpl` 은 검수 성공 후 provider 환불 결과를 **확인하지 않고** 반품을 `REFUND_RECORDED` 로 확정했다.

```java
OrderRefundResponse refund = refunds.processInspectedReturnRefund(returnId);
// refund.paymentStatus() 를 보지 않는다
OrderReturnStatus status = ... : OrderReturnStatus.REFUND_RECORDED;
```

provider 환불이 `FAILED` 면:

- 판매자 화면: "환불 완료"
- 구매자: **돈을 받지 못함**
- 재시도: `executeRefundOperation` 이 terminal `FAILED` 를 보고 즉시 반환하므로 **자동 재시도도 없음**

이는 **금전 상태에 대한 거짓**이다.

## 4. 캠페인 주문의 PayPal 리다이렉트가 401

`PaymentServiceImpl` 은 주문형과 checkout형의 return URL 을 모두 만든다.

```java
String returnPath = attempt.getCheckoutId() == null
    ? "/api/orders/" + ownerText + "/payment/return"
    : "/api/checkouts/" + ownerText + "/payment/return";
```

보안 설정은 **checkout 경로만** `permitAll` 이었다.

구매자는 PayPal 승인을 마친 뒤 브라우저 리다이렉트에서 **401** 을 받는다. 결제는 provider 에서 성사됐지만 우리 쪽 완료 처리가 인증에 막힌다.

## 결정

네 가지를 모두 고친다.

| 결함 | 수정 |
|---|---|
| orderItemId 누락 | `OrderItemResponse` 에 `orderItemId` 추가 |
| 판매자 목록 부재 | `GET /api/sellers/{sellerId}/orders` (keyset), V55 인덱스 |
| 지급 전이 불가 | 운영자 API 4개 + 별도 Basic 인증·역할 |
| 환불 실패 오기록 | `paymentStatus == FAILED` 면 `CONFLICT` 로 남겨 재시도 가능하게 |
| 리다이렉트 401 | 주문형 return/cancel 경로도 `permitAll` |

### 지급 전이를 판매자 API 가 아니라 운영자 API 로 둔 이유

판매자가 자기 지급을 "송금 완료"로 표시할 수 있으면 안 된다. 이 전이는 **외부 송금 결과와 결합되는 운영 절차**다. 기존 `PaymentRecoveryOperatorController` 와 같은 패턴(Basic 인증, 별도 역할, `ConditionalOnProperty`)을 따랐다.

실제 송금 주체·주기는 여전히 정해지지 않았다. 이 API 는 **운영자가 외부 송금을 마친 뒤 결과를 기록하는 경로**이며, 송금 자체를 수행하지 않는다.

## 소유권

새 판매자 목록 엔드포인트는 `/api/sellers/**` 경로이므로 기존 `AccountOwnershipInterceptor` 가 `ownsSeller` 로 자동 검증한다. 별도 코드를 추가하지 않았고, 다른 판매자의 주문이 섞이지 않음을 테스트로 고정했다.

## 검증

- `SellerOrderDiscoveryIntegrationTest`: 판매자 목록으로 `orderId` 발견 → 상세에서 `orderItemId` 획득, 그리고 다른 판매자 주문 비노출
- `CheckoutAmountOverflowIntegrationTest`: 금액 오버플로가 500 이 아니라 400 (별도 PR #437)

## 남은 공백 (정직하게)

이 ADR 은 발견된 네 가지만 다룬다. 조사에서 나온 나머지는 **아직 고치지 않았다**:

- 같은 "내 것이 아님" 응답이 경로에 따라 403/404/409 로 다르다
- 페이지네이션 방언이 다섯 가지다 (의도된 구분이 섞여 있어 일괄 변경은 위험)
- 장바구니 수량·항목 수 상한이 없다 (오버플로는 400 으로 막았지만 근본 상한은 업무 정책 결정이 필요)
- PayPal 토큰 경로 외 다른 외부 호출의 타임아웃 감사

이들은 **측정이나 정책 결정이 선행돼야** 하므로 별도로 다룬다.
