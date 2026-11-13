# EXP-018 — 상품·정산 조회 계획 기준선

**상태:** PostgreSQL 18.6 격리 DB에서 합성 10k·100k row dataset의 `EXPLAIN ANALYZE` 완료

## 목적과 범위

실험 당시 main commit `f0dca97874079461aa5d4ebfe5e5b0289acbc4ad`의 상품 목록·하위 분류 조회와 정산 명세 집계·페이지 SQL을 격리된 PostgreSQL에서 실행. Query plan, actual row, shared buffer와 실행시간 기록; HTTP API latency·RPS/TPS·동시부하는 측정 범위 제외.

## 환경·데이터·workload

- macOS 27.2 arm64 · Colima Docker context: 4 CPU, 10,405,494,784 bytes memory
- PostgreSQL 18.6 image `postgres:18.6`, digest `sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81`
- Baseline SQL runs: `shared_buffers=128MB`, `work_mem=4MB`, `effective_cache_size=4GB`, `max_parallel_workers_per_gather=2`, `max_parallel_workers=8`, `random_page_cost=4`, `jit=on`
- Candidate-index A/B은 같은 PostgreSQL 18.6 image tag의 별도 fresh DB에서 시행. `-c` 설정 override 없이 image defaults 사용; 위 baseline 수치와 직접 합산 불가.
- Isolated `atlas_query_evidence` DB in disposable container; no named/persistent volume, container removed after run. 기본 `atlas` DB 접근 0건.
- Catalog: 100 Sellers, Seller별 1 root + 10 children, 10k 또는 100k Products, 모든 Product에 2 channel availability. 각 child별 10 또는 100 Products.
- Settlement: 같은 dataset size의 `BuyerCheckout`, Seller Order, checkout journal, allocation. Journal당 balanced settlement entry 2개. Seller당 100 또는 1,000 allocations.
- Search match: `broad` 10%, `needle` 1%. Seller1 narrow period 10초는 10/1,000 allocation row (1%); full period는 전체 Seller row.
- `ANALYZE` 뒤 query shape별 serial 3회. Warm cache 전제; shared read 0 대부분, narrow 첫 run에서 1 block. 동시 요청 0건.

## 결과

`EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)`의 execution time 중앙값과 3회 range. **SQL 실행시간(ms); HTTP latency 미측정.**

| Query | 10k | 100k |
|---|---:|---:|
| Catalog global, no keyword | 9.419 [9.272–9.563] | 221.708 [215.250–285.899] |
| Catalog global, `broad` (10%) | 7.003 [6.912–7.037] | 210.852 [204.358–239.222] |
| Catalog global, `needle` (1%) | 6.269 [6.180–6.290] | 209.526 [199.982–220.637] |
| Catalog Seller root subtree | 0.741 [0.671–0.849] | 4.985 [4.869–9.956] |
| Catalog Seller child subtree | 0.252 [0.231–0.301] | 0.640 [0.638–0.694] |
| Settlement totals, full period | 2.589 [2.572–2.631] | 21.108 [20.495–38.064] |
| Settlement page, full period | 2.059 [2.037–2.065] | 20.434 [18.510–23.028] |

