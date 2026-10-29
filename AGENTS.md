# ATLAS 작업 진입 규칙

## 목적과 범위
- ATLAS는 멀티테넌트 한정판매에서 주문·재고·결제·환불·정산의 정합성과 복구를 검증하는 학습용 백엔드 플랫폼이다.
- 장기 범위 A Payment Reliability, B Flash Commerce, C Settlement & Reconciliation을 모두 유지한다. 현재 마일스톤은 별도 관리한다.
- 실제 사용자·거래·트래픽·성과가 없는 경우 사실처럼 표현하지 않는다. 합성 부하와 장애 주입은 실험으로 표시한다.

## 진행 원칙
- 자연스러운 한국어를 기본으로 한다. API, PR, commit, retry처럼 업계에서 널리 쓰이고 뜻이 분명한 영문 용어는 그대로 사용한다. 프로젝트 전용 약어·도메인 용어는 첫 등장 때 짧게 풀이하거나 [Glossary](docs/01-discovery/glossary.md)로 연결한다. 모든 용어를 억지로 한영 병기하지 않는다.
- 확정(CONFIRMED), 제안(PROPOSED), 가정(ASSUMPTION), 미결정(OPEN QUESTION), 측정 결과를 구별한다. 사용자 승인 전 Proposed를 Accepted로 바꾸지 않는다.
- 미결정 정책은 [Assumptions & Open Questions](docs/00-governance/assumptions-and-open-questions.md)에 기록한다.
- Gate 0은 승인됐다. Gate 1 discovery를 진행하며, Gate 2·아키텍처/구현·의존성·외부 결제 호출·배포는 해당 단계의 별도 승인을 받기 전 시작하지 않는다.
- 코드를 바꾸기 전 관련 요구사항·정책·검증 계약을 확인한다. 중요한 설계 선택은 ADR로 남긴다.
- 복잡한 도메인/정책/로직/아키텍처 Issue와 PR 본문에는 배경, 사실/가정, 결정 이유, 대안/트레이드오프, 영향 받는 상태·불변조건, 검증 근거를 충분히 쓴다. 링크는 보충 자료이며 설명을 대체하지 않는다.
- 구조·상태·흐름이 복잡하면 Mermaid 등으로 시각화한다. 이미 기준 문서에 같은 도식이 있으면 PR에는 중복 삽입하지 말고 핵심을 설명해 직접 링크한다. 단순 변경에 도식을 강제하지 않는다.
- PR template의 모든 체크박스는 완료해야 한다. 해당 없음은 이유를 쓴다. 병합 전 `PR checklist`와 `Markdown lint` required checks가 성공해야 한다.
- PayPal 외부 결제 상태와 ATLAS 주문 상태, 내부 정산 계산과 실제 지급을 혼동하지 않는다. 실결제·실개인정보·실판매자 지급은 초기 범위에서 제외한다.
- 모든 후속 작업은 추적 가능해야 한다: 작업 단위·요구사항·결정·변경 파일·검증 결과·커밋/PR 링크를 기록한다. 작은 논리적 변경을 커밋하고 관련 변경만 PR로 묶는다. PR 형식은 [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md)을 따른다.
- 계획된 변경을 시작하기 전에 해당 작업 묶음의 GitHub Issue를 만들고 요구사항/결정 ID, 범위, 인수·검증 기준을 연결한다. 관련 커밋/PR은 이 Issue에 링크하고, 완료 조건 충족 시에만 닫는다.
- 커밋 하나하나에 PR을 만들지 않는다. 같은 목적/수락 기준을 공유하는 관련 커밋과 Issue는 하나의 리뷰 가능한 PR로 묶고, 별도 위험/릴리스 경계는 분리한다.

## 진입점
- 문서 인덱스: [docs/README.md](docs/README.md)
- 프로젝트 범위: [Project Charter](docs/00-governance/project-charter.md)
- 진행 상태: [Roadmap](docs/09-delivery/roadmap.md), [Milestone Log](docs/09-delivery/milestone-log.md), [Session Handoff](docs/09-delivery/session-handoff.md)
- 운영 기준은 [문서 인덱스](docs/README.md), [Change Control](docs/00-governance/change-control.md), [CONTRIBUTING](CONTRIBUTING.md)에 정리한다. 승인이나 범위 변경은 사용자와 먼저 협의한다.
