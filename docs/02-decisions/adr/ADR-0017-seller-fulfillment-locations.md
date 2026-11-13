# ADR-0017 — Seller fulfillment locations

- 상태: 승인
- 결정: D-072
- 추적: [Issue #157](https://github.com/Neo1228/ATLAS/issues/157), [PR #158](https://github.com/Neo1228/ATLAS/pull/158), [요구사항 FR-011](../../01-requirements/requirements-index.md)

## 배경

Seller SKU 재고와 예약이 이미 존재한다. Seller별 fulfillment location을 더하되, 기존 예약의 결제·해제 결과와 재고를 보존하고 한 번의 구매 흐름 안에서 결정적 선택이 필요하다.

## 결정

- Seller는 안정적인 code/name, 0 이상 integer priority, active flag를 가진 location을 소유한다. code는 Seller 내 고유하다. DEFAULT location 삭제는 허용하지 않는다.
- 각 Seller PurchaseOrder는 모든 SKU·수량을 충족하는 active location 하나를 선택한다. priority 오름차순, 동률은 UUID 오름차순이다. Seller child order마다 location은 달라도 되지만 한 주문을 여러 location으로 나누지 않는다. Campaign 단일 SKU 주문도 동일 정책을 쓴다.
- 적격 location이 없으면 전체 Buyer checkout을 rollback한다. Reorder는 모든 line의 적격 location을 사전 확인할 뿐 재고를 예약하지 않는다. Checkout은 원자적으로 다시 검증한다.
- V16은 기존 Seller 재고를 Seller별 DEFAULT location으로 매핑한다. 과거 위치 이력이 없으므로 분할하거나 위치를 추정하지 않는다. 기존 location 없는 pool·reservation·adjustment·order도 DEFAULT에 귀속한다. 새 SKU 초기 재고는 DEFAULT에 들어간다.
- 선택 location은 reservation과 PurchaseOrder에 저장한다. 결제 claim, release, 만료·취소, return restock은 저장된 위치를 사용하며 다시 선택하지 않는다.
- 같은 Seller/SKU 사이 transfer는 available 수량만 이동하며 두 location의 capacity/available을 원자적으로 변경하고 보존식을 지킨다. Append-only audit와 변경은 한 transaction이다. 동일 idempotency key/facts는 replay, 다른 facts는 conflict.
- Transfer도 동일한 2단계 잠금 키 전환을 사용합니다. 호환 release는 legacy delimiter key와 encoded key를 순서대로 획득하고, 이후 release에서 pre-bridge 인스턴스가 모두 drain된 것을 확인한 뒤 legacy key를 제거합니다. 이는 zero-downtime rollout 중 멱등성 동기화를 유지하되, 1단계에는 delimiter alias 경합이 남습니다.
- location별 pool counter는 기존 `integer` 범위 유지. Seller-SKU 합산 조회는 location 간 overflow 방지를 위해 64-bit로 표현. location별 capacity 상한을 높이는 결정은 아님.
- Inventory가 location·pool·reservation·transfer를 소유하고 Order가 PurchaseOrder를 소유한다. 모듈은 공개 API로만 연동하고 PostgreSQL transaction/locking이 무결성을 책임진다.

## 근거와 대안

이 결정은 기존 상거래 기능을 넓히면서 location별 보유량과 주문 경로를 명시하고, 재시도에도 결정적 결과를 제공한다. PostgreSQL 단일 system of record와 기존 modular monolith의 transaction 경계를 유지한다.

- Routing은 active location 중 priority ASC, UUID ASC 순서로 한 곳을 선택. `DEFAULT` migration mapping은 기존 재고를 보존하는 시스템 sentinel이며 주소를 나타내지 않음.
- SellerOrder는 location 간 분할하지 않음. Location 비활성화는 허용하며 deletion과 stock reassignment 정책은 Q-002에서 결정.
- Address·distance·carrier·channel 조건은 현재 routing key에 포함하지 않음; 적용에는 추가 source data와 정책 결정 필요.

- Shopify [Flexible Order Routing](https://shopify.engineering/creating-a-flexible-order-routing-system-with-shopify-functions)은 merchant가 location priority를 관리하는 사례. Implicit DB order 대신 명시적 priority를 둔 근거.
- Shopify [Inventory Reservations](https://shopify.engineering/scaling-inventory-reservations)은 reservation transaction boundary와 lock·connection 지표를 살핀 사례.
- MySQL/Redis/`SKIP LOCKED`/Wasm 구현은 그대로 채택하지 않음.

## 결과와 한계

- 모든 order line을 충족하는 location을 선택. Aggregate Seller-SKU 재고만으로 Reorder 가능성을 판정하지 않음.
- Global seller lock과 location 분할은 추가하지 않음.
- Address·geography·carrier·channel inventory와 자동 최적화 정책은 Q-002에서 미결.
- PostgreSQL Testcontainers 통합 테스트에서 concurrent same-key/same-facts replay의 단일 이동·동일 결과, changed-facts 경합의 단일 이동·conflict, capacity 보존 확인.
