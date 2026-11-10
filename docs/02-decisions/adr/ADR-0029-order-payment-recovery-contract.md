# ADR-0029: 주문 결제·재고 복구 계약

- 상태: Accepted
- 날짜: 2026-11-10

## 맥락

주문, payment provider, inventory, refund, settlement는 서로 다른 상태를 소유한다. 특히 외부 provider timeout은 결제 미실행을 뜻하지 않으며, 여러 모듈의 상태를 하나의 `success/failure` 값으로 축약하면 중복 청구 또는 재고 조기 해제 위험이 있다. 이 문서는 기존 구현과 선행 ADR의 계약을 한곳에서 연결해 설명한다. 현재 구현을 넘어서는 분산 workflow 인프라를 새로 결정하지 않는다.

## 기존 구현으로 확정되는 계약

| 대상 | 상태/권한 계약 | 근거 |
|---|---|---|
| 결제 요청 중복 | 동일 order/checkout payment attempt는 로컬 owner별 1건이다. PayPal create/authorize/capture/void/refund 각 provider operation은 별도 고정 request ID를 재사용해 동일 작업을 replay한다. 새 업무 작업은 새 operation/key를 사용한다. | `PaymentAttemptRepository`, `PaymentServiceImpl`; ADR-0026 |
| 결제 결과 불명 | `PENDING`/`UNKNOWN`은 완료나 거절이 아니다. 같은 attempt와 provider request ID로 대조/재요청해야 하며, 결과 확인 전 두 번째 결제 attempt를 만들지 않는다. | `PaymentState`, `PaymentServiceImpl`, `OrderServiceImpl`; BR-003/INV-004 |
| 재고 hold | checkout 시 예약 후 capture 확정 시 claim한다. capture 결과가 불명확하면 reservation은 유지한다. 재고는 definitive non-payment/required void confirmation 뒤에만 반환한다. | `InventoryServiceImpl`, `CheckoutPaymentServiceImpl`, BR-002/BR-003/INV-004 |
| 금액과 통화 | order item snapshot과 checkout total이 청구 근거다. Provider 경계의 현재 통화는 KRW, amount는 won의 정수 단위다. Capture settlement는 provider capture 확인 뒤 gross order snapshot 금액으로 기록한다. Refund는 원 주문 item의 snapshot 할인 배분/환불 가능 수량을 사용한다. | `PurchaseOrder`, `OrderItem`, `PaymentAttempt.totalWon`, `PaymentServiceImpl`, ADR-0011/ADR-0013/ADR-0024 |
| 환불 소유권 | Order는 환불 eligibility, item quantity, return inspection 및 고객 노출 상태를 결정한다. Payment은 provider refund operation, provider request/refund ID, pending/unknown/completed/failed 상태를 소유한다. Provider timeout은 환불 완료가 아니다. | `OrderRefundServiceImpl`, `PaymentRefundOperation`, `CheckoutPaymentServiceImpl`, ADR-0013/ADR-0026 |
| Settlement | Settlement ledger는 capture/refund posting의 회계 기록이며 order/payment operational status의 대체물이 아니다. 동일 posting replay는 facts가 같을 때만 idempotent하다. | `SettlementServiceImpl`, ADR-0011, D-066/D-068 |
| Checkout expiry | Unsubmitted `RESERVED` checkout만 expiry sweep 대상이다. 이미 provider 작업이 submitted/pending/unknown이면 단순 TTL만으로 inventory를 해제하지 않는다. Fulfillment/shipment state는 payment/order state를 대체하지 않는다. | `OrderServiceImpl`, `CheckoutPaymentServiceImpl`, ADR-0010/ADR-0016; INV-004 |
| Webhook | Signature verification은 sender authenticity만 보장한다. 이벤트는 local provider operation ID와 stored financial facts에 대조한다. 일시적 resource 미매칭은 retryable이며, local operation과 연결된 영구 facts mismatch는 FAILED로 기록하고 acknowledgement한다. Duplicate event ID는 한 번만 처리한다. | `PayPalWebhookService`, `PaymentServiceImpl`, `PaymentWebhookEventRepository`; ADR-0026 |

## 결정과 운영 원칙

- 각 모듈은 위 표의 자기 상태를 소유한다. 한 모듈의 성공 응답만으로 다른 모듈의 상태가 완료됐다고 해석하지 않는다.
- Request/correlation ID는 진단용이며 idempotency key가 아니다. Idempotency key는 업무 작업의 재전송 식별자이고 provider request ID와 같은 범위의 키가 아니다.
- Caller와 operator에는 pending/unknown을 terminal failure/success로 표시하지 않는다. Reconciliation 결과를 반영한 뒤에만 definitive state를 반환한다.
- Order/payment owner lock은 같은 owner의 상충 작업을 프로세스 안에서 직렬화합니다.
  기존 256개 hash stripe는 다른 owner까지 같은 monitor로 묶어 provider 응답을 기다리게 했습니다.
  이제 holder/waiter 참조 수를 추적하는 UUID별 registry를 쓰고 마지막 사용 뒤 항목을 회수합니다.
  같은 owner의 직렬화는 유지합니다.
- 이 registry는 JVM 내부 전용이며 여러 instance를 조정하지 않습니다.
  Provider request ID는 같은 operation 재전송을 보호하지만 capture/void처럼 서로 다른 operation의 우선순위는 정하지 않습니다.
  다중 instance 동시성은 별도 DB 상태 전이 설계와 검증 전까지 보장하지 않습니다.
- 새 `saga`, `outbox`, queue, 분산 lock은 도입하지 않습니다.
  현재 DB transaction, row lock, unique constraint, durable operation row, provider retry ID를 사용합니다.
  추가 운영 인프라는 장애·지연 근거가 생길 때 별도 결정합니다.

## trade-off와 후속 도입 기준

동기 DB 처리와 provider retry는 운영 인프라가 단순하고 현재 단일 서비스 배포 모델에 맞는다. 그 대가로 request latency가 provider/API/DB 처리 시간에 좌우되고, webhook 처리 실패는 provider retry에 의존한다. 이는 별도의 dispatcher와 queue 운영 부담보다 현재 증거상 낮은 비용으로 선택됐다.

Outbox/worker 또는 durable saga는 다음 증거가 생길 때 별도 ADR로 재평가한다: webhook/provider retry만으로 unresolved operation age/SLO를 만족하지 못함, reconciliation 작업이 지속 누락됨, burst/latency 측정에서 동기 처리 한계가 확인됨, 여러 독립 배포/DB 경계가 생겨 현재 transaction이 원자성을 보장하지 못함. 그때 지표·장애모드·복구 운영과 migration plan을 함께 제출한다.

## 검증과 한계

현재 CI의 PostgreSQL integration tests는 로컬 DB/transaction/idempotency 경계를 검증한다. PayPal fixture test는 provider sandbox의 실 delivery를 대체하지 않는다. Live payment는 ADR-0026에 따라 범위 밖이다. 이 문서는 현재 구현을 설명하는 계약 요약이며, 실제 provider sandbox delivery, timeout ambiguity 및 concurrent cross-instance behavior가 검증되지 않은 곳은 완료됐다고 주장하지 않는다.
