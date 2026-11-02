# ATLAS 작업 진입 규칙

## 목적과 범위
- 목적: 주문·재고·결제·환불·정산의 정합성 및 장애 복구 검증.
- 장기 범위: A 결제 신뢰성, B 고동시성 한정판매, C 정산·대사.
- 사실과 합성 실험 구분. 사용자·거래·운영 성과를 근거 없이 주장하지 않음.

## 진행 원칙
- 짧고 스캔하기 쉬운 문장. 자연스러운 경우 명사형/개조식 종결, 조사·수식어 최소화. 수치/단계는 표·불릿.
- 상태 구분: `✓ Confirmed`, `△ Proposed`, `? OPEN`, `ASSUMPTION`, `Measured`. 사용자 승인 전 제안을 확정으로 변경하지 않음.
- 미결정 정책은 [Assumptions & Open Questions](docs/00-governance/assumptions-and-open-questions.md)에 기록.
- 진행 상태: Gate 0/1 완료, Gate 2 Buyer 기준 승인, Gate 3 architecture/stack 기준 확정. Issue #72의 local current-flow runtime 승인; live provider, deployment, production capacity/SLO는 별도 승인·검증 대상.
- 코드 변경 전 요구사항·정책·검증 계약 확인. 중요한 설계 선택은 ADR로 기록.
- 문서·Issue·PR은 프로젝트 맥락을 모르는 독자를 기준으로 작성. 내부 shorthand/ID만 나열하지 말고, 복잡한 변경은 배경·사실/가정·선택 이유·대안·영향·검증을 본문에 설명하고 기준 문서에 연결.
- 흐름/상태가 복잡한 경우 Mermaid 등으로 시각화. 중복 그림 금지, 미정 경계 표시. 단순 변경에 도식 강제 금지.
- PR 템플릿 모든 체크박스 완료. 병합 전 `Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks` 통과.
- 외부 PSP 상태와 주문 상태, 내부 정산과 실제 지급 구분. 초기 범위에서 실결제·개인정보·판매사 지급 제외.
- 모든 작업은 Issue, trace ID, 변경 파일, 검증, commit/PR로 추적. 같은 목적의 후속 선택은 기존 Issue에서 이어가기.
- 독립된 목적·완료 기준·위험 경계만 분리. 병합 PR은 plain-text Closes #N, 일부 반영은 Refs #N; 같은 줄에 Issue link 추가.
- Markdown link 안에 close keyword를 넣지 않음. 필수 checks 통과 후 실제 merge SHA와 Issue 상태 기록.

## 진입점
- 문서 인덱스: [docs/README.md](docs/README.md)
- 프로젝트 범위: [Project Charter](docs/00-governance/project-charter.md)
- 운영 기준: [문서 인덱스](docs/README.md), [Change Control](docs/00-governance/change-control.md), [CONTRIBUTING](CONTRIBUTING.md). 승인·범위 변경은 사용자와 먼저 협의.
