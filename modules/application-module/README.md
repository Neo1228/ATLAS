# application-module

단일 Spring Boot deployment의 composition root. Domain modules는 JAR 단위지만 독립 서비스·배포 단위는 아님.

- 진입점: `com.atlas.AtlasApplication`
- HTTP: `.application.controller.<domain>`; validated immutable request records `CreateCampaignHttpRequest`·`JoinQueueHttpRequest`: `.application.controller.dto`
- 공통 설정·HTTP error mapping·Flyway migrations: `.application.config.ClockConfiguration`, `.application.exception.GlobalExceptionHandler`, resources migrations
- 도메인 의존은 직접 하위 `.service`/`.dto`/`.exception` contract만 사용; persistence implementation 직접 참조 금지.
- JPA는 CRUD mapping, `ddl-auto=validate`; schema 변경은 Flyway만 담당.
- 실행: `./gradlew :application-module:bootRun`; 전체 검증: `./gradlew check`
