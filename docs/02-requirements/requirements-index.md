---
id: DOC-004
title: Requirements Index
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 요구사항 인덱스

FR-001/FR-002/NFR-002의 current Buyer journey 기준 승인. 우선순위와 production 목표는 미정. FR-003 settlement와 NFR-001 security는 △ Proposed.

| ID | 유형 | 상태 | 요구사항 후보 | 우선순위 | 연결 |
|---|---|---|---|---|---|
| FR-001 | 기능 | ✓ Gate 2 기준 | Campaign 1개, 수량형 SKU 1종, 독립 inventory pool ([D-023/D-036/D-037](../00-governance/decision-log.md)). Reservation당 1개; `available + reserved + claimed = initial capacity`; oversell 금지. Sale open 후 matching campaign/SKU request만 admission. Local runtime은 bounded FIFO checkout window, campaign-scoped idempotency, `skuId` + synthetic `buyerRef` payload 비교 ([D-041](../00-governance/decision-log.md)). Production K/TTL, auth/bot rules는 OPEN. | 미정 | [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md), [BR-001/002](../03-domain/business-rules.md), [INV-001/002](../03-domain/invariants.md), [Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md) |
| FR-002 | 기능 | ✓ Gate 2 기준 | Inventory hold 후 AUTHORIZE → CAPTURE; capture success 뒤 claim. Confirmed hold부터 첫 AUTHORIZE 제출까지 10분 local test window, 연장 없음. Submission/expiry 시 queue slot 반환. PENDING/UNKNOWN이면 inventory hold 유지하고 같은 attempt로 reconciliation. 미해결 payment가 있는 same campaign + synthetic `buyerRef`의 추가 payment submission 차단 ([D-041/D-042](../00-governance/decision-log.md)). Fake PSP only; provider deadline/production policy 미정. | 미정 | [Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md), [BR-003](../03-domain/business-rules.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) |
| FR-003 | 기능 | △ Proposed | Reconciliation, 내부 settlement ledger, 조정 내역 추적. Contract 미정. | 미정 | [Q-006](../00-governance/assumptions-and-open-questions.md) |
| NFR-001 | 비기능 | △ Proposed | Tenant 간 데이터·권한 격리 검증. Threat boundary 미정. | 미정 | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | ✓ Gate 2 report profile | Full Buyer journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress. Report-only; production SLO/threshold 없음. Offered/admitted/missed sessions, stage RPS, resolved TPS, p95/p99, errors, queue/hold, expiry, oversell 기록. | 미정 | [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-001/002/009/010/012](../08-experiments/experiment-registry.md), [Issue #66](https://github.com/Neo1228/ATLAS/issues/66) |

## Gate 2 작업
- Rate scope: offered/achieved RPS; business TPS 범위; DB QPS는 측정 시에만.
- 초기 도메인 범위: campaign 1개, 수량형 SKU 1종, 독립 pool ([D-023](../00-governance/decision-log.md)); sale open 후 queue admission ([D-024](../00-governance/decision-log.md)).
- Gate 2 workload profile 승인. Concurrency, latency/error threshold, environment, production objective는 미정. 수치 목표를 추정하지 않음.

## Gate 2 승인 기준 — 현재 Buyer 여정
상태: ✓ 승인 (2026-11-02). Runtime 구현·실험 실행 없음.

| 흐름 | 승인된 기준 | 근거 |
|---|---|---|
| 대기열 입장·재시도 | Sale open 전 admission·sequence 미발급. Open 후 eligible request만 admission. Campaign sequence unique, FIFO. Active ticket의 same-key/same-payload retry는 기존 ticket 재사용. Payload mismatch는 기존 상태 변경 없이 거절. 만료 후 새 key·tail sequence; restart 후 순서 보존. | [D-019~D-024/D-034](../00-governance/decision-log.md), [INV-002](../03-domain/invariants.md), [EXP-011](../08-experiments/experiment-registry.md) |
| 재고 예약 | Reservation당 1개. 확정 전이 후 `available + reserved + claimed = initial capacity`, 각 수량 ≥ 0. 동시 요청에서도 oversell 없음. Capture success만 claim. Release는 confirmed non-payment 후; authorization이 있으면 void/expiry 확인 필요. | [D-025~D-027/D-035](../00-governance/decision-log.md), [INV-001](../03-domain/invariants.md), [EXP-001](../08-experiments/experiment-registry.md) |
| 결제 복구 | Confirmed hold부터 initial AUTHORIZE 제출까지 10분 test window; 연장 없음. 만료 전 미제출이면 checkout 종료, payment attempt 없음. 제출된 PENDING/UNKNOWN은 TTL 만료 뒤에도 reserved 유지 후 같은 attempt reconcile; 결과 확인 전 새 attempt 금지. | [D-025~D-030](../00-governance/decision-log.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md) |
| 부하 측정 | 10k sessions/60m baseline과 2×/4× synthetic stress; report-only, pass/fail SLO 없음. Offered/admitted sessions, generator-missed sessions/iterations, stage RPS, resolved-session TPS, p95/p99, errors, queue/hold occupancy, expiry, oversell 기록. | [D-031~D-033](../00-governance/decision-log.md), [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-010](../08-experiments/experiment-registry.md) |

승인 범위는 current single-SKU Buyer journey와 report-only workload에 한정. FR-003 settlement, NFR-001 security/regulation, production SLO, provider deadline은 미승인 ([Q-006/Q-007](../00-governance/assumptions-and-open-questions.md)).

## 장기 제품 역량 지도
헌장 A/B/C와 Roadmap M1–M5의 장기 범위를 기능별로 연결. Gate 2의 current Buyer journey는 첫 검증 시나리오이며 전체 제품 범위가 아님. 기존 기준을 통합했으며 신규 기능 승인은 아님.

| 역량 영역 | 장기 범위 | 현재 기준·미결정 범위 |
|---|---|---|
| 판매사·Tenant·권한 | Tenant별 데이터 격리와 권한 경계, 개인정보·지역·규제 요구 | [NFR-001](requirements-index.md), [Q-007](../00-governance/assumptions-and-open-questions.md), Roadmap M4. 보안 요구 Proposed; 위협 분석·규제 범위 미정 |
| 상품·campaign·재고 | Catalog/SKU, 판매 단위, inventory pool, 재고 조정·재입고 | [FR-001](requirements-index.md), [Q-002/Q-010](../00-governance/assumptions-and-open-questions.md), Roadmap M1. 현재 기준은 campaign/SKU/pool 각 1개; 다른 유형·공유 재고·조정 미정 |
| 대기열·판매 입장 | 참가 자격, 판매 개시 admission, FIFO, ticket 재시도·재입장, bot/access policy | [D-019~D-036](../00-governance/decision-log.md), [Q-003](../00-governance/assumptions-and-open-questions.md), Roadmap M3. FIFO 기준 확정; eligibility·storage·bot 정책 미정 |
| 주문·결제 복구 | Reservation/hold, AUTHORIZE/CAPTURE, 미확정 결과 대사, void/release | [FR-002](requirements-index.md), [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md), Roadmap M2. Synthetic flow 승인; provider·production 정책 미정 |
| 정산·대사 | Internal ledger, 수수료, seller payout, refund/adjustment, audit trail | [FR-003](requirements-index.md), [Q-006](../00-governance/assumptions-and-open-questions.md), Roadmap M4. 요구사항 Proposed |
| 성능·복구·운영 | Capacity, observability, backup/recovery, SLO/RTO/RPO, failure test | [NFR-002](requirements-index.md), [Q-008](../00-governance/assumptions-and-open-questions.md), Roadmap M3/M5. 합성 report-only profile; production 목표·환경 미정 |

Gate 2 승인은 current single-SKU Buyer journey에 한정. 장기 역량 전체나 구현 완료를 뜻하지 않음.

## 관련 문서
- [용어집](../01-discovery/glossary.md), [가상 사용자 목표](../01-discovery/personas-and-jobs.md)
- [도메인 규칙](../03-domain/README.md), [실험 목록](../08-experiments/experiment-registry.md)
