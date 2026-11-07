# ADR-0018 — SellerOrder Shipment과 부분 출고

- 상태: 승인
- 결정: D-073
- 추적: [Issue #159](https://github.com/Neo1228/ATLAS/issues/159), [PR #160](https://github.com/Neo1228/ATLAS/pull/160), [FR-007 / BR-006 / BR-008 / Q-010](../../01-requirements/requirements-index.md)
- 전제: [D-071](../../00-governance/decision-log.md), [D-072](../../00-governance/decision-log.md), [ADR-0017](ADR-0017-seller-fulfillment-locations.md)

## 맥락

현재 `PurchaseOrder`당 `OrderFulfillment` 한 건과 주문 단위 상태만 보유. Package, tracking reference, OrderItem별 출고 수량은 없음. 여러 package로 나눠 출고할 때 초과 출고·환불 경합·return eligibility를 표현할 수 없음.

Pre-shipment direct refund는 `UNFULFILLED`/`IN_PROGRESS` 상태에서 허용. Return은 Seller 수령·검수 후 처리되며 이미 배송된 수량에 대한 refund/restock임. 두 흐름은 새 shipment 모델에서도 구분되어야 함.

## 결정

- 하나의 SellerOrder는 `OrderFulfillment` summary 한 건과 여러 `Shipment`를 가질 수 있음. 각 Shipment는 같은 SellerOrder의 OrderItem quantity를 담으며 같은 line quantity의 분할도 허용.
- `order_item.seller_id`는 parent PurchaseOrder의 Seller와 일치해야 함. V17 composite FK가 저장소 경계를 보장하고 Shipment service도 요청 line을 다시 검증.
- Shipment는 실제 dispatch 시점에 생성. Shipment status는 `SHIPPED → DELIVERED`; 포장 준비 상태의 임시 package는 저장하지 않음.
- SellerOrder를 시작하는 `IN_PROGRESS` 전이는 별도 use case. Order summary status는 출고 합계로 `PARTIALLY_SHIPPED`, `SHIPPED`, `DELIVERED`로 진행. Generic HTTP status setter 제거; shipment 기록만 실제 출고·배송 사실을 바꿈.
- OrderItem별 출고 가능 수량은 `ordered quantity - direct pre-shipment refunded quantity`. 출고 누적 수량은 이 값을 넘지 않음. Return refund는 `order_return_item.refund_id`로 구별하며 이미 출고된 수량이므로 shipment eligibility에서 빼지 않음.
- Shipment dispatch와 direct refund는 같은 `PurchaseOrder` row lock·PostgreSQL transaction을 사용. 같은 idempotency key/facts는 replay, key/fact mismatch는 conflict.
- Direct pre-shipment refund는 첫 dispatch 전까지만 허용. Return은 SellerOrder의 모든 net shippable quantity가 `DELIVERED`된 뒤 허용. 부분 delivery 상태의 return은 아직 지원하지 않음.
- Shipment carrier code와 tracking number는 둘 다 없거나 둘 다 기록하는 선택 metadata. 외부 carrier API·webhook·polling·배송비·ETA 계산은 제외.
- 새 Migration V17은 기존 `UNFULFILLED`/`IN_PROGRESS` 상태를 유지. 과거 `SHIPPED`/`DELIVERED`는 기존 OrderItem 수량에서 direct pre-shipment refund를 뺀 한 Shipment로 보존. 과거 데이터에 없는 idempotency key·carrier·tracking은 `NULL`; 기존 migration은 수정하지 않음.

## 이유와 대안

| 대안 | 결정 | 이유 |
|---|---|---|
| SellerOrder당 Shipment 1건·전체 수량 출고 | 기각 | 여러 package와 partial quantity를 표현하지 못함 |
| dispatch 전 `PREPARING` Shipment 저장 | 기각 | 기존 `IN_PROGRESS`의 direct refund와 package allocation을 함께 수정해야 해 취소·재배정 규칙을 추가로 요구 |
| dispatch 시점의 Shipment 기록 | 채택 | 물리 출고 사실과 OrderItem allocation이 같은 transaction에서 기록. 기존 pre-shipment refund 경계를 유지 |
| Carrier API 통합 | 후속 | 외부 인증·retry·timeout·provider failure가 범위를 크게 확장. 현재는 local simulation만 필요 |
| 부분 delivery 즉시 return | 후속 | delivered 수량·return claim·inspection 책임을 OrderItem 수량보다 더 정밀하게 정의해야 함 |

- [REF-112](../../00-governance/reference-register.md): Meituan 주문 여정의 변화 사례만 참고. 외부 아키텍처·규모를 ATLAS에 적용하지 않음.
- [REF-118](../../00-governance/reference-register.md): Alibaba의 전체·분할 출고 API 계약 비교만 참고. provider 동작을 복제하지 않음.

## 결과와 한계

- V17은 `order_fulfillment.status`를 `VARCHAR(24)`로 확장해 `PARTIALLY_SHIPPED` 저장. `shipped_at`은 net shippable quantity 전체 dispatch 시각, `delivered_at`은 전체 delivery 완료 시각.
- Aggregate quantity는 64-bit로 계산. OrderItem별 quantity는 기존 integer 범위 유지.
- Return flow는 전체 SellerOrder delivery 이후라는 기존 계약을 유지. Write-off, reinspection, return window, 분실·파손·재배송, multi-location fulfillment는 미정 또는 제외.
- API의 `sellerId`는 소유권 확인용 요청값일 뿐 인증·인가·Tenant 격리를 제공하지 않음.
- PostgreSQL 테스트는 local correctness 검증. 외부 사용자 수, 처리용량, production SLO를 보장하지 않음.
