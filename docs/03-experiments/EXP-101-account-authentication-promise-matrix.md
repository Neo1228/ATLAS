# EXP-101: FR-018 계정 인증 약속은 약속마다 실제 PostgreSQL 테스트가 증명하는가, 증명하지 못하는 약속은 코드가 지키는가

- 질문: FR-018(`◐ 구현·통합 검증 필요`)은 이메일 인증, 로그인, 10분 JWT, 7일 refresh와 30일 family, 회전·재사용 폐기, 30분 재설정, 선택형 TOTP, 모든 보호 경로의 소유 검증을 약속한다. 각 약속은 어떤 테스트가 어떤 단정으로 증명하는가, 증명이 없는 약속은 코드가 실제로 지키는가.
- 상태: 사전 등록(측정 전). 결과 절은 측정 뒤에 같은 파일에 덧붙인다(가설·판정 규칙은 이 커밋 이후 바꾸지 않는다).
- 날짜: 2026-11-26
- 관련: [Issue #672](https://github.com/Neo1228/ATLAS/issues/672), [Issue #206](https://github.com/Neo1228/ATLAS/issues/206)(최초 구현, 닫힘), [ADR-0027](../02-decisions/adr/ADR-0027-account-jwt-authentication.md), [ADR-0085](../02-decisions/adr/ADR-0085-v1-scope-channel-operator-and-payout-decisions.md), [ADR-0092](../02-decisions/adr/ADR-0092-payout-operator-person-identity-and-maker-checker.md), FR-018, BR-018, INV-015, Q-007

## 문제 한 문장

FR-018·BR-018·INV-015·ADR-0027은 약 25개의 약속 문장을 담고 있지만, 문장과 테스트의 대응표가 없고, 읽어서 확인한 결과 경계 시각(30일·7일·30분·5분·24시간), 동시 refresh 경합, 복구 코드·TOTP step 단회 소비, 자원 종류별 타 계정 접근 거부, 실제 라우트 전체의 기본 거부는 모의 객체 단위 테스트나 프로브 컨트롤러 한 개로만 확인돼 있어 "구현됨"과 "증명됨"이 구분되지 않는다.

## 측정 전 대응 현황 (코드와 단정을 읽어 정리, 실행 전)

`단위`는 모의 저장소 단위 테스트, `통합`은 실제 PostgreSQL. 확신도 열은 측정 전 평가이고 결과 절에서 갱신한다.

| 번호 | 약속 | 측정 전 증명 | 판정 |
| --- | --- | --- | --- |
| P01 | 가입 뒤 24시간 verification token 소비 전에는 login 불가 | 소비·만료·재사용 `AccountServiceTest` 단위, 가입→인증→로그인 정상 경로 `BuyerFlowIntegrationTest.createBuyerAccount` | 미인증 login 거부 통합 NONE, 24시간 경계 통합 NONE |
| P02 | 비밀번호 로그인, 일반 오류, 없는 이메일에 더미 hash | `AccountServiceTest` 단위 | 통합은 정상 경로만 |
| P03 | 10분 JWT access | `AccountJwtConfigurationTest`가 `exp-iat == 600`과 만료 거절 확인 | 강함. 허용 오차 60초(Spring 기본) 경계는 단정 없음 |
| P04 | 7일 opaque refresh | `refreshRotatesTokenAndPersistsOnlyItsReplacementHash` 단위(모의) | 7일 경계 통합 NONE |
| P05 | 30일 family 만료, refresh 만료는 family를 넘지 않음 | access 쪽만 `expiredRefreshFamilyRejectsStillUnexpiredAccessToken`(DB 직접 수정) | refresh 쪽 30일 경계와 `minimum()` 상한 NONE |
| P06 | refresh 매번 회전(family 유지) | `AccountAuthenticationIntegrationTest.refreshRotationKeepsFamily...` | 강함 |
| P07 | 소비된 token 재사용 시 family 폐기 | 위 테스트가 access token 둘이 401임을 확인 | 최신 refresh token이 막히는지 NONE, 동시 재사용 경합 NONE(ADR-0027 검증 목록에는 있음) |
| P08 | logout은 현재 family만 폐기 | `logoutRevokesOnlyItsAccessTokenFamily...` | 강함 |
| P09 | password reset token 30분 | 만료 거절 `AccountServiceTest` 단위(모의) | 30분 경계 통합 NONE |
| P10 | reset 성공 시 모든 refresh family와 미소비 MFA challenge 폐기 | family: `sessionInventory...`, `BuyerFlow.passwordResetRevokesExistingRefreshSession`. challenge: 저장소 직접 호출만 | 서비스 경로의 MFA challenge 폐기 NONE |
| P11 | 인증·재설정 token은 hash만 저장, 단회 소비 | 재사용 거절 단위(모의) | DB 실제 단회 소비·원문 비저장 NONE |
| P12 | login은 row lock 아래에서 현재 hash 재검증 | `loginCredentialRowLockBlocksConcurrentPasswordReset`, `rejectsPasswordThatChangedBeforeTheLoginCouldLockTheAccount` | 강함 |
| P13 | TOTP secret은 외부 key로 AES-GCM 암호화 저장 | `AccountMfaSecretProtectorTest` 단위 | DB 행이 암호문인지 NONE |
| P14 | MFA login challenge 5분 | `createMfaChallenge(..., +5분)` 호출 확인 단위 | 5분 경계 통합 NONE |
| P15 | recovery code 10개, hash 저장, 단회 소비 | 개수 `BuyerFlow`, 소비 단위(모의) | 실제 DB 단회 소비 NONE |
| P16 | TOTP time step은 account별 단회 소비, 허용 오차 ±1 | 단위(모의 `markMfaStepUsed`) | 실제 DB 재사용 거절 NONE |
| P17 | signup·verification·reset 요청은 존재 여부와 무관하게 같은 status·body | 단위 | HTTP 통합 NONE |
| P18 | 처리 시간 constant-time은 보장하지 않음(Q-007) | 문장뿐, 측정 없음 | 측정 NONE |
| P19 | JWT는 서명·algorithm·iss·aud·exp·필수 claim 검증 | `AccountJwtConfigurationTest` | 서명·iss·aud·exp·claim 강함, algorithm(HS512, none) 단정 NONE |
| P20 | refresh token은 hash만 저장 | 단위(모의) | DB NONE |
| P21 | 모든 protected operation에서 Buyer/Seller 소유 검증 | `AccountOwnershipInterceptorTest`(모의 서비스), `BuyerFlow`의 주문 목록과 percent-encoded 경로 | 자원 종류별 타 계정 거절 통합 대부분 NONE, 라우트 목록 대응 NONE |
| P22 | 명시하지 않은 route는 default deny | `ApiSecurityConfigurationTest`는 프로브 컨트롤러 1개 | 실제 라우트 전체 NONE |
| P23 | client가 보낸 `buyerRef`·`sellerId`는 principal 증거가 아님 | P21과 같음 | P21과 같음 |

## 가설 (실행 전에 적음)

모든 시간 경계는 주입한 `Clock`으로 정확히 그 순간을 만든다(시계 값은 시험이 지정하고 실제 시계에 기대지 않는다). "통과"는 코드를 고치지 않은 현재 `origin/main`에서의 결과다.

| 번호 | 가설 | 반증 조건(falsifier) | 지표·기대값 | 이 결과가 정하는 결정 |
| --- | --- | --- | --- | --- |
| H1 | refresh는 token 만료와 family 만료(30일)에서 정확히 거절되고 새 token 만료는 family 만료를 넘지 않는다 | T0+30일 정각에 refresh 성공, 또는 새 token의 `expires_at` > family의 `expires_at`, 또는 7일 정각에 성공 | 경계 직전 성공 1, 정각 거절 1(HTTP 401·`INVALID_REFRESH_TOKEN`), `expires_at` 비교. 기대: 위 값 그대로 | 반증이면 `AccountServiceImpl.refresh`를 고치고 회귀 테스트로 남긴다 |
| H2 | 같은 refresh token의 동시 사용 2건은 정확히 하나만 성공하고 family 전체(최신 token 포함)가 폐기된다 | 성공 2건, 또는 성공 0건이면서 family 미폐기, 또는 성공한 쪽이 발급받은 token이 살아 있음 | 반복 N회 각각 성공 수 = 1, 실패 코드 `REFRESH_TOKEN_REUSED` = 1, 이후 둘 다 거절. 기대 N회 모두. 0건 위반이면 95% 상한은 3/N | 반증이면 `findRefreshSessionForUpdate`·`consumeRefreshSession` 경로를 고친다. N회 통과해도 경합의 증명이 아니라 `FOR UPDATE` 직렬화가 보장하는 결과 집합이 같음을 확인하는 것이다 |
| H3 | 재설정 token은 30분 정각에 거절되고, 거절된 시도는 비밀번호·세션을 바꾸지 않으며, 성공 시 모든 family와 미소비 MFA challenge가 폐기되고, token은 단회다 | 정각에 성공, 거절 뒤 로그인·refresh 불가, 성공 뒤 challenge로 토큰 발급, 재사용 성공 | 상태 코드와 DB 행(`consumed_at`, `revoked_at`). 기대: 정각 거절, 직전 성공, challenge 거절, 재사용 거절 | 반증이면 코드 수정 |
| H4 | 이메일 인증 token은 24시간 정각에 거절되고 인증 전 계정은 올바른 비밀번호로도 로그인할 수 없다 | 정각 성공 또는 미인증 로그인 성공 | HTTP 401과 DB 행. 기대: 거절 | 반증이면 코드 수정 |
| H5 | MFA challenge는 5분 정각에 거절되고, 같은 TOTP step 코드와 같은 recovery code는 두 번 쓰이지 않으며, 재설정은 미소비 challenge를 무효로 만든다 | 정각 성공, 재사용 성공 | 상태 코드. 기대: 위 그대로 | 반증이면 코드 수정 |
| H6 | 보호 라우트(비공개, `/api/internal` 제외)는 소유 검증 방식이 하나씩 대응되고, 각 자원 종류에서 타 계정 요청은 거절되며 자원은 변하지 않는다 | 분류되지 않은 보호 라우트가 있거나, 타 계정 요청이 2xx이거나, 자원이 변함 | 라우트 수와 분류 수(전수), 종류별 (소유자 2xx / 타 계정 403·404) 쌍. 기대: 분류 안 된 라우트 0, 모든 쌍 일치 | 반증이면 실제 IDOR 결함이므로 수정과 회귀 테스트 |
| H7 | 공개로 명시한 라우트를 뺀 모든 실제 라우트는 토큰 없이 401을 돌려준다(default deny) | 공개 목록 밖에서 401이 아닌 응답 | 전수 라우트 N개에서 401 아닌 수. 기대 0 | 반증이면 보안 설정 수정 |
| H8 | signup·verification·reset 요청은 계정이 있든 없든 status·body가 바이트 단위로 같고, 로그인 실패 경로의 없는 이메일과 있는 이메일(틀린 비밀번호)의 소요 시간 차이는 잡음 한계 안이다 | status/body가 다르거나, 로그인 실패 경로 중앙값 차이 > 3배 잡음 한계(대조군에서 정한 값) | 응답 동일성(바이트), 로그인 실패 경로 두 조건 n≥400 중앙값·분위수·Mann-Whitney 효과 크기와 잡음 한계. 기대: 동일, 차이 ≲ 잡음 한계 | 차이가 크면 Q-007 문구를 "측정한 크기로 비보장"으로 쓰고 더미 경로를 고친다. 작아도 constant-time 비노출은 주장하지 않는다(ADR-0085 유지), 측정값은 문서화 |
| H9 | `SmtpAccountEmailSender`는 실제 SMTP 서버(로컬 Mailpit)에 verification·reset 메일을 보내고 token 원문이 로그·응답에 남지 않는다 | 메일 미수신, token 불일치, 로그·응답에 원문 노출 | 수신 메시지 수와 token 일치. 기대: 2건 수신, 일치, 로그 0건 | 반증이면 코드 수정. 통과해도 실제 메일 제공자 도달성은 Live gate로 분리(ADR-0093) |

## 설계

### 조건

- 시험 계층: Spring Boot 전체 컨텍스트 + Testcontainers `postgres:18.6` + MockMvc(보안 필터 체인 포함) + 주입한 `Clock`. 기존 `AccountAuthenticationIntegrationTest`와 같은 속성(`atlas.admission.expiry-poll-interval`, `atlas.orders.expiry-poll-interval`을 24시간)으로 배경 스케줄러를 끈다.
- 새 통합 테스트는 코드를 고치지 않은 `origin/main` 위에서 먼저 돌려 각 가설의 현재 결과를 얻고(고치기 전 결과), 결함이 나오면 고친 뒤 같은 테스트로 다시 돌린다.

### 대조군

- 무변경 대조군: 기존 계정 테스트(`AccountAuthenticationIntegrationTest`, `AccountServiceTest`, `AccountJwtConfigurationTest`, `AccountOwnershipInterceptorTest`)가 새 테스트를 넣기 전·후 같은 수로 통과.
- 음성 대조군(검출기가 울려야 하는 상황): 새 테스트마다 변이(경계 비교 `isBefore`→`!isAfter`, `minimum()` 제거, `FOR UPDATE` 제거, 소유 검사 한 줄 제거, 라우트 분류 항목 제거)로 실패함을 확인한다. H8은 더미 hash 계산을 제거한 변이 빌드에서 차이가 커지는지 본다.
- 같은 환경 전후: 같은 호스트, 같은 컨테이너 이미지.

### 표본 크기

- 경계 시험은 결정적이라 표본이 아니라 시각 쌍(직전·정각)이다. 경합 시험(H2)은 N=30회, 0건 위반이면 95% 상한은 rule of three로 3/30 = 10%다(결과 집합이 `FOR UPDATE`로 결정되는 불변식이므로 N은 안전 확인이지 확률 추정이 아니다).
- H8 시간 비교는 조건당 n=400, 교차 순서로 측정하고 공유 호스트이므로 판정은 잡음 한계 대비 효과 크기로 하며 절대값은 맥락용이다.

### 교란 요인 기록

- 공유 Docker(Colima)와 CPU를 다른 작업이 쓴다: 시간 값은 맥락용, 결론은 개수와 불변식으로만.
- `AuthAttemptLimiter`(origin·email 단위 한도)가 로그인 횟수를 제한한다: 시험마다 다른 origin과 email을 쓴다.
- Spring `JwtTimestampValidator`는 실제 시계를 쓰고 서비스는 주입한 `Clock`을 쓴다: access token의 `exp` 단정은 서비스 시계로 만든 token과 실제 시계의 차이를 감안한다(`AccountJwtConfigurationTest`가 별도로 단정).

### 대안

| 대안 | 평가 |
| --- | --- |
| 아무것도 하지 않고 ◐ 유지 | 사용자 요구(세모 금지)와 충돌. 기각 |
| 모의 단위 테스트만 보강 | 경계·경합·소유는 SQL 조건(`expires_at > ?`, `FOR UPDATE`)이 실제로 지키므로 모의로는 증명 불가. 기각 |
| 라우트마다 소유 테스트 | 비공개 라우트 98개(컨트롤러 정적 집계, `/api/internal` 41개 제외) 전부를 라우트별로 쓰는 것은 과하다(과도한 규모). 라우트 전수 분류 + 자원 종류별 대표 + 전수 default deny로 같은 불변식을 작게 검증 |
| 전체를 하나의 큰 테스트 클래스에 | 컨테이너·컨텍스트 비용만 늘고 실패 원인이 섞인다. 약속 묶음별 3개 클래스(시간·세션, MFA·재설정, 소유·기본 거부) |

### 사전 사망 분석(pre-mortem)

- 새 테스트가 간헐 실패(타이밍·한도)하면 CI를 흔든다 → 대기는 조건(래치·`Future.get`)으로, 시계는 주입, origin·email은 시험마다 고유값.
- 소유 테스트가 라우트 변경에 취약하다 → 분류 목록은 라우트 수를 단정하지 않고 "분류되지 않은 라우트가 없음"을 단정한다.
- 컨텍스트 3개가 CI 시간을 늘린다 → 컨테이너 클래스당 1개, Spring 컨텍스트 캐시를 공유하려고 속성을 기존 테스트와 같게 맞춘다. 추가 시간은 결과 절에 적는다.
- 되돌리기: 테스트·문서만 추가(운영 코드는 결함이 나올 때만). 롤백은 브랜치 되돌리기와 `git revert`로 실제 연습한다.

## 결과

(측정 뒤에 채운다)
