# ADR-0012: Catalog 목록의 결정적 순서

- 상태: ✓ 확정
- 결정일: 2026-11-04
- 결정 ID: D-067
- 관련 요구사항: FR-005, Q-010
- 관련 Issue: [Issue #120](https://github.com/Neo1228/ATLAS/issues/120)

## 배경

Catalog는 전체 상품과 Seller별 상품 목록을 반환합니다. 기존 구현은 `findAll()` 결과를 Seller별 `HashMap` 그룹으로 모으고, Seller 목록은 저장소 반환 순서를 그대로 사용합니다. 데이터베이스 조회에 명시적 정렬이 없으므로 응답 순서가 요청 사이에 달라질 수 있습니다.

Q-010은 상품 탐색의 우선순위·세부 기능을 미정으로 둡니다. 그래서 이 결정은 검색·추천 순위를 새로 정하지 않고, 이미 존재하는 목록 API의 결과만 재현 가능하게 만듭니다.

## 결정

- 전체 상품 목록은 `sellerId`, `productId` 순으로 오름차순 반환합니다.
- Seller별 목록은 `productId` 순으로 오름차순 반환합니다.
- 각 상품의 Seller-SKU variants는 `skuId`의 사전식 오름차순으로 반환합니다.
- UUID는 Java의 natural order를, `skuId`는 case-sensitive `String.compareTo` 순서를 사용합니다. 이 순서는 식별자의 재현 가능한 기술 정렬일 뿐 생성 시각, 상품 인기, 판매 우선순위 또는 검색 relevance를 뜻하지 않습니다.
- HTTP 경로와 응답 구조는 유지하고 Catalog service에서 정렬합니다. Database index나 외부 search library는 추가하지 않습니다.

## 근거와 대안

- 정렬 없는 응답은 JPA/PostgreSQL이 보장하지 않는 저장소 순서에 기대어 클라이언트마다 결과가 달라질 수 있습니다.
- 상품명 정렬은 이름 변경과 대소문자·언어 규칙을 제품 정책으로 정해야 합니다.
- relevance/merchandising ranking은 사용자 행동·상품 특성·판매 목표를 정해야 하므로 별도 승인 대상입니다. Meituan의 다중 업무 상품 순위 사례(REF-114)는 그런 ranking이 독립된 모델링 과제임을 보여 주지만, ATLAS의 정렬 키를 정해 주지는 않습니다.

## 현재 구현과 미결 정책

| 영역 | 상태 | 기준 |
|---|---|---|
| Pagination·keyword·category filtering | 구현됨 | [FR-012](../../01-requirements/requirements-index.md), [ADR-0019](ADR-0019-seller-product-categories.md), [ADR-0022](ADR-0022-seller-category-hierarchy.md) |
| Ranking·personalization·new-product priority | 정책 미정 | [Q-010](../../01-requirements/requirements-index.md) |
| Query performance·index | 측정 후 결정 | [EXP-015](../../03-experiments/EXP-015-catalog-listing-performance.md), [EXP-018](../../03-experiments/EXP-018-read-query-performance.md) |

## 검증

- 전체 목록은 입력 저장소 순서가 달라도 Seller ID와 Product ID 기준 결과가 동일합니다.
- Seller별 목록은 Product ID 기준으로 정렬됩니다.
- 전체·단건 Product 응답의 variants는 SKU ID 기준으로 동일하게 정렬됩니다.
- 기존 상품 등록·가격 변경·checkout 가격 재검증 계약은 변경하지 않습니다.

## 판단 근거

- [D-067](../../00-governance/decision-log.md), [FR-005/Q-010](../../01-requirements/requirements-index.md)
- 美团技术团队, [美团搜索多业务商品排序探索与实践](https://tech.meituan.com/2021/11/19/Exploration-and-Practice-of-Multi-business-Commodities-Ranking-in-Meituan-Search.html): 다중 업무 상품 relevance ranking 사례. 다른 서비스의 모델·업무 목표이며 ATLAS의 ranking 또는 성능 근거가 아닙니다. [REF-114](../../00-governance/reference-register.md)
- [ADR-0008: Catalog·Cart·Order 경계](ADR-0008-catalog-cart-order-boundaries.md)

## 구현 갱신 (2026-11-05)

- 목록 응답: `{ items, nextCursor }`. DB keyset query에서 `limit + 1`건만 조회하고 현재 페이지의 Seller-SKU만 batch-load.
- PostgreSQL UUID byte order 대신 두 64-bit 절반의 sign bit를 변환한 정렬 키 사용. D-067의 Java `UUID` natural order와 cursor 순서 일치.
- 검색은 상품명 literal substring. `%`와 `_`는 wildcard로 해석하지 않음.
- 페이지 크기: 기본 `10`, 최대 `100`. [REF-115](../../00-governance/reference-register.md)의 Stripe `v1` API 계약 사례만 참고; ATLAS 처리 용량 근거 아님.
- 계산된 정렬 키에는 전용 index 없음. 현실적인 데이터 크기의 `EXPLAIN`·latency 측정 전까지 성능·용량 주장은 보류.
- cursor는 마지막 Seller/Product ID의 Base64 표현. 인증·서명 토큰이 아니며, 페이지 이동 중 변경 데이터에 대한 snapshot 일관성은 보장하지 않음.
