---
id: DOC-002
title: Assumptions and Open Questions
status: Draft
owner: Project Owner
updated: 2026-11-02
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
| Q-010 | ? OPEN | 초기 Gate 2는 quantity-SKU 1종 ([D-023/D-036](decision-log.md)). [Issue #64](https://github.com/Neo1228/ATLAS/issues/64), [PR #65](https://github.com/Neo1228/ATLAS/pull/65) 비교 후 assigned-seat/time-slot은 추가하지 않기로 선택. 실제 Buyer/Seller 조사 없음; 다른 판매 유형의 장기 범위와 행동은 OPEN. | Gate 2 첫 scenario 선택 완료; 장기 범위는 별도 탐색 | [Personas & Jobs](../01-discovery/personas-and-jobs.md), [REF-045~049](reference-register.md) |
| Q-002 | ? OPEN | [D-023/D-035](decision-log.md): 초기 기준은 대체 가능한 SKU 1종, campaign 소유 pool, reservation당 1개, 수량 conservation. 공유/global/location 재고, 다중 수량, 판매 중 조정·재입고, post-claim 취소·환불 정책은 후속 범위로 OPEN. | Q-010과 함께 논의; 초기 경계는 선택 | [BR-002](../03-domain/business-rules.md), [INV-001](../03-domain/invariants.md), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63) |
| Q-003 | ? OPEN | Initial queue contract: sale-open admission; D-034 same key/payload reuses a ticket, mismatch rejected, expired re-entry requires a new key. D-041 local slice: bounded FIFO window; campaign-scoped key; payload comparison uses `skuId` + synthetic `buyerRef`; configurable per-campaign ticket TTL; eligibility is sale-open + matching campaign/SKU. Local schema/counter implementation is in Issue #72. Production K, TTL value, tombstone cleanup, additional payload fields, schema/lock recovery, and auth/bot policy remain OPEN. PostgreSQL/counter-row transaction selected ([D-037/D-039](decision-log.md)). | After local slice, decide production TTL/retention and security policy | [Issue #72](https://github.com/Neo1228/ATLAS/issues/72), [D-019~D-021/D-034/D-039/D-041](decision-log.md), [BR-001](../03-domain/business-rules.md), [INV-002](../03-domain/invariants.md) |
| Q-004 | ? OPEN | [D-025~D-030](decision-log.md): PSP 전 hold, AUTHORIZE → CAPTURE, capture success 후 claim. Confirmed hold에서 10분 synthetic timer; expiry 전 AUTHORIZE 제출, 연장 없음. Production TTL·SLO·분포 OPEN. | Q-005/Q-008과 lifecycle·measurement 함께 논의 | [FR-002](../02-requirements/requirements-index.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48) |
| Q-005 | ? OPEN | UNKNOWN≠failure; same-attempt reconcile before any new payment attempt. PENDING/UNKNOWN keeps inventory hold. Confirmed non-payment plus required authorization void/expiry before release. D-042 local guard blocks another payment submission for the same campaign + synthetic `buyerRef` while an earlier attempt is unresolved; it is neither authentication nor a completed-purchase limit. Fake PSP provider state is in-memory and resets on app restart; provider-side restart reconciliation remains unverified. Provider/API deadline, retry/key retention, escalation, and compensation remain OPEN. | Q-004/Q-008 lifecycle and measurement review | [FR-002](../02-requirements/requirements-index.md), [D-025/D-027/D-042](decision-log.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #48](https://github.com/Neo1228/ATLAS/issues/48), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72), [REF-034~041](reference-register.md) |
| Q-006 | ? OPEN | 내부 settlement ledger와 PSP 지급 경계, fee·정산 주기·보류·정정 | PSP/지역 조사 뒤 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | ? OPEN | 지역·통화·세금·privacy·security/regulation·합성 데이터 범위 | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | ? OPEN | [D-018/D-031~D-033](decision-log.md): full Buyer journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress; production demand 아님. Burst, audience evidence, think time, environment, SLO OPEN. [EXP-012](../08-experiments/experiment-registry.md): DB-first baseline과 optional queue/cache 비교는 측정된 bottleneck 이후. | Gate 4 load test; assumptions/report-only | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [#51](https://github.com/Neo1228/ATLAS/issues/51), [#54](https://github.com/Neo1228/ATLAS/issues/54), [#56](https://github.com/Neo1228/ATLAS/issues/56), [Issue #68](https://github.com/Neo1228/ATLAS/issues/68), [NFR-002](../02-requirements/requirements-index.md), [EXP-010/012](../08-experiments/experiment-registry.md) |
| Q-009 | ? OPEN | Target stack selected: JDK 25 LTS, Spring Boot 4.1.2, PostgreSQL 18.6, Gradle Wrapper 9.8.2; Spring Modulith 2.1.2 and Testcontainers 2.0.5 test scope ([D-040](decision-log.md)). Boot 4 Flyway wiring uses `spring-boot-starter-flyway`. CI uses Temurin 25 only as build/test runtime; production JDK vendor/distribution/license, infrastructure/deployment, and PayPal product/API remain OPEN. | Verify compatibility during local implementation; choose deployment/provider later | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72), [REF-059~067](reference-register.md) |

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
- Modular Monolith First와 stack 선택 승인 ([D-037/D-040](decision-log.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md)). Initial module/data/queue runtime contract는 [D-038/D-039/D-041](decision-log.md). JDK distribution/licensing, physical schema/isolation/locking, infrastructure/deployment, PayPal product/API, production targets는 OPEN ([Q-003/Q-008/Q-009](assumptions-and-open-questions.md)).
