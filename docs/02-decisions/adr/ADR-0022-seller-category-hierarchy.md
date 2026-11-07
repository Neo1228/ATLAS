# ADR-0022 — Seller 상품 분류 계층과 하위 탐색

- 상태: 구현 반영·local PostgreSQL Testcontainers/full Gradle check 통과
- 결정: [D-077](../../00-governance/decision-log.md)
- 추적: [Issue #168](https://github.com/Neo1228/ATLAS/issues/168), [PR #169](https://github.com/Neo1228/ATLAS/pull/169)
- 요구사항: [FR-012 / BR-012 / INV-009 / Q-010](../../01-requirements/requirements-index.md)
- 대체 범위: [D-074 / ADR-0019](ADR-0019-seller-product-categories.md)의 flat-only 분류 모델. Seller 소유권·Product 단일 Category 연결·V18 데이터 보존은 유지.

## 배경

D-074의 flat category는 Seller별 분류와 상품 필터를 제공하지만, Seller storefront에서 상위 분류를 통한 하위 상품 탐색은 지원하지 않음. Global taxonomy owner와 공통 분류 기준은 정해지지 않았으므로 Seller 분류만 확장.

## 결정

- `ProductCategory`에 nullable `parentCategoryId`를 추가하는 adjacency list 구조. Category는 같은 Seller 안에서 parent를 가짐; root는 parent 없음.
- `UNCATEGORIZED`는 root로 유지. Product는 기존처럼 같은 Seller Category 하나에만 연결.
- V20은 `(seller_id, parent_category_id)` composite FK와 self-parent CHECK 추가. 기존 Category는 모두 root로 남기며 Product 연결은 수정하지 않음.
- Parent 변경은 Catalog transaction에서 Seller row를 `PESSIMISTIC_WRITE`로 lock한 뒤 category ownership과 descendant를 확인하고 저장. 같은 Seller의 parent 변경을 직렬화해 동시에 발생하는 multi-node cycle 방지.
- DB는 same-Seller parent와 self-parent만 제약. Multi-node cycle 검사는 transaction 내 Catalog service의 책임이며, 직접 SQL로 hierarchy를 변경하는 경로는 지원하지 않음.
- Seller-scoped category filter는 선택 Category와 모든 descendant의 상품을 포함. Recursive CTE 사용; 기존 Seller scope, UUID keyset order, public/management visibility 유지.
- 새 listing cursor는 v4. Category filter 없는 legacy v1/v2/v3는 계속 허용; flat exact-match 의미의 기존 category-filtered cursor는 subtree pagination과 호환되지 않아 거절.

## 대안과 절충

| 대안 | 판단 |
|---|---|
| Flat category 유지 | 구조는 가장 단순하지만 상·하위 탐색 요구를 충족하지 못함 |
| Seller-owned single-parent tree | 기존 소유권·단일 Category 모델을 유지하며 storefront 분류 확장; cycle 검사와 recursive query 비용 발생 |
| Global taxonomy·Seller mapping | 공통 marketplace 탐색에 유리하지만 taxonomy owner·mapping·변경 정책 미정. 후속 결정 |
| Closure table·materialized path | subtree read를 빠르게 만들 수 있지만 중복 관계와 write 동기화 필요. query evidence 전 도입하지 않음 |
| Seller row lock | 같은 Seller의 parent 변경을 직렬화하는 단순한 correctness guard. 동시성 비용은 실제 contention 측정 뒤 재검토 |

## 결과와 한계

- V20 migration과 Seller parent API 추가. `PUT /api/sellers/{sellerId}/product-categories/{categoryId}/parent`로 parent 지정, `DELETE`로 root 복귀.
- Parent 변경은 같은 Seller 단위로 직렬화. DB constraint만으로 다중 노드 cycle을 막지 않으므로 Catalog service transaction 경계를 우회하는 write 금지.
- Recursive subtree listing은 새 query shape. `(seller_id, parent_category_id)` index는 추가하지 않음; representative data의 `EXPLAIN (ANALYZE, BUFFERS)`와 관측 결과로 필요성을 판단.
- 성능 향상, 최대 category 수, 사용자 수, RPS/TPS 또는 production SLO는 주장하지 않음.
- Global taxonomy, multi-category/tag, category delete/archive, ranking/search/ML은 별도 결정.

## 검증

- PostgreSQL Testcontainers: V19→V20 upgrade와 기존 Category/Product 보존, same-Seller FK, cross-Seller/self parent 거절.
- Catalog integration: 동시 반대 방향 move에서 하나만 commit, subtree pagination, cursor v4/legacy 호환성, public·Seller visibility.
- 결합 검증: `./gradlew --no-daemon --rerun-tasks check :application-module:bootJar`
- 결과: 40 suites, 200 tests, 0 failures/errors/skips; `bootJar` 성공. 동시성 correctness 확인이며 부하·성능 benchmark는 아님.

## 참고자료

- [REF-119 / REF-120](../../00-governance/reference-register.md): Meituan·Shopify 분류 사례. 문제 맥락만 참고; global taxonomy·NLP/CV/VLM·외부 규모를 ATLAS 설계 근거로 일반화하지 않음.
