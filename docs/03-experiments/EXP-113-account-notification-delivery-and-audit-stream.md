# EXP-113: 인증 메일 전달의 유실·스레드 점유를 재현하고 SMTP timeout·알림 outbox·Spring 내부 이벤트를 같은 칸에서 비교하며, 감사 스트림의 필요성을 가른다

- 상태: 진행 중(사전 등록). 가설과 반증 조건은 첫 측정 전 이 커밋으로 고정한다.
- 날짜: 2026-11-27
- 관련: [#718](https://github.com/Neo1228/ATLAS/issues/718), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), ADR-0102, D-181

## 문제 한 문장

이메일 확인·비밀번호 재설정 메일은 계정 transaction이 커밋된 뒤 요청 스레드에서 SMTP로 보내고 실패를 WARN 한 줄로 삼키므로, 커밋 직후 프로세스가 죽거나 SMTP가 오류를 내면 메일이 재시도도 지표도 없이 사라지고, SMTP가 멈추면 timeout이 없어 요청 스레드(최대 40개)가 무기한 묶인다.

## 코드 읽기 결과 (측정 전, 기준 `origin/main` d2c8395f)

- `AccountServiceImpl.registerBuyer`, `requestPasswordReset`, `requestEmailVerification`은 `TransactionTemplate` 안에서 토큰을 만들고(`account_action_token`에는 SHA-256 해시만 저장) `sendAfterCommit`으로 `TransactionSynchronization.afterCommit`에 전송을 건다. 전송은 요청 스레드에서 동기 실행되고 `RuntimeException`은 `"Account email delivery failed; user can request a replacement token"` WARN 한 줄로 끝난다. 재시도·지표·영속 기록이 없다.
- 평문 토큰은 메모리와 메일 본문에만 있다. 전송이 실패하면 DB에는 해시만 남으므로 시스템이 같은 메일을 다시 만들 방법이 없다. 복구는 사용자의 재요청(`POST /api/auth/email-verification/request`, `POST /api/auth/password-reset/request`)뿐이다.
- `application.yml`과 `compose.yaml`에 `spring.mail.properties`가 없다. JavaMail의 `mail.smtp.connectiontimeout`, `mail.smtp.timeout`, `mail.smtp.writetimeout` 기본값은 무한이다(설정하지 않으면 소켓 timeout을 걸지 않는다). 즉 현재 SMTP 정지는 요청 스레드를 무기한 붙잡을 수 있다(측정으로 확인할 대상).
- Tomcat `server.tomcat.threads.max`는 40이다. 가입·재설정 요청 40개가 SMTP에 묶이면 다른 HTTP 요청이 처리될 스레드가 없다(측정으로 확인할 대상).
- 애플리케이션 내부 이벤트(`ApplicationEventPublisher`, `@TransactionalEventListener`, `@Async`, `@EnableAsync`) 사용처는 운영 코드에 0곳이다.
- 기존 DB 큐 패턴: `paypal_webhook_retry`(임대·재예약·완료), `settlement_payout_dispatch_outbox`, `payment_recovery_job`. worker는 `atlas-application/service`에, 전용 scheduler는 `SchedulingConfiguration`에 둔다.

## 비교할 안

| 안 | 내용 | 바뀌는 것 |
|---|---|---|
| A0 | 현행. 커밋 뒤 요청 스레드에서 동기 전송, 실패 삼킴, timeout 없음 | 없음 |
| A1 | A0 + JavaMail 연결·읽기·쓰기 timeout 5초 | 설정만 |
| A2 | 같은 transaction에 알림 outbox 행을 기록하고 전용 worker가 전송·재시도(지수 backoff, 최대 횟수 뒤 FAILED), 같은 (계정, 목적)의 대기 행 1개로 합침, 결과 지표. 토큰은 outbox에 두지 않고 worker가 전송 직전에 새로 발급(참조 방식) | 테이블 1개(V9), worker 1개, 플래그 |
| A3 | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`(Spring Boot 기본 실행기) | 이벤트·리스너, `@EnableAsync` |

토큰 저장 방식(A2): 평문 금지는 전제다. 후보는 (가) 참조만 저장하고 worker가 전송 직전에 토큰을 발급, (나) 요청 transaction에서 만든 토큰을 대칭키로 암호화해 저장. (가)는 DB에 살아 있는 자격 증명을 어떤 형태로도 남기지 않고, (나)는 키 관리와 키 유출 시 DB 사본에서 재설정 링크를 복원할 수 있는 위험이 생긴다. (가)의 비용은 전송은 됐는데 완료 기록 전에 죽으면 재전송이 새 토큰을 발급해 앞 메일의 링크가 무효가 된다는 점이다(사용자가 재요청할 때와 같은 동작). 이 비용은 C5에서 잰다.

A3와 측정 구동기는 저장소에 올리지 않는다(실험 전용 패치). 결과를 쓸 때 `삭제한 파일의 내용`에 옮긴다.

## 측정 방법 (사전 등록)

공통: bootJar 1개(A0~A2는 같은 jar에서 설정으로 고르고, A3는 실험 패치를 더한 jar), PostgreSQL 18.6 컨테이너(라벨 `atlasexp=notify`), JDK 25, 결제 provider 비활성. SMTP는 Python으로 쓴 가짜 서버로 모드를 바꾼다: `ok`(즉시 250), `slow`(응답마다 1초 지연), `hang`(연결은 받고 인사말을 보내지 않음), `err`(MAIL FROM에 451), `acklost`(DATA 본문 끝까지 받아 기록한 뒤 250을 보내지 않음). `ok`로 바꿀 때 묶인 연결은 닫는다(서버 재시작과 같음). 받은 메일은 수신자·시각·토큰으로 기록한다. 인증 시도 제한은 측정을 막지 않도록 출처 한도를 올린다. 시간 측정 run은 호스트 잠금(`measure-lock.sh`) 안에서만 하고 run마다 잠금 상태(load1, 다른 라벨 컨테이너)를 적는다. load1 > 12이거나 다른 라벨 스택이 있으면 오염으로 표시하고 본표에서 뺀다. 결론은 건수로 내고 시간은 맥락용이다.

| 칸 | 조건 | run당 요청 | 지표 |
|---|---|---|---|
| C1 커밋 직후 종료 | SMTP `hang`. 가입 20건 동시 요청, 20건 커밋과 SMTP 연결 1개 이상을 확인하면 `kill -9`, SMTP `ok`로 재기동, 재기동 후 A0·A1·A3 60초, A2는 임대 만료(실험값 30초) 포함 120초 대기 | 20 | 유실 건수(커밋된 계정 중 메일 0통) |
| C2 SMTP 정지 | SMTP `hang`. 가입 48건 동시(Tomcat 40 초과), 클라이언트 timeout 30초. 3초 뒤 무관한 `GET /api/products`를 5초 timeout으로 10번 탐침, 10초 뒤 스레드 덤프에서 SMTP 대기 스레드 수 | 48 | 요청 지연 p50/p95·timeout 수, 탐침 성공 수·지연, SMTP에 묶인 Tomcat·실행기 스레드 수 |
| C3 SMTP 오류와 복구 | SMTP `err` 상태에서 가입 20건(c = 4), 30초 유지 뒤 `ok`, 180초 대기 | 20 | 복구 후 전달 건수, 복구 시각부터 전달까지 시간, 실패 지표 |
| C4 정상 | SMTP `ok`, 가입 50건(c = 4), 받은 토큰으로 `POST /api/auth/verify-email` | 50 | 요청 지연 p50/p95, 전달 시간 p50/p95, 중복 수, 토큰 확인 성공 수 |
| C5 응답 유실 후 종료(A2) | SMTP `acklost`. 가입 5건, 첫 메일이 기록되면 `kill -9`, `ok`로 재기동 120초 | 5 | 수신자별 메일 수(중복), 최신 토큰 확인 성공, 앞 토큰 거절 |
| C6 SMTP 지연 | SMTP `slow`, 가입 20건(c = 4) | 20 | 요청 지연 p50/p95, 전달 건수 |

반복: 칸·안마다 n = 3 run(C5는 A2만 n = 3). 안 순서는 칸마다 고정(A0, A1, A2, A3).

감사 스트림(3.3): 감사·행동 기록 테이블을 전수 조사(열, 불변 보호, 쓰는 곳, 읽는 곳, 색인)하고, 요구사항·ADR·운영 문서·컨트롤러에서 여러 테이블을 가로지르는 감사 질의 요구를 찾는다. 요구가 있으면 현재 테이블로 그 질의를 쓸 수 있는지와 비용을 재고, 없으면 만들지 않고 재검토 조건을 둔다.

## 가설과 반증 조건 (측정 실행 전 등록)

| # | 가설 | 반증 조건 | 지표 |
|---|---|---|---|
| H1 | C1에서 A0와 A1은 SMTP에 묶여 있던 커밋된 메일을 모두 잃는다(유실률 100%) | 3 run 합계에서 묶여 있던 메일 1통이라도 재기동 뒤 전달 | 유실 건수/커밋 건수 |
| H2 | C1에서 A3도 잃는다(run마다 유실 ≥ 1, 기대 100%) | 어느 run이든 유실 0 | 유실 건수 |
| H3 | C1에서 A2는 잃지 않는다(60건 중 유실 0), 마지막 전달은 재기동 후 120초 안 | 유실 ≥ 1, 또는 120초 초과 | 유실 건수, 재기동→마지막 전달 시간 |
| H4 | C2에서 A0는 SMTP 대기로 Tomcat 스레드 40개가 모두 묶이고 무관한 탐침 10번 중 성공 ≤ 2번(5초 안), 가입 요청 중 30초 timeout ≥ 40건 | 탐침 성공 ≥ 5번, 또는 timeout < 20건 | 탐침 성공 수, timeout 수, 묶인 스레드 수 |
| H5 | C2에서 A1은 요청 p50이 4.5~8초(읽기 timeout 5초)이고 탐침은 성공하지만(≥ 8/10), 메일은 모두 잃는다(복구 뒤 전달 0) | p50 < 4.5초 또는 > 8초, 또는 탐침 성공 < 8, 또는 전달 ≥ 1 | p50, 탐침, 전달 수 |
| H6 | C2와 C6에서 A2의 요청 p95는 C4의 A0 p95의 2배 이하이고 300 ms 이하다(SMTP가 요청 경로에서 빠짐) | 둘 중 하나라도 초과 | p95 |
| H7 | C3에서 A0·A1·A3는 오류 동안 요청한 메일을 복구 뒤 하나도 전달하지 않는다. A2는 60건 모두 전달하고 복구부터 마지막 전달까지 ≤ 90초(backoff 상한 안), 실패 지표가 오른다 | A0·A1·A3 전달 ≥ 1, 또는 A2 전달 < 60, 또는 90초 초과, 또는 실패 지표 0 | 전달 수, 시간, 지표 |
| H8 | C4에서 A2의 중복은 0이고 전달 시간 p95 ≤ 2초(폴링 1초), 토큰 확인 150/150 성공 | 중복 ≥ 1, p95 > 2초, 확인 실패 ≥ 1 | 중복, p95, 확인 수 |
| H9 | C5에서 A2는 응답 유실 run마다 최대 1건의 중복을 만들고, 그 수신자의 최신 토큰은 확인에 성공하고 앞 토큰은 거절된다 | run당 중복 > 1, 최신 토큰 거절, 또는 앞 토큰 성공 | 중복 수, 확인 결과 |
| H10 | A2의 outbox 행에는 토큰·토큰 해시·이메일이 없다 | outbox 열에 그중 하나라도 존재 | 스키마 |
| H11 | 감사 테이블을 가로지르는 질의를 요구하는 요구사항·ADR·운영 절차·엔드포인트가 없고, 가장 가까운 질문(행위자별 운영 조치)은 현재 테이블의 UNION ALL로 쓸 수 있다 | 그런 요구가 있고 현재 테이블로 답할 수 없음(행위자·시각·대상 열 부재) | 조사 결과 |

판정 뒤 채택 규칙: A1은 H5가 서면 기본값으로 넣는다(동작 보존이 아니라 무한 대기를 유한으로 바꾸는 더 안전한 기본값). A2는 H3·H7이 서고 H8이 서면 옵트인 플래그로 넣고, 기본값 전환은 운영 문서의 조건으로 남긴다. A3는 H2가 서면 기각한다. 감사 스트림은 H11이 서면 만들지 않는다.

## 한계 (실행 전에 알고 있는 것)

- 단일 호스트, Colima VM, Apple Silicon, 가짜 SMTP. 실제 메일 제공자의 지연·오류 분포가 아니다.
- C1의 "커밋 직후 종료"는 SMTP가 멈춘 동안의 `kill -9`로 만든다. 커밋과 전송 사이의 수 ms 창을 정확히 겨누지 않지만 같은 상태(커밋됨, 아직 전송 안 됨)를 만든다.
- A2의 임대 시간은 C1에서만 실험값(30초)이고 운영 기본값은 그보다 길다. C1의 전달 시간은 그 값에 비례한다.
