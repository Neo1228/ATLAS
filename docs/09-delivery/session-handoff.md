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
- Gate 1, Issue #33에서 선택한 범위: campaign 하나, 수량형 SKU 1종, 독립 pool (D-023); sale open 전 queue 미운영, open 후 FIFO (D-024; D-019~D-022 유지). [PR #34](https://github.com/Neo1228/ATLAS/pull/34) 검토 중.
- 코드, runtime dependency, 실제 payment 호출, deployment 미착수. Gate 2/구현은 별도 승인 필요.

## 확정된 방향
- 장기 범위: 결제 신뢰성, 고동시성 한정판매, 정산·대사 ([Project Charter](../00-governance/project-charter.md)).
- 초기 시험: PayPal Sandbox와 Fake PSP. 실제 payment·개인정보·판매사 지급 제외.
- 가상 Buyer: 공정한 기회와 정확한 재고/예약 상태. 가상 Seller: 공정한 한정판매와 overselling 방지 ([Personas & Jobs](../01-discovery/personas-and-jobs.md)).
- Queue 기준: D-019~D-022 유지; 초기 sale-open 경계는 D-024. Retry는 기존 ticket 재사용, restart 후 sequence 보존.

## 미정·다음
- ADR-0001 Modular Monolith First: △ Proposed.
- Q-003 잔여 storage/key/payload/expiry/re-entry/tie/bot 정책은 OPEN. Q-002/Q-010은 공유 재고·다른 판매 유형을 계속 미정으로 유지.
- Performance 순서 (D-018, △ Proposed): Gate 2 workload profile → Gate 3 capacity estimate → 구현 승인 후 Gate 4 synthetic validation. RPS/TPS/QPS scope와 용어는 [Glossary](../01-discovery/glossary.md); 목표값·실측 없음 ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20)).
- Q-002~Q-009: [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md). 다음 Gate/구현 진입은 별도 승인 전 보류.

## 이력

| 작업 | 기록 |
|---|---|
| WP-001 | seed `7ed5fac`; workflow `eb371ca`, `5151a15` |
| WP-002~005 | PR #9/#10/#11/#12; Issue #5/#6/#8/#7 |
| Git/PR 기준 | PR #4 (Issue #2); batching PR #16 (Issue #15); delivery PR #18 (Issue #17) |
| Persona/capacity | PR #21 (Issues #19/#20) |
| 초기 판매 시나리오 | Issue #33; PR #34 open; D-023/D-024 |
| 문서 탐색·작성 기준 | Issue #26; PR #27 merged `a82e022`, closeout PR #28 merged `bc8d60b` |

진행 상태는 [Roadmap](roadmap.md), 전체 변경 이력은 [Milestone Log](milestone-log.md). 새 작업은 Issue-first. 관련 commit/Issue는 하나의 reviewable PR로 통합 ([CONTRIBUTING](../../CONTRIBUTING.md)).
