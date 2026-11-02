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

## 2026-10-30 — Issue·PR·문서 추적성 정비
- 추적: [Issue #38](https://github.com/Neo1228/ATLAS/issues/38), [PR #39](https://github.com/Neo1228/ATLAS/pull/39) 병합. 커밋 [`226dad4`](https://github.com/Neo1228/ATLAS/commit/226dad4), [`58d44e6`](https://github.com/Neo1228/ATLAS/commit/58d44e6); merge [`2897ff1`](https://github.com/Neo1228/ATLAS/commit/2897ff1).
- 범위: 23개 Markdown 문서와 8개 Issue/9개 PR 본문의 기준 링크 정비. Issue/PR 양식에 직접 연결을 요구.
- 확인: local link 294개, GitHub 문서 링크 125개 resolve. Markdown lint, YAML 4개, PR #39 필수 checks 통과.
- 제외: seed PR [#1](https://github.com/Neo1228/ATLAS/pull/1)/[#3](https://github.com/Neo1228/ATLAS/pull/3)는 [PR #4](https://github.com/Neo1228/ATLAS/pull/4)에 superseded.

## 2026-10-30 — PayPal authorize/capture 결정
- 결정: [Issue #37](https://github.com/Neo1228/ATLAS/issues/37), [PR #41](https://github.com/Neo1228/ATLAS/pull/41) 병합 [`375645e`](https://github.com/Neo1228/ATLAS/commit/375645e). AUTHORIZE 후 CAPTURE; capture success 확인 후 inventory claim ([D-026](../00-governance/decision-log.md)).
- 영향: Authorization success는 최종 결제나 inventory claim이 아님. [FR-002](../02-requirements/requirements-index.md), [BR-003/INV-003](../03-domain/business-rules.md).
- 근거: [REF-030~033](../00-governance/reference-register.md). Provider별 PayPal contract만 적용; Shopify/Coinbase 사례를 PayPal 정책으로 일반화하지 않음.
- OPEN: endpoint/version, operation별 idempotency 지원·retention·retry, inventory/auth expiry, capture/void/cancel recovery와 compensation.
- 검증: PR #41의 Markdown lint와 PR checklist 통과.

## 2026-10-30 — 결제 hold 만료·복구
- 결정: [Issue #42](https://github.com/Neo1228/ATLAS/issues/42), [PR #43](https://github.com/Neo1228/ATLAS/pull/43) 병합 [`0e8fe43`](https://github.com/Neo1228/ATLAS/commit/0e8fe43). TTL/cancel 시 capture 확인; active authorization은 void. Capture success면 claim, release는 확정된 non-payment와 void/expiry 이후 ([D-027](../00-governance/decision-log.md)).
- 근거: [REF-034~038](../00-governance/reference-register.md). PayPal authorization 기간 문구가 29/30일로 달라 provider TTL은 미선택. ATLAS inventory TTL과 별도.
- OPEN: TTL/deadline, retry/key retention, escalation/compensation. 구현·production payment 미착수.
- 기록: [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md), [BR-003/INV-003](../03-domain/business-rules.md), [EXP-003/004/005](../08-experiments/experiment-registry.md).

## 2026-10-30 — 초기 checkout window
- 선택: [Issue #44](https://github.com/Neo1228/ATLAS/issues/44), [PR #45](https://github.com/Neo1228/ATLAS/pull/45) 병합 [`48eb215`](https://github.com/Neo1228/ATLAS/commit/48eb215). 초기 synthetic sale에 fixed window 1개; demand-based TTL 제외 ([D-028](../00-governance/decision-log.md)).
- OPEN: duration, TTL 시작 시점, checkout/expiry UX, p50/p95/p99와 acceptance. PSP/API/recovery deadline은 별도.
- 근거: [REF-039~041](../00-governance/reference-register.md). Ticketmaster·Broadleaf·PayPal 수치는 각 제품 사례이며 ATLAS 기준값이 아님.

## 2026-10-30 — Buyer checkout timer
- 선택: [Issue #46](https://github.com/Neo1228/ATLAS/issues/46), [PR #47](https://github.com/Neo1228/ATLAS/pull/47) 병합 [`28939a6`](https://github.com/Neo1228/ATLAS/commit/28939a6). Confirmed hold에서 timer 시작; 만료 전 initial AUTHORIZE 제출; grace extension 없음 ([D-029](../00-governance/decision-log.md)).
- 만료 후 UX: 미제출이면 checkout 종료. 제출된 요청은 reconciliation까지 pending 표시; 해결 전 새 attempt 금지.
- OPEN: numeric TTL와 start-point 측정. Eventbrite/FareHarbor/Ticketmaster 사례는 ATLAS 기준값이 아님.

## 2026-10-30 — Initial synthetic TTL
- 선택: [Issue #48](https://github.com/Neo1228/ATLAS/issues/48), [PR #49](https://github.com/Neo1228/ATLAS/pull/49) 병합 [`c0eef73`](https://github.com/Neo1228/ATLAS/commit/c0eef73). Confirmed hold부터 initial AUTHORIZE 제출까지 10분. Test-only, no extension ([D-030](../00-governance/decision-log.md)).
- 재평가 지표: hold-to-submission p50/p95/p99, expiry/abandonment, hold occupancy, oversell invariant. Production TTL/SLO 아님.
- 근거: [REF-039~041](../00-governance/reference-register.md)은 제품별 사례. 10분은 industry standard가 아니라 초기 test input.
- Issue #48 closeout은 누락된 PR link를 보완한 [PR #50](https://github.com/Neo1228/ATLAS/pull/50) 병합 [`2e34fed`](https://github.com/Neo1228/ATLAS/commit/2e34fed)에서 완료. 별도 closeout이 필요했던 trace 누락은 이번 audit [Issue #58](https://github.com/Neo1228/ATLAS/issues/58)에서 방지 기준 보완.

## 2026-10-30 — Gate 2 부하 프로파일

| 결정 | 내용 | 추적 |
|---|---|---|
| [D-031](../00-governance/decision-log.md) | Full Buyer journey, hybrid open-loop arrival, 순차 per-session step, baseline/burst/step-up | [Issue #51](https://github.com/Neo1228/ATLAS/issues/51), [PR #53](https://github.com/Neo1228/ATLAS/pull/53) → [`5266d14`](https://github.com/Neo1228/ATLAS/commit/5266d14) |
| [D-032](../00-governance/decision-log.md) | Synthetic scale ladder, offered Buyer sessions/s primary, report-only; pass/fail SLO 없음 | [Issue #54](https://github.com/Neo1228/ATLAS/issues/54), [PR #55](https://github.com/Neo1228/ATLAS/pull/55) → [`a82b454`](https://github.com/Neo1228/ATLAS/commit/a82b454) |
| [D-033](../00-governance/decision-log.md) | 10,000 sessions/60m (~2.8/s), 2×/4× geometric stress (~5.6/11.1/s). Synthetic input, not production demand. | [Issue #56](https://github.com/Neo1228/ATLAS/issues/56), [PR #57](https://github.com/Neo1228/ATLAS/pull/57) → [`6cf28dd`](https://github.com/Neo1228/ATLAS/commit/6cf28dd) |

### 근거·측정·검증
- [REF-044](../00-governance/reference-register.md): 1.5M invitations/52 dates × historical 40% conversion ≈11.5K buyers/date는 균등 분배 가정일 때만 성립. 실제 날짜별 unique buyer 수는 미공개. 3.5B system requests는 user/session 수가 아님.
- 측정 항목: offered/admitted/dropped sessions, stage RPS, resolved-session TPS, latency, errors, queue/hold occupancy, expiry/abandonment, oversell invariant. 실행 결과와 production SLO는 없음.
- PR #53, #55, #57에서 `Markdown lint`와 `PR checklist` 통과.
- PR #57의 close keyword가 Markdown link 안에 있어 Issue #56은 병합 후 수동 종료. 자동 검사 보완은 [Issue #58](https://github.com/Neo1228/ATLAS/issues/58).

## 2026-10-30 — Queue ticket 수명주기
- 선택: [Issue #60](https://github.com/Neo1228/ATLAS/issues/60), [PR #61](https://github.com/Neo1228/ATLAS/pull/61), A/A/A. Active ticket의 same-key/same-payload retry는 기존 ticket 재사용. Payload 변경은 거절하고 기존 ticket 유지. 만료 후 재입장은 새 key와 새 sequence로 tail 배치. Campaign 안에서 sequence unique.
- 기록: [D-034](../00-governance/decision-log.md), [Q-003](../00-governance/assumptions-and-open-questions.md), [BR-001](../03-domain/business-rules.md), [INV-002](../03-domain/invariants.md), [EXP-011](../08-experiments/experiment-registry.md).
- Commit: [523e564](https://github.com/Neo1228/ATLAS/commit/523e564). PR #61에 required checks와 최종 merge SHA 기록.
- 미정: key scope/retention, payload 비교 필드, expiry trigger/duration, storage/atomicity, bot 방어, 다른 판매 유형. Runtime/API/DB 구현 미착수.

- 실행 결과 없음. [EXP-011](../08-experiments/experiment-registry.md)는 ○ Planned.

## 2026-10-30 — 초기 재고 예약·수량 보존
- 선택: [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63). Reservation당 1개, `available + reserved + claimed = initial capacity`, 각 수량 0 이상.
- 상태 전이: hold 확정 시 reserved, capture success 시 claimed. Confirmed non-payment 후 release; authorization이 있으면 void/expiry 확인 필요.
- 추적: [D-035](../00-governance/decision-log.md), [Q-002/Q-004](../00-governance/assumptions-and-open-questions.md), [FR-001](../02-requirements/requirements-index.md), [BR-002](../03-domain/business-rules.md), [INV-001](../03-domain/invariants.md), [EXP-001](../08-experiments/experiment-registry.md).
- Commit: [7863415](https://github.com/Neo1228/ATLAS/commit/7863415). Required checks와 merge metadata는 [PR #63](https://github.com/Neo1228/ATLAS/pull/63)에서 확인.
- 제외: multi-unit reservation, 판매 중 재고 조정·재입고, post-claim 취소·환불 정책. D-027 checkout expiry/authorization void 복구는 유지. Storage/atomicity 미정.
- 실험 미실행. [EXP-001](../08-experiments/experiment-registry.md)는 ○ Planned.

## 2026-10-30 — 판매 유형 비교·Gate 2 범위
- 비교·선택: [Issue #64](https://github.com/Neo1228/ATLAS/issues/64), [PR #65](https://github.com/Neo1228/ATLAS/pull/65). Assigned-seat는 seat identity/map·개별 hold, time-slot은 시간대별 capacity·도착/미방문 경계를 추가. 제품/vendor 사례는 backend best practice 근거 아님.
- 선택 A: 기존 quantity-SKU scenario 유지, Gate 2 full Buyer journey 진행. Assigned-seat/time-slot은 현재 범위에 넣지 않음; 장기 판매 유형은 [Q-010](../00-governance/assumptions-and-open-questions.md) OPEN.
- 결정·근거: [D-036](../00-governance/decision-log.md), [REF-045~049](../00-governance/reference-register.md), [Personas & Jobs](../01-discovery/personas-and-jobs.md).
- Commit: [7dc9ca6](https://github.com/Neo1228/ATLAS/commit/7dc9ca6). Required checks와 merge metadata는 [PR #65](https://github.com/Neo1228/ATLAS/pull/65)에서 확인.
- 제약: production demand/SLO, storage/API/queue tech, architecture 및 runtime 구현 미선택. Gate 3 이후 별도 승인.

## 2026-11-02 — Gate 2 Buyer journey acceptance candidates
- 제안: [Issue #66](https://github.com/Neo1228/ATLAS/issues/66). Full Buyer journey의 queue·inventory·payment recovery·report-only workload 기준을 기존 D-019~D-036에서 합성.
- 범위: FR-001/FR-002/NFR-002 Proposed 유지. Security/settlement, provider-specific deadlines, production SLO, burst/environment, runtime/technology는 OPEN.
- 추적: [BR-001/002/003](../03-domain/business-rules.md), [INV-001/002/003](../03-domain/invariants.md), [EXP-001/003/004/005/010/011](../08-experiments/experiment-registry.md). No experiment or runtime result.
