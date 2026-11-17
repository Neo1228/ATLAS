# ADR-0040: Global catalog listing의 UUID sort-key index

- 상태: Proposed
- 날짜: 2026-11-17
- 추적: [Issue #305](https://github.com/Neo1228/ATLAS/issues/305)

## 맥락

Buyer `GET /api/products`의 global listing은 `ProductRepository.findListingPageByScope`에서 `(seller_id, product_id)`를 Java UUID 자연 정렬 순서와 일치하도록 변환합니다. Keyset cursor와 `ORDER BY`는 다음 두 표현식을 사용합니다.

- seller key: `encode(set_byte(set_byte(uuid_send(seller_id), ...), ...), 'hex') COLLATE "C"`
- product key: 같은 표현식을 `product_id`에 적용

기존 `product_seller_idx (seller_id, product_id)`는 Seller equality filter에는 맞지만, unconstrained global seller scope에서 이 computed order를 제공하지 못합니다. EXP-015의 100k Global plan은 parallel sequential scan + top-N heapsort였고 101행 반환에 20.652 ms / 2,038 shared hits였습니다.

EXP-015의 100k p95는 global 20.433 ms, broad 19.699 ms, needle 17.937 ms입니다. EXP-018 fuller listing query는 warm-cache 100k dataset에서 3-branch catalog plans median을 no keyword 221.708 ms, broad 210.852 ms, needle 209.526 ms로 기록했습니다. 이 값들은 이전 plan의 baseline이며 HTTP SLO가 아닙니다.

## 결정

V40에서 `product_listing_sort_idx` B-tree를 `product`에 생성합니다. Index key는 query의 `seller_sort_key`, `product_sort_key` 표현식과 **순서·collation까지 동일**합니다.

이 선택은 query의 order/cursor 계약을 그대로 두고 PostgreSQL이 ordered index scan으로 `LIMIT`에 도달하도록 합니다. Seller-scoped listing은 V7 `product_seller_idx`를 계속 사용합니다. Keyword, category, channel, price semantics는 바꾸지 않습니다.

## 대안과 절충

- **Raw `(seller_id, product_id)` index만 사용:** existing `product_seller_idx`가 이미 있으나 transformed Java UUID order와 표현식이 다르므로 global sort에 직접 쓸 수 없습니다.
- **UUID order를 바꿔 raw UUID B-tree 사용:** cursor ordering contract가 바뀌고 기존 clients/cursors·tests에 영향을 주므로 선택하지 않습니다.
- **Materialized/generated sort-key columns:** 쓰기 경로와 schema를 넓히고 key derivation을 DB에 복제해야 합니다. Exact expression index로 같은 결과를 낮은 변경량으로 얻으므로 우선 선택하지 않습니다.
- **Substring search index:** `position(:keyword in lower(name))`는 trigram/substring access path가 아니며, 별도 search design·write cost가 필요합니다. 이번 변경은 정렬 bottleneck만 해결합니다.

Expression index는 product insert 및 seller/product key 변경 시 유지비와 cache/storage 비용을 추가합니다. 100k synthetic products에서 측정된 index size는 9,352 kB였습니다. Insert/write amplification은 별도로 측정하지 않았으므로 운영 write 비용 수치를 주장하지 않습니다. Migration은 regular `CREATE INDEX`이며 대형 production table에서는 build/lock 영향과 배포 시간을 사전에 평가해야 합니다.

## 결과와 한계

EXP-028의 100k product synthetic workload, 단일 PostgreSQL 18.6 session, warm cache, `LIMIT 101`에서 exact-order index 전후 median:

| workload | index 전 | index 후 | 개선 |
| --- | ---: | ---: | ---: |
| global, no keyword | 277.666 ms [253.674–289.980] | 35.717 ms [25.219–45.695] | 7.8× |
| broad substring (10%) | 241.486 ms [229.246–307.392] | 31.816 ms [25.256–36.940] | 7.6× |
| needle substring (1%) | 238.280 ms [223.249–256.797] | 45.883 ms [44.241–50.776] | 5.2× |

- No-keyword first page plan changes from parallel seq scan + top-N sort to `Index Scan using product_listing_sort_idx` with no sort.
- Query still filters keyword candidates by substring and may visit more ordered index entries for selective terms; this is not a full-text index.
- Measurements are SQL-only, synthetic, cache-warm, single-session; no HTTP latency, concurrent workload, production skew, or write throughput was measured. Results must not be read as production capacity/SLO.

## 후속 수정: seller-scoped 경로 (#307)

V40 적용 직후 seller-scoped listing이 같은 query를 공유한다는 점이 문제가 되었습니다. seller scope에서는 `seller_sort_key`가 상수인데도 `ORDER BY p.seller_sort_key, p.product_sort_key`가 그대로 남아, leading key가 `seller_sort_key`인 V40 index를 `seller_id = ?` equality와 결합할 수 없었습니다.

로컬 합성 측정(100 seller · 100k Product · warm cache · first page):

| seller-scoped first page | plan | time |
| --- | --- | ---: |
| V40 이전 | `product_seller_idx` + Sort | 2.469 ms |
| V40 적용 후 | `product_listing_sort_idx` ordered scan | 21.008 ms |
| 조건부 `ORDER BY` 적용 | `product_seller_idx` + Sort | 1.929 ms |

수정은 seller scope에서 상수인 seller key를 `ORDER BY`에서 제거하는 것입니다.

```sql
ORDER BY CASE WHEN CAST(:sellerId AS uuid) IS NOT NULL THEN NULL ELSE p.seller_sort_key END,
         p.product_sort_key
```

seller key가 같은 값이므로 정렬 결과는 완전히 동일하고(row-by-row 비교 mismatch 0), global 경로는 계속 V40 index를 사용합니다. 이 수정으로 V40의 이점은 global/cross-seller listing에 한정되며, seller 화면은 기존 access path를 유지합니다.

## 검증

- V40 migration integration test: 100k products + online availability, `EXPLAIN` first and cursor pages; both use `product_listing_sort_idx` and avoid `Sort`/`Seq Scan on product`.
- seller-scope plan 회귀 테스트: seller 경로가 `product_listing_sort_idx`/`Seq Scan`을 쓰지 않고, 조건부 `ORDER BY`가 기존 정렬과 동일함을 검증.
- Existing `CatalogIntegrationTest` and catalog cursor/filter tests verify the returned page order, cursor continuation, price bounds, and channel visibility are unchanged.
- Reproduction SQL: [EXP-028](../../03-experiments/EXP-028-catalog-global-listing-sort-index.md).
