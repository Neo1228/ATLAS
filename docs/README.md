# ATLAS 문서 인덱스

- 상태: Draft
- 담당: 프로젝트 책임자
updated: 2026-10-30

## 기준 문서 (SSOT)

| 주제 | 기준 문서 |
|---|---|
| 프로젝트 범위·승인 | [Project Charter](00-governance/project-charter.md) |
| 열린 질문·가정 | [Assumptions & Open Questions](00-governance/assumptions-and-open-questions.md) |
| 결정·변경·완료 기준 | [Decision Log](00-governance/decision-log.md), [Change Control](00-governance/change-control.md), [Definition of Done](00-governance/definition-of-done.md) |
| 외부 자료 | [Reference Register](00-governance/reference-register.md) |
| 사용자 목표·용어 | [Personas & Jobs](01-discovery/personas-and-jobs.md), [Glossary](01-discovery/glossary.md) |
| 요구사항 | [Requirements Index](02-requirements/requirements-index.md) |
| 도메인 규칙·불변조건 | [Business Rules](03-domain/business-rules.md), [Invariants](03-domain/invariants.md) |
| 설계·실험 | [ADR-0001](06-decisions/adr/ADR-0001-architecture-style.md), [ADR Template](06-decisions/adr/TEMPLATE.md), [Experiment Registry](08-experiments/experiment-registry.md), [Experiment Template](08-experiments/TEMPLATE.md) |
| 진행·이력 | [Roadmap](09-delivery/roadmap.md), [Milestone Log](09-delivery/milestone-log.md), [Session Handoff](09-delivery/session-handoff.md) |

## 빠른 안내
- **Gate**: 사용자 승인 단계. ✓ Gate 0 완료, → Gate 1 진행 중. [Roadmap](09-delivery/roadmap.md)
- **추적 ID:** [D](00-governance/decision-log.md), [Q](00-governance/assumptions-and-open-questions.md), [FR/NFR](02-requirements/requirements-index.md), [BR](03-domain/business-rules.md), [INV](03-domain/invariants.md), [ADR](06-decisions/adr/ADR-0001-architecture-style.md), [EXP](08-experiments/experiment-registry.md), [REF](00-governance/reference-register.md), [M/WP](09-delivery/roadmap.md).
| 상태 | 표기 |
|---|---|
| 확정/완료 | ✓ Confirmed |
| 승인 전 제안 | △ Proposed |
| 미정 | ? OPEN |
| 작성 중 | `Draft` |
| 미실행 계획 | ○ Planned |
| 검증 전 가정 | `ASSUMPTION` |
| 실제 실행/측정 결과 | `Measured` (실행일·조건·결과 함께 기록) |
| 미정 수치/값 | `TBD` |

- 문장: 핵심 먼저, 한 문단 한 요점. 자연스러운 곳은 명사형/개조식 종결; 불필요한 조사·수식어 최소.
- 수치/단계: 표·불릿 우선. 보편적인 backend term은 English 가능; 프로젝트 용어는 [Glossary](01-discovery/glossary.md) 참조.

## 도구와 작업 규칙
- 저장소 안내: [README](../README.md), [AGENTS](../AGENTS.md), [CONTRIBUTING](../CONTRIBUTING.md)
- GitHub 양식: [PR template](../.github/PULL_REQUEST_TEMPLATE.md), [Bug form](../.github/ISSUE_TEMPLATE/bug_report.yml), [Change form](../.github/ISSUE_TEMPLATE/change_request.yml)
- 필수 검사: [Markdown lint·PR checklist workflow](../.github/workflows/docs-lint.yml)

- ✓ Gate 0 승인, Gate 1 discovery 진행 중. [Roadmap](09-delivery/roadmap.md), [Session Handoff](09-delivery/session-handoff.md).
- Queue 기준: [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), 초기 판매 범위 [Issue #33](https://github.com/Neo1228/ATLAS/issues/33)/[PR #34](https://github.com/Neo1228/ATLAS/pull/34); [Q-003](00-governance/assumptions-and-open-questions.md), [Business Rules](03-domain/business-rules.md).
- Payment/workload: [D-025~D-031](00-governance/decision-log.md). Initial synthetic TTL 10 minutes only; Gate 2 uses full Buyer sessions, hybrid arrival model, baseline/burst/step-up ([Issue #48](https://github.com/Neo1228/ATLAS/issues/48)/[PR #49](https://github.com/Neo1228/ATLAS/pull/49), [Issue #51](https://github.com/Neo1228/ATLAS/issues/51)/[PR #53](https://github.com/Neo1228/ATLAS/pull/53)). Numeric traffic/phase values and production TTL/SLO remain OPEN ([Q-004/Q-005/Q-008](00-governance/assumptions-and-open-questions.md)).
