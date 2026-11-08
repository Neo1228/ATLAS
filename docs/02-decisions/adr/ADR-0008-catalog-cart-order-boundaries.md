# ADR-0008: Catalog·Cart·Order 경계

- 상태: ✓ 확정
- 날짜: 2026-11-04
- 담당자: Project Owner
- 관련 결정: [D-058~D-063, D-071](../../00-governance/decision-log.md)

## 배경

ATLAS의 목표는 전체 이커머스 플랫폼. 기존 Buyer 경로는 Campaign·대기열을 거치는 한정판매로 한정됐고, 일반 상품·장바구니·주문 흐름은 없었습니다.

기존 `campaign-module`은 Seller와 Seller-SKU까지 소유했습니다. 당시에는 독립된 상품 구매 흐름이 없었기 때문입니다 ([ADR-0003/D-054](ADR-0003-gradle-module-boundaries.md)). 일반 Catalog·Cart 흐름이 승인되어 해당 경계를 다시 나눌 조건이 생겼습니다.

기존 `Order`는 Campaign/Ticket을 필수로 저장하고, Inventory reservation은 주문당 한 SKU·수량 1개였습니다. 일반 구매를 route만 추가해서는 기존 모델을 재사용할 수 없습니다.

## 선택지

| 선택 | 장점 | 비용·위험 |
|---|---|---|
| Product/Cart를 Campaign/Order에 추가 | 초기 모듈 추가 없음 | Catalog가 판매 방식에 종속되고 Cart와 Order lifecycle 혼합 |
| Product·Cart를 별도 Gradle 모듈로 분리 — 선택 | Seller 상품 흐름과 mutable Cart의 소유권 명확. Campaign은 판매 채널 중 하나로 유지 | 공개 계약·의존성·Flyway schema 갱신 필요 |
| 독립 서비스·DB로 분리 | 독립 배포·확장 가능 | 별도 요구나 측정 근거가 없고 network failure·분산 transaction 도입 |

## 결정

단일 Spring modular monolith와 PostgreSQL database 유지. 새 도메인은 실제 업무 lifecycle에 맞춰 모듈로 구분.

| 모듈 | 소유 책임 | 의존 방향 |
|---|---|---|
| `catalog-module` | Seller, Product, seller-owned Seller-SKU, 현재 가격 | `inventory-module`의 재고 pool 생성 계약 |
| `cart-module` | Buyer당 하나의 Cart와 `(sellerId, skuId)` line을 가진 CartItem | `catalog-module`의 SKU 유효성·표시 계약 |
| `campaign-module` | Campaign 생성·상태·판매 자격 | `catalog-module`의 Seller-SKU 계약 |
| `admission-module` | FIFO ticket, 전역 sequence, SKU별 `maxActiveCheckouts` policy | Campaign·Catalog 계약 |
| `inventory-module` | Seller-SKU stock pool, 주문별 여러 SKU reservation | 독립 계약 |
| `order-module` | BuyerCheckout, Seller별 PurchaseOrder/OrderItem, checkout·결제 조정, fulfillment | Admission·Catalog·Cart·Inventory·Payment contracts |
| `payment-module` | Payment attempt, 기존 Fake PSP 상태 | 독립 계약 |

