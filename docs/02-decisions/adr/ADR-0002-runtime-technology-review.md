# ADR-0002: 기술 선택과 공통 처리 재검토

- 상태: ✓ 사용자 승인 stack 유지. migration·운영 승인은 아님 ([D-040](../../00-governance/decision-log.md)).
- 날짜: 2026-11-02
- 담당: 프로젝트 책임자
- 관련 기준: [FR-001/002/003, NFR-001/002](../../01-requirements/requirements-index.md), [Q-005/Q-007/Q-008/Q-009](../../01-requirements/requirements-index.md#assumptions-and-open-questions)
- 추적: [Issue #68 stack 승인](https://github.com/Neo1228/ATLAS/issues/68#issuecomment-6566855773), [기술 선택 근거](https://github.com/Neo1228/ATLAS/issues/68#issuecomment-6566950387), [Issue #78 재검토](https://github.com/Neo1228/ATLAS/issues/78)

## 배경

- D-040에는 버전과 현재 stack만 기록. Java/Kotlin, Spring MVC/reactive, PostgreSQL/MySQL, Gradle/Maven 비교 근거는 Issue #68과 참고자료에 흩어져 있었습니다.
- 이 ADR은 이미 선택한 stack을 바꾸지 않은 이유와 근거의 한계를 정리합니다. 지원 버전은 호환성 근거이지 성능 우위의 증거가 아닙니다.

## 판단 기준

- 검토 범위: JDK, runtime framework, database, Gradle/build, persistence interface. 현재 선택 버전은 [D-040](../../00-governance/decision-log.md)·[Q-009](../../01-requirements/requirements-index.md)에 기록.
- 호환성과 지원 기간은 기술 선택의 근거이지 성능 우위의 증거가 아님. 직접 측정하지 않은 처리량·build 시간은 주장하지 않음.
- 기존 library·API도 비교. 요구 기능·호환성·보안·라이선스에 맞는 성숙한 library가 있으면 직접 구현보다 우선.

## 근거와 대안

| 선택 | 검토한 대안과 판단 | 근거·한계 |
|---|---|---|
| Java 25 LTS | Java는 사용자가 선택했고 전체 source/test가 Java입니다. 현재 Java 25 전용 문법 요구는 없습니다. Kotlin은 nullability와 간결한 문법이 장점이나 stdlib/reflection, Spring `all-open`, Jackson module, 혼합 언어 규칙을 추가합니다. Java 유지; Kotlin은 새 기능 요구가 있을 때 제한된 pilot으로 재검토. | Oracle 지원 기간은 vendor별 차이 (REF-059). Spring Boot의 Kotlin 지원은 추가 plugin/dependency 필요 (REF-076). Android 전환·도입 안내 자료는 backend 생산성·결함률 비교가 아님 (REF-074/075). |
| Spring Boot 4.1.2 + MVC | Boot 3.5.16도 Java 25 지원. 현재 JDBC/JPA 같은 blocking I/O에는 MVC를 사용합니다. Quarkus는 cold-start·idle-memory 목표가 생기면, WebFlux는 non-blocking 부하가 확인되면 같은 workload로 비교합니다. | 지원 정보 (REF-060/082/083). Quarkus 단일 embedded-ML service 비교는 재현 가능한 ATLAS benchmark가 아님 (REF-079). Startup·memory·p99 실측 없음. |
| PostgreSQL 18.6 | PostgreSQL 17.11·MySQL도 가능한 RDBMS입니다. 현재 코드는 advisory/row lock과 PostgreSQL 전용 migration/index 의미를 사용합니다. Redis만으로 예약 상태를 관리하면 주문·원장과 원자 경계가 갈라집니다. | 버전 지원 정책 (REF-061). Shopify Redis→MySQL 사례는 자체 workload이며 PostgreSQL 비교나 ATLAS 목표가 아님 (REF-070). ATLAS DB 성능 비교 미실행. |
| Gradle Wrapper 9.8.2 | Gradle은 사용자 선택. Wrapper와 SHA pin으로 버전 고정. Maven은 lifecycle·convention 예측성이 장점; Gradle은 DSL·cache·병렬화 유연성과 build-logic 복잡도를 함께 고려. 기존 build는 작고, 속도 비교는 없음. | 두 도구 모두 Boot 4.1.2 지원 (REF-060/062/086). 비교 글은 통제 실험이 아님 (REF-078). 취향만으로 전환하지 않음. |
| JPA CRUD + PostgreSQL 전용 SQL | 초기에는 모든 persistence를 SQL로 처리했습니다. 일반 CRUD에 JPA를 적용하기로 후속 결정 ([ADR-0004](ADR-0004-jpa-and-sql-boundary.md)). PostgreSQL lock·atomic SQL은 JDBC/native query로 유지. Flyway가 DDL을 관리. | Spring JDBC·JPA lock 자료 (REF-056/057/072/073/097). 성능 우위 실측 없음. |
| PostgreSQL 통합 test | Testcontainers로 실제 PostgreSQL 동작을 확인. H2/embedded DB는 `pg_advisory_xact_lock` 등 PostgreSQL 고유 동작을 대체하지 않음. | Testcontainers 및 PostgreSQL 문서 (REF-056/057/065). 운영 환경 검증은 아님. |

## 공통 처리 기준

- **HTTP 오류:** Spring `ProblemDetail` / RFC 9457 사용. 표준 `type/title/status/detail`과 기계 판독용 `code`를 반환. `application-module`의 `GlobalExceptionHandler`가 module not-found/conflict, request validation/framework 오류, 예상하지 못한 500을 처리. 내부 500 상세는 client에 노출하지 않음.
- **오류 소유권:** 각 도메인은 reason code를 가진 typed exception 하나를 제공하고, `application-module`이 code를 HTTP 응답으로 변환. `IllegalArgumentException`은 client 계약으로 쓰지 않음. Controller별 `ResponseStatusException` mapping 금지.
- **검증:** HTTP request 형식은 Jakarta Bean Validation. Module service도 다른 caller를 위해 도메인 불변조건 검증. Controller는 transport 경계, service는 domain 경계를 담당.
- **조회·변경:** `AdmissionService.find()`는 저장된 상태만 읽음. GET/`OrderResponse` 조립 중 만료·승격·DB 쓰기 금지. 만료는 scheduler 또는 명시적 상태 변경에서 처리하므로 sweep 전 응답은 마지막 commit 상태일 수 있음 (REF-080/081).
- **Transaction:** 불변조건에 필요한 짧은 transaction 사용. `@Transactional`은 일반 module operation, `TransactionTemplate`은 Order/Inventory/Payment 흐름의 명시적 transaction block에 사용. 같은 datasource/manager와 기본 `REQUIRED` 전파에서는 모듈 JDBC 호출이 caller transaction에 참여 가능. PSP 호출은 transaction 밖. 원격 service로 분리한 뒤에도 같은 원자성이 유지된다고 가정 금지.
- **시간·스케줄:** root configuration에서 `Clock` 주입; test는 조정 가능한 clock 사용. Scheduler는 각 모듈 `.service`에서 만료 처리.
- **공유 package:** root에는 공통 예외 처리와 Clock 설정만 둠. 공통 domain utility나 여러 구현체가 없어 `common` module 생성은 보류.
- **보안·운영:** Authentication/Tenant 격리, metrics/tracing/alerts, 운영 backup/deployment/PSP 복구 미승인·미정. “보통 필요”하다는 이유만으로 runtime dependency 추가 금지; 승인된 기능과 운영 경계 필요.

## 결정

- D-040의 Java 25 + Spring Boot 4.1.2/MVC + PostgreSQL 18.6 + Gradle 9.8.2 유지. Runtime language/framework/database 전환 없음. CRUD persistence 범위는 ADR-0004에서 JPA로 결정.
- 이 재검토는 비교 근거를 보완할 뿐, 운영 처리 용량이나 framework 성능을 주장하지 않음.
- Kotlin은 명시된 사용자·역량 목표와 제한된 pilot 결과가 있을 때 검토. Quarkus/reactive는 승인 workload에서 startup·memory·tail latency 요구가 생기면 같은 조건으로 시험. Database/build 변경은 승인된 benchmark 또는 호환성 필요가 있을 때 검토.
- Stack 또는 지원 기간이 바뀌면 새 ADR 작성. D-040/ADR-0001의 과거 기록은 소급 수정하지 않음.

## 참고 자료

[REF-059~086](../../00-governance/reference-register.md)에서 버전 지원 정보, 실무자 migration/benchmark 사례, 출처 편향, ATLAS 적용 범위를 구분합니다.
