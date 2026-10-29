# ATLAS 작업 진입 규칙

## 목적과 범위
- 목적: 주문·재고·결제·환불·정산의 정합성 및 장애 복구 검증.
- 장기 범위: A 결제 신뢰성, B 고동시성 한정판매, C 정산·대사.
- 사실과 합성 실험 구분. 사용자·거래·운영 성과를 근거 없이 주장하지 않음.

## 진행 원칙
- 짧고 스캔하기 쉬운 문장. 자연스러운 경우 명사형/개조식 종결, 조사·수식어 최소화. 수치/단계는 표·불릿.
- 상태 구분: `✓ Confirmed`, `△ Proposed`, `? OPEN`, `ASSUMPTION`, `Measured`. 사용자 승인 전 제안을 확정으로 변경하지 않음.
- 미결정 정책은 [Assumptions & Open Questions](docs/00-governance/assumptions-and-open-questions.md)에 기록.
- Gate 0 승인 완료, Gate 1 진행 중. Gate 2·아키텍처·구현·의존성·결제 호출·배포는 별도 승인 전 보류.
- 코드 변경 전 요구사항·정책·검증 계약 확인. 중요한 설계 선택은 ADR로 기록.
- 복잡한 Issue/PR 본문에 배경·사실/가정·결정 이유·대안·영향·검증 근거 명시. 문서 링크만으로 설명 대체 금지.
- 흐름/상태가 복잡한 경우 Mermaid 등으로 시각화. 중복 그림 금지, 미정 경계 표시. 단순 변경에 도식 강제 금지.
- PR 템플릿 모든 체크박스 완료. 병합 전 `PR checklist`와 `Markdown lint` 필수 검사 통과.
- 외부 PSP 상태와 주문 상태, 내부 정산과 실제 지급 구분. 초기 범위에서 실결제·개인정보·판매사 지급 제외.
- 모든 작업에 Issue·요구사항/결정 ID·변경 파일·검증·commit/PR 기록 연결.
- 한 목적·완료 기준의 여러 Issue/commit은 하나의 PR로 통합. 커밋마다/이슈마다 PR 생성 금지.

## 진입점
- 문서 인덱스: [docs/README.md](docs/README.md)
- 프로젝트 범위: [Project Charter](docs/00-governance/project-charter.md)
- 운영 기준: [문서 인덱스](docs/README.md), [Change Control](docs/00-governance/change-control.md), [CONTRIBUTING](CONTRIBUTING.md). 승인·범위 변경은 사용자와 먼저 협의.
