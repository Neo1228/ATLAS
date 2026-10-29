---
id: DOC-008
title: Roadmap
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 로드맵

Gate는 사용자 승인이 필요한 진행 점검점이다. Gate 완료는 사용자 확인으로만 기록한다. 각 Gate의 상세 목적과 열린 조건은 아래에 적었으며, 일정이나 구현 승인을 자동으로 의미하지 않는다.

| Gate / Milestone | 목적 | 상태 |
|---|---|---|
| Gate 0 | 프로젝트 목적·범위와 문서 관리 기준 확정 | 완료; 사용자 승인 (2026-10-29) |
| Gate 1 | 문제·사용자 목표·핵심 도메인의 충돌 탐색 | 진행 중; Q-003 세부 경계 OPEN, baseline은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록 |
| Gate 2 | 요구사항 우선순위·예외·완료 기준·불변조건을 정하고 합성 부하 프로필 합의 ([Q-008](../00-governance/assumptions-and-open-questions.md)) | 보류 |
| Gate 3 | 모듈·상태·데이터 경계, 계약, ADR, 위험과 용량 추정 초안 마련 | 보류; capacity model은 승인 후 작성 |
| M1 | 캠페인·재고 예약·주문 의도·동시성 | 계획 |
| M2 | PayPal Sandbox/Fake PSP·결제 상태·복구·멱등성 | 계획 |
| Gate 4 (용량 검증) | 승인된 관련 기능으로 합성 부하/장애 실험을 실행해 Gate 3 용량 추정과 가정을 검증 ([EXP-001/002/009](../08-experiments/experiment-registry.md)) | 제안; 관련 구현과 별도 구현 승인이 선행 조건 |
| M3 | 합의된 합성 부하 프로필로 queue/cache/event·동시성·병목 비교 (Gate 4 검증; [EXP-001/002/008](../08-experiments/experiment-registry.md)) | 계획 |
| M4 | 테넌트 보안·원장·정산·대사·환불/정정 | 계획 |
| M5 | 관측·보안·백업/복구·SLO/RTO/RPO 가설의 측정·장애 주입 ([EXP-009](../08-experiments/experiment-registry.md)) | 계획 |
| M6 | 대안 실험·ADR 정리·포트폴리오 근거 | 계획 |

## 문서 작업 묶음 — 계획 및 상태
WP는 Work Package(초기 문서 작업 묶음)의 기록 ID다. 이 번호가 Issue/PR 개수나 이후 작업 분할 방식을 정하지 않는다. 관련 변경은 하나의 리뷰 가능한 경계로 묶는다.

| ID | 범위 | 파일 후보 | 상태 |
|---|---|---|---|
| WP-001 | 저장소 진입점·GitHub 기초 | [README](../../README.md), [AGENTS](../../AGENTS.md), [CONTRIBUTING](../../CONTRIBUTING.md), [PR template](../../.github/PULL_REQUEST_TEMPLATE.md) | main에 게시됨 |
| WP-002 | 프로젝트 헌장·거버넌스·결정 | [문서 인덱스](../README.md), [거버넌스 문서](../00-governance/project-charter.md) | [PR #9](https://github.com/Neo1228/ATLAS/pull/9) 병합 |
| WP-003 | 발견·요구사항·도메인 규칙 | [Discovery](../01-discovery/glossary.md), [Requirements](../02-requirements/requirements-index.md), [Domain Rules](../03-domain/README.md) | [PR #10](https://github.com/Neo1228/ATLAS/pull/10), [PR #21](https://github.com/Neo1228/ATLAS/pull/21) 병합 |
| WP-004 | ADR·실험 계획 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Experiment Registry](../08-experiments/experiment-registry.md) | [PR #11](https://github.com/Neo1228/ATLAS/pull/11) 병합 |
| WP-005 | 로드맵·진행 기록 | [Roadmap](roadmap.md), [Milestone Log](milestone-log.md), [Session Handoff](session-handoff.md) | [PR #12](https://github.com/Neo1228/ATLAS/pull/12), [PR #18](https://github.com/Neo1228/ATLAS/pull/18) 병합 |

## 성능·트래픽 가정의 진행 순서
- **Gate 2:** 요청 구성(request mix), 초당 요청 수(RPS), 동시성, 급증 조건(burst), p95/p99 지연시간과 비용을 사용자와 합의한다. 목표와 가정을 분리하고, 합의 전 숫자는 TBD로 둔다.
- **Gate 3:** 계획 문서 `docs/05-architecture/capacity-model.md`에서 합의된 부하 프로필과 DB/인프라 조건으로 처리 용량·병목·민감도를 추정한다. 파일은 아직 없고, 추정은 실측이 아니다.
추적: [Q-008/NFR-002](../00-governance/assumptions-and-open-questions.md), [Issue #20](https://github.com/Neo1228/ATLAS/issues/20). Gate 4는 성능 검증 순서에서 제안한 단계이며, 사용자 승인 전 독립된 프로젝트 Gate로 취급하지 않는다.

## 현재 상태
WP-001~WP-005는 main에 게시됐다. Git 규칙(PR #4), 여러 커밋·이슈를 한 PR로 묶는 기준(PR #16), 진행 기록 정리(PR #18)도 병합됐다. Gate 0은 2026-10-29 승인됐다. Gate 1은 진행 중이며 Issue #19/#20/#22/#24는 닫혔다. Q-003의 나머지 경계는 여전히 미정이다.

다음에는 Q-003의 남은 경계를 하나의 응집된 작업으로 묶을지 합의한다. 경계 하나마다 Issue를 만들지 않는다. Q-008의 트래픽/용량 목표는 Gate 2에서 합의하기 전까지 TBD다.
각 마일스톤 전 요구사항·정책·검증 계약을 정의한다. 예상 수치를 실측처럼 쓰지 않으며 성능/복구 주장은 재현 가능한 실험 결과와 연결한다.
