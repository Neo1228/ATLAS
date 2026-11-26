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
| 라우트마다 소유 테스트 | 비공개 라우트 79개(컨트롤러 정적 집계, `/api/internal` 40개 제외) 전부를 라우트별로 쓰는 것은 과하다(과도한 규모). 라우트 전수 분류 + 자원 종류별 대표 + 전수 default deny로 같은 불변식을 작게 검증 |
| 전체를 하나의 큰 테스트 클래스에 | 컨테이너·컨텍스트 비용만 늘고 실패 원인이 섞인다. 약속 묶음별 3개 클래스(시간·세션, MFA·재설정, 소유·기본 거부) |

### 사전 사망 분석(pre-mortem)

- 새 테스트가 간헐 실패(타이밍·한도)하면 CI를 흔든다 → 대기는 조건(래치·`Future.get`)으로, 시계는 주입, origin·email은 시험마다 고유값.
- 소유 테스트가 라우트 변경에 취약하다 → 분류 목록은 라우트 수를 단정하지 않고 "분류되지 않은 라우트가 없음"을 단정한다.
- 컨텍스트 3개가 CI 시간을 늘린다 → 컨테이너 클래스당 1개, Spring 컨텍스트 캐시를 공유하려고 속성을 기존 테스트와 같게 맞춘다. 추가 시간은 결과 절에 적는다.
- 되돌리기: 테스트·문서만 추가(운영 코드는 결함이 나올 때만). 롤백은 브랜치 되돌리기와 `git revert`로 실제 연습한다.

## 결과

## 결과 (2026-11-26)

- 상태: **완료(로컬 PostgreSQL·MockMvc; production mail delivery/key rotation은 별도 terminal gate)**. 가설은 `7adf7635`에 측정 전 커밋했다. 사전 등록 뒤에 바뀐 판정 규칙은 없다.
- 실제 코드 결함: `AccountSuspensionOperatorController`는 operator feature flag가 꺼져도 등록됐고 기본 API chain은 일반 인증 JWT만 요구했다. 새 회귀 테스트가 고치기 전 `204 No Content`를 받았다(일반 Buyer C가 Buyer A를 정지할 수 있는 요청). `@ConditionalOnProperty(atlas.account.suspension.operator.enabled=true)`를 controller에도 걸었다. 고친 뒤 같은 요청은 `404`이고 대상 계정 상태는 `ACTIVE`; flag-on 경로는 기존 `AccountSuspensionOperatorIntegrationTest.suspensionCutsSessionsImmediatelyAndIsAudited`에서 Buyer JWT `401`, operator Basic 승인 흐름을 검증한다.

### 약속 → 실제 assertion 대응표

`P`는 측정 전 표의 약속 ID. `AccountCredentialLifecycleIntegrationTest`는 Testcontainers PostgreSQL + application service + MockMvc + injectable Clock, `AccountOwnershipIntegrationTest`는 실제 access JWT·MockMvc·PostgreSQL. 단위 test만으로 적힌 항목은 그 아래 `NONE → 새 증명`으로 보강했다.

