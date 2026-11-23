# 저장소 작업 지침

## 프로젝트 개요

ATLAS는 **PayPal AI Hackathon** 참가를 위해 만드는 이커머스 플랫폼. 상품 탐색부터 장바구니, 결제, 주문 이행과 정산까지 전체 거래 흐름을 다루며 한정 판매에만 범위를 한정하지 않음. 기능의 정본은 [요구사항 인덱스](docs/01-requirements/requirements-index.md), 실행 가능한 기능 요약과 흐름은 [README](README.md) 참고.

## 아키텍처와 데이터 흐름

- Spring Boot 기반 **modular monolith**. `ATLAS-*` 디렉터리별로 도메인 책임을 나누며 애플리케이션은 하나로 실행.
- 실제 디렉터리명은 `ATLAS-*`; Gradle project path는 [`settings.gradle`](settings.gradle)에 선언. 현재 task 이름은 `:application-module:...` 등 설정된 project path 사용.
- 주요 흐름: Buyer cart/checkout → admission·inventory 예약 → PayPal authorization/capture → Seller별 order → shipment/return/refund → settlement ledger. Campaign 참여는 선택 경로. 세부 흐름은 README의 Mermaid diagram 참고.

| 모듈 디렉터리 | 주 책임 |
|---|---|
| `ATLAS-application/` | Spring Boot 진입점, HTTP API, 보안·공통 오류, scheduler 설정과 migration |
| `ATLAS-account/` | 계정과 인증 세션 |
| `ATLAS-catalog/` | Seller, Product, category, SKU, 가격·판매 가능성 |
| `ATLAS-cart/` | Buyer cart와 item |
| `ATLAS-campaign/` | Campaign과 구매 자격 |
| `ATLAS-admission/` | FIFO queue, ticket, checkout slot |
| `ATLAS-inventory/` | 재고 pool·reservation·location·transfer |
| `ATLAS-order/` | Buyer checkout, Seller order, shipment, return/refund |
| `ATLAS-payment/` | PayPal Sandbox authorization/capture/refund, webhook |
| `ATLAS-settlement/` | 내부 capture/refund ledger와 Seller statement |

| 계층 | 책임 | 두지 않을 책임 |
|---|---|---|
| Controller | HTTP binding, validation, response mapping | business logic, persistence |
| Service | use-case orchestration, transaction | HTTP 처리 |
| Repository | persistence | service logic, API response DTO |

계층 간 전달 DTO는 `*Request`/`*Response`로 구분. Persistence record/entity는 API에 노출하지 않으며, 의존성은 constructor DI와 모듈 소유 계약으로 연결.

## 주요 디렉터리와 파일

| 경로 | 용도 |
|---|---|
| `ATLAS-*/src/main/java/` | 각 모듈의 Java 구현 |
| `ATLAS-application/src/main/java/com/atlas/AtlasApplication.java` | Spring Boot 진입점 |
| `ATLAS-*/src/test/java/` | JUnit 단위·통합 테스트 |
| `ATLAS-application/src/main/resources/` | `application.yml`, 관측 설정, Flyway migration |
| `config/checkstyle/checkstyle.xml` | Java Checkstyle 규칙 |
| `compose.yaml`, `observability/` | PostgreSQL·실험 관측 환경 |
| `load-tests/` | 시나리오별 k6 스크립트 |
| `docs/00-governance/`, `docs/01-requirements/`, `docs/02-decisions/`, `docs/03-experiments/` | 결정·요구사항·ADR·검증 근거의 정본 |
| `.github/` | Issue/PR 템플릿과 CI workflow |
| `settings.gradle`, `build.gradle`, `gradlew` | 모듈 mapping, 공통 빌드 설정, Gradle Wrapper |

## 개발 명령

```sh
./gradlew clean check
./gradlew :application-module:bootRun
./gradlew :application-module:bootJar
git ls-files -z -- '*.md' | xargs -0 npx --yes markdownlint-cli2@0.24.0
```

PostgreSQL이 필요한 통합 테스트는 Docker를 사용. 로컬 실행 환경·Sandbox 환경 변수와 `.env.local` 처리 기준은 README 참고. 애플리케이션은 `.env.local`을 자동 로드하지 않음.
## Runtime과 도구

