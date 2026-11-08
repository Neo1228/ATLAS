# EXP-020 — 정산 인덱스 선택도·비용 A/B

**상태:** 측정 완료 · production index 채택 없음 · local-only synthetic experiment

## 질문과 결론

`settlement_journal(recorded_at, journal_id)` 후보 B-tree가 Seller statement의 좁은·넓은 기간 조회와 write cost에 미치는 영향 확인.

- 10초–1,000초 window: SQL plan과 serial API median 개선
- 전체 기간: planner가 후보 index 미선택. Totals/API median은 오히려 증가
- Index storage/WAL 증가 관측
- **결론:** 선택도 높은 기간 조회의 후보 가설. Production migration 근거로 확정하지 않음

## 환경

- 기준 commit `000394067ebd99e7a24364afc3e860b1e0daf5f2` (local evidence branch; main base `f0dca978`)
- macOS 27.2 arm64 · Docker default context → Colima · 4 CPU · memory 10,405,494,784 bytes · overlayfs
- PostgreSQL `18.6`, image digest `sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81`
- `shared_buffers=128MB`, `work_mem=4MB`, `effective_cache_size=4GB`, `max_parallel_workers_per_gather=2`, `max_parallel_workers=8`, `random_page_cost=4`, `jit=on`
- Arm별 새 `atlas_query_evidence` DB와 Docker network. Host port 5432, 기본 `atlas` DB, named volume 미사용. Experiment containers/network만 종료
- Application: 현행 bootJar · `observability` profile · Java 25 runtime image
- Metrics: postgres-exporter 0.17.1 · Prometheus 3.5.0 · Grafana 12.1.1 · 5초 scrape
- API probe: k6 2.3.0 pinned image

## 대상 경로와 Synthetic data

- API: [`SellerSettlementStatementController`](../../ATLAS-application/src/main/java/com/atlas/application/controller/seller/SellerSettlementStatementController.java) → [`SettlementServiceImpl.getSellerStatement`](../../ATLAS-settlement/src/main/java/com/atlas/settlement/service/impl/SettlementServiceImpl.java) → [`SettlementJournalRepository`](../../ATLAS-settlement/src/main/java/com/atlas/settlement/repository/SettlementJournalRepository.java)
- 기존 의미/페이지 검증: [`SettlementStatementIntegrationTest`](../../ATLAS-application/src/test/java/com/atlas/settlement/SettlementStatementIntegrationTest.java)
- EXP-018 100k fixture: Seller 100명 · checkout journal 100,000건 · allocation 100,000건 · balanced entry 200,000건. 금액 100 KRW, `recorded_at` 초당 1건. Seller 1의 allocation 1,000건
- 추가 branch fixture: Seller 1 direct capture 150 KRW + refund reversal 50 KRW, full-period에만 포함. Query branch 검증용 synthetic row; full PSP/refund workflow나 빈도 가정 아님
- Candidate arm에만 `CREATE INDEX exp020_candidate_recorded_journal_idx ON settlement_journal (recorded_at, journal_id)` 생성

## SQL plan 결과

`EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` · 각 range/query 3회 · median (ms). Page limit 100.

| Half-open range | Seller 1 행 | Totals baseline → candidate | Page baseline → candidate | Plan 관찰 |
|---|---:|---:|---:|---|
| 10초 | 10 | 8.658 → 0.299 (−96.5%) | 9.437 → 0.094 (−99.0%) | Candidate timestamp index 사용 · Page Sort 유지 |
| 100초 | 100 | 7.612 → 0.669 (−91.2%) | 6.637 → 0.239 (−96.4%) | Candidate timestamp index 사용 · Page Sort 유지 |
| 1,000초 | 1,000 | 11.873 → 5.237 (−55.9%) | 9.761 → 3.835 (−60.7%) | Journal index 사용 · allocation 100k Seq Scan 잔존 |
| 전체 | 1,002 | 13.670 → 16.236 (+18.8%) | 15.743 → 12.696 (−19.4%) | Candidate index 미선택 · Journal/allocation Seq Scan 및 Sort 유지 |

- Full-period Page median 감소는 candidate index 미사용 plan에서 관측. Index 이득으로 해석하지 않음
- Baseline narrow plan은 Journal Seq Scan으로 100k 중 대다수 row 제거. Candidate narrow plan은 timestamp index 사용
- 1,000초 구간에도 allocation table scan 잔존. Page `UNION ALL` 결과의 최종 Sort는 모든 구간에서 유지
- Shared read blocks: baseline 최대 0 · candidate 최대 4

## API 결과

각 arm/window 1 VU × 20 serial requests · 0.5초 pacing. HTTP 200 · response assertions 통과 · latency threshold 없음. Median과 범위(ms).

