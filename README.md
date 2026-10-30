# ATLAS

한정판매 상황의 주문·재고·결제·환불·정산 정합성과 장애 복구를 다루는 백엔드 학습 프로젝트. 여러 판매사의 데이터·권한 분리, 재고 경쟁, 결제 실패 복구가 주요 주제. 장기 범위: 결제 신뢰성(Payment Reliability), 고동시성 한정판매(Flash Commerce), 정산·대사(Settlement & Reconciliation).

- 프로젝트 방향: [`docs/00-governance/project-charter.md`](docs/00-governance/project-charter.md)
- 문서 인덱스: [`docs/README.md`](docs/README.md)
- 현재 진행 및 미결정: [`docs/09-delivery/session-handoff.md`](docs/09-delivery/session-handoff.md)
- 문서 추적 링크 정비: [Issue #38](https://github.com/Neo1228/ATLAS/issues/38)/[PR #39](https://github.com/Neo1228/ATLAS/pull/39) 검토 중. PayPal authorization/capture 선택 [Issue #37](https://github.com/Neo1228/ATLAS/issues/37)은 이 작업 병합 뒤 재개.

- 기여·작업 단위: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 린트/PR 체크리스트: [Documentation lint workflow](.github/workflows/docs-lint.yml)이 pull request/push 시 실행

✓ Gate 0 승인, Gate 1 문제·도메인 탐색 중. 애플리케이션 코드, 런타임 의존성, 실제 결제 호출, 배포는 미착수. Gate 2와 구현 진입은 별도 승인 필요. 실제 사용자·거래·성능 성과를 검증된 사실로 주장하지 않는다.
