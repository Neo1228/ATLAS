# EXP-027: seller 정산 명세 index 적용 전후

**상태:** 로컬 합성 데이터 측정 · 운영 부하/SLA 근거 아님

## 목적과 범위

Seller 정산 명세(`sellerStatementTotals`, `sellerStatementRows`)는 `settlement_journal`을 `seller_id` + `recorded_at` 범위로 3개 branch에서 조회합니다. 이 실험은 `(seller_id, recorded_at, journal_id)`와 `settlement_seller_allocation (seller_id, journal_id)` index가 실제 plan과 실행 시간을 바꾸는지 측정합니다. HTTP latency와 동시 부하는 측정 범위 제외입니다.

## 환경·데이터·workload

- macOS arm64 · Colima Docker: PostgreSQL 18.6 image `postgres:18.6`, 단일 세션, `-c` override 없음(image 기본값).
- 일회용 container, 종료 후 제거. named volume 없음.
- 합성 데이터: seller 200명, legacy order capture 400,000행, checkout journal 100,000행 + seller allocation, refund reversal 50,000행. 모든 행은 최근 2년에 분포.
- workload: seller 1명 기준 최근 30일 구간.
  - 전체 3-branch aggregate(`sellerStatementTotals`와 동일 SQL)
  - checkout allocation branch 단독
- 측정: `EXPLAIN (ANALYZE, BUFFERS)`, warm cache, 단일 실행.

## 결과

V39 이전 상태는 "index 없음"이 아니라 **V28의 `settlement_seller_allocation (seller_id, journal_id)`만 존재하는 상태**입니다. 따라서 V39의 실제 기여분은 마지막 행과 바로 위 행의 차이입니다.

| 조건 | 3-branch seller statement aggregate |
| --- | ---: |
| 어떤 seller index도 없음 (참고, V28 이전 상태) | 48.918 ms · 8,257 shared hit |
| **V39 이전 실제 상태**: V28 allocation index만 존재 | **27.333 ms · 8,257 shared hit** |
| V39 적용: journal seller index 추가 | **7.004 ms · 2,006 shared hit** |

checkout allocation branch 단독 측정(참고): allocation index 없음 6.774 ms → V28 index 적용 3.357 ms.

V39 적용 후 plan 변화는 branch 1·3에 국한됩니다.

- branch 1·3: `Seq Scan on settlement_journal` → `Bitmap Index Scan on settlement_journal_seller_recorded_idx`
- branch 2: V28 index가 이미 적용되어 `Bitmap Index Scan on settlement_seller_allocation_seller_journal_idx`

## 해석과 한계

- V39 이전에는 `settlement_journal` branch가 **플랫폼 전체 ledger**를 읽었습니다(8,257 buffers가 그대로 유지됨). V39 적용 후 journal 접근은 seller 1명의 30일 구간으로 제한되고 buffers는 2,006으로 줄었습니다.
- 남은 7.004 ms의 상당 부분은 allocation → journal primary key lookup(500회)이며 seller당 allocation 수에 비례합니다. 이 실험은 그 부분을 줄이지 않습니다.
- V28 allocation index의 기여(6.774 → 3.357 ms)는 이번 변경에 포함되지 않습니다.
- `sellerStatementTotals`는 여전히 요청마다 재집계합니다. 집계 자체를 materialize하는 것은 별도 설계가 필요합니다.
- 단일 세션·warm cache·합성 분포 측정이며, 운영 데이터 분포(대형 seller 편중)나 동시 부하로 일반화하지 않습니다.

## 재현

`docs/03-experiments/EXP-027-seller-statement-index.sql`에 동일 dataset·query를 기록했습니다.