| 약속·근거 | 증거 테스트 | 읽은 assertion / 판정 |
| --- | --- | --- |
| P01 FR-018·ADR-0027 D-084: email verification, 미검증 login 거부, token 24h·single-use | `AccountCredentialLifecycleIntegrationTest.emailVerificationTokenExpiresAtTwentyFourHoursAndLoginStaysBlockedUntilItIsConsumed`; `AccountServiceTest.rejectsExpiredEmailVerificationTokenWithoutChangingAccountState` | 미인증 올바른 password도 service·HTTP 401; 24h 정각 거절 및 `email_verified_at IS NULL`; 새 token은 만료 1초 전 consume; replay 401; DB에는 raw token 0, SHA-256 row 1 |
| P02 FR-018·ADR-0027: password login, unknown email dummy PBKDF2·generic failure | `AccountServiceTest.loginIssuesTokensForMatchingAccountCredentials`, `.unknownEmailUsesDummyHashBeforeGenericLoginFailure`, `.wrongPasswordUsesSameGenericCredentialError`; `BuyerFlowIntegrationTest.createBuyerAccount` | 정상 로그인은 access/refresh 발급; 없는 이메일과 틀린 password는 같은 INVALID_CREDENTIALS 코드, 가입 통합 경로는 실제 로그인 성공 |
| P03 FR-018·INV-015·ADR-0027: access lifetime 10m, JWT contract | `AccountJwtConfigurationTest.signedAccessTokenCarriesExpectedIdentityAndClaims`, `.decoderAcceptsOnlyTheConfiguredHmacAlgorithm`, `.accessTokenIsAcceptedUntilItsExpiryPlusTheDefaultSixtySecondClockSkew` | `exp-iat == 600`; HS512·unsigned reject, HS256 positive control; expiry +30s accepted, +90s rejected(기본 skew 60s); invalid signature/iss/aud/expired/missing claim 기존 tests도 유지 |
| P04 FR-018·INV-015·ADR-0027: opaque refresh 7d·family 30d, child 만료는 family를 넘지 않음 | `AccountCredentialLifecycleIntegrationTest.refreshTokenAndFamilyExpireExactlyAtTheirBoundaries` | 7일 직전 rotation 성공·정각 실패; family DB expiry == T0+30d; 24일째 및 30일 정각 직전 발급 child expiry == family expiry; 30일 정각 refresh와 아직 JWT-expiry 전인 access 모두 거부 |
| P05 FR-018·ADR-0027: rotation은 family 유지 | `AccountAuthenticationIntegrationTest.refreshRotationKeepsFamilyAndReplayRevokesAllFamilyAccessTokens`; `AccountCredentialLifecycleIntegrationTest.reusedRefreshTokenRevokesTheLatestTokenAndLeavesSiblingSessionsAlone` | rotation 후 `sid` 동일; 지난 token은 consumed, 새 token hash만 DB 저장 |
| P06 FR-018·INV-015·ADR-0027: reuse revokes family; concurrent pair | `AccountAuthenticationIntegrationTest.refreshRotationKeepsFamilyAndReplayRevokesAllFamilyAccessTokens`; `AccountCredentialLifecycleIntegrationTest.concurrentRefreshOfOneTokenSucceedsOnceAndRevokesTheWholeFamily` | reuse는 `REFRESH_TOKEN_REUSED`; 최신 refresh와 access 401, sibling family 정상. 30 rounds 각각 1 success·1 reuse, family revoked, winner token도 unusable |
| P07 FR-018·INV-015·ADR-0027 D-084: reset token 30m, hash·single-use | `AccountCredentialLifecycleIntegrationTest.passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession`; `AccountServiceTest.rejectsExpiredPasswordResetTokenWithoutChangingPasswordOrSessions` | 30m 정각 거절, old password·session 그대로; 새 token 1초 전 성공, replay 거절, DB raw token 0 |
| P08 FR-018·INV-015·ADR-0027 D-084: password reset revokes every family·open MFA challenge in same transaction | `.passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession`, `.passwordResetMakesAnOutstandingMfaChallengeUnusable`; `AccountAuthenticationIntegrationTest.sessionInventoryTargetedRevocationAndPasswordResetAreImmediateAcrossFamilies` | 모든 refresh family/access 401; challenge row consumed, reset 전에 받은 challenge로 access 발급 실패; 새 password로 새 challenge 성공 |
| P09 FR-018·ADR-0027 D-084: optional TOTP, no access before challenge; AES-GCM secret | `BuyerFlowIntegrationTest.totpEnrollmentRequiresASecondFactorBeforeIssuingAccessTokens`; `AccountCredentialLifecycleIntegrationTest.totpStepAndRecoveryCodeCanBeUsedOnlyOnce`; `AccountMfaSecretProtectorTest` | TOTP enroll 후 login에 `mfaRequired=true`·tokens null, challenge success token; DB ciphertext는 enrollment secret 원문과 다름 |
| P10 FR-018·INV-015·ADR-0027 D-084: challenge 5m | `AccountCredentialLifecycleIntegrationTest.mfaChallengeExpiresAtFiveMinutes` | 5m 정각 거절; 새 challenge는 정각 1초 전까지 유효 |
| P11 FR-018·INV-015·ADR-0027 D-084: ten hashed, single-use recovery codes | `.totpStepAndRecoveryCodeCanBeUsedOnlyOnce`; `AccountServiceTest.mfaEnrollmentCreatesSingleUseRecoveryCodes`, `.mfaRecoveryCodeCompletesChallengeAndIssuesTokens` | 10개 발급; DB hashed code only; code #1 succeeds once, replayed on new challenge fails; code #2 succeeds |
| P12 FR-018·BR-018·INV-015·ADR-0027: every Buyer/Seller protected operation validates ownership; body IDs are not proof | `AccountOwnershipIntegrationTest.anotherAccountIsRefusedOnEveryOwnershipRoute`, `.foreignIdentifiersInRequestBodiesAreRefusedWithoutSideEffects`, `.childResourcesOfAnotherSellerCannotBeReachedThroughOwnSellerPath`; `BuyerFlowIntegrationTest.buyerOrderHistoryPagesCampaignAndMultiSellerOrdersAndEnforcesOwnership`, `.percentEncodedRouteLiteralsStillRequireSellerOwnership`; `AccountOwnershipInterceptorTest` | runtime map의 69 ownership routes마다 C의 JWT로 A/B path ID 접근 시 403; buyer·seller·order·checkout·ticket·campaign 경로 포함. Body의 чуж buyerRef/sellerId와 nested B product/category/coupon/campaign child ID로는 mutation 0; seller order는 모든 item seller 검사; percent-encoded route도 403 |
| P13 BR-018·ADR-0027: unmapped protected route default deny | `AccountOwnershipIntegrationTest.everyMappedRouteIsPublicPrincipalScopedOrCoveredByAnOwnershipRule`, `.routesThatAreNotExplicitlyPublicRejectAnonymousRequests` | 96 non-internal `/api` mappings = 69 ownership + 10 principal-scoped + 17 explicit public; 미분류 0; 비공개 79개 전부 익명 요청 401 |
| P14 INV-015·ADR-0027: password/action token raw value not stored; token response/log boundary | `.emailVerificationTokenExpiresAtTwentyFourHoursAndLoginStaysBlockedUntilItIsConsumed`, `.passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession`, `.reusedRefreshTokenRevokesTheLatestTokenAndLeavesSiblingSessionsAlone` | verification/reset/refresh table에 clear token lookup 0, matching SHA-256 hash row 있음; error response는 generic code |
| P15 INV-015·ADR-0027: normalized email uniqueness, hash-only password | `AccountAuthenticationIntegrationTest.normalizedEmailConflictDoesNotCreateAnotherAccount`; `AccountServiceTest.signupNormalizesEmailHashesPasswordAndCreatesBuyerIdentity` | case-normalized duplicate account count 1; signup path stores encoded hash and creates one Buyer identity |
| P16 INV-015·ADR-0027 D-084: signup/verification/reset status·body uniform | `AccountCredentialLifecycleIntegrationTest.signupVerificationAndResetRequestsAnswerTheSameForKnownAndUnknownEmails`; `AccountServiceTest.signupReturnsTheSameAcceptedResponseForFreshAndExistingEmails`, `.passwordResetRequestDoesNotRevealUnknownAccounts` | known/unknown endpoint response is byte-for-byte equal; signup, reset, verification request tested through MockMvc |
| P17 INV-015·ADR-0085: constant-time is not promised | H8 timing probe (below) | current sample shows small distinguishable distribution; decision is explicitly **no constant-time claim**, not “equal time” |
| P18 ADR-0027 D-084: row-locked current password recheck prevents stale login | `AccountAuthenticationIntegrationTest.loginCredentialRowLockBlocksConcurrentPasswordReset`; `AccountServiceTest.rejectsPasswordThatChangedBeforeTheLoginCouldLockTheAccount` | PostgreSQL row lock blocks concurrent reset; stale password cannot issue new token |
| P19 ADR-0027 D-082: one account, one Buyer identity, multiple Seller profiles; no client-supplied owner evidence | `AccountServiceTest.signupNormalizesEmailHashesPasswordAndCreatesBuyerIdentity`; `AccountOwnershipIntegrationTest` fixture owns B/C sellers with distinct account JWTs; `AccountOwnershipInterceptorTest.rejectsBuyerReferenceThatDoesNotBelongToAuthenticatedAccount` | ownership queries tie account UUID to persisted Buyer/Seller link; attacker-supplied identifier is rejected |
| P20 ADR-0027: route-pattern classification prevents encoded-path bypass | `AccountOwnershipInterceptorTest.rejectsSellerResourceOwnedByAnotherAccountWithServletContextPath`, `.rejectsCampaignManagementForCampaignOwnedByAnotherAccountWithServletContextPath`; `BuyerFlowIntegrationTest.percentEncodedRouteLiteralsStillRequireSellerOwnership` | Spring matched route pattern used, context-path and percent-encoded literal still rejected |
| P21 ADR-0027: public callback exceptions are not a general auth bypass | `BuyerFlowIntegrationTest.anonymousPayPalCancellationRequiresMatchingPersistedState`; `LocationRoutedCheckoutIntegrationTest.paymentAndCancelHttpRoutesKeepTheReservationLocation`, `.passedReturnRestocksTheOriginalLocationAfterRoutingPriorityChanges`; `ApiSecurityConfigurationTest.explicitlyPublicCatalogRouteAllowsAnonymousRequests` | cancel requires matching persisted state and no bad-state mutation; valid return uses persisted payment/order facts; public catalog remains anonymous |
| P22 ADR-0027: core auth scope is first-party JWT + stateful refresh; social login/browser cookie are out of V1 | ADR-0093 DECIDED-OUT row | No provider or browser identity flow is promised. Browser decision: if a browser client is added, HttpOnly/Secure/SameSite + CSRF test is acceptance. External social login remains out of V1 |
| P23 ADR-0027 D-084: email send after commit; failed delivery does not disclose token and user can retry | `AccountServiceTest.failedAfterCommitVerificationEmailCanBeRetriedByUserRequest`; `BuyerFlowIntegrationTest.createBuyerAccount` (test sender captures token only after HTTP signup returns) | after-commit callback and retry are covered with a mock sender. Real external SMTP delivery was **not** tested; see H9 external block and Live gate |
| P24 ADR-0027 D-082: unauthenticated internal operator is not Buyer/Seller operation | `AccountOwnershipIntegrationTest.disabledAccountOperatorRouteCannotBeReachedWithABuyerToken`; `AccountSuspensionOperatorIntegrationTest.suspensionCutsSessionsImmediatelyAndIsAudited` | feature flag off: before fix Buyer JWT got 204; after fix controller is absent (404), target status remains ACTIVE. Flag on: Buyer JWT gets 401, configured operator succeeds |

