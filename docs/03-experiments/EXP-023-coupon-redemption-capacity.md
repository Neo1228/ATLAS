# EXP-023: Coupon redemption cap 동시성 검증

- 상태: 측정 결과 보존 · 현재 JWT API와 provider 동작을 반영하지 않은 기존 run
- 관련 작업: [Issue #189](https://github.com/Neo1228/ATLAS/issues/189), [ADR-0025](../02-decisions/adr/ADR-0025-limited-coupon-redemption.md)

> [!WARNING]
> 이 run의 k6 script는 authenticated Buyer/Seller 요청을 보내지 않았고 payment test adapter를 사용했습니다. 최신 API 재실행 전 JWT setup으로 갱신하고, PayPal Sandbox 결과와 혼합하지 마세요.

## 목적과 범위

- 같은 capped Coupon을 여러 checkout이 동시에 사용해도 `reserved_count + redeemed_count`가 `max_redemptions`를 넘지 않는가?
- PSP pending/unknown 상태에서 reservation을 유지하고, capture·definitive non-capture 결과에 따라 정확히 소비·반환하는가?
- 제한 없는 Coupon 대비 counter/ledger 쓰기 비용을 local에서 관찰할 수 있는가?

- [Meituan: InnoDB lock 범위](https://tech.meituan.com/2014/08/20/innodb-lock.html): indexed predicate를 사용해 lock 범위를 줄이는 방향.
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html): concurrent row update는 기존 row lock을 기다린 뒤 READ COMMITTED predicate를 재평가.
- [Shopify discount code limits](https://help.shopify.com/en/manual/discounts/discounts-faq): total-use와 per-customer cap은 서로 다른 정책. ATLAS는 global cap만 지원.

## 환경·데이터·workload

| 항목 | 값 |
|---|---|
| Host | macOS 27.2 arm64 · 11 logical CPU · 18 GiB RAM |
| Container/runtime | Colima 0.10.3 · Docker Server 29.5.2 · JDK 25.0.5 |
| Stack | PostgreSQL 18.6 · k6 2.3.0 · Prometheus 3.5.0 · Grafana 12.1.1 · postgres-exporter 0.17.1 |
| Fixture | Seller 1곳 · SKU 1개 · 단가 1,000 KRW · seller-funded coupon 100 KRW |
| A/B | 32 VU × 4 iteration/VU = 128 distinct Buyer checkout/capture attempts; unlimited vs `maxRedemptions=128` |
| Saturation | 32 VU × 4 iteration/VU; `maxRedemptions=100`; 128 attempts expected |

- 각 run은 별도 Compose project와 고유 Seller/SKU/Coupon 사용. Payment 완료 응답은 test adapter 제공; PayPal endpoint를 호출한 run이 아님.
Unlimited와 cap=128 profile은 각각 128건 checkout/capture 성공. Capacity saturation은 별도 cap=100 profile에서 측정.

## 재현 방법

- Script: [`load-tests/coupon-redemption.js`](../../load-tests/coupon-redemption.js).
- Profile: 32 VU × 4 iterations; unlimited, cap=128, saturation cap=100. Fixture price 1,000 KRW·discount 100 KRW.
- Current Seller/API requests require JWT; script update is prerequisite for another run.

## 결과

단위는 ms. 각 matched run에서 128건 reservation/capture 완료; conflict와 예상 밖 오류는 0건.

| Run | Mode | Checkout p95 | Accepted session p95 | Measured HTTP req/s |
|---|---|---:|---:|---:|
| `exp189v2-unlimited-b` | Unlimited | 359.32 | 690.95 | 193.68 |
| `exp189v2-limited-full-a` | Cap 128 | 258.03 | 552.20 | 209.92 |
| `exp189v2-unlimited-c` | Unlimited | 137.12 | 316.05 | 501.77 |
| `exp189v2-limited-full-b` | Cap 128 | 327.00 | 570.30 | 286.32 |
| `exp189v2-limited-full-c` | Cap 128 | 150.85 | 330.65 | 366.86 |
| `exp189v2-unlimited-d` | Unlimited | 96.29 | 212.65 | 633.90 |

- Checkout p95 median: unlimited **137.12 ms**, cap=128 **258.03 ms**. 범위가 겹쳐 안정적인 차이로 단정하지 않음 (96–359 ms vs 151–327 ms).
- Accepted-session p95 median: unlimited **316.05 ms**, cap=128 **552.20 ms**. Run 간 편차 큼.
- RPS 편차도 큼. 이 run만으로 성능 저하나 SLO를 확정하지 않으며 별도 infrastructure tuning 근거로 쓰지 않음.

### 한도 포화와 database state

- `exp189v2-cap100-a`: 128 attempts → 100 winners/captures, 28 expected HTTP 409 conflicts, 0 unexpected errors.
- PostgreSQL after the run: `max_redemptions=100`, `reserved_count=0`, `redeemed_count=100`; ledger `RESERVED=0`, `REDEEMED=100`, `RELEASED=0`.
- Cap=128 ledgers: 128 `REDEEMED` rows each; unlimited coupons retained `max_redemptions=NULL`, counters `0`, and no ledger rows.
- JUnit Testcontainers also verified a 32-way cap=10 race, transaction rollback after ledger-insert failure, pending/unknown hold, capture finalization, void confirmation, cancellation, expiry, refund non-replenishment, and multi-Seller atomic rollback.

## 해석과 한계

### 관측 지표
- Prometheus scrape interval은 **5초**. 128-attempt burst 대부분이 다음 scrape 전에 완료되어 sampled metric은 peak concurrency를 보여 주지 않음.
- `pg_locks_count`는 lock object 수이며 wait duration이 아님. Row-wait percentile은 측정하지 않음.
- Tempo/Alloy는 한 application→PostgreSQL path라 사용하지 않음. Toxiproxy는 failure hypothesis가 DB contention이어서 사용하지 않음.
- 다른 local container도 함께 실행 중이었고 CPU/memory quota는 없음. 결과는 해당 환경의 관측값.
- Raw k6 JSON과 generated metric file은 커밋하지 않음. 이 Markdown summary가 보존된 evidence.

### 결론

- Concurrent checkout/payment에서 `reserved_count + redeemed_count <= max_redemptions` 유지. Cap 100 run은 100 capture와 28 expected 409를 기록; counter와 ledger가 일치.
- PostgreSQL conditional update와 transactional ledger가 이 test scenario를 처리. 다른 lock/cache 구조와 비교한 근거는 없음.
- Throughput conclusion에는 isolated host와 row-wait metrics 필요. 이는 기능상 cap invariant와 별도 검증 항목.
