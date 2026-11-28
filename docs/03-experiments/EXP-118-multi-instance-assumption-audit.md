# EXP-118: 다중 인스턴스·rolling 배포 전제에서 단일 인스턴스 가정을 전수 조사하고, 인증 시도 제한 초과와 결제 결과 역전을 실제 인스턴스 2~3개에서 센다

- 상태: ✓ 완료. 가설·반증 조건·칸은 각 칸의 첫 계수 전에 커밋했다. 결과: 인증 주체 한도 초과 재현(H1a·H1b)과 예약 수정 채택(H1c, 진행 중 거절은 대안 a로 받아들임 H1d·H1f), 결제 결과 역전은 실제 인스턴스에서 재현되지 않음(H2a 반증, V13 없음), 만료 sweep 잠금을 transaction 범위로 바꿈(H5), G1 수정, V10 rolling 안전(H3c). 결정은 [ADR-0107](../02-decisions/adr/ADR-0107-multi-instance-shared-state-guards.md)과 D-186.
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
| H3c | (첫 계수 전 추가) 가장 최근 실제 migration V10에서 rolling 배포가 안전하다: V9 jar(16275684)가 checkout 트래픽을 처리하는 중에 V10 jar(57e6ccd7)가 기동해 V10을 적용하고, V9 jar는 그동안과 그 뒤 재기동 후에도 5xx 없이 결제를 `PAID`로 마친다 | R-V10 | 3회 중 한 번이라도 V10 jar 기동 실패(백필 잠금·`lock_timeout`), V9 jar 재기동 실패, 또는 driver 오류(비 2xx) 1건 이상 |
| H4 | 임시 JWT 키로 인스턴스 2대를 띄우면 로그인 뒤 인증 요청의 약 절반이 401이다 | J-N2 | 401이 0건 |
| H1d | (첫 계수 전 추가) 예약 수정에서는 올바른 비밀번호도 검증이 끝날 때까지 주체 한 칸을 쓴다. 한 주체에 올바른 로그인 15건을 동시에 보내면(한도 10) 수정 jar는 회차마다 1건 이상을 검증 전에 거절하고 현행 jar는 0건을 거절한다. 두 jar 모두 회차 뒤 카운터는 0이다(성공이 ADR-0048 결정 6대로 지운다) | A-ok | 수정 jar가 5회 모두 거절 0건, 현행 jar가 1건 이상 거절, 또는 어느 jar든 회차 뒤 카운터 > 0 |
| H1e | (첫 계수 전 추가) 검증 중 `kill -9`로 끝난 시도는 수정 jar에서 예약만 남긴다(유령 실패). 유령 수는 그 순간 검증 중이던 시도 수 이하이고 다음 성공 로그인이나 창 끝에 사라진다. 현행 jar는 유령이 0이다 | A-crash | 수정 jar에서 유령이 보낸 시도 수를 넘거나 성공 로그인 뒤·창 끝 뒤에도 남음, 또는 현행 jar 유령 ≥ 1 |
| H5a | (첫 계수 전 추가) 현행 session advisory lock(`ExpirySweepLockRepository`)은 PgBouncer `pool_mode=transaction` 뒤에서 상호 배제를 잃거나 sweep이 멈추거나 서버 연결에 lock을 남긴다 | S-pgb-session | 3회 모두 겹침 0, 최장 공백 < 2초, 끝난 뒤 서버에 남은 advisory lock 0 |
| H5b | (첫 계수 전 추가) 락 연결의 transaction 안에서 잡는 transaction 범위 lock(이 브랜치)은 같은 PgBouncer 뒤에서 겹침 0, 남은 lock 0, xid를 쥔 idle in transaction 0이다 | S-pgb-xact | 어느 회차든 겹침 ≥ 1, 남은 lock ≥ 1, xid 보유 표본 ≥ 1, 또는 오류 ≥ 1 |
| H5c | (첫 계수 전 추가) pooler 없이도 transaction 범위 lock은 session lock과 같은 상호 배제와 실행 횟수를 낸다 | S-direct-session 대 S-direct-xact | xact 칸 겹침 ≥ 1이나 오류 ≥ 1, 또는 실행 횟수가 session 칸의 90% 미만 |
| H1f | (첫 계수 전 추가, 2026-11-28) H1d의 진행 중 예약 거절을 서버가 200 ms 뒤 한 번 다시 예약해 풀면(대안 b, 실험 jar `fixb`) 올바른 로그인 15건이 모두 성공하지만, 오답 90건 묶음에서 거절된 시도가 Tomcat 스레드(인스턴스당 40)를 200 ms씩 쥐어 같은 인스턴스의 다른 요청(`/actuator/health` 탐침, 20 ms 간격) 지연이 오른다. 이 브랜치 jar(`fix3`)에서 거절된 올바른 로그인은 250 ms 뒤 클라이언트 재시도 한 번으로 모두 성공한다 | A-cost-N1(fix3 대 fixb), A-ok-N3(fix3, fixb), A-ok-retry-N3(fix3) | fixb A-ok가 한 회차라도 15건 성공이 아님, fixb 탐침 p99 중앙값이 fix3보다 50 ms 넘게 높지 않음, fix3 재시도 성공이 거절 수보다 적은 회차가 있음, 또는 어느 칸이든 검증 수 > 10 |

판정 규칙(측정 전 고정): H5b와 H5c가 모두 서면 만료 sweep 잠금을 transaction 범위 lock으로 바꾸고, sizing-envelope 3.4의 transaction mode 권장에 남는 전제(시작 매개변수 `options`, prepared statement)를 적는다. 하나라도 반증되면 session lock을 그대로 두고 "transaction pooling 금지"를 배포 제약으로 적는다. H1e의 유령이 검증 중 시도 수 이하이고 성공과 창 끝에 사라지면 ADR-0048 범위 안으로 받아들이고, 아니면 예약에 만료를 둔다. A칸 수정 jar는 저장값이 구조상 10을 넘지 못하므로, 독립 신호로 응답 지연을 쓴다. 거절된 시도는 PBKDF2를 건너뛰므로 회차마다 정렬한 지연에서 가장 큰 간격으로 빠른 묶음과 느린 묶음을 가르고 느린 묶음 수를 검증한 시도 수로 본다. 현행 jar 칸에서 느린 묶음 수와 저장값의 차가 회차마다 2 이하일 때만 이 신호를 유효로 본다. (첫 계수 전 추가) 두 번째 독립 신호로 앱 프로세스 CPU 시간을 쓴다. 회차마다 같은 주체에 두 번째 90건을 보내(두 코드 모두 이미 한도라 전부 검증 전 거절) 첫 묶음 CPU − 두 번째 묶음 CPU를 검증한 시도의 CPU로 보고, 같은 회차에 새 주체 10건(두 코드 모두 전부 검증)의 CPU ÷ 10으로 나눠 검증 수를 추정한다. 이 추정은 거칠어서(건조 실행에서 실제 10에 9.3~16.7), 현행 jar 칸에서 추정이 저장값의 ±50% 안일 때만 유효로 보고, 수정 jar 칸에서 어느 회차든 추정 > 20(한도의 2배)이면 H1c를 반증된 것으로 본다.

