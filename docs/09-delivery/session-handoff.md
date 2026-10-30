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
- ✓ Gate 0 승인 (2026-10-29). → Gate 1 discovery 진행 중.
- Gate 1: initial sale/queue scope [D-023/D-024](../00-governance/decision-log.md) ([PR #34](https://github.com/Neo1228/ATLAS/pull/34) merged). Hold/recovery [D-025](../00-governance/decision-log.md) ([Issue #35](https://github.com/Neo1228/ATLAS/issues/35)/[PR #36](https://github.com/Neo1228/ATLAS/pull/36) merged). Trace audit [Issue #38](https://github.com/Neo1228/ATLAS/issues/38) closed by [PR #39](https://github.com/Neo1228/ATLAS/pull/39) (`2897ff1`). [Issue #37](https://github.com/Neo1228/ATLAS/issues/37)/[PR #41](https://github.com/Neo1228/ATLAS/pull/41): `AUTHORIZE → CAPTURE`, claim after confirmed capture. [Issue #42](https://github.com/Neo1228/ATLAS/issues/42)/[PR #43](https://github.com/Neo1228/ATLAS/pull/43): release after confirmed non-payment and void/expiry. [Issue #44](https://github.com/Neo1228/ATLAS/issues/44)/[PR #45](https://github.com/Neo1228/ATLAS/pull/45): fixed checkout window. [Issue #46](https://github.com/Neo1228/ATLAS/issues/46)/[PR #47](https://github.com/Neo1228/ATLAS/pull/47): timer starts at confirmed hold; initial AUTHORIZE submission before expiry; no extension.
- 코드, runtime dependency, 실제 payment 호출, deployment 미착수. Gate 2/구현은 별도 승인 필요.

## 확정된 방향
- 장기 범위: 결제 신뢰성, 고동시성 한정판매, 정산·대사 ([Project Charter](../00-governance/project-charter.md)).
- 초기 시험: PayPal Sandbox와 Fake PSP. 실제 payment·개인정보·판매사 지급 제외.
- 가상 Buyer: 공정한 기회와 정확한 재고/예약 상태. 가상 Seller: 공정한 한정판매와 overselling 방지 ([Personas & Jobs](../01-discovery/personas-and-jobs.md)).
- Queue 기준 [D-019~D-022](../00-governance/decision-log.md)과 sale-open 기준 [D-024](../00-governance/decision-log.md) 유지. Payment [D-025~D-027](../00-governance/decision-log.md): PSP 요청 전 hold, authorize 후 capture, capture success 후 claim; TTL/cancel 시 capture 확인 및 active authorization void; confirmed non-payment와 void/expiry 후 release.

## 미정·다음
- [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md) Modular Monolith First: △ Proposed.
- [Q-003](../00-governance/assumptions-and-open-questions.md) 잔여 storage/key/payload/expiry/re-entry/tie/bot policy OPEN. [Q-002/Q-010](../00-governance/assumptions-and-open-questions.md)은 공유 재고·다른 판매 유형 미정.
- [Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md): [D-027~D-029](../00-governance/decision-log.md) define release boundary and checkout timer contract. Numeric TTL, completion-time data, provider deadlines, retry/key retention, escalation/compensation OPEN. [Issue #46](https://github.com/Neo1228/ATLAS/issues/46)/[PR #47](https://github.com/Neo1228/ATLAS/pull/47); no implementation approval.
- Performance 순서 ([D-018](../00-governance/decision-log.md), △ Proposed): Gate 2 workload profile → Gate 3 capacity estimate → implementation 승인 후 Gate 4 synthetic validation. RPS/TPS/QPS scope와 용어는 [Glossary](../01-discovery/glossary.md); 목표값·실측 없음 ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20)).

## 이력

| 작업 | 기록 |
|---|---|
| [WP-001](roadmap.md) | seed [`7ed5fac`](https://github.com/Neo1228/ATLAS/commit/7ed5fac); workflow [`eb371ca`](https://github.com/Neo1228/ATLAS/commit/eb371ca), [`5151a15`](https://github.com/Neo1228/ATLAS/commit/5151a15) |
| [WP-002~005](roadmap.md) | [PR #9](https://github.com/Neo1228/ATLAS/pull/9)/[#10](https://github.com/Neo1228/ATLAS/pull/10)/[#11](https://github.com/Neo1228/ATLAS/pull/11)/[#12](https://github.com/Neo1228/ATLAS/pull/12); [Issue #5](https://github.com/Neo1228/ATLAS/issues/5)/[#6](https://github.com/Neo1228/ATLAS/issues/6)/[#8](https://github.com/Neo1228/ATLAS/issues/8)/[#7](https://github.com/Neo1228/ATLAS/issues/7) |
| Git/PR 기준 | [PR #4](https://github.com/Neo1228/ATLAS/pull/4) ([Issue #2](https://github.com/Neo1228/ATLAS/issues/2)); [PR #16](https://github.com/Neo1228/ATLAS/pull/16) ([Issue #15](https://github.com/Neo1228/ATLAS/issues/15)); [PR #18](https://github.com/Neo1228/ATLAS/pull/18) ([Issue #17](https://github.com/Neo1228/ATLAS/issues/17)) |
| Persona/capacity | [PR #21](https://github.com/Neo1228/ATLAS/pull/21) ([Issue #19](https://github.com/Neo1228/ATLAS/issues/19)/[#20](https://github.com/Neo1228/ATLAS/issues/20)) |
| 초기 판매 시나리오 | [Issue #33](https://github.com/Neo1228/ATLAS/issues/33); [PR #34](https://github.com/Neo1228/ATLAS/pull/34) merged; [D-023/D-024](../00-governance/decision-log.md) |
| 결제 중 hold/recovery | [Issue #35](https://github.com/Neo1228/ATLAS/issues/35); [PR #36](https://github.com/Neo1228/ATLAS/pull/36) merged; [D-025](../00-governance/decision-log.md) |
| 문서·Issue·PR 추적 링크 | [Issue #38](https://github.com/Neo1228/ATLAS/issues/38) closed; [PR #39](https://github.com/Neo1228/ATLAS/pull/39) merged `2897ff1` |
| PayPal auth/capture flow | [Issue #37](https://github.com/Neo1228/ATLAS/issues/37); [PR #41](https://github.com/Neo1228/ATLAS/pull/41); selected `AUTHORIZE → CAPTURE`; [D-026](../00-governance/decision-log.md); implementation details OPEN |
| Payment TTL/recovery | [Issue #42](https://github.com/Neo1228/ATLAS/issues/42); [PR #43](https://github.com/Neo1228/ATLAS/pull/43); [D-027](../00-governance/decision-log.md); numeric deadlines OPEN |
| Checkout TTL policy | [Issue #44](https://github.com/Neo1228/ATLAS/issues/44); [PR #45](https://github.com/Neo1228/ATLAS/pull/45); [D-028](../00-governance/decision-log.md): fixed initial window; duration/start trigger TBD |
| Checkout timer contract | [Issue #46](https://github.com/Neo1228/ATLAS/issues/46); [PR #47](https://github.com/Neo1228/ATLAS/pull/47); [D-029](../00-governance/decision-log.md): starts on confirmed hold; AUTHORIZE before expiry; no extension |
| 문서 작성 기준 | [Issue #26](https://github.com/Neo1228/ATLAS/issues/26); [PR #27](https://github.com/Neo1228/ATLAS/pull/27) merged [`a82e022`](https://github.com/Neo1228/ATLAS/commit/a82e022); closeout [PR #28](https://github.com/Neo1228/ATLAS/pull/28) merged [`bc8d60b`](https://github.com/Neo1228/ATLAS/commit/bc8d60b) |

진행 상태는 [Roadmap](roadmap.md), 전체 변경 이력은 [Milestone Log](milestone-log.md). 새 작업은 Issue-first. 관련 commit/Issue는 하나의 reviewable PR로 통합 ([CONTRIBUTING](../../CONTRIBUTING.md)).
