# ADR-0028: HTTP 오류 상관관계 ID와 공통 로그

- 상태: Accepted
- 날짜: 2026-11-10

## 맥락

애플리케이션은 `GlobalExceptionHandler`에서 모듈 예외를 공통 `ProblemDetail` 응답으로 변환하고, 예상하지 못한 예외만 오류 로그로 남긴다. 이 구조는 유지하되, 클라이언트 응답과 서버 로그 사이에 공유되는 요청 식별자가 없어 운영자가 보고된 5xx를 로그와 연결하기 어렵다. 호출자가 보낸 ID를 그대로 신뢰하면 로그 간 위조·충돌을 허용할 수 있다.

## 결정

- Servlet 요청마다 서버가 UUID 기반 `requestId`를 생성한다. 외부 `X-Request-ID` 값은 이 시스템의 신뢰된 식별자로 사용하지 않는다.
- 요청 동안 `requestId`를 MDC에 설정하고, 응답의 `X-Request-ID` 헤더와 공통 `ProblemDetail.requestId`에 동일한 값을 기록한다. 요청 종료 시 MDC 값을 정리한다.
- 모듈별 비즈니스 예외는 기존 공통 handler에서 stable `code`/HTTP status로 변환하고 기본적으로 ERROR 로그를 남기지 않는다. 예상하지 못한 예외만 한 번 ERROR와 stack trace로 기록하며, 로그에 서버 생성 `requestId`를 포함한다.
- 로그는 한국어 설명을 사용하되 예외 클래스, 상태·오류 코드, HTTP method/path와 도메인 식별자는 그대로 유지한다. credentials, authorization header, raw body, 개인정보 및 결제정보는 기록하지 않는다.

## 근거와 대안

Servlet filter와 SLF4J MDC는 이미 사용 중인 Spring MVC/SLF4J 기반을 활용하며 새 dependency, request-scoped 저장소 또는 tracing backend를 추가하지 않는다. 사용자 제공 ID를 canonical로 채택하는 대안은 upstream 검증과 trust boundary를 요구하므로 선택하지 않았다. 모든 도메인 예외를 ERROR로 기록하는 대안은 예상 가능한 4xx 비즈니스 충돌을 오류 경보로 부풀리고 중복 로깅을 유발하므로 사용하지 않는다.

MDC 범위는 동기 HTTP 요청이다. 별도 비동기 executor나 provider 작업으로 context 전파가 필요해지는 경우, 그 필요성을 실측·검증한 뒤 전파 방식을 별도로 결정한다. 현재는 분산 tracing/새 logging framework가 도입되지 않았으므로 이 변경을 해당 기능으로 주장하지 않는다.

## 검증 기준

- 같은 요청 ID가 response header와 `ProblemDetail` body에 나타난다.
- 외부에서 보낸 임의 request ID가 사용되지 않는다.
- 예외 이후 MDC 값이 thread에 남지 않는다.
- 기존 domain exception status/code 계약은 변하지 않는다.
- 예상하지 못한 오류는 응답에 내부 상세를 노출하지 않고 server-side stack trace와 request ID를 한 번 기록한다.
