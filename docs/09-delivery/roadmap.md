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

Gate 종료/진입에는 사용자 승인이 필요하다. 순서는 조정 가능하나 A/B/C 장기 범위는 유지한다.

| Gate / Milestone | 목적 | 상태 |
|---|---|---|
| Gate 0 | 헌장·문서 거버넌스 초안 및 미결정 목록 | 완료; 사용자 승인 (2026-10-29) |
| Gate 1 | 문제/이해관계자/용어/핵심 도메인 충돌 발견 | 진행 중; Q-003 추가 경계 OPEN, baseline은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록 |
| Gate 2 | FR/NFR·우선순위·예외·인수 조건·불변조건 정의와 합성 workload profile 합의 ([Q-008](../00-governance/assumptions-and-open-questions.md)) | 보류 |
| Gate 3 | 모듈/상태/데이터 경계·계약·ADR·위험 모델 및 `docs/05-architecture/capacity-model.md` 초안 (계획 문서; 아직 생성하지 않음) | 보류 |
| M1 | 캠페인·재고 예약·주문 의도·동시성 | 계획 |
| M2 | PayPal Sandbox/Fake PSP·결제 상태·복구·멱등성 | 계획 |
| M3 | 합의된 합성 traffic profile로 queue/cache/event·동시성·병목 비교 ([EXP-001/002/008](../08-experiments/experiment-registry.md)) | 계획 |
| M4 | 테넌트 보안·원장·정산·대사·환불/정정 | 계획 |
| M5 | 관측·보안·백업/복구·SLO/RTO/RPO 가설의 측정·장애 주입 ([EXP-009](../08-experiments/experiment-registry.md)) | 계획 |
| M6 | 대안 실험·ADR 정리·포트폴리오 근거 | 계획 |

## 문서 작업 묶음 — 계획 및 상태

아래 WP는 추적·검토 단위의 제안이지 PR 경계를 강제하지 않는다. 관련 Issue/커밋은 하나의 일관된 수락·리뷰 경계를 공유하면 PR 하나로 묶고, 독립 위험/목적은 분리한다.

| ID | 범위 | 파일 후보 | 상태 |
|---|---|---|---|
| WP-001 | 진입점/GitHub 기초 | [README](../../README.md), [AGENTS](../../AGENTS.md), [CONTRIBUTING](../../CONTRIBUTING.md), [PR template](../../.github/PULL_REQUEST_TEMPLATE.md) | main에 seed 게시 |
| WP-002 | 헌장·거버넌스·결정 | [문서 인덱스](../README.md), [거버넌스 문서](../00-governance/project-charter.md) | PR #9 merged |
| WP-003 | 발견·요구사항·도메인 규칙 | [Discovery](../01-discovery/glossary.md), [Requirements](../02-requirements/requirements-index.md), [Domain Rules](../03-domain/README.md) | PR #10, #21 merged |
| WP-004 | ADR·실험 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Experiment Registry](../08-experiments/experiment-registry.md) | PR #11 merged |
| WP-005 | 로드맵·마일스톤·인계 | [Roadmap](roadmap.md), [Milestone Log](milestone-log.md), [Session Handoff](session-handoff.md) | PR #12 merged; final status PR #18 |

## 성능·트래픽 가정의 진행 순서
- **Gate 2:** 합성 workload 유형, request mix, RPS/동시성, ramp-up/burst, p95/p99, budget을 사용자와 합의하고 목표와 가정을 구분한다. 합의 전 값은 TBD/ASSUMPTION.
- **Gate 3:** 계획 중인 `docs/05-architecture/capacity-model.md`에서 합의된 profile과 선택된 DB/인프라 제약으로 용량 추정·병목·민감도를 구조화한다. 해당 파일은 아직 만들지 않았고 추정값은 실측이 아니다.
- **Gate 4:** [EXP-001/002/009](../08-experiments/experiment-registry.md) 등 합성 부하/장애 실험으로 모델을 검증·갱신한다. 재현 가능한 실측 전 성능 달성을 주장하지 않는다.
- 추적: [Q-008/NFR-002](../00-governance/assumptions-and-open-questions.md), [Issue #20](https://github.com/Neo1228/ATLAS/issues/20); EXP-001/002/009는 Planned.

## 현재 상태
WP-001~WP-005 are merged to main. Git conventions PR #4, batching policy PR #16, and final delivery handoff PR #18 are merged. Gate 0 was user-approved on 2026-10-29. Gate 1 discovery continues; Issues #19/#20/#22/#24 are closed. Q-003 edge decisions remain OPEN in the question register.

Next: group the remaining Q-003 boundary choices into one reviewable work item when agreed; do not create an Issue per edge. Q-008 traffic/capacity values remain TBD until Gate 2 workload profiles are agreed.
각 마일스톤 전 요구사항·정책·검증 계약을 정의한다. 예상 수치를 실측처럼 쓰지 않으며 성능/복구 주장은 재현 가능한 실험 결과와 연결한다.