100k Settlement narrow 10-second period (10 rows of Seller1's 1,000): totals 18.450 ms [17.397–34.476], page 17.148 ms [16.473–17.272]; page returned 10 rows.

### Query plan 결과

- Catalog global/keyword: Product와 `product_channel_availability` Seq Scan, then sort/keyset. 100k plan estimated 50k Product vs actual 100k; `broad` estimate 1,667 vs actual 10,000; `needle` estimate 1,667 vs actual 1,000. Buffer reads 0 in warm-cache runs.
- Seller subtree: `product_seller_idx`와 Seller/category index 사용. Root subtree는 100k dataset에서 1,000 matches; child는 100. Page limit 101이므로 root 응답은 101 rows.
- Settlement totals/page: 100k journal/allocation fixture에서 Seller1 결과는 1,000 rows. Plan에는 `settlement_journal`/`settlement_seller_allocation` sequential scans 포함. 10초 period는 10 rows만 매치했지만 execution median은 full-period 대비 비례 감소 미관측.
- Baseline은 새로운 index나 SQL rewrite의 이득 입증에 불충분. 후보 index A/B는 Disposable DB에서만 추가 측정.

### 인덱스 후보 비교

Candidate-index A/B: PostgreSQL 18.6 image defaults, 동일한 100k checkout-only fixture, serial warm-cache query 3회. 실제 API·동시성·cold-cache 비교 미수행.

| 후보 | 범위 | 중앙값 전 → 후 | 계획 변화 / 저장 공간 |
|---|---|---:|---|
| `settlement_seller_allocation(seller_id, journal_id)` | Full-period totals | 22.442 → 20.821 ms (−7.2%) | Allocation scan은 100k table scan에서 1k matches용 bitmap scan으로 변경. Journal scan 유지. 4,880 KiB |
| 동일 | Full-period page | 21.336 → 19.780 ms (−7.3%) | 동일. 4,880 KiB |
| `settlement_journal(recorded_at, journal_id)` | 10초 window, 10/100k rows | 16.817 → 0.077 ms (−99.5%) | Journal full scan에서 time-key index scan으로 변경. 3,976 KiB |
| 동일 | 10초 window page | 15.693 → 0.055 ms (−99.6%) | 10 rows 반환. 3,976 KiB |
| 두 후보 index | Full-period totals/page | 19.742 / 18.807 ms | Planner가 time-key index를 선택하지 않고 Journal sequential scan 유지 |

재현 가능한 run 및 plan-node 요약: [`index-candidate-ab.json`](results/exp-018/index-candidate-ab.json). 후보 index read A/B는 disposable DB에서만 수행. 10초/100k (0.01%)의 강한 selectivity에서 큰 차이 확인. Full-period allocation index 읽기 개선은 synthetic A/B 결과; write 비용은 아래 별도 측정.

**V28 migration:** `settlement_seller_allocation(seller_id, journal_id)` B-tree lookup index 추가.
- Read A/B: Seller 선택도 1% (`1,000/100,000`)에서 totals·page SQL 실행시간 합 43.778 → 40.601 ms (−3.177 ms, −7.3%). Allocation scan은 100k rows 전체 scan에서 1k matches bitmap scan으로 전환.
- Write A/B: allocation 100k건 단일 insert에서 arm median 240.331 [210.628–379.020] → 354.606 [296.626–465.204] ms. Median 차이 +114.275 ms (+47.5%); paired median 차이 +95.080 ms/100k (+0.9508 μs/allocation), 5개 paired delta 모두 양수.
- WAL: 38,600,443 → 51,360,745 bytes/100k (+12,760,302 bytes, +33.06%), 추가 127.6 bytes/allocation.
- Insert-grown index size 8,264 KiB (`8,462,336 / 1,024`) 대 read A/B의 bulk-built 4,880 KiB. 생성 순서·B-tree page fill 차이가 있는 별도 fixture 결과; 보편적인 row당 크기 추정에 미사용.
- 순수 SQL 실행시간 손익분기: `R × 3.177 ms > W × 0.0009508 ms`. `R`은 full-period statement read 수, `W`는 allocation insert 수이며 약 1 read / 3,341 writes의 임계값. WAL·저장 공간·FK·parent write·transaction commit·동시성 비용 제외.
- 실제 production R/W 비율과 application transaction의 증분 비용 미측정. Merge 판단은 synthetic read/write 결과 기반; production 성능 개선은 미검증.

### Allocation index write-cost A/B

[`EXP-018-allocation-index-write-cost.sql`](EXP-018-allocation-index-write-cost.sql)로 100k logged target rows에 단일 `INSERT SELECT` 실행. 두 arm 모두 primary key·seller unique·ordinal unique·check constraint 적용; candidate arm에만 `(seller_id, journal_id)` index 추가. 100 Sellers·각 1,000 allocations, UUIDv7 IDs, unlogged seed, 5회 serial 실행, PostgreSQL 18.6 image defaults. Foreign key와 parent-table write는 제외.

| Arm | 실행시간 median [range] ms | WAL bytes/100k | Candidate index size |
|---|---:|---:|---:|
| Baseline | 240.331 [210.628–379.020] | 38,600,443 | — |
| Candidate | 354.606 [296.626–465.204] | 51,360,745 | 8,462,336 bytes (8,264 KiB) |

반복별 원자료: [`allocation-index-write-cost.json`](results/exp-018/allocation-index-write-cost.json). 실행시간 범위 일부 겹침이나 5개 paired delta 모두 양수; 단일 synthetic table insert 결과이며 API latency나 전체 checkout/capture transaction 비용으로 일반화 금지. WAL 차이는 5회 모두 동일한 12,760,302 bytes.

**결론:** `recorded_at` index는 짧은 기간 조회의 후보 가설. V28 allocation index는 synthetic read/write tradeoff를 근거로 이 변경에 포함. 실제 production R/W 비율과 application-level 증분 비용 미측정으로 production performance gain 주장은 미해당. Post-merge 검증은 실제 R/W·WAL·저장 공간·cache·동시성 비용 확인.

## 해석과 한계

- SQL을 `EXPLAIN ANALYZE`로 직접 실행. Spring HTTP/JVM mapping, RPS/TPS, Hikari wait는 측정 범위 제외. Query plan output의 actual rows·execution time·buffers 판독 시나리오라 application observability profile 미기동.
- Flyway 적용 뒤 Spring Boot는 대기 상태. HTTP 요청 0건. Hikari idle connection의 환경상 잔존 가능성을 기록; 측정값은 psql plan 실행시간이며 API latency 미포함.
- 모든 query는 serial 상태에서 fresh disposable container의 warm cache로 실행. Cold cache, concurrent load, lock contention은 측정 범위 제외.
- Standard PostgreSQL `CREATE INDEX`의 production table build duration과 write blocking 영향 미측정; 실제 table size 기반 rollout window 확인 필요.
- Settlement fixture는 checkout allocation만 생성. Direct Campaign capture/refund rows 0건; 해당 branch는 query plan만 확인.
- 각 size/profile 3회. 100k global first run에서 `9.9ms` 수준의 최초 실행값과 200ms대 후속 실행값 관측. 중앙값과 범위 기록; 10× data의 성능 영향 원인 미확정.
- 10k/100k row counts는 synthetic test size. 더 큰 dataset 결과 미측정.
- Original read-plan A/B 실행 중 application source·schema·index·configuration·dependency 변경 0건. Follow-up write-cost SQL fixture는 별도 측정; V28 migration은 이번 변경에 포함, production deployment 상태 미확인.

## 재현 방법

- [`EXP-018-read-query-performance.sql`](EXP-018-read-query-performance.sql): 허용 dataset size 10,000/100,000; DB가 `atlas_query_evidence`가 아니거나 기존 experiment rows가 있으면 중단.
- [`EXP-018-read-query-performance-explain.sql`](EXP-018-read-query-performance-explain.sql): actual Catalog subtree 및 Settlement totals/page query plans.
- [`results/exp-018/`](results/exp-018/): per-run EXPLAIN JSON, 10k/100k 요약, narrow-period 요약.
- Historical comparison: [EXP-015](EXP-015-catalog-listing-performance.md), [EXP-016](EXP-016-buyer-checkout-comparison.md).

Seed script는 허용된 두 크기의 fresh V1–V20 PostgreSQL schema에서 검증. 10k → Product 10k / journal 10k / entry 20k; 100k → Product 100k / journal 100k / entry 200k. 모두 합성 fixture 검증.

### 재현 절차

각 dataset size는 별도 fresh DB에서 실행. Seed가 `atlas_query_evidence`가 아닌 DB 또는 이전 EXP-018 rows가 있으면 중단.

```sh
./gradlew --no-daemon :application-module:bootJar
docker run --rm -d --name atlas-query-evidence-pg \
  -e POSTGRES_DB=atlas_query_evidence -e POSTGRES_USER=atlas \
  -e POSTGRES_PASSWORD=atlas-local-only \
  -p 127.0.0.1:55432:5432 postgres:18.6
ATLAS_DB_URL=jdbc:postgresql://127.0.0.1:55432/atlas_query_evidence \
ATLAS_DB_USER=atlas ATLAS_DB_PASSWORD=atlas-local-only \
java -jar ATLAS-application/build/libs/application-module.jar
docker cp docs/03-experiments/EXP-018-read-query-performance.sql \
  atlas-query-evidence-pg:/tmp/seed.sql
docker exec atlas-query-evidence-pg psql -v ON_ERROR_STOP=1 \
  -v dataset_size=100000 -U atlas -d atlas_query_evidence -f /tmp/seed.sql
docker cp docs/03-experiments/EXP-018-read-query-performance-explain.sql \
  atlas-query-evidence-pg:/tmp/explain.sql
# Bind the variables documented at the top of explain.sql, then run with psql -f.
# Stop only atlas-query-evidence-pg after exporting result files.
```

애플리케이션은 Flyway 적용을 위해 별도 terminal에서 실행. Migration 완료 뒤 같은 disposable DB에서 seed/query 실행; HTTP traffic 0건.

`dataset_size=10000` and `100000` require separate fresh database instances. Use a unique container/port per parallel run. No Docker volume or default `atlas` database.

## 후속 검증 후보

1. Catalog global listing: 100k Product의 no-keyword median 221.708 ms, `broad` 210.852 ms(10%), `needle` 209.526 ms(1%). 세 plan 모두 Product·`product_channel_availability` Seq Scan과 sort. 1% 검색어는 1,000/100,000 row 일치·99,000 row 불일치이며, 현행 전체 스캔의 감소 가능성은 후보 index로 검증 필요.
   - Keyset order 후보: 현행 signed UUID sort-key와 같은 expression B-tree. No-keyword 첫 페이지·후속 cursor·seller/category 범위별 plan, 반환 순서 동등성, index size와 Product insert/update 비용 비교.
   - Keyword 후보: `lower(name)` 기반 `pg_trgm` GIN. 현행 `position()` predicate는 후보 index 활용 불가. Literal substring semantics 보존을 위한 `%`·`_` escape, 3자 미만 keyword fallback, 검색 결과 순서와 write cost 검증 필요.
2. Settlement 10초 기간에서 rows scanned vs returned, Planner estimate, `recorded_at`/Seller selectivity 측정.
3. V28 post-merge 검증: 실제 statement read 수 `R`·allocation insert 수 `W`와 application-level 증분 비용 측정. Synthetic time-only 임계값은 약 1 statement read / 3,341 allocation writes; WAL·저장 공간·cache·동시성·migration lock duration 별도 반영.
