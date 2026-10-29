---
id: DOC-012
title: Milestone Log
status: Current
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---

# 마일스톤 로그
이 문서는 변경 이력이다. 현재 상태와 다음 단계는 [Roadmap](roadmap.md) 및 [Session Handoff](session-handoff.md)을 참조한다.

## 2026-10-29 — Gate 0 문서 기반
- ✓ Gate 0 문서/거버넌스 검토 승인. Gate 1 discovery 시작; 구현/기술 선택은 별도 승인 대상.
- 기준 문서: [docs index](../README.md). 범위, 거버넌스, discovery, requirements, domain, ADR, experiments, delivery records 포함.
- 주요 결정: D-005~D-014. 상세는 [Decision Log](../00-governance/decision-log.md).

| 묶음 | Issue | PR | Merge commit |
|---|---:|---:|---|
| WP-001 저장소 기반 | — | seed | `7ed5fac`; workflow `eb371ca`, `5151a15` |
| WP-002 governance | #5 | #9 | `6049755` |
| WP-003 discovery/requirements | #6 | #10 | `f73a7e9` |
| WP-004 ADR/experiments | #8 | #11 | `6b2b379` |
| WP-005 delivery records | #7 | #12 | `8c71869` |
| Git conventions | #2 | #4 | `a76016e` |
| PR batching | #15 | #16 | `80dd964` |
| Delivery sync | #17 | #18 | `4b5ca7b` |

- PR #1/#3: 승인된 이력/branch 수정 중 미병합 종료. 최종 내용은 PR #4에 포함.

## 2026-10-29 — Gate 1 discovery
- [Issue #19/#20](https://github.com/Neo1228/ATLAS/issues/19) → [PR #21](https://github.com/Neo1228/ATLAS/pull/21), commit `f583ce2`: 가상 Buyer/Seller 목표, capacity 계획. 실사용자 검증/성능 측정 없음.
- [Issue #22](https://github.com/Neo1228/ATLAS/issues/22) → [PR #23](https://github.com/Neo1228/ATLAS/pull/23), commit `e8a85ae`: queue fairness baseline D-019.
- [Issue #24](https://github.com/Neo1228/ATLAS/issues/24) → [PR #25](https://github.com/Neo1228/ATLAS/pull/25), commit `154c31e`: admission/retry/restart baseline D-020~D-022. Q-003 세부 경계 ? OPEN.

## 2026-10-29 — 문서 가독성·추적 기준
- [Issue #26](https://github.com/Neo1228/ATLAS/issues/26), [PR #27](https://github.com/Neo1228/ATLAS/pull/27) merged `a82e022`; closeout [PR #28](https://github.com/Neo1228/ATLAS/pull/28) merged `bc8d60b`.
- 범위: 문서 링크/인덱스, 독자용 용어·상태, 복잡한 Issue/PR 설명, Mermaid 기준, 필수 PR checklist.
- 검증: Markdown lint 24 files; 23 Markdown files/211 local links; YAML 4 files; checklist pass/fail smoke cases. PR #27에서 lint/checklist 통과.
- `main` required checks: `Markdown lint`, `PR checklist`. Strict checks/linear history 켬; force push/deletion 차단.