### 가설 결과·대조군

| H | 관측 | 판정 |
| --- | --- | --- |
| H1 | `refreshTokenAndFamilyExpireExactlyAtTheirBoundaries`: 7d 정각/30d 정각 거절, child expiry ≤ family expiry | 지지; 테스트 assertion과 DB row로 확인 |
| H2 | 30 동시쌍에서 한 건만 성공, reuse loser, family revocation. Reuse-branch revoke mutation으로 simple-replay test와 concurrent test 모두 실패 | 지지; PostgreSQL `FOR UPDATE`로 serializable outcome |
| H3 | 30m 정각 reset 거절·password/session 유지, 성공은 모든 family와 MFA challenge 폐기 | 지지 |
| H4 | 24h 정각 verification 거절; verified 전 login 401 | 지지 |
| H5 | 5m 정각 challenge 거절; TOTP step·recovery code 각 단회, reset 후 challenge 폐기 | 지지 |
| H6 | runtime handler census: 96 = ownership 69 + principal 10 + public 17 (기본 설정). `atlas.payment.paypal.payouts.enabled=true`이면 `SellerPayoutProfileController`의 2개 route(GET, PUT, 모두 ownership)가 켜져 98 = 71 + 10 + 17이 된다. 정적 어노테이션 집계는 조건부 컨트롤러를 포함해 non-internal 98개, internal 40개, 합 138개이고 기본 설정의 런타임 매핑은 96개로 일치한다(2개 차이는 이 플래그가 설명한다); unknown 0. Cross-account 69 route 거절; body·nested child 사례도 거절, 후속 DB state 불변 | 지지. Internal `/api/internal/**` 41 mappings는 계정 ownership 대상이 아니라 전용 operator/internal-token chain |
| H7 | 공개 17 route는 401 아님, 나머지 79 route는 전부 익명 401 | 지지; `anyRequest().permitAll()` mutation에서 테스트 실패 |
| H8 | 현재 baseline U unknown n=400 median 126.125ms, K known-wrong n=400 136.077ms, KC same-path control n=400 136.687ms. Paired U−K median −8.876ms(0.927 ratio); K−KC control median −2.041ms, MAD 32.137ms, 3×MAD=96.411ms. Mann–Whitney normal approximation: U vs K p=0.000643, AUC=0.430. 차이는 통계적으로 구별되지만 중앙값 효과는 control 3×MAD보다 작다. | dummy hash를 제거한 음성 대조군 U 16.831ms vs K 84.381ms, U−K −64.011ms; K−KC control MAD=11.360ms, 3×MAD=34.081ms, p≈1.42e−125, AUC=0.013로 detector가 울림. **constant-time 보장 없음으로 결정**; status/body 동일만 계약. |
| H9 | `SmtpAccountEmailSenderTest`는 `JavaMailSender` mock으로 subject/body 검증, production transport는 실행되지 않음. SMTP 관련 env name 없음; `127.0.0.1:25` 연결은 `ConnectionRefusedError: [Errno 61] Connection refused` | **EXTERNAL-BLOCKED with proof**: live SMTP host·port·인증과 controlled inbox가 없음. V1은 post-commit delivery failure를 비밀 없이 기록하고 verification/reset 재요청 허용. Live acceptance: 실제 verification·reset 메일 수신 후 각각 action token 소비까지 확인 |

