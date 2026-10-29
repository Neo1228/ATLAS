---
id: DOC-012
title: Milestone Log
status: Current
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---

# 마일스톤 로그
이 문서는 변경 이력이다. 현재 상태와 다음 단계는 [Roadmap](roadmap.md) 및 [Session Handoff](session-handoff.md)을 참조한다.

## 2026-10-29 — Gate 0 문서·작업 기반 마련
- 당시 상태: Gate 0 문서/거버넌스 검토 승인 완료, Gate 1 discovery 시작. 이후 구현·기술 선택은 별도 승인 대상.
- 변경: [AGENTS](../../AGENTS.md); [문서 인덱스](../README.md); 거버넌스 [헌장](../00-governance/project-charter.md)/[결정 로그](../00-governance/decision-log.md)/[미결정 목록](../00-governance/assumptions-and-open-questions.md)/[레퍼런스](../00-governance/reference-register.md); [요구사항 인덱스](../02-requirements/requirements-index.md); [용어집](../01-discovery/glossary.md); [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md) 및 템플릿; [실험 레지스트리](../08-experiments/experiment-registry.md)/템플릿; [로드맵](roadmap.md); [세션 인계](session-handoff.md).
- 제안: Modular Monolith First는 검증 대상 아키텍처 가설이며 확정이 아니다 ([ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md)).
- 결정: 가상 학습 시나리오로 문제 발견을 시작하며 실제 문제/사용자 검증으로 주장하지 않음 (D-005, Q-001).
- 결정: 여러 판매 유형을 탐색하되 전체 유형 구현을 약속하지 않는다 (D-006, Q-002/Q-010).
- 결정: 가상 시나리오는 구매자와 판매자부터 모델링한다 (D-007); 당시 세부 목표는 Q-010에서 논의할 예정이었다.
- 결정: 작은 논리 커밋을 남기고 관련 변경을 한 PR에 묶어 추적한다 (D-008).
- 당시 다음 단계: 구매자/판매자 목표를 Issue #19/Q-010에서 논의. Gate 2와 코드는 별도 승인 전 보류.
- PR 준비: [Pull Request Template](../../.github/PULL_REQUEST_TEMPLATE.md) 생성. 초기 문서 전체를 한 번에 올리지 않고 작은 논리 묶음을 순차 처리하기로 합의 ([D-009](../00-governance/decision-log.md)).
- 후보 묶음: 거버넌스/진입점 → 발견·요구사항·용어 → ADR·실험 → 로드맵·인계. PR은 각 묶음 검토 후 다음 단계 진행.
- 비공개 GitHub 저장소 [Neo1228/ATLAS](https://github.com/Neo1228/ATLAS)를 만들고 Issues를 활성화했다.
- WP-001 (초기 저장소 기반): 첫 commit `7ed5fac`; workflow 수정 `eb371ca`, `5151a15`.
- WP-002 (거버넌스): [PR #9](https://github.com/Neo1228/ATLAS/pull/9)/Issue #5, 병합 commit `6049755`.
- WP-003 (발견/요구사항): [PR #10](https://github.com/Neo1228/ATLAS/pull/10)/Issue #6, 병합 commit `f73a7e9`.
- WP-004 (ADR/실험): [PR #11](https://github.com/Neo1228/ATLAS/pull/11)/Issue #8, 병합 commit `6b2b379`; ADR-0001 Proposed, EXP-001~009 Planned.
- WP-005 (진행 기록): [PR #12](https://github.com/Neo1228/ATLAS/pull/12)/Issue #7, 병합 commit `8c71869`.
- Git 규칙/PR 묶음 기준: [PR #4](https://github.com/Neo1228/ATLAS/pull/4)/Issue #2, `a76016e`; [PR #16](https://github.com/Neo1228/ATLAS/pull/16)/Issue #15, `80dd964`.
- [PR #1](https://github.com/Neo1228/ATLAS/pull/1)과 [PR #3](https://github.com/Neo1228/ATLAS/pull/3)은 승인된 이력/브랜치 수정 과정에서 병합되지 않고 닫혔다. 최종 내용은 병합된 PR #4에 있다.
- Gate 0 문서 묶음은 main에 게시됐다. 당시 main 보호와 CI가 활성화돼 있었고 lint가 통과했다. 현재 필수 검사는 [CONTRIBUTING](../../CONTRIBUTING.md)에 따른다.
- 최종 진행 기록: [PR #18](https://github.com/Neo1228/ATLAS/pull/18)/Issue #17, merge commit `4b5ca7b`; roadmap, milestone log, session handoff를 동기화.
- Gate 0은 2026-10-29 사용자 승인으로 종료됐다 (D-014). Gate 1은 [Issue #19](https://github.com/Neo1228/ATLAS/issues/19)에서 가상 페르소나 논의를 시작했다. 이는 당시 기록이며, Gate 2/3/구현은 승인되지 않았다.
- D-015: 공통 구매자/판매자 목표를 사용하고 판매 유형별 차이를 추가한다 ([Issue #19](https://github.com/Neo1228/ATLAS/issues/19)); 실제 사용자 연구 결과는 아니다.
- D-016: 가상 구매자 목표는 공정한 구매 기회와 정확한 재고/예약 상태다 ([Issue #19](https://github.com/Neo1228/ATLAS/issues/19)); 실제 시장 수요로 검증되지 않았다.
- D-017: 가상 판매자 목표는 초과판매 없이 한정 판매를 운영하는 것이다 ([Issue #19](https://github.com/Neo1228/ATLAS/issues/19)); 실제 판매자 조사 결과는 아니다.
- D-018 Proposed: Gate 2 부하 프로필 → Gate 3 용량 추정 → Gate 4 합성 검증. RPS·동시성·지연시간·SLO/RTO/RPO 값은 합의/측정 전 TBD ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20), Q-008).
- D-019: 서버가 발급한 대기열 순번을 가상 공정성 기준으로 선택했다. 당시 발급 시점·유형별 정책은 미정 ([Issue #22](https://github.com/Neo1228/ATLAS/issues/22), Q-003).
- D-020~D-022 ([Issue #24](https://github.com/Neo1228/ATLAS/issues/24)): 자격 통과 후 입장 시 순번 발급, 같은 요청 재시도는 기존 ticket 재사용, 서비스 재시작 뒤 순서 보존. 저장 방식, 동순위, 만료, bot 방어, 유형별 차이는 미정.
- [PR #23](https://github.com/Neo1228/ATLAS/pull/23)/[Issue #22](https://github.com/Neo1228/ATLAS/issues/22)은 대기 순서 기준을 기록하고 병합됐다 (`eb95982`); Issue #22 closed.
- [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)는 순번 발급/재시도/재시작 기준을 기록해 닫혔다. Q-003의 다른 세부사항은 계속 OPEN이다.

## 2026-10-29 — 문서 연결·작성 기준·PR 검사 (진행 중)
- 범위: 문서 인덱스/상호 링크, 독자용 용어 설명과 현재 상태 정합화, 복잡한 변경의 Issue/PR 설명 기준, Mermaid 활용 기준, PR checklist required check.
- 추적: [Issue #26](https://github.com/Neo1228/ATLAS/issues/26), [PR #27](https://github.com/Neo1228/ATLAS/pull/27). PR은 아직 열려 있어 커밋/검증 기록은 병합 전 상태다.
- 로컬 검증(현재 변경분): Markdown lint 24개 파일 통과; 23개 Markdown 파일의 211개 상대 링크 resolve; YAML 양식/워크플로 4개 파일 파싱 성공. GitHub workflow check와 branch protection 갱신은 아직 확인 전.
