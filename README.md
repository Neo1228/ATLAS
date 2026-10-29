# ATLAS

ATLAS는 여러 판매사가 데이터와 권한을 분리해 사용하는 한정판매 플랫폼을 설계·실험하는 학습 프로젝트입니다. 재고가 제한된 상품에 요청이 몰릴 때 주문·재고·결제·환불·정산이 일치하는지, 장애 뒤 복구할 수 있는지를 검증합니다. 장기 범위는 결제 신뢰성(Payment Reliability), 동시성 높은 한정판매(Flash Commerce), 정산·대사(Settlement & Reconciliation)입니다. Gate 0 승인을 마치고 Gate 1 discovery를 진행 중입니다.

- 프로젝트 방향: [`docs/00-governance/project-charter.md`](docs/00-governance/project-charter.md)
- 문서 인덱스: [`docs/README.md`](docs/README.md)
- 현재 진행 및 미결정: [`docs/09-delivery/session-handoff.md`](docs/09-delivery/session-handoff.md)

- 기여·작업 단위: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 린트/PR 체크리스트: [Documentation lint workflow](.github/workflows/docs-lint.yml)이 pull request/push 시 실행

Gate 0 승인은 애플리케이션 코드, 런타임 의존성, 외부 결제 호출, 배포를 허가하지 않습니다. 각 후속 Gate의 별도 승인 전에는 해당 단계 작업을 시작하지 않습니다. 실사용자/거래/성능 성과가 검증된 것처럼 표현하지 않습니다.