(첫 계수 전 추가, 2026-11-28) 진행 중 예약 거절(H1d)은 ADR-0048의 순서(추측 방어가 가용성보다 먼저, 거절은 검증 전에 싸게)로 판정한다. fixb가 회차마다 15건을 받고 탐침 p99 중앙값 증가가 50 ms 이하이면 대안 b를 채택한다. 증가가 50 ms를 넘거나 fixb가 한 회차라도 15건을 다 받지 못한 경우, fix3 재시도가 회차마다 거절을 모두 풀면 대안 a(받아들이고 문서화)를 채택한다. fix3 재시도가 거절을 다 풀지 못하면 a도 기각하고 다른 대안을 측정한다.

## 측정 방법

공통: 같은 호스트, PostgreSQL 18.6 컨테이너 1개(라벨 `atlasexp=mia`, `max_connections=300`), JDK 25, bootJar 프로세스(인스턴스마다 Tomcat 40·Hikari 10 기본값, `-Xmx512m`), Python 표준 라이브러리로 쓴 round-robin reverse proxy(요청마다 다음 인스턴스, 응답에 처리 인스턴스를 적는다). 서명 비밀은 H4 외에는 모든 인스턴스가 같다. 계수 run은 호스트 잠금(`measure-lock.sh`) 아래에서 돌리고 run마다 `load1`을 적는다. 결론은 시간이 아니라 건수로 낸다.

- **A칸(H1).** 회차마다 새 이메일 1개에 오답 로그인 90건을 barrier로 동시에 보낸다. `X-Forwarded-For`를 9개 출처에 10건씩 나눠 출처 한도(60)가 묶이지 않게 한다(첫 계수 전 추가: 출처 주소를 묶음마다 새로 뽑아 회차·칸 사이에도 한도가 쌓이지 않게 하고, 기록하지 않는 예열 회차 1회를 먼저 돈다). 통과 수 = 회차 뒤 `auth_subject_bucket`의 해당 주체 값. 현행 코드는 통과한 시도마다 실패 1을 기록하고(모든 비밀번호가 틀림), 수정 코드는 통과한 시도마다 1을 예약하므로 두 경우 모두 "검증까지 간 시도 수"다. 칸마다 n = 5.
- **P칸(H2).** 로컬 PayPal·환율 stub(OAuth, 주문 생성·조회, authorize, capture, `PayPal-Request-Id` 멱등). 회차마다 장바구니 → checkout → 결제 제출 → stub에서 구매자 승인 → 같은 checkout에 대해 두 요청을 barrier로 동시에 보낸다. 모드 `double-return`(반환 GET 둘), `return-plus-reconcile`(반환 GET + reconcile POST). proxy가 번갈아 보내므로 두 요청이 다른 인스턴스로 간 회차만 센다(처리 인스턴스를 응답에서 확인). stub 지연: authorize 30 ms ± 20 ms, capture 20 ms ± 20 ms(균등). 결제 복구 worker는 켠 채 5초 정착 뒤 DB에서 결제 시도 상태와 checkout·자식 주문 상태를 읽는다. 위반 = 결제 `CAPTURED`인데 checkout이나 자식 주문이 `PAID`가 아님. 칸·모드마다 n = 100.
- **R칸(H3).** H3a·H3b: new jar(이 브랜치, V13 포함)가 migration을 적용한 DB에서 old jar를 기동하고 P칸 happy path 10회를 돌린다. R-mix는 old jar 1대 + new jar 1대를 proxy 뒤에 두고 P칸을 100회 돈다. H3c(R-V10): 회차마다 빈 DB `atlas_roll`에 V9 jar를 띄워 V9까지 적용하고, proxy(두 인스턴스, 기동 전인 쪽은 건너뜀)로 `double-return` 40회를 돌리는 도중 12초 뒤 V10 jar를 같은 DB로 기동한다. 트래픽이 끝나면 V9 jar를 재기동하고 V9 jar에만 10회를 더 보낸다. n = 3.
- **J칸(H4).** 서명 비밀 없이 `ATLAS_JWT_ALLOW_EPHEMERAL_KEY=true`로 2대를 띄우고, 가입·로그인 뒤 인증이 필요한 GET 20건을 proxy로 보내 401 수를 센다. n = 3.
- **A-ok칸(H1d).** 회차마다 새 계정 1개를 가입·이메일 확인한 뒤 올바른 비밀번호 로그인 15건을 barrier로 동시에 proxy(인스턴스 3대)로 보낸다. 출처는 15개로 나눈다. 성공·거절 수와 회차 뒤 카운터를 센다. jar마다 n = 5.
- **A-crash칸(H1e).** 인스턴스 1대, 이 칸만 창 `PT60S`. 새 계정 X·Y에 오답 5건씩을 함께 보내고 50 ms 뒤 `kill -9`(첫 계수 전 25 ms에서 바꿈: 건조 실행에서 25 ms면 예약이 1~2건만 잡혔다). 유령 = 저장값 − 실제로 받은 401 수. 재기동 뒤 X는 올바른 비밀번호로 로그인한 뒤 카운터를, Y는 kill 뒤 62초를 기다려 오답 1회 뒤 카운터를 읽는다. jar마다 n = 3.
- **S칸(H5).** 현행 jar와 이 브랜치 jar에서 꺼낸 실제 `ExpirySweepLockRepository`를 인스턴스 3개 몫(각자 Hikari 10)으로 만들어 같은 workflow에 50~75 ms마다 `runExclusive`를 부른다. sweep 본문은 같은 풀의 다른 연결로 `pg_sleep(0.02)`를 한다(행별 transaction 자리). 겹침(실행 중인데 또 시작), 건너뜀, 오류, 실행 사이 최장 공백, 끝난 뒤 서버의 advisory lock 수, `pg_stat_activity`에서 xid를 가진 idle in transaction 표본 수를 센다. pooler는 PgBouncer 1.24.1(`pool_mode=transaction`, `default_pool_size=5`, `max_prepared_statements=200`). 칸마다 30초 × 3회.
- **A-cost·A-ok-retry칸(H1f, 첫 계수 전 추가).** `fix3`은 이 브랜치 코드(G1 fencing 포함), `fixb`는 `fix3`에 "예약이 거절되면 200 ms 쉬고 한 번 더 예약"만 더한 실험 jar다(저장소에 넣지 않는다). A-cost: 인스턴스 1대, 새 주체에 오답 90건을 동시에 보내는 동안 같은 인스턴스의 `/actuator/health`를 20 ms 간격으로 부르고, 묶음 첫 요청부터 마지막 응답 사이에 걸친 탐침의 p50·p99·최대와 검증 수(저장값)를 센다. 예열 1회 뒤 n = 5. A-ok는 위와 같고, A-ok-retry는 401을 받은 요청이 250 ms 뒤 한 번 다시 로그인한다. jar마다 n = 5. 같은 잠금 보유 안에서 PostgreSQL을 새로 만든 뒤 돈다.

