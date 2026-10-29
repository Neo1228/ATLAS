---
id: DOC-009
title: Session Handoff
status: Current
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 세션 인계

## 현재
- 저장소: 비공개 [Neo1228/ATLAS](https://github.com/Neo1228/ATLAS).
- ✓ Gate 0 승인 (2026-10-29). → Gate 1 discovery 진행 중.
- Queue baseline은 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록. Q-003 저장·만료·tie·bot·유형별 policy는 ? OPEN.
- 코드, runtime dependency, 실제 payment 호출, deployment 미착수. Gate 2/구현은 별도 승인 필요.

## 확정된 방향
- 장기 범위: 결제 신뢰성, 고동시성 한정판매, 정산·대사 ([Project Charter](../00-governance/project-charter.md)).
- 초기 시험: PayPal Sandbox와 Fake PSP. 실제 payment·개인정보·판매사 지급 제외.
- 가상 Buyer: 공정한 기회와 정확한 재고/예약 상태. 가상 Seller: 공정한 한정판매와 overselling 방지 ([Personas & Jobs](../01-discovery/personas-and-jobs.md)).
- Queue baseline: 자격 통과 후 admission 시 sequence 발급; 같은 논리 요청 retry는 기존 ticket 재사용; restart 후 sequence 보존 (D-019~D-022).

## 미정·다음
- ADR-0001 Modular Monolith First: △ Proposed.
- Q-003 잔여 boundary를 한 작업 묶음으로 논의. 경계별 Issue 분할 금지.
- Performance 순서 (D-018, △ Proposed): Gate 2 workload profile → Gate 3 capacity estimate → 구현 승인 후 Gate 4 synthetic validation. RPS/TPS/QPS scope와 용어는 [Glossary](../01-discovery/glossary.md); 목표값·실측 없음 ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20)).
- Q-002~Q-009: [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md). 다음 Gate/구현 진입은 별도 승인 전 보류.

## 이력

| 작업 | 기록 |
|---|---|
| WP-001 | seed `7ed5fac`; workflow `eb371ca`, `5151a15` |
| WP-002~005 | PR #9/#10/#11/#12; Issue #5/#6/#8/#7 |
| Git/PR 기준 | PR #4 (Issue #2); batching PR #16 (Issue #15); delivery PR #18 (Issue #17) |
| Persona/capacity | PR #21 (Issues #19/#20) |
| Queue fairness/lifecycle | PR #23 (Issue #22), commit `eb95982`; PR #25 (Issue #24), commit `154c31e` |
| 문서 탐색·작성 기준 | Issue #26; PR #27 merged `a82e022`, closeout PR #28 merged `bc8d60b` |

진행 상태는 [Roadmap](roadmap.md), 전체 변경 이력은 [Milestone Log](milestone-log.md). 새 작업은 Issue-first. 관련 commit/Issue는 하나의 reviewable PR로 통합 ([CONTRIBUTING](../../CONTRIBUTING.md)).