H8은 Node/JVM warmup 이후 한 번에 3 조건을 매 round 순서를 섞어 측정, `n=400`/조건, raw `/tmp/t1_timing_control_before.csv` 및 negative-control file은 커밋하지 않는다. 분석은 중앙값·분위수·paired median·control MAD·Mann–Whitney tie-corrected normal approximation·AUC이다. `uptime` 시 host load가 27.89/32.41/32.33으로 높았으므로 절대 지연은 맥락용이고 원인 판정은 대조군 대비 효과로만 한다. 알려진 경로와 없는 이메일이 똑같은 시간이라고 주장하지 않는다.

### 변이·롤백 검증

> 검증 범위 안내(2026-11-26): 아래 변이 18건과 H8 타이밍 측정은 이 PR 작업을 처음 수행한 에이전트가 실행한 결과를 그대로 옮긴 것이다. 작업을 이어받아 직접 다시 실행해 확인한 것은 다음뿐이다: (1) 신규·수정 테스트 28건(lifecycle 9, ownership 7, JWT 12) 통과, (2) 운영자 컨트롤러의 `@ConditionalOnProperty`를 제거하면 `disabledAccountOperatorRouteCannotBeReachedWithABuyerToken`를 포함해 3건이 실패하고 복원하면 통과(보안 결함의 회귀 시험이 실제로 결함을 잡음), (3) 라우트 분류 수 96·69·10·17과 비공개 79를 실제 handler mapping과 정적 어노테이션 집계로 다시 센 값. 이 과정에서 에이전트가 쓴 98·71·81이 기능 플래그(`atlas.payment.paypal.payouts.enabled=true`)를 켠 구성의 수였음을 확인해 기본 구성 수치로 고쳤다. 변이 18건과 H8의 n=400은 2026-11-26에 같은 스크립트와 같은 설계로 다시 실행했고 결과는 아래 `재실행 결과` 절에 있다(변이 17건이 대상 시험을 실패시켰고 1건은 본문이 적은 대로 통과, H8 결정 유지). 이 문단의 "재실행하지 않은" 서술은 그 이전 상태의 기록이다.

18개 신규 테스트 method 모두 자신의 주장을 깨는 targeted mutation에서 실패하고(재실행 결과: 변이 18건 중 17건이 대상 시험을 실패시켰고, 도달할 수 없는 `consumeRefreshSession` `false` 분기를 지우는 1건은 아래 서술대로 통과했다), 코드는 매 mutation 뒤 원본 bytes와 `cmp`로 같음을 확인해 복원했다. 7d/24h/30m/5m boundary, replay/reuse family revoke, reset·challenge revoke, TOTP step, recovery consume, enumeration response, JWT algorithm/timestamp, route classification/default-deny, buyer ownership/owner positive control, body seller ID, nested product scope가 해당 테스트를 깨뜨렸다. `consumeRefreshSession`의 `false` 분기에서만 revoke를 제거한 mutant는 실패하지 않았다: `findRefreshSessionForUpdate`가 `FOR UPDATE`로 먼저 직렬화하므로 해당 race는 consumed-token branch에 도달한다. 그 reachable branch의 revoke를 제거한 mutant에서는 concurrent test가 실패했다. Operator endpoint는 실제 rollback drill로 flag guard 제거 → Buyer JWT `204` 및 regression failure; guard 복구 → test pass·`cmp` identical. H8 negative control은 dummy PBKDF2 skip → effect가 noise threshold를 넘어 검출; 원본 복구 후 SHA-256 `9f01dbc8f78a79f299e397468b2801263f9d296143f5a5e861efbab2abdb683d`로 byte-identical 확인.