- 애플리케이션은 JDK 25와 Gradle Wrapper 사용. Node.js는 application runtime이 아님.
- PostgreSQL integration test와 local database는 Docker Compose 사용. k6는 `load-tests/` 시나리오 실행에만 사용.
- [.env.example](.env.example)을 환경 변수 예시로 사용. `.env.local`이 없을 때만 복사해 기존 자격 증명을 보존하고, 실행 shell에 export. 비밀값은 commit/log 금지. 절차는 [README](README.md#로컬-개발) 참고.

## 코드 규칙과 공통 패턴

- JDK 25와 저장소의 Gradle Wrapper·Checkstyle 설정을 유지. 새 runtime, plugin, dependency는 필요성과 대안을 확인하고 변경 이유를 기록.
- Java FQCN 직접 표기 금지. 필요한 타입은 import하고 단순 이름 사용. 클래스·메서드명은 책임 중심으로 짧고 실무에서 통용되는 용어 사용.
- Controller에는 business logic, Repository에는 service logic·API DTO를 두지 않음. 계층 간 전달 DTO는 `*Request`/`*Response`로 구분하고 persistence record/entity를 API에 노출하지 않음. 작은 클래스·단독 패키지를 형식적으로 추가하지 말고 실제 책임 경계가 생길 때 분리.
- 기존 오류 응답·error code·validation·logging contract를 재사용. 민감값과 credential은 코드·로그·Issue·PR에 기록하지 않음.
- PayPal 통화·환율 스냅샷·callback URL·UNKNOWN capture 복구의 기준: [ADR-0030](docs/02-decisions/adr/ADR-0030-paypal-currency-boundary.md).
- DB transaction은 필요한 원자성 범위에 한정. Provider network call은 transaction 밖에서 실행. 저장된 provider amount/currency와 FX 스냅샷이 일치할 때만 내부 상태 반영.
- HTTP 요청은 현재 동기 흐름을 기본으로 유지. 비동기 처리·thread pool은 처리 순서, 재시도, 상태 추적의 구체적 요구와 검증 근거가 있을 때만 도입; 테스트의 동시 실행 코드를 production pattern으로 복사하지 않음.
- 업무 상태는 소유 모듈의 영속화 및 명시적 상태 전이로 관리. 숨은 mutable singleton state를 추가하지 않음.
- 락은 보호할 invariant와 재현 가능한 경쟁 조건이 있을 때만 추가. lock scope/order, transaction boundary, retry 및 failure recovery를 PostgreSQL 통합 테스트로 검증.
- DDD, CQRS, hexagonal architecture 등 패턴을 전제하지 않음. 기존 계층 구조를 유지하고, 구조 변경은 구체적인 문제·근거가 있을 때만 수행.

## 패키지 구조와 명명

세부 결정은 [ADR-0069](docs/02-decisions/adr/ADR-0069-layered-naming-and-runtime-configuration.md). 아래 규칙은 `ModuleBoundaryTest`가 강제.

- 모든 모듈이 같은 패키지 어휘 사용: `controller`, `dto.request`, `dto.response`, `exception`, `repository`, `service`, `service.impl`, `persistence.entity|projection|state`, `config`, `security`, `provider`(외부 연동), `health`. `domain` 같은 모듈 고유 어휘 금지.
- 계층 패키지는 클래스가 1개여도 상위로 합치지 않음. 모듈마다 같은 자리에 같은 종류가 있어야 탐색 비용이 줄고 패키지 이름으로 규칙을 걸 수 있음. 어휘에 없는 새 패키지는 모든 모듈에 적용할 수 있는지 먼저 확인.
- 모듈 루트(`com.atlas.<module>`)에는 `*Application` 진입점만 둠.

|대상|위치와 이름|
|---|---|
|`*Controller`|`controller[.<도메인>]`|
|`*Repository`, `@Repository`|`repository`. `JdbcTemplate`, `EntityManager` 같은 영속 API는 `repository`와 `persistence`에서만 사용|
|`*ServiceImpl`|`service.impl`. 이름은 `<인터페이스>Impl`|
|`*Configuration`|`config`. 보안 필터·인터셉터·암호화는 `security`|
|`*Request`, `*Response`|`dto.request`, `dto.response`. `*Page`, `*Summary`, enum은 허용. HTTP 바인딩 전용은 `controller.dto`|
|예외|`exception`|
|DB에서 읽은 값|`persistence.projection`. 저장소 클래스 안에 record를 선언하지 않음. 모듈 밖으로 내보낼 때 서비스가 응답 DTO로 변환|

- 인터페이스와 `*Impl`은 다른 Gradle 모듈이 쓰는 계약에만 사용. 한 모듈 안의 순수 계산은 구체 클래스로 유지. 같은 필드를 복사만 하는 DTO 사본을 만들지 않고 소유 모듈의 응답 DTO를 그대로 사용.
- `Helper`, `Util`, `Manager`, `Support`, `Common` 같은 뭉뚱그린 이름 금지. 역할을 이름에 드러냄.
- 40줄 안팎의 package-private 보조 클래스는 모듈마다 두는 것을 허용. 공유하려면 모듈 간 의존이 새로 생기므로 두 번째 사본이 생길 때 비용을 비교.
- `ATLAS-application`은 진입점, HTTP, 보안, 스케줄러 조립이 책임. 도메인 규칙과 SQL은 도메인 모듈이 소유.

## 테스트와 품질 확인

- JUnit Platform과 Spring Boot Test 사용. 단순 로직은 unit test, SQL·transaction·locking 동작은 PostgreSQL integration test로 확인.
- `ModuleBoundaryTest`는 ArchUnit으로 계층·모듈 의존 규칙 검증. PostgreSQL integration test는 Testcontainers, PayPal HTTP test는 MockWebServer 사용.
- 테스트는 꼭 필요한 핵심 검증에만 작성. 새 코드마다 테스트를 기계적으로 늘리지 않고, 깨지면 돈·재고·권한·데이터 정합성이 틀어지는 불변식, 재현된 버그의 회귀, 모듈 간 계약, 동시성·트랜잭션·복구 경계를 우선 검증. 커버리지 비율 gate는 설정돼 있지 않으므로 임의 기준을 주장하지 않음.
- 테스트를 추가하지 않는 경우: getter·단순 위임·매핑, 프레임워크 동작, 이미 다른 테스트가 같은 불변식을 검증하는 분기, 구현 세부(호출 순서·SQL 문자열·mock 호출 횟수)에 묶인 검증. 리팩터링은 기존 테스트가 동작 보존을 이미 검증하면 테스트를 늘리지 않고 그대로 통과시킴.
- 테스트 하나는 하나의 동작·불변식을 검증하고 이름에 그 의미를 드러냄. 같은 검증을 중복하는 테스트와 중복 fixture는 합치거나 삭제하며, 동작이 바뀌어 의미를 잃은 테스트는 수정이 아니라 삭제 여부를 먼저 판단. 변경 요약에는 추가·삭제한 테스트와 그 이유를 함께 기록.
- 성능 변경은 workload·dataset·자원·측정 지표·반복 횟수·제한을 기록. 요청 경로 또는 시스템 부하를 측정하는 실험은 필요한 관측 지표와 결과 산출물을 함께 보관.
- 실행하지 않은 테스트·성능 결과는 통과한 것처럼 작성하지 말고, 실행 명령·결과·제한을 Issue/PR에 기록.

## 문서와 Git 작업 흐름

- 문서는 한국어로 작성; 코드 식별자와 일반적으로 통용되는 기술 용어는 원문 유지. GitHub Flavored Markdown, 상대 링크, 간결한 표·목록·Mermaid 사용.
- 복잡한 도메인·상태 전이는 Issue/PR 본문에서 사실·가정·대안·영향을 설명. 문서 링크나 ID만으로 설명을 대체하지 않음.
- PR·commit 제목은 `<type>(<scope>): <한국어 요약>` 형식. Issue 제목은 해당 Issue template의 한국어 분류를 사용. PR checklist와 Issue/추적 ID 표기는 템플릿 및 `.github/workflows/docs-lint.yml`의 parser 계약을 따름.
- 관련 Issue와 commit을 하나의 목적에 맞는 PR로 묶음. 독립 목적·위험·검증 기준일 때만 분리. 필수 CI와 추적 링크 확인.
- 작업 전 `git status`와 대상 파일을 확인하고 기존 사용자 변경을 보존. `main`에 직접 push하지 않음.