시간 예산: 계수 run 합계 90분. 넘으면 우선순위가 낮은 칸(J, R-mix의 V13 없는 칸)부터 범위 밖 결정으로 닫는다.

## 전수 조사 표 (코드 읽기, 줄 번호는 `origin/main` 57e6ccd7 기준)

분류: **안전**(이유가 DB 쪽 장치), **정합성 결함**(돈·재고·권한·데이터 불변식이 깨지거나 멱등으로 흡수되지 않는 외부 부작용), **성능 저하**(효율·지표·부하·한도가 N배). 찾은 방법: `rg`로 `synchronized|ReentrantLock|Semaphore|ConcurrentHashMap|Atomic*|volatile|@Scheduled|CommandLineRunner|ApplicationRunner|@PostConstruct|@EventListener|Files\.|Instant\.now|clock\.instant` 를 `atlas-*/src/main` 전체에서 찾고 호출부를 읽었다. 줄 번호 37개는 원문과 다시 대조했다.

### JVM 안의 잠금

| 지점 | 무엇을 지키나 | 인스턴스가 여럿이면 | 분류 |
|---|---|---|---|
| `atlas-order/.../OrderServiceImpl.java:55`, `:591`, `OwnerLockRegistry.java:8-33` | 단일 판매자 주문의 결제 제출·대조·취소 직렬화 | 다른 JVM을 막지 못한다. 상태 전이는 `lockOrder`(`OrderRepository.java:25` PESSIMISTIC_WRITE)가 지키지만 `applyPayment`(`:417-456`)가 잠금 밖 스냅숏으로 상태를 정한다 | 정합성 결함(H2) |
| `atlas-payment/.../PaymentServiceImpl.java:169`, `:802`, `PaymentRefundWorkflow.java:266`, `PaymentWebhookProcessor.java:190` | 결제 단계 예약·PayPal 호출·확정 직렬화 | 결제 행은 안전: 예약은 행 잠금(`:520-564`), 확정은 단계·요청 id 펜스(`:585-587`), 웹훅은 `ON CONFLICT (event_id) DO NOTHING`(`PaymentWebhookEventRepository.java:21`). 남는 것은 같은 `PayPal-Request-Id`의 중복 호출 | 성능 저하(외부 호출 중복). 결과 반영은 위 행의 결함 |
| `atlas-admission/.../AdmissionServiceImpl.java:56`, `:239-300` (`synchronized` + 메모리 cursor) | 만료 sweep 한 번에 하나, 다음 tick 위치 | 인스턴스마다 매 초 후보 조회. 판매자 SKU 카운터는 `FOR UPDATE SKIP LOCKED`(`QueueRepository.java:78`), 표 갱신은 상태 조건부 | 성능 저하(distributed-readiness 2절과 같음) |
| `atlas-application/.../RedisStockAdmissionControl.java:215`, `:234` | Redis 시드·연결 초기화 | 인스턴스 사이는 `SET NX`(`:225`)가 지킨다 | 안전 |
| `atlas-payment/.../CachingExchangeRateProvider.java:50-57` | 환율 단일 비행·캐시 | 외부 호출 N배(실패 backoff로 상한). 금액은 결제 시도마다 행 잠금 아래 첫 값으로 고정(`PaymentServiceImpl.java:451-461`) | 성능 저하(ADR-0051·0053이 이미 정함) |
| `atlas-payment/.../PaymentRecoveryServiceImpl.java:36-37`, `:69`; `ScheduledExpiryMetricsWorker.java:73` | 지표 스냅숏, 로그 | 지표·로그만 | 안전 |

### 메모리 상태·캐시·한도

| 지점 | 쓰임 | 인스턴스가 여럿이면 | 분류 |
|---|---|---|---|
| `atlas-account/.../AuthAttemptLimiterImpl.java:56-68`, `AccountServiceImpl.java:191-209`, `:298-308` | 인증 주체 한도(로그인·MFA) | 카운터는 DB에서 합산되지만 읽고 판단한 뒤 검증 후 기록해서 동시 시도가 한도를 넘는다. 동시 처리 상한이 인스턴스 수에 비례 | 정합성 결함(H1) |
| `atlas-account/.../AuthThrottleRepository.java:40-58` | 인증 출처 한도 | 원자적 upsert | 안전 |
| `atlas-application/.../PrecheckStockAdmissionControl.java:36` | 품절 음성 캐시(TTL 1초) | 인스턴스마다 location을 TTL마다 1회 더 읽고, 재입고 뒤 TTL 동안 과거절. 최종 판정은 location 조건부 갱신 | 성능 저하 |
| `RedisStockAdmissionControl.java:113-114` | fail-open breaker | 인스턴스마다 따로 열림 | 성능 저하 |
| `atlas-application/.../LocationCallBulkhead.java:23`, `atlas-payment/.../PayPalCallBulkhead.java:23`, `:28` | 요청 스레드의 외부 호출 동시 상한 | 클러스터 상한은 N × 설정값. 목적이 인스턴스 스레드 보호라 인스턴스별이 맞고, 외부 quota를 지키려면 설정값 = 전체 ÷ N | 성능 저하 |
| `HttpLocationClient.java:33` | location별 RestClient | 설정 수로 한정 | 안전 |
| `SampledFailureLog.java:21`, 각 worker의 `AtomicLong` 로그 간격 | 로그 표본 | 로그량 N배 | 성능 저하 |
| `AccountNotificationWorker.java:38-41`, `DatabaseSignalMetrics.java:44-45`, `LedgerAuditMetrics.java:31-32`, `PayoutRiskMetrics.java:16`, `ReturnInspectionMetrics.java:18`, `PayoutDispatchWorker.java:44` | DB에서 읽은 전체 값 게이지 | 인스턴스마다 같은 전체 값을 낸다. 대시보드에서 `sum`이면 N배 | 성능 저하(관측) |
| ID 생성(`PaymentConfiguration.java:14`, `UUID.randomUUID`, `uuidv7()`) | 행 id | 프로세스 안 순번 없음 | 안전 |

