# ADR-0004: JPA CRUD와 PostgreSQL 특화 SQL 경계

- 상태: ✓ Accepted
- 관련 결정: [D-048](../../00-governance/decision-log.md)
- 상위 결정: [ADR-0003](ADR-0003-gradle-module-boundaries.md), [ADR-0002](ADR-0002-runtime-technology-review.md)

## 배경

초기 persistence는 모든 CRUD와 동시성 SQL을 `JdbcTemplate`로 구현했다. 계층 경계는 분명해졌지만 단순 CRUD에도 SQL과 row mapping을 반복했다. 사용자는 다섯 도메인 모두 기본 CRUD에 JPA를 사용하고, 동시성 이점을 잃는 구간은 명시 SQL을 유지하도록 결정했다.

## 결정

일반 CRUD는 각 모듈의 JPA entity와 Spring Data `JpaRepository`로 처리한다. Entity는 `.persistence.entity`, repository는 module-private `.repository`에 둔다. 별도 mapper·generic base·custom ORM은 만들지 않는다.
- DB schema는 `application-module`의 Flyway migrations가 단독 소유한다. Hibernate `ddl-auto=validate`; schema 자동 생성/변경 금지.
- Entity 간 module relation 대신 ID를 저장하고, 다른 module과의 연동은 공개 `.service` contract와 `.dto`로 수행한다.
- PostgreSQL lock·atomic update·sequence allocation이 필요한 경로만 module-private `.repository` 안에서 `JdbcTemplate` 또는 JPA native query로 처리한다. CRUD SQL 반복 구현은 금지.
- Service implementation이 transaction과 scheduled workflow를 소유한다. Payment provider 호출은 transaction 밖에서 유지한다.

## 결과와 한계

- CRUD SQL과 수동 row mapping을 줄이고 JPA entity/repository를 도메인 소유 package 안에 둔다.
- ORM dependency와 entity mapping 비용이 추가된다. JPA 적용 자체는 성능 개선의 증거가 아니다.
- PostgreSQL 전용 동시성 SQL은 database-specific하며, transaction/lock 경계를 바꾸는 batch 최적화는 측정 근거와 별도 결정 전 추가하지 않는다.
- PostgreSQL/Testcontainers 통합 테스트에서 `ddl-auto=validate` 및 현재 JPA mapping 검증 통과. 이는 local schema 검증이며 production database, isolation, operation/performance 보장은 아님.

## 근거

- Spring Data JPA, [Defining Repository Interfaces](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html): typed repository가 CRUD 기능을 제공하는 공식 계약 (REF-097).
- Spring Framework, [JDBC access styles](https://docs.spring.io/spring-framework/reference/data-access/jdbc/choose-style.html), PostgreSQL [locking](https://www.postgresql.org/docs/18/explicit-locking.html)·[isolation](https://www.postgresql.org/docs/18/transaction-iso.html): 특화 SQL·transaction semantics 근거 (REF-056/057/073).
- Andrei Roșca, [Spring Data JPA locking trade-offs](https://softice.dev/posts/optimistic_and_pessimistic_locking_with_spring_data_jpa/): JPA lock 기능 사례이지 ATLAS 성능 증명 아님 (REF-072).
- 少个分号, [Architecture tests](https://shaogefenhao.com/posts/architecture/arch-unit.html): 계층과 business module 경계를 구분하는 개인 경험. 모든 package 층 도입 근거로 일반화하지 않음 (REF-095).