| 주장 | 증거 | 확신도 |
| --- | --- | --- |
| FR-018 local PostgreSQL에서 account token, expiry, reset, MFA, ownership 약속 충족 | 위 P01–P24 route/controller·DB assertions, 최종 `/tmp/afull.sh` 결과 (아래 보충). 2026-11-26 재실행: 신규·수정 28건과 `com.atlas.account.*`·JWT 시험 80개 통과, 변이 17/18 재현 | confirmed |
| 없는 email login 전체 시간은 constant-time | 주장하지 않음; H8 결과와 mutant detector | decided |
| General staff/admin role matrix | V1 범위 밖; Q-007에 이유·대안·revisit trigger·acceptance 기재 | decided |
| Live SMTP delivery | env 없음, localhost:25 connect refused, mock sender test만 있음 | external-blocked(with proof) |
| Production signing-key rotation | Single supplied HMAC key; V1 rotation promise 없음, rotate-before-production acceptance 별도 명시 | decided |

### 결정하지 않고 남긴 사항

없음. FR-018 구현은 검증 완료. H8은 constant-time 성질을 외부에 약속하지 않기로 결정했고, H9 실제 provider delivery는 증거와 필요한 입력이 특정된 external-blocked Live gate다. Browser storage, social login, generic staff role matrix, production key rotation은 이유·대안·측정 가능한 trigger/acceptance가 있는 V1 out-of-scope 결정이다.

## 재실행 결과 (2026-11-26, [Issue #707](https://github.com/Neo1228/ATLAS/issues/707))

위 "검증 범위 안내"가 `local-only(에이전트 보고)`라고 적은 변이 18건과 H8 타이밍을 같은 날 직접 다시 실행했다. 대상은 `origin/main` 기준 커밋된 시험이다. 변이 확인은 통과·실패만 보므로 호스트 부하와 무관하다(호스트 잠금 없이 `nice`로 한 번에 하나씩 돌렸다). Gradle은 `--no-build-cache cleanTest`로 돌려 캐시에서 재생된 결과를 실행으로 세지 않았다. H8은 시간 민감해서 호스트 잠금을 잡고 돌렸다.

### 변이 18건 다시 실행(원래 스크립트 그대로)

원래 에이전트가 쓴 변이 스크립트(`t1_mutations.py`, 18건의 파일·원문·대체문·대상 시험이 그대로 들어 있다)가 남아 있어 같은 문자열 치환을 그대로 적용했다. 변이마다 한 번에 하나, 대상 시험 한 개를 실행했고, 복원 뒤 파일 바이트가 원래와 같은지와 `git status`가 깨끗한지 확인했다.

| 번호 | 변이(원래 18건 중) | 대상 시험 | 실패 | `cmp` |
| --- | --- | --- | --- | --- |
| M01 | refresh 7일: `Duration.ofDays(7)`→`ofDays(8)` | `refreshTokenAndFamilyExpireExactlyAtTheirBoundaries` | 실패(시험이 잡음) | 일치 |
| M02 | 재사용 분기의 family 폐기 제거 | `reusedRefreshTokenRevokesTheLatestTokenAndLeavesSiblingSessionsAlone` | 실패(시험이 잡음) | 일치 |
| M03 | `consumeRefreshSession`이 `false`일 때의 family 폐기만 제거 | `concurrentRefreshOfOneTokenSucceedsOnceAndRevokesTheWholeFamily` | 통과(살아남음), 실패한 시험 0건 | 일치 |
| M04 | 이메일 인증 24시간: `ofHours(24)`→`ofHours(25)` | `emailVerificationTokenExpiresAtTwentyFourHoursAndLoginStaysBlockedUntilItIsConsumed` | 실패(시험이 잡음) | 일치 |
| M05 | 재설정 30분: `ofMinutes(30)`→`ofMinutes(31)` | `passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession` | 실패(시험이 잡음) | 일치 |
| M06 | 재설정이 MFA challenge 폐기를 생략 | `passwordResetMakesAnOutstandingMfaChallengeUnusable` | 실패(시험이 잡음) | 일치 |
| M07 | MFA challenge 5분: `ofMinutes(5)`→`ofMinutes(6)` | `mfaChallengeExpiresAtFiveMinutes` | 실패(시험이 잡음) | 일치 |
| M08 | TOTP step 단회 소비 조건(`last_accepted_step < ?`) 제거 | `totpStepAndRecoveryCodeCanBeUsedOnlyOnce` | 실패(시험이 잡음) | 일치 |
| M09 | recovery code 단회 소비 조건(`consumed_at IS NULL`) 제거 | `totpStepAndRecoveryCodeCanBeUsedOnlyOnce` | 실패(시험이 잡음) | 일치 |
| M10 | 가입 열거 응답: 기존 계정이면 `Action(false)` | `signupVerificationAndResetRequestsAnswerTheSameForKnownAndUnknownEmails` | 실패(시험이 잡음) | 일치 |
| M11 | JWT 알고리즘 허용 목록 `HS256`→`HS512` | `decoderAcceptsOnlyTheConfiguredHmacAlgorithm` | 실패(시험이 잡음) | 일치 |
| M12 | JWT 만료·발급 시각 검증 제거(issuer 검증을 항상 성공으로) | `accessTokenIsAcceptedUntilItsExpiryPlusTheDefaultSixtySecondClockSkew` | 실패(시험이 잡음) | 일치 |
| M13 | 라우트 분류 자체 시험: 분류 조건에 `false &&` 추가 | `everyMappedRouteIsPublicPrincipalScopedOrCoveredByAnOwnershipRule` | 실패(시험이 잡음) | 일치 |
| M14 | 기본 거부: `anyRequest().authenticated()`→`permitAll()` | `routesThatAreNotExplicitlyPublicRejectAnonymousRequests` | 실패(시험이 잡음) | 일치 |
| M15 | Buyer 소유 검사 생략 | `anotherAccountIsRefusedOnEveryOwnershipRoute` | 실패(시험이 잡음) | 일치 |
| M16 | Buyer 소유 양성 대조군: 소유 여부와 무관하게 거절 | `theOwnerPassesTheOwnershipRulesOnReadRoutesAndTheWrongRoleDoesNot` | 실패(시험이 잡음) | 일치 |
| M17 | 요청 본문 sellerId 소유 검사 제거 | `foreignIdentifiersInRequestBodiesAreRefusedWithoutSideEffects` | 실패(시험이 잡음) | 일치 |
| M18 | 중첩 상품 범위: `findByProductIdAndSellerIdForUpdate`→`findById` | `childResourcesOfAnotherSellerCannotBeReachedThroughOwnSellerPath` | 실패(시험이 잡음) | 일치 |