### 스케줄 작업

| 지점 | 인스턴스 사이 장치 | 분류 |
|---|---|---|
| 주문·checkout 만료 sweep (`ScheduledExpiryMetricsWorker.java:46`, `:51`, `ExpirySweepLockRepository.java:43-63`) | session advisory try-lock, 행마다 잠금 + 상태 재확인 | 안전. 배포 전제: transaction pooling 금지(아래 B절) |
| 결제 복구 claim (`PaymentRecoveryRepository.java:164-200`, `:191`) | `FOR UPDATE SKIP LOCKED`, lease 60초, 펜스(`:510`) | 안전(PayPal 요청 id 멱등 전제) |
| 결제 복구 소진 표시 (`PaymentRecoveryRepository.java:493-508`) | 잠금 없는 조건부 UPDATE를 인스턴스마다 매 초 | 성능 저하 |
| PayPal 웹훅 재시도 (`PayPalWebhookRetryRepository.java:51-100`) | `SKIP LOCKED` + lease 펜스 | 안전 |
| 지급 dispatch (`JdbcPayoutDispatchStore.java:57-72`, `:66`) | `SKIP LOCKED`, lease 60초, `sender_batch_id` 중복을 원 배치로 채택 | 안전(PayPal 멱등 전제) |
| 인증 메일 outbox (`AccountNotificationRepository.java:41-66`, `:48`) | `SKIP LOCKED`, lease 2분, 완료 펜스 | 안전. SMTP 수락 뒤 완료 전 종료면 lease 뒤 한 번 더 보낸다(ADR-0102가 재시작 뒤 중복을 재검토 신호로 둠) |
| 판매자 잔액 마감 (`SettlementBalanceCheckpointRepository.java:37`) | 커서 행 `SKIP LOCKED` | 안전 |
| location 예약·재입고·checkout 마무리 (`LocationReservationRepository.java:197-215`, `LocationRestockRepository.java:47-60`, `CheckoutFinalizationServiceImpl.java:189-210`) | `SKIP LOCKED` + lease, 마무리는 잠금 없는 조회 후 행 잠금 + 상태 재확인 | 안전(마무리 조회는 N배). location 쓰기 fencing은 EXP-117 G1 |
| 예약 이벤트 relay (`ReservationEventRepository.java:76`, `:96`) | lease + 펜스 전진. lease 만료 뒤 재전송은 `event-id` 헤더로 consumer가 거름 | 안전(ADR-0067) |
| 원장 점검·DB 신호·지급 위험·반품 검수 지표 (`LedgerAuditMetrics.java:40`, `DatabaseSignalMetrics.java:55`, `PayoutRiskMetrics.java:32`, `ReturnInspectionMetrics.java:26`) | 없음. 모든 인스턴스가 읽기 질의 실행 | 성능 저하(읽기 N배) |
| 인증 카운터·admission tombstone 정리 (`AuthThrottleRepository.java:82-100`, `QueueRepository.java:124-140`) | 정리는 정확성과 무관, tombstone은 `SKIP LOCKED` | 안전 |
| location 서비스 예약 만료 (`atlas-inventory-location/.../InventoryReservationRepository.java:84-88`) | `SKIP LOCKED` + 조건부 전이 | 안전 |

### 시작·세션·시계·파일·배포

| 지점 | 인스턴스가 여럿이면 | 분류 |
|---|---|---|
| 시작 초기화 | `CommandLineRunner`·`ApplicationRunner`·`@PostConstruct`·`@EventListener`·`SmartLifecycle`이 운영 코드에 0곳. 시작 때 쓰기 없음 | 안전 |
| Flyway (`application.yml:67-68`, 설정은 `enabled`뿐) | 모든 인스턴스가 기동 때 migrate. 동시 기동은 Flyway 잠금이 직렬화. 미래 migration 무시가 기본(H3a로 확인) | 안전 |
| JWT 서명 키 (`AccountJwtConfiguration.java:41-51`, `application.yml:111-113`, `compose.yaml:41`) | 비밀이 없고 임시 키를 허용하면 인스턴스마다 다른 키 | 성능 저하(401로 닫힘, H4) · 배포 제약 |
| refresh 세션·action token·MFA 챌린지 (`AccountRepository.java:106`, `:276-296`, `:372-385`) | DB 행 잠금 + 조건부 소비 | 안전 |
| HTTP 세션 | `STATELESS` | 안전 |
| 앱 시계로 쓴 기한을 다른 인스턴스 시계로 비교: admission 표(`QueueRepository.java:173-175` ↔ `AdmissionServiceImpl.java:240`), 주문 만료(`OrderRepository.java:59`, `OrderServiceImpl.java:408`), location 예약(`InventoryReservationService.java:90`, `:183`), 계정 토큰(`AccountRepository.java:122`, `:279`), 결제 승인 기한(`PaymentServiceImpl.java:544` ↔ `PaymentRecoveryWorker.java:239` `Instant.now()` ↔ `PaymentRecoveryRepository.java:459-481` DB 시계) | 시계 차이만큼 이르거나 늦게 만료. 상태는 행 잠금 + 재확인이 지킨다 | 성능 저하 · 배포 제약(NTP) |
| DB 시계 lease (`PaymentRecoveryRepository.java:186-195`, `PayPalWebhookRetryRepository.java:61`, `JdbcPayoutDispatchStore.java:63`) | 앱 시계와 무관 | 안전 |
| location workflow lease 앱 시계(`LocationReservationServiceImpl.java:110-112`) | 시계 차이만큼 이른 재claim. 쓰기 펜스는 lease token. (결과 뒤 갱신) lease를 잃은 claim이 기록하지 못한 `CLAIMED`를 답하던 결함(EXP-117 G1)은 이 브랜치에서 고쳤다(아래 "G1" 절) | 정합성 결함(고침) |
| 로컬 파일 | `Files.*`·`FileOutputStream`·임시 파일 0곳, CSV는 메모리에서 읽음 | 안전 |
| 종료 (`SchedulingConfiguration.java:61-62`) | scheduler는 진행 작업을 10초 기다림. `kill -9`면 lease 만료 뒤 다른 인스턴스가 이어받음 | 안전 |
| V5~V10 (rolling) | V5·V6은 old jar의 같은 행위자 조작을 23514로 거절(닫힘), V8은 비동시 인덱스 생성 잠금, V10은 백필 중 ACCESS EXCLUSIVE + `lock_timeout` 5초로 기동이 실패할 수 있다고 읽었다. (결과 뒤 갱신) V10은 R-V10 3회에서 V9 jar 트래픽 중 7~9초에 기동했고 오류 0이었다(H3c) | 성능 저하(배포), V10은 측정상 안전 |

