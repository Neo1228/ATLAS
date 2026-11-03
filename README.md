# ATLAS

한정판매 시스템의 backend 학습 프로젝트. 주문·재고·결제·환불·정산의 정합성과 장애 복구를 다룹니다.

updated: 2026-11-03
장기 범위: 결제 신뢰성, 고동시성 판매, 정산·대사.

- 프로젝트 범위·승인: [Project Charter](docs/00-governance/project-charter.md), [Roadmap](docs/09-delivery/roadmap.md)
- 요구사항·결정·도메인 규칙: [Requirements](docs/02-requirements/requirements-index.md), [Decision Log](docs/00-governance/decision-log.md), [Business Rules](docs/03-domain/business-rules.md), [Invariants](docs/03-domain/invariants.md)
- 실험·근거·delivery history: [Experiment Registry](docs/08-experiments/experiment-registry.md), [Milestone Log](docs/09-delivery/milestone-log.md)

- [저장소 작업 규칙](AGENTS.md#github-작업-및-변경-관리)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 검사·PR 체크리스트: [workflow](.github/workflows/docs-lint.yml)이 pull request/push 때 실행
- Backend build·PostgreSQL tests: [workflow](.github/workflows/backend-ci.yml).

V6는 clean-start만 지원합니다. V1–V5 application table에 행이 있으면 DDL 전에 실패하며, 보존할 local data는 앱 밖에서 먼저 보관·reset해야 합니다 ([D-055](docs/00-governance/decision-log.md), [ADR-0006](docs/06-decisions/adr/ADR-0006-v6-clean-start.md)).
UUIDv7 identifier 범위와 local 비교 한계: [D-056](docs/00-governance/decision-log.md), [ADR-0007](docs/06-decisions/adr/ADR-0007-uuidv7-identifiers.md), [EXP-014](docs/08-experiments/EXP-014-id-strategy-comparison.md).

## 도메인 모듈

한 번에 실행·배포하는 Spring Boot modular monolith. 여섯 Gradle project는 root의 `ATLAS-<name>` 디렉터리에 배치. `settings.gradle`은 물리 디렉터리를 기존 Gradle project path(`:application-module` 등)에 매핑하며, Java package와 의존성 방향은 유지.

| 물리 디렉터리 | Gradle project | 책임 | 의존 |
|---|---|---|---|
| [ATLAS-application](ATLAS-application/README.md) | `:application-module` | 실행 진입점, HTTP adapters, 런타임 설정·migration | 다섯 도메인 모듈 |
| [ATLAS-campaign](ATLAS-campaign/README.md) | `:campaign-module` | Seller·Seller-SKU·Campaign | inventory |
| [ATLAS-admission](ATLAS-admission/README.md) | `:admission-module` | Seller-SKU global FIFO/K·Campaign ticket | campaign |
| [ATLAS-inventory](ATLAS-inventory/README.md) | `:inventory-module` | Seller-SKU shared stock pool·reservation·conservation | 없음 |
| [ATLAS-order](ATLAS-order/README.md) | `:order-module` | checkout·order lifecycle·seller attribution | admission, inventory, payment |
| [ATLAS-payment](ATLAS-payment/README.md) | `:payment-module` | payment attempts·provider 결과 | 없음 |

- **공개 contract:** `.service`, `.dto.request`, `.dto.response`, `.exception`; HTTP transport DTO는 `application-module`의 `.application.controller.dto`.
- **상태·저장소:** lifecycle enum은 `.persistence.state`; JPA entity는 `.persistence.entity`; repository는 module-private `.repository`. Entity/repository는 모듈 간 공유하지 않음.
- **구현:** workflow/scheduled expiry는 `.service.impl`, Fake provider는 `.provider.fake`. `application-module`은 HTTP adapter, 공통 설정·오류 처리, Flyway migration 소유.
- **DB 접근:** CRUD는 JPA. PostgreSQL 전용 lock/sequence/guarded update는 `.repository`의 JDBC/native SQL. Flyway owns schema; Hibernate `validate`.
- **경계:** ArchUnit은 허용한 package dependency만 검사. `package-info.java` marker, CQRS, `internal`, 불필요한 common module 금지. 상세 근거는 [ADR-0003](docs/06-decisions/adr/ADR-0003-gradle-module-boundaries.md)·[ADR-0004](docs/06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md).

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

같은 Seller-SKU를 참조하는 Campaign은 shared stock/FIFO/K를 사용합니다. Ticket TTL/idempotency는 Campaign별이며 `sellerId`는 인증·인가가 아닙니다 ([D-049](docs/00-governance/decision-log.md), [ADR-0005](docs/06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md)).

Live payment, Buyer authentication, Tenant isolation, deployment와 production capacity는 미구현·미승인 범위입니다 ([Project Charter](docs/00-governance/project-charter.md), [Q-007/Q-009](docs/00-governance/assumptions-and-open-questions.md)).
