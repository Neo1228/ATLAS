# EXP-025 — Java 기반 slow-query 감지

- 상태: 코드 경로·통합 검증 완료 · 운영 threshold와 proxy overhead는 미측정
- 관련 작업: [Issue #251](https://github.com/Neo1228/ATLAS/issues/251), [EXP-024](EXP-024-postgres-contention-observation.md)

## 목적과 선택

현재 application에는 JDBC statement elapsed-time 감지가 없음. YAML·PostgreSQL 설정이 아닌 Spring DI 경계에서 code-managed 감지 기능을 검증.

- [datasource-proxy 1.10.1](https://central.sonatype.com/artifact/net.ttddyy/datasource-proxy/1.10.1) 사용; JDBC proxy 구현을 직접 만들지 않음.
- [`SlowQueryDataSourceConfiguration`](../../ATLAS-application/src/main/java/com/atlas/application/config/SlowQueryDataSourceConfiguration.java)이 auto-configured `DataSource`를 선택적으로 감쌈.
- `ATLAS_DB_SLOW_QUERY_THRESHOLD_MS`가 없으면 기존 `DataSource` 유지. 양의 정수만 허용.
- `afterQuery` callback에서 실제 JDBC elapsed time이 threshold 이상이면 WARN 기록. SQL template만 출력하고 bind parameter 값은 출력하지 않음.

## 측정 조건

| 항목 | 값 |
|---|---|
| DB | PostgreSQL 18.6 Testcontainers |
| Detector threshold | 200 ms · 통합 테스트 전용 |
| Fast query | `SELECT 1` |
| Slow query | Prepared statement `SELECT pg_sleep(?)`, bind value `0.4` seconds |
| Logger | `WARN` · `slow_sql datasource=... elapsed_ms=... success=... statement=...` |

200 ms와 0.4 s는 logger 분기 및 bind-value 미노출을 검증하기 위한 synthetic 값. 서비스 latency 목표나 운영 threshold가 아님.

## 결과

- Slow-query test: fast query는 slow log 미발생, threshold 초과 prepared query는 WARN 1건.
- Event에 SQL template와 elapsed 필드 포함; `0.4` bind value 미포함.
- 실제 Spring context에서 threshold 환경 변수 적용 시 `ProxyDataSource` 확인.
- `DataSource.unwrap(HikariDataSource.class)` 성공 및 `hikaricp.connections.active` meter 등록 확인.
- threshold 미설정 시 raw `DataSource` 유지; `0`, 음수, 비정수 입력은 startup 오류.
- 전체 검증: `./gradlew --no-daemon clean check :application-module:bootJar` 성공 · 58 test suites · 277 tests · failure/error/skip 0.

## 해석과 한계

- 기록 시점은 SQL 완료 후. 종료되지 않는 query는 감지하지 못함.
- elapsed는 JDBC 호출의 client-side 경과 시간으로 DB server execution time만을 의미하지 않음.
- SQL template의 bind value는 제외. SQL literal을 statement text에 직접 넣으면 literal은 로그에 남을 수 있음.
- Proxy overhead는 별도 A/B workload로 측정하지 않음. 기본 동작은 threshold 미설정 상태이며, clean performance baseline에서는 detector를 켜지 않음.
- datasource-proxy의 active `SlowQueryListener`는 실행 중 query를 threshold 시점에 감지하지만 query별 scheduled check를 추가. 현재는 실제 elapsed를 제공하고 scheduler overhead가 없는 after-execution callback 선택.
- [datasource-proxy guide](https://jdbc-observations.github.io/datasource-proxy/docs/current/user-guide/index.html#slow-query-logging-listener)는 두 방식의 감지 시점·elapsed·overhead를 비교. [pgDash slow-query guide](https://pgdash.io/blog/slow-queries-postgres.html)는 `pg_stat_statements`의 누적 통계와 개별 slow-query log의 차이를 설명.
