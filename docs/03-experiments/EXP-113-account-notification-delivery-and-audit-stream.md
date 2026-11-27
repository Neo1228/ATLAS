# EXP-113: 인증 메일 전달의 유실·스레드 점유를 재현하고 SMTP timeout·알림 outbox·Spring 내부 이벤트를 같은 칸에서 비교하며, 감사 스트림의 필요성을 가른다

- 상태: ✓ 완료(PostgreSQL 18.6, 단일 호스트, 가짜 SMTP, 칸·안마다 n = 3, 잠금 아래). 가설과 반증 조건은 첫 측정 전 커밋(`22630fc2`)으로 고정했고, 정정 1건·사후 안 A1b·사용자 지시로 추가한 감사 로그 가설은 각각 해당 측정 전에 커밋했다. 반증된 가설은 H4·H5·H6(C2)·H8이다.
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

## 사전 등록 정정 (측정 구동기 시운전 뒤, 본 측정 전)

시운전(run 1회씩, 잠금 밖, 본표에서 제외)으로 구동기를 확인한 뒤 고친 것이다. 가설과 반증 조건은 바꾸지 않았다.

- 대기 시간: 재시도 경로가 없는 A0·A1·A3는 복구 또는 재기동 뒤 C1 30초, C2 20초, C3 30초만 기다린다(재시도가 없으므로 그 뒤에 도착할 메일이 없다). A2는 C1 120초, C2·C3 180초 또는 모두 도착할 때까지.
- A2의 임대 30초는 C1만이 아니라 A2의 모든 칸에 같은 값으로 둔다(한 번 띄운 프로세스로 칸을 이어 돌리기 때문). 정상·지연 칸은 임대가 끝나기 전에 전송이 끝나므로 결과에 영향이 없다.
- C5는 재기동 뒤 수신자 5명이 모두 도착해도 끝내지 않고, 응답이 유실된 메일의 재전송(임대 만료 뒤)까지 최대 120초 기다린다. 시운전에서 임대 만료 전에 끝나 중복을 세지 못했다.
- C6은 첫 요청부터 최소 10초 또는 요청이 끝날 때까지를 창으로 두고 창 안의 전달 수를 센 뒤 SMTP를 `ok`로 돌려 남은 전달을 센다.

## 사후 추가 안 A1b (시운전에서 본 현상, A1b 측정 전 등록)

시운전 C2(A0, n = 1, 잠금 밖)에서 가입 48건 중 커밋은 10건뿐이고 38건과 무관한 탐침 10건이 모두 503이었다. SMTP에 묶인 스레드는 Tomcat 스레드 10개였다. Spring의 `afterCommit` 콜백은 transaction 자원 정리(연결 반환) 전에 실행되므로, 현행 경로는 SMTP를 기다리는 동안 Hikari 연결(풀 10개)을 쥐고 있다고 보고, 사전 등록에 없던 안을 더한다. 사전 등록 가설이 아니므로 결과는 별도 표로 둔다.

- A1b: A1 + 전송을 `TransactionTemplate.execute`가 끝난 뒤(연결 반환 뒤) 같은 요청 스레드에서 실행. 순서·스레드·실패 처리는 A1과 같다.
- H12: C2에서 A1b는 무관한 탐침 성공 ≥ 8/10, 가입 응답 503 = 0이다. 반증: 탐침 성공 < 8 또는 503 ≥ 1.
- H13: C4에서 A1b의 요청 p95는 A1의 1.5배 이하다(전송 위치만 바뀜). 반증: 초과.
- 측정: C4·C6·C2를 n = 3, 같은 잠금 조건.

## 사용자 지시로 추가: 통합 감사·도메인 이벤트 로그 (구현·측정 전 등록)

2026-11-27 측정 도중 사용자가 "감사 도메인 이벤트 로그는 필요해 도입해도 괜찮다"고 지시했다. H11(요구 문서 없음)은 저장소 문서 기준의 판정이고, 이 지시가 그 요구다. 그래서 만들지 않는 결정 대신 아래 설계를 구현하고 측정한다.

- 설계 D1: 추가 전용 테이블 `domain_audit_event`(V9에 함께 둔다). 열은 `event_id`(uuidv7), `tx_id`(`pg_current_xact_id()`, ADR-0067과 같은 xmin 안전 cursor용), `occurred_at`, `source_table`, `source_id`, `event_type`, `aggregate_type`·`aggregate_id`, `actor`(없으면 NULL), `payload`(원본 행의 jsonb에서 자유 문자열 `reason`, `idempotency_key`, `request_facts_sha256`를 뺀 것). 쓰기는 원본 테이블의 `AFTER INSERT` 행 트리거가 같은 transaction에서 한다(쓰기 코드 수정 없음, 앞으로의 쓰기 경로도 덮음). UPDATE·DELETE·TRUNCATE는 거절한다. 색인은 `(actor, occurred_at DESC)`(actor 있는 행), `(aggregate_type, aggregate_id, occurred_at)`, `(tx_id)`.
- 대상 9개: 운영자 조치 6개(`account_suspension_action`, `payment_recovery_operator_action`, `paypal_webhook_retry_operator_audit`, `seller_payout_profile_action`, `settlement_payout_operator_action`, `paypal_balance_report_disposition`), 재고 감사 2개(`inventory_capacity_adjustment`, `inventory_transfer`), 돈 도메인 이벤트 1개(`settlement_journal`: capture, checkout capture, 환불, 지급 분개).
- 대안: (가) 서비스마다 앱 코드로 기록(9곳 수정, 새 쓰기 경로가 빠질 수 있음, 모듈 간 공용 기록기 필요), (나) 기존 `order_inventory_event` 재사용(예약 workflow 전용 열 구조), (다) CDC·Kafka(인프라 추가). 트리거 방식을 고른 이유는 원자성과 누락 방지이고, 비용은 원본 INSERT마다 행 하나가 더 쓰인다는 것이다.

