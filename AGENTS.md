# Repository Guidelines

## 프로젝트 개요

- ATLAS는 전체 이커머스 플랫폼의 구매·판매 흐름, 거래 정합성, 장애 복구를 단계적으로 검증하는 로컬 학습 프로젝트.
- 현재 기능: 상품·Seller-SKU, 장바구니, 한정판매 대기열, 재고 예약, 주문·Fake PSP, fulfillment, 내부 정산·환불 기록.
- 실제 사용자, 개인정보, 실결제·송금, 운송사 연동, 클라우드 운영은 범위 밖.
- 검증된 사실, 소유자 결정, 합성 입력, 실측 결과를 구분. 시험 입력을 실제 수요나 운영 용량으로 표현하지 않음.
- 현재 기능과 실행 방법: [README](README.md). 요구사항과 미결정 정책: [요구사항 인덱스](docs/01-requirements/requirements-index.md). 결정 근거: [결정 로그](docs/00-governance/decision-log.md).

## 아키텍처와 데이터 흐름

- 하나의 Spring Boot modular monolith. Gradle 모듈은 독립 배포 서비스가 아님.
- `application-module`: HTTP adapter, 설정, 공통 오류 처리, Flyway migration.
- 도메인 소유권:

| 모듈 | 책임 |
|---|---|
| `catalog-module` | Seller, Product, Seller-SKU, 현재 가격 |
| `cart-module` | Seller별 Cart와 CartItem |
| `campaign-module` | Campaign과 판매 자격 |
| `admission-module` | FIFO 대기열, ticket, checkout slot, queue policy |
| `inventory-module` | 공유 재고 pool, reservation, 수량 보존 |
| `order-module` | Cart/Campaign checkout, Order/OrderItem, fulfillment 및 흐름 조정 |
| `payment-module` | PaymentAttempt와 Fake PSP 상태 |
| `settlement-module` | 내부 gross-capture 원장과 로컬 환불 reversal |

- 모듈 간 호출은 공개 `.service`/DTO 계약 사용. 다른 모듈의 entity/repository 직접 참조 금지.
- 일반 Cart: 현재 Seller-SKU·가격 확인 → OrderItem snapshot → 모든 재고 line 원자적 예약 → Cart 비우기 → 별도 결제 요청.
- 한정판매: Campaign 개방 → queue 자격 확인 → FIFO 순번·checkout slot → Campaign Order → 재고 예약 → 같은 Order 결제 흐름.
- 결제 성공이 확인된 뒤 재고 claim. `PENDING`/`UNKNOWN`이면 같은 attempt와 재고 hold를 유지해 reconciliation. Fulfillment와 refund record는 `OrderState`와 분리.
- 재고 불변조건: `available + reserved + claimed = initial_capacity`; 각 값은 음수가 아님. 확인된 비결제/필요한 void 전에는 hold 해제 금지.
- PSP 요청과 DB commit을 한 transaction으로 취급하지 않음. Fake PSP는 로컬 시뮬레이션이며, 앱 재시작 후 상태 보존을 별도 구현·검증하지 않으면 provider 상태가 memory에서 초기화됨.
- `sellerId`와 `buyerRef`는 합성 식별자일 뿐 인증·인가·tenant 격리 수단이 아님.

## 주요 디렉터리

| 경로 | 용도 |
|---|---|
| `ATLAS-application/` | Spring Boot 실행, HTTP, 설정, Flyway migration |
| `ATLAS-catalog/`, `ATLAS-cart/`, `ATLAS-campaign/`, `ATLAS-admission/` | 상품, 장바구니, Campaign, 대기열 |
| `ATLAS-inventory/`, `ATLAS-order/`, `ATLAS-payment/`, `ATLAS-settlement/` | 재고, 주문, Fake PSP, 내부 원장 |
| `docs/00-governance/` | 결정 로그와 참고자료 레지스트리 |
| `docs/01-requirements/` | 기능 요구사항, 불변조건, 미결정 사항 |
| `docs/02-decisions/adr/` | 승인된 Architecture Decision Record |
| `docs/03-experiments/` | 재현 가능한 실험과 측정 한계 |
| `load-tests/` | 승인된 workload에 한해 사용하는 k6 script |
| `.github/workflows/`, `.github/ISSUE_TEMPLATE/` | CI, 문서 검사, Issue/PR 양식 |
| `config/checkstyle/`, `gradle/` | Checkstyle과 Gradle Wrapper 설정 |

