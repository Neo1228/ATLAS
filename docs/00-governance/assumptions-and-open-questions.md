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
| Q-002 | ? OPEN | [D-023/D-035](decision-log.md): 초기 기준은 대체 가능한 SKU 1종, campaign 소유 pool, reservation당 1개, 수량 conservation. 공유/global/location 재고, 다중 수량, 판매 중 조정·재입고, post-claim 취소·환불 정책은 후속 범위로 OPEN. | Q-010과 함께 논의; 초기 경계는 선택 | [BR-002](../03-domain/business-rules.md), [INV-001](../03-domain/invariants.md), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63) |
| Q-003 | ? OPEN | 초기 시나리오는 sale open 전 admission 없음, open 후 [D-019/D-020](decision-log.md). [D-034](decision-log.md): active ticket의 same-key/same-payload retry는 기존 ticket 반환; payload 변경 거절; expiry 후 재입장은 새 key와 tail sequence. OPEN: key scope/retention, payload 비교 필드, expiry trigger/duration, storage/atomicity, bot 방어, 다른 판매 유형. | Q-002/Q-010과 queue lifecycle을 함께 논의; 항목별 Issue 분리 금지 | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Issue #60](https://github.com/Neo1228/ATLAS/issues/60), [BR-001](../03-domain/business-rules.md) |
| Q-004 | ? OPEN | [D-025~D-030](decision-log.md): PSP 전 hold, AUTHORIZE → CAPTURE, capture success 후 claim. Confirmed hold에서 10분 synthetic timer; expiry 전 AUTHORIZE 제출, 연장 없음. Production TTL·SLO·분포 OPEN. | Q-005/Q-008과 lifecycle·measurement 함께 논의 | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48) |
| Q-005 | ? OPEN | UNKNOWN≠failure; same-attempt reconcile 전 새 attempt 금지. PENDING/UNKNOWN 동안 hold. Confirmed non-payment 후 active authorization void; void/expiry 확인 후 release. Provider/API deadline은 checkout TTL과 별개. Retry/key retention/escalation/compensation OPEN. | Q-004와 함께 lifecycle 결정 | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48), [REF-034~041](reference-register.md) |
| Q-006 | ? OPEN | 내부 settlement ledger와 PSP 지급 경계, fee·정산 주기·보류·정정 | PSP/지역 조사 뒤 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | ? OPEN | 지역·통화·세금·privacy·security/regulation·합성 데이터 범위 | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | ? OPEN | [D-018/D-031~D-033](decision-log.md): full Buyer journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress; production demand 아님. Burst, audience evidence, think time, environment, SLO OPEN. | Gate 2; 가정을 명시해 하나의 profile로 정리 | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [#51](https://github.com/Neo1228/ATLAS/issues/51), [#54](https://github.com/Neo1228/ATLAS/issues/54), [#56](https://github.com/Neo1228/ATLAS/issues/56), [NFR-002](../02-requirements/requirements-index.md), [EXP-010](../08-experiments/experiment-registry.md) |
| Q-009 | ? OPEN | Java/Spring/DB/infra version, PayPal 제품별 API 범위 | ADR/구현 직전 공식 확인 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Reference Register](reference-register.md) |

## Q-008 측정 계약 — report-only, 일부 test input 선택
- 기준 부하: Buyer 10,000 sessions/60m (~2.8 sessions/s); 같은 window에서 2×/4× stress (~5.6/11.1 sessions/s). 합성 입력.
- 도착률: offered/admitted sessions/s와 generator에서 누락된 session/iteration.
- Request: stage별 offered/achieved RPS. DB query 범위를 측정할 때만 QPS.
- Transaction: 완료된 Buyer-session TPS만 계산. PENDING/UNKNOWN은 in-flight/backlog.
- OPEN: 별도 burst multiplier/duration, arrival curve/think time, phase duration, concurrency, environment, production target.
- 결과: p95/p99 latency 경계, errors, expiry/abandonment, queue/hold occupancy, resource saturation, oversell invariant. Pass/fail SLO 없음.

## ✓ 기존 제약
- 장기 범위 A/B/C 유지.
- PayPal Sandbox 우선; Fake PSP는 별도 장애 재현 도구.
- 실제 payment·개인정보·판매사 지급은 초기 범위에서 제외.
- Modular Monolith First는 △ Proposed, 확정 아키텍처 아님.
