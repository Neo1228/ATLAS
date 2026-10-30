---
id: DOC-009
title: Session Handoff
status: Current
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 세션 인계

## 현재
- 저장소: 비공개 [Neo1228/ATLAS](https://github.com/Neo1228/ATLAS).
- ✓ Gate 0 승인 (2026-10-29). Gate 1 진행 중.
- 초기 판매 범위: [D-023/D-024](../00-governance/decision-log.md), [PR #34](https://github.com/Neo1228/ATLAS/pull/34) 병합.
- Queue ticket 수명주기: [D-034](../00-governance/decision-log.md), [Issue #60](https://github.com/Neo1228/ATLAS/issues/60), [PR #61](https://github.com/Neo1228/ATLAS/pull/61).
- Inventory baseline: [D-035](../00-governance/decision-log.md), [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [BR-002](../03-domain/business-rules.md), [INV-001](../03-domain/invariants.md). Reservation당 1개; 재고 합계 보존. PR 진행 중.
- 결제 복구 [D-025~D-030](../00-governance/decision-log.md):
  - Issues: [#35](https://github.com/Neo1228/ATLAS/issues/35), [#37](https://github.com/Neo1228/ATLAS/issues/37), [#42](https://github.com/Neo1228/ATLAS/issues/42), [#44](https://github.com/Neo1228/ATLAS/issues/44), [#46](https://github.com/Neo1228/ATLAS/issues/46), [#48](https://github.com/Neo1228/ATLAS/issues/48).
  - PRs: [#36](https://github.com/Neo1228/ATLAS/pull/36), [#41](https://github.com/Neo1228/ATLAS/pull/41), [#43](https://github.com/Neo1228/ATLAS/pull/43), [#45](https://github.com/Neo1228/ATLAS/pull/45), [#47](https://github.com/Neo1228/ATLAS/pull/47), [#49](https://github.com/Neo1228/ATLAS/pull/49).
- Gate 2 profile [D-031~D-033](../00-governance/decision-log.md): full Buyer journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress; report-only.
  - 추적 Issue: [#51](https://github.com/Neo1228/ATLAS/issues/51), [#54](https://github.com/Neo1228/ATLAS/issues/54), [#56](https://github.com/Neo1228/ATLAS/issues/56).
- 병합 PR:
  - [PR #53](https://github.com/Neo1228/ATLAS/pull/53) → [5266d14](https://github.com/Neo1228/ATLAS/commit/5266d14)
  - [PR #55](https://github.com/Neo1228/ATLAS/pull/55) → [a82b454](https://github.com/Neo1228/ATLAS/commit/a82b454)
  - [PR #57](https://github.com/Neo1228/ATLAS/pull/57) → [6cf28dd](https://github.com/Neo1228/ATLAS/commit/6cf28dd)
  - OPEN: production demand/SLO, burst factor/duration, capacity, implementation.
  - 코드·runtime dependency·실제 payment 호출·deployment 미착수. Gate 2·구현 진입은 별도 승인 대상.

## 확정된 방향
- 장기 범위: 결제 신뢰성, 고동시성 한정판매, 정산·대사 ([Project Charter](../00-governance/project-charter.md)).
- 초기 시험: PayPal Sandbox와 Fake PSP. 실제 결제·개인정보·판매사 지급 제외.
- 가상 Buyer: 공정한 구매 기회와 정확한 재고/예약 상태. 가상 Seller: 공정한 한정판매와 overselling 방지 ([Personas & Jobs](../01-discovery/personas-and-jobs.md)).
- Queue: [D-019~D-022/D-024/D-034](../00-governance/decision-log.md). Retry는 동일 key·payload일 때 기존 ticket 재사용; payload 변경은 거절. Expiry 뒤 re-entry는 새 key와 tail sequence ([Issue #60](https://github.com/Neo1228/ATLAS/issues/60)).
- Payment: PSP 요청 전 hold; AUTHORIZE/CAPTURE; capture success 확인 후 inventory claim.
- TTL/cancel 시 capture 확인. Active authorization은 void. 확정된 non-payment와 void/expiry 확인 후에만 stock release ([D-025~D-027](../00-governance/decision-log.md)).

## 미정·다음
- [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md) Modular Monolith First: △ Proposed.
- [Q-003](../00-governance/assumptions-and-open-questions.md) OPEN: key scope/retention, payload 비교 필드, expiry 조건/기간, storage/atomicity, bot 방어, 다른 판매 유형 ([Issue #60](https://github.com/Neo1228/ATLAS/issues/60)).
- 성능 검증 순서 ([D-018](../00-governance/decision-log.md), △ Proposed): Gate 2 profile → Gate 3 capacity estimate → 구현 승인 후 Gate 4 synthetic test. RPS/TPS/QPS 정의는 [Glossary](../01-discovery/glossary.md); 목표·실측 없음 ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20)).

## 이력

| 작업 | 기록 |
|---|---|
| [WP-001](roadmap.md) | seed [`7ed5fac`](https://github.com/Neo1228/ATLAS/commit/7ed5fac); workflow [`eb371ca`](https://github.com/Neo1228/ATLAS/commit/eb371ca), [`5151a15`](https://github.com/Neo1228/ATLAS/commit/5151a15) |
| [WP-002~005](roadmap.md) | [PR #9](https://github.com/Neo1228/ATLAS/pull/9)/[#10](https://github.com/Neo1228/ATLAS/pull/10)/[#11](https://github.com/Neo1228/ATLAS/pull/11)/[#12](https://github.com/Neo1228/ATLAS/pull/12); [Issue #5](https://github.com/Neo1228/ATLAS/issues/5)/[#6](https://github.com/Neo1228/ATLAS/issues/6)/[#8](https://github.com/Neo1228/ATLAS/issues/8)/[#7](https://github.com/Neo1228/ATLAS/issues/7) |
| Git/PR 기준 | [PR #4](https://github.com/Neo1228/ATLAS/pull/4) ([Issue #2](https://github.com/Neo1228/ATLAS/issues/2)); [PR #16](https://github.com/Neo1228/ATLAS/pull/16) ([Issue #15](https://github.com/Neo1228/ATLAS/issues/15)); [PR #18](https://github.com/Neo1228/ATLAS/pull/18) ([Issue #17](https://github.com/Neo1228/ATLAS/issues/17)) |
| 페르소나·용량 계획 | [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), [#20](https://github.com/Neo1228/ATLAS/issues/20); [PR #21](https://github.com/Neo1228/ATLAS/pull/21) |
| 초기 판매 범위 | [Issue #33](https://github.com/Neo1228/ATLAS/issues/33); [PR #34](https://github.com/Neo1228/ATLAS/pull/34) 병합; [D-023/D-024](../00-governance/decision-log.md) |
| 결제 중 hold·복구 | [Issue #35](https://github.com/Neo1228/ATLAS/issues/35); [PR #36](https://github.com/Neo1228/ATLAS/pull/36) 병합; [D-025](../00-governance/decision-log.md) |
| 문서 추적성 정비 | [Issue #38](https://github.com/Neo1228/ATLAS/issues/38) 종료; [PR #39](https://github.com/Neo1228/ATLAS/pull/39) 병합 [`2897ff1`](https://github.com/Neo1228/ATLAS/commit/2897ff1) |
| PayPal 승인·청구 | [Issue #37](https://github.com/Neo1228/ATLAS/issues/37); [PR #41](https://github.com/Neo1228/ATLAS/pull/41); [D-026](../00-governance/decision-log.md). 구현 세부 OPEN |
| 결제 hold 복구 | [Issue #42](https://github.com/Neo1228/ATLAS/issues/42); [PR #43](https://github.com/Neo1228/ATLAS/pull/43); [D-027](../00-governance/decision-log.md). 기한 수치 OPEN |
| Checkout 시간 제한 | [Issue #44](https://github.com/Neo1228/ATLAS/issues/44); [PR #45](https://github.com/Neo1228/ATLAS/pull/45); [D-028](../00-governance/decision-log.md). 방식 선택, duration/start trigger OPEN |
| Buyer timer 계약 | [Issue #46](https://github.com/Neo1228/ATLAS/issues/46); [PR #47](https://github.com/Neo1228/ATLAS/pull/47); [D-029](../00-governance/decision-log.md). Confirmed hold부터 시작, expiry 전 AUTHORIZE 제출, extension 없음 |
| 문서 작성 기준 | [Issue #26](https://github.com/Neo1228/ATLAS/issues/26); [PR #27](https://github.com/Neo1228/ATLAS/pull/27) 병합 [`a82e022`](https://github.com/Neo1228/ATLAS/commit/a82e022); [PR #28](https://github.com/Neo1228/ATLAS/pull/28) closeout 병합 [`bc8d60b`](https://github.com/Neo1228/ATLAS/commit/bc8d60b) |
| Queue ticket 수명주기 | [Issue #60](https://github.com/Neo1228/ATLAS/issues/60); [PR #61](https://github.com/Neo1228/ATLAS/pull/61); [D-034](../00-governance/decision-log.md): retry·payload mismatch·expiry 후 재입장 기준 |
| Inventory reservation/conservation | [Issue #62](https://github.com/Neo1228/ATLAS/issues/62); [D-035](../00-governance/decision-log.md); [BR-002](../03-domain/business-rules.md); [INV-001](../03-domain/invariants.md); [EXP-001](../08-experiments/experiment-registry.md) |

- 진행 현황: [Roadmap](roadmap.md).
- 완료 이력: [Milestone Log](milestone-log.md).
- 작업 방식: Issue-first. 관련 commit은 같은 목적이면 PR 하나로 통합 ([CONTRIBUTING](../../CONTRIBUTING.md)).
