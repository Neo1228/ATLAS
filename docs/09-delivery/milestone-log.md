---
id: DOC-012
title: Milestone Log
status: Current
owner: Project Owner
updated: 2026-10-30
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

## 2026-10-29 — 전체 문서 가독성 정비
- 범위: 24개 Markdown 문서와 GitHub forms/templates 전반의 짧은 문장, 개조식 종결, status 기호, 수치·단계 표기.
- 기준: 사실/정책/미정 경계 보존. 명사형을 강제하지 않고 자연스러운 의미 우선.
- 추적: [Issue #29](https://github.com/Neo1228/ATLAS/issues/29), [PR #30](https://github.com/Neo1228/ATLAS/pull/30), commit `3ff5d49`.
- 로컬 확인: Markdown lint 통과, 138개 상대 링크 resolve, YAML 4개 parse, Cyrillic 검색 결과 없음, `git diff --check` 통과.

## 2026-10-29 — 성능·부하 용어와 측정 범위
- 추적: [Issue #31](https://github.com/Neo1228/ATLAS/issues/31), [PR #32](https://github.com/Neo1228/ATLAS/pull/32), commits `7665434`, `1cba9e1`, `f28b2f6`.
- 범위: RPS/TPS/QPS 단위·scope, offered/achieved load, ramp-up/burst/steady state, open/closed model, latency/error/queue/saturation metrics, RED/USE, Little’s Law; Q-008 수치와 성능 목표는 TBD.
- 근거: [Reference Register REF-018~022](../00-governance/reference-register.md). 측정값·tool·infra 선택 없음.
- 확인: Markdown lint 24 files, 141 local links resolve, YAML 4 files parse, Cyrillic/Han 검색 결과 없음, `git diff --check` 통과. PR required checks `Markdown lint`, `PR checklist` 통과.

## 2026-10-30 — 초기 판매 시나리오 경계
- 추적: [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [PR #34](https://github.com/Neo1228/ATLAS/pull/34); 문서 결정 commit `abcfa69`.
- 범위: campaign 1개·수량형 SKU 1종·독립 pool; sale open 전 queue 미운영, open 후 FIFO.
- 근거: [REF-023~026](../00-governance/reference-register.md). 구현·architecture·성능 목표는 선택하지 않음.
- 잔여: Q-002/Q-003/Q-010은 공유 재고, 다른 판매 유형, queue edge policy 등 미해결 경계 때문에 OPEN.
- 확인: Markdown lint 24 files, 144 local links, YAML 4 forms, Mermaid render, `git diff --check` 통과. PR required checks는 PR #34에서 추적.
