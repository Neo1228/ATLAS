---
id: DOC-002
title: Assumptions and Open Questions
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 가정 및 미결정 사항

상태: `✓ Resolved` 해결, `? OPEN` 미정, `△ ASSUMPTION` 검증 전 가정. 일부 baseline을 정해도 세부 경계가 남으면 OPEN 유지.

| ID | 상태 | 질문/리스크 | 권장 논의 순서 | 추적 |
|---|---|---|---|---|
| Q-001 | ✓ Resolved | 가상 학습 시나리오로 시작. 실제 시장/사용자 검증은 없음. Buyer/seller 목표는 D-015~D-017에 기록. | 완료 | [D-005](decision-log.md) |
| Q-010 | ? OPEN | 판매 유형별 행동과 공통 목표 차이 | Gate 1; 기본 목표는 [Issue #19](https://github.com/Neo1228/ATLAS/issues/19)에서 정리 | [Personas & Jobs](../01-discovery/personas-and-jobs.md) |
| Q-002 | ? OPEN | 판매 단위(SKU/수량/좌석 등), 캠페인 재고와 전체 재고의 관계 | 유형별 행동과 함께 논의 | [Business Rules](../03-domain/business-rules.md), [Personas](../01-discovery/personas-and-jobs.md) |
| Q-003 | ? OPEN | Queue persistence, key scope/retention, payload change, expiry/re-entry, tie, bot, type policy | Q-002와 함께 합의 | baseline [Issue #24](https://github.com/Neo1228/ATLAS/issues/24); [Rules](../03-domain/business-rules.md) |
| Q-004 | ? OPEN | 예약 TTL 만료와 결제 진행/성공 경합 시 주문·재고·결제 결과 | Q-002 뒤 | [FR-002](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-005 | ? OPEN | Authorization/capture 시점, retry, UNKNOWN 결제, refund policy | Q-010 뒤 | [FR-002](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-006 | ? OPEN | 내부 settlement ledger와 PSP 지급 경계, fee·정산 주기·보류·정정 | PSP/지역 조사 뒤 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | ? OPEN | 지역·통화·세금·privacy·security/regulation·합성 데이터 범위 | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | ? OPEN | Workload profile, performance/recovery targets. 값은 합의 전 TBD. | Gate 2 | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Requirements](../02-requirements/requirements-index.md) |
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
