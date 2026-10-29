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

요구사항은 발견/합의 전까지 후보이며, 승인 전 우선순위·수치·인수 기준을 확정하지 않는다. 현재 Gate 0에서는 전체 범위와 검증 축만 추적한다.

| ID | 유형 | 상태 | 범위/주제 | 우선순위 | 연결 |
|---|---|---|---|---|---|
| FR-001 | 기능 | Proposed | 캠페인 재고 예약과 주문 의도를 관리하고 초과 예약을 방지한다. 세부 정책 TBD. | TBD | [Q-002](../00-governance/assumptions-and-open-questions.md), [Q-003](../00-governance/assumptions-and-open-questions.md), [Business Rules](../03-domain/business-rules.md) |
| FR-002 | 기능 | Proposed | 결제 시도/웹훅/멱등성/불확실 상태/복구를 다룬다. PSP 세부 TBD. | TBD | [Q-004](../00-governance/assumptions-and-open-questions.md), [Q-005](../00-governance/assumptions-and-open-questions.md) |
| FR-003 | 기능 | Proposed | 거래 대사와 내부 정산 기록 및 정정을 추적한다. 계약상 의미 TBD. | TBD | [Q-006](../00-governance/assumptions-and-open-questions.md) |
| NFR-001 | 비기능 | Proposed | 테넌트 간 데이터/권한 격리를 검증한다. 위협 경계 TBD. | TBD | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | Proposed | Gate 2에서 합의한 synthetic workload의 traffic mix/요청량/동시성/latency/recovery profiles를 검증한다. 목표값은 사용자 합의 전 TBD이며 실측이 아니다. | TBD | [Q-008](../00-governance/assumptions-and-open-questions.md), [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Experiment Registry](../08-experiments/experiment-registry.md) |

## 정책
Gate 2에서 우선순위, 예외 시나리오, Given/When/Then 인수 기준, 불변조건 및 추적 링크를 구체화한다. 실제 목표값과 합성 실험 가정을 분리한다.

## 관련 문서
- [Glossary](../01-discovery/glossary.md)와 [Personas & Jobs](../01-discovery/personas-and-jobs.md)는 용어/가상 목표를 정의한다.
- [Domain Rules](../03-domain/README.md)와 [Experiments](../08-experiments/experiment-registry.md)는 요구사항을 정책/검증 후보에 연결한다.
