---
id: DOC-009
title: Session Handoff
status: Current
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 세션 인계

## 현재 상태
- 저장소는 비공개 [Neo1228/ATLAS](https://github.com/Neo1228/ATLAS)다. Gate 0 문서 검토는 2026-10-29 승인됐고 Gate 1 discovery(문제·도메인 탐색)는 진행 중이다.
- 대기열 순번 발급, 같은 요청 재시도, 서비스 재시작 기준은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록됐다. 저장 방식, 만료, 동순위, bot 방어, 판매 유형별 차이는 Q-003에서 미정이다.
- 애플리케이션 코드, 런타임 의존성, 실제 결제 호출, 배포는 시작하지 않았다. 코드는 후속 단계와 구현 진입을 별도 승인받은 뒤 시작한다.

## 승인된 범위와 기준
- 장기 범위는 결제 신뢰성, 동시성 높은 한정판매, 정산·대사다 ([Project Charter](../00-governance/project-charter.md)).
- 초기 결제 시험은 PayPal Sandbox와 별도 Fake PSP로 제한한다. 실제 결제·고객 개인정보·판매사 지급은 제외한다.
- [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md)의 Modular Monolith First는 제안된 가설이며 승인된 아키텍처가 아니다.
- 구매자는 공정한 구매 기회와 정확한 재고/예약 상태, 판매자는 초과판매 방지를 목표로 한다. 이는 가상 목표이며 시장 검증 결과가 아니다 ([Personas & Jobs](../01-discovery/personas-and-jobs.md)).
- 여러 판매 유형을 탐색하고 싶다는 선호는 전체 유형의 구현 약속이 아니다 (D-006).
대기열 기준은 자격 확인 뒤 입장할 때 순번을 발급하고, 같은 논리 요청의 retry와 서비스 재시작 뒤에도 순서를 보존하는 것이다 (D-019~D-022). 저장·복구 방식과 나머지 Q-003 경계는 미정이며 [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md)에 기록한다.

[Issue #20](https://github.com/Neo1228/ATLAS/issues/20)은 성능·용량 계획을 추적한다: Gate 2에서 부하 프로필 합의, Gate 3에서 용량 추정, 구현 승인 뒤 Gate 4에서 부하/복구 검증. 목표 수치와 실측 결과는 없다. 자세한 내용은 [Roadmap](roadmap.md), 실험 후보는 [Experiment Registry](../08-experiments/experiment-registry.md)에 있다.

## 다음 논의
Q-003의 남은 세부 경계를 한 작업 단위로 묶을지 사용자와 합의한다. 각 경계마다 Issue를 만들지 않는다. Gate 2와 코드 진입은 별도 승인을 받기 전 시작하지 않는다.

## 추적과 진행 기록
- 현재 단계와 제안된 다음 단계는 [Roadmap](roadmap.md), 완료한 Issue/PR·커밋·검증 이력은 [Milestone Log](milestone-log.md)에 기록한다.
- 문서 탐색, 처음 접하는 독자용 설명, 복잡한 변경 기록, PR checklist 자동 확인은 [Issue #26](https://github.com/Neo1228/ATLAS/issues/26) / [PR #27](https://github.com/Neo1228/ATLAS/pull/27) 한 작업 묶음으로 진행 중이다.
- 새 작업은 먼저 Issue에 범위·완료 기준을 기록한다. 관련 커밋 여러 개와 이슈를 하나의 리뷰 가능한 PR로 묶는다 ([CONTRIBUTING](../../CONTRIBUTING.md)).
