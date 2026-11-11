# EXP-015: 상품 목록 조회 성능 기준선

**상태:** 10k·100k synthetic dataset 실측 완료 · PostgreSQL 18.6 · 4-vCPU Colima profile

## 목적과 범위

- D-067/ADR-0012의 pagination·listing-order 계약에서 Seller/global query plan을 10k·100k synthetic Product rows로 비교.
- 동일 PostgreSQL 18.6·JDK 25·4-vCPU Colima profile에서 10k 후 100k dataset 측정
- GitHub Issue [#132](https://github.com/Neo1228/ATLAS/issues/132)와 PR에 기록

### 데이터 형태와 조회 경로

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

## 환경·데이터·workload

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

## 재현 방법

> [!WARNING]
> SQL은 격리된 `atlas_catalog_perf` database에서 실행. 기존 `atlas` database에 연결하거나 schema를 지우지 않으며, 명령의 Docker context·container name을 실험별로 고유하게 유지.

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

## 결과

단위는 ms. 같은 synthetic dataset·환경에서 각 run은 400 request. Query timing과 row/plan 동작 비교.

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

### Query plan 결과

`EXP-015-catalog-listing-performance-explain.sql`로 `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` 실행. Predicate·order는 `ProductRepository.findListingPage`와 동일; API limit 100은 DB fetch limit 101.

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

### 관측성 및 수집 지표

- 이 one-VU run에는 Prometheus/Grafana/Tempo/Alloy를 사용하지 않음.
- k6 per-scenario metric, PostgreSQL `EXPLAIN`/buffer와 Docker CPU/RAM snapshot으로 제한된 query 가설 확인. Application tracing/pool instrumentation이 없어 HTTP와 SQL latency correlation은 분리할 수 없음.
- JVM saturation, pool wait, DB lock/IO wait 또는 trace-correlated latency가 필요한 후속 test에는 Prometheus/Grafana/Tempo/Alloy 검토. Dashboard, query/trace output, scrape interval, resource limits, retention, image digest와 commit을 실험 기록에 보관.

## 해석과 한계

- Warm-cache, one-VU synthetic run에서 Product 수 10배 증가 시 global/search p50은 약 5ms에서 16–18ms로 증가; Seller-scoped read는 약 3ms 유지. Global/search plan은 더 많은 row를 scan/sort하고 Seller filter는 B-tree 사용. 이 결과만으로 index/migration 변경하지 않음.
- [D-067·ADR-0012](../02-decisions/adr/ADR-0012-catalog-listing-order.md) · [REF-115](../00-governance/reference-register.md) · [EXP-014](EXP-014-id-strategy-comparison.md)
