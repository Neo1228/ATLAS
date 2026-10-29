---
id: DOC-012
title: Milestone Log
status: Current
owner: Project Owner / Agent
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---

# 마일스톤 로그

## 2026-10-29 — Gate 0 bootstrap 시작
- 상태: Gate 0 초안. 사용자는 추가 보완을 요청했고 종료 승인은 하지 않음.
- 변경: `AGENTS.md`; `docs/README.md`; 거버넌스 헌장/결정 로그/미결정/레퍼런스; 요구사항 인덱스; 용어집; ADR-0001 및 템플릿; 실험 레지스트리/템플릿; 로드맵; 세션 인계.
- 제안: Modular Monolith First (ADR-0001, Proposed).
- 결정: 가상 학습 시나리오로 문제 발견을 시작하며 실제 문제/사용자 검증으로 주장하지 않음 (D-005, Q-001).
- 결정: 단일 판매 유형 대신 여러 유형을 모두 탐색하고 싶다는 선호를 기록. 구체 유형·순서·구현 범위는 미정 (D-006, Q-002/Q-010).
- 결정: 가상 시나리오는 구매자와 판매자부터 모델링 (D-007); 구체 페르소나/목표는 Q-010 OPEN.
- 결정: 작업 단위 추적, 작은 커밋, 관련 작업의 PR 묶음 및 결과 기록을 모든 후속 작업에 적용 (D-008).
- 미결정: Q-002~Q-010. 다음은 구매자/판매자 목표(Q-010), 이후 판매 유형/재고 모델(Q-002).
- 위험: 현재 구체 사용자 조사·거래·성능 측정·PayPal API 검증 근거 없음. 다수 판매 유형의 전체 구현은 확정되지 않음.
- 다음: Gate 0 초안 사용자 검토 및 Q-010/Q-002 협의. 사용자의 Gate 0 승인 전 Gate 1/코드 착수 금지.
- PR 준비: `.github/PULL_REQUEST_TEMPLATE.md` 생성. 초기 문서 전체를 한 번에 올리지 않고 작은 논리 묶음을 순차 처리하기로 합의 (D-009).
- 후보 묶음: 거버넌스/진입점 → 발견·요구사항·용어 → ADR·실험 → 로드맵·인계. PR은 각 묶음 검토 후 다음 단계 진행.
- Private GitHub 저장소 `Neo1228/ATLAS`: `https://github.com/Neo1228/ATLAS`, Issues 활성화.
- WP-001 bootstrap: commit `7ed5fac`; workflow corrections `eb371ca`, `5151a15`.
- WP-002 governance: PR #9 / Issue #5, merge commit `6049755`.
- WP-003 discovery/requirements: PR #10 / Issue #6, merge commit `f73a7e9`.
- WP-004 ADR/experiments: PR #11 / Issue #8, merge commit `6b2b379`; ADR-0001 Proposed and EXP-001~009 Planned.
- WP-005 delivery records: PR #12 / Issue #7, merge commit `8c71869`.
- Git conventions/batching: PR #4 / Issue #2, merge commit `a76016e`; PR #16 / Issue #15, merge commit `80dd964`.
- PR #1 and #3 were closed unmerged by GitHub during approved history/branch corrections; their final content is in merged PR #4.
- All documentation packets are published on main. Main protection and latest CI remain enabled/passing; local Markdown lint passes.
- Issue #17 / PR #17 tracks this final delivery-record consistency update. Gate 0 remains Draft and unapproved.
- Gate 0 remains Draft and unapproved; no Gate 1/code work is authorized. Main protection remains enabled and force-push disabled.
