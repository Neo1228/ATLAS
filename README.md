# ATLAS

한정판매 시스템의 backend 학습 프로젝트. 주문·재고·결제·환불·정산의 정합성과 장애 복구를 다룹니다.

updated: 2026-11-02
장기 범위: 결제 신뢰성, 고동시성 판매, 정산·대사.

- 프로젝트 방향: [`docs/00-governance/project-charter.md`](docs/00-governance/project-charter.md)
- 문서 인덱스: [`docs/README.md`](docs/README.md)
- 현재 진행 및 미결정: [`docs/09-delivery/session-handoff.md`](docs/09-delivery/session-handoff.md)

- 기여·작업 단위: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 검사·PR 체크리스트: [workflow](.github/workflows/docs-lint.yml)이 pull request/push 때 실행
- Backend build·PostgreSQL tests: [workflow](.github/workflows/backend-ci.yml).

- 진행: Gate 0/1 완료, Gate 2 Buyer 기준 승인, Gate 3 architecture 기준 확정. [Issue #72](https://github.com/Neo1228/ATLAS/issues/72)의 local runtime 구현·검증 완료. Fake PSP only; PR merge, live payment, cloud deployment, production load는 별도 상태.

## Local runtime

- 필요: JDK 25, Docker Compose. CI는 Temurin 25; production JDK 배포판은 미정.
- Toolchain 선택: `export JAVA_HOME="<JDK 25 home>"`; `export PATH="$JAVA_HOME/bin:$PATH"`.
- PostgreSQL 시작/health 대기: `docker compose up -d --wait postgres`
- Colima에서 Testcontainers를 쓸 경우:

  ```sh
  export DOCKER_HOST="$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}')"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  ```

- 앱 실행: `./gradlew bootRun` — API는 `127.0.0.1:8080`, DB는 local Compose 기준.
- 검증: `./gradlew check` — PostgreSQL/Testcontainers 통합 테스트 포함.

### 기본 Buyer flow

1. Campaign 생성: `POST /api/campaigns`

   ```json
   {"skuId":"demo-sku","inventoryCapacity":1,"queueTicketTtlSeconds":300,"maxActiveCheckouts":1}
   ```

2. Sale open: `POST /api/campaigns/{campaignId}/open`
3. Queue 진입: `POST /api/campaigns/{campaignId}/tickets`
   - Header: `Idempotency-Key: demo-request-1`
   - Body: `{"skuId":"demo-sku","buyerRef":"local-buyer-1"}`
4. Checkout 시작: `POST /api/campaigns/{campaignId}/tickets/{ticketId}/checkout`
5. Fake payment 제출: `POST /api/orders/{orderId}/payment`
6. 상태 확인·복구: `GET /api/orders/{orderId}`, `POST /api/orders/{orderId}/reconcile`

실제 결제·Buyer 인증·Tenant 격리·배포·성능 보장은 구현 범위 밖. 상세 계약은 [Issue #72](https://github.com/Neo1228/ATLAS/issues/72), 미결정 사항은 [Q-003/Q-005/Q-007/Q-008/Q-009](docs/00-governance/assumptions-and-open-questions.md) 참조.
