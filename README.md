# ATLAS

한정판매 시스템의 backend 학습 프로젝트. 주문·재고·결제·환불·정산의 정합성과 장애 복구를 다룹니다.

updated: 2026-11-03
장기 범위: 결제 신뢰성, 고동시성 판매, 정산·대사.

- 프로젝트 방향: [`docs/00-governance/project-charter.md`](docs/00-governance/project-charter.md)
- 문서 인덱스: [`docs/README.md`](docs/README.md)
- 현재 진행 및 미결정: [`docs/09-delivery/session-handoff.md`](docs/09-delivery/session-handoff.md)

- 기여·작업 단위: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 검사·PR 체크리스트: [workflow](.github/workflows/docs-lint.yml)이 pull request/push 때 실행
- Backend build·PostgreSQL tests: [workflow](.github/workflows/backend-ci.yml).

- 진행: Gate 0/1 완료, Gate 2 Buyer 기준 승인, Gate 3 architecture 기준 확정. [Issue #72](https://github.com/Neo1228/ATLAS/issues/72)·[PR #73](https://github.com/Neo1228/ATLAS/pull/73) local runtime 완료. Fake PSP only; live payment, cloud deployment, production load는 별도 상태.
- 기술 비교와 shared API/error rules: [ADR-0002](docs/06-decisions/adr/ADR-0002-runtime-technology-review.md), [Decision Log](docs/00-governance/decision-log.md), [Issue #78](https://github.com/Neo1228/ATLAS/issues/78), [PR #79](https://github.com/Neo1228/ATLAS/pull/79).

## 도메인 모듈

한 번에 실행·배포하는 Spring Boot modular monolith. 루트 Gradle project는 다섯 도메인 모듈과 실행 조립 모듈을 포함합니다. 각 디렉터리의 README에 책임·공개 API·의존·데이터 소유를 기록했습니다.

| 모듈 | 책임 | 의존 |
|---|---|---|
| [application-module](application-module/README.md) | 실행 진입점, HTTP adapters, 런타임 설정·migration | 다섯 도메인 모듈 |
| [campaign-module](campaign-module/README.md) | Seller·Seller-SKU·Campaign | inventory |
| [admission-module](admission-module/README.md) | Seller-SKU global FIFO/K·Campaign ticket | campaign |
| [inventory-module](inventory-module/README.md) | Seller-SKU shared stock pool·reservation·conservation | 없음 |
| [order-module](order-module/README.md) | checkout·order lifecycle·seller attribution | admission, inventory, payment |
| [payment-module](payment-module/README.md) | payment attempts·provider 결과 | 없음 |

각 도메인 `-module`은 `.service` contract, `.dto.request` 입력, `.dto.response` 출력, `.exception` 오류를 공개합니다. 상태 enum은 persistence-owned lifecycle vocabulary로 `.persistence.state`에 두고 모듈 간 contract에서 공유합니다; JPA entity/repository는 공유하지 않습니다. 구현과 scheduled expiry는 `.service.impl`, JPA mapping/CRUD는 `.persistence.entity`·`.persistence.repository`, Fake provider는 `.provider.fake`에 둡니다. ArchUnit이 허용한 package dependency만 검사하며 `package-info.java` marker는 없습니다. 기본 CRUD는 JPA, advisory lock·sequence·guarded update 등 PostgreSQL 특화 경로만 JDBC/native SQL을 사용합니다. Flyway가 schema를 소유하고 Hibernate는 `validate`만 수행합니다.
HTTP controller·request DTO·공통 설정·오류 처리는 `application-module`의 `.application.controller.<domain>`·`.application.controller.dto`·`.application.config`·`.application.exception`에 둡니다. CQRS·`internal`·불필요한 공통 모듈은 사용하지 않습니다. 경계와 근거는 [ADR-0003](docs/06-decisions/adr/ADR-0003-gradle-module-boundaries.md), SQL 선택은 [ADR-0004](docs/06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md).

실행: `./gradlew :application-module:bootRun`. 전체 compile·Checkstyle·test 검증: `./gradlew check`.

### 의존 방향

Gradle module 의존은 한 방향이며 각 module의 `build.gradle`에서 확인할 수 있습니다. 모듈 간 호출은 현재 단일 JVM·transaction 경계 안의 공개 API 호출입니다. 독립 배포 또는 remote extraction을 뜻하지 않습니다.

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

1. Seller 등록: `POST /api/sellers`
   - 응답의 UUID를 이후 요청에서 `sellerId`로 사용.
2. Seller-SKU 등록: `POST /api/sellers/{sellerId}/skus`

   ```json
   {"skuId":"demo-sku","initialStock":1,"maxActiveCheckouts":1}
   ```

3. Campaign 생성: `POST /api/campaigns`

   ```json
   {"sellerId":"<seller-uuid>","skuId":"demo-sku","queueTicketTtlSeconds":300}
   ```

4. Sale open: `POST /api/campaigns/{campaignId}/open`
5. Queue 진입: `POST /api/campaigns/{campaignId}/tickets`
   - Header: `Idempotency-Key: demo-request-1`
   - Body: `{"skuId":"demo-sku","buyerRef":"local-buyer-1"}`
6. Checkout 시작: `POST /api/campaigns/{campaignId}/tickets/{ticketId}/checkout`
7. Fake payment 제출: `POST /api/orders/{orderId}/payment`
8. 상태 확인·복구: `GET /api/orders/{orderId}`, `POST /api/orders/{orderId}/reconcile`

같은 `(sellerId, skuId)`를 참조하는 Campaign은 재고 pool과 FIFO/K를 공유. Ticket TTL과 idempotency scope는 Campaign 기준. `sellerId`는 data identity일 뿐 인증·권한 검사가 아니며, tenant isolation을 제공하지 않음. 현재 변경은 [Issue #89](https://github.com/Neo1228/ATLAS/issues/89)와 [PR #90](https://github.com/Neo1228/ATLAS/pull/90)에서 추적.

실제 결제·Buyer 인증·Tenant 격리·배포·성능 보장은 미구현. 장기 도메인 범위는 [Project Charter](docs/00-governance/project-charter.md), [Requirements Index](docs/02-requirements/requirements-index.md), 미결정 사항은 [Q-002/Q-003/Q-007/Q-010](docs/00-governance/assumptions-and-open-questions.md) 참조.
