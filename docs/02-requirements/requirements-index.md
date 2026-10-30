---
id: DOC-004
title: Requirements Index
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: []
  experiments: []
---
# 요구사항 인덱스

요구사항은 현재 △ Proposed. 우선순위, 목표 수치, 완료 기준은 미승인.

| ID | 유형 | 상태 | 요구사항 후보 | 우선순위 | 연결 |
|---|---|---|---|---|---|
| FR-001 | 기능 | △ Proposed | Gate 2 baseline: campaign 1개, 대체 가능한 수량형 SKU 1종, 독립 inventory pool ([D-023/D-036](../00-governance/decision-log.md)). Reservation당 1개. `available + reserved + claimed = initial capacity`; oversell 방지. Queue admission은 sale open 후만 허용 ([D-024](../00-governance/decision-log.md)); ticket retry/re-entry는 [D-034](../00-governance/decision-log.md) 기준. Assigned-seat/time-slot, 다른 판매 유형은 현재 Gate 2 밖 ([Q-010](../00-governance/assumptions-and-open-questions.md)). | TBD | [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md), [BR-002](../03-domain/business-rules.md), [INV-001](../03-domain/invariants.md), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [Issue #64](https://github.com/Neo1228/ATLAS/issues/64), [PR #65](https://github.com/Neo1228/ATLAS/pull/65) |
| FR-002 | 기능 | △ Proposed | D-026 AUTHORIZE → CAPTURE; D-025 PSP 전 hold, capture success 후 claim. D-030은 confirmed hold부터 initial AUTHORIZE 제출까지 10분 synthetic window. 만료 시 checkout 종료, 연장 없음; PENDING/UNKNOWN은 D-027 복구. Production TTL/SLO OPEN. | TBD | [Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md), [BR-003](../03-domain/business-rules.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48) |
| FR-003 | 기능 | △ Proposed | Reconciliation, 내부 settlement ledger, 정정 내역 추적. Contract TBD. | TBD | [Q-006](../00-governance/assumptions-and-open-questions.md) |
| NFR-001 | 비기능 | △ Proposed | Tenant 간 데이터·권한 격리 검증. Threat boundary TBD. | TBD | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | △ Proposed | [D-031~D-033](../00-governance/decision-log.md): full journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress, report-only. Offered/admitted sessions/s, stage RPS, resolved TPS, p95/p99, errors, queue/hold, expiry, oversell 기록. Production demand/SLO/burst OPEN. | TBD | [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-001/002/009/010](../08-experiments/experiment-registry.md), [Issue #56](https://github.com/Neo1228/ATLAS/issues/56) |

## Gate 2 작업
- Rate scope: offered/achieved RPS; business TPS 범위; DB QPS는 측정 시에만.
- 초기 도메인 범위: campaign 1개, 수량형 SKU 1종, 독립 pool ([D-023](../00-governance/decision-log.md)); sale open 후 queue admission ([D-024](../00-governance/decision-log.md)).
- Workload, concurrency, latency/error/recovery 목표는 TBD. 수치 목표를 추정하지 않음.

## 관련 문서
- [용어집](../01-discovery/glossary.md), [가상 사용자 목표](../01-discovery/personas-and-jobs.md)
- [도메인 규칙](../03-domain/README.md), [실험 목록](../08-experiments/experiment-registry.md)
