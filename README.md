# ATLAS

한정판매 시스템을 설계·실험하는 학습 프로젝트. 주문·재고·결제·환불·정산의 정합성과 장애 복구를 다룹니다.

- 갱신일: 2026-11-04
- 장기 범위: 결제 신뢰성, 고동시성 판매, 정산·대사.

현재 승인 범위는 로컬 Buyer 구매 흐름과 Seller-SKU 단위 공유 재고·전역 FIFO입니다. `sellerId`는 데이터 식별자이며 인증·인가 수단이 아닙니다. 실제 결제, 배포, 운영 환경의 보안·처리 용량·SLO는 승인되지 않았습니다.

- 요구사항과 미결정 사항: [요구사항 인덱스](docs/01-requirements/requirements-index.md)
- 결정과 근거: [결정 로그](docs/00-governance/decision-log.md)

## 실측 자료

| 자료 | 관찰 결과 | 해석 범위 |
|---|---|---|
| [EXP-013](docs/03-experiments/EXP-013-gate4-20-minute-calibration.md) | 로컬 20분 실행에서 HTTP 오류와 재고 보존 위반이 없었습니다. K=1 조건에서 WAITING/409는 발생하지 않았습니다. | 대기열 경합은 측정하지 않았습니다. 운영 처리 용량을 뜻하지 않습니다. |
| [EXP-014](docs/03-experiments/EXP-014-id-strategy-comparison.md) | UUIDv7/UUIDv4 상위 키 인덱스: 3,178,496/4,407,296 bytes. Insert p50: 211.49/212.64 ms. 결합 처리량 p50: 183,503.1/184,479.5 rows/s. | 로컬 PostgreSQL 18.6에서 전략별 5회 측정. ID 생성·commit 시간 제외. 운영 환경에 일반화하지 않습니다. [실험 SQL](docs/03-experiments/EXP-014-id-strategy-comparison.sql) |
| [EXP-015](https://github.com/Neo1228/ATLAS/issues/95#issuecomment-6605721385) | 만료 대상 10/100/300건에서 Order 중앙값은 0.247/1.540/5.225초, Admission은 10.2/10.4/20.1ms였습니다. | 합성 데이터를 순차 처리한 결과입니다. Admission은 Seller-SKU 한 그룹만 측정했습니다. 잠금·연결 점유·만료 지연은 측정하지 않았습니다. |

- [저장소 작업 규칙](AGENTS.md#github-작업-및-변경-관리)
- GitHub PR 양식: [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)
- Markdown 검사·PR 체크리스트: [검사 workflow](.github/workflows/docs-lint.yml)
- Backend 빌드·PostgreSQL 통합 검사: [검증 workflow](.github/workflows/backend-ci.yml)

V6는 빈 데이터베이스에서 새 schema를 만드는 방식만 지원합니다. V1–V5 application table에 데이터가 있으면 DDL 전에 실패하며 데이터를 변경하지 않습니다. 보존할 로컬 데이터는 앱 외부에서 먼저 보관하거나 초기화해야 합니다 ([D-055](docs/00-governance/decision-log.md)).

## 도메인 모듈

ATLAS는 Spring Boot modular monolith입니다. 여섯 Gradle project는 저장소 최상위의 `ATLAS-<name>` 디렉터리에 있습니다. `settings.gradle`이 물리 디렉터리를 기존 Gradle project path(예: `:application-module`)에 연결합니다. Java package와 의존 방향은 바뀌지 않습니다.

| 물리 디렉터리 | Gradle project | 책임 | 의존 대상 |
|---|---|---|---|
| `ATLAS-application` | `:application-module` | 실행 조립, HTTP, 설정, Flyway migration | 다섯 도메인 모듈 |
| `ATLAS-campaign` | `:campaign-module` | Seller·Seller-SKU 등록, Campaign | inventory |
| `ATLAS-admission` | `:admission-module` | ticket·sequence·idempotency, Seller-SKU FIFO/K·만료 | campaign |
| `ATLAS-inventory` | `:inventory-module` | 공유 재고·reservation·재고 보존 | 없음 |
| `ATLAS-order` | `:order-module` | checkout·주문 상태·결제 조정 | admission, inventory, payment |
| `ATLAS-payment` | `:payment-module` | payment attempt 상태·Fake PSP 연결 | 없음 |

- 공개 계약: `.service`, `.dto.request`, `.dto.response`, `.exception`. HTTP 전송 DTO는 `application-module` 소유.
- 상태·저장소: lifecycle enum은 `.persistence.state`, JPA entity는 `.persistence.entity`, module-private repository는 `.repository`. 모듈 사이에서 entity/repository를 공유하지 않습니다.
- 구현: workflow와 만료 처리는 `.service.impl`, Fake provider는 `.provider.fake`에 둡니다. `application-module`은 HTTP adapter, 공통 설정·오류 처리, Flyway migration을 소유합니다.
- 데이터 접근: 일반 CRUD는 JPA, PostgreSQL 전용 lock·sequence·guarded update는 `.repository`의 JDBC/native SQL로 처리합니다. Flyway가 schema를 관리하고 Hibernate는 `validate`만 수행합니다.
- 경계 검사: ArchUnit은 명시한 package 의존 규칙만 검사합니다. `package-info.java` marker, CQRS, `internal`, 불필요한 common module은 두지 않습니다. 근거는 [결정 로그](docs/00-governance/decision-log.md).

- 실행: `./gradlew :application-module:bootRun`
- 전체 compile·Checkstyle·test: `./gradlew check`

## 로컬 실행 환경

- 필요 도구: JDK 25, Docker Compose. CI는 Temurin 25를 사용하며 운영 JDK 배포판은 미정입니다.
- Toolchain 설정: `export JAVA_HOME="<JDK 25 home>"`; `export PATH="$JAVA_HOME/bin:$PATH"`.
- PostgreSQL 시작 및 준비 확인: `docker compose up -d --wait postgres`
- Lombok 1.18.48은 `compileOnly` + `annotationProcessor`로만 사용하며 runtime classpath에는 포함하지 않습니다. Root `lombok.config`는 generated marker를 class file에 넣지 않습니다. IDE별 설정은 [공식 Lombok setup 안내](https://projectlombok.org/setup) 참조.
- Colima에서 Testcontainers를 사용할 때:

  ```sh
  export DOCKER_HOST="$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}')"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  ```

- 앱 실행: `./gradlew bootRun`. API는 `127.0.0.1:8080`, DB는 local Compose를 사용합니다.
- 검증: `./gradlew check`. PostgreSQL/Testcontainers 통합 테스트를 포함합니다.

### 기본 Buyer 구매 흐름

1. Seller 등록: `POST /api/sellers`
   - 응답 UUID를 이후 요청의 `sellerId`로 사용합니다.
2. Seller-SKU 등록: `POST /api/sellers/{sellerId}/skus`

   ```json
   {"skuId":"demo-sku","initialStock":1,"maxActiveCheckouts":1}
   ```

3. Campaign 생성: `POST /api/campaigns`

   ```json
   {"sellerId":"<seller-uuid>","skuId":"demo-sku","queueTicketTtlSeconds":300}
   ```

4. 판매 시작: `POST /api/campaigns/{campaignId}/open`
5. 대기열 진입: `POST /api/campaigns/{campaignId}/tickets`
   - Header: `Idempotency-Key: demo-request-1`
   - Body: `{"skuId":"demo-sku","buyerRef":"local-buyer-1"}`
6. Checkout 시작: `POST /api/campaigns/{campaignId}/tickets/{ticketId}/checkout`
7. Fake 결제 제출: `POST /api/orders/{orderId}/payment`
8. 상태 확인·복구: `GET /api/orders/{orderId}`, `POST /api/orders/{orderId}/reconcile`
