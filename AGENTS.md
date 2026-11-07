# Repository Guidelines

## 프로젝트 개요

- ATLAS는 전체 이커머스 플랫폼의 구매·판매 흐름, 거래 정합성, 장애 복구를 단계적으로 검증하는 로컬 학습 프로젝트.
- 현재 기능: 상품·Seller-SKU, 장바구니, 한정판매 대기열, 재고 예약·location routing, 주문·Fake PSP, 부분 출고·배송 기록, 내부 정산·환불·반품.
- 실제 사용자, 개인정보, 실결제·송금, 운송사 연동, 클라우드 운영은 범위 밖.
- 검증된 사실, 소유자 결정, 합성 입력, 실측 결과를 구분. 시험 입력을 실제 수요나 운영 용량으로 표현하지 않음.
- 현재 기능과 실행 방법: [README](README.md). 기능 요구사항·미결정 정책: [요구사항 인덱스](docs/01-requirements/requirements-index.md). 지속 보존할 결정: [결정 로그](docs/00-governance/decision-log.md).

## 아키텍처 및 데이터 흐름

- 하나의 Spring Boot 모듈형 단일 애플리케이션(modular monolith). Gradle 모듈은 독립 배포 서비스가 아님.
- `application-module`: HTTP 계층, 설정, 공통 오류 처리, Flyway 마이그레이션.
- 도메인 소유권:

| 모듈 | 책임 |
|---|---|
| `catalog-module` | Seller, Product/ProductCategory, Seller-SKU, 현재 가격·seller-scoped Catalog listing |
| `cart-module` | Buyer당 Cart 1개, `(sellerId, skuId)`별 CartItem |
| `campaign-module` | Campaign과 판매 자격 |
| `admission-module` | FIFO 대기열, ticket, checkout slot, queue policy |
| `inventory-module` | 공유 재고 pool, reservation, 수량 보존 |
| `order-module` | BuyerCheckout, Seller별 PurchaseOrder/OrderItem·location snapshot, Shipment·fulfillment·refund·return |
| `payment-module` | PaymentAttempt와 Fake PSP 상태 |
| `settlement-module` | 내부 gross-capture 원장, Seller allocation, local refund reversal, read-only Seller statement; 실제 payout 없음 |

- Current Cart·checkout cardinality and return policy: [ADR-0016](docs/02-decisions/adr/ADR-0016-buyer-wide-cart-multi-seller-checkout.md).
- 모듈 간 호출은 공개 `.service`/DTO 계약으로 제한. 다른 모듈의 entity/repository 직접 참조 금지.
- 일반 Cart 구매: Buyer-wide Cart `(sellerId, skuId)` → BuyerCheckout와 Seller별 PurchaseOrder → OrderItem snapshot → 전체 재고 atomic reservation → checkout당 Fake PSP attempt/capture 1회 → Seller별 allocation·fulfillment. Delivered return은 Seller 수령 후 OrderItem 검수; PASSED만 local refund/restock, FAILED는 claimed 유지.
- Catalog discovery: Seller-owned flat ProductCategory와 Product의 same-Seller ownership은 Catalog 소유. `categoryId` filter는 Seller scope에서만 허용; Inventory availability는 query 조건이 아님 ([D-074](docs/00-governance/decision-log.md), [ADR-0019](docs/02-decisions/adr/ADR-0019-seller-product-categories.md)).
- Shipment는 실제 dispatch 시점에 기록. SellerOrder마다 여러 package와 OrderItem 수량 분할 가능; `shipped + direct pre-shipment refunded <= ordered`. 모든 net shippable quantity delivery 전 Return 거부. Carrier 연동 없이 local simulation ([D-073](docs/00-governance/decision-log.md), [ADR-0018](docs/02-decisions/adr/ADR-0018-seller-order-shipments.md)).
- Location-aware 재고: Inventory가 SellerOrder의 모든 line을 충족하는 active location 하나를 priority ASC·UUID ASC로 선택. 주문 분할 금지. Reservation/PurchaseOrder에 선택 위치를 저장하고 claim·release·return-restock에 재사용. 기존 pool은 V16에서 `DEFAULT`로 보존 매핑 ([D-072](docs/00-governance/decision-log.md), [ADR-0017](docs/02-decisions/adr/ADR-0017-seller-fulfillment-locations.md)).
- 한정판매: Campaign 개방 → queue 자격 확인 → FIFO 순번·checkout slot 발급 → Campaign Order 생성 → 재고 예약 → 같은 Order 결제 흐름.
- 결제 상태가 `PENDING`/`UNKNOWN`이면 같은 attempt와 재고 hold 유지. 결과 대조 전에 새 결제 attempt 생성 금지. 주문 이행·환불 기록은 `OrderState`와 별도 관리.
- 재고 불변조건: `available + reserved + claimed = initial_capacity`; 각 값은 음수가 아님. 확인된 비결제와 필요한 void 뒤에만 hold 해제.
- PSP 결과 기록과 DB transaction은 별개 경계. 실제 외부 요청을 긴 DB transaction 안에서 실행하지 않음. Fake PSP는 로컬 시뮬레이션이며 재시작 뒤 상태 보존은 별도 구현·검증 대상.
- `sellerId`와 `buyerRef`는 합성 식별자일 뿐 인증·인가·tenant 격리 수단이 아님.