## 개발 명령

- 필요 도구: JDK 25, Docker Compose. PostgreSQL image/version: 18.6.
- JDK 설정 및 로컬 DB 시작:

```sh
export JAVA_HOME="<JDK 25 home>"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait postgres
```

- 애플리케이션 실행: `./gradlew :application-module:bootRun`
- 전체 compile·Checkstyle·unit/integration test·실행 archive 검사: `./gradlew --no-daemon check :application-module:bootJar`
- Markdown 검사: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- 기본 API 주소: `127.0.0.1:8080`. Colima 환경에서는 현재 Docker context의 `DOCKER_HOST` 사용; 기기별 socket 경로 하드코딩 금지.
- 사용자가 금지한 `view` command 실행 금지. 정적 파일 확인은 read/search 도구 사용.

## 코드 규칙과 공통 패턴

- Java 코드에 FQCN 대신 import 사용. Checkstyle은 Gradle `check`에서 실행.
- 역할을 모듈 안에 응집: 공개 `.service`, `.dto.request`, `.dto.response`, `.exception`; 내부 JPA `.persistence.entity`, lifecycle `.persistence.state`, 소유 모듈 `.repository`, workflow `.service.impl`.
- Constructor injection 우선. Lombok 1.18.48은 `@Getter`, 단순 생성자용 `@RequiredArgsConstructor`에 한정. `@Data`, 일괄 `@Setter`, builder, entity equality 생성 금지.
- 일반 CRUD는 JPA. PostgreSQL 전용 동시성·atomic SQL은 해당 모듈의 `.repository`에서 처리하고 소유 모듈 테이블에만 쓰기.
- Schema는 Flyway 소유, Hibernate는 `ddl-auto: validate`. 병렬 작업 migration version은 coordinator가 배정.
- 모듈 오류는 typed exception과 `Code` 사용. `GlobalExceptionHandler`는 RFC 9457 `ProblemDetail`과 stable `code` 반환. 예기치 않은 500에서 내부 상세 숨김.
- GET/query는 상태를 바꾸지 않음. 만료·retry·queue promotion은 상태 변경 요청이나 scheduler에서 처리.
- Transaction 경계는 domain invariant 중심. PostgreSQL lock/SQL 경합은 실제 DB로 검증. `@Transactional` proxy 대상 class/method에 `final` 사용 금지.
- CQRS, CommandBus, handler/mediator, 선제 `common` module, generic base/mapper/framework 금지. 실제 요구 없이 module·library·infrastructure 추가 금지.
- 신규 library가 직접 구현보다 적합하면 기능·호환성·보안·유지보수·license·runtime/test/build 비용과 대안을 Issue/PR에 기록.
- Primary ID는 UUIDv7 정책(D-056)과 현재 migration을 확인. Hibernate generator API/version은 BOM 기준으로 검증.

## 중요 파일

- `settings.gradle`: Gradle project와 `ATLAS-*` 디렉터리 연결.
- `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`: Java toolchain, 의존성 관리, Checkstyle, Gradle Wrapper.
- `ATLAS-application/src/main/java/com/atlas/AtlasApplication.java`: Spring Boot 시작점과 scheduling.
- `ATLAS-application/src/main/resources/application.yml`, `compose.yaml`: 로컬 runtime과 PostgreSQL 기본값. Credential은 로컬 전용.
- `ATLAS-application/src/main/resources/db/migration/`: versioned schema 변경. 이미 적용한 migration 수정 금지.
- `ATLAS-application/src/main/java/com/atlas/application/controller/`: HTTP adapter. 전송 field/validation이 다른 경우에만 HTTP DTO 별도 작성.
- `.github/workflows/backend-ci.yml`, `.github/workflows/docs-lint.yml`: 필수 CI 명령과 검사 이름.
- `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/`: 필수 Issue/PR 양식 및 checklist.
- `docs/03-experiments/EXP-013-*`은 V1–V5/Campaign 중심의 과거 실험이며 현행 V6+ 흐름을 검증하지 않음. EXP-014 수치는 한정된 로컬 UUID 비교이지 일반 성능 보장이 아님.

## 런타임과 도구

