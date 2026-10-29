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
| Gate 1 | 문제/이해관계자/용어/핵심 도메인 충돌 발견 | 진행 중; Issue #19 / Q-010 |
| Gate 2 | FR/NFR·우선순위·예외·인수 조건·불변조건 정의와 synthetic workload profile 합의 (Q-008) | 보류 |
| Gate 3 | 모듈/상태/데이터 경계·계약·ADR·위험 모델 및 `docs/05-architecture/capacity-model.md` 초안 | 보류 |
| M1 | 캠페인·재고 예약·주문 의도·동시성 | 계획 |
| M2 | PayPal Sandbox/Fake PSP·결제 상태·복구·멱등성 | 계획 |
| M3 | 합의된 synthetic traffic profile로 queue/cache/event·동시성·병목 비교 (EXP-001/002/008) | 계획 |
| M4 | 테넌트 보안·원장·정산·대사·환불/정정 | 계획 |
| M5 | 관측·보안·백업/복구·SLO/RTO/RPO 가설의 측정·장애 주입 (EXP-009) | 계획 |
| M6 | 대안 실험·ADR 정리·포트폴리오 근거 | 계획 |

## 성능·트래픽 가정의 진행 순서
- **Gate 2:** 합성 workload 유형, request mix, RPS/동시성, ramp-up/burst, p95/p99, budget을 사용자와 합의하고 목표와 가정을 구분한다. 합의 전 값은 TBD/ASSUMPTION.
- **Gate 3:** `docs/05-architecture/capacity-model.md`에서 합의된 profile과 선택된 DB/인프라 제약으로 용량 추정·병목·민감도를 구조화한다. 추정값은 실측이 아니다.
- **Gate 4:** EXP-001/002/009 등 합성 부하/장애 실험으로 모델을 검증·갱신한다. 재현 가능한 실측 전 성능 달성을 주장하지 않는다.
- 추적: Q-008, NFR-002, Issue #20; EXP-001/002/009는 Planned.

## Gate 0 문서 PR 작업 묶음 — 제안

아래는 문서 패킷을 위한 작업 단위 예시다. PR 경계는 파일 묶음에 자동 종속되지 않는다. 같은 목적/수락 기준을 가진 항목은 함께 검토하고, 독립 위험/목적은 나눈다. Issue는 작업 전 생성한다.

| ID | 작업 범위 | 파일 후보 | 선행 조건 / 완료 기준 |
|---|---|---|---|
| WP-001 | 프로젝트 진입점 및 GitHub 기초 설정 | `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `.gitignore`, `.editorconfig`, `.markdownlint-cli2.yaml`, `.github/` | 설정 파일/템플릿 lint; 문서 실행 안내와 실제 설정 일치 |
| WP-002 | 헌장·거버넌스·결정 기록 | `docs/README.md`, `docs/00-governance/{project-charter,assumptions-and-open-questions,decision-log,change-control,definition-of-done,reference-register}.md` | 사용자 확정/미결정 구분, 추적 ID와 상호 참조 검토 |
| WP-003 | 발견 용어 및 요구사항 인덱스 | `docs/01-discovery/glossary.md`, `docs/01-discovery/personas-and-jobs.md`, `docs/02-requirements/requirements-index.md` | Q/요구사항 연결; 미확정 사실은 Draft/OPEN 유지 |
| WP-004 | 아키텍처 제안 및 실험 계획 | `docs/06-decisions/adr/*`, `docs/08-experiments/*` | ADR Proposed 유지, 모든 실험 Planned이며 측정 결과를 만들지 않음 |
| WP-005 | 로드맵 및 인계 이력 | `docs/09-delivery/*` | Gate 0 승인 및 Gate 1 진행 상태, 실제 Issue/PR/검증만 기록 |

하나의 후보 묶음 내부라도 독립 리뷰 가능한 목적이 둘이면 더 나눈다. 묶음 간 참조에 필요한 파일만 최소한으로 함께 변경한다.
PR은 커밋 수나 이슈 수마다 만들지 않는다. 같은 목적/수락 경계를 공유하는 관련 WP·Issue와 커밋은 하나의 PR로 묶을 수 있다. 독립된 위험/목적/릴리스 경계는 분리한다.

WP-001~WP-005 are merged to main. Git conventions PR #4, batching policy PR #16, and final delivery handoff PR #18 are merged. Gate 0 was user-approved on 2026-10-29. Gate 1 is in progress under Issue #19; capacity planning is tracked by Issue #20.

## 진행 원칙
각 마일스톤 전 요구사항·정책·검증 계약을 정의한다. 예상 수치를 실측처럼 쓰지 않으며 성능/복구 주장은 재현 가능한 실험 결과와 연결한다.
