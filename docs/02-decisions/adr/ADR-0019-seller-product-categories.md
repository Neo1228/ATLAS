# ADR-0019 — Seller 상품 분류와 Catalog filter

- 상태: 대체됨 · 현재 category hierarchy는 [ADR-0022](ADR-0022-seller-category-hierarchy.md)
- 결정: D-074
- 추적: [Issue #161](https://github.com/Neo1228/ATLAS/issues/161), [PR #162](https://github.com/Neo1228/ATLAS/pull/162)
- 요구사항: [FR-012 / BR-012 / INV-009 / Q-010](../../01-requirements/requirements-index.md)
- 관련 결정: [D-061 / D-067](../../00-governance/decision-log.md), [ADR-0008](ADR-0008-catalog-cart-order-boundaries.md), [ADR-0012](ADR-0012-catalog-listing-order.md)

## 배경

Catalog는 Seller·Product·Seller-SKU를 소유합니다. Product listing은 상품명 substring 검색과 Seller filter, UUID keyset pagination을 지원하지만 카테고리 분류는 없습니다.

D-067/ADR-0012는 목록 정렬만 규정. 이 ADR은 Seller-owned category filter를 추가하며, Product·Category 관계는 Catalog 소유. Global taxonomy와 multi-category 연결은 Q-010에서 정책 결정.

## 결정

- Catalog가 Seller 소유 `ProductCategory`를 관리. 한 Product는 같은 Seller의 Category 하나에만 연결.
- Category `code`는 Seller별 unique·immutable. 표시 `name`만 수정 가능. `UNCATEGORIZED`는 Seller마다 하나만 두고 생성 시 Product 기본 분류로 사용; 표시명 변경·삭제·archive 금지.
- 신규 Seller 생성과 기본 Category 저장은 같은 Catalog transaction. V18에서 기존 Seller마다 기본 Category를 만들고 기존 Product를 backfill. `product(seller_id, category_id)` composite FK로 Seller 경계 보장.
- Product 생성 시 같은 Seller의 `categoryId`는 선택값; 생략 시 `UNCATEGORIZED`. 기존 Product는 Seller 전용 reassignment API로 분류 변경.
- `categoryId` listing filter는 명시적인 Seller 범위에서만 허용. `/api/products`의 sellerId 또는 Seller path가 필요하며, seller scope 없는 Category filter는 `INVALID_REQUEST`. 다른 Seller의 Category는 `NOT_FOUND`.
- 기존 name substring, seller/product UUID ordering, pagination size는 유지. 결과 순서·가격·재고 filter·ranking은 변경하지 않음.
- Category 없는 기존 cursor v1은 유지. Category filter를 포함한 cursor는 v2로 발급하며 Seller·Category·normalized query·마지막 위치에 결합. 다른 filter와의 재사용은 거부.
- Category list는 Seller별 code 오름차순. Category의 hard delete, archive, hierarchy, tag, multiple assignment는 후속 결정.
- Inventory availability는 Catalog Category query의 조건이 아니며, 읽기 요청에서 Inventory 상태를 조회하거나 변경하지 않음.

## 근거와 대안

| 선택 | 대안 | 이유·절충 |
|---|---|---|
| Seller-owned category | global canonical taxonomy | 현재 플랫폼 분류 주체·taxonomy governance가 없음. Seller별 storefront 분류로 시작 |
| flat·single category | hierarchy·multi-category·tag | 교차 분류와 이동/삭제 의미를 추가하지 않고 기본 탐색 경로부터 검증 |
| PostgreSQL listing query + filter index | Elasticsearch·recommendation·ML ranking | ATLAS에 정확도·지연 한계를 입증한 데이터 없음. Seller/category equality predicate에 composite index 적용; 이는 query-shape 결정이며 측정된 성능 향상 주장이 아님 |
| legacy v1 + category v2 cursor | 모든 cursor를 v2로 강제 전환 | category 없는 기존 페이지 재개를 유지하면서 새 filter를 payload에 명시 |

## 결과와 한계

- V18 migration만 추가. V1–V17 이력은 수정하지 않음. Catalog module이 entity·service·repository·DTO를 소유; Application은 HTTP·Flyway·PostgreSQL integration test 소유.
- V18은 `product(seller_id, category_id, product_id)` index로 Seller/category equality predicate를 지원. 추가 index·성능 개선 주장은 representative local data와 `EXPLAIN` 확인 뒤 결정.
- Cursor payload와 Product response에 category 정보 추가. 클라이언트는 Seller-scoped category list를 사용해 filter 선택.
- Category 필터는 sellerId 데이터 소유 관계를 검증하지만 인증·인가나 tenant isolation을 제공하지 않음.
- Global taxonomy, category lifecycle/archive, 여러 Category 할당, 재고/가격 filter, ranking, Search engine·ML은 미정 또는 제외.
- PostgreSQL correctness 검증만 수행. 외부 scale, 사용자 수, 처리용량 또는 production SLO를 추정하지 않음.

## 참고 자료

- [REF-119](../../00-governance/reference-register.md): Meituan 사례는 판매자별 상품명 편차와 분류 granularity 문제를 보여 줌. NLP/CV standardization pipeline은 도입하지 않음.
- [REF-120](../../00-governance/reference-register.md): Shopify 사례는 중앙 hierarchical taxonomy와 VLM 기반 분류의 발전을 보여 줌. global taxonomy·ML·규모를 ATLAS에 일반화하지 않음.
