# ADR-0002: 기술 선택과 공통 처리 재검토

- 상태: ✓ 검토 완료. [D-040](../../00-governance/decision-log.md)의 사용자 승인 stack 유지; migration 또는 production 승인 아님
- 날짜: 2026-11-02
- 담당: Project Owner
- 관련 ID: [FR-001/002/003, NFR-001/002](../../02-requirements/requirements-index.md), [D-040](../../00-governance/decision-log.md), [Q-005/Q-007/Q-008/Q-009](../../00-governance/assumptions-and-open-questions.md)
- 추적: [Issue #68 stack approval](https://github.com/Neo1228/ATLAS/issues/68#issuecomment-6566855773), [toolchain rationale](https://github.com/Neo1228/ATLAS/issues/68#issuecomment-6566950387), [Issue #78 re-review](https://github.com/Neo1228/ATLAS/issues/78)

## 배경

D-040에 version과 현재 stack은 기록됐지만 Java/Kotlin, Spring MVC/reactive, PostgreSQL/MySQL, Gradle/Maven 등 대안·선택 이유는 Issue #68 댓글과 여러 참고자료에 분산. 현재 코드에 사용된 선택도 전부 같은 수준으로 비교된 것은 아님.

이 문서는 근거의 빈틈과 한계를 드러내고, 이미 선택된 stack을 바꾸지 않은 이유를 기록. Version support는 호환성 근거이며 성능 우위의 증명 아님.

## 판단 기준

- 승인 범위: 단일 SKU Buyer flow, blocking JDBC, PostgreSQL, 한 Spring Boot deployment, Fake PSP.
- 사용자 선택과 적용 시점의 support/compatibility를 구분. 측정하지 않은 throughput, build speed, cloud cost를 추정하지 않음.
- Mature library는 비교 대상. 기존 capability로 충분하면 새 dependency 회피; 부족한 기능이 있고 운영·보안·license 비용이 납득되면 직접 재구현보다 우선.
- Production JDK vendor/license, deployment, workload/SLO, live PSP는 별도 결정 대상.

## 기술 대안 비교

| 선택                             | 대안                                                    | Atlas 기준·trade-off                                                                                                                                                                                                                                                                          | 근거와 한계                                                                                                                                                                                                                                                                                                                                               |
| ------------------------------ | ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Java 25 LTS                    | Java 21 LTS, Kotlin 2.2                               | Java는 사용자가 고른 language이며 현재 전체 source·tests가 Java. Java 25는 더 새 LTS line; 현재 code에서 Java 25 전용 language feature 요구는 확인되지 않음. Kotlin은 nullability type system과 concise syntax 장점, 대신 stdlib/reflection, Spring `all-open`, Jackson module 및 mixed-language convention 추가.                    | Oracle support horizon은 vendor별 일정 (REF-059). Spring Boot 4.1.2는 Kotlin을 공식 지원하나 추가 plugin/dependency 필요 (REF-076). Meta Android 전환은 custom toolchain/build 비용을 보여주는 사례이지 backend 비교가 아님 (REF-074). JetBrains/Xebia는 adoption guide이지 independent defect/productivity benchmark가 아님 (REF-075). 현재 Java를 유지; Kotlin은 요구가 생기면 신규 bounded slice에서만 재평가. |
| Spring Boot 4.1.2 + Spring MVC | Boot 3.5.16, Quarkus, Spring WebFlux                  | Boot 3.5.16도 Java 25를 지원; 4.1.2는 승인 시점의 최신 stable line이며 Spring Modulith 2.1.2와 version compatibility 확인. Quarkus는 cold-start/idle-memory objective가 있으면 비교 후보. 현재 code의 JDBC와 other blocking I/O에는 MVC가 자연스러운 fit; WebFlux는 non-blocking workload에서 resource 효율 이점이 있을 수 있으나 일반적 속도 향상 아님. | Boot 4/3.5 requirements (REF-060/083). Quarkus-hosted interview summary의 수치는 embedded-ML 단일 service/workload, AI-assisted summary이며 재현 가능한 Atlas benchmark가 아님 (REF-079). Spring Framework는 JDBC/JPA 사용 시 MVC를 권장하고 WebFlux가 대체로 더 빠르다고 보장하지 않음 (REF-082). Cloud startup, memory, p99는 Atlas에서 측정 안 됨.                                               |
| PostgreSQL 18.6                | PostgreSQL 17.11, MySQL, Redis-only reservation state | PostgreSQL 18 지원 종료는 2030-11; 17은 2029-11. Atlas code는 `pg_advisory_xact_lock`, row lock, PostgreSQL-specific migration/index semantics 사용. MySQL도 유효한 RDBMS alternative. Redis-only reservation은 order/ledger와 atomic boundary를 분리.                                                        | PostgreSQL release policy (REF-061). Shopify는 자체 workload에서 Redis를 MySQL로 옮긴 production 사례지만 PostgreSQL-vs-MySQL benchmark가 아니며 수치를 Atlas 목표로 전용하지 않음 (REF-070). Atlas DB performance comparison은 미실행.                                                                                                                                               |
| Gradle Wrapper 9.8.2           | Maven Wrapper 3.9.16                                  | 사용자가 Gradle을 선택. Wrapper + SHA pin으로 tool version 재현. Maven은 lifecycle/convention 예측성이 강점; Gradle은 flexible DSL/cache/parallel capability와 build-logic 복잡도 trade-off. Atlas build는 작은 단일 `build.gradle`; build speed 우위 실측 없음.                                                              | Boot 4.1.2는 Maven 3.6.3+와 Gradle 8.14+/9.x 모두 지원 (REF-060). Gradle/Maven releases (REF-062/086). Practitioner comparison은 의견이며 controlled benchmark 아님 (REF-078). Existing Gradle을 taste만으로 전환하지 않음.                                                                                                                                                 |
| Spring `JdbcTemplate` + Flyway | Spring Data JPA/JDBC, ORM schema generation           | Current invariants에 명시적 SQL, guarded update, locks, unique/check constraints와 migration ordering 필요. `JdbcTemplate`로 SQL·transaction을 직접 통제; JPA도 native SQL/locking을 지원하지만 mapping/state model을 추가해도 핵심 SQL은 남음. Flyway는 versioned DDL을 관리.                                                | Spring documents `JdbcTemplate` as its classic JDBC approach and offers `NamedParameterJdbcTemplate` as an option (REF-073); JPA locking is viable but not automatically a fit (REF-072). Boot 4 Flyway uses `spring-boot-starter-flyway` (REF-066/067). No ORM/library migration benefit is measured.                                               |
| PostgreSQL integration tests   | H2/embedded DB                                        | Testcontainers는 production target이 아니라 현재 PostgreSQL engine semantics를 검사하는 test dependency. H2는 `pg_advisory_xact_lock` 및 PostgreSQL behavior를 대체하지 않음.                                                                                                                                    | Testcontainers PostgreSQL module (REF-065), PostgreSQL locking docs (REF-056/057).                                                                                                                                                                                                                                                                   |

## 공통 처리 기준

- **HTTP errors:** Spring `ProblemDetail` / RFC 9457 사용. Standard `type/title/status/detail`과 machine-readable `code` extension. Root `AtlasApiExceptionHandler`가 module not-found/conflict, request validation/framework errors와 unexpected 500을 공통 처리. Internal 500 detail은 client에 노출하지 않음.
- **Exception ownership:** Campaign, Admission, Order는 자체 not-found exception; conflict·domain exception도 owner module에 유지. Controller별 `ResponseStatusException` mapping 금지; API error serialization은 root advice 소유.
- **Validation:** HTTP request syntax는 Jakarta Bean Validation. Module service는 다른 내부 caller도 있으므로 domain invariant를 다시 확인. Controller/service validation은 같은 계층의 duplicate copy가 아니라 transport boundary와 domain boundary의 책임.
- **Read/write:** `AdmissionApi.find()`는 persisted state만 읽음. GET/OrderView 조립에서 만료·promotion을 실행하지 않음. 만료는 scheduler/명령이 처리하며, sweep 전 response status는 마지막 commit 상태라 잠시 늦을 수 있음. 근거: CQS (REF-080), HTTP GET safe semantics (REF-081).
- **Transactions:** 짧은 invariant transaction. `@Transactional`은 기본 module operation, `TransactionTemplate`은 명시 transaction block이 필요한 Order/Inventory/Payment flow. 동일 datasource/manager와 기본 `REQUIRED` propagation에서 cross-module JDBC operation은 caller transaction에 참여 가능. PSP network call은 transaction 밖. Remote module/external service로 분리될 때 같은 atomicity가 있다고 가정 금지.
- **Time/scheduling:** `Clock`는 root configuration에서 주입, tests use controllable clock. Scheduler trigger는 module `.internal`; scheduler와 command operation이 expiry mutator.
- **Shared package:** root의 exception handler와 Clock configuration만 application composition concern. 현재 stable shared domain utility나 여러 구현체 없음. Generic `common` module 생성은 보류.
- **보안·운영:** Authentication/Tenant isolation, metrics/tracing/alerts, production backup/deployment/PSP recovery는 미승인/OPEN. “보통 필요”라는 이유만으로 runtime dependency를 추가하지 않음; approval과 capability/operations boundary 필요.

## 결정·재검토 조건

- D-040의 Java 25 + Spring Boot 4.1.2/MVC + PostgreSQL 18.6 + Gradle 9.8.2 stack 유지. Runtime language/framework/database migration 없음.
- This review adds comparison rationale only; no production capacity or framework performance claim.
- Kotlin: explicit user/skill objective와 bounded pilot 결과가 있을 때. Quarkus/reactive stack: approved deployment/workload에서 startup, memory, tail latency가 요구될 때 same-workload pilot. Database/build changes: approved benchmark or compatibility need. Review must record source bias, migration cost, rollback plan, and measurement methodology.
- Current stack and support horizon changes: create a new ADR; do not retro-edit D-040/ADR-0001.

## 참고 자료

[REF-059~086](../../00-governance/reference-register.md)에는 versioned support facts, practitioner migration/benchmark cases, source bias와 Atlas applicability를 구분해 기록.
