# EXP-026: 만료 sweep 상한 적용 전후 비교

**상태:** 로컬 합성 데이터 측정 · 운영 부하/SLA 근거 아님

## 목적과 범위

`expireDueOrders`/`expireDueCheckouts`는 매 tick(기본 `PT1S`)마다 만료 후보 전체를 조회한 뒤 후보별로 개별 transaction을 열었다. 후보 조회에 상한이 없어 backlog가 커지면 한 tick이 공유 scheduler thread를 무기한 점유한다. 이 실험은 후보 조회에 `Limit` 상한을 적용했을 때의 비용 차이만 측정한다. 후보 1건당 transaction 비용은 ADR-0031의 기존 측정(만료 주문 300건 중앙값 ≈ 5.225 s)을 인용하며 재측정하지 않는다.

## 환경·데이터·workload

- macOS arm64 · Colima Docker: PostgreSQL 18.6 image `postgres:18.6`, 단일 세션, image 기본 설정(`-c` override 없음).
- `sweep_t` 테이블에 120,000행: 이미 만료된 `RESERVED` 60,000행 + 아직 만료되지 않은 60,000행.
- `CREATE INDEX ... (checkout_expires_at) WHERE state='RESERVED'` — 실제 `purchase_order_expiry_idx`(V3)와 동일한 형태.
- 비교 workload: 같은 후보 조건의 `ORDER BY checkout_expires_at`에 (A) 상한 없음 vs (B) `LIMIT 25`.
- 측정 항목: `EXPLAIN (ANALYZE, BUFFERS, TIMING)`의 Execution Time과 shared buffer 수.

## 측정 결과

| 조건 | Execution Time | Shared buffers |
| --- | --- | --- |
| A. 상한 없음 | 8.842 ms | 613 (hit 561 + read 52) |
| B. `LIMIT 25` | 0.055 ms | 3 |

두 조건 모두 index scan 1회를 사용했고, 차이는 **읽는 행 수**에서 발생했다. 상한이 없으면 만료된 60,000행을 모두 읽어 정렬하고, 상한이 있으면 25행에서 멈춘다.

## 해석과 한계

- 후보 조회 비용은 backlog에 비례하고, 상한을 두면 batch 크기에 비례한다. 60,000행 기준 약 160배 차이이며, 이는 이 합성 데이터 크기에서의 값이다.
- 위 값은 **후보 조회만** 측정한 것이다. 실제 tick 시간의 대부분은 후보별 개별 transaction(잠금·상태 전이·재고 해제)이며, 그 비용은 ADR-0031의 300건 ≈ 5.225 s 측정을 참고한다. 따라서 상한의 실제 효과는 "한 tick의 작업량이 batch/time budget으로 잘린다"는 구조적 보장이지, 위 배수만큼 tick이 빨라진다는 주장이 아니다.
- 단일 세션·warm cache 측정이며, 동시 부하·cold cache·운영 규모로 일반화하지 않는다.

## 재현

`docs/03-experiments/EXP-026-expiry-sweep-bound.sql`은 동일 비교를 실행 가능한 형태로 기록한다.