결과: 18건 중 **17건은 대상 시험이 실패**했다(재현). 나머지 1건(M03, `consumeRefreshSession`이 `false`일 때의 family 폐기만 제거)은 시험이 통과했다. 이는 문서 본문이 이미 적은 대로다: 그 분기는 `findRefreshSessionForUpdate`의 `FOR UPDATE` 직렬화 때문에 도달하지 않고, 도달 가능한 분기(재사용 분기, M02)를 제거한 변이에서는 시험이 실패한다. 본문 문장 "18개 신규 테스트 method 모두 자신의 주장을 깨는 targeted mutation에서 실패"는 "18건 중 17건이 실패하고, 도달 불가능한 분기를 지우는 1건은 같은 의미라 통과한다"로 읽어야 한다(본문 그 문장을 이 값으로 고쳤다). 모든 변이에서 복원 뒤 `cmp`와 `git status`가 깨끗했다.

### 경계 변이의 이중 방어(동등 변이)와 이를 겨냥한 변이

문서의 24시간·30분 경계 변이(M04, M05)는 `Duration` 상수를 바꾸는 변이였고 시험이 잡았다. 그런데 같은 경계를 비교 연산자만 바꿔 어기려 하면 Java 비교(`!now.isBefore(token.expiresAt())`)와 SQL 조건(`consumed_at IS NULL AND expires_at > ?`)이 서로를 막는다. 한 군데만 바꾼 변이는 시험이 통과하고(동등 변이), 둘을 함께 바꾸면 시험이 실패한다.

| 번호 | 변이 | 실패한 시험 수 | 실패한 시험 | `cmp` |
| --- | --- | --- | --- | --- |
| M02 | refresh: `familyExpiresAt` 비교만 `!isBefore`→`isAfter`(`expiresAt` 비교는 그대로) | 0 | 없음(시험 통과) | 일치 |
| M05 | 이메일 인증 24시간: Java 비교만 `!isBefore`→`isAfter`(SQL 조건 `expires_at > ?`는 그대로) | 0 | 없음(시험 통과) | 일치 |
| M06 | 재설정 30분: Java 비교만 `!isBefore`→`isAfter`(SQL 조건은 그대로) | 0 | 없음(시험 통과) | 일치 |
| M04 | `consumeRefreshSession`이 `false`일 때의 family 폐기만 제거(문서가 실패하지 않는다고 적은 변이) | 0 | 없음(시험 통과) | 일치 |
| M02b | refresh: `expiresAt`·`familyExpiresAt` 비교를 둘 다 `isAfter`로 | 1 | `refreshTokenAndFamilyExpireExactlyAtTheirBoundaries` | 일치 |
| M05b | 이메일 인증 24시간: Java 비교(`!isBefore`→`isAfter`)와 SQL 조건(`expires_at > ?`→`>=`)을 둘 다 포함 비교로 | 1 | `emailVerificationTokenExpiresAtTwentyFourHoursAndLoginStaysBlockedUntilItIsConsumed` | 일치 |
| M06b | 재설정 30분: Java 비교와 SQL 조건을 둘 다 포함 비교로 | 1 | `passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession` | 일치 |

해석: 문서가 말한 "경계에서 정확히 거절"은 두 방어 중 어느 하나만 남아도 지켜지는 불변식이며, 시험은 둘 다 무너졌을 때만 잡는다. 시험이 헐겁다는 뜻이 아니라 방어가 둘이라는 뜻이다. refresh의 7일 경계는 SQL 조건이 없어 Java 비교 하나만 바꿔도 잡힌다(M01).

### 새로 만든 추가 변이(소유·MFA·JWT·재설정)

원래 18건에 없던 변이를 더해 시험이 약속을 지키는지 한 번 더 확인했다(한 번에 하나, 해당 시험 클래스, 복원 뒤 `cmp`).

