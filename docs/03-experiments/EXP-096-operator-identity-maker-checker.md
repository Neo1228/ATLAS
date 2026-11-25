# EXP-096: 운영자 사람별 신원과 승인자 ≠ 검증자 규칙은 어떤 강제 수단에서 경쟁 조건에도 깨지지 않는가

- 질문: 프로필 검증과 지급 승인을 같은 사람이 하지 못하게 하려면 신원 모델을 어디까지 바꿔야 하고, 그 규칙을 service 검사·조건부 SQL·DB trigger 중 무엇으로 강제해야 동시 요청과 우회 경로에서 깨지지 않는가?
- 상태: 실험 완료(로컬). **가설·위협 모델·통제·표본 크기는 실행 전에 적었고** 그 커밋(`docs(experiments): 운영자 신원·승인자 분리 측정의 위협 모델과 가설을 실행 전에 기록 (EXP-096, #651)`)이 git 이력에 남아 있다. 결과·판정·한계는 그 뒤 커밋에서 이어 적었고 가설 표는 고치지 않았다. 원본 출력(TSV, 로그, 변이 로그)은 저장소에 넣지 않았고 이 문서에는 집계만 있다. 재현은 아래 "재현" 절.
- 날짜: 2026-11-25
- 관련: [ADR-0078](../02-decisions/adr/ADR-0078-payout-operator-controls-production-gate.md), [ADR-0085](../02-decisions/adr/ADR-0085-v1-scope-channel-operator-and-payout-decisions.md), [ADR-0027](../02-decisions/adr/ADR-0027-account-jwt-authentication.md), [ADR-0057](../02-decisions/adr/ADR-0057-financial-operation-outbox-and-payout-reconciliation.md), [Issue #651](https://github.com/Neo1228/ATLAS/issues/651)
- 기준 커밋: `origin/main` 82345acd

## 문제 (한 문장)

Seller 지급 경로의 운영자 인증이 설정값 하나(username, password)라서 감사 행의 actor가 모든 운영자에게 같은 문자열이고, 프로필을 검증한 계정과 그 프로필로 만든 지급을 승인하는 계정이 같아도 막을 수 없다. 영향 받는 쪽은 Live 지급을 여는 시점의 운영(내부자 단일 행위자 사기, 사후 책임 추적)이다. 지금은 Sandbox이고 feature flag가 꺼져 있어 움직이는 돈이 없다(가정, ADR-0078). 발생 빈도와 금액은 측정할 수 없어 쓰지 않는다.

## 코드에서 읽은 사실 (기준 커밋, 실행 아님)

- 운영자 chain은 `SettlementPayoutOperatorSecurityConfiguration`의 HTTP Basic 단일 사용자이고 `InMemoryUserDetailsManager`에 1명만 만든다. 컨트롤러는 `Principal.getName()`을 actor로 service에 넘긴다.
- 프로필 검증은 `seller_payout_profile.verified_by`(현재 행)와 `seller_payout_profile_action`의 `PROFILE_VERIFIED` 행(actor, `profile_version`)에 남긴다. `verify`는 `PENDING_REVIEW` → `ACTIVE`로 바꾸며 `profile_version`을 1 올리고, 이 새 버전으로 `PROFILE_VERIFIED` 행을 쓴다. Seller가 수취 계정·국가를 바꾸면 `submit`이 버전을 올리고 `verified_by`를 비운다.
- 지급 승인은 `PayoutDispatchApprovalCommitService.approve`가 한 트랜잭션에서 지급 상태 전이, outbox(`settlement_payout_dispatch_outbox.profile_version`에 검증된 버전을 묶음), `settlement_payout_operator_action`(`PAYOUT_APPROVED`) 행을 쓴다. 지급 쪽 승인은 검증자를 읽지 않는다.
- account 모듈에는 이미 사람별 `user_account`(PBKDF2 `DelegatingPasswordEncoder`), 로그인 한도(`AuthAttemptLimiter`), 선택형 TOTP MFA(`account_mfa_secret`, D-084), refresh family 폐기·계정 정지가 있다. ADR-0078의 "앱에 MFA가 없다"는 운영자 Basic chain에 대한 말이고 account 로그인에는 해당하지 않는다(코드 읽기, 아직 실행 확인 아님).
- 로그인은 buyer 연결이 있는 계정만 통과한다(`AccountServiceImpl.login`). 즉 운영자 계정도 일반 계정 가입 경로로 만든다.
- 다른 운영자 chain(계정 정지, 결제 복구, PayPal 보고서·웹훅)도 각각 단일 설정 자격 증명이다.

## 위협 모델 (돈이 외부 계정으로 나가는 경로)

| # | 위협 | 공격 | 이번 변경이 다루는가 | 가설 |
|---|---|---|---|---|
| T1 | 내부자 단일 행위자 사기 | 한 사람이 자기 수취 계정 프로필을 검증하고 같은 계정으로 지급을 승인 | 다룬다 | H1, H2, H3 |
| T2 | 자격 증명 공유 | 두 사람이 한 자격 증명을 쓰면 감사로 구분 불가 | 신원 모델로 다룬다 | H6 |
| T3 | 승인 재전송 | 이미 승인된 지급에 승인 요청을 다시 보내 중복 송금·감사 오염 | 기존 멱등 경로 + 새 규칙이 검증자의 재전송도 거절하는지 확인 | H5 |
| T4 | 검증과 승인 사이의 상태 변화(TOCTOU) | 검증 뒤 프로필이 바뀐 사이에 승인이 들어옴, 또는 규칙 검사가 승인과 다른 버전의 검증자를 봄 | 다룬다 | H2 |
| T5 | 요청 본문으로 권한 상승 | 본문에 `actor` 같은 값을 넣어 다른 사람인 척함 | 다룬다 | H6 |
| T6 | 훔친 일반 사용자 JWT | Buyer·Seller 토큰으로 운영자 경로 호출 | 다룬다 | H8 |
| T7 | 해제·정지된 운영자의 남은 토큰 | access token 수명(10분) 안에 계속 사용 | 요청마다 DB로 권한을 확인해 다룬다 | H8 |
| T8 | 운영자 둘의 담합 | 검증자와 승인자가 공모 | **다루지 않는다**(4-eyes는 공모를 못 막는다) | 해당 없음 |
| T9 | DB 권한을 가진 내부자 | 감사 행·trigger를 직접 수정 | **다루지 않는다** | 해당 없음 |

## 가설

각 가설은 반증 조건, 지표, 기대값, 결정을 함께 적는다. 판정에는 개수와 불변식만 쓴다. 이 공유 Mac의 시간 값은 맥락 정보다.

| # | 가설 | 반증 조건 | 지표 | 기대값 | 결정 |
|---|---|---|---|---|---|
| H1 | 사람별 신원이 있을 때 `PROFILE_VERIFIED` actor와 같은 actor의 `PAYOUT_APPROVED` 행은 DB가 거절한다. Java 검사를 거치지 않고 행을 직접 넣어도 거절한다 | 한 건이라도 저장됨 | 저장된 위반 행 수 / 시도 수 | 0 / n | 강제 위치를 DB에 둘지 |
| H2 | 규칙이 **현재** profile 행의 `verified_by`를 읽으면 승인 직전에 프로필이 다음 버전으로 넘어간 경우 위반을 놓친다. outbox에 묶인 **그 버전**의 `PROFILE_VERIFIED` 행을 읽는 규칙은 놓치지 않는다 | 현재 행 규칙이 결정적 끼어들기에서 위반을 놓치지 않음(하네스 의심), 또는 버전 묶음 규칙이 놓침 | 끼어들기 n회 중 저장된 위반 행 수, 변형별 | 현재 행 규칙 n/n, 버전 묶음 0/n | 규칙이 읽을 값 |
| H3 | 강제 수단 4종을 비교하면 경쟁 조건에서는 service 검사(S)·조건부 SQL(C)·trigger(T)가 모두 0건이다(검증자 행은 버전에 묶여 불변). 차이는 우회 경로(규칙을 모르는 INSERT)에서만 난다: S, C는 통과시키고 T만 거절한다 | S 또는 C가 우회를 거절함, 또는 T가 우회를 통과시킴 | 변형 × 시나리오별 저장된 위반 행 수 | 표 참조(결과 절) | 어느 수단을 고를지 |
| H4 | 서로 다른 두 사람(검증자 아님)이 같은 지급을 동시에 승인하면 정확히 한 건만 `PAYOUT_APPROVED`로 저장되고 outbox도 1행이다. 진 쪽은 202(재전송 기록)나 409이고 두 번째 승인 행은 없다 | 한 라운드라도 `PAYOUT_APPROVED` ≠ 1 또는 outbox ≠ 1 | 라운드당 승인 행 수, outbox 행 수 | 모든 라운드에서 1, 1 | 직렬화를 추가 잠금 없이 믿어도 되는지 |
| H5 | 검증자가 이미 승인된 지급에 승인을 다시 보내도(다른 Idempotency-Key) 거절된다. 승인자가 같은 key와 같은 사실로 다시 보내면 같은 응답이고 행이 늘지 않는다 | 검증자의 재전송이 2xx, 또는 정당한 재시도가 행을 늘림 | 상태 코드, 행 수 | 403, 행 수 불변 | 재전송 경로에도 같은 규칙을 둘지 |
| H6 | 감사 actor는 항상 인증된 principal이다. 요청 본문에 actor·operator·verifiedBy 같은 필드를 넣어도 저장되지 않는다. 대조: 기존 단일 자격 증명에서는 두 사람이 같은 actor 문자열을 남긴다 | 저장된 actor ≠ principal | 불일치 행 수 / 요청 수 | 0, 대조군은 구분 불가 | 신원 모델이 문제 (2)를 푸는지 |
| H7 | rollback flag(`identity=shared-credential`)는 기존 Basic 단일 자격 증명 동작을 되살린다. 이때 DB trigger는 사람 신원이 아닌 actor 문자열에는 규칙을 적용하지 않아 기존 흐름(같은 문자열이 검증·승인)을 막지 않는다. migration은 additive라 기존 행을 건드리지 않는다 | rollback 모드에서 기존 테스트가 실패함, 또는 migration 뒤 기존 행이 바뀜 | 기존 payout 통합 테스트 통과 수, V5 전후 행 수·해시 | 전부 통과, 동일 | 되돌릴 수 있다고 쓸 수 있는지 |
| H8 | 운영자 경로 인증 경계: 토큰 없음 401, 일반 사용자(운영자 아님) 토큰 403, 운영자 권한이 해제된 계정 403, MFA를 등록하지 않은 운영자 403, 정지된 계정 401. 어느 경우에도 service가 호출되지 않는다 | 2xx 또는 service 호출 | 경우별 상태 코드, service 호출 수 | 표 참조 | 신원 모델 경계 |
| H9 | 수동 지급 경로의 `MANUAL_PAYOUT_SENT`는 `MANUAL_PAYOUT_APPROVED` 행위자와, `MANUAL_PAYOUT_CONFIRMED`는 `MANUAL_PAYOUT_SENT` 행위자와 달라야 한다(같으면 DB가 거절, 다르면 성공) | 같은 사람이 통과하거나 다른 사람이 거절됨 | 시나리오별 결과 | 같은 사람 0건 통과 | 쌍별 4-eyes 결정 |
| H10 | trigger의 조회는 `seller_payout_profile_action`의 seller_id 접두 인덱스를 쓰고 INSERT 한 건 비용은 trigger 없는 대조와 같은 자릿수다(시간은 맥락) | EXPLAIN이 전체 스캔, 또는 trigger가 자릿수 이상 느림 | EXPLAIN 노드, 삽입 p50·min·max | Index Scan, 같은 자릿수 | trigger를 써도 되는지 |

## 통제

- **변경 없음 대조(a):** 기준 커밋의 코드로 같은 시나리오(같은 자격 증명으로 검증 후 승인)를 실행해 현재 동작(모두 성공, actor 문자열 동일)을 기록한다.
- **음성 대조(b):** (1) 규칙 없는 변형(D0)에서 같은 사람 흐름이 성공해야 탐지기가 작동한다. (2) 현재 행 규칙 변형(N)이 결정적 끼어들기에서 위반을 놓쳐야 하네스의 끼어들기가 실제로 일어난다. 둘 중 하나라도 기대와 다르면 그 결과를 쓰기 전에 하네스를 먼저 의심하고 고친다. (3) 통합 테스트에서는 규칙을 끈 상태(rollback flag)에서 같은 사람 흐름이 성공해야 한다.
- **전후 비교(c):** 같은 Postgres 이미지(`postgres:18.6`), 같은 시드, 같은 시나리오 스크립트로 기준 커밋과 변경 후를 실행한다. 워밍업 라운드는 집계에서 제외한다.

## 표본 크기

- 결정적 끼어들기와 우회 시나리오는 변형당 n=200. 0/200이면 위반율의 95% 상한은 3/200 = 1.5%(rule of three)다. 이는 이 하네스가 만드는 끼어들기에 한정한 상한이다.
- 두 사람 동시 승인(H4)은 앱 수준 n=100 라운드(0/100이면 상한 3%)와 변형별 SQL 수준 n=200 라운드를 쓴다. CI의 `check`에는 3라운드만 넣고 반복은 환경 변수로 올린다.
- n이 충분한 이유: 이 규칙의 안전성은 "검증자 행은 한 번 쓰이면 바뀌지 않고, outbox가 그 버전을 묶는다"는 불변식에서 나온다. 반복은 그 불변식을 반증하려는 시도이지 증명이 아니다. 반증이 없었다는 것을 "안전하다"로 쓰지 않는다.
- 시간 값은 최소·중앙·최대로만 보고하고 판정에 쓰지 않는다. 변형 실행 순서는 라운드마다 무작위로 섞는다.

## 교란 요인 기록 (결과 절에서 하나씩 닫는다)

| 요인 | 어떻게 배제하는가 |
|---|---|
| 첫 실행 효과(캐시, 계획 캐시) | 워밍업 라운드를 버린다 |
| 다른 에이전트의 Docker·CPU 사용 | 실행 전후 `uptime`, `docker stats --no-stream` 기록. 판정에 시간을 쓰지 않는다 |
| 하네스 버그(끼어들기가 안 일어남) | 음성 대조 (b)로 증명한다 |
| 라운드 간 데이터 오염 | 라운드마다 새 UUID 시드. 집계는 라운드 id 단위 |
| 스레드 시작 편향 | `CyclicBarrier`/advisory lock으로 동시에 풀고 라운드마다 두 쪽의 시작 순서를 무작위로 바꾼다 |
| 멱등 key 충돌 | key는 라운드·행위자 단위로 유일하게 만든다 |
| 규칙이 아니라 다른 제약이 거절함 | 거절 오류의 constraint 이름과 상태 코드를 함께 단정한다 |

## 계층 (한 번에 한 층, 증거가 가리키는 곳까지)

앱 응답(상태 코드, 오류 code)과 감사 행 개수 → PostgreSQL(행, `pg_locks`/`pg_stat_activity`, EXPLAIN) 순으로 본다. 네트워크·런타임·컨테이너 층은 앱·DB 층에서 설명되지 않는 결과가 나올 때만 내려간다. 멈춘 층과 이유는 결과 절에 적는다.

## 대안 (실행 전 가설적 비교, 근거는 결과 절에서 채움)

| 안 | 비용 | 위험 | 비고 |
|---|---|---|---|
| A0 하지 않음(ADR-0078 (a) 유지) | 0 | gate 7이 계속 미결, 같은 사람 검증·승인을 막을 수 없음 | 가장 강한 반론: V1은 Sandbox라 돈이 안 움직인다. 이 이슈는 Live 전 조건을 미리 닫는 것이다 |
| A1 IdP·gateway가 신원을 제공(ADR-0078 (c-2)) | 인프라 도입·운영. 저장소에 없음 | 일정 불명 | 반론 중 가장 강함: 신원·MFA·회전·잠금을 한 번에 얻는다. 단 그 경우에도 principal 이름이 사람 하나를 가리키는 규칙과 승인자 ≠ 검증자 강제는 필요하다. 이번 설계는 A1과 배타적이지 않다 |
| A2 설정에 Basic 사용자 N명 | 코드 작음 | 비밀번호가 환경 변수에, 회전·잠금·MFA 없음, 사용자 추가에 재배포 | |
| A3 Basic 인증을 `user_account` 해시로 검증 | 작음 | 온라인 추측에 한도가 없고 사람이 정한 비밀번호가 기존 32자 무작위 비밀번호보다 약함 | |
| A4 account JWT + 운영자 권한 표(선택 후보) | 표 1개, 권한 변환기, 운영 절차 | 계정이 buyer 연결을 가져야 로그인됨, 첫 운영자 부여는 DB 작업 | 기존 해시·로그인 한도·TOTP·세션 폐기 재사용 |

강제 수단 비교 변형: D0 규칙 없음(음성 대조) / S service 검사(검증자 조회 후 별도 문장으로 INSERT) / C 조건부 SQL(`INSERT ... SELECT ... WHERE NOT EXISTS`) / T trigger(outbox 버전에 묶인 `PROFILE_VERIFIED` 행 조회) / N 현재 profile 행을 읽는 trigger(음성 대조).

## 사전 부검 (이 변경이 운영에서 어떻게 실패할 수 있는가)

| # | 실패 | 감지 | 대응 |
|---|---|---|---|
| F1 | 운영자가 아무도 인증하지 못한다(부여 누락, MFA 미등록, 계정 정지) | 시작 시 활성 운영자 수 0이면 WARN, 운영자 경로 401·403 비율 | 운영 절차의 부여 SQL, rollback flag |
| F2 | 정당한 승인이 같은 사람 규칙에 걸려 막힌다(소규모 팀) | 403 `SEPARATION_OF_DUTIES` 로그·응답 | 두 번째 운영자 부여, 긴급 시 rollback flag(감사 actor 구분이 사라짐을 알고 쓴다) |
| F3 | 권한이 해제된 사람이 trigger 적용 대상에서 빠져 규칙이 꺼진다 | 해제를 삭제가 아니라 `revoked_at`로만 하고, trigger는 행 존재만 본다 | 테스트로 고정 |
| F4 | 멱등 재시도(`ON CONFLICT DO NOTHING`)에서 BEFORE trigger가 먼저 실행돼 정당한 재시도가 403이 된다 | H5 테스트 | 재시도는 INSERT 전에 `matches`로 반환되는지 확인 |
| F5 | 다른 에이전트의 V4·V6 migration과 순서가 엇갈린다 | Flyway validate, CI | 병합 순서 조정 |
| F6 | DB 오류 문구가 바뀌어 403 매핑이 500이 된다 | 상태 코드 단정 테스트 | constraint 이름·SQLSTATE 둘 다 단정 |
| F7 | JWT 수명(10분)이 운영 편의를 깎는다 | 사용자 피드백 | 안전 문제가 아님. 기록만 |
| F8 | 운영자 계정이 Buyer 신원도 가져 구매자 경로와 섞인다 | H8 | 전용 메일함 사용을 절차에 적는다 |

## 되돌리기 훈련 계획

(1) flag 훈련: `identity=shared-credential`로 기동해 Basic으로 같은 사람 흐름이 성공하는지 테스트로 보인다. (2) migration 되돌림: V5는 새 표·함수·trigger만 만든다. 되돌림 SQL(`DROP TRIGGER`, `DROP FUNCTION`, `DROP TABLE`)을 하네스에서 실제로 실행하고, 기존 흐름이 그대로 되는지 본다. (3) 기준 커밋의 코드가 V5 스키마에서 동작하는지(additive)를 기존 payout 통합 테스트로 본다.

## 계획한 실험

- E1 SQL 수준 비교(H1~H3, H10): SQL 하네스(이 PR에는 넣지 않았다. 내용은 "삭제한 파일의 내용" 절에 전부 있다) 한 줄 실행. 라벨 `atlasexp=h7` 컨테이너 1개.
- E2 앱 수준 통합 테스트(H1, H4~H9): PostgreSQL Testcontainers. 같은 사람 거절, 다른 사람 성공, 동시 두 사람, 재전송, 본문 actor 무시, 음성 대조, 변이 검증.
- E3 기준 커밋 대조(통제 a): 기준 커밋 worktree에서 같은 자격 증명으로 검증·승인이 모두 성공하고 actor가 같음을 기록.

## 환경

- 호스트: macOS arm64, Colima VM(8 CPU, 약 13 GB)을 **다른 에이전트 여섯 명과 공유**했다. 실행 중 `uptime` load average는 20~152였다. 그래서 시간 값은 맥락이고 판정은 개수와 불변식으로만 했다.
- PostgreSQL 18.6(`postgres:18.6`, 이미지 기본 설정), JDK 25, Spring Boot 통합 테스트는 Testcontainers. 기준 커밋 `82345acd`.
- E1은 컨테이너 1개(`atlasexp=h7`)를 만들고 끝나면 지웠다(실행 뒤 같은 label 컨테이너 0개).

## 결과

### E3 변경 없음 대조: 기준 커밋(통제 a), 단일 공유 자격 증명, 100라운드

같은 자격 증명으로 프로필을 검증하고 지급을 승인하는 흐름을 기준 커밋 코드에서 실행했다(일회용 탐침, 저장소에 넣지 않음). 라운드마다 두 요청이 동시에 승인한다.

| 지표 | 결과 (n=100 라운드) |
|---|---|
| 같은 자격 증명의 검증 + 승인이 모두 성공 | 100 / 100 |
| 감사 행의 검증자 actor와 승인자 actor가 같은 문자열 | 100 / 100 |
| 라운드당 `PAYOUT_APPROVED` 행 수가 2건 | 100 / 100 라운드(합계 200행, outbox는 100행) |

관찰 1은 문제 (1), (2)의 재현이다. **관찰 2는 사전 등록에 없던 발견이다**: 동시 승인에서 진 쪽도 `PAYOUT_APPROVED`를 한 번 더 쓴다. 돈은 한 번만 나간다(outbox PK가 1행). 그러나 감사 이력이 "승인 2건"이라 거짓이고, 이 PR의 H4 기대("정확히 한 건")가 기준 코드에서는 100/100 라운드에서 거짓이었다. 이 결함 때문에 아래 H4는 "수정 전 반증, 수정 후 확인"이다.

### E1 SQL 수준: 강제 수단 비교 (H1, H2, H3), 시나리오·변형당 n=200, 실제 V1 표와 V5 함수

표의 값은 "저장됨(위반 통과) / 규칙이 거절 / 다른 오류"이고 n=200씩이다. 변형: D0 규칙 없음, S service 검사(검증자 조회 후 별도 INSERT), C 조건부 SQL, T V5 trigger, N 현재 profile 행을 읽는 trigger. S, C, T는 모두 **outbox가 묶은 버전**의 검증자를 읽는다.

| 시나리오 | 기대 | D0 | S | C | T | N |
|---|---|---|---|---|---|---|
| x0 정당한 승인(다른 사람) | 전 변형 통과 | 200 / 0 / 0 | 200 / 0 / 0 | 200 / 0 / 0 | 200 / 0 / 0 | 200 / 0 / 0 |
| x1 같은 사람 | D0만 통과 | **200** / 0 / 0 | 0 / 200 / 0 | 0 / 200 / 0 | 0 / 200 / 0 | 0 / 200 / 0 |
| x2 승인 도중 프로필이 다음 버전으로 넘어감 | D0, N만 통과(위반) | **200** / 0 / 0 | 0 / 200 / 0 | 0 / 200 / 0 | 0 / 200 / 0 | **200** / 0 / 0 |
| x3 규칙을 모르는 직접 INSERT, 검증자 = 승인자 | D0, S, C 통과(위반) | **200** / 0 / 0 | **200** / 0 / 0 | **200** / 0 / 0 | 0 / 200 / 0 | 0 / 200 / 0 |

- **음성 대조가 작동했다:** D0는 x1, x2, x3에서 600/600 위반을 통과시켰고, N은 x2에서 200/200 위반을 통과시켰다. 탐지기와 끼어들기 하네스가 위반을 만들어 낸다. x0의 정당한 승인이 전 변형에서 200/200 통과해 규칙이 과하게 거절하지도 않는다. 다른 오류는 모든 칸에서 0이라 거절이 다른 제약 때문이 아니었다.
- **H1 확인:** T는 x1, x3에서 위반 행을 0/200씩 저장했다(각 시나리오 95% 상한 3/200 = 1.5%). x3은 Java 검사를 거치지 않는 INSERT다. 통합 테스트에서도 서비스를 거치지 않은 직접 INSERT가 거절됐다.
- **H2 확인:** 현재 행 규칙(N)은 x2에서 200/200 놓쳤고 묶인 버전 규칙(T, S, C)은 0/200이다. 사전 등록에서 적은 반증 조건("현재 행 규칙이 놓치지 않음")은 일어나지 않았다.
- **H3 확인(정확한 범위):** 경쟁 조건(x2)에서는 S, C, T 모두 0/200이었다. 차이는 규칙을 모르는 경로(x3)에서만 났다: S, C는 200/200 통과, T는 0/200. 즉 service 검사와 조건부 SQL은 "그 코드를 지나는 요청"만 막는다. 여기서 S와 C는 dblink로 만든 SQL 수준 모형이지 Java 서비스가 아니다. 결론은 메커니즘 종류에 대한 것이다.
- 경쟁 조건에서 trigger와 조건부 SQL이 같다는 것은 두 사람의 두 INSERT가 서로를 보지 않아도 되는 설계(검증자 행이 이미 커밋되어 불변)라서 나온 결과다. 이 하네스는 "한 사람이 검증과 승인을 동시에 한다"는 경쟁을 만들지 않았다. 검증자는 검증이 끝난 뒤에만 승인할 수 있고, 검증과 승인을 같은 시각에 같은 사람이 보내는 경쟁은 두 요청이 서로 다른 트랜잭션에서 verify 커밋 전에 approve가 실행되는 경우뿐이다. 그때 approve는 `requireActiveRecipient`에서 `ACTIVE`가 아니라 409로 끝난다(코드 읽기, 이 경쟁은 따로 실행하지 않았다).

### E2 앱 수준 PostgreSQL 통합 테스트 (H1, H4~H9)

`PayoutOperatorMakerCheckerIntegrationTest`(8건). 변경 전 코드에는 이 흐름을 만들 수 없어(운영자 account가 없음) 기준 커밋 대조는 위 E3로 했다.

| 가설 | 관찰 | 판정 |
|---|---|---|
| H1 같은 사람 승인 거절 | 검증자의 승인 → 403 `SEPARATION_OF_DUTIES`, `PAYOUT_APPROVED` 행 0, outbox 0, 지급 상태 `REQUESTED` 유지(롤백). 직접 INSERT도 거절 | 확인(n=1 결정적) |
| H4 두 사람 동시 승인 | **100 라운드**: `PAYOUT_APPROVED` 행 정확히 1(100/100), outbox 정확히 1(100/100), 검증자 동시 승인 거절 100/100, 승인자 두 명의 응답은 모두 202(200/200, 진 쪽은 `PAYOUT_APPROVAL_REPLAYED` 기록). 위반 0/100 → 95% 상한 3% | 수정 전 **반증**(기준 100/100 라운드에서 2행), 수정 후 확인 |
| H5 재전송 | 같은 key 재시도는 202이고 행 수 불변. 이미 승인된 지급에 검증자가 다른 key로 보내면 403 | 확인(n=1) |
| H6 actor는 principal | 저장된 승인 actor = 승인자 account id. 요청 본문에 `actor`로 다른 사람을 적어도 principal 기준으로 판정(검증자가 본문에 승인자 id를 넣어도 403). 대조: 기준 코드에서는 100/100이 같은 문자열 | 확인 |
| H7 되돌리기 | rollback flag `identity=shared-credential`의 Basic chain이 기존 두 보안 테스트를 통과. 사람 신원이 아닌 actor 문자열은 trigger가 적용되지 않고 같은 문자열의 검증과 승인이 성공. SQL 되돌림 훈련은 아래 | 확인(제한은 한계 절) |
| H8 인증 경계 | 토큰 없음 401, 일반 사용자 토큰 403, MFA 미등록 운영자 403, 권한 해제 후 같은 토큰 403, 비활성 계정 401. 정상 운영자 200 | 확인(서비스 호출 횟수는 단정하지 않음) |
| H9 수동 지급 쌍 | 승인자가 송금 기록 시도 → 403, 다른 사람은 200. 송금 기록자가 확인 시도 → 403, 승인자는 200 | 확인 |
| (추가) 수취 account = 검증자 | 운영자가 자기 account가 수취 account인 프로필을 검증 → 403, 상태 `PENDING_REVIEW` 유지, 다른 운영자는 200 | 사전 등록에 없던 틈을 코드 읽기로 찾아 닫음 |
| (추가) 묶인 버전 | 지급 outbox가 v2에 묶이고 프로필이 v4로 넘어간 뒤: v2 검증자 거절, v4 검증자 통과 | 확인 |

음성 대조(통제 b-3): trigger를 끈 같은 테스트에서 같은 사람이 검증과 승인을 모두 성공했다(202). trigger를 다시 켜는 것까지 `finally`로 복구한다.

### H7 migration 되돌리기 훈련 (SQL, 실제 실행)

기준 스키마(V1~V4)에 지급 50건·감사 50행·사용자 등을 넣고 모든 관련 표의 행 해시를 잡았다. V5 적용 → 해시 동일, 기존 actor 문자열의 새 감사 INSERT 통과 → `undo.sql` 실행 → trigger·함수·표·인덱스 모두 0개, 해시가 처음과 동일 → V5 재적용 성공. 해시가 같다는 것은 V5가 기존 행을 바꾸지 않았다는 근거다. 이것은 additive임을 보인 것이고, 되돌린 뒤 기준 Java 코드를 실행하지는 않았다.

### H10 trigger 비용과 실행 계획

- 계획: 다른 Seller의 감사 행 200,000개를 채운 뒤 trigger 조회의 `EXPLAIN (ANALYZE, BUFFERS)`는 `settlement_payout`·`settlement_payout_dispatch_outbox` PK Index Scan과 `seller_payout_profile_action`의 `ix_seller_payout_profile_action_created`(seller_id 접두) Bitmap Index Scan을 썼고 전체 스캔이 없었다. 읽은 버퍼 10개.
- INSERT 한 건 시간(마이크로초, 3,000건씩, 순서 무작위, 공유 호스트라 맥락): trigger 없음 최소 16, p50 21, p95 89, 최대 1,196 / trigger 있음 최소 31, p50 41, p95 172, 최대 2,060. 같은 자릿수(약 2배, 20 µs 안팎의 절대 차이). 판정은 계획의 노드 종류로 했다. 지급 승인 요청은 외부 호출과 PBKDF2 등으로 수십 ms이므로 이 차이는 요청 시간에 의미가 없다고 추정한다(이 비교는 측정하지 않았다).

### 변이 검증 (규칙을 깨면 테스트가 실패하는가)

변이마다 규칙 하나를 깨고 `PayoutOperatorMakerCheckerIntegrationTest`를 `--no-build-cache`로 실행한 뒤 파일을 복구하고 `cmp`로 원본과 같음을 확인했다.

| 변이 | 결과 | 실패한 테스트 |
|---|---|---|
| M1 trigger의 승인 규칙 분기 제거 | 실패 2건 | 같은 사람 승인 거절, 동시 승인 |
| M2 승인 행을 항상 `PAYOUT_APPROVED`로 기록(수정 되돌림) | 실패 1건 | 동시 승인(승인 행 2건) |
| M3 unique 인덱스를 일반 인덱스로 | 실패 1건 | 같은 사람 승인 거절(두 번째 직접 INSERT 통과) |
| M4 converter가 항상 운영자 권한 부여 | 실패 1건 | 운영자 인증 경계 |
| M5 수취 account trigger 제거 | 실패 1건 | 자기 프로필 검증 거절 |
| M6 수동 송금 기록 규칙 제거 | 실패 1건 | 수동 지급 쌍 |
| M7 묶인 버전 조건을 풀어 아무 버전의 검증자나 매칭 | **처음엔 살아남음**(통과) | 테스트가 이 경우를 보지 못했다. 위 "묶인 버전" 테스트를 추가한 뒤 실패 1건으로 잡혔고, 변이 없는 코드에서는 통과 |

M7 생존은 유용했다. 앱 수준 테스트에는 "프로필이 다음 버전으로 넘어간" 경우가 없었고 그 경우는 E1(SQL)에만 있었다.

## 교란 요인 결과

| 요인 | 결과 |
|---|---|
| 호스트 공유(Docker·CPU) | load average 20~152에서 실행. 첫 전체 반복 실행에서 테스트 23건이 컨테이너 연결 거부(`Connection refused`)로 실패했다. 같은 코드를 다시 실행해 전부 통과했다. 원인은 컨테이너 기동 경쟁으로 보이나 확정하지 않았다. 시간은 판정에 쓰지 않았다 |
| 하네스 버그(끼어들기가 일어나지 않음) | E1은 음성 대조 D0, N이 위반을 통과시켜 하네스가 위반을 만든다는 것을 보였다. 앱 수준 동시 승인은 기준 코드에서 100/100 라운드에 2행이 나와 두 요청이 실제로 겹친다는 것을 보였다. 수정 후 실행에서 진 쪽이 "초기 `find` 시점에 이미 보인 경로"(조기 replay)와 "커밋 경로"(`firstApproval=false`) 중 어느 쪽을 탔는지는 구분하지 않았다 |
| 빌드 캐시 | 첫 기준 실행이 `FROM-CACHE`로 재사용돼 라운드 수 변경이 반영되지 않았다. `--no-build-cache`로 다시 실행했고 위 표는 그 결과다 |
| 라운드 간 오염 | 라운드마다 새 UUID 시드, key는 라운드·행위자별 유일 |
| 시작 편향 | `CyclicBarrier`로 동시에 시작. 스레드 간 시작 순서를 라운드마다 바꾸지는 않았다 |
| 기준 대조와 변경 후의 차이 | 기준은 2스레드(같은 자격 증명), 변경 후는 3스레드(검증자 1 + 승인자 2). 승인 행 수의 비교는 승인 요청 2개에 대한 것이다 |

## 대안 비교의 결론

| 안 | 결론 |
|---|---|
| A0 하지 않음 | 기각. 기준 코드에서 같은 사람 검증·승인이 100/100 성공했고 동시 승인이 감사 행 2건을 남기는 결함도 있었다. 가장 강한 반론("Sandbox라 돈이 안 움직인다")은 그대로 유효하며 이 PR은 Live를 열었다고 주장하지 않는다 |
| A1 IdP 신원 | 대체가 아니라 후속으로 둔다. trigger와 UUID actor 규칙은 IdP가 생겨도 유지되고 converter만 바뀐다. 이번 범위에서 IdP는 저장소에 없다 |
| A2, A3 Basic 다중 사용자·해시 재사용 | 기각. 온라인 추측 한도와 MFA가 없다 |
| A4 account JWT + 권한 표 | 선택. 해시·로그인 한도·TOTP·세션 폐기를 재사용하고 요청마다 해제를 반영한다 |
| 강제 수단 | trigger 선택(x3에서만 차이). service 검사는 보조로도 두지 않았다(같은 규칙이 두 곳이 되기 때문) |

## 사전 부검 결과

| # | 결과 |
|---|---|
| F1 운영자 0명 | 이 PR은 시작 시 WARN을 두지 않았다(미구현). 표가 비면 운영자 경로가 모두 403이다. 운영 절차 문서에 적었다 |
| F2 소규모 팀이 막힘 | 운영 절차에 "운영자 2명 이상"을 적었다. 긴급 우회는 rollback flag뿐이다 |
| F3 해제 시 규칙 꺼짐 | trigger는 권한 표를 읽지 않는다. 해제해도 규칙은 그대로다 |
| F4 BEFORE trigger가 정당한 재시도를 막음 | 같은 key·같은 사실 재시도는 INSERT 전에 `matches`로 반환되어 trigger에 가지 않는다. H5 테스트로 확인 |
| F5 다른 migration과 번호 충돌 | 이 PR은 V5만 쓴다. 병합 전 `origin/main` 재확인 필요 |
| F6 오류 매핑이 500이 됨 | SQLSTATE 23514와 이름의 `_separation_of_duties`를 함께 보고 403으로 응답. 테스트로 확인 |
| (신규) F9 V5 인덱스가 기존 중복 행 때문에 생성 실패 | 기준 코드가 중복 승인 행을 만든다는 것을 이번에 확인했다. 배포 전 점검 쿼리를 ADR-0092에 적었다. 실제 DB에는 실행하지 않았다 |

## 계획에서 바뀐 것

- 사전 등록에서 H4는 "정확히 한 건이 이긴다"였다. 기준 코드에서는 100/100 라운드가 승인 행 2건이었고, 이를 고치는 코드 변경(진 쪽은 `PAYOUT_APPROVAL_REPLAYED`)과 unique 인덱스를 추가했다. 계획에 없던 변경이다.
- 위협 모델에 없던 "운영자 account가 수취 account"를 코드 읽기로 찾아 trigger를 하나 더 두었다.
- 수동 지급 쌍(H9)은 사전 등록대로 했고, 변경한 것은 `MANUAL_PAYOUT_CONFIRMED` 규칙의 이전 단계를 `MANUAL_PAYOUT_SENT`로 정한 것이다.
- E1 n은 계획대로 200. 앱 수준 반복은 계획대로 100(기본 3은 `check`용, `H7_ROUNDS`로 올림).
- 판정 기준은 바꾸지 않았다.

## 한계 (확인하지 못한 것)

- 이 하네스의 동시 승인은 한 JVM의 MockMvc 요청이다. 여러 application 인스턴스, 네트워크 지연, 실제 HTTP 경계는 실행하지 않았다.
- trigger와 부분 unique 인덱스의 성능은 지급 승인 규모(드문 요청)에서만 의미가 있다. 높은 동시성의 INSERT 처리량은 측정하지 않았다.
- TOTP: converter는 TOTP **등록**을 요구하지만 이 access token을 받은 로그인이 TOTP를 거쳤는지는 검사하지 않는다(JWT에 인증 수단 claim이 없다). 등록된 계정의 로그인이 challenge를 요구하는 것은 기존 account 테스트가 다룬다. 이 PR의 테스트는 TOTP 로그인 전체 흐름을 거치지 않고 등록 행을 직접 넣었다.
- rollback flag는 WebMvc 슬라이스 테스트로만 확인했다. `identity=shared-credential`로 애플리케이션을 끝까지 기동해 보지는 않았다.
- 기준 커밋의 Java 코드를 V5 스키마에서 실행해 보지 않았다.
- 같은 사람이 account를 둘 만들어 규칙을 우회하는 것은 막지 못한다. 담합, DB 권한자, 계정 잠금·회전 정책은 범위 밖이다.
- 다른 운영 조치(재큐, provider 검토 해소, 레거시 무이체 확인, 취소)에는 4-eyes를 적용하지 않았다. 이유는 ADR-0092 쌍별 판단 표에 있다.
- 시간 값은 공유 호스트의 맥락이다.

## 주장 표

| 주장 | 근거 | 신뢰도 |
|---|---|---|
| 기준 코드에서 같은 자격 증명의 프로필 검증과 지급 승인이 모두 성공하고 actor 문자열이 같다 | E3, n=100 라운드 | 확인(로컬) |
| 기준 코드의 동시 승인은 `PAYOUT_APPROVED` 행을 2건 남긴다 | E3, 100/100 라운드 | 확인(로컬, 한 JVM) |
| 수정 후 동시 승인은 승인 행 1건, outbox 1행이다 | E2, 100 라운드, 위반 0/100(상한 3%) | 확인(로컬, 한 JVM) |
| 같은 사람의 검증자 ≠ 승인자 규칙이 service를 거치지 않는 INSERT에서도 지켜진다 | E1 x3(0/200)과 통합 테스트의 직접 INSERT | 확인(로컬) |
| "현재 profile 행"을 읽는 규칙은 승인 도중 프로필이 바뀌면 위반을 놓친다 | E1 x2, N 200/200 | 확인(로컬, 결정적 끼어들기) |
| service 검사와 조건부 SQL은 규칙을 모르는 경로를 막지 못한다 | E1 x3, 200/200 | 확인(로컬, SQL 모형) |
| 감사 actor는 항상 인증된 principal(account id)이다 | E2 H6, 본문 actor 무시 | 확인(로컬, n=1 결정적 테스트) |
| 운영자 권한 해제와 계정 정지가 같은 토큰에 바로 적용된다 | E2 H8 | 확인(로컬) |
| V5는 additive이고 아래 "삭제한 파일의 내용"의 되돌림 SQL로 되돌릴 수 있다 | 되돌리기 훈련, 행 해시 동일 | 확인(로컬, SQL 수준) |
| rollback flag가 단일 자격 증명 모드를 되살린다 | 보안 슬라이스 테스트 2건 + actor 규칙 테스트 | 로컬만(앱 전체 기동은 하지 않음) |
| 이 신원 모델이 MFA를 **강제**한다 | TOTP 등록만 요구, 로그인 시 사용은 검사하지 않음 | 확인하지 못함(부분만) |
| Live 지급에 충분하다 | ADR-0078의 다른 gate가 미결 | 확인하지 못함 |
| 규칙이 공모·계정 이중 보유를 막는다 | 해당 없음 | 막지 못함 |
| trigger가 높은 동시성 INSERT를 느리게 하지 않는다 | 측정하지 않음 | 확인하지 못함 |
| 컨테이너 연결 거부 23건의 원인 | 재실행 통과, 원인 미확정 | 확인하지 못함 |

## 재현

SQL 하네스(E1, 되돌리기 훈련, 비용·계획)는 저장소에 넣지 않았다. 아래 "삭제한 파일의 내용" 절의 파일 5개를 한 폴더(예: `/tmp/exp096`)에 같은 이름으로 만들고 실행한다. 선행 조건은 Docker, `psql`이 든 `postgres:18.6` 이미지, 이 저장소의 `atlas-application/src/main/resources/db/migration/V*.sql`이다. `run.sh`는 저장소 루트를 `$HERE/../..`로 찾으므로 저장소 안의 `load-tests/operator-separation/`에 두거나 `ROOT`를 고쳐 쓴다.

```sh
# E1 SQL 수준(컨테이너 1개, 약 1~2분). 원본 출력은 $OUT(기본 /tmp/exp096-out)에 쓴다.
N=200 TAG=h7 OUT=/tmp/exp096-out ./run.sh

# E2 앱 수준(Testcontainers). H7_ROUNDS로 동시 승인 라운드 수를 올린다(기본 3).
H7_ROUNDS=100 ./gradlew --no-build-cache :atlas-application:cleanTest :atlas-application:test \
  --tests com.atlas.settlement.PayoutOperatorMakerCheckerIntegrationTest
```

E3(기준 커밋 대조)의 탐침과 변이 검증 스크립트는 저장소에 넣지 않았다. 그 내용은 아래 "삭제한 파일의 내용"에 적었다. 같은 동작은 이 PR의 테스트에서 trigger를 끈 음성 대조(`withTheRuleDisabledTheSameOperatorFlowSucceeds`)로도 다시 볼 수 있다.

## 삭제한 파일의 내용 (저장소에 넣지 않은 실험 산출물)

원본 파일은 로컬 `/tmp` 아래에만 있고 커밋하지 않았다. 이 문서가 결과를 다시 읽는 데 필요한 내용 전부다.

### E3 기준 커밋 탐침(`BaselineSharedCredentialProbeIntegrationTest`, 기준 커밋 worktree에서만 실행)

- 위치와 환경: `origin/main` `82345acd`의 별도 worktree에 테스트 파일 1개만 추가했다. `postgres:18.6` Testcontainers, `atlas.settlement.payout.operator.enabled=true`, username `payout-operator`, 32자 비밀번호(테스트용 상수), `atlas.payment.paypal.payouts.enabled=true`, PayPal은 `PayPalIntegrationTestSupport`의 로컬 MockWebServer.
- 라운드마다 하는 일: (1) Seller·수취 account·`PENDING_REVIEW` 프로필 v1·원장 capture 10,000원·지급 요청을 시드한다. (2) 운영자 Basic 자격 증명으로 `POST /api/internal/settlement/payout-profiles/{sellerId}/verify`(`profileVersion=1`)를 보낸다. (3) `CyclicBarrier(2)`로 두 스레드가 같은 자격 증명으로 `POST /api/internal/settlement/payouts/{payoutId}/approve`를 서로 다른 Idempotency-Key로 동시에 보낸다. (4) 검증 상태 코드, 승인 응답, `settlement_payout_operator_action`의 `PAYOUT_APPROVED` 행 수, `settlement_payout_dispatch_outbox` 행 수, 검증·승인 행의 actor를 읽는다.
- 명령: `H7_ROUNDS=100 ./gradlew --no-daemon --no-build-cache :atlas-application:cleanTest :atlas-application:test --tests <탐침 클래스>`. 처음 두 번은 Gradle 빌드 캐시(`FROM-CACHE`)가 이전 결과를 재사용해 라운드 수가 반영되지 않았다. 위 결과는 `--no-build-cache`로 다시 돌린 것이다.
- 출력 한 줄(n=100): `rounds=100 sameCredentialVerifyAndApproveAccepted=100 verifierEqualsApproverActorRounds=100 approvedRowsTotal=200 roundsWithDuplicateApprovalRows=100 outboxRows=100`. 앞선 n=3 실행은 `approvedRowsTotal=6 roundsWithDuplicateApprovalRows=3 outboxRows=3`.

### SQL 하네스 파일 5개의 내용과 무엇을 확인하는가 (`load-tests/operator-separation/`, 이 PR에서 제외)

실행 흐름(`run.sh`): (1) 컨테이너 `postgres:18.6`을 라벨 `atlasexp=$TAG`로 띄운다(종료 시 `trap`으로 삭제). (2) **A. 되돌리기 훈련**: V1~V4만 적용한 DB `pre`에 `fixtures.sql`과 지급 50건·기존 actor `legacy-operator`의 승인 감사 50행을 넣고 관련 표 행 해시를 잡는다 → V5 적용 → 해시 비교 → 기존 actor로 승인 감사 INSERT가 통과하는지 → `undo.sql` 실행 → 남은 객체 수와 해시 비교 → V5 재적용 시 trigger 수. (3) **B. 강제 수단 비교**: V1~V5를 적용한 DB `exp`에 `fixtures.sql`+`scenarios.sql`을 넣고 `CALL exp_run('x0'..'x3', $N)`을 실행해 `exp_result`를 TSV로 낸다. (4) **C. 비용과 계획**: 같은 DB에서 `timing.sql`을 실행한다. 환경 변수는 `N`(반복, 기본 200), `TAG`(기본 `h7`), `OUT`(출력 폴더).

| 파일 | 목적 | 입력 | 기대 결과(실제 결과는 위 결과 절) |
|---|---|---|---|
| `fixtures.sql` | `exp_seed(bound_verifier, current_verifier)`: 프로필 v3(ACTIVE, 현재 행 `verified_by` = `current_verifier`), v3의 `PROFILE_VERIFIED` 감사 행(actor = `bound_verifier`), 그 v3에 묶인 outbox가 있는 지급을 실제 V1 표에 만든다 | 두 actor 문자열 | seller, payout id를 돌려준다 |
| `scenarios.sql` | 변형 5종(D0, S, C, T, N)을 같은 시나리오 4종에서 비교한다. 변형은 각각 `exp_action_<변형>` 표(실제 감사 표의 `LIKE` 복사)에 쓴다. `exp_try`가 시도 하나를 실행하고 `accepted`·`rejected`·`error`를 돌려주며 `exp_run`이 n회 반복·집계한다. 변형 실행 순서는 반복마다 무작위, 시도마다 새 데이터, 승인 트랜잭션은 dblink 연결 `a`, 프로필 이동은 연결 `b`로 순서를 결정적으로 고정한다 | `exp_run(시나리오, n)` | x0: 전 변형 통과(과하게 거절하지 않음). x1: D0만 통과. x2: D0, N만 통과. x3: D0, S, C만 통과 |
| `timing.sql` | 다른 Seller 20,000명의 프로필 감사 행 200,000개를 `session_replication_role = replica`로 FK 없이 채운 뒤 trigger 조회의 `EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)`와, D0·T 각 3,000건 INSERT 시간(순서 무작위)의 최소·p50·p95·최대를 낸다 | 없음 | 계획에 전체 스캔 없음, INSERT 시간은 같은 자릿수 |
| `undo.sql` | V5가 만든 것만 지운다: 부분 unique 인덱스, 지급 운영자 감사 trigger와 함수, 프로필 감사 trigger와 함수, `payout_operator_account` 표. 기존 표·행·열은 건드리지 않으므로 되돌림 뒤 관련 표 행 해시가 V5 전과 같아야 한다 | 없음 | trigger, 함수, 표, 인덱스 0개 |
| `run.sh` | 위 흐름 전체와 집계 출력(`migration-drill.tsv`, `enforcement-comparison.tsv`, `trigger-cost-and-plan.txt`, `environment.txt`) | `N`, `TAG`, `OUT` | |

시나리오별 입력과 기대 결과(n=200, 변형별):

| 시나리오 | 입력(검증자 / 승인자) | 의미 | D0 | S | C | T | N |
|---|---|---|---|---|---|---|---|
| x0 | 묶인 버전의 검증자 A, 현재 행 A / 승인자 C(다른 사람) | 정당한 승인이 과하게 거절되지 않는가 | 통과 | 통과 | 통과 | 통과 | 통과 |
| x1 | 묶인 검증자 A, 현재 행 A / 승인자 A | 같은 사람 | 통과(위반) | 거절 | 거절 | 거절 | 거절 |
| x2 | v3 검증자 B, 현재 행 B / 승인자 B. 승인 트랜잭션이 프로필을 읽은 뒤 다른 연결이 프로필을 v4(`PENDING_REVIEW`)→v5(`ACTIVE`, 검증자 A)로 넘긴다. outbox는 v3 그대로 | 승인 도중 프로필 변경(TOCTOU). 승인자 B는 v3의 검증자다 | 통과(위반) | 거절 | 거절 | 거절 | 통과(위반, 현재 행의 검증자 A만 봄) |
| x3 | 묶인 검증자 B / 승인자 B, **규칙을 모르는 경로**(S, C의 클라이언트 검사를 거치지 않는 직접 INSERT) | 우회 경로 | 통과(위반) | 통과(위반) | 통과(위반) | 거절 | 거절 |

변형 정의: D0 규칙 없음. S 버전에 묶인 검증자를 별도 문장으로 조회해 같으면 INSERT를 하지 않음(dblink 연결 `a`). C `INSERT ... SELECT ... WHERE NOT (EXISTS 묶인 검증자 = actor)`. T V5의 `enforce_payout_operator_separation()` trigger를 그대로 `exp_action_t`에 붙임. N "그 지급 Seller의 현재 `seller_payout_profile.verified_by` = actor"만 보는 trigger(`exp_enforce_current_row`).

아래는 파일 내용 그대로다(주석 포함).

`fixtures.sql`

```sql
-- EXP-096 고정 데이터. 실제 V1 표에 프로필·지급·outbox를 한 벌 만든다(시나리오와 되돌림 훈련이 함께 쓴다).
-- 고정 데이터: 프로필 v3(ACTIVE, 현재 행 verified_by = current_verifier), v3의 PROFILE_VERIFIED 행(actor = bound_verifier),
-- 그 버전에 묶인 outbox가 있는 지급.
CREATE OR REPLACE FUNCTION exp_seed(bound_verifier text, current_verifier text, OUT seller uuid, OUT payout uuid)
LANGUAGE plpgsql AS $$
DECLARE
    account uuid := gen_random_uuid();
BEGIN
    seller := gen_random_uuid();
    payout := gen_random_uuid();
    INSERT INTO seller(seller_id) VALUES (seller);
    INSERT INTO user_account(account_id, email, password_hash, email_verified_at)
        VALUES (account, account || '@exp.test', 'h', now());
    INSERT INTO seller_account(account_id, seller_id) VALUES (account, seller);
    INSERT INTO seller_payout_profile(seller_id, recipient_account_id, country_code, payout_currency, status,
        profile_version, created_at, updated_at, verified_at, verified_by)
        VALUES (seller, account, 'US', 'USD', 'ACTIVE', 3, now(), now(), now(), current_verifier);
    INSERT INTO seller_payout_profile_action(seller_id, profile_version, action, idempotency_key,
        request_facts_sha256, actor, reason, created_at)
        VALUES (seller, 3, 'PROFILE_VERIFIED', 'v3-' || seller, repeat('0', 64), bound_verifier, 'case', now());
    INSERT INTO settlement_payout(payout_id, seller_id, period_start, period_end, amount_won, state,
        idempotency_key, requested_at, updated_at)
        VALUES (payout, seller, now() - interval '1 day', now() + interval '1 day', 1000, 'APPROVED',
        'exp-' || payout, now(), now());
    INSERT INTO settlement_payout_dispatch_outbox(payout_id, profile_version, recipient_email_ciphertext,
        recipient_country_code, requested_amount_won, provider_amount_minor, fx_rate_won_per_usd, fx_source,
        fx_as_of_date, provider_debit_book_value_won, payout_fx_rounding_expense_won, sender_batch_id,
        sender_item_id, paypal_request_id, request_payload_sha256, status, available_at, first_enqueued_at,
        created_at, updated_at)
        VALUES (payout, 3, 'x', 'US', 1000, 100, 1000, 'exp', current_date, 1000, 0, 'b-' || payout,
        'i-' || payout, 'r-' || payout, repeat('a', 64), 'PENDING', now(), now(), now(), now());
END $$;
```

`scenarios.sql`

```sql
-- EXP-096 E1: 승인자 ≠ 검증자 규칙의 강제 수단 비교(SQL 수준).
-- 실행: load-tests/operator-separation/run.sh (컨테이너를 만들고 migration을 적용한 뒤 이 파일을 실행한다).
--
-- 변형(variant)별로 감사 행 표를 따로 만들어 같은 고정 데이터(실제 V1 표)를 읽게 한다.
--   d0 규칙 없음(음성 대조)
--   s  service 검사: 버전에 묶인 검증자를 읽고(별도 문장), 다르면 INSERT
--   c  조건부 SQL: INSERT ... SELECT ... WHERE NOT EXISTS (버전에 묶인 검증자 = actor)
--   t  DB trigger: V5가 만든 enforce_payout_operator_separation() 그대로
--   n  "현재 profile 행의 verified_by" 를 읽는 trigger(음성 대조: 규칙이 읽을 값을 잘못 고른 경우)
--
-- 시나리오(scenario)
--   x0 정당한 승인(검증자와 다른 사람)         기대: 전 변형 통과(규칙이 과하게 거절하지 않음)
--   x1 같은 사람(검증자 = 승인자)              기대: d0만 통과
--   x2 승인 트랜잭션 도중 프로필이 다음 버전으로 넘어감. 승인자는 outbox에 묶인 버전의 검증자  기대: d0, n 통과(위반)
--   x3 규칙을 모르는 경로의 직접 INSERT(검증자 = 승인자)  기대: d0, s, c 통과(위반), t, n 거절
-- 승인 트랜잭션은 dblink 연결 a, 프로필 이동은 연결 b이고 한 세션 안에서 순서를 결정적으로 고정한다.
\set ON_ERROR_STOP on
\timing off
CREATE EXTENSION IF NOT EXISTS dblink;
SELECT dblink_connect('a', format('dbname=%s user=%s', current_database(), current_user));
SELECT dblink_connect('b', format('dbname=%s user=%s', current_database(), current_user));

DROP TABLE IF EXISTS exp_result;
CREATE TABLE exp_result (
    scenario text NOT NULL, variant text NOT NULL, accepted int NOT NULL DEFAULT 0,
    rejected_by_rule int NOT NULL DEFAULT 0, other_error int NOT NULL DEFAULT 0, n int NOT NULL DEFAULT 0,
    PRIMARY KEY (scenario, variant));

DO $$
DECLARE v text;
BEGIN
    FOREACH v IN ARRAY ARRAY['d0', 's', 'c', 't', 'n'] LOOP
        EXECUTE format('DROP TABLE IF EXISTS exp_action_%s', v);
        EXECUTE format('CREATE TABLE exp_action_%s (LIKE settlement_payout_operator_action INCLUDING DEFAULTS)', v);
    END LOOP;
END $$;

CREATE TRIGGER exp_t BEFORE INSERT ON exp_action_t
    FOR EACH ROW EXECUTE FUNCTION enforce_payout_operator_separation();

CREATE OR REPLACE FUNCTION exp_enforce_current_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.actor !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN RETURN NEW; END IF;
    IF NEW.action IN ('PAYOUT_APPROVED', 'PAYOUT_APPROVAL_REPLAYED') AND EXISTS (
        SELECT 1 FROM settlement_payout payout
        JOIN seller_payout_profile profile ON profile.seller_id = payout.seller_id
        WHERE payout.payout_id = NEW.payout_id AND profile.verified_by = NEW.actor) THEN
        RAISE EXCEPTION 'exp_n_separation_of_duties' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exp_n BEFORE INSERT ON exp_action_n
    FOR EACH ROW EXECUTE FUNCTION exp_enforce_current_row();

-- 한 시도를 한 변형에서 실행하고 'accepted' / 'rejected' / 'error' 중 하나를 돌려준다.
-- client_path=true 이면 s, c 변형은 자기 검사를 거치고, false 이면 규칙을 모르는 경로의 직접 INSERT 다.
CREATE OR REPLACE FUNCTION exp_try(variant text, payout uuid, actor text, client_path boolean) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    bound_match text := format($q$SELECT EXISTS (SELECT 1 FROM settlement_payout p
        JOIN settlement_payout_dispatch_outbox o ON o.payout_id = p.payout_id
        JOIN seller_payout_profile_action v ON v.seller_id = p.seller_id AND v.profile_version = o.profile_version
            AND v.action = 'PROFILE_VERIFIED'
        WHERE p.payout_id = %L AND v.actor = %L)$q$, payout, actor);
    plain text := format($q$INSERT INTO exp_action_%s(payout_id, idempotency_key, action, actor, reason, created_at)
        VALUES (%L, %L, 'PAYOUT_APPROVED', %L, 'exp', now())$q$, variant, payout, gen_random_uuid()::text, actor);
    conditional text := format($q$INSERT INTO exp_action_c(payout_id, idempotency_key, action, actor, reason, created_at)
        SELECT %L, %L, 'PAYOUT_APPROVED', %L, 'exp', now() WHERE NOT (%s)$q$, payout, gen_random_uuid()::text, actor,
        regexp_replace(bound_match, '^SELECT ', ''));
    matched boolean;
    status text;
BEGIN
    IF client_path AND variant = 's' THEN
        SELECT m INTO matched FROM dblink('a', bound_match) AS t(m boolean);
        IF matched THEN RETURN 'rejected'; END IF;
        PERFORM dblink_exec('a', plain);
    ELSIF client_path AND variant = 'c' THEN
        status := dblink_exec('a', conditional);
        IF status = 'INSERT 0 0' THEN RETURN 'rejected'; END IF;
    ELSE
        PERFORM dblink_exec('a', plain);
    END IF;
    RETURN 'accepted';
EXCEPTION WHEN OTHERS THEN
    IF SQLERRM LIKE '%separation_of_duties%' THEN RETURN 'rejected'; END IF;
    RAISE NOTICE 'exp_try other error (%): %', variant, SQLERRM;
    RETURN 'error';
END $$;

-- n회 반복. 시도마다 새 데이터를 쓰고 변형 실행 순서를 무작위로 섞는다.
CREATE OR REPLACE PROCEDURE exp_run(which text, n int) LANGUAGE plpgsql AS $$
DECLARE
    a_id text := gen_random_uuid()::text;
    b_id text := gen_random_uuid()::text;
    c_id text := gen_random_uuid()::text;
    v text;
    s record;
    outcome text;
    i int;
BEGIN
    FOR i IN 1..n LOOP
        FOR v IN SELECT x FROM unnest(ARRAY['d0', 's', 'c', 't', 'n']) AS x ORDER BY random() LOOP
            IF which = 'x0' THEN
                SELECT * INTO s FROM exp_seed(a_id, a_id);
            ELSIF which = 'x1' THEN
                SELECT * INTO s FROM exp_seed(a_id, a_id);
            ELSIF which = 'x2' THEN
                SELECT * INTO s FROM exp_seed(b_id, b_id);
            ELSE
                SELECT * INTO s FROM exp_seed(b_id, b_id);
            END IF;
            COMMIT;
            PERFORM dblink_exec('a', 'BEGIN');
            IF which = 'x2' THEN
                -- 승인 트랜잭션이 프로필을 읽은 뒤, 다른 연결이 프로필을 v4(PENDING_REVIEW) → v5(ACTIVE, 검증자 a)로 넘긴다.
                PERFORM * FROM dblink('a', format('SELECT verified_by FROM seller_payout_profile WHERE seller_id = %L', s.seller))
                    AS t(verified_by text);
                PERFORM dblink_exec('b', format($q$UPDATE seller_payout_profile SET status = 'PENDING_REVIEW',
                    profile_version = 4, verified_at = NULL, verified_by = NULL WHERE seller_id = %L$q$, s.seller));
                PERFORM dblink_exec('b', format($q$UPDATE seller_payout_profile SET status = 'ACTIVE', profile_version = 5,
                    verified_at = now(), verified_by = %L WHERE seller_id = %L$q$, a_id, s.seller));
                PERFORM dblink_exec('b', format($q$INSERT INTO seller_payout_profile_action(seller_id, profile_version, action,
                    idempotency_key, request_facts_sha256, actor, reason, created_at)
                    VALUES (%L, 5, 'PROFILE_VERIFIED', 'v5-' || %L, repeat('0', 64), %L, 'case', now())$q$,
                    s.seller, s.seller, a_id));
            END IF;
            outcome := exp_try(v, s.payout,
                CASE which WHEN 'x0' THEN c_id WHEN 'x1' THEN a_id ELSE b_id END,
                which <> 'x3');
            PERFORM dblink_exec('a', CASE outcome WHEN 'accepted' THEN 'COMMIT' ELSE 'ROLLBACK' END);
            INSERT INTO exp_result(scenario, variant, n, accepted, rejected_by_rule, other_error)
                VALUES (which, v, 1, (outcome = 'accepted')::int, (outcome = 'rejected')::int, (outcome = 'error')::int)
                ON CONFLICT (scenario, variant) DO UPDATE SET n = exp_result.n + 1,
                    accepted = exp_result.accepted + EXCLUDED.accepted,
                    rejected_by_rule = exp_result.rejected_by_rule + EXCLUDED.rejected_by_rule,
                    other_error = exp_result.other_error + EXCLUDED.other_error;
            COMMIT;
        END LOOP;
    END LOOP;
END $$;
```

`timing.sql`

```sql
-- EXP-096 H10: trigger가 INSERT 한 건에 더하는 비용(맥락)과 trigger 조회의 실행 계획.
-- 시간은 공유 호스트의 값이라 판정에 쓰지 않는다. 판정은 실행 계획의 노드 종류(Index Scan 여부)로 한다.
\set ON_ERROR_STOP on
\timing off
-- 계획 확인용으로 다른 Seller 20,000명의 프로필 감사 행 200,000개를 FK 검사 없이 채운다.
SET session_replication_role = replica;
INSERT INTO seller_payout_profile_action(seller_id, profile_version, action, idempotency_key,
    request_facts_sha256, actor, reason, created_at)
SELECT s.id, 1 + (g % 10), 'PROFILE_SUBMITTED', 'bulk-' || g, repeat('0', 64), 'bulk', 'bulk', now()
FROM generate_series(1, 200000) g CROSS JOIN LATERAL (SELECT (md5((g % 20000)::text))::uuid AS id) s;
SET session_replication_role = origin;
ANALYZE seller_payout_profile_action;

SELECT seller AS seller, payout AS payout FROM exp_seed(gen_random_uuid()::text, gen_random_uuid()::text) \gset
COMMIT;

\echo '--- trigger 조회의 실행 계획 (seller_payout_profile_action 200,000행)'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
SELECT EXISTS (
    SELECT 1 FROM settlement_payout payout
    JOIN settlement_payout_dispatch_outbox outbox ON outbox.payout_id = payout.payout_id
    JOIN seller_payout_profile_action verification ON verification.seller_id = payout.seller_id
        AND verification.profile_version = outbox.profile_version AND verification.action = 'PROFILE_VERIFIED'
    WHERE payout.payout_id = :'payout' AND verification.actor = 'x');

CREATE TABLE IF NOT EXISTS exp_timing(variant text, micros double precision);
TRUNCATE exp_timing;
DO $$
DECLARE
    payout uuid := (SELECT payout_id FROM settlement_payout ORDER BY requested_at DESC LIMIT 1);
    actor text := gen_random_uuid()::text;
    started timestamptz;
    v text;
    i int;
BEGIN
    FOR i IN 1..3000 LOOP
        FOR v IN SELECT x FROM unnest(ARRAY['d0', 't']) AS x ORDER BY random() LOOP
            started := clock_timestamp();
            EXECUTE format($q$INSERT INTO exp_action_%s(payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (%L, %L, 'PAYOUT_APPROVED', %L, 'exp', now())$q$, v, payout, gen_random_uuid()::text, actor);
            INSERT INTO exp_timing VALUES (v, extract(epoch FROM clock_timestamp() - started) * 1e6);
        END LOOP;
    END LOOP;
END $$;
\echo '--- INSERT 한 건 시간(마이크로초), 변형별. 3,000건씩, 순서 무작위'
SELECT variant, count(*) AS n, round(min(micros)::numeric) AS min_us,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY micros)::numeric) AS p50_us,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY micros)::numeric) AS p95_us,
       round(max(micros)::numeric) AS max_us
FROM exp_timing GROUP BY variant ORDER BY variant;
```

`undo.sql`

```sql
-- V5의 되돌림 SQL(EXP-096 "되돌리기 훈련"). 새로 만든 것만 지우므로 기존 표·행은 그대로다.
DROP INDEX uq_settlement_payout_operator_action_approved;
DROP TRIGGER settlement_payout_operator_action_separation ON settlement_payout_operator_action;
DROP FUNCTION enforce_payout_operator_separation();
DROP TRIGGER seller_payout_profile_action_separation ON seller_payout_profile_action;
DROP FUNCTION enforce_profile_verifier_is_not_recipient();
DROP TABLE payout_operator_account;
```

`run.sh`

```bash
#!/usr/bin/env bash
# EXP-096 E1 한 줄 실행: load-tests/operator-separation/run.sh
# 환경 변수: N(시나리오·변형당 반복, 기본 200), TAG(컨테이너 label atlasexp=<TAG>, 기본 h7), OUT(원본 결과 출력 폴더, 기본 /tmp/exp096-out. 저장소에 넣지 않는다)
# 컨테이너 1개(postgres:18.6)를 만들고 끝나면 지운다. 이미지 기본 설정이며 값은 맥락이다.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
N="${N:-200}"
TAG="${TAG:-h7}"
OUT="${OUT:-/tmp/exp096-out}"
MIGRATIONS="$ROOT/atlas-application/src/main/resources/db/migration"
NAME="${TAG}-pg-opsep"
mkdir -p "$OUT"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
psql_db() { local db="$1"; shift; docker exec -i "$NAME" psql -U postgres -d "$db" -v ON_ERROR_STOP=1 -X -q "$@"; }
apply() { local db="$1"; shift; for f in "$@"; do psql_db "$db" -o /dev/null < "$f"; done; }

echo "uptime(before): $(uptime)" | tee "$OUT/environment.txt"
docker run -d --label "atlasexp=$TAG" --name "$NAME" -e POSTGRES_PASSWORD=x postgres:18.6 >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$NAME" psql -U postgres -c 'select 1' >/dev/null 2>&1; then sleep 2; \
     docker exec "$NAME" psql -U postgres -c 'select 1' >/dev/null 2>&1 && break; fi
  sleep 1
done
docker exec "$NAME" psql -U postgres -tAc 'select version()' | tee -a "$OUT/environment.txt"
docker stats --no-stream --format 'docker stats {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' "$NAME" | tee -a "$OUT/environment.txt"

PRE=()
while IFS= read -r f; do PRE+=("$f"); done < <(ls "$MIGRATIONS"/V*.sql | grep -v 'V5__payout_operator_identity' | sort -V)
V5="$(ls "$MIGRATIONS"/V5__payout_operator_identity*.sql)"

echo "== A. migration additive drill (V5 전후 기존 행 해시, 기존 흐름, 되돌림, 재적용)"
psql_db postgres -c 'create database pre' >/dev/null
apply pre "${PRE[@]}" "$HERE/fixtures.sql"
SNAPSHOT="select md5(coalesce(string_agg(t.x, '|' order by t.x), '')) from (
  select p::text x from settlement_payout p union all select o::text from settlement_payout_dispatch_outbox o
  union all select a::text from seller_payout_profile_action a union all select s::text from seller_payout_profile s
  union all select u::text from user_account u union all select oa::text from settlement_payout_operator_action oa) t"
psql_db pre -c "do \$\$ declare i int; s record; begin for i in 1..50 loop select * into s from exp_seed('legacy-operator','legacy-operator');
  insert into settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
  values (s.payout, 'legacy-'||i, 'PAYOUT_APPROVED', 'legacy-operator', 'legacy', now()); end loop; end \$\$"
BEFORE="$(psql_db pre -tA -c "$SNAPSHOT")"
ROWS_BEFORE="$(psql_db pre -tA -c "select (select count(*) from settlement_payout)||','||(select count(*) from settlement_payout_operator_action)")"
apply pre "$V5"
AFTER="$(psql_db pre -tA -c "$SNAPSHOT")"
apply pre "$HERE/undo.sql"
GONE="$(psql_db pre -tA -c "select (select count(*) from pg_trigger where tgname='settlement_payout_operator_action_separation')||','||(select count(*) from pg_proc where proname='enforce_payout_operator_separation')||','||(select count(*) from pg_class where relname='payout_operator_account')||','||(select count(*) from pg_class where relname='uq_settlement_payout_operator_action_approved')")"
AFTER_UNDO="$(psql_db pre -tA -c "$SNAPSHOT")"
apply pre "$V5"
REAPPLIED="$(psql_db pre -tA -c "select count(*) from pg_trigger where tgname='settlement_payout_operator_action_separation'")"
LEGACY_FLOW="$(psql_db pre -tA -c "do \$\$ declare s record; begin select * into s from exp_seed('legacy-operator','legacy-operator');
  insert into settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
  values (s.payout, 'legacy-after-v5', 'PAYOUT_APPROVED', 'legacy-operator', 'legacy', now()); end \$\$; select 'ok'" | tail -1)"

{
  echo -e "check\tvalue"
  echo -e "rows_before(payout,action)\t$ROWS_BEFORE"
  echo -e "snapshot_equal_after_V5\t$([ "$BEFORE" = "$AFTER" ] && echo yes || echo NO)"
  echo -e "legacy_actor_flow_after_V5\t$LEGACY_FLOW"
  echo -e "objects_after_undo(trigger,function,table,index)\t$GONE"
  echo -e "snapshot_equal_after_undo\t$([ "$BEFORE" = "$AFTER_UNDO" ] && echo yes || echo NO)"
  echo -e "trigger_count_after_reapply\t$REAPPLIED"
} | tee "$OUT/migration-drill.tsv"

echo "== B. 강제 수단 비교 (N=$N)"
psql_db postgres -c 'create database exp' >/dev/null
apply exp "${PRE[@]}" "$V5"
{
  cat "$HERE/fixtures.sql" "$HERE/scenarios.sql"
  for s in x0 x1 x2 x3; do echo "CALL exp_run('$s', $N);"; done
} | psql_db exp -o /dev/null
psql_db exp -A -F $'\t' -c "select scenario, variant, n, accepted, rejected_by_rule, other_error from exp_result order by scenario, variant" \
  | tee "$OUT/enforcement-comparison.tsv"

echo "== C. trigger 비용과 실행 계획"
psql_db exp < "$HERE/timing.sql" | tee "$OUT/trigger-cost-and-plan.txt"
echo "uptime(after): $(uptime)" | tee -a "$OUT/environment.txt"
docker ps -a --filter "label=atlasexp=$TAG" --format '{{.Names}}' | sed 's/^/remaining container: /'
```

### E2 동시 승인 출력(변경 후, `H7_ROUNDS=100`)

`rounds=100 approvedRows=100 outboxRows=100 verifierRejected=100 approverConflict409=0 approverAccepted202=200`. 승인자 두 명의 응답은 모두 202였고 진 쪽의 감사 행은 `PAYOUT_APPROVAL_REPLAYED`다. 첫 실행(n=3, 수정 전)은 `승인 행은 정확히 한 건` 단정에서 2건으로 실패했고 그 실패가 기준 코드의 중복 승인 행 발견으로 이어졌다.

### E1 환경과 되돌리기 훈련 원본

- 환경 기록: 실행 전 `uptime` load average 21.36 25.10 22.08, 실행 후 20.48 24.47 21.97(N=200 실행 전후). PostgreSQL 18.6(Debian 18.6-1.pgdg13+2, aarch64), 컨테이너 CPU 0.06%, 메모리 25.9 MiB / 12.64 GiB(유휴 상태에서 측정).
- 되돌리기 훈련 표(`migration-drill`):

| 점검 | 값 |
|---|---|
| V5 전 행 수(지급, 운영자 조치) | 50, 50 |
| V5 적용 뒤 관련 표 행 해시가 처음과 같은가 | 예 |
| V5 적용 뒤 기존 actor 문자열(`legacy-operator`)로 승인 감사 행 INSERT | 통과 |
| 되돌림 SQL(`undo.sql`) 뒤 남은 (trigger, function, table, index) 수 | 0, 0, 0, 0 |
| 되돌림 뒤 행 해시가 처음과 같은가 | 예 |
| V5 재적용 뒤 trigger 수 | 1 |

  해시는 `settlement_payout`, `settlement_payout_dispatch_outbox`, `seller_payout_profile_action`, `seller_payout_profile`, `user_account`, `settlement_payout_operator_action`의 모든 행 문자열을 정렬해 이어 붙인 MD5다. 위 표는 최종 V5(trigger 2개, 함수 2개, 인덱스 1개, 표 1개)로 `N=200` 전체 실행에서 얻었다.

- 하네스 버그 한 건: N=20 확인 실행의 첫 결과에서 `snapshot_equal_after_undo`가 `NO`였다. 원인은 점검 순서였다(되돌림 전에 새 행을 넣는 "기존 actor 흐름" 점검을 하고 해시를 잡아 행이 늘었다). 그 점검을 되돌림 뒤로 옮기자 `yes`가 나왔다. 하네스 버그였고 V5 문제가 아니었다.
- 실행 계획과 INSERT 시간은 위 H10 절의 값이 원본 그대로다(`ANALYZE` 뒤 계획, 3,000건 × 2, 순서 무작위).

### 변이 검증 절차

각 변이는 파일 하나에 문자열 치환 하나를 적용하고(`str.replace`로 첫 일치만, 일치가 없으면 중단), `H7_ROUNDS=20`으로 `PayoutOperatorMakerCheckerIntegrationTest`를 `--no-build-cache`로 실행하고, 원본 복사본으로 되돌린 뒤 `cmp`로 같음을 확인했다.

| 변이 | 치환 |
|---|---|
| M1 | V5의 `IF NEW.action IN ('PAYOUT_APPROVED', 'PAYOUT_APPROVAL_REPLAYED') THEN` → `IF NEW.action IN ('NEVER_MATCHES') THEN` |
| M2 | `boolean firstApproval = dispatches.find(payoutId).isEmpty();` → `boolean firstApproval = true;` |
| M3 | `CREATE UNIQUE INDEX uq_settlement_payout_operator_action_approved` → `CREATE INDEX ...` |
| M4 | converter의 `accounts.isPayoutOperator(accountId)` 조건을 `true`로 |
| M5 | V5의 `IF NEW.action = 'PROFILE_VERIFIED' AND EXISTS (` → `IF NEW.action = 'NEVER' AND EXISTS (` |
| M6 | V5의 `ELSIF NEW.action = 'MANUAL_PAYOUT_SENT' THEN` → `ELSIF NEW.action = 'NEVER_SENT' THEN` |
| M7 | V5의 `AND verification.profile_version = outbox.profile_version` → `AND verification.profile_version >= 0 AND outbox.profile_version >= 0` |

결과는 위 변이 검증 표와 같다. M7은 "묶인 버전" 테스트를 추가하기 전(7건 중 실패 0)과 후(8건 중 실패 1, 변이 없는 코드는 8건 모두 통과)를 모두 실행했다.

### 한 번 실패한 전체 실행

테스트 하나만이 아니라 `schema` 묶음을 함께 돌린 한 번의 실행에서 23건이 `Connection refused`로 실패했다(`PayoutOperatorMakerCheckerIntegrationTest` 7건 포함, 호스트 load average 152 구간). 코드 변경 없이 같은 명령을 다시 실행해 전부 통과했다. 원인은 확정하지 않았다. 마지막 `./gradlew clean check`(기준 `origin/main` 위, Docker 공유 상태)는 178개 XML 보고서, 1,048건 테스트, 실패 0, 14분 8초였다.