| # | 가설 | 반증 조건 | 지표 |
|---|---|---|---|
| H14 | 대상 9개 테이블의 INSERT 한 건마다 로그 행이 정확히 하나 같은 transaction에 생기고, 롤백하면 로그 행도 없다. 실제 저장소 코드(`AccountSuspensionRepository.apply`)로 쓴 조치도 로그에 행위자와 함께 남는다 | 테이블 하나라도 0개 또는 2개 이상, 롤백 뒤 로그 행 존재, 행위자 누락 | 시험 |
| H15 | 로그의 UPDATE·DELETE·TRUNCATE는 거절된다 | 하나라도 성공 | SQLSTATE |
| H16 | 트리거가 원본 INSERT 한 건에 더하는 시간은 p50 ≤ 0.3 ms다(`settlement_journal` 복사본, 트리거 유·무, pgbench c = 1, 10초, n = 3) | 중앙값 차이 > 0.3 ms | pgbench 지연 |
| H17 | 행위자별 조치 질의(최근 100건)는 테이블당 20만 행에서 통합 로그 + 색인이 현재 테이블 UNION ALL(색인 없음)보다 10배 이상 빠르다 | 10배 미만 | 실행 시간 중앙값 |
| H18 | 로그 `payload`에 `reason`·`idempotency_key`·`request_facts_sha256`·이메일 열이 없다 | 하나라도 있음 | 시험 |

## 한계 (실행 전에 알고 있는 것)

- 단일 호스트, Colima VM, Apple Silicon, 가짜 SMTP. 실제 메일 제공자의 지연·오류 분포가 아니다.
- C1의 "커밋 직후 종료"는 SMTP가 멈춘 동안의 `kill -9`로 만든다. 커밋과 전송 사이의 수 ms 창을 정확히 겨누지 않지만 같은 상태(커밋됨, 아직 전송 안 됨)를 만든다.
- A2의 임대 시간은 C1에서만 실험값(30초)이고 운영 기본값은 그보다 길다. C1의 전달 시간은 그 값에 비례한다.

## 결과

측정 시각 2026-11-27 12:16~12:54, 잠금 `notify` 아래 안마다 다시 잡음. run마다 load1 2.96~10.08(모두 ≤ 12). 다른 에이전트의 라벨 컨테이너 `eps-pg` 1개가 내내 떠 있었고 한 번 표본한 CPU는 0.00%였다. 사전 등록 규칙의 문자 그대로는 "다른 라벨 스택 있음"이라 모든 run을 오염 후보로 표시한다. 결론은 부하에 민감하지 않은 건수(유실·중복·전달·탐침 성공·503)로 내고, 시간 값은 맥락으로만 쓴다. 시운전 run(n = 1)은 표에서 뺐다. A1·A2 jar는 커밋 91ba8d12(전송이 `afterCommit` 안), A1b는 4eaf06b8(연결 반환 뒤 전송)이다.

### C1 커밋 직후 종료 (H1·H2·H3)

A0·A1은 SMTP가 멈춘 요청 10개가 Hikari 연결 10개를 모두 쥐어 나머지 10개 가입이 커밋 전에 503으로 끝났다(그래서 커밋 10). 커밋된 메일은 모두 잃었다.

| 안 | run | 커밋 | 종료 시 SMTP 연결 | 재기동 뒤 전달 | 유실 | 중복 | 재기동→마지막 전달 s |
|---|---|---|---|---|---|---|---|
| A0 | 1 | 10 | 10 | 0 | 10 | 0 | - |
| A0 | 2 | 10 | 10 | 0 | 10 | 0 | - |
| A0 | 3 | 10 | 10 | 0 | 10 | 0 | - |
| A0 | 합 | 30 | | 0 | 30 | 0 | |
| A1 | 1 | 10 | 10 | 0 | 10 | 0 | - |
| A1 | 2 | 10 | 10 | 0 | 10 | 0 | - |
| A1 | 3 | 10 | 10 | 0 | 10 | 0 | - |
| A1 | 합 | 30 | | 0 | 30 | 0 | |
| A2 | 1 | 20 | 1 | 20 | 0 | 0 | 23.1 |
| A2 | 2 | 20 | 1 | 20 | 0 | 0 | 23.2 |
| A2 | 3 | 20 | 1 | 20 | 0 | 0 | 23.3 |
| A2 | 합 | 60 | | 60 | 0 | 0 | |
| A3 | 1 | 20 | 20 | 0 | 20 | 0 | - |
| A3 | 2 | 20 | 20 | 0 | 20 | 0 | - |
| A3 | 3 | 20 | 20 | 0 | 20 | 0 | - |
| A3 | 합 | 60 | | 0 | 60 | 0 | |

### C2 SMTP 정지, 가입 48건 동시 (H4·H5·H6, A1b의 H12)

