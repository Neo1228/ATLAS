# EXP-023: Coupon redemption cap 동시성 검증

- 상태: local 검증 완료; production capacity/SLO 주장 아님
- 관련 작업: [Issue #189](https://github.com/Neo1228/ATLAS/issues/189), [ADR-0025](../02-decisions/adr/ADR-0025-limited-coupon-redemption.md)

## 질문과 근거

- 같은 capped Coupon을 여러 checkout이 동시에 사용해도 `reserved_count + redeemed_count`가 `max_redemptions`를 넘지 않는가?
- PSP pending/unknown 상태에서 reservation을 유지하고, capture·definitive non-capture 결과에 따라 정확히 소비·반환하는가?
- 제한 없는 Coupon 대비 counter/ledger 쓰기 비용을 local에서 관찰할 수 있는가?

- [Meituan: InnoDB lock 범위](https://tech.meituan.com/2014/08/20/innodb-lock.html): indexed predicate를 사용해 lock 범위를 줄이는 방향.
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html): concurrent row update는 기존 row lock을 기다린 뒤 READ COMMITTED predicate를 재평가.
- [Shopify discount code limits](https://help.shopify.com/en/manual/discounts/discounts-faq): total-use와 per-customer cap은 서로 다른 정책. ATLAS는 global cap만 지원.

## 환경과 workload

| 항목 | 값 |
|---|---|
| Host | macOS 27.2 arm64 · 11 logical CPU · 18 GiB RAM |
| Container/runtime | Colima 0.10.3 · Docker Server 29.5.2 · JDK 25.0.5 |
| Stack | PostgreSQL 18.6 · k6 2.3.0 · Prometheus 3.5.0 · Grafana 12.1.1 · postgres-exporter 0.17.1 |
| Fixture | Seller 1곳 · SKU 1개 · 단가 1,000 KRW · seller-funded coupon 100 KRW |
| A/B | 32 VU × 4 iteration/VU = 128 distinct Buyer checkout/capture attempts; unlimited vs `maxRedemptions=128` |
| Saturation | 32 VU × 4 iteration/VU; `maxRedemptions=100`; 128 attempts expected |

- Unique Seller/SKU/Coupon per run, one isolated local Compose project. No PayPal/Live endpoint, cloud, Toxiproxy, or production traffic.
- Unlimited and cap=128 runs have equal successful checkout/capture counts. Capacity saturation uses a separate cap=100 run.

## K6 matched results

Times in milliseconds. Each matched run recorded 128 successful reservations and captures; no conflicts or unexpected errors.

| Run | Mode | Checkout p95 | Accepted session p95 | Measured HTTP req/s |
|---|---|---:|---:|---:|
| `exp189v2-unlimited-b` | Unlimited | 359.32 | 690.95 | 193.68 |
| `exp189v2-limited-full-a` | Cap 128 | 258.03 | 552.20 | 209.92 |
| `exp189v2-unlimited-c` | Unlimited | 137.12 | 316.05 | 501.77 |
| `exp189v2-limited-full-b` | Cap 128 | 327.00 | 570.30 | 286.32 |
| `exp189v2-limited-full-c` | Cap 128 | 150.85 | 330.65 | 366.86 |
| `exp189v2-unlimited-d` | Unlimited | 96.29 | 212.65 | 633.90 |

- Median checkout p95: unlimited **137.12 ms**, cap=128 **258.03 ms**; ranges overlap (96–359 ms vs 151–327 ms).
- Median accepted-session p95: unlimited **316.05 ms**, cap=128 **552.20 ms**; run-to-run spread remains high.
- RPS also varied widely. Short runs on a shared local host do not establish a stable performance penalty or SLO; no infrastructure tuning follows.

## Cap saturation and database state

- `exp189v2-cap100-a`: 128 attempts → 100 winners/captures, 28 expected HTTP 409 conflicts, 0 unexpected errors.
- PostgreSQL after the run: `max_redemptions=100`, `reserved_count=0`, `redeemed_count=100`; ledger `RESERVED=0`, `REDEEMED=100`, `RELEASED=0`.
- Cap=128 ledgers: 128 `REDEEMED` rows each; unlimited coupons retained `max_redemptions=NULL`, counters `0`, and no ledger rows.
- JUnit Testcontainers also verified a 32-way cap=10 race, transaction rollback after ledger-insert failure, pending/unknown hold, capture finalization, void confirmation, cancellation, expiry, refund non-replenishment, and multi-Seller atomic rollback.

## Observability and limits

- Local Compose Prometheus/Grafana/Postgres exporter used. Deadlock counter remained **0**.
- Prometheus scrape interval: **5s**. Most 128-attempt bursts completed between scrapes; sampled activity/lock counts are not peak concurrency.
- `pg_locks_count` reports lock objects, not wait duration. No row-wait percentile claim.
- No Tempo/Alloy: one local application→PostgreSQL path. No Toxiproxy: tested failure hypothesis is database contention, not network transport.
- Other local containers were running; no CPU/memory quota. Results are descriptive only.
- No raw K6 JSON or generated metrics files committed; this Markdown summary is the retained evidence.

## Conclusion

- Database cap invariant held across concurrent checkout and payment outcomes; no over-redemption or counter/ledger mismatch.
- PostgreSQL conditional update plus transactional ledger is sufficient for this local profile. Redis, distributed locks, counter sharding, or new dependencies are not justified.
- Further throughput conclusions need an isolated host and reliable row-wait sampling; not a blocker for functional correctness.
