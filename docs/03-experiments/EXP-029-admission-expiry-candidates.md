# EXP-029: admission expiry keyset candidate index

**상태:** PostgreSQL 18.6 local synthetic query 측정 · 운영 SLA 아님

## 목적과 범위

Admission expiry worker는 due ticket rows에서 seller-SKU를 읽고 pair별 bulk expiry/promotion을 수행합니다. Candidate query는 `(expires_at, ticket_id)` keyset으로 page를 이어갑니다. 이 실험은 기존 timestamp-only expiry index와 V41 composite index에서 first/cursor candidate page의 access path를 비교합니다. Java service loop, counter lock, bulk update, promotion, concurrent workers는 측정 범위 제외입니다.

## 환경·데이터·workload

- PostgreSQL 18.6 disposable container, single session, image defaults.
- `admission_ticket` 1,000,000 rows: 500,000 due (`WAITING`), 500,000 future expiry.
- Seller 100명 × SKU 10,000 조합; due/future ticket 분포 균등.
- Candidate page: `status IN ('WAITING','CHECKOUT_ACTIVE')`, `expires_at <= CURRENT_TIMESTAMP`, `ORDER BY expires_at,ticket_id`, `LIMIT 200`.
- Cursor page는 첫 page의 마지막 `(expires_at,ticket_id)` 이후 tuple comparison을 사용.

## 결과

| Candidate page | V1 timestamp-only index | V41 `(expires_at,ticket_id)` index |
|---|---:|---:|
| First page, 200 rows | 176.840 ms · Parallel Index Scan + Incremental Sort | 2.369 ms · ordered Index Scan |
| Cursor page, 200 rows | 188.910 ms · Parallel Index Scan + Incremental Sort | 2.585 ms · ordered Index Scan |

V41 index는 **39 MB** for 1,000,000 active rows (including future-expiry rows in the partial index). It replaces the existing V1 expiry index, not an additional index. Candidate query selects seller/SKU from heap for the bounded page; this keeps the index smaller than including those columns.

## Interpretation and limits

- The old index ordered only by `expires_at`; equal-expiry rows needed `Incremental Sort` by `ticket_id`. The cursor condition also caused the plan to scan/sort a large due range on each page.
- The composite keyset index orders both filter/cursor columns and lets PostgreSQL stop after 200 rows. This directly enables cursor progression across ticks, including busy pairs.
- The service deduplicates candidate rows by seller-SKU within each window and advances its process-local cursor to the last consumed ticket. Restart resets the cursor; processing is idempotent, so it starts safely from the beginning.
- Results are local warm-cache SQL timings, single session, synthetic distribution. Index write amplification, high-concurrency lock waits, production skew, and HTTP latency were not measured.

## Reproduction

`docs/03-experiments/EXP-029-admission-expiry-candidates.sql` builds the synthetic table, applies V41, and runs first/cursor `EXPLAIN (ANALYZE, BUFFERS)` queries.