| 안 | run | 상태 | p50 ms | p95 ms | timeout | 탐침 성공/10 | 탐침 상태 | SMTP 대기 스레드(10초) | 커밋 | 복구 뒤 전달 |
|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 1 | timeout: 10, 503: 38 | 785.6 | 30002.3 | 10 | 0 | 503: 10 | http-nio-127.0.0.1-48180-exec: 10 | 10 | 0 |
| A0 | 2 | timeout: 10, 503: 38 | 742.9 | 30003.5 | 10 | 0 | 503: 10 | http-nio-127.0.0.1-48180-exec: 10 | 10 | 0 |
| A0 | 3 | timeout: 10, 503: 38 | 770.1 | 30003.5 | 10 | 0 | 503: 10 | http-nio-127.0.0.1-48180-exec: 10 | 10 | 0 |
| A1 | 1 | 202: 10, 503: 38 | 804.8 | 5306.9 | 0 | 6 | 200: 6, 503: 4 | 0 | 10 | 0 |
| A1 | 2 | 202: 10, 503: 38 | 763.6 | 5246.0 | 0 | 6 | 200: 6, 503: 4 | 0 | 10 | 0 |
| A1 | 3 | 202: 10, 503: 38 | 833.1 | 5311.6 | 0 | 6 | 200: 6, 503: 4 | 0 | 10 | 0 |
| A1b | 1 | 202: 48 | 5325.5 | 10391.6 | 0 | 10 | 200: 10 | http-nio-127.0.0.1-48180-exec: 8 | 48 | 0 |
| A1b | 2 | 202: 48 | 5334.0 | 10354.9 | 0 | 10 | 200: 10 | http-nio-127.0.0.1-48180-exec: 8 | 48 | 0 |
| A1b | 3 | 202: 48 | 5316.5 | 10325.5 | 0 | 10 | 200: 10 | http-nio-127.0.0.1-48180-exec: 8 | 48 | 0 |
| A2 | 1 | 202: 48 | 368.0 | 389.5 | 0 | 10 | 200: 10 | atlas-account-notification: 1 | 48 | 48 |
| A2 | 2 | 202: 48 | 366.5 | 388.0 | 0 | 10 | 200: 10 | atlas-account-notification: 1 | 48 | 48 |
| A2 | 3 | 202: 48 | 445.5 | 472.8 | 0 | 10 | 200: 10 | atlas-account-notification: 1 | 48 | 48 |
| A3 | 1 | 202: 48 | 398.2 | 457.5 | 0 | 10 | 200: 10 | SimpleAsyncTaskExecutor: 48 | 48 | 0 |
| A3 | 2 | 202: 48 | 336.6 | 368.4 | 0 | 10 | 200: 10 | SimpleAsyncTaskExecutor: 48 | 48 | 0 |
| A3 | 3 | 202: 48 | 794.8 | 899.6 | 0 | 10 | 200: 10 | SimpleAsyncTaskExecutor: 48 | 48 | 0 |

- A0·A1: SMTP를 기다린 요청 10개가 각자 DB 연결을 쥔 채 멈춰(Spring `afterCommit`은 연결 반환 전에 실행된다) 풀 10개가 고갈되고, 나머지 가입 38건과 무관한 `GET /api/products` 탐침이 `connection-timeout` 500 ms 뒤 503이 됐다. A1은 5초 뒤 풀려 탐침 6/10이 성공했다.
- A1b: 연결을 돌려준 뒤 보내므로 503 0건, 탐침 10/10, 48건 모두 커밋. 요청은 읽기 timeout만큼(p50 약 5.3초) 걸리고 메일은 모두 잃는다.
- A2: 요청 경로에 SMTP가 없다. SMTP에 묶인 스레드는 worker 1개뿐이고 복구 뒤 48/48 전달.
- A3: 요청은 빠르지만 실행기를 정하지 않은 `@Async`가 `SimpleAsyncTaskExecutor`로 전송마다 새 스레드를 만들어 48개가 SMTP에 묶였고, 복구 뒤 전달 0(재시도 없음).

### C3 SMTP 오류 30초와 복구 (H7)

| 안 | run | 상태 | p50 ms | p95 ms | 커밋 | 장애 중 전달 | 복구 뒤 전달 | 복구→마지막 s | 지표 |
|---|---|---|---|---|---|---|---|---|---|
| A0 | 1 | 202: 20 | 50.1 | 72.9 | 20 | 0 | 0 | - | - |
| A0 | 2 | 202: 20 | 60.0 | 85.8 | 20 | 0 | 0 | - | - |
| A0 | 3 | 202: 20 | 63.4 | 94.4 | 20 | 0 | 0 | - | - |
| A1 | 1 | 202: 20 | 81.7 | 124.8 | 20 | 0 | 0 | - | - |
| A1 | 2 | 202: 20 | 65.1 | 84.9 | 20 | 0 | 0 | - | - |
| A1 | 3 | 202: 20 | 84.4 | 121.8 | 20 | 0 | 0 | - | - |
| A2 | 1 | 202: 20 | 44.6 | 57.4 | 20 | 0 | 20 | 6.0 | retry_scheduled 증가 |
| A2 | 2 | 202: 20 | 47.9 | 54.0 | 20 | 0 | 20 | 6.4 | retry_scheduled 증가 |
| A2 | 3 | 202: 20 | 48.1 | 60.6 | 20 | 0 | 20 | 7.2 | retry_scheduled 증가 |
| A3 | 1 | 202: 20 | 49.9 | 56.8 | 20 | 0 | 0 | - | - |
| A3 | 2 | 202: 20 | 51.3 | 63.5 | 20 | 0 | 0 | - | - |
| A3 | 3 | 202: 20 | 52.9 | 64.4 | 20 | 0 | 0 | - | - |

A0·A1·A3 각 3 run은 모두 상태 202, 장애 중 전달 0, 복구 뒤 30초 동안 전달 0이었다(표 생략 없이 집계: A0 60건 중 0, A1 60건 중 0, A3 60건 중 0). A2는 60/60을 복구 뒤 6.0~7.2초에 마쳤고 `outcomes{outcome=retry_scheduled}`가 run마다 20씩 늘었다.

