---
id: DOC-003
title: Decision Log
status: Current
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 결정 로그

사용자 승인 전 제안은 확정이 아니다. 결정 변경 시 새 항목/ADR을 만들고 과거 기록을 소급 수정하지 않는다.

| ID | 상태 | 요약 | 근거/추적 |
|---|---|---|---|
| D-001 | △ Proposed | Modular Monolith First 초기 가설 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md) |
| D-002 | ✓ Confirmed | A/B/C 세 영역을 장기 범위로 유지하고 순차 진행 | [Project Charter](project-charter.md), [Roadmap](../09-delivery/roadmap.md) |
| D-003 | ✓ Confirmed | PayPal Sandbox 우선. 장애 재현은 별도 Fake PSP | [Project Charter](project-charter.md), [Glossary](../01-discovery/glossary.md) |
| D-004 | ✓ Confirmed | 초기 실험에서 실제 결제·개인정보·판매사 지급 제외 | [Project Charter](project-charter.md) |
| D-005 | ✓ Confirmed | 사용자/시장 검증 주장 없이 가상 시나리오 사용. 공통 목표는 정했으며 유형별 행동은 [Q-010](assumptions-and-open-questions.md) OPEN | [Q-001](assumptions-and-open-questions.md) resolved; [Q-010](assumptions-and-open-questions.md) OPEN |
| D-006 | ✓ Confirmed | 여러 판매 유형 탐색 선호. 첫 합성 시나리오만 수량형 SKU 1종으로 시작; 전체 유형·순서·구현 범위는 [Q-002/Q-010](assumptions-and-open-questions.md) OPEN | [Issue #33](https://github.com/Neo1228/ATLAS/issues/33) |
| D-007 | ✓ Confirmed | 가상 시나리오는 Buyer/Seller부터 모델링 | [Q-010](assumptions-and-open-questions.md) |
| D-008 | ✓ Confirmed | 작은 논리 commit, 관련 작업은 PR로 추적 | [Change Control](change-control.md) |
| D-009 | ✓ Confirmed | PR template/추적 규칙을 먼저 정하고, Gate 0 문서 전체를 한 번에 올리지 않음 | [PR Template](../../.github/PULL_REQUEST_TEMPLATE.md), [Change Control](change-control.md) |
| D-010 | ✓ Confirmed | 첫 가상 시나리오는 재고/예약 경합과 공정한 구매 기회. 초기 단위와 pool은 D-023; acceptance는 미정 | [Q-002/Q-010](assumptions-and-open-questions.md) |
| D-011 | ✓ Confirmed | 계획 작업은 Issue-first, 기준·범위·검증·PR 연결. 커밋마다 Issue 생성 금지 | [Issue #2](https://github.com/Neo1228/ATLAS/issues/2), [CONTRIBUTING](../../CONTRIBUTING.md) |
| D-012 | ✓ Confirmed | 공개 이력 수정은 원칙적으로 금지. 예외는 명시 승인·별도 Issue·대상 SHA/백업 ref·`--force-with-lease`·branch protection 즉시 복원 | [Issue #2](https://github.com/Neo1228/ATLAS/issues/2) |
| D-013 | ✓ Confirmed | 관련 Issue/commit은 한 PR로 묶고, 독립 목적/위험은 분리 | [Issue #15](https://github.com/Neo1228/ATLAS/issues/15), [CONTRIBUTING](../../CONTRIBUTING.md) |
| D-014 | ✓ Confirmed | Gate 0 승인, Gate 1 시작. Gate 2·설계·구현은 별도 승인 전 보류 | 2026-10-29; [Issue #19](https://github.com/Neo1228/ATLAS/issues/19) |
| D-015 | ✓ Confirmed | 공통 Buyer/Seller 목표 + 유형별 차이를 탐색 | [Issue #19](https://github.com/Neo1228/ATLAS/issues/19) |
| D-016 | ✓ Confirmed | 가상 Buyer goal: 공정한 구매 기회, 정확한 재고/예약 상태 | [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), [Q-010](assumptions-and-open-questions.md) |
| D-017 | ✓ Confirmed | 가상 Seller goal: 공정한 한정판매, overselling 방지 | [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), [Q-010](assumptions-and-open-questions.md) |
| D-018 | △ Proposed | Gate 2 workload profile → Gate 3 capacity estimate → Gate 4 synthetic validation. 수치 TBD, 미승인 | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Q-008](assumptions-and-open-questions.md) |
| D-019 | ✓ Confirmed | 가상 queue fairness 기준: 서버 sequence 오름차순. 운영 공정성 미검증 | [Issue #22](https://github.com/Neo1228/ATLAS/issues/22), [Q-003](assumptions-and-open-questions.md) |
| D-020 | ✓ Confirmed | 자격 통과 후 queue admission 시 server sequence 발급 | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Q-003](assumptions-and-open-questions.md) |
| D-021 | ✓ Confirmed | 같은 논리 요청의 retry/중복 전달은 기존 ticket/sequence 재사용 | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Q-003](assumptions-and-open-questions.md) |
| D-022 | ✓ Confirmed | service/worker 재시작 후 발급된 ticket/sequence 보존 | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Q-003](assumptions-and-open-questions.md) |
| D-023 | ✓ Confirmed | 첫 합성 판매 시나리오: campaign 1개, 수량형 SKU 1종, campaign 소유의 독립 재고 pool. 다른 유형/공유 재고는 이 시나리오에서 제외 | [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Q-002/Q-010](assumptions-and-open-questions.md); [REF-023/025](reference-register.md) |
| D-024 | ✓ Confirmed | 초기 시나리오는 sale open 전 admission 없음; open 후 자격 통과 요청을 순번화하고 기존 FIFO 유지. Pre-sale lottery 없음; D-019~D-022 변경 없음 | [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Q-003](assumptions-and-open-questions.md); [REF-024/026](reference-register.md) |
| D-025 | ✓ Confirmed | 초기 lifecycle: PSP 요청 직전 hold; terminal success→claim, 확정 실패→release. UNKNOWN은 같은 attempt로 reconcile하고, 결과 전 새 attempt 금지. TTL 중 UNKNOWN이면 reconciliation까지 pool unavailable (`EXPIRED_PENDING_PAYMENT`). | [Issue #35](https://github.com/Neo1228/ATLAS/issues/35), [Q-004/Q-005](assumptions-and-open-questions.md); [REF-023/027~029](reference-register.md) |
| D-026 | ✓ Confirmed | 초기 PayPal sale scenario는 `AUTHORIZE → CAPTURE`. Authorization success는 중간 단계; capture success 확인 후에만 inventory claim. | [Issue #37](https://github.com/Neo1228/ATLAS/issues/37), [Q-005/Q-009](assumptions-and-open-questions.md); [FR-002](../02-requirements/requirements-index.md), [BR-003/INV-003](../03-domain/business-rules.md); [REF-030~033](reference-register.md) |

D-026은 초기 scenario의 순서와 claim 기준만 결정. Auth/capture/void failure 및 cancellation recovery, expiry, retry/retention, TTL·compensation은 [Q-004/Q-005/Q-009](assumptions-and-open-questions.md)에서 계속 OPEN. 구현 승인 아님.

✓ Gate 0 승인. Gate 1 진행 중. Gate 2/3과 구현 진입은 미승인.
