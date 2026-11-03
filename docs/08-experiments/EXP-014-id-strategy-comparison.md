# EXP-014 — PostgreSQL ID strategy comparison

**상태:** Measured (2026-11-03); owner 선택에 따라 UUIDv7 primary IDs adopted. Testcontainers/JPA integration verified UUID version 7 for generated Seller/Campaign/Ticket/Order IDs and PaymentAttempt.order_id. This is a local database/index comparison, not a production load or capacity result.

## Question and controlled workload

Compare (A) UUIDv4 primary/public ID, (B) UUIDv7 primary/public ID, and (C) BIGINT identity primary key plus unique UUIDv7 public ID. PostgreSQL 18 provides the UUID generators. Each design receives the same 100,000 parent records (256-byte payload) and 200,000 children (64-byte payload), with the same deterministic child-reference distribution. Each design has the same child-FK index on `parent_id`; child foreign keys target the design's primary key.

The UUIDv4 values, UUIDv7 values, payloads, and BIGINT key values are prepared once in the shared seed table before timed inserts. BIGINT values use `seed.ord` (1..100,000), inserted with `OVERRIDING SYSTEM VALUE` into the `GENERATED ALWAYS AS IDENTITY` column. Thus UUID and identity-sequence generation costs are explicitly out of scope; primary-key storage/index work and C's UUIDv7 public-ID unique-index writes are in scope. The three logical datasets remain aligned by seed ordinal.

The harness retains all five randomized-order insertion runs per strategy, reporting parent and child insert milliseconds and derived rows/s. It emits per-strategy medians for parent insert ms, child insert ms, and combined rows/s while preserving raw runs. It measures the set-based query over 2,000 sampled public IDs, plus associated child rows, in five randomized-order runs; it retains raw elapsed times/returned rows/rates and outputs median query ms and rows/s. The 2,000 ordinals satisfy `ord % 50 = 3`, are shuffled once into a shared sample table, and are reused by all three variants. `EXPLAIN (ANALYZE, BUFFERS, TIMING)` reports the same set-based public-ID lookup plans and child-FK join plans. After the final insertion repetition, the harness runs `ANALYZE` on each of the six parent/child tables and reports heap/index/total bytes for only those relations. FK-index/table setup, seed generation, and `ANALYZE` are outside insert timings; index maintenance and FK checks are inside the timed INSERT statements.

## Environment to record at execution

- OS/host: macOS 27.2, arm64, 11 hardware CPUs, 18 GiB RAM.
- Docker Engine 29.5.2: 4 CPUs, 9.69 GiB. Postgres container에는 별도 resource limit을 설정하지 않음. 다른 `data-lake-lab` containers도 실행 중; activity는 확인하지 않아 CPU/IO contention 여부 미확정.
- PostgreSQL: 18.6 (Debian 18.6-1.pgdg13+2), `shared_buffers=128MB`, `effective_cache_size=4GB`, `max_parallel_workers_per_gather=2`, `synchronous_commit=on`, `track_io_timing=off`.
- Database: 격리된 `atlas_id_bench`. 기존 `atlas` DB에 연결·변경하지 않음. SQL 전체 실행 약 30초.
- Dataset: 전략별 100,000 parent × 256-byte payload + 200,000 child × 64-byte payload; insertion 5회, set-based lookup/join 5회.

## Exact local commands

Use the intended local PostgreSQL 18 instance. These commands create a separate database and do not connect to, drop, or modify `atlas`. If `createdb` reports that the name already exists, stop and verify it is a disposable experiment database; do not reuse a database containing valued data. From the repository root:

```sh
createdb --template=template0 --encoding=UTF8 atlas_id_bench
psql -X -v ON_ERROR_STOP=1 -d atlas_id_bench \
  -f docs/08-experiments/EXP-014-id-strategy-comparison.sql
```

The SQL immediately fails unless `current_database()` is exactly `atlas_id_bench`. It drops/recreates only `exp014_id_strategy` within that database. The schema is retained after the run for inspection; optional cleanup is restricted to that schema and only after confirming the database name. Record CPU, RAM, OS, storage, PostgreSQL launch/configuration details, and whether other workloads shared the host, in addition to the SQL output.

## 결과 — 실제 측정

### INSERT 측정

각 셀의 괄호는 5회 raw timing; throughput은 combined insert rows/s. 시간은 generation/setup/commit 제외, index write와 FK 확인 포함.