### C4 정상 (H8, A1b의 H13)

| 안 | run | 상태 | p50 ms | p95 ms | 전달 | 중복 | 전달 p50 ms | 전달 p95 ms | 토큰 확인 |
|---|---|---|---|---|---|---|---|---|---|
| A0 | 1 | 202: 50 | 57.4 | 147.1 | 50 | 0 | 54.9 | 141.8 | 50/50 |
| A0 | 2 | 202: 50 | 57.9 | 114.3 | 50 | 0 | 55.8 | 108.9 | 50/50 |
| A0 | 3 | 202: 50 | 50.1 | 61.5 | 50 | 0 | 48.6 | 58.9 | 50/50 |
| A0 | 중앙값 | | 57.4 | 114.3 | 150 | 0 | 54.9 | 108.9 | 150/150 |
| A1 | 1 | 202: 50 | 54.8 | 125.3 | 50 | 0 | 52.6 | 121.6 | 50/50 |
| A1 | 2 | 202: 50 | 51.3 | 59.1 | 50 | 0 | 49.3 | 57.8 | 50/50 |
| A1 | 3 | 202: 50 | 50.2 | 101.9 | 50 | 0 | 47.9 | 100.4 | 50/50 |
| A1 | 중앙값 | | 51.3 | 101.9 | 150 | 0 | 49.3 | 100.4 | 150/150 |
| A1b | 1 | 202: 50 | 51.0 | 126.5 | 50 | 0 | 49.3 | 122.7 | 50/50 |
| A1b | 2 | 202: 50 | 49.9 | 59.2 | 50 | 0 | 48.1 | 57.2 | 50/50 |
| A1b | 3 | 202: 50 | 51.4 | 55.0 | 50 | 0 | 49.6 | 53.4 | 50/50 |
| A1b | 중앙값 | | 51.0 | 59.2 | 150 | 0 | 49.3 | 57.2 | 150/150 |
| A2 | 1 | 202: 50 | 52.6 | 99.5 | 50 | 0 | 1445.0 | 2405.9 | 50/50 |
| A2 | 2 | 202: 50 | 43.1 | 53.1 | 50 | 0 | 1380.7 | 2367.1 | 50/50 |
| A2 | 3 | 202: 50 | 49.4 | 76.2 | 50 | 0 | 1480.8 | 2432.5 | 50/50 |
| A2 | 중앙값 | | 49.4 | 76.2 | 150 | 0 | 1445.0 | 2405.9 | 150/150 |
| A3 | 1 | 202: 50 | 65.1 | 138.8 | 50 | 0 | 71.8 | 165.8 | 50/50 |
| A3 | 2 | 202: 50 | 72.8 | 112.6 | 50 | 0 | 79.8 | 130.4 | 50/50 |
| A3 | 3 | 202: 50 | 56.9 | 68.2 | 50 | 0 | 62.0 | 73.4 | 50/50 |
| A3 | 중앙값 | | 65.1 | 112.6 | 150 | 0 | 71.8 | 130.4 | 150/150 |

### C5 응답 유실 뒤 종료, A2 (H9)

| 안 | run | 종료 전 기록 | 재기동 뒤 수신자 | 중복 | 중복 수신자 | 앞 토큰 확인 성공 | 최신 토큰 확인 성공 |
|---|---|---|---|---|---|---|---|
| A2 | 1 | 1 | 5 | 1 | 1 | 0 | 1 |
| A2 | 2 | 1 | 5 | 1 | 1 | 0 | 1 |
| A2 | 3 | 1 | 5 | 1 | 1 | 0 | 1 |

### C6 SMTP 지연(응답마다 1초)

| 안 | run | 상태 | p50 ms | p95 ms | 창 s | 창 안 전달 | ok 뒤 전달 |
|---|---|---|---|---|---|---|---|
| A0 | 1 | 202: 20 | 7089.0 | 7105.3 | 35.5 | 20 | 20 |
| A0 | 2 | 202: 20 | 7097.9 | 7108.7 | 35.5 | 20 | 20 |
| A0 | 3 | 202: 20 | 7107.1 | 7132.3 | 35.5 | 20 | 20 |
| A1 | 1 | 202: 20 | 7095.2 | 7110.0 | 35.5 | 20 | 20 |
| A1 | 2 | 202: 20 | 7094.6 | 7105.4 | 35.5 | 20 | 20 |
| A1 | 3 | 202: 20 | 7095.5 | 7099.9 | 35.5 | 20 | 20 |
| A1b | 1 | 202: 20 | 7092.9 | 7131.0 | 35.5 | 20 | 20 |
| A1b | 2 | 202: 20 | 7107.8 | 7121.5 | 35.6 | 20 | 20 |
| A1b | 3 | 202: 20 | 7099.4 | 7125.8 | 35.5 | 20 | 20 |
| A2 | 1 | 202: 20 | 52.6 | 65.8 | 11.2 | 1 | 20 |
| A2 | 2 | 202: 20 | 46.8 | 54.3 | 11.2 | 1 | 20 |
| A2 | 3 | 202: 20 | 53.5 | 59.8 | 11.2 | 1 | 20 |
| A3 | 1 | 202: 20 | 54.7 | 59.9 | 10.0 | 20 | 20 |
| A3 | 2 | 202: 20 | 50.7 | 60.7 | 10.0 | 20 | 20 |
| A3 | 3 | 202: 20 | 52.1 | 58.4 | 10.0 | 20 | 20 |

