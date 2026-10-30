# ATLAS

한정판매 시스템의 backend 학습 프로젝트. 주문·재고·결제·환불·정산의 정합성과 장애 복구를 다룹니다.

핵심 주제: 판매사별 데이터·권한 분리, 재고 경합, 결제 실패 복구.
장기 범위: 결제 신뢰성, 고동시성 판매, 정산·대사.

- 프로젝트 방향: [`docs/00-governance/project-charter.md`](docs/00-governance/project-charter.md)
- 문서 인덱스: [`docs/README.md`](docs/README.md)
- 현재 진행 및 미결정: [`docs/09-delivery/session-handoff.md`](docs/09-delivery/session-handoff.md)

- 기여·작업 단위: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 검사·PR 체크리스트: [workflow](.github/workflows/docs-lint.yml)이 pull request/push 때 실행

현재 Gate 0 승인, Gate 1 진행 중. Gate 2와 구현 진입은 별도 승인 대상.
애플리케이션 코드, 런타임 의존성, 실제 결제 호출, 배포는 미착수. 실제 사용자·거래·성능 성과를 검증한 사실로 주장하지 않음.
