# EXP-118: 다중 인스턴스·rolling 배포 전제에서 단일 인스턴스 가정을 전수 조사하고, 인증 시도 제한 초과와 결제 결과 역전을 실제 인스턴스 2~3개에서 센다

- 상태: ✓ 사전 등록(가설·반증 조건·칸을 첫 계수 측정 전에 커밋). 결과 절은 측정 뒤 추가한다.
- 날짜: 2026-11-27
- 관련: [#730](https://github.com/Neo1228/ATLAS/issues/730), [distributed-readiness](../02-decisions/distributed-readiness.md), [ADR-0048](../02-decisions/adr/ADR-0048-authentication-attempt-throttling.md), [ADR-0053](../02-decisions/adr/ADR-0053-shared-infra-adoption-gates.md), [ADR-0066](../02-decisions/adr/ADR-0066-redis-admission-gate.md), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [EXP-042](EXP-042-db-concurrency-guard-audit.md)

## 문제 한 문장

app이 한 대라는 가정은 JVM 안의 잠금·메모리 상태·스케줄 작업·시계·배포 순서에 흩어져 있는데, 기존 문서(`distributed-readiness.md` 9절, ADR-0053 "이 ADR이 답하지 않는 것")는 다중 인스턴스 경합을 측정하지 않았다고 적었고, 그 공백을 메우던 `CrossInstancePaymentRaceTest`도 한 JVM 안에서만 돌아 JVM 잠금이 그대로 직렬화한다.

## 기존 결정과의 관계 (다시 하지 않는 것)

| 문서 | 이미 정한 것 | 이 실험이 더하는 것 |
|---|---|---|
| ADR-0048 | 인증 시도 카운터의 SSOT는 DB이고 다중 인스턴스에서 합산된다 | 합산은 맞지만 주체 축이 "읽고 판단한 뒤 검증 후 기록"이라 동시 시도가 한도를 넘는지 센다 |
| ADR-0053 | Redis·외부 분산 락은 측정 근거 전까지 도입하지 않는다. DB row lock·조건부 UPDATE·멱등성이 최종 방어선 | 그 방어선이 다른 JVM에서 온 오래된 결제 스냅숏을 막는지 실제 인스턴스 2개로 센다. Redis 후보는 ADR-0053 조건을 다시 대조만 한다 |
| ADR-0066 | Redis admission gate는 옵트인이고 정본이 아니다 | 재현 대상 아님(전수 표에만 둔다) |
| ADR-0067 | relay 재전송 중복은 `event-id` 헤더로 consumer가 거른다 | 재현 대상 아님 |
| distributed-readiness 2·3절 | admission cursor 중복 스캔, `Instant.now()` 직접 사용 | 현재 코드 기준으로 다시 분류만 한다 |

TxBacklog(#729, EXP-117)의 F1·F2·F3·G1·C4·E3와 AuditGuards(EXP-116)의 감사 테이블 가드는 이 실험에서 다루지 않는다. 전수 표에 같은 지점이 나오면 소유 문서로 넘긴다.

## 코드 읽기에서 나온 재현 후보 (측정 전, 기준 `origin/main` 57e6ccd7)

1. **인증 주체 한도 초과.** `AuthAttemptLimiterImpl.tryAcquire`(`atlas-account/.../AuthAttemptLimiterImpl.java:56-68`)는 `activeSubjectFailures`를 읽어 한도(기본 10)와 비교만 하고, 실패 기록(`recordSubjectFailure`)은 `AccountServiceImpl.login`(`AccountServiceImpl.java:191-209`)이 PBKDF2 검증을 마친 뒤에 한다. 검증 1회는 수십 ms(ADR-0048 측정 47.7~58.3 ms)이므로 그 사이에 들어온 시도는 모두 한도 확인을 통과한다. 출처 축은 원자적 upsert(`incrementOriginAttempts`)라 출처별 60은 지켜진다. 인스턴스마다 Tomcat 스레드 40개라 동시 통과 수의 상한은 인스턴스 수에 비례한다.
2. **결제 결과 역전.** `CheckoutPaymentServiceImpl.completeApproval`(`CheckoutPaymentServiceImpl.java:185-194`)은 결제 계층이 돌려준 스냅숏이 `CAPTURE_PENDING`·`AUTHORIZATION_PENDING`·`UNKNOWN`이면 `applyPaymentResult`(`:379-498`)로 바로 보낸다. `applyPaymentResult`는 checkout 행을 잠근 뒤 현재 상태를 보지 않고 그 스냅숏으로 자식 주문과 checkout 상태를 정한다(`:459-476`). 같은 JVM에서는 결제 계층의 `OwnerLockRegistry`(`PaymentServiceImpl.java:169`, `:802`)가 다른 스레드의 캡처가 끝날 때까지 스냅숏 읽기를 막는다. 다른 인스턴스는 막지 못하므로, 인스턴스 B가 캡처 진행 중에 읽은 `CAPTURE_PENDING`을 인스턴스 A가 `PAID`를 커밋한 뒤에 쓰면 checkout과 주문이 `PAYMENT_PENDING`으로 되돌아간다. A가 `markOwnerApplied`를 이미 불렀으므로 복구 worker가 다시 맞추지 않는다고 읽힌다(측정으로 확인할 대상). 단일 판매자 주문 경로(`OrderServiceImpl.completePaymentApproval` `:221-231`, `applyPayment` `:417-456`)도 같은 모양이다.
3. **JWT 임시 키.** `AccountJwtConfiguration.java:41-51`은 서명 비밀이 없고 `allow-ephemeral-key=true`이면 프로세스마다 무작위 키를 만든다. `application.yml` 기본값은 false라 기동이 실패하지만 `compose.yaml:41`의 기본값은 true다. 이 구성으로 인스턴스를 둘 이상 띄우면 다른 인스턴스가 발급한 토큰을 401로 거절한다고 읽힌다.

## 가설과 반증 조건 (사전 등록)

| ID | 가설 | 칸 | 반증 조건 |
|---|---|---|---|
| H1a | 현행 코드에서 한 주체에 대한 동시 오답 로그인은 인스턴스 1대에서도 주체 한도 10을 넘겨 검증까지 간다 | A-N1 | 5회 중 2회 이상 통과 수 ≤ 10 |
| H1b | 인스턴스 3대는 1대보다 더 많이 통과시킨다(동시 처리 상한이 인스턴스 수에 비례) | A-N1 대 A-N3 | A-N3 통과 수 중앙값 ≤ A-N1 중앙값 |
| H1c | 주체 한도를 검증 전에 원자적으로 예약하면(조건부 upsert) 인스턴스 수와 무관하게 통과 수가 한도 이하다 | A-N1-fix, A-N3-fix | 어느 회차든 통과 수 > 10 |
| H2a | 현행 코드에서 같은 checkout의 반환 요청 둘이 서로 다른 인스턴스에 들어가면 캡처된 결제의 checkout·주문이 `PAID`가 아닌 상태로 남는 경우가 생긴다 | P-N2 | 모드마다 100회 중 0회 |
| H2b | 인스턴스 1대에서는 같은 조건에서 0회다(JVM 잠금이 직렬화) | P-N1 | 100회 중 1회 이상 |
| H2c | 결제 반영을 checkout(주문) 행 잠금 아래에서 결제 시도의 현재 상태로 다시 정하면 인스턴스 2대에서도 0회다 | P-N2-fix | 100회 중 1회 이상 |
| H3a | 새 migration(V13)이 적용된 DB에서 old jar(57e6ccd7)를 다시 기동해도 Flyway가 미래 migration을 무시해 기동하고, 인증·checkout 흐름이 5xx 없이 돈다 | R-old | 기동 실패 또는 5xx 1건 이상 |
| H3b | old jar 1대 + new jar 1대가 섞인 동안, 코드 수정만으로는 old jar가 낸 결제 결과 역전을 막지 못하고, DB 가드(V13)는 막는다 | R-mix(V13 없음) 대 R-mix(V13) | H2a가 재현되지 않으면 이 칸은 돌리지 않고 V13을 만들지 않는다. 재현되면: V13 없는 칸에서 역전 0회이거나 V13 칸에서 역전 1회 이상 |
| H4 | 임시 JWT 키로 인스턴스 2대를 띄우면 로그인 뒤 인증 요청의 약 절반이 401이다 | J-N2 | 401이 0건 |

## 측정 방법

공통: 같은 호스트, PostgreSQL 18.6 컨테이너 1개(라벨 `atlasexp=mia`, `max_connections=300`), JDK 25, bootJar 프로세스(인스턴스마다 Tomcat 40·Hikari 10 기본값, `-Xmx512m`), Python 표준 라이브러리로 쓴 round-robin reverse proxy(요청마다 다음 인스턴스, 응답에 처리 인스턴스를 적는다). 서명 비밀은 H4 외에는 모든 인스턴스가 같다. 계수 run은 호스트 잠금(`measure-lock.sh`) 아래에서 돌리고 run마다 `load1`을 적는다. 결론은 시간이 아니라 건수로 낸다.

- **A칸(H1).** 회차마다 새 이메일 1개에 오답 로그인 90건을 barrier로 동시에 보낸다. `X-Forwarded-For`를 9개 출처에 10건씩 나눠 출처 한도(60)가 묶이지 않게 한다. 통과 수 = 회차 뒤 `auth_subject_bucket`의 해당 주체 값. 현행 코드는 통과한 시도마다 실패 1을 기록하고(모든 비밀번호가 틀림), 수정 코드는 통과한 시도마다 1을 예약하므로 두 경우 모두 "검증까지 간 시도 수"다. 칸마다 n = 5.
- **P칸(H2).** 로컬 PayPal·환율 stub(OAuth, 주문 생성·조회, authorize, capture, `PayPal-Request-Id` 멱등). 회차마다 장바구니 → checkout → 결제 제출 → stub에서 구매자 승인 → 같은 checkout에 대해 두 요청을 barrier로 동시에 보낸다. 모드 `double-return`(반환 GET 둘), `return-plus-reconcile`(반환 GET + reconcile POST). proxy가 번갈아 보내므로 두 요청이 다른 인스턴스로 간 회차만 센다(처리 인스턴스를 응답에서 확인). stub 지연: authorize 30 ms ± 20 ms, capture 20 ms ± 20 ms(균등). 결제 복구 worker는 켠 채 5초 정착 뒤 DB에서 결제 시도 상태와 checkout·자식 주문 상태를 읽는다. 위반 = 결제 `CAPTURED`인데 checkout이나 자식 주문이 `PAID`가 아님. 칸·모드마다 n = 100.
- **R칸(H3).** new jar(이 브랜치, V13 포함)가 migration을 적용한 DB에서 old jar를 기동하고 A칸 1회와 P칸 happy path 10회를 돌린다. R-mix는 old jar 1대 + new jar 1대를 proxy 뒤에 두고 P칸을 100회 돈다.
- **J칸(H4).** 서명 비밀 없이 `ATLAS_JWT_ALLOW_EPHEMERAL_KEY=true`로 2대를 띄우고, 가입·로그인 뒤 인증이 필요한 GET 20건을 proxy로 보내 401 수를 센다. n = 3.

시간 예산: 계수 run 합계 90분. 넘으면 우선순위가 낮은 칸(J, R-mix의 V13 없는 칸)부터 범위 밖 결정으로 닫는다.