A0·A1·A1b는 가입 한 건이 SMTP 왕복 7번(약 7.1초)을 요청 스레드에서 기다렸다. A2는 요청이 빠르지만 worker가 한 통씩 보내 10초 창 안 전달은 1통이고, SMTP가 정상으로 돌아온 뒤 모두 전달됐다. A3는 창 안에 20통을 모두 보냈다(스레드 20개).

### 감사·분석 스트림 (리뷰 3.3, H11)

조사 방법: 마이그레이션 V1~V7의 `CREATE TABLE`·`CREATE TRIGGER`·`CREATE INDEX`, 운영 코드의 INSERT·SELECT 위치, `docs/`(요구사항·ADR·운영 문서)와 컨트롤러를 검색했다. 검색어: `UNION ALL`, `timeline|타임라인|통합 조회|actor별|by actor|cross.?table|감사 조회|감사 로그|주문 타임라인`, `감사|audit|append-only|operator_action|_history`, `INDEX.*actor`.

| 테이블 | 행위자·시각·대상·행동 열 | DB 가드 | 읽는 곳 |
|---|---|---|---|
| `account_suspension_action` | `actor`, `created_at`, `account_id`, `action` | 없음 | `GET /api/internal/accounts/{accountId}/suspension/history` |
| `payment_recovery_operator_action` | `actor`, `created_at`, `recovery_job_id`(행동 열 없음, requeue만) | 없음 | 멱등 재생 확인만 |
| `paypal_webhook_retry_operator_audit` | `actor`, `created_at`, `event_id`, `outcome` | UPDATE·DELETE 거절(TRUNCATE 가드 없음) | 이벤트별 timeline(`PayPalWebhookRetryHistoryRepository`, 시도 이력과 UNION ALL) |
| `paypal_webhook_retry_attempt_history` | `claimed_at`, `finished_at`, `event_id`, `outcome`(행위자 없음) | 종료 1회 UPDATE만 허용, TRUNCATE 거절 | 같은 timeline |
| `seller_payout_profile_action` | `actor`, `created_at`, `seller_id`, `action` | INSERT 분리 트리거만(V5) | 멱등 확인만 |
| `settlement_payout_operator_action` | `actor`, `created_at`, `payout_id`, `action` | INSERT 분리 트리거만(V5·V6) | 멱등·재생 확인만 |
| `paypal_balance_report_disposition` | `actor`, `created_at`, `run_id`·`row_number`, `disposition` | UPDATE·DELETE·TRUNCATE 거절 | 행별 이력 엔드포인트 |
| `inventory_capacity_adjustment` | `created_at`, `seller_id`·`sku_id`, `delta`, `reason`(행위자 없음) | 없음(INV-006·ADR-0014는 append-only라고 씀) | 멱등 재생 확인만 |
| `inventory_transfer` | `created_at`, 출발·도착 위치, `reason`(행위자 없음) | UPDATE·DELETE 거절 | 멱등 재생 확인만 |
| `order_inventory_event` | `occurred_at`, `workflow_id`, `event_type`(예약 상태 전이만) | 없음 | 이벤트 worker, Kafka relay(ADR-0067) |
| 원장 3개 | `recorded_at`, `seller_id`(행위자 없음) | V7 추가 전용·균형 | 잔액·정산서 |

결과:

- 여러 감사 테이블을 가로지르는 조회를 요구하는 요구사항·ADR·운영 절차·엔드포인트는 찾지 못했다. 기존 UNION ALL은 한 웹훅 이벤트의 시도 이력과 운영자 재처리를 합치는 timeline 하나뿐이고 같은 대상(`event_id`) 범위다. 감사 관련 요구 ID(BR-009, INV-006, INV-008, FR-019)는 모두 한 테이블 안의 원자성·재시도 요구다.
- 가장 가까운 질문 "운영자 X가 한 조치 전부"는 행위자 열이 있는 6개 테이블의 UNION ALL로 쓸 수 있다(행위자 열은 모두 `varchar(128)`, 시각은 `timestamptz`). 이 질의를 V7 스키마에 실행해 오류 없이 도는 것을 확인했다. 행위자 열에 색인은 0개다.
- 행위자 열이 없는 테이블(재고 조정·이동, 원장, 예약 이벤트)은 통합 로그를 만들어도 "누가"를 채울 원천이 없다. 통합 로그의 값은 쓰기 경로마다 행위자를 새로 전달해야 생긴다.
- `order_inventory_event`는 예약 workflow 상태 전이만 기록하고 결제 확정·환불·지급 상태는 기록하지 않는다(트리거 함수가 `'RESERVATION_' || workflow_state`만 쓴다).
- 부수 발견: 문서가 추가 전용이라고 쓰는 `inventory_capacity_adjustment`와 운영자 조치 테이블 4개에 UPDATE·DELETE 가드가 없다. 이 PR의 범위가 아니므로 후속 이슈 [#720](https://github.com/Neo1228/ATLAS/issues/720)으로 남겼다.

판정: H11은 문서 기준으로 성립했다. 그 뒤 사용자 지시로 통합 로그를 도입했다(아래 H14~H18).

### 감사 로그 (H14~H18)

- H14·H15·H18: `DomainAuditEventIntegrationTest` 3건 통과. 실제 `AccountSuspensionRepository.apply`로 쓴 조치는 로그 1행(행위자·집계·이벤트 종류·원본 시각 일치), 롤백한 조치는 0행, 대상 9개 테이블 모두에 트리거, 로그 UPDATE·DELETE·TRUNCATE 거절, payload에 `reason`·멱등 키·사유 문자열 없음. capture 분개는 `CAPTURE_JOURNAL_POSTED`/`order`로 기록.
- H16 트리거 비용(`settlement_journal` 복사본, pgbench c = 1, 10초, n = 3):

| run | 트리거 없음 p50 / p95 ms | 트리거 있음 p50 / p95 ms | p50 차이 ms | tps 없음 / 있음 | load1 |
|---|---|---|---|---|---|
| 1 | 0.104 / 0.200 | 0.135 / 0.213 | +0.031 | 8,024 / 6,564 | 7.26 / 6.84 |
| 2 | 0.105 / 0.183 | 0.142 / 0.413 | +0.037 | 8,115 / 5,430 | 6.18 / 5.82 |
| 3 | 0.103 / 0.157 | 0.140 / 0.253 | +0.037 | 8,761 / 6,103 | 6.22 / 5.65 |

트리거 있는 쪽의 INSERT 180,951건에 로그 행 180,951건(정확히 한 건씩). 단일 INSERT만 하는 극단 부하라 처리량 차이(19~33%)는 상한으로 읽는다. 실제 쓰기 경로는 분개 하나에 항목 여러 행과 다른 갱신이 함께라 비율은 더 작다(재지 않음).

- H17 행위자별 조치 질의(테이블당 20만 행, 행위자 1,000명, 최근 100건, 6회 중 첫 회 제외 중앙값):

| 방식 | 행 | 실행 시간 중앙값 ms(최소~최대) |
|---|---|---|
| 현재 6개 테이블 UNION ALL, 행위자 색인 없음 | 테이블당 200,000 | 27.64 (20.21~61.17) |
| 같은 UNION ALL, 테이블마다 `(actor, created_at DESC)` 색인 | 테이블당 200,000 | 3.97 (3.63~4.25) |
| 통합 로그, V9 색인 | 1,200,000 | 0.37 (0.32~0.65) |

### 변이 확인

| 변이 | 결과 |
|---|---|
| M1 요청 경로가 outbox에 기록하지 않음 | outbox 시험 2건 모두 실패 |
| M2 전송 실패를 재예약하지 않음 | `smtpFailureIsRetried...` 실패 |
| M3 청구가 임대를 걸지 않음 | `committedSignupIsDelivered...` 실패 |
| M4 토큰을 요청 시점에 발급 | `committedSignupIsDelivered...` 실패 |
| 연결 반환 전 전송(직전 커밋의 `afterCommit` 방식) | `signupMailIsSentAfterTheDatabaseConnectionIsReleased` 실패 |
| MA1 대상 테이블 하나의 감사 트리거 누락 | `everyAuditedTable...` 실패 |
| MA2 `reason` 제거 안 함 | `operatorActionIsLogged...` 실패 |
| MA3 추가 전용 트리거 없음 | `everyAuditedTable...` 실패 |
| MA4 `occurred_at`을 원본 시각 대신 기록 시각으로 | `operatorActionIsLogged...` 실패 |

변이 없는 기준 실행은 모두 통과했다.

## 사전 등록 규칙 평가

| # | 판정 | 근거 |
|---|---|---|
| H1 | ✓ 성립 | A0 30/30, A1 30/30 유실(커밋은 run마다 10건: 풀 고갈로 나머지는 503) |
| H2 | ✓ 성립 | A3 60/60 유실 |
| H3 | ✓ 성립 | A2 0/60 유실, 재기동 뒤 마지막 전달 23.1~23.3초(임대 30초) |
| H4 | ✗ 반증 | 탐침 0/10(예측대로)이지만 30초 timeout은 run마다 10건(< 20). 묶인 것은 Tomcat 스레드 40개가 아니라 DB 연결 10개였고, 나머지 38건은 빠른 503 |
| H5 | ✗ 반증 | A1 p50 764~833 ms(38건이 빠른 503), 탐침 6/10(< 8). 전달 0은 예측대로. timeout만으로는 부족 |
| H6 | ✗ 반증(C2) / ✓(C6) | C2의 A2 p95 388~473 ms > 300 ms. 같은 칸 A3도 368~900 ms라 SMTP가 아니라 동시 48건의 비밀번호 해시(PBKDF2) CPU 비용으로 본다. C6(c = 4)는 54~66 ms로 성립. 기준을 c = 4 칸의 값으로 잡은 사전 등록의 설계 오류 |
| H7 | ✓ 성립 | A0·A1·A3 전달 0/60씩, A2 60/60, 복구 뒤 ≤ 7.2초, `retry_scheduled` 증가 |
| H8 | ✗ 반증(전달 p95) | 중복 0, 확인 150/150은 성립. 전달 p95 2.37~2.43초 > 2초: 폴링 1초 + 한 스레드가 한 통씩 보내므로 몰린 요청 50건의 뒤쪽이 기다린다 |
| H9 | ✓ 성립 | run마다 중복 1, 최신 토큰 3/3 성공, 앞 토큰 0/3 |
| H10 | ✓ 성립 | V9 `account_notification_outbox` 열에 토큰·해시·이메일 없음 |
| H11 | ✓ 성립(문서 기준) | 교차 조회 요구 문서 없음. 이후 사용자 지시로 도입(H14~H18) |
| H12 | ✓ 성립 | A1b 503 0, 탐침 10/10 |
| H13 | ✓ 성립 | C4 p95 중앙값 A1b 59.2 ms, A1 101.9 ms |
| H14 | ✓ 성립 | 시험 |
| H15 | ✓ 성립 | 시험 |
| H16 | ✓ 성립(p50 +0.031~0.037 ms) | 위 표 |
| H17 | ✓ 성립(27.64 → 0.37 ms, 약 75배. 원본에 색인만 더해도 3.97 ms) | 위 표 |
| H18 | ✓ 성립 | 시험 |

## 해석

- 현행의 가장 큰 위험은 유실보다 넓다. SMTP가 멈추면 메일 요청 10건이 DB 연결 풀 전체를 쥐어 무관한 API까지 503이 된다. 원인은 Spring `afterCommit`이 연결 반환 전에 실행되는 데 있고, timeout(A1)은 그 시간을 5초로 줄일 뿐이다. 연결 반환 뒤 전송(A1b)이 이 결합을 끊는다.
- 유실은 내구성 있는 기록 없이는 막을 수 없다. A0·A1·A1b·A3는 커밋 직후 종료와 SMTP 오류에서 모두 잃었고, A2만 0건이었다. A3는 요청 지연을 줄이지만 유실을 그대로 두고 스레드를 무제한으로 만든다.
- A2의 비용은 전달 지연(정상 p95 약 2.4초)과 응답 유실 시 중복 1통(앞 링크 무효)이다.

## 결정 (ADR-0102, D-181)

- 기본값: SMTP timeout 5초 + 연결 반환 뒤 전송(A1b). 둘 다 기존 동작을 바꾸지 않고 무한 대기·풀 고갈만 없앤다.
- 옵트인: 알림 outbox(A2), `ATLAS_ACCOUNT_NOTIFICATION_OUTBOX_ENABLED=true`. 기본 전환 조건은 ADR-0102 재검토 조건.
- 기각: A3(Spring 내부 이벤트).
- 감사: 사용자 지시로 통합 감사·도메인 이벤트 로그를 V9에 도입(항상 켜짐).

## 한계

- 단일 호스트, 가짜 SMTP, 시간 값은 맥락용. `eps-pg` 컨테이너가 떠 있던 창이다(위 결과 머리말).
- C1의 A0·A1은 풀 고갈 때문에 run당 커밋이 10건이라 표본이 30건이다.
- A1·A2 jar는 최종 커밋 이전(91ba8d12)이다. 그 뒤의 변경은 현행 경로의 전송 위치(A1b로 따로 측정)와 감사 로그(시험·별도 측정)이고 outbox 경로 코드는 같다.
- 감사 로그의 소비자(cursor, relay)와 조회 API는 만들지 않았다. 질의는 운영 문서의 SQL이다.
- 실제 메일 제공자, 다중 인스턴스 worker 경합, 운영 부하에서의 로그 증가 속도는 재지 않았다.

## 주장 표

| 주장 | 증거 | 확신도 |
|---|---|---|
| 현행은 커밋 직후 종료와 SMTP 오류에서 메일을 잃는다 | C1 A0 30/30, C3 A0 60/60 | 확인(local-only, 단일 호스트) |
| 현행에서 SMTP 정지는 DB 연결 풀을 고갈시켜 무관한 API를 503으로 만든다 | C2 A0 탐침 0/10, 503 38건 × 3 run, 회귀 시험 | 확인(local-only) |
| timeout만으로는 풀 고갈과 유실이 남는다 | C2 A1 탐침 6/10, 503 38건; C1·C3 A1 유실 | 확인(local-only) |
| 연결 반환 뒤 전송은 풀 고갈을 없앤다 | C2 A1b 503 0, 탐침 10/10; 시험의 변경 전 실패·후 통과 | 확인(local-only) |
| outbox는 커밋 직후 종료와 SMTP 오류에서 잃지 않는다 | C1 A2 0/60, C3 A2 60/60, 시험 2건과 변이 4건 | 확인(local-only) |
| outbox는 응답 유실 시 중복 1통을 만들고 최신 토큰만 유효하다 | C5 3 run | 확인(local-only) |
| outbox 전달 p95는 2초 이내다 | C4 2.37~2.43초 | 기각(H8) |
| Spring 내부 이벤트는 종료 시 잃고 전송마다 스레드를 만든다 | C1 A3 60/60, C2 스레드 48 | 확인(local-only) |
| 감사 로그는 원본 쓰기와 같은 transaction에 한 행씩 쌓이고 수정할 수 없다 | 시험 3건, 변이 4건 | 확인(local-only) |

## 삭제한 파일의 내용

구동 파일은 저장소에 올리지 않았다(일회성, `experiment-artifacts` 기준). 재현에 필요한 내용을 여기에 옮긴다.

### 구성

- jar 4개: A0 = `origin/main` d2c8395f의 bootJar, A3 = 같은 기준 + 아래 A3 패치, A1·A2 = 이 브랜치 커밋 91ba8d12의 bootJar(A2는 `--atlas.account.notification.outbox.enabled=true --atlas.account.notification.lease-duration=PT30S`), A1b = 커밋 4eaf06b8의 bootJar.
- 공통 실행 인자: `--management.endpoints.web.exposure.include=health,prometheus --atlas.auth.throttle.origin-limit=1000000 --atlas.auth.throttle.subject-limit=1000 --atlas.admission.expiry-poll-interval=PT24H --atlas.orders.expiry-poll-interval=PT24H --atlas.payment.paypal.webhook-retry.worker.enabled=false`, 환경 변수 `ATLAS_PAYMENT_PROVIDER=disabled SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=<가짜 SMTP>`, DB URL에 `statement_timeout=30000, idle_in_transaction_session_timeout=60000, lock_timeout=5000`, `-Xms768m -Xmx768m`.
- PostgreSQL: `docker run -d --label atlasexp=notify -e POSTGRES_USER=atlas -e POSTGRES_PASSWORD=... -e POSTGRES_DB=atlas postgres:18.6`, 안마다 `DROP DATABASE atlas WITH (FORCE); CREATE DATABASE atlas`로 새로 만들고 앱 기동 때 Flyway가 스키마를 만든다.
- 구동기(Python): 안마다 앱을 한 번 띄우고 C4 → C6 → C3 → C2 → C1(→ C5) 순서로 run 3회씩. 가입 요청은 `POST /api/auth/signup`(`{"email","password"}`), 이메일은 `c<칸>-<안>-r<run>-<i>@example.com`. 지연은 요청 시작부터 응답까지의 벽시계 시간, 전달 시간은 요청 시작부터 가짜 SMTP가 메일 본문을 받은 시각까지. 유실 = 커밋된 계정 수(`user_account`에서 접두어로 셈) − 메일을 받은 서로 다른 수신자 수. 중복 = 수신자별 (메일 수 − 1)의 합. 토큰 확인은 수신자별 마지막 토큰으로 `POST /api/auth/verify-email`(204 성공). C2의 SMTP 대기 스레드는 10초 시점 `jcmd <pid> Thread.print`에서 스택에 `SMTPTransport`가 있는 스레드를 이름 접두어별로 셌다. 모든 run 옆에 load1, 잠금 소유자, 다른 라벨 컨테이너를 기록했다.
- 잠금: 안마다 `measure-lock.sh acquire notify 40`을 (다시) 잡고 돌렸다.

### 가짜 SMTP 서버(`fake_smtp.py`, 요지)

```python
# 모드는 응답 직전마다 파일에서 읽는다: ok | slow(응답마다 1초) | hang(인사말을 보내지 않음) | err(MAIL FROM에 451)
# | acklost(DATA 끝까지 받아 기록한 뒤 250을 보내지 않음). hang·acklost 연결은 모드가 바뀌면 닫는다.
async def handle(reader, writer):
    if mode() == 'hang':
        await wait_until_not('hang'); return
    await reply(writer, '220 fake ESMTP')
    while True:
        cmd = (await reader.readline()).decode().strip().upper()
        if cmd.startswith('EHLO'): await reply(writer, '250-fake\r\n250 OK')
        elif cmd.startswith('MAIL FROM'): await reply(writer, '451 4.3.0 temporary failure' if mode() == 'err' else '250 OK')
        elif cmd.startswith('RCPT TO'): rcpt = ...; await reply(writer, '250 OK')
        elif cmd == 'DATA':
            await reply(writer, '354 end with .'); body = await reader.readuntil(b'\r\n.\r\n')
            log_json(t=time.time(), rcpt=rcpt, token=re.search(rb'one-time token: ([A-Za-z0-9_-]+)', body))
            if mode() == 'acklost': await wait_until_not('acklost'); return
            await reply(writer, '250 queued')
        elif cmd.startswith('QUIT'): await reply(writer, '221 bye'); return
        else: await reply(writer, '250 OK')
```

### A3 패치(실험 전용, `origin/main` 위)

`AccountServiceImpl.sendAfterCommit`을 `events.publishEvent(new AccountEmailRequested(delivery))`로 바꾸고(`ApplicationEventPublisher` 생성자 주입), 아래 리스너를 더했다. 실행기는 따로 정의하지 않았다(기본 동작 측정).

```java
@Configuration
@EnableAsync
public class AccountEmailEventListener {
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailRequested(AccountEmailRequested event) {
        try { event.delivery().run(); }
        catch (RuntimeException exception) { LOGGER.warn("Account email delivery failed; user can request a replacement token"); }
    }
}
```

### 감사 질의 측정(`audit_bench.py`)

6개 운영자 조치 테이블을 `scratch` 스키마에 `CREATE TABLE scratch.<t> (LIKE public.<t> INCLUDING DEFAULTS)`로 복사하고(NOT NULL만 유지, CHECK·FK·트리거·색인 없음) 테이블마다 행을 넣었다(행위자 `'actor-' || (i % 1000)`, `created_at = now() - i초`, 그 밖의 열은 타입별 상수). 위 UNION ALL(`actor = 'actor-417'`, `ORDER BY created_at DESC LIMIT 100`)을 `EXPLAIN (ANALYZE, BUFFERS)`로 6회 돌려 첫 회를 빼고 실행 시간 중앙값을 냈다. 이어서 테이블마다 `(actor, created_at DESC)` 색인을 만들고 같은 측정을 반복했다.

### 트리거 비용 측정(`audit_cost.sh`)

같은 DB에 `sj_plain.settlement_journal`·`sj_audit.settlement_journal`(`LIKE public.settlement_journal INCLUDING DEFAULTS`)과 각 스키마의 `domain_audit_event`(`INCLUDING ALL`)를 만들고 `sj_audit`에만 `record_domain_audit_event` 트리거를 걸었다. pgbench 스크립트는 `INSERT INTO settlement_journal(source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at) VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 10000, now());` 한 줄, `PGOPTIONS='-c search_path=<스키마>,public' pgbench -n -c 1 -T 10 --log`, 로그 3열(마이크로초)의 중앙값·95번째 값.

### 변이 확인 구동(`mut.sh`, `mut2.sh`)

변이마다 운영 코드를 `perl`로 고치고 `./gradlew :atlas-application:test --tests <클래스>`를 돌린 뒤 `git checkout`으로 되돌렸다. 변이 내용은 본문 표에 있다.