## 주요 디렉터리

| 경로 | 용도 |
|---|---|
| `ATLAS-application/` | Spring Boot 실행, HTTP, 설정, Flyway 마이그레이션 |
| `ATLAS-catalog/`, `ATLAS-cart/`, `ATLAS-campaign/`, `ATLAS-admission/` | 상품, 장바구니, Campaign, 대기열 |
| `ATLAS-inventory/`, `ATLAS-order/`, `ATLAS-payment/`, `ATLAS-settlement/` | 재고, 주문, Fake PSP, 내부 원장 |
| `docs/00-governance/` | 결정 로그와 참고자료 레지스트리 |
| `docs/01-requirements/` | 기능 요구사항, 불변조건, 미결정 사항 |
| `docs/02-decisions/adr/` | 승인된 Architecture Decision Record |
| `docs/03-experiments/` | 재현 가능한 실험과 측정 한계 |
| `load-tests/` | 승인된 HTTP 부하 구성에 한해 사용하는 k6 script |
| `.github/workflows/`, `.github/ISSUE_TEMPLATE/` | CI, 문서 검사, Issue/PR 양식 |
| `config/checkstyle/`, `gradle/` | Checkstyle과 Gradle Wrapper 설정 |

## 개발 명령

- 필요 도구: JDK 25, Docker Compose. 로컬 PostgreSQL 컨테이너 버전: 18.6.
- JDK 설정과 로컬 DB 시작:

```sh
export JAVA_HOME="<JDK 25 home>"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait postgres
```

