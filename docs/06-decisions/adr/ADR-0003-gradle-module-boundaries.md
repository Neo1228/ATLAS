# ADR-0003: 도메인 모듈과 계층 경계
- 상태: ✓ Accepted
- 날짜: 2026-11-02
- 담당자: Project Owner
- 관련 결정: [D-037/D-038/D-047](../../00-governance/decision-log.md)

## 배경

ADR-0001에서 현재 Buyer 흐름의 Campaign, Admission, Inventory, Order/Checkout, Payment 경계와 단일 배포형 modular monolith를 결정했다. 기존 코드는 하나의 Gradle project에 모여 있어 의존 경계가 build에서 보장되지 않았고, 네 도메인 service가 SQL·row mapping까지 맡고 있었다. 사용자는 도메인별 `-module`, 명시적 계층, CQRS와 `internal` package 금지를 요구했다.

## 선택지

| 선택 | 절충 |
|---|---|
| 패키지만 분리 | 시작은 단순하나 모듈 간 compile dependency를 막지 못함 |
| 도메인별 Gradle module + 단일 실행 module — 선택 | build 의존과 소유권이 드러남. 설정과 모듈 간 API 연결이 필요하지만 운영 배포는 하나로 유지 |
| 도메인별 독립 서비스 | 독립 배포·데이터 소유 근거가 없음. 네트워크 장애와 분산 transaction까지 앞당겨 도입 |

## 결정

기존 다섯 business capability를 각각 Gradle subproject로 두고 `application-module`에서 조립한다. 모듈 추가·독립 배포를 뜻하지 않는다.

| Module | 소유 책임 | Gradle 의존 |
|---|---|---|
| `application-module` | Spring Boot, HTTP adapters, root error mapping, configuration, Flyway migrations | 다섯 도메인 모듈 |
| `campaign-module` | Campaign과 SKU eligibility | `inventory-module` |
| `admission-module` | Queue ticket·순번·idempotency·checkout slot·expiry | `campaign-module` |
| `inventory-module` | Inventory pool/reservation 및 conservation | 없음 |
| `order-module` | Checkout·order lifecycle orchestration | `admission-module`, `inventory-module`, `payment-module` |
| `payment-module` | Payment attempts·상태·provider 결과 | 없음 |

### 계층과 데이터 접근

- Public contracts: `.service`, `.dto.request`, `.dto.response`, `.exception`. Lifecycle enums live in `.persistence.state` and are the only persistence-nested types intentionally shared where module APIs need the same vocabulary; JPA entities and repositories remain module-private. Each module uses one `<Domain>Exception` with a `Code` enum rather than a class per failure. Scheduled expiry stays in cohesive `.service.impl`; ArchUnit verifies package dependencies without `package-info.java` markers.
- Cross-module calls use service contracts, request/response records, and explicitly allowed lifecycle enums only. Never access another module's entity/repository or write its tables; entity associations use scalar IDs. Entity class names express the persisted concept (`Campaign`, `QueueTicket`, `PurchaseOrder`, `PaymentAttempt`) without a redundant `Entity` suffix.
- Lifecycle enums use `EnumType.STRING` and matching database `CHECK` constraints; ordinal persistence is not used.
- CRUD는 Spring Data JPA. Generic repository base, 별도 mapper layer, 복제 CRUD SQL은 만들지 않는다. Flyway가 schema를 소유하고 Hibernate `ddl-auto=validate`만 수행한다.
- PostgreSQL 특화 경합 경로만 `.persistence.repository` 안에서 JDBC/native SQL로 유지한다: Admission counter lock·sequence·FIFO 처리, Inventory advisory lock·guarded quantity update, Order buyer lock, Payment initial `ON CONFLICT`. 세부 범위는 [ADR-0004](ADR-0004-jpa-and-sql-boundary.md).
- HTTP controller·transport request DTO·공통 설정·HTTP error mapping은 `application-module`의 `.application.controller.<domain>`, `.application.controller.dto`, `.application.config`, `.application.exception`에 둔다. Request DTO는 immutable record와 Jakarta validation을 사용하며, path/header 조합·초 단위 입력 변환처럼 domain request와 transport shape가 다를 때만 분리한다. 공통 HTTP 정책은 `GlobalExceptionHandler`; 공통 Clock wiring은 `ClockConfiguration`.
- Service transaction은 invariant 단위로 유지한다. Admission counter lock·sequence·ticket insert와 Inventory pool/reservation 전이는 한 transaction이다. Order transaction은 Admission·Inventory API 호출 경계를 포함한다. Payment는 PSP 호출 전 DB transaction을 종료하고 결과를 별도 저장한다.
- CQRS·CommandBus·command/query handler·mediator 패턴은 금지한다. 입력은 `*Request`, 흐름은 직접 호출하는 Service로 표현한다. 새 분리·패턴은 구체 요구와 ADR 승인 없이 추가하지 않는다.
- Tests exist to protect consumer-visible behavior, module boundaries, state transitions, error contracts, and database/concurrency invariants. Do not add implementation/wiring tests or duplicate the same risk at multiple layers; use the smallest faithful boundary, with real PostgreSQL for PostgreSQL-specific locking/SQL ([REF-099](../../00-governance/reference-register.md)).