## 결과 (2026-11-28)

원자료(회차별 JSON, 구동 스크립트, 앱 로그)는 저장소에 올리지 않았다. 아래 수치는 그 원자료를 요약 스크립트로 센 값이다. 계수 run은 호스트 잠금(`measure-lock.sh`) 아래에서 돌렸고, 잠금 없이 돈 첫 1부 실행은 오염으로 따로 적는다(맨 아래 표). bootJar: 현행 `57e6ccd7`, 수정 `fix2`(이 브랜치의 인증 예약·결제 재조회·sweep 잠금 수정, G1 수정 전), H1f의 `fix3`(G1 수정까지 포함한 이 브랜치 코드).

### 판정 요약

| ID | 결과 | 판정 |
|---|---|---|
| H1a | 인스턴스 1대에서 검증까지 간 오답 40~48건(한도 10), 5/5회 초과 | ✓ 섬 |
| H1b | 3대 중앙값 90 > 1대 중앙값 47 | ✓ 섬 |
| H1c | 수정 jar 1대·3대 모두 회차마다 10건, CPU 추정 4.1~18.3(반증 문턱 20) | ✓ 섬 |
| H1d | 올바른 로그인 15건 동시: 수정 jar 회차마다 5건 거절, 현행 0건, 회차 뒤 카운터 모두 0 | ✓ 섬 |
| H1e | 검증 중 `kill -9`: 수정 jar 유령 5·5(보낸 수 이하), 성공과 창 끝에 사라짐. 현행 0 | ✓ 섬 |
| H1f | 서버 재예약(대안 b)은 올바른 로그인 15건을 모두 받았지만 오답 90건 묶음 동안 같은 인스턴스 탐침 p99 중앙값 93 → 308 ms. 이 브랜치 jar의 거절 5건은 250 ms 뒤 재시도에서 모두 성공 | ✓ 섬, 대안 a 채택 |
| H2a | 두 요청이 다른 인스턴스로 간 200회(모드마다 100)에서 역전 0 | ✗ 반증 |
| H2b | 인스턴스 1대 200회에서 역전 0 | ✓ 섬 |
| H2c | 수정 jar 2대 200회에서 역전 0. 현행도 0이라 이 칸은 수정의 효과를 가르지 못한다 | ✓ 섬 |
| H3a·H3b | H2a가 재현되지 않아 사전 등록대로 R-old·R-mix를 돌리지 않았다. V13을 만들지 않는다 | ✓ 결정 |
| H3c | V10 rolling 3회 모두 기동·재기동 성공, 오류 0, 역전 0 | ✓ 섬 |
| H4 | 임시 키 2대에서 인증 GET 20건 중 401 7·7·14 | ✓ 섬 |
| H5a·H5b·H5c | session lock은 PgBouncer 뒤 겹침 15~24, 남은 lock 1. transaction 범위 lock은 겹침·남은 lock·xid 보유·오류 0, 직접 연결에서 실행 수 99.8~102.1% | ✓ 셋 다 섬 |

### 잠금 아래 계수: 1부(A, A-ok, P, J)

잠금 `mia-c`, 01:57:53~02:04:51. 시작 때 다른 에이전트의 라벨 컨테이너 0개, 칸 시작 때 load1 2.9~9.4(이 실험의 앱 인스턴스와 하네스 자신의 부하).

| 칸 | n | 결과 | 판정 |
|---|---|---|---|
| A-N1-base(H1a) | 5 | 검증까지 간 시도 47·44·40·47·48(중앙 47). 응답은 모두 401 | ✓ H1a 섬 |
| A-N3-base(H1b) | 5 | 90·90·90·90·89(중앙 90). 5회차에 503 1건 | ✓ H1b 섬 |
| A-N1-fix(H1c) | 5 | 저장값 10·10·10·10·10. CPU 추정 9.1·11.2·12.2·11.0·9.9 | ✓ H1c 섬 |
| A-N3-fix(H1c) | 5 | 저장값 10·10·10·10·10. CPU 추정 4.1·11.7·4.7·15.0·18.3 | ✓ H1c 섬 |
| A-ok-base(H1d) | 5 | 회차마다 성공 15, 거절 0, 회차 뒤 카운터 0 | ✓ H1d 섬 |
| A-ok-fix(H1d) | 5 | 회차마다 성공 10, 거절 5(거절 응답 최대 79·117·99·66·98 ms), 회차 뒤 카운터 0. 성공 p50 235·204·134·109·145 ms | ✓ H1d 섬 |
| P-N1-base(H2b) | 100 × 2모드 | `double-return`·`return-plus-reconcile` 모두 역전 0, 오류 0, 최종 `PAID·PAID·CAPTURED` 100 | ✓ H2b 섬 |
| P-N2-base(H2a) | 100 × 2모드 | 두 요청이 다른 인스턴스로 간 회차 100·100, 역전 0·0, 오류 0. stub의 capture 호출 113·112건(요청 id 100개) | ✗ H2a 반증 |
| P-N2-fix(H2c) | 100 × 2모드 | 다른 인스턴스 회차 100·100, 역전 0·0, 오류 0. capture 호출 126·110건(요청 id 100개) | ✓ H2c 섬 |
| J-N2-ephemeral(H4) | 3 | 401 7·7·14건. 401은 모두 로그인하지 않은 인스턴스에서 났고, 로그인한 인스턴스로 간 요청은 인증을 통과했다(대상이 없어 404) | ✓ H4 섬 |

