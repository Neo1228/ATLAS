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

요구사항은 현재 제안 상태. 우선순위·목표 수치·완료 기준은 미승인.

| ID | 유형 | 상태 | 요구사항 후보 | 우선순위 | 연결 |
|---|---|---|---|---|---|
| FR-001 | 기능 | △ Proposed | 초기 시나리오: campaign 하나, 대체 가능한 수량형 SKU 1종, 독립 inventory pool ([D-023](../00-governance/decision-log.md)). Reservation/order intent 후보 흐름에서 pool 초과 판매 방지. Sale open 후에만 queue admission ([D-024](../00-governance/decision-log.md)). 다른 유형·재고 규칙·acceptance TBD. | TBD | [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md), [Business Rules](../03-domain/business-rules.md), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33) |
| FR-002 | 기능 | △ Proposed | Payment `AUTHORIZE → CAPTURE` ([D-026](../00-governance/decision-log.md)); PSP 요청 전 inventory hold ([D-025](../00-governance/decision-log.md)). Capture success 확인 후 claim. Initial fixed TTL starts when hold is confirmed; initial `AUTHORIZE` must be submitted before expiry. Expiry closes checkout without extension; submitted PENDING/UNKNOWN operation follows D-027 reconciliation and hold rules ([D-028/D-029](../00-governance/decision-log.md)). | TBD | [Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md), [BR-003](../03-domain/business-rules.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #46](https://github.com/Neo1228/ATLAS/issues/46) |
| FR-003 | 기능 | △ Proposed | reconciliation, 내부 settlement ledger, 정정 내역 추적. 계약 TBD. | TBD | [Q-006](../00-governance/assumptions-and-open-questions.md) |
| NFR-001 | 비기능 | △ Proposed | tenant 간 데이터/권한 격리 검증. threat boundary TBD. | TBD | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | △ Proposed | 합의된 workload에서 offered/achieved throughput, latency, error, recovery 검증. Rate 단위/transaction scope 명시; 목표/실측 없음. | TBD | [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-001/002/009](../08-experiments/experiment-registry.md) |

## Gate 2 작업
- Rate 단위/scope: offered vs achieved RPS; business TPS 정의; DB QPS는 측정 시에만.
- Initial domain scope: campaign 하나, 수량형 SKU 1종, 독립 pool ([D-023](../00-governance/decision-log.md)); sale open 후 queue admission ([D-024](../00-governance/decision-log.md)).
- Workload, concurrency, latency/error/recovery 목표는 TBD. 수치 목표를 추정하지 않음.

## 관련 문서
- [Glossary](../01-discovery/glossary.md), [Personas](../01-discovery/personas-and-jobs.md)
- [Domain Rules](../03-domain/README.md), [Experiments](../08-experiments/experiment-registry.md)
