# ADR-0028: HTTP 오류 상관관계 ID와 공통 로그

- 상태: ✓ 확정
- 결정일: 2026-11-10
- 결정 ID: [D-046](../../00-governance/decision-log.md#detailed-decision-records)
- 관련 Issue: [Issue #78](https://github.com/Neo1228/ATLAS/issues/78)

## 배경

- `GlobalExceptionHandler`의 모듈 예외 공통 `ProblemDetail` 변환과 예상하지 못한 예외의 오류 로그 기록 구조.
- 클라이언트 응답과 서버 로그 간 공통 요청 식별자 부재로 인한 5xx 상관관계 확인 난점.
- 호출자 제공 ID를 그대로 신뢰할 때의 로그 식별자 위조·충돌 가능성.

## 결정

- Servlet 요청별 서버 생성 UUID `requestId`를 canonical 식별자로 사용. 외부 `X-Request-ID`는 신뢰 식별자에서 제외.
- 요청 처리 동안 `requestId`를 MDC에 설정하고, response `X-Request-ID` header와 공통 `ProblemDetail.requestId`에 같은 값 기록. 요청 종료 시 MDC 정리.
- 모듈별 비즈니스 예외의 stable `code`/HTTP status 변환과 기본 ERROR 로그 기록 0건. 예상하지 못한 예외의 ERROR·stack trace 기록 1회와 서버 생성 `requestId` 포함.
- Slow SQL 로그에 HTTP 요청 context의 MDC `requestId`, statement count, SHA-256 query fingerprint 포함. SQL text와 prepared statement bind value 기록 0건. Detector 비활성 상태 startup INFO 기록.
- 로그는 한국어 설명을 사용하되 예외 클래스, 상태·오류 코드, HTTP method/path와 도메인 식별자는 원문 유지. credentials, authorization header, raw body, 개인정보 및 결제정보 기록 금지.

## 근거와 대안

- 기존 Spring MVC servlet filter와 SLF4J MDC 활용. 새 dependency, request-scoped 저장소, tracing backend 추가 0건.
- 사용자 제공 ID의 canonical 채택은 upstream 검증과 trust boundary 요구로 제외.
- 모든 도메인 예외의 ERROR 기록은 예상 가능한 4xx 충돌의 오류 경보 오염과 중복 로그 유발로 제외.

MDC context 전파 범위는 동기 HTTP 요청. 비동기 executor 또는 provider 작업의 context 전파 필요성은 실측·검증 뒤 별도 결정. 분산 tracing과 새 logging framework는 현재 범위 외.

## 검증

- Response header와 `ProblemDetail` body의 request ID 일치.
- 외부 제공 임의 request ID 미사용.
- 예외 처리 후 thread-local MDC 잔류 0건.
- Slow SQL log의 `requestId`는 `RequestCorrelationFilter.REQUEST_ID_MDC_KEY` MDC 값 사용. `SlowQueryDataSourceConfigurationTest`는 MDC 값 기록만 검증하며 filter 경유 HTTP 요청 end-to-end 연결은 미검증. HTTP 외 실행의 request ID는 미해당.
- 기존 domain exception status/code 계약 유지.
- 예상하지 못한 오류의 응답 내부 상세 비노출과 server-side stack trace·request ID 기록 1회.
- 구현: [RequestCorrelationFilter](../../../ATLAS-application/src/main/java/com/atlas/application/filter/RequestCorrelationFilter.java) · [GlobalExceptionHandler](../../../ATLAS-application/src/main/java/com/atlas/application/exception/GlobalExceptionHandler.java) · [SlowQueryDataSourceConfiguration](../../../ATLAS-application/src/main/java/com/atlas/application/config/SlowQueryDataSourceConfiguration.java)
- 검증 코드: [RequestCorrelationFilterTest](../../../ATLAS-application/src/test/java/com/atlas/application/exception/RequestCorrelationFilterTest.java) · [GlobalExceptionHandlerTest](../../../ATLAS-application/src/test/java/com/atlas/application/exception/GlobalExceptionHandlerTest.java) · [SlowQueryDataSourceConfigurationTest](../../../ATLAS-application/src/test/java/com/atlas/application/config/SlowQueryDataSourceConfigurationTest.java)
