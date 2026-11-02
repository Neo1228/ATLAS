---
id: DOC-008
title: Roadmap
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: [FR-001, FR-002, FR-003, NFR-001, NFR-002]
  adr: [ADR-0001, ADR-0003, ADR-0004]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009, EXP-010, EXP-011, EXP-012]
---
# 로드맵

Gate는 사용자 승인이 필요한 점검 단계. 명시적 승인 없이 상태 변경 금지. 작은 작업마다 별도 Gate를 만들지 않음.

| 단계 | 결과 | 상태 |
|---|---|---|
| Gate 0 | 프로젝트 범위·문서 기준 | ✓ 완료, 2026-10-29 승인 |
| Gate 1 | 문제·사용자 목표·도메인 탐색 | ✓ 초기 single-SKU 시나리오 완료 (2026-10-30). 판매 유형·재고 정책·대기열 세부는 [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md) 미정 |
| Gate 2 | 요구사항·예외·완료 기준·불변조건·workload profile | ✓ 현재 Buyer journey 기준 승인. 정산·보안 범위는 미정 |
| Gate 3 | Module·state·data·contract·risk·capacity estimate | ✓ Boundary/toolchain and report-only envelope recorded. Numeric capacity model not produced: request mix, environment, latency and production targets remain OPEN ([D-037~D-044](../00-governance/decision-log.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [ADR-0002](../06-decisions/adr/ADR-0002-runtime-technology-review.md)) |
| Gate 4 | Synthetic load/failure verification | ○ M1 local runtime complete. Entry approval and environment/request mix/phase still required. Report-only, no production SLO ([Q-008](../00-governance/assumptions-and-open-questions.md)) |
| M1 | Local single-SKU Buyer flow: Campaign, Admission, Inventory, Order/Checkout, PostgreSQL, Fake PSP, tests/CI | ✓ [PR #73](https://github.com/Neo1228/ATLAS/pull/73) merged; [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) closed |
| M2 | Synthetic load/failure verification, report-only | ○ Gate 4 run after entry/environment approval; no measurement result yet |
| M3 | PayPal Sandbox adapter, webhook·payment recovery | ○ Provider/API and failure/recovery policy decision required; sequencing relative to M2, credentials and scope remain OPEN |
| M4 | 장기 역량 확장: Tenant/security, catalog·판매 유형, settlement·reconciliation, refund/adjustment | ○ 계획 ([Project Charter](../00-governance/project-charter.md), [Q-002/Q-006/Q-007/Q-010](../00-governance/assumptions-and-open-questions.md)) |
| M5 | Observability, backup/recovery, SLO/RTO/RPO | ○ 운영 환경 선택 뒤 계획 |
| M6 | 대안 비교, ADR 정리, portfolio 근거 | ○ 계획 |

## 초기 문서 작업 묶음
WP는 초기 문서 작업 이력 ID. 이후 Issue/PR 개수나 분할 기준 아님.

| WP | 범위 | 기록 |
|---|---|---|
| WP-001 | 저장소 진입점·GitHub 기초 | main 게시 |
| WP-002 | 거버넌스 | [PR #9](https://github.com/Neo1228/ATLAS/pull/9) 병합 |
| WP-003 | discovery·requirements·domain | [PR #10](https://github.com/Neo1228/ATLAS/pull/10), [#21](https://github.com/Neo1228/ATLAS/pull/21) 병합 |
| WP-004 | ADR·experiments | [PR #11](https://github.com/Neo1228/ATLAS/pull/11) 병합 |
| WP-005 | roadmap·delivery records | [PR #12](https://github.com/Neo1228/ATLAS/pull/12), [#18](https://github.com/Neo1228/ATLAS/pull/18) 병합 |

## 용량·성능 검증 순서 ([D-018](../00-governance/decision-log.md), △ Proposed)
- **Gate 2 — workload:** request/transaction mix, offered·achieved RPS, transaction 범위를 명시한 TPS. DB query 측정 시에만 QPS.
- **Gate 2 — model:** concurrency, open/closed model, payload, burst/ramp, duration.
- **Gate 2 — 목표:** p95/p99 latency, error rate, budget, SLO/RTO/RPO. 수치 목표는 합의 전 TBD.
- **Gate 3:** 합의한 profile과 DB/infra 조건으로 capacity model 작성. 추정은 측정값이 아님. `docs/05-architecture/capacity-model.md` 예정; 현재 없음.
- **Gate 4:** 구현 승인 후 synthetic load/failure test로 추정 검증. [EXP-001/002/009](../08-experiments/experiment-registry.md)는 ○ Planned.
- 추적: [Q-008](../00-governance/assumptions-and-open-questions.md), [NFR-002](../02-requirements/requirements-index.md), [Issue #20](https://github.com/Neo1228/ATLAS/issues/20).

## 현재 상태
- WP-001~WP-005 main 게시. Git 규칙 [PR #4](https://github.com/Neo1228/ATLAS/pull/4), batching [PR #16](https://github.com/Neo1228/ATLAS/pull/16), delivery [PR #18](https://github.com/Neo1228/ATLAS/pull/18) 병합.
- [Issue #37](https://github.com/Neo1228/ATLAS/issues/37)에서 AUTHORIZE → CAPTURE 선택. [D-026](../00-governance/decision-log.md): capture success 확인 후 inventory claim. [PR #41](https://github.com/Neo1228/ATLAS/pull/41) 병합.
- Gate 1 완료, Gate 2 current Buyer journey 승인. Assigned-seat/time-slot은 현재 범위 밖 ([D-023~D-036](../00-governance/decision-log.md), [Q-010](../00-governance/assumptions-and-open-questions.md)).
- [Q-003](../00-governance/assumptions-and-open-questions.md): [D-034](../00-governance/decision-log.md)에서 active ticket retry, payload 불일치 거절, 만료 후 새 key·tail sequence 결정. [D-039](../00-governance/decision-log.md)에서 campaign counter row + ticket 단일 transaction 선택. [Issue #60](https://github.com/Neo1228/ATLAS/issues/60), [PR #61](https://github.com/Neo1228/ATLAS/pull/61). Key scope/retention, payload 비교 필드, expiry trigger/duration, exact schema, lock timeout/deadlock retry, bot policy, 다른 판매 유형은 미정.
- [Q-002](../00-governance/assumptions-and-open-questions.md): [D-035](../00-governance/decision-log.md)에서 reservation당 1개와 재고 conservation invariant 확정. [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63). 조정·재입고·다중 수량·post-claim 취소/환불 정책은 미정; D-027 recovery 기준 유지.
- Gate 2 승인 기록: current Buyer journey와 report-only workload 기준 ([Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [PR #67](https://github.com/Neo1228/ATLAS/pull/67)). Production SLO·환경·성능 보장은 승인 범위 밖.
- Workload 이력: [Issue #51](https://github.com/Neo1228/ATLAS/issues/51), [Issue #54](https://github.com/Neo1228/ATLAS/issues/54), [Issue #56](https://github.com/Neo1228/ATLAS/issues/56); [PR #53](https://github.com/Neo1228/ATLAS/pull/53) → [5266d14](https://github.com/Neo1228/ATLAS/commit/5266d14), [PR #55](https://github.com/Neo1228/ATLAS/pull/55) → [a82b454](https://github.com/Neo1228/ATLAS/commit/a82b454), [PR #57](https://github.com/Neo1228/ATLAS/pull/57) → [6cf28dd](https://github.com/Neo1228/ATLAS/commit/6cf28dd).
- Gate 3: modular monolith, current-flow modules, PostgreSQL ownership/transactions, queue counter-row baseline, stack and runtime contract recorded ([D-037~D-042](../00-governance/decision-log.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Issue #68](https://github.com/Neo1228/ATLAS/issues/68), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72)).
- Local implementation and verification merged in [PR #73](https://github.com/Neo1228/ATLAS/pull/73), merge [`223a09320c3206b066bc224bde63c09e4ac6b0c5`](https://github.com/Neo1228/ATLAS/commit/223a09320c3206b066bc224bde63c09e4ac6b0c5). Live payment, deployment, production sizing and load claims remain outside the approval.
- Current package/persistence structure: [D-047/D-048](../00-governance/decision-log.md), [ADR-0003](../06-decisions/adr/ADR-0003-gradle-module-boundaries.md), [ADR-0004](../06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md). Five `*-module` domains plus `application-module`; public `.service`/`.dto.request`/`.dto.response`/`.exception`, lifecycle enums shared from `.persistence.state`, entity/repository kept module-private; implementation in `.service.impl`/`.persistence.*`. CRUD uses JPA; concurrency-specific SQL remains JDBC/native. ArchUnit checks package dependencies. No package-info markers, CQRS or `.internal`.
- Current verification (2026-11-03): `./gradlew check :application-module:bootJar` passed, including Campaign/Admission unit suites and the PostgreSQL/Testcontainers application suites; JPA `ddl-auto=validate` passed. `bootRun` with Compose PostgreSQL applied V1–V5 and GET of an unknown campaign returned 404 `CAMPAIGN_NOT_FOUND`. Markdown lint: 33 files, 0 issues. [PR #83](https://github.com/Neo1228/ATLAS/pull/83) squash-merged to `main` as [`b0ca9c7`](https://github.com/Neo1228/ATLAS/commit/b0ca9c7a80cf6aaa3a64bd7468e8fb70a423f9aa); [Issue #82](https://github.com/Neo1228/ATLAS/issues/82) closed. No production/load claim.
- [Q-008](../00-governance/assumptions-and-open-questions.md): burst factor/duration, production demand/SLO, capacity target and deployment remain OPEN.
