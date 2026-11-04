# ATLAS

전체 이커머스 플랫폼 backend를 단계적으로 검증하는 로컬 학습 프로젝트. 현재 첫 시나리오는 Seller-SKU 공유 재고와 대기열 기반 한정판매의 주문·결제 정합성 검증.

- 현재 흐름: Seller-SKU 기준 shared inventory와 global FIFO/K
- 미구현 범위: 실사용자 인증·tenant 격리, live payment, cloud 배포, production SLO/capacity
- 요구사항: [Requirements Index](docs/01-requirements/requirements-index.md)
- 결정: [Decision Log](docs/00-governance/decision-log.md)
- 로컬 실행 및 검증: 아래 [환경 설정](#로컬-실행-환경)
- 작업 규칙: [AGENTS.md](AGENTS.md)
- GitHub 양식: [PR template](.github/PULL_REQUEST_TEMPLATE.md), [Issue templates](.github/ISSUE_TEMPLATE)
- CI: [Backend checks](.github/workflows/backend-ci.yml), [Markdown lint·PR checklist](.github/workflows/docs-lint.yml)

## 현재 검증 결과

| 실험 | 결과 | 범위와 한계 |
|---|---|---|
| [EXP-013](docs/03-experiments/EXP-013-gate4-20-minute-calibration.md) | Local Buyer flow에서 inventory 보존 확인 | K=1 profile에서 WAITING/409 미관찰. Queue contention·capacity 입증 아님 |
| [EXP-014](docs/03-experiments/EXP-014-id-strategy-comparison.md) | UUIDv7 parent PK index 3,178,496 bytes; UUIDv4 4,407,296 bytes. Insert p50 211.49/212.64ms로 유사 | PostgreSQL 18.6, 합성 100k parent/200k child. 단일 local 비교; production 성능 근거 아님 |
| [EXP-015](https://github.com/Neo1228/ATLAS/issues/95#issuecomment-6605721385) | Order expiry sweep median: 10/100/300건에서 247.1/1,540.2/5,225.4ms. Admission: 10.2/10.4/20.1ms | 로컬 데이터 규모·현재 구현의 측정치. production traffic/SLO 아님 |

추가 부하·용량 검증은 별도 승인과 범위 결정 전까지 수행하지 않습니다. 실험 목록: [EXP registry](docs/03-experiments/).

## 모듈 구조

한 번에 실행되는 Spring Boot modular monolith. 물리 디렉터리는 저장소 root의 `ATLAS-*`; `settings.gradle`은 기존 Gradle project path를 유지합니다.

| 디렉터리 | Gradle project | 책임 |
|---|---|---|
| [ATLAS-application](ATLAS-application) | `:application-module` | HTTP, 설정, 공통 오류 처리, Flyway migration |
| [ATLAS-campaign](ATLAS-campaign) | `:campaign-module` | Seller, Seller-SKU, Campaign |
| [ATLAS-admission](ATLAS-admission) | `:admission-module` | Seller-SKU FIFO/K, ticket, expiry |
| [ATLAS-inventory](ATLAS-inventory) | `:inventory-module` | Shared stock, reservation, 재고 불변조건 |
| [ATLAS-order](ATLAS-order) | `:order-module` | Checkout 및 order lifecycle |
| [ATLAS-payment](ATLAS-payment) | `:payment-module` | Payment attempt와 Fake PSP 상태 |

- 모듈 간 호출은 공개 `.service`/DTO 계약을 사용; entity/repository 직접 공유 금지.
- CRUD는 JPA, PostgreSQL 특화 SQL은 모듈 내부 `.repository`의 JDBC/native query.
- 생성 primary ID는 UUIDv7. 측정 근거와 Hibernate `@Incubating` caveat: [D-056](docs/00-governance/decision-log.md), [ADR-0007](docs/02-decisions/adr/ADR-0007-uuidv7-identifiers.md).
- Schema는 Flyway 소유, Hibernate는 `ddl-auto: validate`.

## 로컬 실행 환경

필요: JDK 25, Docker Compose, PostgreSQL container.

```sh
export JAVA_HOME="<JDK 25 home>"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait postgres
./gradlew :application-module:bootRun
```

- API bind address: `127.0.0.1:8080`.
- 전체 compile·Checkstyle·unit/integration tests: `./gradlew --no-daemon check :application-module:bootJar`.
- Colima에서 Testcontainers 연결이 필요하면 현재 Docker context의 `DOCKER_HOST`와 `/var/run/docker.sock` override를 사용. 자세한 규칙은 [AGENTS.md](AGENTS.md#재발-방지-기준) 참조.

## 기본 Buyer 흐름

1. Seller 생성: `POST /api/sellers` → `sellerId` 반환.
2. Seller-SKU 등록: `POST /api/sellers/{sellerId}/skus`.

   ```json
   {"skuId":"demo-sku","initialStock":1,"maxActiveCheckouts":1}
   ```

3. Campaign 생성: `POST /api/campaigns`.

   ```json
   {"sellerId":"<seller-uuid>","skuId":"demo-sku","queueTicketTtlSeconds":300}
   ```

4. Campaign open: `POST /api/campaigns/{campaignId}/open`.
5. Queue join: `POST /api/campaigns/{campaignId}/tickets` with `Idempotency-Key` header and `{"skuId":"demo-sku","buyerRef":"local-buyer-1"}`.
6. Checkout: `POST /api/campaigns/{campaignId}/tickets/{ticketId}/checkout`.
7. Fake payment: `POST /api/orders/{orderId}/payment`.
8. 상태 확인/수동 복구: `GET /api/orders/{orderId}`, `POST /api/orders/{orderId}/reconcile`.

같은 `(sellerId, skuId)`를 참조하는 Campaign은 재고와 FIFO/K를 공유합니다. `sellerId`는 데이터 식별자이며 인증·인가 수단이 아닙니다.

## Database 시작 정책

V6는 fresh start만 지원합니다. V1–V5 application table에 기존 row가 있으면 schema/data를 수정하지 않고 중단합니다. 빈 database만 새 Seller-SKU schema로 전환할 수 있습니다. 기존 데이터가 필요하면 애플리케이션 밖에서 먼저 명시적으로 보관·초기화해야 하며, 앱은 자동 reset하지 않습니다. 자세한 조건: [D-055](docs/00-governance/decision-log.md), [ADR-0006](docs/02-decisions/adr/ADR-0006-v6-clean-start.md).