| 전략 | Parent insert ms (5회; p50) | Child insert ms (5회; p50) | Combined rows/s (5회; p50) |
|---|---|---|---|
| UUIDv4 PK | 240.37, 212.64, 314.33, 210.22, 203.38; **212.64** | 1385.83, 1326.48, 1739.80, 1499.05, 1386.34; **1450.46** | 184479.5, 194917.1, 146047.2, 175936.7, 188712.8; **184479.5** |
| UUIDv7 PK | 191.31, 156.35, 263.94, 269.21, 211.49; **211.49** | 1609.83, 1389.81, 1972.41, 1365.64, 1321.92; **1389.81** | 166561.2, 194030.0, 134146.9, 183503.1, 195642.1; **183503.1** |
| BIGINT PK + UUIDv7 public ID | 302.58, 298.30, 385.07, 227.06, 248.72; **298.30** | 1379.64, 1517.54, 1531.44, 1211.70, 1873.28; **1517.54** | 178335.3, 165213.2, 156534.9, 208512.3, 141375.8; **165213.2** |

### Storage

| 전략 | Parent heap / indexes / total (bytes) | Child heap / indexes / total (bytes) | Combined total (bytes) |
|---|---|---|---:|
| UUIDv4 PK | 31,514,624 / 4,407,296 / 35,954,688 | 25,206,784 / 10,797,056 / 36,036,608 | 71,991,296 |
| UUIDv7 PK | 31,514,624 / 3,178,496 / 34,725,888 | 25,206,784 / 10,747,904 / 35,987,456 | 70,713,344 |
| BIGINT PK + UUIDv7 public ID | 32,768,000 / 5,439,488 / 38,240,256 | 23,412,736 / 9,347,072 / 32,792,576 | 71,032,832 |

### Set-based public-ID lookup + child join

| 전략 | 5회 raw ms | p50 ms | p50 rows/s |
|---|---|---:|---:|
| UUIDv4 PK | 25.25, 23.28, 20.27, 11.98, 13.37 | 20.27 | 197,345.7 |
| UUIDv7 PK | 29.56, 17.80, 11.11, 16.26, 18.69 | 17.80 | 224,681.2 |
| BIGINT PK + UUIDv7 public ID | 23.19, 20.12, 11.25, 18.77, 17.84 | 18.77 | 213,094.7 |

EXPLAIN plans for the set-based lookup and FK join are included in the execution output. The planner used parent-table sequential scans for the 2,000-key set; these timings do **not** measure per-key point lookup latency.

## 해석과 한계

- UUIDv7 parent PK index는 이 run에서 UUIDv4보다 **27.9% 작음**. Parent insert median은 211.49 ms vs 212.64 ms로 사실상 비슷. Combined rows/s median도 UUIDv4 184,479.5 vs UUIDv7 183,503.1로 차이가 거의 없음.
- BIGINT+UUIDv7 public ID는 child FK index를 더 작게 만들었지만 parent PK와 public-ID unique index가 모두 필요. Combined storage는 UUIDv4보다 1.3% 작고 UUIDv7보다 0.5% 큼; combined insert median throughput도 두 UUID PK보다 낮음.
- Row count 100k, 단일 session, 비동시 workload, commit·ID 생성 비용 제외. 5회라도 raw timing 편차가 큼. 별도 Docker workload 실행도 resource contention 여부를 불확실하게 함.
- UUIDv7은 이 workload에서 parent PK index 크기 이점을 보였고 insert 결과는 UUIDv4와 거의 같았다. BIGINT hybrid는 이 측정에서 명확한 총 storage/throughput 우위를 보이지 않았다. 이 결과와 제약을 검토해 소유자는 UUIDv7 PK/public UUID ID를 선택했으며, 생산 환경의 성능을 입증하거나 자동으로 보장하지 않는다.
- 미측정: application/ORM ID 생성 비용, per-key point lookup distribution, 동시 부하, larger working set, sustained WAL/checkpoint, actual ATLAS entity/JPA lifecycle.
- 핵심 참고: [REF-104](../00-governance/reference-register.md)·[REF-105](../00-governance/reference-register.md)는 PostgreSQL/Hibernate UUIDv7 생성 API와 계약을 설명하며 통합 검증 결과가 아니다. [REF-106](../00-governance/reference-register.md)은 다른 workload의 practitioner 측정, [REF-107](../00-governance/reference-register.md)은 설계 trade-off 자료다. 외부 결과는 ATLAS 측정값을 대신하지 않으며 일반화하지 않음.
