# ATLAS 작업 진입 규칙

## 목적과 범위
- ATLAS는 멀티테넌트 한정판매에서 주문·재고·결제·환불·정산의 정합성과 복구를 검증하는 학습용 백엔드 플랫폼이다.
- 장기 범위 A Payment Reliability, B Flash Commerce, C Settlement & Reconciliation을 모두 유지한다. 현재 마일스톤은 별도 관리한다.
- 실제 사용자·거래·트래픽·성과가 없는 경우 사실처럼 표현하지 않는다. 합성 부하와 장애 주입은 실험으로 표시한다.

## 진행 원칙
- 기본 문서 언어는 한국어이며, 도메인/API/ADR 상태는 일관된 영어 명칭을 병기한다.
- 확정(CONFIRMED), 제안(PROPOSED), 가정(ASSUMPTION), 미결정(OPEN QUESTION), 측정 결과를 구별한다. 사용자 승인 전 Proposed를 Accepted로 바꾸지 않는다.
- 불명확한 정책을 사실로 채우지 말고 `docs/00-governance/assumptions-and-open-questions.md`에 기록한다.
- Gate 0 승인 전 Gate 1 확정, 아키텍처 확정, 소스 코드 작성, 의존성 설치, 외부 결제 호출, 배포를 하지 않는다.
- 코드를 바꾸기 전 관련 요구사항·정책·검증 계약을 확인한다. 중요한 설계 선택은 ADR로 남긴다.
- PayPal 외부 결제 상태와 ATLAS 주문 상태, 내부 정산 계산과 실제 지급을 혼동하지 않는다. 실결제·실개인정보·실판매자 지급은 초기 범위에서 제외한다.
- 모든 후속 작업은 추적 가능해야 한다: 작업 단위·요구사항·결정·변경 파일·검증 결과·커밋/PR 링크를 기록한다. 작은 논리적 변경을 커밋하고 관련 변경만 PR로 묶는다. PR 형식은 `.github/PULL_REQUEST_TEMPLATE.md`를 따른다. Gate 0의 모든 문서를 한 번에 업로드하지 말고, 검토 가능한 소단위 묶음을 순차적으로 준비한다.
- 계획된 변경을 시작하기 전에 해당 작업 묶음의 GitHub Issue를 만들고 요구사항/결정 ID, 범위, 인수·검증 기준을 연결한다. 관련 커밋/PR은 이 Issue에 링크하고, 완료 조건 충족 시에만 닫는다.

## 진입점
- 문서 인덱스: `docs/README.md`
- 프로젝트 범위: `docs/00-governance/project-charter.md`
- 진행 상태: `docs/09-delivery/roadmap.md`, `milestone-log.md`, `session-handoff.md`
- 상세 운영 원칙은 시작 프롬프트 v1.3을 따른다. 승인·범위 변경은 사용자와 먼저 협의한다.