A칸의 독립 신호. CPU 신호는 현행 jar 칸에서 추정이 저장값의 89~110%(A-N1-base 41.8~46.1 대 40~48, A-N3-base 84.4~99.0 대 89~90)라 유효다. 수정 jar 칸의 추정은 모두 20 이하라 H1c를 반증하지 않는다. 지연 간격 신호는 현행 jar 칸에서 느린 묶음 수와 저장값의 차가 −15~42(A-N1-base)와 −5~−1(A-N3-base)로 2를 넘는 회차가 있어 사전 규칙대로 쓰지 않는다.

P칸의 해석. 다른 인스턴스로 간 400회(현행 200, 수정 200)에서 결제 결과 역전은 한 번도 나지 않았다. 역전에는 B가 A의 capture 진행 중에 `CAPTURE_PENDING`을 읽고 A가 `PAID`를 커밋한 뒤에 써야 하는데, stub 지연(capture 20 ± 20 ms)과 실제 요청 경로에서는 그 순서가 나오지 않았다. 반면 같은 장면을 순서를 고정해 만든 PostgreSQL 시험 2개(`staleApprovalSnapshotFromAnotherInstanceDoesNotUndoAPaid*`)는 재조회를 빼면 `PAYMENT_PENDING`으로 실패하고 넣으면 통과한다(아래 변이 표). 그래서 재조회 수정은 남기고(스키마 변경 없음, 이미 잡는 행 잠금 아래 읽기 1회), 판정 규칙대로 V13 DB 가드는 만들지 않는다. 다른 JVM 사이의 중복 PayPal capture 호출(요청 id 100개에 110~126건)은 `PayPal-Request-Id` 멱등이 흡수했고, 1대에서는 100건이었다.

### H1f: 진행 중 예약 거절의 대안 비교

잠금 `mia-d`, 02:12:45~02:14:22. 시작 때 다른 에이전트의 라벨 컨테이너 0개, 칸 시작 때 load1 3.0~4.8. PostgreSQL은 이 잠금 안에서 새로 만들었다. `fix3`은 이 브랜치 코드, `fixb`는 `fix3`에 "예약이 거절되면 200 ms 쉬고 한 번 더 예약"만 더한 실험 jar다(저장소에 넣지 않았다).

| 칸 | n | 결과 |
|---|---|---|
| A-cost-N1-fix3 | 5 | 검증 10·10·10·10·10. 묶음 시간 252·263·205·261·231 ms. 탐침 p50 90·24·36·78·65 ms, p99 93·120·56·86·100 ms(중앙 93), 탐침 오류 0 |
| A-cost-N1-fixb | 5 | 검증 10·10·10·10·10. 묶음 시간 601·572·554·585·622 ms. 탐침 p50 4·3·4·4·3 ms, p99 399·299·308·381·294 ms(중앙 308), 탐침 오류 0 |
| A-ok-N3-fix3 | 5 | 회차마다 성공 10, 거절 5, 회차 뒤 카운터 0 |
| A-ok-retry-N3-fix3 | 5 | 회차마다 처음 성공 10, 거절 5. 거절된 5건은 250 ms 뒤 재시도에서 5·5·5·5·5건 성공, 회차 뒤 카운터 0 |
| A-ok-N3-fixb | 5 | 회차마다 성공 15, 거절 0, 회차 뒤 카운터 0 |

탐침은 묶음 첫 요청부터 마지막 응답 사이에 걸친 것만 세서 묶음마다 3~7개다. 그래서 p99는 사실상 그 묶음의 최댓값이다. `fixb`는 탐침 중앙값을 낮췄지만(거절 80건이 한꺼번에 처리되지 않고 200 ms 뒤로 밀림) 꼬리를 늘렸고, 묶음이 끝날 때까지 2.3배 걸렸다. 거절된 오답도 모두 200 ms를 기다렸다가 다시 거절됐다.

판정: H1f의 반증 조건 넷 모두 나오지 않았다(`fixb` A-ok 회차마다 15건 성공, 탐침 p99 중앙값 +215 ms로 50 ms 초과, `fix3` 재시도가 회차마다 거절 5건을 모두 풂, 어느 칸이든 검증 10). ✓ H1f 섬. 사전 규칙대로 대안 b(서버 재예약)를 채택하지 않고 대안 a를 채택한다. 한도만큼의 시도가 한 주체에 대해 동시에 검증 중일 때만 그다음 올바른 로그인이 401로 거절되고, 검증 중이던 시도가 성공하면 카운터가 지워져 다음 시도는 통과한다. 오답 예약은 성공이 지우기 전까지 남으므로, 한 주체에 한도만큼 오답이 쌓인 공격 중에는 대안 b의 재예약도 다시 거절된다(코드상, 이 칸에서 따로 재지 않음). 즉 대안 b가 살리는 것은 올바른 로그인끼리만 몰린 경우뿐이다. ADR-0048의 순서(추측 방어와 검증 전의 싼 거절이 가용성보다 먼저)와도 맞다. 시험 `correctLoginRefusedOnlyBecauseOthersAreStillBeingVerifiedSucceedsOnceTheyFinish`가 이 동작을 고정한다(변이 2개 모두 실패, 아래 변이 표).

### 잠금 아래 계수: 2부(A-crash, R-V10, S)

잠금 `mia-b`, 01:20:14~01:36:36. 시작 때 다른 에이전트의 라벨 컨테이너(`exp117c4` 5개)가 떠 있었고 load1은 2.7~8.8이었다. 이 칸의 판정은 건수와 불변식(유령 수, 겹침, 남은 lock, 오류)으로 하고, 시간 값(최장 공백)은 맥락으로만 쓴다.

