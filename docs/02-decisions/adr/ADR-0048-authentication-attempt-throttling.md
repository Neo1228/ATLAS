# ADR-0048: 인증 시도 빈도를 PostgreSQL 고정 창 카운터로 제한한다

- 상태: Accepted — 구현 추적: [#413](https://github.com/Neo1228/ATLAS/issues/413)
- 날짜: 2026-11-20
- 관련 결정: [ADR-0037](ADR-0037-refresh-family-bound-access-token-sessions.md)(session·token 경계), [ADR-0047](ADR-0047-abandoned-approval-deadline.md)(외부 신호 없이 남는 상태를 닫는 같은 원칙)

## 맥락

인증 엔드포인트에는 요청 빈도 제한이 없었다. 애플리케이션에도, 리버스 프록시(`config/nginx`)에도 `limit_req`/`limit_conn`/버킷 구현이 없었다.

측정·코드로 확인한 사실:

- `AccountSecurityConfiguration`이 `DelegatingPasswordEncoder("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8())`를 쓴다. 실제 구성과 같은 인코더로 `matches` 1회를 10회씩 5라운드 측정한 결과 **47.7–58.3 ms, 중위 50.3 ms**(로컬 Apple Silicon, JDK 25, JIT warmup 3회 후)였다. 최초 기록은 단일 라운드 55.8 ms였으나, 반복 측정에서 편차가 커 범위와 중위값으로 정정한다. 절대값보다 **검증 1회가 수십 ms 규모**라는 사실이 판단 근거다.
- `AccountService.login`은 존재 여부를 감추기 위해 비밀번호를 **두 번** 검증한다. 로그인 1건이 최대 110 ms의 CPU를 쓴다.
- `/api/auth/login`, `/api/auth/refresh`, `/api/auth/signup` 등이 `permitAll()`이며, 이 경로에 시도 제한이 없다.
- `requestPasswordReset`/`requestEmailVerification`은 인증 없이 호출되며 매 호출마다 action token을 만들고 메일을 발송한다.
- `completeMfaLogin`은 챌린지 TTL 5분 동안 6자리 코드를 횟수 제한 없이 시도할 수 있고, 실패해도 챌린지를 소모하지 않는다.

즉 비밀번호 추측 방어를 PBKDF2 비용 하나에만 의존했다. 그 비용은 공격자에게도 같은 자원 소모이므로, 소수의 연결로 로그인 지연을 만들 수 있다.

## 결정

1. 두 축으로 나눈다.
   - **주체(subject) 축**: 정규화 이메일. 한 계정에 대한 비밀번호·MFA 코드 추측을 막는다.
   - **출처(origin) 축**: 클라이언트 주소. 한 출처가 여러 계정을 훑거나 메일을 대량 발송하는 것을 막는다.
2. `auth_subject_bucket`(subject_key, scope)와 `auth_origin_bucket`(origin_key, scope)에 고정 창 카운터를 둔다. 창이 바뀌면 같은 row를 새 창으로 덮어쓰므로 (주체 수 × 창 수)로 늘지 않고, row 수가 활성 사용자 수준에 머문다.
3. 키는 원문이 아니라 SHA-256 축약값을 저장한다. 카운터에 이메일·주소 원문을 남기지 않는다.
4. 한도 확인은 **비밀번호 검증 전에** 한다. 검증 뒤에 막으면 CPU 고갈을 그대로 허용한다.
5. 거절 응답은 기존 인증 실패(`INVALID_CREDENTIALS` → 401 `AUTHENTICATION_FAILED`)와 동일하게 유지한다. 잠금 여부가 계정 존재 여부를 드러내면 안 된다.
6. 성공한 인증은 주체 카운터를 삭제한다. 정상 사용자가 창이 끝날 때까지 잠기지 않게 한다.
7. 카운터 갱신과 실패 기록은 `REQUIRES_NEW` transaction으로 분리한다. 인증이 실패해 호출 트랜잭션이 rollback되어도 시도 횟수는 남아야 한도가 실제로 줄어든다.
8. MFA는 주체 축을 재사용한다. 한도를 넘으면 챌린지를 소모시켜 같은 챌린지로 계속 시도하지 못하게 한다.
9. 메일 발송형 엔드포인트는 출처 축을 공유한다. 한도가 없으면 **메일을 보내지 않고** 동일한 성공 응답을 돌려준다.
10. 오래된 카운터는 별도 sweep이 주기적으로 삭제한다. 정확성은 창 비교가 담당하고, sweep은 저장 공간만 회수한다.
11. 프록시 `limit_req`는 보조로만 쓴다. 애플리케이션 제한이 1차 방어선이며 프록시가 없는 환경에서도 동작해야 한다.

## SSOT와 실패 처리

- 한도 판단의 SSOT는 DB 카운터다. 단일 인스턴스 메모리 카운터는 replica 간에 합산되지 않으므로 쓰지 않는다.
- 카운터 row가 없으면 한도가 남은 것으로 본다. sweep이 지운 직후에도 정상 동작해야 한다.
- **DB 오류 시 동작(명시적)**: `AuthAttemptLimiter`는 DB 예외를 삼키지 않고 그대로 전파한다. `GlobalExceptionHandler.handleUnexpected`가 이를 500 ProblemDetail로 매핑하고 `requestId`와 함께 ERROR 로그를 남긴다(코드 확인). 즉 카운터 저장소 장애는 "인증 거절(401)"이 아니라 **가시적 장애(500)** 로 나타난다. 가용성보다 추측 방어를 우선한다는 방향은 유지하되, 조용히 401로 위장하지 않는다. 401로 위장하면 실제 장애가 "비밀번호가 틀렸다"로 보여 운영자가 원인을 찾지 못하고, 사용자는 재시도만 반복하게 된다. 장애는 장애로 드러내는 편이 낫다.
- 창 경계는 정확한 sliding window가 아니다. 고정 창은 경계에서 최대 2배까지 허용하지만, 구현과 저장 비용이 단순하고 요구 수준(추측 방어)에 충분하다.

## 동시성·확장·운영

- 카운터 갱신은 `INSERT … ON CONFLICT DO UPDATE` 단일 statement이므로 동시 요청이 같은 row에 직렬화된다. 다중 인스턴스에서도 합산된다.
- 주체 축 확인은 인덱스 탐색 1회, 출처 축 소비는 upsert 1회다. 인증 경로에 transaction 2개(확인·기록)가 추가되지만, PBKDF2 수십 ms에 비하면 무시할 수준이다.
- 설정: `atlas.auth.throttle.window`(기본 `PT15M`), `subject-limit`(10), `origin-limit`(60). 창·한도는 배포 후 실패 분포를 보고 조정한다.
- 관측: 거절은 기존 인증 실패 metric에 포함되고, 회수는 `atlas.auth.throttle.purged`로 기록한다.

## 검증

`AuthAttemptLimiterIntegrationTest`가 실제 PostgreSQL에서 다음을 검증한다.

- 잘못된 비밀번호 3회 후 올바른 비밀번호도 거절되고, 그 응답이 PBKDF2 2회(약 100 ms)보다 확실히 빠르다(60 ms 상한). 즉 한도 확인이 검증 앞에 있다.
- 성공 로그인 후 카운터가 0이 되고 다시 로그인할 수 있다.
- 창이 지나면 운영자 개입 없이 스스로 풀린다.
- 저장된 키에 이메일·주소 원문이 없다.
- 출처 한도가 서로 다른 주체에 걸쳐 공유된다(한 출처의 다계정 시도 차단).
- 메일 요청이 한도 소진 시 더 이상 허용되지 않는다.
- 오래된 카운터가 sweep으로 삭제된다.
- `counterStoreFailureIsVisibleAsAServerErrorNotAsAFakeCredentialRejection`: 카운터 테이블을 잠시 사용 불가로 만들어 실제 저장소 오류를 주입하면, 응답이 401이 아니라 **500 `INTERNAL_ERROR`** 임을 확인한다. 장애를 인증 실패로 위장하지 않는다는 SSOT 절의 주장을 코드로 고정한다.

측정 위생: 검증 비용은 단일 측정값이 아니라 5라운드 반복의 범위(47.7–58.3 ms)로 기록한다. 최초 기록의 단일 값 55.8 ms는 편차를 감춰 과대 확정했다.

## 대안과 trade-off

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 인메모리 버킷(Bucket4j 등) | replica마다 따로 세므로 다중 인스턴스에서 한도가 replica 수만큼 늘어난다. |
| Redis | 새 인프라·운영 비용. 현재 규모에서 측정된 필요가 없고, DB로 충분한 처리량이다. |
| 프록시 `limit_req`만 사용 | 프록시가 없는 환경(직접 노출·테스트)에서 무방비다. 계정 단위 추측도 막지 못한다. |
| Sliding window log | 정확하지만 요청별 row가 필요해 저장·쓰기 비용이 크다. 고정 창으로 요구 수준을 충족한다. |
| 실패 시 계정 잠금(운영자 해제) | 정상 사용자를 공격자가 잠글 수 있다. 시간 기반 자동 해제가 안전하다. |
