# EXP-096: 운영자 사람별 신원과 승인자 ≠ 검증자 규칙은 어떤 강제 수단에서 경쟁 조건에도 깨지지 않는가

- 질문: 프로필 검증과 지급 승인을 같은 사람이 하지 못하게 하려면 신원 모델을 어디까지 바꿔야 하고, 그 규칙을 service 검사·조건부 SQL·DB trigger 중 무엇으로 강제해야 동시 요청과 우회 경로에서 깨지지 않는가?
- 상태: **사전 등록(실행 전).** 이 문서는 아무것도 실행하기 전에 커밋한다. 결과·판정·한계는 이후 커밋에서 이 문서에 이어 적고, 가설 표와 판정 기준은 고치지 않는다. 바꾼 것이 생기면 "계획에서 바뀐 것" 절에 적는다.
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

- E1 SQL 수준 비교(H1~H3, H10): `load-tests/operator-separation/run.sh` 한 줄. 라벨 `atlasexp=h7` 컨테이너 1개.
- E2 앱 수준 통합 테스트(H1, H4~H9): PostgreSQL Testcontainers. 같은 사람 거절, 다른 사람 성공, 동시 두 사람, 재전송, 본문 actor 무시, 음성 대조, 변이 검증.
- E3 기준 커밋 대조(통제 a): 기준 커밋 worktree에서 같은 자격 증명으로 검증·승인이 모두 성공하고 actor가 같음을 기록.

## 결과, 판정, 한계, 주장 표

실행 후 이어 적는다.