## 결과와 재검토

- `application-module`만 실행·배포한다. Gradle dependency graph와 ArchUnit package rules가 module 방향·공개 contract를 검사한다; 각 module README가 소유권을 설명한다.
- 기존 다섯 capability 경계와 Buyer 흐름을 유지한다. `application-module`은 실행·HTTP·migration 조립, 각 domain module은 JPA CRUD와 필요한 DB 특화 repository만 소유한다.
- Payment phase는 `PaymentPhase`로 명명한다. 기존 V4 migration checksum을 바꾸지 않고 V5에서 `operation` column/constraint를 `payment_phase`로 rename한다.
- 다섯 capability 경계는 ADR-0001의 승인 범위 그대로다. 독립 team/release/data ownership 또는 측정된 운영·성능 요구가 생길 때만 module 수를 재검토한다.
- JPA와 PostgreSQL SQL의 범위·절충은 ADR-0004에 기록. 독립 서비스 추출·추가 persistence framework는 근거와 사용자 승인 없이 진행하지 않는다.

## 근거

- Google Cloud, [Promote modular design](https://docs.cloud.google.com/architecture/framework/performance-optimization/promote-modular-design): 명확한 경계와 modularity 비용 균형 (REF-087).
- Gradle 9.8.2, [Multi-Project Builds](https://docs.gradle.org/9.8.2/userguide/multi_project_builds.html): subproject 의존과 이름 규칙 (REF-092).
- Alibaba COLA 작성자, [The Simplicity of COLA](https://www.alibabacloud.com/blog/the-simplicity-of-cola-an-alibaba-open-source-application-architecture_597234): CommandBus 의존 제거·직접 읽히는 흐름의 작성자 경험; 보편적 지침이나 ATLAS 선택의 유일한 근거는 아님 (REF-089).
- Meituan Engineering, [领域驱动设计在互联网业务开发中的实践](https://tech.meituan.com/2017/12/22/ddd-in-practice.html): 업무 의미 기반 capability 경계 사례; ATLAS 경계나 성능을 입증하지 않음 (REF-094).
- Spring JDBC 선택과 PostgreSQL locking 근거: [REF-056/057/073](../../00-governance/reference-register.md).
- 개인 실무자 참고: [REF-095](../../00-governance/reference-register.md)는 module boundary와 내부 계층을 별도 검토하고 복잡도에 맞춰 층을 줄이는 관점, [REF-096](../../00-governance/reference-register.md)는 결제 pending/unknown과 같은 attempt 재사용 사례. 둘 다 업계 표준의 증거가 아니라 참고 사례.
- ArchUnit, [User Guide](https://www.archunit.org/userguide/html/000_Index.html): bytecode-based package dependency rules without annotations or package metadata (REF-098). Rules describe only ATLAS's explicit allowlist.