- 저장소 toolchain: Java 25, Gradle Wrapper 9.8.2, Spring Boot 4.1.2, PostgreSQL 18.6.
- Global Gradle 대신 checked-in Wrapper 사용. 일반 모듈/애플리케이션 의존성은 `implementation`, PostgreSQL/Flyway DB 지원은 runtime scope, Lombok은 `compileOnly`+`annotationProcessor`, test 도구는 test scope.
- Test: JUnit Platform, Spring Boot Test, Testcontainers PostgreSQL 18.6, ArchUnit 1.5.1. Backend CI는 Java 25와 Docker 검사 뒤 Gradle check 실행.
- 문서 CI는 Node 22와 markdownlint-cli2 0.24.0 사용. `package.json` 또는 다른 JS package manager workflow는 구성되어 있지 않음.
- Cloud/live payment/refund 금지. PayPal Sandbox는 미래 후보일 뿐 승인된 통합이 아니며 Fake PSP는 PayPal 동작을 재현하지 않음.
- Cache, broker, search service, worker, external library는 기본 도입 대상이 아님. PostgreSQL 기본 구현의 병목·기능 공백을 재현한 뒤 비용과 대안을 비교.

## 테스트와 품질 점검

- Module unit test는 각 `ATLAS-*/src/test`, Spring/PostgreSQL integration 및 architecture test는 `ATLAS-application/src/test`.
- 주요 suite: `ModuleBoundaryTest`, `BuyerFlowIntegrationTest`, `InventoryConcurrencyIntegrationTest`, `PaymentLifecycleIntegrationTest`, `SettlementLedgerIntegrationTest`, `OrderRefundLifecycleIntegrationTest`, `SellerSkuGlobalFifoMigrationIntegrationTest`.
- PostgreSQL SQL, lock, migration, concurrency, transaction rollback은 Testcontainers의 실제 PostgreSQL에서 검증. Docker 접근 필요; CI는 `docker info` 확인.
- 위험한 계약·실패 경계를 검증. 단순 forwarding/mapping/default 값, 중복 assertion은 테스트하지 않음. Mock은 PostgreSQL 의미 검증의 대체 수단이 아님.
- 수동 SQL/마이그레이션 시험은 전용 disposable DB/schema만 사용. 기존 `atlas` DB를 destructive test에 사용 금지. Clean-start migration guard 준수.
- 변경 작업 완료 후 실제 영향 경로 테스트와 전체 check 수행. 실패/미실행 검사는 성공으로 기록하지 말고 명령·환경·실패 시나리오·한계 보존.
- Performance/capacity 주장에는 workload·data shape·환경·반복 측정·출처 한계 명시. Q-008/EXP 수치는 synthetic/report-only 범위에 한정. k6는 승인된 HTTP workload, Toxiproxy는 구체적인 network failure 가설에만 사용.
- 수치 목표는 직접 측정 또는 출처가 있을 때만 사용. Synthetic input을 사용자 수요/SLO/capacity로 해석하지 않음.

## Git 및 병렬 작업

- 한 목적·완료 기준에는 Issue/PR 하나. Endpoint/class 단위 분할 금지. 관련 코드·테스트·migration·문서를 함께 묶고, 원격 Issue/PR은 로컬 작업 완료 후 갱신.
- Branch: `<type>/<issue>-<short-topic>`. Commit 제목: `<type>(<scope>): <한국어 요약>`; 짧은 명령형 요약.
- `main` 직접 push 금지. PR checklist 완료와 필수 CI 통과 후 squash merge.
- 변경 전 `git status --short --branch`; stage 전에 `git diff`, `git diff --cached` 확인. 관련 파일만 stage. 무단 reset/clean/shared history rewrite 금지.
- 병렬 세션마다 plan/worktree와 명시적 file allowlist 하나. 공통 requirements/decision/README/reference/migration/build 파일은 한 coordinator만 수정. 충돌이 있는 rebase·migration 버전은 먼저 조정하고 검증 재실행.
- Owner가 plan을 참고용으로만 지정하면 배정된 local worktree에 미커밋 상태로 유지. 게시하거나 별도 Issue/PR 만들지 않음.
- 복잡한 Issue/PR은 배경, 선택 이유·대안, state/금전/재고 영향, 한계, 검증을 설명하고 정확한 requirements/decision 기록을 링크. 출처 없는 ID 나열이나 실행하지 않은 테스트 주장 금지.
