# ATLAS

한정판매 시스템의 backend 학습 프로젝트. 주문·재고·결제·환불·정산의 정합성과 장애 복구를 다룹니다.

updated: 2026-11-03
장기 범위: 결제 신뢰성, 고동시성 판매, 정산·대사.

현재 승인 범위는 local Buyer flow와 Seller-SKU shared stock/global FIFO. `sellerId`는 인증·인가가 아님. Live PSP, deployment, production security/capacity/SLO는 미승인.

- Requirements, domain rules, open questions: [Requirements](docs/02-requirements/requirements-index.md)
- Decisions and rationale: [Decision Log](docs/00-governance/decision-log.md)

## Measured evidence

| Evidence | Observed result | Boundary |
|---|---|---|
| [EXP-013](docs/08-experiments/EXP-013-gate4-20-minute-calibration.md) | 20-minute local run: no HTTP failure or inventory-conservation breach; K=1 produced 0 WAITING/409. | Did not measure queue contention; no production capacity claim. |
| [EXP-014](docs/08-experiments/EXP-014-id-strategy-comparison.md) | UUIDv7 vs UUIDv4 parent PK index: 3,178,496 vs 4,407,296 bytes; insert p50: 211.49 vs 212.64 ms; combined rows/s p50: 183,503.1 vs 184,479.5. | Five-run local PostgreSQL 18.6; generation/commit excluded; no production inference. [SQL](docs/08-experiments/EXP-014-id-strategy-comparison.sql) |
| [EXP-015](https://github.com/Neo1228/ATLAS/issues/95#issuecomment-6605721385) | At 10/100/300 due rows, Order median: 0.247/1.540/5.225 s; Admission: 10.2/10.4/20.1 ms. | Synthetic, sequential; Admission is one Seller-SKU group. No lock/connection/expiry-lag metrics or target. |

- [저장소 작업 규칙](AGENTS.md#github-작업-및-변경-관리)
- GitHub PR 템플릿: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- 문서 검사·PR 체크리스트: [workflow](.github/workflows/docs-lint.yml)이 pull request/push 때 실행
- Backend build·PostgreSQL tests: [workflow](.github/workflows/backend-ci.yml).

V6는 clean-start만 지원. V1–V5 application table에 행이 있으면 DDL 전에 실패하며 데이터를 지우지 않음. 보존할 local data는 앱 밖에서 먼저 보관·reset해야 함 ([D-055](docs/00-governance/decision-log.md)).

## 도메인 모듈

한 번에 실행·배포하는 Spring Boot modular monolith. 여섯 Gradle project는 root의 `ATLAS-<name>` 디렉터리에 배치. `settings.gradle`은 물리 디렉터리를 기존 Gradle project path(`:application-module` 등)에 매핑하며, Java package와 의존성 방향은 유지.

| 물리 디렉터리 | Gradle project | 책임 | 의존 |
|---|---|---|---|
| `ATLAS-application` | `:application-module` | composition root, HTTP, runtime configuration, Flyway migrations | 다섯 도메인 모듈 |
| `ATLAS-campaign` | `:campaign-module` | Seller·Seller-SKU 등록, Campaign | inventory |
| `ATLAS-admission` | `:admission-module` | ticket·sequence·idempotency, Seller-SKU FIFO/K·expiry | campaign |
| `ATLAS-inventory` | `:inventory-module` | shared stock pool·reservation·conservation | 없음 |
| `ATLAS-order` | `:order-module` | checkout·order lifecycle·payment coordination | admission, inventory, payment |
| `ATLAS-payment` | `:payment-module` | payment-attempt state·Fake PSP adapter | 없음 |

- **공개 contract:** `.service`, `.dto.request`, `.dto.response`, `.exception`; HTTP transport DTO는 `application-module`의 `.application.controller.dto`.
- **상태·저장소:** lifecycle enum은 `.persistence.state`; JPA entity는 `.persistence.entity`; repository는 module-private `.repository`. Entity/repository는 모듈 간 공유하지 않음.
- **구현:** workflow/scheduled expiry는 `.service.impl`, Fake provider는 `.provider.fake`. `application-module`은 HTTP adapter, 공통 설정·오류 처리, Flyway migration 소유.
- **DB 접근:** CRUD는 JPA. PostgreSQL 전용 lock/sequence/guarded update는 `.repository`의 JDBC/native SQL. Flyway owns schema; Hibernate `validate`.
- **경계:** ArchUnit은 허용한 package dependency만 검사. `package-info.java` marker, CQRS, `internal`, 불필요한 common module 금지. 결정·근거는 [Decision Log](docs/00-governance/decision-log.md).

실행: `./gradlew :application-module:bootRun`. 전체 compile·Checkstyle·test 검증: `./gradlew check`.

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
