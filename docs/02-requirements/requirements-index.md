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
| FR-002 | 기능 | △ Proposed | Payment `AUTHORIZE → CAPTURE` ([D-026](../00-governance/decision-log.md)); PSP 요청 전 inventory hold ([D-025](../00-governance/decision-log.md)); capture success 확인 후 claim. Initial synthetic profile은 hold confirmation부터 initial `AUTHORIZE` submit까지 10분 fixed window ([D-030](../00-governance/decision-log.md)); production TTL/SLO 미정. 만료는 checkout 종료, extension 없음; 이미 제출한 PENDING/UNKNOWN은 D-027 recovery. | TBD | [Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md), [BR-003](../03-domain/business-rules.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48) |
| FR-003 | 기능 | △ Proposed | reconciliation, 내부 settlement ledger, 정정 내역 추적. 계약 TBD. | TBD | [Q-006](../00-governance/assumptions-and-open-questions.md) |
| NFR-001 | 비기능 | △ Proposed | tenant 간 데이터/권한 격리 검증. threat boundary TBD. | TBD | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | △ Proposed | [D-031](../00-governance/decision-log.md) initial profile: hybrid open Buyer-session arrivals, sequential checkout steps, baseline/burst/step-up. Record offered/admitted Buyer sessions/s, dropped sessions, request RPS by stage, and resolved Buyer-session TPS separately. Measure p95/p99 latency, errors, queue/hold occupancy, expiry/abandonment and oversell invariant. | TBD | [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-001/002/009/010](../08-experiments/experiment-registry.md), [Issue #51](https://github.com/Neo1228/ATLAS/issues/51) |

## Gate 2 작업
- Rate 단위/scope: offered vs achieved RPS; business TPS 정의; DB QPS는 측정 시에만.
- Initial domain scope: campaign 하나, 수량형 SKU 1종, 독립 pool ([D-023](../00-governance/decision-log.md)); sale open 후 queue admission ([D-024](../00-governance/decision-log.md)).
- Workload, concurrency, latency/error/recovery 목표는 TBD. 수치 목표를 추정하지 않음.

## 관련 문서
- [Glossary](../01-discovery/glossary.md), [Personas](../01-discovery/personas-and-jobs.md)
- [Domain Rules](../03-domain/README.md), [Experiments](../08-experiments/experiment-registry.md)
