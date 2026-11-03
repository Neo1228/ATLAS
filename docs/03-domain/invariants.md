---
id: DOC-015
title: Domain Invariants
status: Draft
owner: Project Owner
updated: 2026-11-03
links:
  requirements: [FR-001, FR-004, NFR-002]
  adr: [ADR-0001, ADR-0005]
  experiments: [EXP-001, EXP-005]
---
# 도메인 불변조건
문서 유형: 기준 자료

불변조건(invariant)은 정상 처리와 실패·복구 뒤에도 유지할 성질. Gate 2 baseline은 승인됨. Seller-SKU shared stock/global FIFO는 [PR #90](https://github.com/Neo1228/ATLAS/pull/90)에서 구현·병합됐고 [Issue #89](https://github.com/Neo1228/ATLAS/issues/89)는 닫힘. Production lock recovery·성능 검증은 별도 실험/환경 대상. 용어는 [용어집](../01-discovery/glossary.md) 참조.

| ID | 불변조건 | 상태 | 미정 경계 | 근거 |
|---|---|---|---|---|
| INV-001 | Inventory identity는 `(sellerId, skuId)`. 같은 Seller-SKU를 참조하는 Campaign은 하나의 pool 사용. 각 확정 전이 후 `available + reserved + claimed = initial capacity`; 각 값은 0 이상. Hold는 available→reserved, capture success는 reserved→claimed. Release는 confirmed non-payment 후, active authorization은 void/expiry 확인 뒤. | ✓ Local baseline | 다중 수량·location/channel·조정·재입고·post-claim 취소/환불은 미정 ([Q-002](../00-governance/assumptions-and-open-questions.md)). PostgreSQL 경쟁/복구 검증은 local test 범위이며 production 성능 근거 아님. | [BR-002](business-rules.md), [FR-001/FR-004](../02-requirements/requirements-index.md), [ADR-0005](../06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md), [Issue #89](https://github.com/Neo1228/ATLAS/issues/89), [PR #90](https://github.com/Neo1228/ATLAS/pull/90) |
| INV-002 | Sale open 뒤 eligibility admission. Seller-SKU별 sequence unique, 낮은 값 우선; K와 FIFO는 Campaign 간 global. Same key/payload는 해당 Campaign ticket 재사용; mismatch 거절; expiry 뒤 새 key는 global tail sequence. Queue key/K global, idempotency/TTL Campaign-scoped. `sellerId`는 인증·권한 경계가 아님 ([D-019~D-022/D-034/D-039/D-041/D-049](../00-governance/decision-log.md)). | ✓ Local baseline | Production K/TTL, tombstone cleanup, bot/auth, lock timeout/deadlock recovery 미정 ([Q-003/Q-007](../00-governance/assumptions-and-open-questions.md)). | [BR-001](business-rules.md), [Q-003/Q-007](../00-governance/assumptions-and-open-questions.md), [ADR-0005](../06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md), [Issue #89](https://github.com/Neo1228/ATLAS/issues/89), [PR #90](https://github.com/Neo1228/ATLAS/pull/90) |
| INV-003 | Capture success만 claim. TTL/cancel recovery는 capture 먼저 확인; 미캡처 active authorization은 void. Capture/void PENDING·UNKNOWN 동안 stock unavailable. Confirmed non-payment와 필요한 void/expiry 뒤 release ([D-025~D-027/D-037/D-042](../00-governance/decision-log.md)). | ✓ Gate 2 recovery 기준 | Same campaign + synthetic `buyerRef`의 unresolved payment 동안 새 payment submission 차단; identity/security 보장 아님. Provider deadline, retry/key retention, escalation은 OPEN ([Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md)). | [BR-003](business-rules.md), [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) |

위반 측정 결과 없음. 검증 후보는 [실험 목록](../08-experiments/experiment-registry.md). 남은 경계: [Q-002/Q-003](../00-governance/assumptions-and-open-questions.md), [도메인 규칙](business-rules.md).
