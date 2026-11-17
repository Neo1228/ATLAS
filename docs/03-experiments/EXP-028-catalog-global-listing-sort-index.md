# EXP-028: global catalog listing UUID sort-key index

**상태:** PostgreSQL 18.6 local synthetic data 측정 완료 · 운영 SLA 아님

## 목적과 범위

Buyer `GET /api/products`의 global first-page query는 `ProductRepository.findListingPageByScope`에서 seller/product UUID를 sign-bit-transformed hex sort key로 만들고 `ORDER BY seller_sort_key, product_sort_key LIMIT 101`을 수행합니다. 기존 `product_seller_idx (seller_id, product_id)`는 이 global expression order와 맞지 않습니다.

EXP-015의 100k Global plan은 parallel sequential scan + top-N heapsort, 101행 반환 20.652 ms / 2,038 shared-hit blocks였습니다. 이번 실험은 같은 ordering expression에 맞춘 B-tree가 실제 global listing filter와 keyword workloads에서 plan·시간을 바꾸는지 봅니다.

## 환경·데이터·workload

- macOS arm64 · Colima disposable PostgreSQL 18.6 container, 단일 세션, image defaults.
- 100,000 Product, 100 Seller; 모든 Product `ACTIVE`, 모두 `ONLINE_STORE` available, Product당 Seller-SKU 1개.
- Keyword distribution: `broad` 10%, `needle` 1%. No category assignment; no price bounds; first page limit 101.
- Exact repository-shaped CTE, UUID expression sort keys, online-channel `EXISTS`, substring `position()` filter, and `LIMIT`.
- Baseline plan/timing: index absent. Then create candidate expression index, `ANALYZE`, repeat the same three queries.
- 3 serial repetitions per query after seed; report median and [min–max]. Shared cache warm after first run. Concurrent load 0.

## 결과

| Workload | Before: Seq Scan + sort | After: expression index | Median reduction |
|---|---:|---:|---:|
| Global, no keyword | 277.666 ms [253.674–289.980] | 35.717 ms [25.219–45.695] | 7.8× |
| `broad` (10%) | 241.486 ms [229.246–307.392] | 31.816 ms [25.256–36.940] | 7.6× |
| `needle` (1%) | 238.280 ms [223.249–256.797] | 45.883 ms [44.241–50.776] | 5.2× |

The no-keyword plan changes from `Parallel Seq Scan on product` + top-N `Sort` to `Index Scan using product_listing_sort_idx`; the sort disappears. The keyword cases also use ordered access but still filter candidates through `position()`.

The index occupied **9,352 kB** for 100k synthetic products. This is storage only; insert/update maintenance cost was not measured.

## Interpretation and limits

- The exact two-key B-tree matches the established ordering contract; no order/cursor semantics change.
- Seller-scoped listing remains supported by the existing `product_seller_idx`; V40 adds a separate global-order path.
- Keyword search still scans index entries until enough matches satisfy the substring predicate. The measured 1% case is slower than unfiltered global but materially better than the pre-index scan+sort.
- Local synthetic SQL execution time is not HTTP latency. The workload is warm-cache, single-session, uniform seller distribution; it does not represent production data skew, concurrent traffic, or write throughput.

## Reproduction

`docs/03-experiments/EXP-028-catalog-global-listing-sort-index.sql` creates the synthetic data, runs the repository-shaped query, creates the candidate index, and reruns it.