| 번호 | 변이 | 실패한 시험 수 | 실패한 시험 | `cmp` |
| --- | --- | --- | --- | --- |
| M01 | refresh 7일 경계: `!isBefore(expiresAt)`→`isAfter`(재설정·인증과 달리 SQL 조건이 없어 잡혀야 한다) | 1 | `refreshTokenAndFamilyExpireExactlyAtTheirBoundaries` | 일치 |
| M03 | 재사용 분기의 family 폐기 제거 | 2 | `concurrentRefreshOfOneTokenSucceedsOnceAndRevokesTheWholeFamily`, `reusedRefreshTokenRevokesTheLatestTokenAndLeavesSiblingSessionsAlone` | 일치 |
| M07 | 재설정이 `revokeAllRefreshFamilies` 생략 | 1 | `passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession` | 일치 |
| M08 | 재설정이 `revokeMfaChallenges` 생략 | 1 | `passwordResetMakesAnOutstandingMfaChallengeUnusable` | 일치 |
| M09 | MFA challenge 5분: SQL 조건 `expires_at > ?`→`>=`(세 곳) | 1 | `mfaChallengeExpiresAtFiveMinutes` | 일치 |
| M10 | TOTP step 단회 소비: `last_accepted_step < ?`→`<=` | 1 | `totpStepAndRecoveryCodeCanBeUsedOnlyOnce` | 일치 |
| M11 | recovery code 단회 소비: `consumed_at IS NULL` 제거 | 1 | `totpStepAndRecoveryCodeCanBeUsedOnlyOnce` | 일치 |
| M12 | 가입 열거: 재설정 요청이 없는 이메일에서 예외 | 1 | `signupVerificationAndResetRequestsAnswerTheSameForKnownAndUnknownEmails` | 일치 |
| M13 | 운영자 정지 컨트롤러의 `@ConditionalOnProperty` 제거(PR #677 결함의 회귀) | 1 | `disabledAccountOperatorRouteCannotBeReachedWithABuyerToken` | 일치 |
| M14 | 기본 거부 `anyRequest().authenticated()`→`permitAll()` | 1 | `routesThatAreNotExplicitlyPublicRejectAnonymousRequests` | 일치 |
| M15 | 인터셉터가 `buyerRef` 소유 검사를 생략 | 1 | `anotherAccountIsRefusedOnEveryOwnershipRoute` | 일치 |
| M16 | 인터셉터가 주문의 seller 범위 검사를 생략 | 2 | `anotherAccountIsRefusedOnEveryOwnershipRoute`, `theOwnerPassesTheOwnershipRulesOnReadRoutesAndTheWrongRoleDoesNot` | 일치 |
| M17 | `ownsBuyer`가 항상 거짓(소유자 양성 대조군) | 7 | `anotherAccountIsRefusedOnEveryOwnershipRoute`, `childResourcesOfAnotherSellerCannotBeReachedThroughOwnSellerPath`, `disabledAccountOperatorRouteCannotBeReachedWithABuyerToken`, `everyMappedRouteIsPublicPrincipalScopedOrCoveredByAnOwnershipRule`, `foreignIdentifiersInRequestBodiesAreRefusedWithoutSideEffects`, `routesThatAreNotExplicitlyPublicRejectAnonymousRequests`, `theOwnerPassesTheOwnershipRulesOnReadRoutesAndTheWrongRoleDoesNot` | 일치 |
| M18 | `CampaignController`가 본문 `sellerId` 소유 검사 생략 | 1 | `foreignIdentifiersInRequestBodiesAreRefusedWithoutSideEffects` | 일치 |
| M19 | 중첩 상품 범위: `findByProductIdAndSellerIdForUpdate`→`findById` | 1 | `childResourcesOfAnotherSellerCannotBeReachedThroughOwnSellerPath` | 일치 |
| M20 | 분류 안 된 새 라우트를 `CampaignController`에 추가 | 2 | `everyMappedRouteIsPublicPrincipalScopedOrCoveredByAnOwnershipRule`, `routesThatAreNotExplicitlyPublicRejectAnonymousRequests` | 일치 |
| M21 | JWT 디코더 허용 알고리즘 HS256→HS512 | 4 | `accessTokenIsAcceptedUntilItsExpiryPlusTheDefaultSixtySecondClockSkew`, `decoderAcceptsOnlyTheConfiguredHmacAlgorithm`, `decoderRejectsMalformedFamilyAndFailsClosedWhenFamilyLookupIsUnavailable`, `signedAccessTokenCarriesExpectedIdentityAndClaims` | 일치 |
| M22 | JWT 디코더에서 만료·발급 시각 검증 제거(issuer만) | 1 | `accessTokenIsAcceptedUntilItsExpiryPlusTheDefaultSixtySecondClockSkew` | 일치 |

모든 추가 변이를 해당 클래스가 잡았다(재현). 소유자 양성 대조군(M17)은 클래스의 시험 7건이 모두 실패해 조회 경로가 함께 무너짐을 보인다.

### H8 로그인 실패 경로 타이밍(n=400/조건, 같은 짝짓기 설계)

같은 탐침(`LoginTimingProbe`: 알려진 계정 40개, 워밍업 50회, 조건 U 없는 이메일, K 알려진 이메일의 틀린 비밀번호, KC 같은 K 대조, 매 라운드 조건 순서를 섞음)을 `AccountService.login`에 직접 호출해 조건당 400회 측정했다. 호스트 잠금을 잡았고 시작 때 `load1` 6.08(≤12), 끝날 때 4.37이었다. 다른 라벨 스택은 `pool110` 1개가 떠 있었으나 활동은 확인하지 못했다.

| 구성 | U 중앙값 | K 중앙값 | KC 중앙값 | 짝 U−K 중앙값 | U/K | K−KC 중앙값 | MAD | 3×MAD | Mann–Whitney p | AUC |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 이번 재실행, 현재 코드 | 37.486ms | 38.087ms | 38.093ms | −0.670ms | 0.984 | 0.023ms | 0.773ms | 2.318ms | 1.28e−13 | 0.349 |
| 문서 원본(호스트 `load1` 27.89~32.41) | 126.125ms | 136.077ms | 136.687ms | −8.876ms | 0.927 | −2.041ms | 32.137ms | 96.411ms | 0.000643 | 0.430 |
| 이번 재실행, 더미 hash 계산을 건너뛰는 변이 | 2.909ms | 37.976ms | 37.894ms | −35.114ms | 0.077 | −0.001ms | 0.820ms | 2.459ms | 2.44e−132 | 0.000 |
| 문서 원본, 같은 변이 | 16.831ms | 84.381ms | 84.954ms | −64.011ms | 0.199 | −0.820ms | 11.360ms | 34.081ms | 1.42e−125 | 0.013 |

원본 CSV(`/tmp/t1_timing_control_before.csv`, 변이 쪽도)가 이 호스트에 남아 있어 같은 분석을 다시 돌렸고 문서 원본의 값(126.125, 136.077, 136.687, −8.876, 0.927, 32.137, 96.411, 0.000643, 0.430과 변이 쪽 값)이 소수점까지 그대로 나왔다(분석 계산 재현 ✓).

판정: 절대 지연은 호스트 부하에 따라 3배 이상 달랐다(이번 37ms 대 원본 126ms). 중앙값 차이는 이번 재실행에서 −0.670ms(U가 K보다 1.6% 빠름)로 원본(−8.876ms, 7.3%)보다 작지만 방향은 같다. 사전 등록 규칙의 기준은 "효과가 대조군 3×MAD보다 작은가"이고 이번에도 |−0.670|ms < 2.318ms로 충족한다(원본도 충족). 통계적으로는 두 조건이 구별된다(p=1.28e−13, AUC 0.349). 더미 hash 계산을 건너뛰면 차이가 3×MAD를 크게 넘어 탐지기가 울린다(−35.114ms ≫ 2.459ms). 따라서 "constant-time은 주장하지 않는다(ADR-0085 유지), status/body 동일만 계약"이라는 결정은 이번 재실행에서도 그대로 유지된다. 재현하지 못한 것: 원본의 절대값(126ms)이다. 원본이 부하가 높은 호스트에서 잰 값이었음을 문서가 이미 밝혔고, 이번에는 같은 코드가 3분의 1 시간에 돈다.

### 신규·수정 테스트 28건과 `com.atlas.account.*` 전체

| 범위 | 클래스(시험 수) | 결과 |
| --- | --- | --- |
| 신규·수정 28건 | `AccountCredentialLifecycleIntegrationTest`(9), `AccountOwnershipIntegrationTest`(7), `AccountJwtConfigurationTest`(12) | 28/28 통과, 실패 0, skipped 0 |
| `atlas-application`의 `com.atlas.account.*`와 JWT 시험 | 위 3개 + `AccountAuthenticationIntegrationTest`(12), `AuthAttemptLimiterIntegrationTest`(8), `AccountSuspensionOperatorIntegrationTest`(1), `LoginConnectionPoolIntegrationTest`(2) | 51/51 통과 |
| `atlas-account` 모듈 | `AccountServiceTest`(23), `AccountSecurityConfigurationTest`(1), `AccountMfaSecretProtectorTest`(3), `SmtpAccountEmailSenderTest`(2) | 29/29 통과 |

합계 80/80 통과, 실패 0, skipped 0(`--no-build-cache cleanTest`, 캐시 재생 0건).

### 그 밖의 주장

| 주장 | 다시 실행한 것 | 판정 |
| --- | --- | --- |
| H9: `127.0.0.1:25` 연결이 `Connection refused`이고 SMTP 환경 이름이 없다 | 소켓 연결 | `127.0.0.1:25`와 `localhost:25` 모두 `ConnectionRefusedError [Errno 61]`, 이 프로세스 환경에 SMTP 이름 없음. 재현 ✓ (live SMTP는 여전히 external-blocked) |
| H6: 정적 어노테이션 집계 non-internal 98, internal 40, 합 138 | 컨트롤러 소스의 `@Get/Post/Put/Delete/PatchMapping` 개수를 클래스 `@RequestMapping` 접두로 센 스크립트 | 98 / 40 / 138로 같다. 런타임 96 = 69 + 10 + 17은 `AccountOwnershipIntegrationTest`의 단정(통과)이 지킨다. 재현 ✓ |
| `/tmp/afull.sh` 전체 `clean check` 결과(주장 표 "confirmed") | 다시 실행하지 않았다 | 재현 불가가 아니라 이번 PR이 운영 코드를 바꾸지 않아 전체 빌드를 돌리지 않았다. 대신 위 80개와 추가 변이를 돌렸다. 확신도는 바꾸지 않는다 |
