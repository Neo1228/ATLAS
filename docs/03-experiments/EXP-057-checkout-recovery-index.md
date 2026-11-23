# EXP-057: Checkout recovery access path과 cancellation age

**상태:** 측정 완료 — final clean-check에 포함된 PostgreSQL 합성 데이터 1회 실행. 운영 SLO가 아니다.

**날짜:** 2026-11-22 (측정일은 아래 환경 표와 같다.)

**관련:** [ADR-0062](../02-decisions/adr/ADR-0062-order-recovery-backlog-observability.md), [EXP-056](EXP-056-order-inventory-recovery-capacity.md)

## 목적과 방법

주문 재고 finalizer는 `RESERVATION_PENDING` checkout을 `created_at` 순으로 bounded 조회한다. 이력이 쌓여도 전체 scan·sort에 의존하지 않도록 V73 state/created index를 검증한다. 또한 취소 재시도마다 바뀌는 `updated_at` 대신 최초 `CANCEL_PENDING` 진입 시각을 저장해 backlog age를 관측하는 V74 변경과 partial age index를 검증한다.

측정에 쓴 `BuyerCheckoutRecoveryIndexMigrationIntegrationTest`는 일회성 탐색 test였고 저장소에 보존하지 않았다. 이 test는 PostgreSQL 18.6 Testcontainers에서 100,000 checkout을 생성한다. 분포는 `RESERVATION_PENDING` 100건, `CANCEL_PENDING` 50,000건, `PAID` 49,900건이며 각 취소 checkout에 remote `purchase_order`를 연결한다. `ANALYZE` 뒤 finalizer 예약 query와 취소 age query에 `EXPLAIN (ANALYZE, BUFFERS)`를 실행한다. 예약 query는 V73 index 적용 전후, 취소 age query는 V74 timestamp migration 뒤 age index를 뺀 상태와 적용한 상태를 비교한다. 경과 시간은 성능 gate로 사용하지 않고, plan의 access path와 함께 관찰한다.

| 항목 | 환경 |
|---|---|
| 측정일 | 2026-11-22 |
| 호스트 | macOS 27.2, Apple Silicon arm64, 11 논리 CPU, 18 GiB |
| JVM | OpenJDK 25.0.5.1 (2026-11-17) |
| Docker | client 29.10.0 / server 29.5.2 |
| PostgreSQL 자원 제한 | Testcontainer에 별도 CPU·memory limit 없음 |

### 지표 정의와 반복

- 시간 값(ms)은 PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)`가 보고한 `Execution Time`이다(planning time은 제외). 클라이언트 왕복이나 application 지연은 포함하지 않는다.
- 각 access path는 warm-up 1회 뒤 같은 `EXPLAIN` 5회를 실행해 median을 기록했다(실행당 n = 5).
- 이 측정을 같은 호스트에서 두 번 실행했다. "단독"은 harness test만 실행한 값이고 "전체 check"는 전체 `clean check` 안에서 실행한 값이다(실행 n = 2). 두 실행은 같은 분포로 데이터를 각각 새로 생성했다.
- "배"는 변경 전 ÷ 적용 후 값이다(4.174 ÷ 0.049 = 85.2, 4.367 ÷ 0.056 = 78.0). "% 낮은 median"은 (변경 전 − 적용 후) ÷ 변경 전이다((20.163 − 19.114) ÷ 20.163 = 5.2%, (20.941 − 18.315) ÷ 20.941 = 12.5%).
- 실측 행은 당시 테스트 실행 결과 XML의 `system-out`에 남았고 보존하지 않았다.

## 결과

| Query | 변경 전 | 적용 후 | 관찰 |
|---|---:|---:|---|
| `RESERVATION_PENDING` `ORDER BY LIMIT 100` | 4.174 ms (단독) / 4.367 ms (전체 check) | 0.049 ms / 0.056 ms | V73 인덱스 사용, 78.0–85.2배 |
| `CANCEL_PENDING`의 `MIN(cancellation_requested_at)` | 20.163 ms / 20.941 ms | 19.114 ms / 18.315 ms | V74 migration 인덱스 사용, 두 실행에서 5.2–12.5% 낮은 median |

표의 "변경 전"은 해당 index가 없는 상태이고, "적용 후"는 index가 있는 상태다. 취소 age query의 "변경 전"은 V74 timestamp migration은 적용하되 age index만 뺀 상태다. 관찰 열의 "인덱스 사용"은 `EXPLAIN` 계획에서 해당 index가 선택됐음을 뜻한다.

## 해석과 한계

- 같은 호스트에서 단독 test와 전체 clean check로 두 번 실행했다. 5-sample median도 JVM·buffer cache·Testcontainer host load 영향을 받으므로 실행 간 값이 달라질 수 있다.
- 100,000행의 synthetic checkout history와 50,000행의 취소 backlog는 access path를 보기 위한 workload이지 운영 거래 분포나 backlog 예측이 아니다.
- Plan과 read path는 측정했지만 V73/V74 index의 storage·write amplification 비용, 장시간 write workload는 재지 않았다.
- 78.0–85.2배 및 5.2–12.5%는 이 두 로컬 실행에서만 관측했다. 반복 workload, production latency 또는 SLO를 보장하지 않는다.
- Multi-replica contention, downstream network latency, failure/retry 혼합 workload는 [EXP-056](EXP-056-order-inventory-recovery-capacity.md)의 한계를 따른다.

## 재현

- 재현 명령은 제공하지 않는다. 측정 harness `BuyerCheckoutRecoveryIndexMigrationIntegrationTest`는 일회성 탐색 test였고 저장소에서 제거했다. 원본 결과(`system-out`)도 보존하지 않았다.
- 같은 분포(100,000 checkout: `RESERVATION_PENDING` 100, `CANCEL_PENDING` 50,000, `PAID` 49,900)를 PostgreSQL 18.6에 만들고 `ANALYZE` 뒤 `EXPLAIN (ANALYZE, BUFFERS)`를 직접 실행해야 한다. V73/V74 migration 파일은 저장소에 있다(`V73__buyer_checkout_recovery_index.sql`, `V74__buyer_checkout_cancellation_requested_at.sql`).
