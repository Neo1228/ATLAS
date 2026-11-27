# ADR-0104: 판매자 잔액은 쓰기 경로 밖 마감 행 + 꼬리로 읽고, 정산서 기간은 배분 날짜와 분개 쪽 인덱스로 자른다

- 상태: Accepted(사용자 지시 "실 데이터 규모에 모자르는 부분들 전부 보강"에 따른 에이전트 결정. 사용자 승인 여부는 확인하지 못함)
- 날짜: 2026-11-27
- 관련 결정: [ADR-0103](ADR-0103-eda-read-projection-deferred-event-contract-and-signals.md)(투영·덮는 인덱스 보류), [ADR-0100](ADR-0100-settlement-ledger-guards.md)(원장 추가 전용·균형), [ADR-0101](ADR-0101-queue-poll-partial-indexes-and-keep-queue-layout.md)(큐 폴링 부분 인덱스), D-183
- 근거: [EXP-115](../../03-experiments/EXP-115-seller-ledger-scale-hardening-and-growth-table-sweep.md), 이슈 [#723](https://github.com/Neo1228/ATLAS/issues/723)

## 문제

판매자 advisory 락 안의 환불 분개(보류 잔액)와 지급 예약(지급 가능 잔액), 정산서 잔액은 판매자 원장 이력 전체를 합산했고, 30일 정산서의 수수료·보류 갈래와 checkout 배분 갈래는 기간과 무관하게 판매자 이력을 읽었다. 30초마다 도는 음수 지급 가능 노출 지표(`PayoutRiskMetrics`)는 플랫폼 원장 전체를 판매자별로 모았다. EXP-114가 재현한 비용(판매자 항목 39.2만 건에서 환불 분개 p50 191 ms, 392만 건에서 999 ms)을 고칠 수 있는 안 중 측정한 것(A1 덮는 인덱스)은 쓰기 예산을 넘었다.

## 결정

1. **판매자 잔액 = 마감 행 + 꼬리(EXP-115 Ae).** `settlement_seller_balance_checkpoint`(판매자별 지급 가능·보류·수수료)는 커서(`entry_id`) 아래 항목의 합이고, `SettlementBalanceCheckpointWorker`가 쓰기 경로 밖에서 10초마다 커서를 옮긴다. 잔액 읽기는 마감 행과 커서를 한 문장에서 읽고, 그 커서 이후 항목을 커서를 리터럴로 넣은 두 번째 문장으로 더한다. 항목 기록 문장·인덱스·잠금은 V10 전과 같다(쓰기 경로 변화 없음). 커서는 "쓰기를 시작한 다른 트랜잭션의 가장 이른 시작"과 "지금 − 5분" 중 이른 쪽을 넘지 않아 늦게 커밋되는 항목을 잃지 않는다(시험과 변이 M7).
2. **정산서 기간 읽기(Ac).** checkout 배분에 그 분개의 `recorded_at`을 BEFORE INSERT 트리거가 채우고(앱이 넘긴 값도 덮음), 배분 인덱스를 `(seller_id, recorded_at, journal_id)`로 바꾼다. 30일 합계의 수수료·보류는 기간 안 판매자 분개(분개·배분 인덱스)에서 `journal_id`로 항목을 찾는다.
3. **기각:** A1 덮는 인덱스, Aa INCLUDE 없는 키 인덱스, Ab 계정별 부분 인덱스, Ad 같은 트랜잭션 판매자 잔액 행, Ad16 16칸 잔액 행은 사전 등록한 쓰기 예산(같은 창 쌍의 p50 비 ≤ 1.10, 처리량 비 ≥ 0.90)을 넘었다(EXP-115 표). Ad·Ad16은 같은 판매자 동시 쓰기와 판매자 20명 checkout에서 잔액 행 경합으로 처리량이 줄었다. 시간 분할(`settlement_entry` 기간 분할)은 고유 보장 약화와 V7 트리거 재구성 비용 때문에 하지 않는다(Ae가 같은 목적을 쓰기 비용 없이 달성).
4. **원장 점검 3개 추가:** `ENTRY_SELLER_OUTSIDE_JOURNAL`(정산서 기간 합계가 기대는 규칙), `ALLOCATION_RECORDED_AT_MISMATCH`, `BALANCE_CHECKPOINT_DRIFT`.
5. **정산서 세션의 병렬 질의 제한:** EXP-115 H8 판정에 따른다(EXP-115 "H8" 절).

## 대안과 기각 이유

| 안 | 측정(EXP-115) | 결정 |
|---|---|---|
| A0 현행 | 기준선 | ✗ 기각(락 안 이력 합산) |
| A1 덮는 인덱스 + 항목 날짜 조건 | 인덱스 749.8 MB, 쓰기 예산 4칸 탈락 | ✗ 기각 |
| Aa `(seller_id, account_code, entry_side, amount_won)` | 인덱스 387.0 MB(중복 제거가 거의 안 됨), 쓰기 예산 탈락 | ✗ 기각 |
| Ab 계정별 부분 인덱스 3개 | 합 205.1 MB, 쓰기 예산 탈락. 읽기는 여전히 이력 비례(인덱스만 스캔) | ✗ 기각 |
| Ac 기간 조건 + 배분 날짜(X1) | 결과 A0와 같음, checkout 쓰기 예산 통과 | ✓ 채택 |
| Ad 같은 트랜잭션 잔액 행(문장 트리거, 판매자 순서) | 환불 p50 이력 무관, 교착 0, 쓰기 예산 탈락 | ✗ 기각 |
| Ad16 16칸 잔액 행 | 쓰기 예산 탈락 | ✗ 기각 |
| Ae 마감 행 + 꼬리(쓰기 경로 밖 worker) | 꼬리 읽기 버퍼 100k 101, 1m 25(A0 71,888 / 138,633). 노출 지표 138,647 → 68 버퍼. 쓰기 문장 A0와 같음 | ✓ 채택 |

## 결과

- 환불 분개·지급 예약·정산서 잔액·노출 지표가 읽는 양은 판매자 이력이 아니라 마감 이후 꼬리(기본 최대 5분 + 주기 10초분의 플랫폼 항목)에 비례한다. 배포 직후에는 커서가 0이라 worker가 이력을 따라잡을 때까지(한 주기 최대 100만 항목) V10 전과 같은 양을 읽는다.
- 마감 표는 원장에서 다시 만들 수 있는 파생 데이터다. 원천은 여전히 `settlement_entry` 하나다. 어긋나면 `BALANCE_CHECKPOINT_DRIFT`가 0이 아니고, 운영 가이드의 절차로 다시 만든다.
- 정산서 30일 읽기는 기간 안 분개 수에 비례한다(판매자 이력과 무관).
- V10은 배분 표 기존 행을 한 번 채우는 동안 배분 표를 `ACCESS EXCLUSIVE`로 잠근다(checkout 기록이 기다림).

## 롤백

- 앱을 V10 이전 이미지로 되돌린 뒤 `scripts/db/rollback/V10__undo_seller_ledger_history_bounded_reads.sql`(마감 표 2개, 배분 열·트리거·인덱스 되돌림, 원장 금액 행은 그대로). 시험 `documentedRollbackScriptRemovesTheV10ObjectsAndKeepsLedgerRows`가 실행한다.
- worker만 끄기: `atlas.settlement.balance-checkpoint.enabled=false`(잔액은 정확, 읽는 양만 V10 전과 같아짐).

## 재검토 조건

- 마감 커서 지연이 10분을 넘는 상태가 15분 넘게 이어지거나 `atlas.settlement.balance_checkpoint.failures`가 늘면: worker·잠금·긴 쓰기 트랜잭션을 본다.
- 플랫폼 항목 쓰기가 초당 1,000건을 넘어 꼬리(5분 + 10초)가 30만 항목을 넘으면: 안전 지연을 줄일 근거(다른 역할 세션이 없음을 운영으로 보장)를 정하거나 꼬리 범위 인덱스를 다시 잰다.
- 원장 정정을 다른 DB 역할로 자주 하게 되면: 정정 절차에 worker 정지를 강제하는 장치를 둔다.
