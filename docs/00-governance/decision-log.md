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
| D-001 | Proposed | Modular Monolith First를 초기 아키텍처 가설로 검증한다. | `docs/06-decisions/adr/ADR-0001-architecture-style.md`; 사용자 승인 대기 |
| D-002 | Confirmed | A/B/C 전체 도메인은 장기 범위이며 마일스톤을 나누어 진행한다. | 시작 프롬프트 v1.3 §0 |
| D-003 | Confirmed | PayPal Sandbox 우선, 장애 재현용 Fake PSP는 별도 경계로 둔다. | 시작 프롬프트 v1.3 §1–2 |
| D-004 | Confirmed | 초기 실험에서 실결제·실개인정보·실판매사 지급을 제외한다. | 시작 프롬프트 v1.3 §2.1 |
| D-005 | Confirmed | 문제 발견은 가상 학습 시나리오로 시작하며, 실제 사용자/거래/인터뷰 검증 사실처럼 주장하지 않는다. 구체 역할·목표는 계속 미결정. | 사용자 선택; Q-001 해결, Q-010 OPEN |
| D-006 | Confirmed | 단일 판매 유형에 고정하지 않고 여러 판매 유형을 모두 탐색해보고 싶다는 사용자 선호. 구체 유형·순서·구현 범위는 미정이며, 탐색 의사를 전체 구현 약속으로 간주하지 않는다. | 사용자 선택; Q-002, Q-010 |
| D-007 | Confirmed | 가상 시나리오는 구매자와 판매자부터 모델링한다. | 사용자 선택; Q-010 |
| D-008 | Confirmed | 후속 작업을 작은 작업 단위·커밋으로 추적하고 관련 커밋을 PR로 묶어 기록한다. | 사용자 지시; `change-control.md` |
| D-009 | Confirmed | PR 템플릿/추적 규칙을 먼저 정하고, 현 Gate 0 문서 전체를 한 번에 올리지 않는다. 이후 검토 가능한 소단위 묶음을 순차적으로 PR 처리한다. | 사용자 지시; `.github/PULL_REQUEST_TEMPLATE.md`, `change-control.md` |
| D-010 | Confirmed | 첫 가상 학습 시나리오는 재고/예약 경합과 공정한 구매 기회로 둔다. 실제 사용자 문제/검증된 요구사항이 아니며 정책·인수 기준은 미정. | 사용자 선택; Q-010, Q-002 |
| D-011 | Confirmed | 계획된 작업은 Issue 선행으로 범위/수락 기준을 기록하고, 관련 브랜치·커밋·PR을 Issue에 연결한다. Issue는 작업 묶음 기준이며 커밋마다 만들지 않는다. | 사용자 지시; Issue #2, `CONTRIBUTING.md` |
| D-012 | Confirmed | 사용자가 기존 bootstrap commit 제목의 규칙 정정을 승인했다. 공개 이력 재작성은 기본 금지하며, 명시 승인·백업 ref·force-with-lease·branch protection 복원을 전제로 예외 처리한다. | 사용자 선택; Issue #2 |

Gate 0 종료 및 Gate 1 진입은 아직 승인되지 않았다.
