---
id: DOC-015
title: Domain Invariants
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: [FR-001, NFR-002]
  adr: [ADR-0001]
  experiments: [EXP-001, EXP-005]
---
# 도메인 불변조건
문서 유형: 기준 자료

불변조건(invariant)은 정상 처리와 실패·복구 뒤에도 유지할 성질. 현재 Buyer journey 기준은 Gate 2에서 승인됨. DB 구현·측정 세부는 Gate 3 설계, 실행 검증은 구현 승인 뒤 진행. 용어는 [용어집](../01-discovery/glossary.md) 참조.

| ID | 불변조건 | 상태 | 미정 경계 | 근거 |
|---|---|---|---|---|
| INV-001 | 초기 pool: reservation당 1개. 각 확정 전이 후 `available + reserved + claimed = initial capacity`; 각 값은 0 이상. Hold는 available→reserved, capture success는 reserved→claimed. Release는 confirmed non-payment 후, authorization이 존재하면 void/expiry 확인 뒤에만 허용 ([D-027/D-035/D-037](../00-governance/decision-log.md)). | ✓ Gate 2 baseline | 다중 수량, 공유 재고, 판매 중 조정·재입고, post-claim 취소·환불 정책 제외. PostgreSQL 선택; schema/transaction/isolation/locking/atomicity는 Gate 3 OPEN ([Q-002/Q-004/Q-009](../00-governance/assumptions-and-open-questions.md)). | [BR-002](business-rules.md), [FR-001](../02-requirements/requirements-index.md), [EXP-001/005](../08-experiments/experiment-registry.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Issue #68](https://github.com/Neo1228/ATLAS/issues/68) |
| INV-002 | Sale open 뒤 admission. Campaign 안에서 server sequence unique, 낮은 값 우선. Active ticket의 same-key/same-payload retry는 기존 ticket/sequence 재사용; 같은 key의 payload 변경은 거절. 만료 후 재입장은 새 key·새 sequence를 tail에 발급. Restart 뒤 active order 보존 ([D-019~D-022/D-034](../00-governance/decision-log.md)). | ✓ Gate 2 기준 | [Q-003](../00-governance/assumptions-and-open-questions.md): key scope/retention, payload fields, expiry rules, atomic sequence allocation은 미정. | [BR-001](business-rules.md), [Q-003](../00-governance/assumptions-and-open-questions.md), [EXP-011](../08-experiments/experiment-registry.md), [Issue #68](https://github.com/Neo1228/ATLAS/issues/68) |
| INV-003 | Capture success가 확인된 경우에만 inventory claim. TTL/cancel 시 capture를 먼저 확인하고, 미캡처 active authorization은 void. Capture/void `PENDING`·UNKNOWN 동안 stock unavailable; release는 confirmed non-payment와 void/expiry 확인 뒤에만 허용 ([D-025~D-027/D-037](../00-governance/decision-log.md)). | ✓ Gate 2 recovery 기준 | Provider deadline, retry/key retention, escalation은 미정 ([Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md)). | [BR-003](business-rules.md), [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Issue #68](https://github.com/Neo1228/ATLAS/issues/68) |

위반 측정 결과 없음. 검증 후보는 [실험 목록](../08-experiments/experiment-registry.md). 남은 경계: [Q-002/Q-003](../00-governance/assumptions-and-open-questions.md), [도메인 규칙](business-rules.md).
