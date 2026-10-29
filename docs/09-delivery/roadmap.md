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
| Gate 0 | 헌장·문서 거버넌스 초안 및 미결정 목록 | 진행 중; 사용자가 추가 보완을 요청함, 미승인 |
| Gate 1 | 문제/이해관계자/용어/핵심 도메인 충돌 발견 | 보류; Gate 0 승인 전 |
| Gate 2 | FR/NFR·우선순위·예외·인수 조건·불변조건 정의 | 보류 |
| Gate 3 | 모듈/상태/데이터 경계·계약·ADR·위험 모델 | 보류 |
| M1 | 캠페인·재고 예약·주문 의도·동시성 | 계획 |
| M2 | PayPal Sandbox/Fake PSP·결제 상태·복구·멱등성 | 계획 |
| M3 | 대기열/캐시/이벤트 및 부하·병목 비교 | 계획 |
| M4 | 테넌트 보안·원장·정산·대사·환불/정정 | 계획 |
| M5 | 관측·보안·백업/복구·SLO 가설·장애 주입 | 계획 |
| M6 | 대안 실험·ADR 정리·포트폴리오 근거 | 계획 |

## Gate 0 문서 PR 작업 묶음 — 제안

아래는 추후 로컬 커밋과 PR을 작게 구성하기 위한 후보 작업 단위다. 아직 커밋/PR은 만들지 않았고, 저장소/원격 설정 이후 각 묶음을 검토해 순차적으로 처리한다. 파일 수보다 단일 목적과 추적성을 우선한다.

| ID | 작업 범위 | 파일 후보 | 선행 조건 / 완료 기준 |
|---|---|---|---|
| WP-001 | 프로젝트 진입점 및 GitHub 기초 설정 | `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `.gitignore`, `.editorconfig`, `.markdownlint-cli2.yaml`, `.github/` | 설정 파일/템플릿 lint; 문서 실행 안내와 실제 설정 일치 |
| WP-002 | 헌장·거버넌스·결정 기록 | `docs/README.md`, `docs/00-governance/{project-charter,assumptions-and-open-questions,decision-log,change-control,definition-of-done,reference-register}.md` | 사용자 확정/미결정 구분, 추적 ID와 상호 참조 검토 |
| WP-003 | 발견 용어 및 요구사항 인덱스 | `docs/01-discovery/glossary.md`, `docs/02-requirements/requirements-index.md` | 각 제안 상태/열린 질문 연결, Gate 1 승인 전 Draft 유지 |
| WP-004 | 아키텍처 제안 및 실험 계획 | `docs/06-decisions/adr/*`, `docs/08-experiments/*` | ADR Proposed 유지, 모든 실험 Planned이며 측정 결과를 만들지 않음 |
| WP-005 | 로드맵 및 인계 이력 | `docs/09-delivery/*` | Gate 0 미승인 상태, 다음 작업, 실제 수행한 검증/커밋/PR만 기록 |

하나의 후보 묶음 내부라도 독립 리뷰 가능한 목적이 둘이면 더 나눈다. 묶음 간 참조에 필요한 파일만 최소한으로 함께 변경한다.
Git 작업 묶음은 코드/문서/설정 변경 전에 GitHub Issue를 먼저 만든다. 한 Issue당 한 응집된 작업 단위이며, 여러 커밋을 연결하고 필요 시 여러 PR로 나눈다. 마지막 완료 PR만 Issue를 닫는다.

WP-001~WP-005 are merged to main (WP-001 bootstrap, WP-002 PR #9, WP-003 PR #10, WP-004 PR #11, WP-005 PR #12). All five Issues are closed. Issue #13 tracks the final handoff status update. Gate 0 remains unapproved.

## 진행 원칙
각 마일스톤 전 요구사항·정책·검증 계약을 정의한다. 예상 수치를 실측처럼 쓰지 않으며 성능/복구 주장은 재현 가능한 실험 결과와 연결한다.
