# ATLAS

멀티테넌트 한정판매에서 주문·재고·결제·환불·정산의 정합성과 장애 복구를 검증하는 백엔드 커머스 플랫폼의 설계·실험 프로젝트입니다. 장기 범위는 Payment Reliability, Flash Commerce, Settlement & Reconciliation을 모두 포함하며, Gate 0 승인을 마치고 Gate 1 discovery를 진행 중입니다.

- 프로젝트 방향: [`docs/00-governance/project-charter.md`](docs/00-governance/project-charter.md)
- 문서 인덱스: [`docs/README.md`](docs/README.md)
- 현재 진행 및 미결정: [`docs/09-delivery/session-handoff.md`](docs/09-delivery/session-handoff.md)

- 기여·작업 단위: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 린트: [Markdown lint workflow](.github/workflows/docs-lint.yml)이 pull request/push 시 실행

Gate 0 승인은 애플리케이션 코드, 런타임 의존성, 외부 결제 호출, 배포를 허가하지 않습니다. 각 후속 Gate의 별도 승인 전에는 해당 단계 작업을 시작하지 않습니다. 실사용자/거래/성능 성과가 검증된 것처럼 표현하지 않습니다.