| 칸 | n | 결과 | 판정 |
|---|---|---|---|
| A-crash, 현행 jar(H1e) | 3 | 회차마다 X·Y 각 5건 중 받은 401 0, kill 뒤 저장값 0, 유령 0. 재기동 뒤 X 올바른 로그인 200, 카운터 0. Y는 창(60초) 뒤 오답 1회에 카운터 1 | ✓ H1e 섬 |
| A-crash, 수정 jar(H1e) | 3 | 회차마다 받은 401 0, kill 뒤 저장값 5·5, 유령 5·5(보낸 5건 모두 검증 중이었음). X 올바른 로그인 200 뒤 카운터 0, Y 창 뒤 오답 1회에 카운터 1 | ✓ H1e 섬, 판정 규칙대로 예약 만료를 두지 않음 |
| R-V10(H3c) | 3 | V10 jar 기동 8·9·7초, migration 오류 0, V9 jar 로그 오류 0, V9 jar 재기동 성공, Flyway 1~10. 트래픽 40회(다른 인스턴스로 간 회차 8·6·6) 오류 0, 역전 0, 재기동 뒤 10회 오류 0, 역전 0 | ✓ H3c 섬 |
| S-pgb-session(H5a) | 3 | 실행 273·362·435, 겹침 15·19·24, 최장 공백 6469·1783·2472 ms, 끝난 뒤 서버에 남은 advisory lock 1·1·1, 오류 0 | ✓ H5a 섬(현행 session lock은 transaction pooling 뒤에서 안전하지 않음) |
| S-pgb-xact(H5b) | 3 | 실행 612·607·627, 겹침 0, 남은 lock 0, 오류 0, idle in transaction 표본 156·150·154 중 xid 보유 0, 최장 공백 314·209·206 ms | ✓ H5b 섬 |
| S-direct-session 대 S-direct-xact(H5c) | 3 + 3 | 실행 617·619·619 대 630·619·619(xact가 session의 99.8~102.1%), 겹침 0 대 0, 오류 0 대 0, 남은 lock 0 대 0 | ✓ H5c 섬 |

판정 규칙대로 H5b와 H5c가 섰으므로 만료 sweep 잠금을 transaction 범위 lock으로 바꿨다(`ExpirySweepLockRepository`). sizing-envelope 3.4에 transaction mode 전제 세 개(advisory lock, 시작 매개변수 `options`, prepared statement)를 적었다.

### G1: lease를 잃은 location claim (EXP-117 H7 인계)

EXP-117 G1 담당(TxBacklog)이 실제 `LocationReservationServiceImpl` 두 개(coordinator A·B), 같은 order PostgreSQL, 가변 시계, MockWebServer location 모형으로 13가지 끼어들기를 돌려 넘긴 결과다. 전체 하네스 3회, 회마다 13개 중 2개가 어긋났다(EXP-117 본문의 "12경우 중 2경우"는 인계 문서에서 13개로 정정됐다). 인계받은 줄인 시험 3건은 수정 전 1건 실패, 수정 뒤 두 번 0건 실패, 변이에서 1건 실패였다. 이 수치는 하위 에이전트가 실행하고 보고한 값이다: local-only(에이전트 보고). 기준 `origin/main` 57e6ccd7.

| 끼어들기 | 결과 | 판정 |
|---|---|---|
| I6: A의 마지막 위치 claim은 원격에 적용됐지만 응답이 lease(30초)보다 늦게 오고, 그 사이 B가 취소해 두 위치를 restore | A의 `updateLocation`·`finishWorkflow`는 lease token이 없어 0행인데 결과를 버리고 `CLAIMED`를 답한다. 호출자 `claimBeforeCapture`는 `CLAIMED`면 매입한다 | 결함: 매입된 돈 + 풀린 재고 |
| I3: `INSUFFICIENT_STOCK`로 실패한 키의 예약 요청이 늦게 도착 | 위치에 고아 예약 1개, 위치 TTL(기본 2분) 뒤 0개. 재고 보존 위반 0 | 채택하지 않음(아래) |
| 나머지 11개(I0·I1·I2·I4·I5·I6b·I7·I8·I9·I10a·I10b) | 고아 0, 매달림 0, 보존 위반 0. I10b는 `NEEDS_REVIEW`로 운영자에게 드러남 | 이상 없음 |

수정: `finish()`가 lease 펜스 결과를 돌려주고, `claim()`은 `CLAIMED`를 기록했을 때만 `CLAIMED`를 답한다. 기록하지 못했으면 workflow를 다시 읽어 취소·종결은 `REJECTED`, 그 밖은 `PENDING`으로 답한다. 기존 lease token이 펜스라 새 열·토큰이 없다. 회귀 시험은 인계받은 별도 클래스(574줄, 위치 모형 포함) 대신 기존 `LocationReservationServiceIntegrationTest`의 MockWebServer 두 대와 지연 응답으로 같은 장면을 재현했다(35줄). 아래 표는 이 브랜치에서 직접 돌렸다.

| 실행 | 코드 | 결과 |
|---|---|---|
| 고치기 전 | 이 브랜치 + `claim()` 원래 코드 | 1/1 실패: `expected: "REJECTED" but was: "CLAIMED"` |
| 고친 뒤 | 이 브랜치 | `LocationReservationServiceIntegrationTest` 19/19, `LocationFaultConvergenceIntegrationTest` 9/9, `RemoteReservationCheckoutIntegrationTest` 16/16 |
| 변이 | 펜스 결과 무시(`if (false & !finish(...))`) | 1/1 실패, 같은 메시지 |

I3을 채택하지 않은 이유(인계 그대로, 다시 재지 않음): 위치 TTL이 2분 안에 예약을 지우고 재고는 보존된다. order 쪽 수정(실패 키 취소나 tombstone)은 품절 실패마다 위치 호출이 하나 늘어난다. 재검토: `inventory.reservation-ttl`이 2분을 넘거나 위치 만료 worker가 꺼지거나 밀릴 때, 운영에서 `FAILED` workflow 키의 위치 `RESERVED` 행이 보일 때, 품절 때 위치 요청 대기가 coordinator read timeout을 넘을 때, 위치 서비스가 `INSUFFICIENT_STOCK` 키를 롤백 대신 tombstone으로 남길 수 있게 될 때.

### PgBouncer 기능 확인 (계수 칸 밖, H5 판정 보조)

PgBouncer 1.24.1(`edoburu/pgbouncer:v1.24.1-p0`), `pool_mode=transaction`, `default_pool_size=5`, PostgreSQL 18.6. 한 번씩 실행한 기능 확인이라 건수 판정에는 쓰지 않는다.

