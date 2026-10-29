---
id: DOC-003
title: Decision Log
status: Current
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 결정 로그

사용자 승인 없는 제안은 확정 결정이 아니다. 변경 시 새 ADR/항목을 연결하고 기존 기록을 소급 수정하지 않는다.

| ID | 상태 | 요약 | 근거/추적 |
|---|---|---|---|
| D-001 | Proposed | Modular Monolith First를 초기 아키텍처 가설로 검증한다. | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md); 사용자 승인 대기 |
| D-002 | Confirmed | A/B/C 세 영역을 장기 범위로 유지하고 단계별로 진행한다. | [Project Charter](project-charter.md), [Roadmap](../09-delivery/roadmap.md) |
| D-003 | Confirmed | 우선 연동 결제 제공자(PSP)는 PayPal Sandbox이며, 장애 재현은 별도 Fake PSP로 다룬다. | [Project Charter](project-charter.md), [Glossary](../01-discovery/glossary.md) |
| D-004 | Confirmed | 초기 실험에는 실제 결제, 실제 고객 개인정보, 실제 판매사 지급을 포함하지 않는다. | [Project Charter](project-charter.md) |
| D-005 | Confirmed | 문제 탐색은 가상 학습 시나리오로 시작하며, 실제 사용자·거래·인터뷰에서 확인된 요구로 주장하지 않는다. 구매자/판매자 목표는 D-015~D-017에서 정했고, 유형별 행동은 Q-010에 남아 있다. | Q-001 해결; Q-010 OPEN |
| D-006 | Confirmed | 단일 판매 유형에 고정하지 않고 여러 판매 유형을 모두 탐색해보고 싶다는 사용자 선호. 구체 유형·순서·구현 범위는 미정이며, 탐색 의사를 전체 구현 약속으로 간주하지 않는다. | 사용자 선택; Q-002, Q-010 |
| D-007 | Confirmed | 가상 시나리오는 구매자와 판매자부터 모델링한다. | 사용자 선택; Q-010 |
| D-008 | Confirmed | 후속 작업을 작은 작업 단위·커밋으로 추적하고 관련 커밋을 PR로 묶어 기록한다. | 사용자 지시; [Change Control](change-control.md) |
| D-009 | Confirmed | PR 템플릿/추적 규칙을 먼저 정하고, 현 Gate 0 문서 전체를 한 번에 올리지 않는다. 이후 검토 가능한 소단위 묶음을 순차적으로 PR 처리한다. | 사용자 지시; [Pull Request Template](../../.github/PULL_REQUEST_TEMPLATE.md), [Change Control](change-control.md) |
| D-010 | Confirmed | 첫 가상 학습 시나리오는 재고/예약 경합과 공정한 구매 기회로 둔다. 실제 사용자 문제/검증된 요구사항이 아니며 정책·인수 기준은 미정. | 사용자 선택; Q-010, Q-002 |
| D-011 | Confirmed | 계획된 작업은 Issue 선행으로 범위/수락 기준을 기록하고, 관련 브랜치·커밋·PR을 Issue에 연결한다. Issue는 작업 묶음 기준이며 커밋마다 만들지 않는다. | 사용자 지시; [Issue #2](https://github.com/Neo1228/ATLAS/issues/2), [CONTRIBUTING](../../CONTRIBUTING.md) |
| D-012 | Confirmed | 사용자가 기존 bootstrap commit 제목의 규칙 정정을 승인했다. 공개 이력 재작성은 기본 금지하며, 명시 승인·백업 ref·force-with-lease·branch protection 복원을 전제로 예외 처리한다. | 사용자 선택; Issue #2 |
| D-013 | Confirmed | 관련된 소단위 커밋과 Issue들을 목적/수락 경계가 일관된 PR 하나로 묶는다. 커밋/Issue마다 별도 PR을 만들지 않으며 독립 목적은 분리한다. | 사용자 지시; [Issue #15](https://github.com/Neo1228/ATLAS/issues/15), [CONTRIBUTING](../../CONTRIBUTING.md) |
| D-014 | Confirmed | Gate 0 문서/거버넌스 검토를 승인하고 Gate 1 discovery를 시작한다. Gate 2·설계·코드는 별도 승인 전 보류. | 사용자 승인; 2026-10-29; [Issue #19](https://github.com/Neo1228/ATLAS/issues/19) |
| D-015 | Confirmed | 여러 판매 유형은 구매자/판매자 공통 핵심 페르소나로 시작하고 유형별 차이를 확장한다. 구체 목표/행동은 discovery에서 추가 확정한다. | 사용자 선택; [Issue #19](https://github.com/Neo1228/ATLAS/issues/19) |
| D-016 | Confirmed | 가상 구매자의 우선 목표는 한정 판매에서 공정한 구매 기회를 얻고 재고/예약 상태를 정확히 아는 것으로 둔다. 실제 시장 검증 요구는 아니다. | 사용자 선택; [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), Q-010 |
| D-017 | Confirmed | 가상 판매자는 여러 유형의 한정 판매를 공정하게 운영하고 초과판매를 막는 것을 우선 목표로 둔다. 실제 판매자 조사 결과가 아니다. | 사용자 선택; [Issue #19](https://github.com/Neo1228/ATLAS/issues/19), Q-010 |
| D-018 | Proposed | 성능/용량 작업은 Gate 2에서 합성 부하 프로필을 합의하고, Gate 3에서 용량을 추정한 뒤, Gate 4에서 실험으로 검증하는 순서로 제안한다. 사용자 승인은 아직 없다. 숫자는 합의/측정 전 TBD다. | [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Q-008](assumptions-and-open-questions.md) |
| D-019 | Confirmed | 가상 기준에서는 서버가 발급한 순번이 작은 요청을 먼저 처리한다. 발급 시점과 유형별 정책은 미정이며, 실제 운영 공정성은 검증되지 않았다. | [Issue #22](https://github.com/Neo1228/ATLAS/issues/22), [Q-003](assumptions-and-open-questions.md) |
| D-020 | Confirmed | 자격 확인을 통과한 요청이 대기열에 입장할 때 서버가 순번을 발급한다. | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Q-003](assumptions-and-open-questions.md) |
| D-021 | Confirmed | 같은 논리 요청을 재시도하거나 중복 전달받으면 기존 대기표와 순번을 재사용한다. idempotency key 범위/보존 기간은 미정이다. | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Q-003](assumptions-and-open-questions.md) |
| D-022 | Confirmed | 서비스/작업 처리기(worker) 재시작 뒤 이미 발급된 대기표와 순번을 보존·복구한다. 저장·복구 방식은 미정이다. | [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Q-003](assumptions-and-open-questions.md) |

Gate 0 종료 승인은 기록됨 (D-014). Gate 1 discovery가 진행 중이며, Gate 2/3 및 구현 진입 승인은 받지 않았다.
