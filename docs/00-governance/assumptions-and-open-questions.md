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
| Q-004 | ? OPEN | [D-025~D-030](decision-log.md): PSP request before hold; `AUTHORIZE → CAPTURE`; capture success→claim; fixed timer from confirmed hold; initial `AUTHORIZE` before expiry; expiry closes checkout without extension. Initial synthetic profile uses 10 minutes ([D-030](decision-log.md)); production TTL, Buyer completion SLO and measured distribution remain OPEN. | Q-005/Q-008와 함께 lifecycle/measurement 논의 | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48) |
| Q-005 | ? OPEN | [D-025~D-030](decision-log.md): UNKNOWN은 failure 아님; same attempt reconcile. Capture success만 claim; `PENDING`/UNKNOWN 동안 hold. Confirmed non-payment 후 active authorization void; confirmed void/expiry 후 release. Provider/API/recovery deadlines are separate from synthetic checkout TTL. Retry/key retention, deadlines, escalation/compensation OPEN. | Q-004와 lifecycle 정책 합의 | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48); [REF-034~041](reference-register.md) |
| Q-006 | ? OPEN | 내부 settlement ledger와 PSP 지급 경계, fee·정산 주기·보류·정정 | PSP/지역 조사 뒤 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | ? OPEN | 지역·통화·세금·privacy·security/regulation·합성 데이터 범위 | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | ? OPEN | [D-018/D-031/D-032](decision-log.md): profile shape selected (full Buyer session; hybrid open arrivals + sequential session steps; baseline/burst/step-up). Use synthetic scale ladder, offered Buyer sessions/s as primary, report-only acceptance. Numeric values, phase durations, think time, concurrency, environment, thresholds and production targets remain TBD. | Gate 2; settle campaign assumptions/parameters as one workload profile | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Issue #54](https://github.com/Neo1228/ATLAS/issues/54), [NFR-002](../02-requirements/requirements-index.md), [EXP-010](../08-experiments/experiment-registry.md) |
| Q-009 | ? OPEN | Java/Spring/DB/infra version, PayPal 제품별 API 범위 | ADR/구현 직전 공식 확인 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Reference Register](reference-register.md) |

## Q-008 measurement contract — fields selected; values OPEN
- Arrival: offered/admitted Buyer sessions/s; dropped sessions/iterations from the generator.
- Requests: offered/achieved request RPS by stage; QPS only for explicitly measured DB query scope.
- Transactions: resolved Buyer-session TPS only; unresolved PENDING/UNKNOWN remains in-flight/backlog.
- Shape: hybrid open arrivals with sequential dependent steps per Buyer; baseline/burst/step-up. Numeric rates, phase duration, ramp and concurrency TBD.
- Results: p95/p99 latency with boundaries, errors, expiry/abandonment, queue/hold occupancy, resource saturation, oversell invariant.
- Acceptance: report-only; no pass/fail SLO. Environment, target thresholds and measured results remain TBD.

## ✓ 기존 제약
- 장기 범위 A/B/C 유지.
- PayPal Sandbox 우선; Fake PSP는 별도 장애 재현 도구.
- 실제 payment·개인정보·판매사 지급은 초기 범위에서 제외.
- Modular Monolith First는 △ Proposed, 확정 아키텍처 아님.
