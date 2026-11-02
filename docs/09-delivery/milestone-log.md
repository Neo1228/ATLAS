---
id: DOC-012
title: Milestone Log
status: Current
owner: Project Owner
updated: 2026-11-02
links:
  requirements: []
  adr: [ADR-0001, ADR-0003, ADR-0004]
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

## 2026-10-30 — 판매 유형 비교와 Gate 2 범위 선택
- 비교·선택: [Issue #64](https://github.com/Neo1228/ATLAS/issues/64), [PR #65](https://github.com/Neo1228/ATLAS/pull/65). Assigned-seat는 좌석 식별·seat map·개별 hold, time-slot은 시간대별 capacity·도착/미방문 경계를 추가. 제품/vendor 사례는 backend best practice의 증거가 아님.
- 선택 A: 기존 quantity-SKU 시나리오로 Gate 2 full Buyer journey 진행. Assigned-seat/time-slot은 현재 범위 밖; 장기 판매 유형은 [Q-010](../00-governance/assumptions-and-open-questions.md) 미정.
- 근거: [D-036](../00-governance/decision-log.md), [REF-045~049](../00-governance/reference-register.md), [Personas & Jobs](../01-discovery/personas-and-jobs.md).
- Commit: [7dc9ca6](https://github.com/Neo1228/ATLAS/commit/7dc9ca6). Required checks와 merge 기록은 [PR #65](https://github.com/Neo1228/ATLAS/pull/65) 참조.

## 2026-11-02 — Gate 2 Buyer 여정 기준 승인
- 승인 기록: [Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [PR #67](https://github.com/Neo1228/ATLAS/pull/67). 현재 single-SKU Buyer journey와 report-only workload 기준 승인. Production readiness 승인은 아님.
- 범위: FR-001/FR-002/NFR-002 Gate 2 기준 승인. FR-003 정산과 NFR-001 보안은 Proposed. Provider deadline, production SLO, burst/environment는 미정.
- 추적: [BR-001/002/003](../03-domain/business-rules.md), [INV-001/002/003](../03-domain/invariants.md), [EXP-001/003/004/005/010/011](../08-experiments/experiment-registry.md). 실험·runtime 결과 없음.
- Commit: [2884e94](https://github.com/Neo1228/ATLAS/commit/2884e94), [773ea6f](https://github.com/Neo1228/ATLAS/commit/773ea6f). Merge: [`b85a0ec1ec6f0d0c0a72089176b513a1bce47d3f`](https://github.com/Neo1228/ATLAS/commit/b85a0ec1ec6f0d0c0a72089176b513a1bce47d3f).

## 2026-11-02 — Gate 3 아키텍처 방향 승인
- 선택: [Issue #68](https://github.com/Neo1228/ATLAS/issues/68). Gate 2 current Buyer journey 기준 승인. Modular Monolith First, Java + Spring Boot + PostgreSQL.
- 방향: PostgreSQL을 초기 durable system of record로 사용. 별도 queue/cache/broker는 bottleneck과 비교 가능한 load test 이점을 확인한 경우에만 검토.
- PR #69 병합: [기록](https://github.com/Neo1228/ATLAS/pull/69), commits [6694fe2](https://github.com/Neo1228/ATLAS/commit/6694fe2)/[cf93b73](https://github.com/Neo1228/ATLAS/commit/cf93b73), merge [`638514d8fa166ef57c6591ca2efbd43741eea45f`](https://github.com/Neo1228/ATLAS/commit/638514d8fa166ef57c6591ca2efbd43741eea45f). Required Markdown lint/PR checklist 통과.
- 근거·한계: [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [REF-023/050~052](../00-governance/reference-register.md). 사례는 context-specific이며 ATLAS capacity를 입증하지 않음.
- 미정: exact versions, physical schema/isolation/lock recovery, infrastructure, PayPal product/API, production capacity/SLO. Runtime implementation 미승인.

## 2026-11-02 — Gate 3 초기 module/data 설계 결정
- D-038: 사용자가 module scope 선택을 위임. 비교 검토 후 현재 Buyer 흐름의 Campaign/Admission/Inventory/Order-Checkout/Payment만 우선 설계. Tenant security·확장 catalog·Settlement/Reconciliation module 경계는 장기 capability map에 유지.
- D-039: campaign counter row와 ticket을 한 transaction으로 commit해 D-019/D-020 FIFO 기준 유지. Same-campaign contention은 합성 workload로 측정; inventory lock 전략은 EXP-002 비교 전까지 미선택.
- 근거·선택: [Issue #68 설계 결정 댓글](https://github.com/Neo1228/ATLAS/issues/68#issuecomment-6566630187), [REF-050/052~058](../00-governance/reference-register.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md). 상세 근거와 한계는 Issue 본문에 기록.
- 미정: physical schema, isolation/lock timeout/deadlock recovery, key/expiry/bot policy, production SLO/environment. Runtime implementation/deployment 별도 승인 대상.
- PR #70 병합: commits [2456d08](https://github.com/Neo1228/ATLAS/commit/2456d08)/[893b138](https://github.com/Neo1228/ATLAS/commit/893b138), merge [`9bd90ffdc25321573d2e43bb88225f4bdf2578a8`](https://github.com/Neo1228/ATLAS/commit/9bd90ffdc25321573d2e43bb88225f4bdf2578a8). Required Markdown lint/PR checklist 통과.
- Capacity envelope: accepted 10k/60m, 2×/4× synthetic profile. Stage RPS·resolved-session TPS 측정 대상; request mix·latency·environment 없이는 concurrency/resource sizing 산출 불가. Production target/SLO 없음 ([D-031~D-033](../00-governance/decision-log.md), [Q-008](../00-governance/assumptions-and-open-questions.md), [REF-018/019/022](../00-governance/reference-register.md)).

## 2026-11-02 — Local Buyer runtime implemented
- Scope: [Issue #72](https://github.com/Neo1228/ATLAS/issues/72). Campaign → admission → one-unit inventory hold → order/payment orchestration with Fake PSP. No real payment, identity/Tenant policy, deployment or performance claim.
- Decisions: [D-040~D-042](../00-governance/decision-log.md); toolchain, queue gate, checkout expiry, same-buyer unresolved-payment guard, and local restart limits.
- Commit units in PR #73: [271222f](https://github.com/Neo1228/ATLAS/commit/271222f) build/CI; [5a5dbe9](https://github.com/Neo1228/ATLAS/commit/5a5dbe9) runtime; [de38b5e](https://github.com/Neo1228/ATLAS/commit/de38b5e) tests; [10637b4](https://github.com/Neo1228/ATLAS/commit/10637b4) design/docs; [f791990](https://github.com/Neo1228/ATLAS/commit/f791990), [81c2038](https://github.com/Neo1228/ATLAS/commit/81c2038), [55e37a7](https://github.com/Neo1228/ATLAS/commit/55e37a7) delivery trace.
- PostgreSQL 18.6/Testcontainers 2.0.5 check: 13 tests passed, 0 failed, 0 skipped; includes inventory concurrency, Buyer recovery and module-boundary verification.
- HTTP smoke with local Compose PostgreSQL: campaign creation/open, queue sequence 1, checkout reservation, Fake PSP capture, order `PAID`, inventory `CLAIMED`. After app restart with the same PostgreSQL container, the next ticket received sequence 2. Fake provider outcome across app restart and any load/performance behavior remain unverified.
- PR #73 merged via squash (repository's only enabled method): [PR #73](https://github.com/Neo1228/ATLAS/pull/73); merge [`223a09320c3206b066bc224bde63c09e4ac6b0c5`](https://github.com/Neo1228/ATLAS/commit/223a09320c3206b066bc224bde63c09e4ac6b0c5). Required checks passed: Backend CI, Markdown lint, PR checklist. [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) closed.

## 2026-11-02 — Code architecture and efficiency retrospective · [Issue #75](https://github.com/Neo1228/ATLAS/issues/75)
- **구조 당시 기록:** Issue #75/PR #76 시점에는 capability package root에 API를 두고 구현은 `.internal`, HTTP adapter는 `.web`에 배치했다. 현재 패키지 구조는 [ADR-0003](../06-decisions/adr/ADR-0003-gradle-module-boundaries.md)에서 Gradle 도메인 모듈·계층 package로 재편했으며 과거 `.internal` 구조를 복원하지 않음.
- **Boundary gap:** Modulith does not detect SQL that writes another module's tables. Earlier review found Campaign code initializing Admission's counter; PR #73 moved initialization into Admission's transaction. Keep table write ownership explicit in review.
- **Responsibility finding 당시 기록:** Issue #75는 `OrderService`의 persistence를 `JdbcOrderRepository`/`OrderRecord`로 분리했고 scheduler-only operation을 공개 API에서 제거했다. 당시 구현은 `.internal` package를 썼으나 ADR-0003에서 `.service`/`.repository`로 옮김. 업무 흐름·저장소 책임 분리는 유지.
- **FQCN finding:** several implementation classes used fully qualified types despite the project rule. D-043 and Checkstyle now encode the rule; package/import declarations remain valid.
- **Measured inefficiency:** none established. No load profile was run for this change; do not label the current code slow based on source shape alone.
- **Expiry sweep risk — Order:** repository query returns every currently due order ID into a list, then `OrderService` starts and commits one transaction per ID and rechecks state under row lock. Work and transaction count grow with due-order count; multiple app instances may scan the same candidates. The partial expiry index already supports this predicate. Why not batch now: batch claiming/`SKIP LOCKED` alters concurrency and recovery semantics; no approved load result identifies this as a bottleneck. Compare bounded batches against current logic using due backlog, sweep duration, query/transaction count, lock waits, connection use, and expiry lag.
- **Expiry sweep risk — Admission:** scheduler selects all distinct campaigns with expired tickets, then holds one transaction while locking each counter and expiring/promoting per campaign. SQL/lock work grows with due campaign count; concurrent instances can repeat candidate scans. Why not rewrite now: per-campaign FIFO promotion and counter locking are correctness constraints, and no measured backlog/latency exists. Measure due campaigns/tickets, sweep duration, transaction age, lock waits, query count, and expiry lag before considering bounded campaign batches or row-claiming.
- **Order view fan-out:** assembling one view reads Order, Inventory, Payment and Admission state across module APIs. This is a fixed small number of reads per request, not an N+1 loop. Keep module ownership; only consider a dedicated read model if profiling shows this fan-out dominates latency.
- **Decision rule:** Fowler's N+1 example distinguishes query count that scales with result size from fixed fan-out (REF-071). Shopify's inventory case found connection hold time—not the initially suspected reservation SQL—to be the throughput ceiling, after measuring production traffic (REF-070). These are diagnostics, not proof of ATLAS bottlenecks or direct MySQL/PostgreSQL equivalence. Use the approved synthetic workload and end-to-end DB/connection/lock metrics before optimizing; preserve state transitions and verify correctness after any batching change.
- **Naming and abstraction 당시 기록:** `JdbcOrderRepository`, `OrderRecord`, scheduler names가 당시 책임을 표현했다. `OrderRecord`는 PR #76 당시 package-private였으며, ADR-0003에서 service/repository package가 갈라져 module 내부 package 간 전달을 위해 public으로 변경. Spring Modulith는 capability/API boundary를 확인하는 test-only 보조 도구. Generic repository base나 speculative payment/webhook abstraction은 추가하지 않음.
- **Persistence/library review 당시 기록:** Issue #75 implementation used `JdbcTemplate`/row mapping for all order storage. ADR-0004 later moved ordinary CRUD to Spring Data JPA and retained explicit PostgreSQL lock/atomic SQL; this historical observation is not the current persistence choice (REF-072/073/097).
- **In-process lock risk 당시/현재 이름:** Issue #75 당시 payment adapter는 `JdbcPaymentApi`로 불렸고, 현재는 `PaymentService`. 현재 `OrderService`와 `PaymentService`의 256 monitor stripes는 같은 order 처리만 JVM 내부에서 직렬화; 충돌하는 ID는 함께 기다리며 여러 JVM 사이에는 공유되지 않는다. Fake PSP local safeguard이며 distributed payment coordination이 아님. Unbounded lock map/library로 대체하지 않음; live provider에는 idempotency와 durable multi-instance coordination이 필요.
- **Repository/dependency decision:** keep one repo/deployable; no application runtime library added. Checkstyle is build-time only and enforces the explicit style rule. Split/reuse decisions require independent ownership or measured technical need; candidate library must justify capability gap, alternatives, maintenance/security/license cost, and scope ([ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [REF-050/053~055/069~073](../00-governance/reference-register.md)).
- **Practitioner evidence and limits:** Shopify's Packwerk retrospective warns semantic package names/static checks may diverge from runtime dependencies or add indirection (REF-053). Thoughtworks supports modular monoliths while domain knowledge evolves (REF-054), not as measured capacity evidence. Spring Modulith checks structural dependencies, not SQL ownership (REF-055). This audit therefore inspected SQL writes, call paths, runtime tests, and module verification separately.
- **Follow-up boundary:** real PSP adapters/webhooks, refund, settlement, tenant isolation, multi-instance expiry coordination, and measured load remain separately approved work. Do not pre-create abstractions before a concrete use case and contract.
- **Verification:** `./gradlew check` passed 14 tests, 0 failures/errors/skips; includes FQCN Checkstyle, internal queue/order expiry integration cases and `ApplicationModules.verify()`. Markdown lint passed 24 files, 0 issues. Final HTTP smoke confirmed sequence 1 checkout → Fake PSP `CAPTURED` → `PAID`/`CLAIMED`. No load/performance results.
- **Merge record:** [PR #76](https://github.com/Neo1228/ATLAS/pull/76) squash-merged to `main` as [`d2f9e53766b983902d3bc4ad1e2cfea40a2888ce`](https://github.com/Neo1228/ATLAS/commit/d2f9e53766b983902d3bc4ad1e2cfea40a2888ce). Branch commits: [`a9c1a4f`](https://github.com/Neo1228/ATLAS/commit/a9c1a4ff0a88c5419d0f6269144d695fc469f555) runtime boundaries, [`bd36b20`](https://github.com/Neo1228/ATLAS/commit/bd36b2039bc72f8d7961286b1a910ff428ed9048) FQCN enforcement, [`4529f4e`](https://github.com/Neo1228/ATLAS/commit/4529f4eb87236a0889416b4a9f364a903a0edde3) audit/docs. Required Java 25/PostgreSQL, Markdown lint and PR checklist checks passed. [Issue #75](https://github.com/Neo1228/ATLAS/issues/75) closed.

## 2026-11-02 — Read semantics, API errors and technology review · [Issue #78](https://github.com/Neo1228/ATLAS/issues/78)
- `AdmissionApi.find()` became a persisted-state query; GET/OrderView no longer triggers expiry, promotion or DB writes. Expiry remains with explicit state-changing operations and schedulers; stale persisted status until the next sweep is intentional and recorded.
- Root `AtlasApiExceptionHandler` now emits RFC 9457 `ProblemDetail` with stable `code`; module-owned typed exceptions distinguish missing Campaign/Admission resources. Validation, not-found, conflict and unexpected 500 responses have contract tests. Internal 500 details remain server-side.
- D-044~D-046 and [ADR-0002](../06-decisions/adr/ADR-0002-runtime-technology-review.md) retain the approved stack, compare alternatives and limits, and define validation/clock/transaction/scheduler/logging/security/common-package ownership. No runtime library, repository split, or unmeasured performance claim.
- Verification: JDK 25 + PostgreSQL/Testcontainers `./gradlew check` passed 18 tests, 0 failures/skips; targeted Buyer-flow integration test passed; Markdown lint covered 25 files with 0 issues; relative-link check found 0 missing targets across 25 Markdown files.
- No load/failure experiment or production behavior was measured. Current load profile and production/provider/security decisions remain OPEN.
- Commits: [a1b15f8](https://github.com/Neo1228/ATLAS/commit/a1b15f8) runtime/API; [5e669ce](https://github.com/Neo1228/ATLAS/commit/5e669ce) architecture/technology docs; [7a2167a](https://github.com/Neo1228/ATLAS/commit/7a2167a) delivery tracking. [PR #79](https://github.com/Neo1228/ATLAS/pull/79) squash-merged to `main` as [`d954d4f4981298c5740f9aef31abb28b05e77e9f`](https://github.com/Neo1228/ATLAS/commit/d954d4f4981298c5740f9aef31abb28b05e77e9f). Java 25/PostgreSQL, Markdown lint and PR checklist passed; post-merge Backend CI [run 41395666245](https://github.com/Neo1228/ATLAS/actions/runs/41395666245) passed. [Issue #78](https://github.com/Neo1228/ATLAS/issues/78) closed.

## 2026-11-02 — 도메인별 Gradle 모듈 재구성
- [D-047](../00-governance/decision-log.md)/[ADR-0003](../06-decisions/adr/ADR-0003-gradle-module-boundaries.md): 기존 Campaign, Admission, Inventory, Order, Payment 소유 경계를 유지하고 `application-module` + 다섯 `*-module`로 소스·의존을 재배치. 단일 Spring Boot deployment·PostgreSQL schema·기존 public API 흐름은 유지.
- `internal` package를 제거하고 책임별 `.service`/`.repository`/`.provider`로 이동. HTTP controller·설정·migration은 `application-module`. 기존 `*Command` 단순 입력 record는 `*Request`로 이름 정리; CQRS dispatcher/handler/event bus를 만들지 않음.
- 각 Gradle module root README와 Repository README, 문서 인덱스·AGENTS·CONTRIBUTING·ADR·Decision Log·Roadmap·Session Handoff를 갱신.
- 검증: `./gradlew check -x :application-module:test` 통과. Campaign/Admission unit, Spring Modulith `ModuleBoundaryTest`, API exception contract test 통과. 전체 Testcontainers test는 Docker client unavailable로 Buyer flow, Inventory concurrency, Payment suite가 초기화 실패. `:application-module:bootJar` 성공; 다섯 domain JAR와 migration entries 확인. 재구성 뒤 Buyer HTTP/PostgreSQL smoke 미실행, production/load claim 없음.
- 외부 근거: Google Cloud·Gradle·Spring·PostgreSQL 공식 문서, Alibaba COLA 작성자, Meituan 기술팀 사례. 적용 범위·한계는 [REF-087/089/092/094](../00-governance/reference-register.md), [REF-056/057/073](../00-governance/reference-register.md), [ADR-0003](../06-decisions/adr/ADR-0003-gradle-module-boundaries.md)에 정리.

## 2026-11-02 — JPA CRUD, package boundaries and shared HTTP handling
- 사용자 선택에 따라 다섯 도메인의 기본 CRUD를 JPA entity/Spring Data repository로 전환. Flyway는 schema owner, Hibernate는 `validate`; concurrency 경로에 필요한 explicit SQL만 JDBC/native query로 유지 ([D-048](../00-governance/decision-log.md), [ADR-0004](../06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md)).
- Admission의 임시 persistence input record·Payment의 중복 state record·unused counter entity/repository 제거. Scheduled forwarding classes를 service 구현에 병합.
- `package-info.java`와 `.api` package 제거. Public modules expose `.service`, `.dto.request`, `.dto.response`, `.exception`; shared status enums remain at `.dto` root. No `*View` names; ArchUnit 1.5.1 verifies the explicit package allowlist.
- `GlobalExceptionHandler` 하나가 모듈별 단일 typed exception과 Code enum을 HTTP ProblemDetail로 변환. Raw `IllegalArgumentException`은 client validation 응답으로 매핑하지 않음. `CreateCampaignHttpRequest`·`JoinQueueHttpRequest`는 immutable validation records; domain Request와 transport shape가 다른 경우에만 별도 DTO.
- 확인: `./gradlew check -x :application-module:test` 성공; targeted ArchUnit `ModuleBoundaryTest`·`GlobalExceptionHandlerTest` 성공; `:application-module:bootJar` 성공. 전체 `:application-module:test` 성공: Buyer flow 11, Inventory concurrency 2, Payment lifecycle 1, boundary 1, exception 2 (17 passed). Testcontainers/PostgreSQL 실행에서 `ddl-auto=validate` 통과; no performance/production claim.
- 참고: module 경계와 계층 결정은 [ADR-0003](../06-decisions/adr/ADR-0003-gradle-module-boundaries.md), JPA/SQL은 [ADR-0004](../06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md), 근거는 [REF-087/089/092/094/095/096/098](../00-governance/reference-register.md). 성능·production 보장은 주장하지 않음.

## 2026-11-03 — Persistence vocabulary, test scope and final verification
- Lifecycle enums now live in `.persistence.state`, separate from request/response DTOs. Only these explicitly allowed state packages cross module boundaries; JPA entity/repository types remain private. Persisted lifecycle values use `EnumType.STRING` with matching database `CHECK` constraints.
- Entity names use the persisted concept without redundant `Entity` suffix: `Campaign`, `QueueTicket`, `Pool`, `Reservation`, `PurchaseOrder`, `PaymentAttempt`. Campaign status uses typed `CampaignStatus` mapping; existing `VARCHAR`/CHECK schema and V1–V5 migration history were unchanged.
- Test audit found no redundant suite: existing tests cover different service preconditions, HTTP behavior, PostgreSQL concurrency, payment recovery, error mapping, and package boundaries. No tests were added or deleted. `ModuleBoundaryTest` remains because Gradle dependency edges do not enforce package-layer boundaries. Test criteria are recorded in [AGENTS.md](../../AGENTS.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), and [REF-099](../00-governance/reference-register.md).
- Verification: `./gradlew check :application-module:bootJar` passed, including domain unit and PostgreSQL/Testcontainers suites; Hibernate `ddl-auto=validate` passed. Markdown lint covered 33 files with 0 issues. Local `bootRun` against Compose PostgreSQL applied V1–V5; GET for an unknown campaign returned 404 ProblemDetail with `CAMPAIGN_NOT_FOUND`. No performance or production claim.
- Design record: [ADR-0003](../06-decisions/adr/ADR-0003-gradle-module-boundaries.md), [ADR-0004](../06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md), [D-047/D-048](../00-governance/decision-log.md).
- GitHub: [PR #83](https://github.com/Neo1228/ATLAS/pull/83) is open on `refactor/82-layered-module-boundaries` with source/docs commits [`3531ee4`](https://github.com/Neo1228/ATLAS/commit/3531ee4) and [`3668796`](https://github.com/Neo1228/ATLAS/commit/3668796). [Issue #82](https://github.com/Neo1228/ATLAS/issues/82) now treats the latest implementation as authoritative and closes on PR merge. The older Sale/Queue and assembler draft is superseded; merged PR #79/#80 facts remain unchanged.
