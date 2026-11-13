# EXP-025 — Java 기반 slow-query 감지

- 상태: Code path·Testcontainers 검증 완료 · production threshold·proxy overhead 미측정
- 관련 작업: [Issue #251](https://github.com/Neo1228/ATLAS/issues/251), [EXP-024](EXP-024-postgres-contention-observation.md)

## 목적과 선택

- 현재 application의 JDBC statement elapsed-time 감지 기능 부재.
- YAML·PostgreSQL 설정 대신 Spring DI 경계의 code-managed detector 검증.
- [datasource-proxy 1.10.1](https://central.sonatype.com/artifact/net.ttddyy/datasource-proxy/1.10.1) 사용; 자체 JDBC proxy 구현 회피.
- [`SlowQueryDataSourceConfiguration`](../../ATLAS-application/src/main/java/com/atlas/application/config/SlowQueryDataSourceConfiguration.java)의 auto-configured `DataSource` 선택적 wrapping.
- `ATLAS_DB_SLOW_QUERY_THRESHOLD_MS` 미설정 시 기존 `DataSource` 유지와 detector 비활성 startup INFO 기록. 설정값은 양의 정수만 허용.
- `afterQuery` callback에서 threshold 이상 JDBC elapsed time에 WARN 기록. HTTP request의 `requestId`, statement count, SHA-256 query fingerprint 포함. SQL text·bind value 기록 0건.

## 측정 조건

| 항목 | 값 |
|---|---|
| DB | PostgreSQL 18.6 Testcontainers |
| Detector threshold | 200 ms · 통합 테스트 전용 |
| Fast query | `SELECT 1` |
| Slow query | Prepared statement `SELECT pg_sleep(?)`, bind value `0.4` seconds |
| Logger | `WARN` · `slow_sql requestId=... datasource=... elapsed_ms=... success=... statement_count=... statement_fingerprint=...` |

200 ms와 0.4 s는 logger 분기와 bind-value 미노출 검증용 synthetic values. Service latency target·운영 threshold와 무관.

## 결과

- Fast query의 slow log 0건, threshold 초과 prepared query의 WARN 1건.
- 현재 revision의 focused test: `./gradlew --no-daemon --max-workers=1 :application-module:test --tests com.atlas.application.config.SlowQueryDataSourceConfigurationTest` 성공 · 4 tests · failure/error 0.
- Event에 requestId·elapsed·statement count·SHA-256 statement fingerprint 포함. SQL template와 `0.4` bind value 기록 0건.
- Threshold 미설정 startup log에 detector 비활성 상태 포함.
- 실제 Spring context에서 threshold 환경 변수 적용 시 `ProxyDataSource` 확인.
- `DataSource.unwrap(HikariDataSource.class)` 성공과 `hikaricp.connections.active` meter 등록 확인.
- Threshold 미설정 시 raw `DataSource` 유지; `0`, 음수, 비정수 입력은 startup 오류.
- 현재 revision 전체 검증: `./gradlew --no-daemon --max-workers=1 check :application-module:bootJar` 성공 · failure/error 0.

## 해석과 한계

- 기록 시점은 SQL 완료 후. 완료 전 query 탐지 범위 외.
- elapsed는 JDBC 호출의 client-side 경과 시간이며 DB server execution time과 구분.
- Query fingerprint는 전체 SQL text의 SHA-256. Prepared statement placeholder 문장은 bind value와 무관하게 같은 fingerprint, SQL text에 직접 넣은 literal은 값마다 다른 fingerprint. SQL text 복원 기능 미지원.
- 알려진 SQL template과 낮은 entropy의 inline literal 조합은 대입 추측으로 literal 복원 가능성 존재. Application SQL의 bind parameter 사용 전제.
- Proxy overhead 별도 A/B workload 미측정. 기본 동작은 threshold 미설정이며 clean performance baseline의 detector 비활성.
- datasource-proxy의 active `SlowQueryListener`는 실행 중 query를 threshold 시점에 감지하지만 query별 scheduled check 추가. 현재는 실제 elapsed 제공과 scheduler overhead가 없는 after-execution callback 선택.
- [datasource-proxy guide](https://jdbc-observations.github.io/datasource-proxy/docs/current/user-guide/index.html#slow-query-logging-listener)는 두 방식의 감지 시점·elapsed·overhead 비교. [pgDash slow-query guide](https://pgdash.io/blog/slow-queries-postgres.html)는 `pg_stat_statements`의 누적 통계와 개별 slow-query log의 차이 참고.
