# EXP-015: Catalog listing 로컬 baseline

**상태:** 10k·100k 실측 완료 · local-only · 운영 capacity 주장 없음

## 목적과 범위

- D-067 / ADR-0012 pagination contract의 합성 데이터 조회 특성 비교
- 사용자 수요, production RPS/TPS, SLO, 용량 산정 목적 아님
- 동일 PostgreSQL 18.6·JDK 25·4-vCPU Colima profile에서 10k 후 100k dataset 측정
- GitHub Issue [#132](https://github.com/Neo1228/ATLAS/issues/132)와 PR에 기록

## 데이터·시나리오

| Products | Sellers | Seller-SKU | `broad` matches | `needle` matches | Page limit |
|---:|---:|---:|---:|---:|---:|
| 10,000 | 100 | 20,000 | 1,000 (10%) | 100 (1%) | 100 |
| 100,000 | 100 | 200,000 | 10,000 (10%) | 1,000 (1%) | 100 |

- Seller별 동일 분배, 상품별 SKU 2개
- ID·검색 label deterministic. `needle`은 `broad`의 subset
- API request: global listing, Seller listing, `broad`, `needle`
- k6: 각 scenario당 100 serial requests × 4 scenarios = 400 requests/run
- VU 1, concurrency 없음. Dataset별 correctness warm-up 1회, 측정 3회
- 모든 run의 2,800 k6 checks 통과, HTTP failure rate 0
- 요청당 `limit=100`; cursor token이 마지막 item을 인코딩하는지 및 Java UUID natural order 검사
- Trend는 scenario별 HTTP request duration. 표의 값은 각 run p50/p95/p99의 **3회 median**; percentile을 합쳐 재계산한 pooled 값 아님

## 결과: HTTP request duration

단위: ms. 같은 synthetic dataset·환경에서의 bounded comparison일 뿐, 용량/사용자 수 예측이 아님.

각 dataset/run은 400 requests, 2,800/2,800 checks 통과, HTTP failure 0.

| Products | Scenario | p50 | p95 | p99 |
|---:|---|---:|---:|---:|
| 10,000 | Global | 5.169 | 5.862 | 7.558 |
| 10,000 | Seller | 2.592 | 3.139 | 3.775 |
| 10,000 | Search `broad` (10%) | 5.126 | 5.772 | 6.247 |
| 10,000 | Search `needle` (1%) | 4.750 | 5.349 | 5.566 |
| 100,000 | Global | 18.172 | 20.433 | 20.840 |
| 100,000 | Seller | 2.950 | 3.691 | 4.166 |
| 100,000 | Search `broad` (10%) | 17.751 | 19.699 | 22.424 |
| 100,000 | Search `needle` (1%) | 16.162 | 17.937 | 18.503 |

- Each raw k6 summary: [`results/exp-015/`](results/exp-015/); one JSON per dataset/run
- Cursor verification failed before measurement because k6 `b64decode` returns an `ArrayBuffer`; the test harness now converts those bytes before comparing the decoded UUIDs. Re-run after the fix: all 28 checks per request passed.
- Request rate from one VU is not interpreted as RPS capacity.

## Query plan evidence

`EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` executed from [`EXP-015-catalog-listing-performance-explain.sql`](EXP-015-catalog-listing-performance-explain.sql), matching `ProductRepository.findListingPage` predicate/order/limit. API limit 100 maps to DB fetch limit 101.

| Products | Scenario | Access / sort | Actual rows returned | Execution ms | Shared hit / read blocks |
|---:|---|---|---:|---:|---:|
| 10,000 | Global | Seq Scan + top-N heapsort (68 KB) | 101 | 4.442 | 185 / 0 |
| 10,000 | Seller | Bitmap index/heap scan + quicksort (50 KB) | 100 | 0.835 | 105 / 0 |
| 10,000 | `broad` | Seq Scan + top-N heapsort (69 KB) | 101 | 3.519 | 185 / 0 |
| 10,000 | `needle` | Seq Scan + quicksort (50 KB) | 100 | 2.610 | 185 / 0 |
| 100,000 | Global | Parallel Seq Scan, 1 worker + top-N heapsort (68 KB) | 101 | 20.652 | 2,038 / 0 |
| 100,000 | Seller | Bitmap index/heap scan + top-N heapsort (66 KB) | 101 | 1.744 | 1,014 / 0 |
| 100,000 | `broad` | Parallel Seq Scan, 1 worker + top-N heapsort (69 KB) | 101 | 17.810 | 2,038 / 0 |
| 100,000 | `needle` | Parallel Seq Scan, 1 worker + top-N heapsort (69 KB) | 101 | 15.244 | 2,038 / 0 |

- 모든 query의 shared read blocks `0`: cache-warm runs; cold-start latency 아님
- Sort는 메모리 사용, disk spill 없음
- Seller filter는 `product_seller_idx` 사용. Global·substring search는 expression sort key와 `position()` 조건으로 Seq Scan + sort
- 100k search의 planner estimate와 actual matches 차이: `broad` 10,000, `needle` 1,000 actual. `position(lower(name))` 선택도 추정의 후속 검토 근거; 단독으로 index 추가를 정당화하지 않음
- Raw plan JSON: [`results/exp-015/`](results/exp-015/)

## 실행 환경 및 재현성

- Source commit: `9659a3d763256bf62663f5f761fd39218f240d42`
- Application bootJar SHA-256: `e1ea12758aaae5978fbcbe21054e14b98a060685cde23cc6bbcaeb8fbc3d91b3`
- Host: macOS 27.2, arm64, 11 logical CPUs, 18 GiB RAM
- Colima `catxi-resilience`: 4 vCPU, 6 GiB RAM, 60 GiB disk; Docker Engine 29.5.2
- PostgreSQL 18.6. Settings: `shared_buffers=163848kB`, `work_mem=4096kB`, `effective_cache_size=5242888kB`, `max_parallel_workers_per_gather=2`, `max_parallel_workers=8`, `random_page_cost=4`, `jit=on`
- Java: Temurin/Homebrew 25.0.5.0.3
- Images: `postgres:18.6` `sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81`; `grafana/k6:2.3.0` `sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8`; `eclipse-temurin:25-jre` `sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0`
- Database: disposable container, database name `atlas_catalog_perf`, anonymous volume, private Docker network. Default `atlas` database/container/volume untouched.
- Seed time: 10k initial seed 0.54s; 100k expansion from 10k to 100k 5.13s. Expansion time includes upsert of the existing 10k rows.
- 100k run1 sample at ~1s: PostgreSQL 65.93% CPU / 212.8 MiB; app 0.28% CPU / 448.1 MiB. Single snapshot, not peak or continuous telemetry.
- Seed row-count assertions and `ANALYZE` completed before measurements. Exact database counts: 100k products, 100 sellers, 200k Seller-SKUs, 10k/1k broad/needle matches.

Re-run outline from repository root; use an isolated Docker context and unique names. Never point these commands at the existing `atlas` database.

```sh
export CTX=colima-catxi-resilience
export NET=atlas-catalog-perf-net
export DB=atlas-catalog-perf-db
export APP=atlas-catalog-perf-app

docker --context "$CTX" network create "$NET"
docker --context "$CTX" run -d --rm --name "$DB" --network "$NET" --network-alias "$DB" \
  -e POSTGRES_DB=atlas_catalog_perf -e POSTGRES_USER=atlas -e POSTGRES_PASSWORD=atlas-local-only postgres:18.6
export JAVA_HOME="<JDK 25 home>"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon :application-module:bootJar
docker --context "$CTX" run -d --rm --name "$APP" --network "$NET" --network-alias catalog-app \
  -v "$PWD/ATLAS-application/build/libs/application-module.jar:/app/atlas.jar:ro" \
  -e ATLAS_SERVER_ADDRESS=0.0.0.0 -e ATLAS_DB_URL="jdbc:postgresql://$DB:5432/atlas_catalog_perf" \
  -e ATLAS_DB_USER=atlas -e ATLAS_DB_PASSWORD=atlas-local-only eclipse-temurin:25-jre java -jar /app/atlas.jar
# Wait until Flyway has created the schema, then use a fresh DB for each exact size.
docker --context "$CTX" exec -i "$DB" psql -U atlas -d atlas_catalog_perf \
  -v ON_ERROR_STOP=1 -v product_count=10000 -f /dev/stdin \
  < docs/03-experiments/EXP-015-catalog-listing-performance.sql
# Set product_count=100000 only for the separate 100k run.
```

Run k6 on the private Docker network; capture each run JSON under `results/exp-015/`. Use `--summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)'`. Stop only the named experiment app/database containers and remove the experiment network; no volume-prune command.

## Observability decision

- No Prometheus/Grafana/Tempo/Alloy stack was added or used for this one-VU experiment.
- k6 per-scenario trends, PostgreSQL `EXPLAIN`/buffers, and a Docker CPU/memory sample answered the bounded questions. The runtime had no app tracing/pool instrumentation; full stack would not provide HTTP–DB correlation without additional instrumentation.
- Reconsider local-only Prometheus/Grafana/Tempo/Alloy when a future test needs to distinguish JVM saturation, pool wait, DB lock/IO wait, or trace-correlated application/SQL latency. If adopted, save dashboard export, query results/trace sample, scrape interval, resource allocations, retention, image digests, and test commit alongside that experiment's Markdown. No cloud export.

## 결론과 한계

- 10x product count raised global/search request p50 from roughly 5 ms to 16–18 ms in this one-VU local setup; seller-scoped reads remained around 3 ms. Search/global query plans scanned/sorted more rows; seller-scoped plans used the B-tree seller index.
- This is a local synthetic comparison on a warm cache, not a performance target or production capacity estimate. No index/migration or infrastructure change follows from this experiment alone.
- [D-067·ADR-0012](../02-decisions/adr/ADR-0012-catalog-listing-order.md) · [REF-115](../00-governance/reference-register.md) · [EXP-014](EXP-014-id-strategy-comparison.md)
