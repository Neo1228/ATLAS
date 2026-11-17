# EXP-031: 공개 상품 존재 확인 비용

**상태:** PostgreSQL 18.6 로컬 합성 측정 · 운영 SLA 아님

## 목적과 범위

`GET /api/products/{productId}/reviews`와 `POST /api/buyers/{buyerRef}/wishlist/products/{productId}`는 상품이 **존재하고 공개 구매 가능한지**만 확인하면 됩니다. 변경 전에는 `ProductService.findProduct`를 호출해 상품 상세(상품 row + channel availability, SKU 변형 목록, 카테고리 배정)를 조립한 뒤 결과를 버렸습니다. 이 실험은 그 존재 확인 비용 차이만 측정합니다.

## 환경·데이터·workload

- PostgreSQL 18.6 disposable container, 단일 세션, image 기본 설정.
- 100,000 Product, Product당 SKU 5개(총 500,000), Product당 카테고리 배정 1건(총 100,000), Product당 channel availability 2행.
- 측정 대상: 상품 1건에 대한 (A) `SELECT EXISTS(...)` 단일 조회, (B) 기존 상세 조립에 해당하는 3개 조회.
- `EXPLAIN (ANALYZE, SUMMARY)` 1회 warm-cache 실행.

## 결과

| 경로 | round trip | SQL 실행 시간 |
| --- | ---: | ---: |
| A. 변경 후 `EXISTS` (product + ONLINE_STORE availability) | 1 | **0.064 ms** |
| B-1. 변경 전 상품 row + channel availability | 1 | 0.045 ms |
| B-2. 변경 전 SKU 변형 목록 | 1 | 0.200 ms |
| B-3. 변경 전 카테고리 배정 | 1 | 0.044 ms |
| **B 합계** | **3** | **0.289 ms** |

## 해석과 한계

- SQL 시간만으로 약 4.5배, round trip은 3회 → 1회로 줄었습니다. 절대값이 작은 이유는 두 경로 모두 인덱스 단일 조회이기 때문입니다.
- 이 표에 포함되지 않은 차이가 더 큽니다. 변경 전 경로는 JPA entity hydration, `@ElementCollection` channel map 로딩, SKU 응답 변환·정렬, 카테고리 정렬, 그리고 버려질 `ProductListingResponse` 조립을 수행합니다. 이 실험은 그 JVM/ORM 비용을 측정하지 않았으므로 배수로 주장하지 않습니다.
- 리뷰 목록 응답은 여전히 `product_review` 집계(`summary`)와 페이지 조회를 수행합니다. 이 변경은 그 앞단의 불필요한 상세 조립만 제거합니다.
- 로컬 단일 세션 측정이며 HTTP latency·동시 부하·운영 분포로 일반화하지 않습니다.

## 재현

`docs/03-experiments/EXP-031-public-product-existence-check.sql`.