| Range | Baseline | Candidate | Median 변화 |
|---|---:|---:|---:|
| 10초 | 25.711 `[19.401–123.404]` | 11.461 `[5.776–55.946]` | −55.4% |
| 100초 | 27.172 `[18.814–50.278]` | 11.426 `[6.332–20.330]` | −57.9% |
| 1,000초 | 33.177 `[23.384–42.788]` | 22.511 `[13.621–30.447]` | −32.1% |
| 전체 | 33.301 `[25.453–49.712]` | 38.302 `[28.758–43.148]` | +15.0% |

Twenty samples/window은 기술 통계용. p95/SLO/처리용량/사용자 수로 해석 금지.

## Write/storage 결과

- Candidate index build: 32.363 ms
- Index size: 100k journal 후 4,071,424 bytes (3,976 KiB) · 30k journal insert 후 5,283,840 bytes (5,160 KiB), 증가 1,212,416 bytes
- Arm별 3 batch, 각 10,000 journal + 20,000 entry. Read/API 측정 후 실행. `EXPLAIN ANALYZE` DML statement의 실행 시간/WAL 기록; commit latency 별도 비교 없음
- Candidate 최종 DB: journal 130,002 · allocation 100,000 · entry 260,004 · debit/credit 총액 균형

| Batch | Journal WAL baseline → candidate | 증가 | Entry WAL baseline → candidate |
|---|---:|---:|---:|
| 1 | 9,541,137 → 10,399,308 bytes | +858,171 | 11,388,014 → 11,371,496 bytes |
| 2 | 3,680,312 → 4,522,312 bytes | +842,000 | 5,596,361 → 5,596,361 bytes |
| 3 | 3,746,296 → 4,587,456 bytes | +841,160 | 5,637,752 → 5,637,752 bytes |

- Journal INSERT execution median: baseline 86.586 ms · candidate 82.877 ms. Ranges overlap; latency 차이 결론 없음
- Candidate는 later batches에서 journal WAL 약 0.84 MB / 10k rows 증가. First batch WAL는 양 arm에서 크게 달랐으며 원인 미분리
- Direct SQL batch는 full payment/order transaction 및 application TPS가 아님

## Observability

- Arm별 HTTP request counter 80 · PostgreSQL deadlock counter 0
- Post-run `pg_stat_activity`: active 1 / idle 10 · Hikari active/pending 0/0
- `process_cpu_usage` snapshot: baseline 0.004715 · candidate 0.004702. Heap: baseline 73,236,536 bytes · candidate 126,966,544 bytes
- Prometheus에서 저장한 HTTP metric은 request counter. Histogram bucket은 원자료에 미포함; 요청별 latency는 k6 Trend 결과 사용
- Raw counter/gauge snapshots는 seed·실험 전체 누적/측정 후 시점. Peak CPU·rate로 해석 금지
- Prometheus/Grafana/postgres-exporter 사용. Toxiproxy/Tempo/Alloy 미사용: network fault, distributed trace, log ingestion 가설 없음

## 재현 산출물

- [요약 JSON](results/exp-020/exp020-summary.json)
- SQL plans: [baseline](results/exp-020/baseline-sql-plans.json) · [candidate](results/exp-020/candidate-sql-plans.json)
- Write plans: [baseline](results/exp-020/baseline-write-plans.json) · [candidate](results/exp-020/candidate-write-plans.json)
- Metrics: [baseline](results/exp-020/baseline-observability.json) · [candidate](results/exp-020/candidate-observability.json)
- k6: [baseline 10초](results/exp-020/baseline-10s-k6.json) · [100초](results/exp-020/baseline-100s-k6.json) · [1,000초](results/exp-020/baseline-1000s-k6.json) · [전체](results/exp-020/baseline-full-k6.json) · [candidate 10초](results/exp-020/candidate-10s-k6.json) · [100초](results/exp-020/candidate-100s-k6.json) · [1,000초](results/exp-020/candidate-1000s-k6.json) · [전체](results/exp-020/candidate-full-k6.json)
- 비교 기준: [EXP-018 query-plan baseline](EXP-018-read-query-performance.md) · [EXP-018 candidate A/B](results/exp-018/index-candidate-ab.json)

## 해석과 다음 검증

- Candidate index는 synthetic narrow/medium ranges에서 SQL/API read 개선. Full range에서는 planner가 index를 선택하지 않았고 API median도 증가
- Write sample에서 index size와 WAL 증가 확인. Statement latency는 표본 범위 중첩; commit/FSYNC 및 end-to-end write path 별도 측정 필요
- **Production migration 보류.** 실제 기간 선택도, 더 다양한 timestamp 분포, cold-cache, 동시 요청, end-to-end write transaction 근거 전까지 index 채택 금지

## 참고자료

- [Use The Index, Luke — multi-column key order](https://use-the-index-luke.com/sql/where-clause/the-equals-operator/concatenated-keys)
- [Use The Index, Luke — indexed ORDER BY](https://use-the-index-luke.com/sql/sorting-grouping/indexed-order-by)
- [Use The Index, Luke — index write cost](https://use-the-index-luke.com/sql/dml/update)