| 확인 | 결과 |
|---|---|
| 기본 `ATLAS_DB_URL`과 같은 시작 매개변수(`options=-c statement_timeout=30000 -c lock_timeout=5000`)로 연결 | `FATAL: unsupported startup parameter in options: statement_timeout`, 연결 거절 |
| `track_extra_parameters = statement_timeout, idle_in_transaction_session_timeout, lock_timeout` + 같은 `options` | 연결됨, 세 값 모두 `SHOW` 0(적용되지 않음), 2회 같음 |
| `options` 없이 + `ALTER ROLE atlas IN DATABASE atlas SET` 세 값 | `SHOW` 30s·5s·1min(적용됨) |
| 직접 연결(pooler 없음) + `options` | `SHOW statement_timeout` 30s |
| S칸 probe, `max_prepared_statements=0`, 4초 | 오류 68건: `prepared statement "S_1" does not exist`(SQLSTATE 26000) |
| S칸 probe, `max_prepared_statements=200`, 4초 | 오류 0건 |

### 변이 시험과 현행 코드 실행

변이마다 소스를 고쳐 해당 시험만 돌리고 파일을 바이트 그대로 되돌렸다(되돌림 확인 9/9, 현행 코드 실행 포함). PostgreSQL 18.6 Testcontainers.

| 변이 | 시험 | 결과 |
|---|---|---|
| 예약 SQL `failures < ?` → `<= ?` | `attemptsStillBeingVerifiedCannotPassTheSubjectLimitTogether` | 실패: expected 3 but was 4 |
| 출처 거절 뒤 예약 반환 제거 | `attemptRejectedByItsOriginDoesNotSpendTheSubjectLimit` | 실패: expected 1 but was 2 |
| 성공 뒤 카운터 삭제(`resetSubject`) 제거 | `correctPasswordsVerifiedAlongsideAWrongOneDoNotLockTheSubject` | 실패: 저장값 3(≤ 1 기대) |
| 주문 경로 재조회 제거 | `staleApprovalSnapshotFromAnotherInstanceDoesNotUndoAPaidCampaignOrder` | 실패: expected PAID but was PAYMENT_PENDING |
| checkout 경로 재조회 제거 | `staleApprovalSnapshotFromAnotherInstanceDoesNotUndoAPaidCheckout` | 실패: expected PAID but was PAYMENT_PENDING |
| claim이 펜스 결과 무시 | `claimWhoseLeaseWasTakenByACancellationIsNotReportedAsClaimed` | 실패: expected REJECTED but was CLAIMED |
| sweep 락을 잡은 직후 commit(락이 sweep 전에 풀림) | `concurrentSweepOfTheSameWorkflowIsSkippedWhileAnotherRunsOutsideAnyTransaction` | 실패: 빈 Optional 기대, 2 |
| sweep 락 연결을 autocommit으로 둠 | `JdbcExpirySweepLockRepositoryTest` 2개 | 실패 2/2(rollback이 25P01로 실패). 상호 배제 변이로는 위 행을 쓴다 |
| 예약 SQL `failures < ?` → `<= ?` | `correctLoginRefusedOnlyBecauseOthersAreStillBeingVerifiedSucceedsOnceTheyFinish`(H1f 선택 a의 시험) | 실패: 거절을 기대했으나 통과 |
| 로그인 성공 뒤 `resetSubject` 제거 | 같은 시험 | 실패: expected 0 but was 1 |

현행 `origin/main` 인증 코드(4개 파일)로 `AuthAttemptLimiterIntegrationTest` 11개를 돌리면 3개가 실패한다: `attemptsStillBeingVerifiedCannotPassTheSubjectLimitTogether`(expected 3 but was 8), `attemptRejectedByItsOriginDoesNotSpendTheSubjectLimit`(expected 1 but was 0, 현행은 예약하지 않음), `everyScopeIsStoredWithinTheBucketConstraints`(현행은 검증 전에 주체 행을 만들지 않음). 성공 경로 시험 `correctPasswordsVerifiedAlongsideAWrongOneDoNotLockTheSubject`는 현행 코드에서도 통과한다. 현행은 올바른 시도가 칸을 쓰지 않으므로 성공 경로가 원래 깨지지 않았고, 이 시험은 예약 수정의 성공 경로를 지킨다.

결제 재조회 시험 2개는 이 브랜치에서 `PaymentWorkflowCrashWindowIntegrationTest` 5/5 통과(캠페인 주문 경로 시험은 이번에 처음 실행). 만료 sweep 잠금 변경 뒤 `BuyerFlowIntegrationTest` 50, `ScheduledExpiryMultiInstanceIntegrationTest` 3(애플리케이션 컨텍스트 2개로 checkout 만료가 정확히 한 번인지 확인), `SellerCouponRedemptionIntegrationTest` 7, `ScheduledExpiryMetricsWorkerTest` 4, `JdbcExpirySweepLockRepositoryTest` 2가 모두 통과했다.

### 오염된 첫 1부 실행 (잠금 없이, 판정에 쓰지 않음)

01:12:17~01:20:13에 1부가 잠금 없이 돌았다. 잠금 대기 반복문만 끝나고 구동 스크립트는 계속 돌았기 때문이다(그 뒤 스크립트는 잠금을 실제로 쥐지 않으면 멈추도록 고쳤다). 그 시간은 다른 에이전트의 잠금(`exp117h10`) 창이었고 그 에이전트의 컨테이너가 떠 있었으며 load1은 6.8~12.4였다. 건수는 위 잠금 아래 재실행과 같은 방향이지만 판정에는 재실행 값만 쓴다. 같은 창의 다른 에이전트 측정도 오염됐을 수 있어 그 에이전트에게 알렸다.

| 칸 | 오염된 실행의 결과 |
|---|---|
| A-N1-base | 44·45·48·48·46 |
| A-N3-base | 84·88·87·89·90, 503 6·2·3·1·0건 |
| A-N1-fix, A-N3-fix | 회차마다 10. CPU 추정 8.9~11.5(1대), 5.5~16.7(3대) |
| A-ok | 현행 성공 15·거절 0, 수정 성공 10·거절 5(거절 응답 최대 110~173 ms), 카운터 0 |
| P-N1-base, P-N2-base, P-N2-fix | 모드마다 100회 중 역전 0 |
| J-N2 | 401 7·7·14 |