- 애플리케이션 실행: `./gradlew :application-module:bootRun`
- 전체 컴파일·Checkstyle·단위/통합 테스트·실행 archive 검사: `./gradlew --no-daemon check :application-module:bootJar`
- Markdown 검사: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- 기본 API 주소: `127.0.0.1:8080`.
- Colima/Testcontainers: `export DOCKER_HOST="$(docker context inspect --format '{{ .Endpoints.docker.Host }}')"` 및 `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. 기기별 socket 경로 하드코딩 금지.
- 사용자가 금지한 `view` command 실행 금지. 파일 확인은 정적 read/search 도구 사용.

## 코드 규칙 및 공통 패턴

- Java 코드에 FQCN 대신 import 사용. Checkstyle은 Gradle `check`에서 실행.
- 책임을 모듈 안에 응집: 공개 `.service`, `.dto.request`, `.dto.response`, `.exception`; 내부 JPA `.persistence.entity`, lifecycle `.persistence.state`, 소유 모듈 `.repository`, workflow `.service.impl`.
- 공개 DTO는 `.dto.request`의 `*Request`, `.dto.response`의 `*Response`로 명명. HTTP 전용 DTO는 application controller 안에 둠.
- Service는 응집된 use case 조정 책임. 실제로 분리된 업무 책임이 없으면 wrapper class/helper 계층을 추가하지 않음.
- 생성자 주입 우선. Lombok 1.18.48은 `@Getter`, 단순 생성자용 `@RequiredArgsConstructor`에 한정. `@Data`, 일괄 `@Setter`, builder, entity equality 생성 금지.
- 일반 CRUD는 JPA. PostgreSQL 전용 동시성·원자성 SQL은 해당 모듈 `.repository`에서 처리하고, 소유 모듈 테이블에만 기록.
- 계층 경계: Controller는 HTTP binding·status·transport token 변환과 Service 호출만 담당. Use-case policy·transaction·repository 접근 금지.
- Service는 유스케이스·정책 검증·transaction 경계와 비즈니스 오류 변환 담당. Repository는 SQL/JPA와 persistence 결과만 담당하며 HTTP DTO·HTTP 예외에 의존 금지. 조회 projection record는 `.persistence.projection`에 배치.
- 생성자 DI로 필요한 공개 Service만 연결. 모듈 간 entity/repository 직접 참조 및 계층 우회 금지.
- Product 판매 상태와 채널 가용성은 Catalog 소유. 주문·대기열은 Catalog 공개 Service로 판정; 공개 목록은 판매 가능한 상품만 노출하고 Seller 관리 목록은 비활성 상태도 보존.
- Schema는 Flyway가 관리하고 Hibernate는 `ddl-auto: validate`만 수행. 병렬 작업의 migration version은 coordinator가 배정.
- 모듈별 오류는 타입이 지정된 예외와 `Code`로 표현. `GlobalExceptionHandler`는 RFC 9457 `ProblemDetail`과 고정 `code` 반환. 예기치 않은 500 응답에 내부 상세를 노출하지 않음.
- GET 등 조회 요청은 상태를 변경하지 않음. 만료·재시도·대기열 승격은 상태 변경 요청이나 scheduler가 처리.
- Transaction 경계는 도메인 불변조건 기준. PostgreSQL 잠금/SQL 경합은 실제 DB로 검증. `@Transactional` proxy 대상 class/method에 `final` 사용 금지.
- CQRS, CommandBus, handler/mediator, 사전 `common` module, 범용 base/mapper/framework 금지. 실제 요구 없이 모듈·라이브러리·인프라 추가 금지.
- 신규 라이브러리가 직접 구현보다 적합하면 기능·호환성·보안·유지보수·라이선스·실행/테스트/빌드 범위와 대안을 Issue/PR에 기록.
- 주요 식별자는 UUIDv7 결정(D-056)과 현재 migration을 확인. Hibernate ID 생성 API와 버전은 Spring Boot BOM 기준 검증.

## 중요 파일

- `settings.gradle`: Gradle project와 `ATLAS-*` 디렉터리 연결.
- `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`: Java toolchain, 의존성 관리, Checkstyle, Gradle Wrapper.
- `ATLAS-application/src/main/java/com/atlas/AtlasApplication.java`: Spring Boot 시작점과 scheduling.
- `ATLAS-application/src/main/resources/application.yml`, `compose.yaml`: 로컬 실행과 PostgreSQL 기본값. Credential은 로컬 전용.
- `ATLAS-application/src/main/resources/db/migration/`: versioned schema 변경. 이미 적용한 migration 수정 금지.
- `ATLAS-application/src/main/java/com/atlas/application/controller/`: HTTP adapter. 전송 field/validation이 다를 때만 HTTP DTO 별도 작성.
- `.github/workflows/backend-ci.yml`, `.github/workflows/docs-lint.yml`: 필수 CI 명령과 검사 이름.
- `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/`: 필수 Issue/PR 양식과 확인 항목.
- `docs/03-experiments/EXP-013-*`은 V1–V5/Campaign 중심 과거 실험이며 현행 V6+ 흐름을 검증하지 않음. EXP-014 수치는 한정된 로컬 UUID 비교이지 일반 성능 보장이 아님.

## 런타임 및 도구

- 저장소에서 고정한 버전: Java 25, Gradle Wrapper 9.8.2, Spring Boot 4.1.2, PostgreSQL 18.6.
- 전역 Gradle 대신 저장소 Wrapper 사용. 일반 의존성은 `implementation`, PostgreSQL/Flyway는 실행 시, Lombok은 `compileOnly`+`annotationProcessor`, 테스트 도구는 테스트 전용.
- 테스트 도구: JUnit Platform, Spring Boot Test, Testcontainers PostgreSQL 18.6, ArchUnit 1.5.1. Backend CI는 Java 25와 Docker 확인 뒤 Gradle check 실행.
- 문서 검사는 Node 22와 markdownlint-cli2 0.24.0 사용. `package.json` 또는 별도 JavaScript package-manager workflow는 없음.
- 클라우드 및 실제 결제·환불은 범위 밖. PayPal Sandbox는 미래 후보일 뿐 승인된 통합이 아니며 Fake PSP는 PayPal 동작을 재현하지 않음.
- Cache, broker, search service, worker, 외부 library를 선제 도입하지 않음. PostgreSQL 기본 구현의 병목·기능 공백을 재현한 뒤 비용과 대안을 비교.

## 테스트와 품질 점검

- 단위 테스트는 각 `ATLAS-*/src/test`, Spring/PostgreSQL 통합·아키텍처 테스트는 `ATLAS-application/src/test`.
- 주요 테스트: `ModuleBoundaryTest`, `CatalogIntegrationTest`, `ProductCategoryMigrationIntegrationTest`, `ProductOrderabilityMigrationIntegrationTest`, `BuyerFlowIntegrationTest`, `InventoryConcurrencyIntegrationTest`, `PaymentLifecycleIntegrationTest`, `SettlementLedgerIntegrationTest`, `SettlementStatementIntegrationTest`, `OrderShipmentIntegrationTest`, `OrderShipmentMigrationIntegrationTest`, `OrderRefundLifecycleIntegrationTest`, `SellerSkuGlobalFifoMigrationIntegrationTest`.
- Controller slice test는 Service-owned validation의 typed exception을 stub하고 HTTP mapping을 확인. Mock을 통과시키려고 domain policy를 Controller로 옮기지 않음.
- Direct SQL fixture는 대상 schema와 일치시킴: 현재 schema의 `NOT NULL`·FK 필수 값을 채우고, migration upgrade fixture는 predecessor Flyway version을 고정.
- PostgreSQL 전용 SQL, 잠금, migration, 동시성, transaction rollback은 Testcontainers의 실제 PostgreSQL에서 검증. Docker 접근 필요; CI는 `docker info` 확인.
- 구체적인 계약·실패 경계 검증. 단순 전달·mapping·default 값과 중복 assertion은 테스트하지 않음. Mock은 PostgreSQL 의미 검증의 대체 수단이 아님.
- 수동 SQL/마이그레이션 시험은 전용 임시 DB/schema만 사용. 기존 `atlas` DB를 초기화·삭제하는 테스트 금지. Clean-start migration guard 준수.
- 변경 작업 완료 후 실제 영향 경로와 전체 check 실행. 실패·미실행 검사는 성공으로 기록하지 말고 명령·환경·실패 시나리오·남은 한계 기록.
- 성능·처리용량 주장에는 workload·data shape·환경·반복 측정·출처 한계 명시. Q-008/EXP 수치는 합성·보고 전용 범위에 한정. k6는 승인된 HTTP workload, Toxiproxy는 구체적인 network failure 가설에만 사용.
- `EXP-` 식별자는 저장소 전체에서 고유하게 사용. Issue comment에만 남긴 실측은 정식 EXP report가 아니면 `[Issue #N 측정 메모]`로 표기. 새 EXP 작성 전 README·문서·Issue 참조를 검색해 중복 ID와 오래된 실험 링크를 확인.
- 수치 목표는 직접 측정 또는 출처가 있을 때만 사용. 합성 입력을 사용자 수요/SLO/capacity로 해석하지 않음.

## Git 및 병렬 작업

- 하나의 목적·완료 기준·독립 검토 경계에는 Issue/PR 하나. 파일·클래스·엔드포인트별 분할 금지. 관련 코드·테스트·migration·문서를 묶고 원격 Issue/PR은 로컬 작업 완료 후 갱신.
- 브랜치 이름: `<type>/<issue>-<short-topic>`. Commit 제목: `<type>(<scope>): <한국어 요약>`; 짧은 명령형 요약.
- `main` 직접 push 금지. PR 확인 항목을 모두 완료하고 필수 CI 통과 후 squash merge.
- 변경 전 `git status --short --branch`; stage 전에 `git diff`, `git diff --cached` 확인. 관련 파일만 stage. 승인 없는 reset/clean/shared history rewrite 금지.
- 병렬 세션마다 하나의 plan/worktree와 명시적 허용 파일 목록 사용. 공통 요구사항·결정·README·참고자료·migration·build 파일은 통합 담당자 한 명만 수정. 충돌이 있는 rebase와 migration version은 먼저 조정하고 검사 재실행.
- 소유자가 plan을 참고용으로만 지정하면 배정된 local worktree에 미커밋 상태로 유지. 게시하거나 별도 Issue/PR 만들지 않음.
- 복잡한 Issue/PR은 배경, 선택 이유·대안, 상태/금전/재고 영향, 한계, 검증을 설명하고 요구사항·결정 기록을 직접 링크. 출처 없는 ID 나열이나 실행하지 않은 테스트를 성공으로 주장하지 않음.
- PR의 `Issues:` 필드는 `Closes #N — [Issue #N](https://github.com/OWNER/REPO/issues/N)` 또는 `Refs #N — [Issue #N](https://github.com/OWNER/REPO/issues/N)`을 같은 줄에 작성. `요구사항·결정·규칙 ID`도 같은 줄에 직접 링크하거나 `N/A: 구체적 사유` 표기. `.github/workflows/docs-lint.yml`이 두 필드를 검사.
