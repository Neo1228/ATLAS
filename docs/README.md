# ATLAS 문서 인덱스

- 상태: Draft
- 담당: 프로젝트 책임자
updated: 2026-11-02

## 기준 문서 (SSOT)

| 주제 | 기준 문서 |
|---|---|
| 프로젝트 범위·승인 | [프로젝트 헌장](00-governance/project-charter.md) |
| 열린 질문·가정 | [가정·미결정 사항](00-governance/assumptions-and-open-questions.md) |
| 결정·변경·완료 기준 | [결정 로그](00-governance/decision-log.md), [변경 통제](00-governance/change-control.md), [완료 기준](00-governance/definition-of-done.md) |
| 외부 자료 | [참고자료 목록](00-governance/reference-register.md) |
| 사용자 목표·용어 | [가상 사용자 목표](01-discovery/personas-and-jobs.md), [용어집](01-discovery/glossary.md) |
| 요구사항 | [요구사항 인덱스](02-requirements/requirements-index.md) |
| 도메인 규칙·불변조건 | [비즈니스 규칙](03-domain/business-rules.md), [불변조건](03-domain/invariants.md) |
| 설계·실험 | [ADR-0001](06-decisions/adr/ADR-0001-architecture-style.md), [ADR 양식](06-decisions/adr/TEMPLATE.md), [실험 목록](08-experiments/experiment-registry.md), [실험 양식](08-experiments/TEMPLATE.md) |
| 진행·이력 | [로드맵](09-delivery/roadmap.md), [마일스톤 로그](09-delivery/milestone-log.md), [세션 인계](09-delivery/session-handoff.md) |

## 빠른 안내
- **Gate**: Gate 0/1 완료, Gate 2의 current Buyer journey 승인, Gate 3의 modular monolith·Java/Spring/PostgreSQL 설계 진행. [Roadmap](09-delivery/roadmap.md)
- 장기 제품 역량과 미결정 범위: [Project Charter](00-governance/project-charter.md), [Requirements Index](02-requirements/requirements-index.md).
- 추적 ID:
  - 결정·질문: [D](00-governance/decision-log.md), [Q](00-governance/assumptions-and-open-questions.md)
  - 요구사항·규칙: [FR/NFR](02-requirements/requirements-index.md), [BR](03-domain/business-rules.md), [INV](03-domain/invariants.md)
  - 설계·실험·진행: [ADR](06-decisions/adr/ADR-0001-architecture-style.md), [EXP](08-experiments/experiment-registry.md), [REF](00-governance/reference-register.md), [M/WP](09-delivery/roadmap.md)
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

- 문장: 핵심부터. 한 문단에 한 가지 내용. 가능하면 짧은 명사형·개조식으로 작성.
- 독자: 프로젝트 맥락을 모르는 사람도 이해하도록 개념·이유를 본문에 짧게 설명. ID만 단독으로 남기지 않음.
- 수치·단계: 단위와 기준을 붙여 표나 불릿으로 표시.
- 기술 용어: API, retry, queue, transaction처럼 정확한 backend 용어는 English 사용 가능. 일반 설명은 한국어 우선; 불필요한 전문 용어·병기 금지.

## 도구와 작업 규칙
- 저장소 안내: [README](../README.md), [작업 규칙](../AGENTS.md), [기여 안내](../CONTRIBUTING.md)
- GitHub 양식: [PR template](../.github/PULL_REQUEST_TEMPLATE.md), [버그 양식](../.github/ISSUE_TEMPLATE/bug_report.yml), [변경 제안 양식](../.github/ISSUE_TEMPLATE/change_request.yml)
- 필수 검사: [Markdown lint·PR checklist workflow](../.github/workflows/docs-lint.yml)

## 주요 도메인 결정과 이력
- 대기열: [D-019~D-022/D-024/D-034](00-governance/decision-log.md), [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Issue #60](https://github.com/Neo1228/ATLAS/issues/60), [PR #61](https://github.com/Neo1228/ATLAS/pull/61), [Q-003](00-governance/assumptions-and-open-questions.md), [도메인 규칙](03-domain/business-rules.md).
- 재고: [D-023/D-035](00-governance/decision-log.md), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63), [BR-002](03-domain/business-rules.md), [INV-001](03-domain/invariants.md), [Q-002](00-governance/assumptions-and-open-questions.md).
- 초기 판매 범위: [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [PR #34](https://github.com/Neo1228/ATLAS/pull/34), [D-023/D-024/D-036](00-governance/decision-log.md), [Issue #64](https://github.com/Neo1228/ATLAS/issues/64), [PR #65](https://github.com/Neo1228/ATLAS/pull/65). Gate 2는 quantity-SKU만 포함.
- Gate 2 workload: [D-031~D-036](00-governance/decision-log.md), [Issue #51](https://github.com/Neo1228/ATLAS/issues/51), [Issue #54](https://github.com/Neo1228/ATLAS/issues/54), [Issue #56](https://github.com/Neo1228/ATLAS/issues/56), [Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [PR #67](https://github.com/Neo1228/ATLAS/pull/67). 10k sessions/60m, 2×/4× synthetic stress, report-only; production target은 미정.
- Workload 기록: [PR #53](https://github.com/Neo1228/ATLAS/pull/53) → [5266d14](https://github.com/Neo1228/ATLAS/commit/5266d14), [PR #55](https://github.com/Neo1228/ATLAS/pull/55) → [a82b454](https://github.com/Neo1228/ATLAS/commit/a82b454), [PR #57](https://github.com/Neo1228/ATLAS/pull/57) → [6cf28dd](https://github.com/Neo1228/ATLAS/commit/6cf28dd).
- Gate 3 설계: [D-037~D-039](00-governance/decision-log.md), [ADR-0001](06-decisions/adr/ADR-0001-architecture-style.md), [Issue #68](https://github.com/Neo1228/ATLAS/issues/68). 현재 Buyer flow module 5개, 단일 PostgreSQL schema, campaign counter row 기준. Exact schema/lock recovery와 runtime 구현은 미승인.
