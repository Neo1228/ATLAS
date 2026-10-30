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

불변조건(invariant)은 정상 처리와 실패·복구 뒤에도 유지해야 하는 성질. 수치·저장 방식·검증 계약은 Gate 2~4에서 결정. 용어는 [Glossary](../01-discovery/glossary.md) 참조.

| ID | 불변조건 | 상태 | 미정 경계 | 근거 |
|---|---|---|---|---|
| INV-001 | 초기 pool: reservation당 1개. 각 확정 전이 후 `available + reserved + claimed = initial capacity`; 각 값은 0 이상. Hold는 available→reserved, capture success는 reserved→claimed. Release는 confirmed non-payment 후, authorization이 존재하면 void/expiry 확인 뒤에만 허용 ([D-027/D-035](../00-governance/decision-log.md)). | ✓ Baseline | 다중 수량, 공유 재고, 판매 중 조정·재입고, post-claim 취소·환불 정책 제외. D-027 checkout expiry/authorization void 흐름 유지. Gate 3 storage/atomicity 미정 ([Q-002/Q-004](../00-governance/assumptions-and-open-questions.md)). | [BR-002](business-rules.md), [FR-001](../02-requirements/requirements-index.md), [EXP-001/005](../08-experiments/experiment-registry.md), [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63) |
| INV-002 | Sale open 후 admission. Campaign 안에서 server sequence unique, 낮은 값 우선. Active ticket의 same-key/same-payload retry는 기존 ticket/sequence 재사용; 같은 key의 payload 변경은 거절. 만료 후 재입장은 새 key·새 sequence를 tail에 발급. Restart 후 active order 보존 ([D-019~D-022/D-034](../00-governance/decision-log.md)). | ✓ Baseline; acceptance △ Proposed | [Q-003](../00-governance/assumptions-and-open-questions.md): key scope/retention, payload comparison fields, expiry trigger/duration, storage/atomicity, bot defense, other types. | [BR-001](business-rules.md), [Q-003](../00-governance/assumptions-and-open-questions.md), [EXP-011](../08-experiments/experiment-registry.md), [Issue #60](https://github.com/Neo1228/ATLAS/issues/60) |
| INV-003 | Capture success가 확인된 경우에만 inventory claim. TTL/cancel 시 capture를 먼저 확인하고, 미캡처 active authorization은 void. Capture/void `PENDING`·UNKNOWN 동안 stock unavailable; release는 confirmed non-payment와 void/expiry 후에만 가능 ([D-025~D-027](../00-governance/decision-log.md)). | ✓ Baseline; recovery acceptance △ Proposed | [Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md): TTL/deadline, retry/key retention, escalation/compensation. | [BR-003](business-rules.md), [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #42](https://github.com/Neo1228/ATLAS/issues/42) |

위반 측정 결과 없음. 검증 후보는 [Experiment Registry](../08-experiments/experiment-registry.md). 남은 경계: [Q-002/Q-003](../00-governance/assumptions-and-open-questions.md), [Business Rules](business-rules.md).
