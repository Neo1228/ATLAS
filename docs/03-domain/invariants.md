---
id: DOC-015
title: Domain Invariants
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: [FR-001, NFR-002]
  adr: []
  experiments: [EXP-001, EXP-005]
---
# 도메인 불변조건
문서 유형: 기준 자료

Invariant는 정상 처리와 실패/recovery 뒤에도 유지할 성질. 수치·저장 방식·검증 계약은 Gate 2~4에서 결정. 용어는 [Glossary](../01-discovery/glossary.md) 참조.

| ID | 불변조건 | 상태 | 미정 경계 | Trace |
|---|---|---|---|---|
| INV-001 | 초기 시나리오: 수량형 SKU 1종의 campaign pool에서 예약·판매 수량 합이 capacity 이하 ([D-023](../00-governance/decision-log.md)). | △ Proposed | [Q-002/Q-004](../00-governance/assumptions-and-open-questions.md): 다른 판매 단위, 공유 재고, 예약·결제·취소·조정 상태 전이와 acceptance. Gate 3 storage/atomicity. | [BR-002](business-rules.md), [FR-001](../02-requirements/requirements-index.md), [EXP-001/005](../08-experiments/experiment-registry.md) |
| INV-002 | Sale open 후 admission; 자격 통과 뒤 낮은 server sequence 우선. Retry는 ticket 재사용, restart 후 순서 보존 ([D-019~D-024](../00-governance/decision-log.md)). | ✓ Baseline; acceptance △ Proposed | [Q-003](../00-governance/assumptions-and-open-questions.md): storage/key/payload/lifecycle/tie/bot/other types. | [BR-001](business-rules.md), [Q-003](../00-governance/assumptions-and-open-questions.md) |
| INV-003 | Payment outcome UNKNOWN이면 같은 attempt를 reconcile하기 전 새 attempt 금지. TTL 경과 후에도 `EXPIRED_PENDING_PAYMENT` 재고는 definitive result까지 unavailable ([D-025](../00-governance/decision-log.md)). | ✓ Baseline; recovery acceptance △ Proposed | [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md): TTL, provider-specific recovery와 장기 미해결 처리 ? OPEN. | [BR-003](business-rules.md), [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md) |

위반 측정 결과 없음. 검증 후보: [Experiment Registry](../08-experiments/experiment-registry.md). 경계 상세: [Q-002/Q-003](../00-governance/assumptions-and-open-questions.md), [Business Rules](business-rules.md).
