---
id: DOC-004
title: Requirements Index
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: []
  experiments: []
---
# 요구사항 인덱스

요구사항은 현재 제안 상태. 우선순위·목표 수치·완료 기준은 미승인.

| ID | 유형 | 상태 | 요구사항 후보 | 우선순위 | 연결 |
|---|---|---|---|---|---|
| FR-001 | 기능 | △ Proposed | 캠페인 재고 예약, order intent 생성, overselling 방지. 세부 정책 TBD. | TBD | [Q-002/Q-003](../00-governance/assumptions-and-open-questions.md), [Business Rules](../03-domain/business-rules.md) |
| FR-002 | 기능 | △ Proposed | payment attempt, webhook, idempotency, UNKNOWN 상태와 recovery 처리. PSP 정책 TBD. | TBD | [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md) |
| FR-003 | 기능 | △ Proposed | reconciliation, 내부 settlement ledger, 정정 내역 추적. 계약 TBD. | TBD | [Q-006](../00-governance/assumptions-and-open-questions.md) |
| NFR-001 | 비기능 | △ Proposed | tenant 간 데이터/권한 격리 검증. threat boundary TBD. | TBD | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | △ Proposed | 합의된 workload에서 throughput·latency·recovery 검증. 목표/실측 없음. | TBD | [Q-008](../00-governance/assumptions-and-open-questions.md), EXP-001/002/009 |

## Gate 2 작업
- 우선순위, 예외, Given/When/Then acceptance criteria, invariants, trace IDs 확정.
- 실제 목표와 synthetic workload assumptions 분리.

## 관련 문서
- [Glossary](../01-discovery/glossary.md), [Personas](../01-discovery/personas-and-jobs.md)
- [Domain Rules](../03-domain/README.md), [Experiments](../08-experiments/experiment-registry.md)
