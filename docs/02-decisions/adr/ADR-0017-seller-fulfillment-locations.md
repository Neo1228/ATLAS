# ADR-0017 — Seller fulfillment locations

- 상태: 승인
- 결정: D-072
- 추적: [Issue #157](https://github.com/Neo1228/ATLAS/issues/157), [요구사항 FR-011](../../01-requirements/requirements-index.md)

## 맥락

Seller SKU 재고와 예약이 이미 존재한다. Seller별 fulfillment location을 더하되, 기존 예약의 결제·해제 결과와 재고를 보존하고 한 번의 구매 흐름 안에서 결정적 선택이 필요하다.

## 결정

- Seller는 안정적인 code/name, 0 이상 integer priority, active flag를 가진 location을 소유한다. code는 Seller 내 고유하다. DEFAULT location 삭제는 허용하지 않는다.
- 각 Seller PurchaseOrder는 모든 SKU·수량을 충족하는 active location 하나를 선택한다. priority 오름차순, 동률은 UUID 오름차순이다. Seller child order마다 location은 달라도 되지만 한 주문을 여러 location으로 나누지 않는다. Campaign 단일 SKU 주문도 동일 정책을 쓴다.
- 적격 location이 없으면 전체 Buyer checkout을 rollback한다. Reorder는 모든 line의 적격 location을 사전 확인할 뿐 재고를 예약하지 않는다. Checkout은 원자적으로 다시 검증한다.
- V16은 기존 Seller 재고를 Seller별 DEFAULT location으로 매핑한다. 과거 위치 이력이 없으므로 분할하거나 위치를 추정하지 않는다. 기존 location 없는 pool·reservation·adjustment·order도 DEFAULT에 귀속한다. 새 SKU 초기 재고는 DEFAULT에 들어간다.
- 선택 location은 reservation과 PurchaseOrder에 저장한다. 결제 claim, release, 만료·취소, return restock은 저장된 위치를 사용하며 다시 선택하지 않는다.
- 같은 Seller/SKU 사이 transfer는 available 수량만 이동하며 두 location의 capacity/available을 원자적으로 변경하고 보존식을 지킨다. Append-only audit와 변경은 한 transaction이다. 동일 idempotency key/facts는 replay, 다른 facts는 conflict.
- Transfer advisory lock scope is a length-prefixed encoding of seller/SKU/idempotency tuple fields, so allowed `:` characters cannot make distinct operations share a lock key before PostgreSQL hashing.
- location별 pool counter는 기존 `integer` 범위 유지. Seller-SKU 합산 조회는 location 간 overflow 방지를 위해 64-bit로 표현. location별 capacity 상한을 높이는 결정은 아님.
- Inventory가 location·pool·reservation·transfer를 소유하고 Order가 PurchaseOrder를 소유한다. 모듈은 공개 API로만 연동하고 PostgreSQL transaction/locking이 무결성을 책임진다.

## 이유와 대안

이 결정은 기존 상거래 기능을 넓히면서 location별 보유량과 주문 경로를 명시하고, 재시도에도 결정적 결과를 제공한다. PostgreSQL 단일 system of record와 기존 modular monolith의 transaction 경계를 유지한다.

임의의 DB 반환 순서는 안정적인 정책이 아니므로 기각한다. 주문 line을 location별로 쪼개면 배송·결제 후속 흐름의 단위를 확장하므로 제외한다. 주소·거리·carrier·채널 기반 최적화는 위치 데이터와 외부 운영 의미를 요구하므로 연기한다. DEFAULT backfill은 이전 location 이력이 없어 채택한 보존 매핑이며 실제 물리 location의 주장은 아니다. location 비활성화는 허용하되 삭제·재고 재배치 정책은 범위 밖이다.

Shopify의 [Flexible Order Routing](https://shopify.engineering/creating-a-flexible-order-routing-system-with-shopify-functions)은 merchant가 location 선호 순서를 관리하는 사례를 제공해 implicit DB 순서 대신 명시적 priority를 둔 근거로 사용했다. [Inventory Reservations](https://shopify.engineering/scaling-inventory-reservations)은 reservation의 DB transaction 경계와 lock·connection 지표를 살펴본 사례다. MySQL/Redis/`SKIP LOCKED`/Wasm 구현은 복사하지 않았으며 운영 규모나 성능 목표도 가져오지 않았다.

## 결과와 미정 범위

location 선택은 모든 주문 line을 만족하는지 기준으로 하며 aggregate Seller-SKU 재고 조회만으로 Reorder 가능 여부를 판정하지 않는다. Global seller lock이나 location 분할을 추가하지 않는다. 주소·지리·carrier·channel inventory, 자동 최적화, 운영 용량/SLO는 미정 또는 제외다. PostgreSQL 검증은 통합 후 수행되며 이 ADR은 테스트 통과를 주장하지 않는다.
