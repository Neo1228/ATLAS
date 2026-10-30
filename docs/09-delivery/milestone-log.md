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
| WP-001 저장소 기반 | — | seed | [`7ed5fac`](https://github.com/Neo1228/ATLAS/commit/7ed5fac); workflow [`eb371ca`](https://github.com/Neo1228/ATLAS/commit/eb371ca), [`5151a15`](https://github.com/Neo1228/ATLAS/commit/5151a15) |
| WP-002 governance | [Issue #5](https://github.com/Neo1228/ATLAS/issues/5) | [PR #9](https://github.com/Neo1228/ATLAS/pull/9) | [`6049755`](https://github.com/Neo1228/ATLAS/commit/6049755) |
| WP-003 discovery/requirements | [Issue #6](https://github.com/Neo1228/ATLAS/issues/6) | [PR #10](https://github.com/Neo1228/ATLAS/pull/10) | [`f73a7e9`](https://github.com/Neo1228/ATLAS/commit/f73a7e9) |
| WP-004 ADR/experiments | [Issue #8](https://github.com/Neo1228/ATLAS/issues/8) | [PR #11](https://github.com/Neo1228/ATLAS/pull/11) | [`6b2b379`](https://github.com/Neo1228/ATLAS/commit/6b2b379) |
| WP-005 delivery records | [Issue #7](https://github.com/Neo1228/ATLAS/issues/7) | [PR #12](https://github.com/Neo1228/ATLAS/pull/12) | [`8c71869`](https://github.com/Neo1228/ATLAS/commit/8c71869) |
| Git conventions | [Issue #2](https://github.com/Neo1228/ATLAS/issues/2) | [PR #4](https://github.com/Neo1228/ATLAS/pull/4) | [`a76016e`](https://github.com/Neo1228/ATLAS/commit/a76016e) |
| PR batching | [Issue #15](https://github.com/Neo1228/ATLAS/issues/15) | [PR #16](https://github.com/Neo1228/ATLAS/pull/16) | [`80dd964`](https://github.com/Neo1228/ATLAS/commit/80dd964) |
| Delivery sync | [Issue #17](https://github.com/Neo1228/ATLAS/issues/17) | [PR #18](https://github.com/Neo1228/ATLAS/pull/18) | [`4b5ca7b`](https://github.com/Neo1228/ATLAS/commit/4b5ca7b) |

- [PR #1](https://github.com/Neo1228/ATLAS/pull/1)/[PR #3](https://github.com/Neo1228/ATLAS/pull/3): 승인된 이력/branch 수정 중 미병합 종료. 최종 내용은 [PR #4](https://github.com/Neo1228/ATLAS/pull/4)에 포함.

## 2026-10-29 — Gate 1 discovery
- [Issue #19](https://github.com/Neo1228/ATLAS/issues/19)/[Issue #20](https://github.com/Neo1228/ATLAS/issues/20) → [PR #21](https://github.com/Neo1228/ATLAS/pull/21), commit [`f583ce2`](https://github.com/Neo1228/ATLAS/commit/f583ce2): 가상 Buyer/Seller 목표, capacity 계획. 실사용자 검증/성능 측정 없음.
- [Issue #22](https://github.com/Neo1228/ATLAS/issues/22) → [PR #23](https://github.com/Neo1228/ATLAS/pull/23), commit [`e8a85ae`](https://github.com/Neo1228/ATLAS/commit/e8a85ae): queue fairness baseline [D-019](../00-governance/decision-log.md).
- [Issue #24](https://github.com/Neo1228/ATLAS/issues/24) → [PR #25](https://github.com/Neo1228/ATLAS/pull/25), commit [`154c31e`](https://github.com/Neo1228/ATLAS/commit/154c31e): admission/retry/restart baseline [D-020~D-022](../00-governance/decision-log.md). [Q-003](../00-governance/assumptions-and-open-questions.md) detail OPEN.

## 2026-10-29 — 문서 가독성·추적 기준
- [Issue #26](https://github.com/Neo1228/ATLAS/issues/26), [PR #27](https://github.com/Neo1228/ATLAS/pull/27) merged [`a82e022`](https://github.com/Neo1228/ATLAS/commit/a82e022); closeout [PR #28](https://github.com/Neo1228/ATLAS/pull/28) merged [`bc8d60b`](https://github.com/Neo1228/ATLAS/commit/bc8d60b).
- 범위: 문서 링크/인덱스, 독자용 용어·상태, 복잡한 Issue/PR 설명, Mermaid 기준, 필수 PR checklist.
- Verification: Markdown lint 24 files; 23 Markdown files/211 local links; YAML 4 files; checklist pass/fail smoke cases. Required PR checks passed in [PR #27](https://github.com/Neo1228/ATLAS/pull/27).
- `main` required checks: `Markdown lint`, `PR checklist`. Strict checks/linear history 켬; force push/deletion 차단.

## 2026-10-29 — 전체 문서 가독성 정비
- 범위: 24개 Markdown 문서와 GitHub forms/templates 전반의 짧은 문장, 개조식 종결, status 기호, 수치·단계 표기.
- 기준: 사실/정책/미정 경계 보존. 명사형을 강제하지 않고 자연스러운 의미 우선.
- 추적: [Issue #29](https://github.com/Neo1228/ATLAS/issues/29), [PR #30](https://github.com/Neo1228/ATLAS/pull/30), commit [`3ff5d49`](https://github.com/Neo1228/ATLAS/commit/3ff5d49).
- 로컬 확인: Markdown lint 통과, 138개 상대 링크 resolve, YAML 4개 parse, Cyrillic 검색 결과 없음, `git diff --check` 통과.

## 2026-10-29 — 성능·부하 용어와 측정 범위
- 추적: [Issue #31](https://github.com/Neo1228/ATLAS/issues/31), [PR #32](https://github.com/Neo1228/ATLAS/pull/32), commits [`7665434`](https://github.com/Neo1228/ATLAS/commit/7665434), [`1cba9e1`](https://github.com/Neo1228/ATLAS/commit/1cba9e1), [`f28b2f6`](https://github.com/Neo1228/ATLAS/commit/f28b2f6).
- 범위: RPS/TPS/QPS 단위·scope, offered/achieved load, ramp-up/burst/steady state, open/closed model, latency/error/queue/saturation metrics, RED/USE, Little’s Law; [Q-008](../00-governance/assumptions-and-open-questions.md) 수치와 성능 목표는 TBD.
- 근거: [Reference Register REF-018~022](../00-governance/reference-register.md). 측정값·tool·infra 선택 없음.
- 확인: Markdown lint 24 files, 141 local links resolve, YAML 4 files parse, Cyrillic/Han 검색 결과 없음, `git diff --check` 통과. PR required checks [PR #32](https://github.com/Neo1228/ATLAS/pull/32) `Markdown lint`, `PR checklist` 통과.

## 2026-10-30 — 초기 판매 시나리오 경계
- 추적: [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [PR #34](https://github.com/Neo1228/ATLAS/pull/34); commits [`abcfa69`](https://github.com/Neo1228/ATLAS/commit/abcfa69), [`c8968d4`](https://github.com/Neo1228/ATLAS/commit/c8968d4); squash merge [`cdf1f0f`](https://github.com/Neo1228/ATLAS/commit/cdf1f0f).
- 범위: campaign 1개·수량형 SKU 1종·독립 pool; sale open 전 queue 미운영, open 후 FIFO.
- 근거: [REF-023~026](../00-governance/reference-register.md). 구현·architecture·성능 목표는 선택하지 않음.
- 잔여: [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md)은 공유 재고, 다른 판매 유형, queue edge policy 등 미해결 경계 때문에 OPEN.
- 확인: Markdown lint 24 files, 144 local links, YAML 4 forms, Mermaid render, `git diff --check` 통과. PR required checks [PR #34](https://github.com/Neo1228/ATLAS/pull/34) 통과.

## 2026-10-30 — 결제 결과와 재고 hold lifecycle
- 추적: [Issue #35](https://github.com/Neo1228/ATLAS/issues/35), [PR #36](https://github.com/Neo1228/ATLAS/pull/36); commits [`4138367`](https://github.com/Neo1228/ATLAS/commit/4138367), [`9491795`](https://github.com/Neo1228/ATLAS/commit/9491795); squash merge [`bda0c73`](https://github.com/Neo1228/ATLAS/commit/bda0c73).
- 범위: PSP 요청 전 hold, terminal success 후 claim/definitive failure 후 release, UNKNOWN same-attempt reconcile, expiry 중 재고 보류.
- 근거: [REF-023/027~029](../00-governance/reference-register.md). Stripe/Brandur 사례를 PayPal contract로 일반화하지 않음.
- 잔여: [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md) TTL, auth/capture mapping, provider support/retention, retry limit, irrecoverable UNKNOWN/refund policy OPEN.
- 로컬 확인: Markdown lint 24 files, 145 local links, YAML 4 forms, 두 Mermaid diagram render, `git diff --check` 통과. PR required checks [PR #36](https://github.com/Neo1228/ATLAS/pull/36) 통과.

## 2026-10-30 — Issue/PR/document traceability cleanup
- 추적: [Issue #38](https://github.com/Neo1228/ATLAS/issues/38), [PR #39](https://github.com/Neo1228/ATLAS/pull/39) merged; commits [`226dad4`](https://github.com/Neo1228/ATLAS/commit/226dad4), [`58d44e6`](https://github.com/Neo1228/ATLAS/commit/58d44e6); squash merge [`2897ff1`](https://github.com/Neo1228/ATLAS/commit/2897ff1).
- 범위: canonical links in 23 Markdown docs and 8 Issue/9 PR bodies; Issue/PR forms require clickable source links.
- 확인: 294 local Markdown links and 125 GitHub document links resolve; Markdown lint, YAML 4 forms, no Cyrillic/Han. Required PR checks [PR #39](https://github.com/Neo1228/ATLAS/pull/39) passed.
- 의도적 N/A: seed-only [PR #1](https://github.com/Neo1228/ATLAS/pull/1)/[PR #3](https://github.com/Neo1228/ATLAS/pull/3)는 독립 implementation docs가 아니라 [PR #4](https://github.com/Neo1228/ATLAS/pull/4)에 superseded.

## 2026-10-30 — PayPal authorize/capture 흐름 결정
- 결정: [Issue #37](https://github.com/Neo1228/ATLAS/issues/37)에서 `AUTHORIZE → CAPTURE` 선택. [D-026](../00-governance/decision-log.md)은 capture success 확인 후에만 inventory claim하도록 기록. [FR-002](../02-requirements/requirements-index.md), [BR-003/INV-003](../03-domain/business-rules.md), [Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md).
- 근거: [REF-030~033](../00-governance/reference-register.md). PayPal contract는 해당 provider에만 적용; Shopify/Coinbase 사례는 on-chain practitioner reference이며 PayPal 근거 아님. Authorization은 최종 청구나 inventory claim이 아님.
- 미정: endpoint/version, operation별 idempotency 지원/retention과 retry, inventory/auth expiry, capture/void/cancel 복구, compensation. 구현·production behavior 승인 아님.
- Verification: Markdown lint, local links and PR #41 required checks passed.

## 2026-10-30 — 결제 hold 만료·복구 기준
- 선택: [Issue #42](https://github.com/Neo1228/ATLAS/issues/42)/[PR #43](https://github.com/Neo1228/ATLAS/pull/43). B: TTL/cancel 시 capture 확인; active uncaptured authorization은 void. Capture success면 claim; release는 confirmed non-payment와 void/expiry 이후.
- 기록: [D-027](../00-governance/decision-log.md), [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md), [BR-003/INV-003](../03-domain/business-rules.md), [EXP-003/004/005](../08-experiments/experiment-registry.md).
- 근거: [REF-034~038](../00-governance/reference-register.md). PayPal authorization 기간 문구가 29/30일로 달라 provider TTL 미선택; ATLAS inventory TTL과 구분.
- 미정: TTL/deadline 수치, retry/key retention, escalation·compensation. Implementation/production payment 미착수.

## 2026-10-30 — 초기 checkout TTL 방식
- 선택: [Issue #44](https://github.com/Neo1228/ATLAS/issues/44)/[PR #45](https://github.com/Neo1228/ATLAS/pull/45) A — fixed window. 초기 synthetic sale에는 하나의 checkout window 적용; demand-based TTL은 제외.
- 미정: duration, TTL start trigger, checkout journey/expiry UX, p50/p95/p99 측정값 및 acceptance. PSP delivery/API/recovery deadline은 별도.
- 근거: [REF-039~041](../00-governance/reference-register.md). Ticketmaster는 event demand에 따라 timer 변경; Broadleaf 5분은 configurable product example; PayPal 3일은 webhook redelivery period. 어느 것도 ATLAS TTL 값 아님.
- 추적: [D-028](../00-governance/decision-log.md), [Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-005](../08-experiments/experiment-registry.md).

## 2026-10-30 — Buyer checkout timer contract
- 선택: [Issue #46](https://github.com/Neo1228/ATLAS/issues/46)/[PR #47](https://github.com/Neo1228/ATLAS/pull/47); timer starts at confirmed inventory hold, initial `AUTHORIZE` submission required before expiry, no grace extension.
- Expiry UX: close checkout; if payment request was submitted, show pending status until D-027 reconciliation resolves. No new payment attempt before resolution.
- Numeric TTL and start point measurement remain OPEN. Eventbrite/FareHarbor/Ticketmaster examples are product-specific, not ATLAS values ([REF-039~041](../00-governance/reference-register.md)).
- Records: [D-029](../00-governance/decision-log.md), [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md), [BR-003/INV-003](../03-domain/business-rules.md), [EXP-005](../08-experiments/experiment-registry.md).

## 2026-10-30 — Initial synthetic TTL parameter
- Selection: [Issue #48](https://github.com/Neo1228/ATLAS/issues/48), 10-minute fixed window from confirmed hold; initial AUTHORIZE submission required before expiry; no extension.
- Scope: synthetic-test parameter only, not production TTL/SLO. Re-evaluate after collecting hold-to-submission p50/p95/p99, expiry/abandonment, hold occupancy and oversell invariant.
- Evidence/trade-off: [REF-039~041](../00-governance/reference-register.md) are product-specific examples. 10 minutes is an initial test value, not an industry standard.
- Records: [D-030](../00-governance/decision-log.md), [Q-004/Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-005](../08-experiments/experiment-registry.md).
