---
id: DOC-002
title: Assumptions and Open Questions
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 가정 및 미결정 사항

상태: `✓ Resolved` 해결, `? OPEN` 미정, `△ ASSUMPTION` 검증 전 가정. 일부 baseline을 정해도 세부 경계가 남으면 OPEN 유지.

| ID | 상태 | 질문/리스크 | 권장 논의 순서 | 추적 |
|---|---|---|---|---|
| Q-001 | ✓ Resolved | 가상 학습 시나리오로 시작. 실제 시장/사용자 검증은 없음. Buyer/seller 목표는 [D-015~D-017](decision-log.md)에 기록. | 완료 | [D-005](decision-log.md) |
| Q-010 | ? OPEN | 첫 합성 시나리오는 수량형 SKU 1종 ([D-023](decision-log.md)). 다른 판매 유형별 Buyer/Seller 행동은 미정. | Gate 1; 공통 목표 [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), 초기 범위 [Issue #33](https://github.com/Neo1228/ATLAS/issues/33) | [Personas & Jobs](../01-discovery/personas-and-jobs.md) |
| Q-002 | ? OPEN | [D-023](decision-log.md): 초기 시나리오는 대체 가능한 SKU 1종과 campaign 소유 pool 1개. 공유/global/location 재고, 다른 단위, 재입고·조정·취소 정책은 OPEN. | Q-010과 함께 논의; 초기 경계만 선택 | [Business Rules](../03-domain/business-rules.md), [Personas](../01-discovery/personas-and-jobs.md), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33) |
| Q-003 | ? OPEN | 초기 시나리오는 sale open 전 admission 없음, open 후 [D-019/D-020](decision-log.md) 적용. Storage, key/retention, payload 변경, expiry/re-entry, tie, bot, 다른 유형의 queue policy는 OPEN. | Q-002/Q-010과 묶어 논의; 경계별 Issue 금지 | baseline [Issue #24](https://github.com/Neo1228/ATLAS/issues/24); [Issue #33](https://github.com/Neo1228/ATLAS/issues/33); [Rules](../03-domain/business-rules.md) |
| Q-004 | ? OPEN | [D-025](decision-log.md) initial flow: PSP 요청 직전 hold; terminal success 확인 후 claim, 확정 실패 후 release. 결과 UNKNOWN이면 hold 유지; TTL 중 만료해도 reconciliation까지 unavailable. TTL 수치·장기 미해결 처리 ? OPEN. | Q-005와 묶어 정책 합의 | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #35](https://github.com/Neo1228/ATLAS/issues/35) |
| Q-005 | ? OPEN | [D-025](decision-log.md): UNKNOWN outcome은 실패로 간주하지 않고 같은 attempt를 reconcile한 뒤 새 attempt 허용. PSP idempotency key 지원 시 같은 key 재사용. Auth/capture 순서, provider contract, key retention/retry limit, refund policy는 OPEN. | Q-004와 lifecycle batch | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #35](https://github.com/Neo1228/ATLAS/issues/35) |
| Q-006 | ? OPEN | 내부 settlement ledger와 PSP 지급 경계, fee·정산 주기·보류·정정 | PSP/지역 조사 뒤 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | ? OPEN | 지역·통화·세금·privacy·security/regulation·합성 데이터 범위 | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | ? OPEN | Workload profile, performance/recovery targets. 값은 합의 전 TBD. 제안 순서 [D-018](decision-log.md). | Gate 2 | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Requirements](../02-requirements/requirements-index.md) |
| Q-009 | ? OPEN | Java/Spring/DB/infra version, PayPal 제품별 API 범위 | ADR/구현 직전 공식 확인 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Reference Register](reference-register.md) |

## Q-008 측정 항목 — 모두 ? OPEN
- 요청률: offered/achieved RPS. Transaction TPS는 transaction 범위 정의; DB QPS는 DB 측정 시에만 사용.
- Workload: open/closed model, request/transaction mix, concurrency, payload, burst, 지속 시간.
- 결과: throughput, p95/p99 latency, error rate, queue depth/saturation, budget.
- 복구/환경: SLO, RTO, RPO, deployment 조건.
- 수치 목표는 합의 전 TBD. 제안 순서 (D-018, △ Proposed): Gate 2 profile → Gate 3 capacity model → 구현 승인 후 Gate 4 validation.

## ✓ 기존 제약
- 장기 범위 A/B/C 유지.
- PayPal Sandbox 우선; Fake PSP는 별도 장애 재현 도구.
- 실제 payment·개인정보·판매사 지급은 초기 범위에서 제외.
- Modular Monolith First는 △ Proposed, 확정 아키텍처 아님.
