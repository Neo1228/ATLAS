# ATLAS 문서 인덱스

- 상태: Draft
- 담당: 프로젝트 책임자
- 갱신일: 2026-10-29

## 기준 문서 (단일 진실 공급원, Single Source of Truth/SSOT)
- 프로젝트 목적·범위: [Project Charter](00-governance/project-charter.md)
- 열린 질문·가정: [Assumptions & Open Questions](00-governance/assumptions-and-open-questions.md)
- 결정·변경 절차: [Decision Log](00-governance/decision-log.md), [Change Control](00-governance/change-control.md), [Definition of Done](00-governance/definition-of-done.md)
- 외부 자료와 검증 상태: [Reference Register](00-governance/reference-register.md)
- 용어·가상 사용자 목표: [Glossary](01-discovery/glossary.md), [Personas & Jobs](01-discovery/personas-and-jobs.md)
- 요구사항: [Requirements Index](02-requirements/requirements-index.md)
- 도메인 규칙·불변조건: [Domain Index](03-domain/README.md), [Business Rules](03-domain/business-rules.md), [Invariants](03-domain/invariants.md)
- 아키텍처 가설·기록 양식: [ADR-0001](06-decisions/adr/ADR-0001-architecture-style.md), [ADR Template](06-decisions/adr/TEMPLATE.md)
- 실험 계획: [Experiment Registry](08-experiments/experiment-registry.md), [Experiment Template](08-experiments/TEMPLATE.md)
- 진행·이력: [Roadmap](09-delivery/roadmap.md), [Milestone Log](09-delivery/milestone-log.md), [Session Handoff](09-delivery/session-handoff.md)

## 읽는 법
- **Gate**는 사용자 승인이 필요한 단계다. Gate 0(문서·거버넌스)은 승인 완료됐고 Gate 1(문제·도메인 탐색)은 진행 중이다. 자세한 순서는 [Roadmap](09-delivery/roadmap.md)을 참조한다.
- 추적 ID: `D` 결정, `Q` 열린 질문, `FR/NFR` 기능/비기능 요구사항, `BR/INV` 규칙/불변조건, `ADR` 아키텍처 결정, `EXP` 실험, `M` 장기 milestone, `WP` 초기 문서 작업 묶음이다. ID의 기준 문서는 위 색인에서 찾는다.
`Confirmed baseline`은 현재 합의된 출발 기준이며 모든 세부 정책/구현이 확정됐다는 뜻은 아니다. `Proposed`는 승인 전 제안, `Assumption`은 검증 전 가정, `OPEN`은 미정, `Planned`는 미수행, `TBD`는 아직 정하지 않은 값이다. `Draft`는 작성 중 문서를 뜻한다.
문장은 자연스러운 한국어를 기본으로 하고, API/PR/commit/retry처럼 널리 쓰이는 영문 기술 용어는 정확성과 자연스러움을 위해 그대로 사용할 수 있다. 프로젝트 전용 약어·도메인 용어는 처음 나올 때 설명하거나 [Glossary](01-discovery/glossary.md)를 참조한다.

## 작업 진입점
- 저장소 안내: [README](../README.md), [AGENTS](../AGENTS.md), [CONTRIBUTING](../CONTRIBUTING.md)
- PR 작성: [Pull Request Template](../.github/PULL_REQUEST_TEMPLATE.md)
- GitHub Issues: [Bug Report Form](../.github/ISSUE_TEMPLATE/bug_report.yml), [Change Request Form](../.github/ISSUE_TEMPLATE/change_request.yml)
- 문서 품질 검사: [Markdown lint 및 PR checklist workflow](../.github/workflows/docs-lint.yml)

Gate 0(문서와 관리 기준)은 2026-10-29 승인됐고 Gate 1(문제·도메인 탐색)은 진행 중이다. 대기열의 서버 순번, 재시도, 재시작 기준은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록됐으며 Q-003의 나머지 정책은 미정이다. 가상 구매자/판매자 목표는 [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), 성능·용량 계획 순서는 [Issue #20](https://github.com/Neo1228/ATLAS/issues/20)에 기록됐다. 애플리케이션 코드·런타임 의존성·배포는 아직 시작하지 않았다.

## 문서 상태
문서 상태의 뜻: `Draft`는 작성 중, `Proposed`는 승인 전 제안, `Confirmed`는 승인된 결정, `OPEN`은 미결정, `Planned`는 아직 수행하지 않은 작업이다. 외부 자료의 검증 상태는 [Reference Register](00-governance/reference-register.md)에 기록한다.