- `SellerSku`는 하나 이상의 SKU variant를 가진 Seller Product에 소속. 현재 단순 필드만 지원하며 옵션 조합·promotion·가격 이력은 추가하지 않음.
- D-078/ADR-0023는 이 범위를 부분 확장해 SellerSku별 한 개의 seller-funded 기간 한정 sale price를 추가. Coupon·stacking·전체 가격 이력은 계속 제외.
- `maxActiveCheckouts`는 상품 속성이 아닌 Admission policy. Catalog schema에서 제거하고 Admission 소유의 `(sellerId, skuId)` 정책으로 관리. 모든 Campaign은 같은 SKU policy를 공유.
- 당시 Cart는 `(buyerRef, sellerId)` 기준이며 Seller 간 합치지 않았음. 현재 Buyer-wide Cart와 주문 cardinality는 [D-071/ADR-0016](ADR-0016-buyer-wide-cart-multi-seller-checkout.md)에서 대체.
- Cart의 가격은 안내용. Checkout에서 Catalog의 현재 가격을 다시 확인하고 OrderItem에 상품명·SKU·unit price(KRW)·quantity snapshot 저장.
- Checkout은 Cart row를 lock하고 모든 SKU의 재고를 일관된 순서로 확인·예약. 하나라도 부족하면 Order·reservation·Cart 변경 전체 rollback.
- 당시 idempotency replay는 단일 Seller Order 반환. 현재 replay 대상은 BuyerCheckout과 그 SellerOrder 집합 ([D-071/ADR-0016](ADR-0016-buyer-wide-cart-multi-seller-checkout.md)).
- 확인된 hold에서 첫 AUTHORIZE 제출까지 10분은 로컬 synthetic test window. 운영 TTL·SLO·수요 가정 아님. Fake PSP만 사용; 외부 호출은 DB transaction 밖.
- V7 migration은 V6 fresh-start schema에 신규 구조를 추가. 예상치 못한 기존 row를 자동 reset·mapping하지 않음.
- Reorder는 terminal `CART` Order만 허용. Campaign·진행 중 Order는 제외. 원본 Order 변경 없음.
- 취소·만료 후 Cart 자동 복원 없음. Buyer가 `POST /api/orders/{orderId}/reorder`를 명시적으로 호출.
- Reorder에서 현재 Seller-SKU/Product와 available quantity를 batch 확인. 일부라도 판매 불가면 Cart 변경 전체 rollback. 이 확인은 point-in-time이며 재고 hold가 아님; 새 Checkout이 atomic reservation을 담당.
- 기존 Cart가 비어 있지 않으면 merge confirmation 필수. 같은 SKU quantity 합산, 다른 SKU 유지. Current Catalog 가격·상품명 사용. `(sellerId, buyerRef, idempotencyKey)` 재시도는 같은 source Order에 한 번만 적용; 다른 source Order로 재사용하면 conflict.
- V8은 Order 소유의 Reorder idempotency 기록만 추가. Cleanup/retention 정책은 [Q-012](../../01-requirements/requirements-index.md#assumptions-and-open-questions)에서 미정.

## 결과

- Product·Cart·Order·Inventory multi-line 계약과 migration은 하나의 Buyer journey로 구현·검증.
- 기존 Campaign/FIFO/K/Fake PSP 경로 유지. Seller-SKU 등록은 Catalog가 소유하며 queue policy는 Admission API에서 별도 설정.
- `buyerRef`는 synthetic identifier. 인증·인가·tenant isolation은 제공하지 않음.
- 이 ADR의 초기 구현에서는 fulfillment·정산·환불을 제외. Fulfillment는 [D-065/ADR-0010](ADR-0010-order-fulfillment-lifecycle.md), capture/refund recording은 D-066/D-068 및 [ADR-0011](ADR-0011-gross-capture-settlement-ledger.md)/[ADR-0013](ADR-0013-pre-shipment-refund-recording.md), Buyer-wide checkout과 delivered return 규칙은 [D-071/ADR-0016](ADR-0016-buyer-wide-cart-multi-seller-checkout.md)에서 후속 확정.
- Redis, broker, distributed lock, microservice 추가 없음. PostgreSQL baseline에서 재현 가능한 병목이 확인된 뒤에만 고도화 검토.
- terminal Cart Order를 명시적으로 재주문하는 사용자 흐름 추가. Existing Cart merge·current price·available stock 확인, idempotent retry, all-or-nothing 실패를 같은 PostgreSQL transaction에서 검증.

## 근거와 한계

- PostgreSQL 18, [Explicit Locking](https://www.postgresql.org/docs/18/explicit-locking.html): row lock 수명과 deadlock 위험. SQL semantics 설명이며 Cart/Order 구현을 대신하지 않음 ([REF-056](../../00-governance/reference-register.md)).
- Shopify Engineering, [Inventory reservations with MySQL](https://shopify.engineering/scaling-inventory-reservations): transaction 중심 reservation 사례. MySQL·Shopify 부하이며 ATLAS 용량이나 PostgreSQL 실행 계획의 근거가 아님 ([REF-070](../../00-governance/reference-register.md)).
- Meituan, [Order database sharding](https://tech.meituan.com/2016/11/18/dianping-order-db-sharding.html): 측정된 DB 한계 이후의 분할 절충. Architecture type은 원문에 명시되지 않아 비-MSA 근거로 분류하지 않음. MySQL 사례와 운영 수치는 복제하지 않음 ([REF-108](../../00-governance/reference-register.md)).
- Ordering.co의 Reorder 자료는 원본 주문 재현을 보장하지 않고 Cart 충돌에 사용자 선택을 요구. Optimizely는 saved-order restore를 별도 동작으로 제공. Shopify cancellation에서 inventory restock 옵션은 Cart 복원과 별도. 사례마다 다르며 Reorder 정책은 [D-063](../../00-governance/decision-log.md) 프로젝트 결정 ([REF-109~REF-111](../../00-governance/reference-register.md)).

## 재검토 기준

- 실제 buyer/product variant 요구가 합성 범위를 넘어설 때 product attributes·multi-currency 검토.
- k6/Toxiproxy scenario에서 DB lock·connection occupancy·reservation lag 병목이 반복 재현될 때만 인프라·추가 기술 대안 비교.
- Reorder idempotency record 보존/정리 기준은 source Order 보존 기간과 저장량을 관찰한 뒤 검토 ([Q-012](../../01-requirements/requirements-index.md#assumptions-and-open-questions)).
- 독립 release/team/data ownership 근거 전에는 모듈을 별도 서비스로 분리하지 않음.
