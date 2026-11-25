# ADR-0078: Seller 지급의 운영자 통제는 V1 Sandbox 한계로 두고 production 전환 gate를 체크리스트로 고정한다

- 상태: 채택(제안 PR, 병합은 통합 담당). Sandbox 전용. **갱신:** 운영자 사람별 신원과 승인자 ≠ 검증자 강제는 [ADR-0092](ADR-0092-payout-operator-person-identity-and-maker-checker.md)에서 구현했다. 아래 사실 1의 "없음" 행(사람별 신원, 운영자 간 maker-checker)은 기준 커밋 546d8002 시점의 사실이고 gate 7 행이 현재 상태다. Live 지급은 이 문서의 gate를 모두 닫은 뒤 별도 결정한다.
- 날짜: 2026-11-25
- 관련: [ADR-0057](ADR-0057-financial-operation-outbox-and-payout-reconciliation.md), [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md), [ADR-0027](ADR-0027-account-jwt-authentication.md), [EXP-073](../../03-experiments/EXP-073-paypal-sandbox-payouts-response-shapes.md)
- 추적: [Q-013](../../01-requirements/requirements-index.md), [D-142](../../00-governance/decision-log.md#d-142), [Issue #621](https://github.com/Neo1228/ATLAS/issues/621)

## 문제

Q-013은 PayPal Payouts의 상인 권한·funding source, 수취 국가·통화 적격성, USD 지갑·환율·provider fee, 프로필 검토 증거, 운영자 maker-checker·MFA, 지급 일정이 production 전에 미결이라고 적는다. 어떤 통제가 코드에 이미 있고 어떤 것이 없는지, 외부 전제가 무엇인지가 한곳에 정리돼 있지 않아 "정책으로 막았다"와 "코드로 막았다"를 구분할 수 없다. 이 ADR은 그 둘을 나누고, V1에서 코드를 바꿀 근거가 있는지 비교한다.

## 사실 1: 코드에 있는 통제와 없는 통제

기준 커밋은 `origin/main` 546d8002이다. 줄 번호는 이 커밋의 것이다.

| 통제 | 있나 | 근거 |
| --- | --- | --- |
| 지급 경로 기본 비활성 | 있음 | `application.yml:171`(`ATLAS_PAYPAL_PAYOUTS_ENABLED:false`), `:177` worker `false`, `:188` operator `false`. 컨트롤러 3개가 `@ConditionalOnProperty`로 켜진 경우에만 등록된다(`PayPalPayoutOperatorController:26-27`). |
| 운영자 경로 인증 | 있음 | `SettlementPayoutOperatorSecurityConfiguration:25-57`. `/api/internal/settlement/payouts/**`, `payout-profiles/**`만 `ROLE_SETTLEMENT_PAYOUT_OPERATOR` + HTTP Basic, 세션 없음. username이 비었거나 비밀번호가 32자 미만이면 기동 실패(`:38-41`). |
| Seller와 운영자의 분리(maker와 checker) | 있음 | 프로필은 Seller가 만들고(`SellerPayoutProfileService.configure:45-86`, `PROFILE_SUBMITTED`) 운영자가 검증한다(`verify:89-138`). 지급은 Seller가 요청하고(`SellerPayoutController:30,56`, `REQUESTED`) 운영자가 승인한다(`PayoutDispatchApprovalService.approve:51-94`). 두 쪽은 다른 URL 공간이고 운영자 chain의 `securityMatcher`는 internal 경로만 잡는다. Seller JWT chain 쪽은 이번 조사에서 읽지 않았다(ADR-0027 참조). |
| 프로필 검증 증거 | 있음(형식만) | `verify`는 `profileVersion` 일치, `PENDING_REVIEW` 상태, 증거 참조 `[A-Za-z0-9][A-Za-z0-9._:/-]{0,499}`, 연결 계정의 이메일 인증을 요구한다. DB는 `ACTIVE`이면 `verified_at`·`verified_by`가 있어야 한다(`V1__baseline.sql:1164`, `seller_payout_profile_verification_check`). 증거 참조는 검토 내용을 담지 않는 문자열 토큰이다. |
| 감사 행과 멱등성 | 있음 | `seller_payout_profile_action`(`V1:1172-1184`, unique `(seller_id, idempotency_key)`)과 `settlement_payout_operator_action`(`V1:1344-1353`, unique `(payout_id, idempotency_key)`)에 actor·reason·요청 해시가 남는다. 상태 전이와 outbox intent와 감사 행은 한 트랜잭션이다(`PayoutDispatchApprovalCommitService.approve:45-79`). action 값은 V2 CHECK로 닫혀 있다. |
| 감사 actor가 인증 principal에서 온다 | 있음, 이번에 테스트 추가 | `PayPalPayoutOperatorController:43-44`, `SettlementPayoutProfileOperatorController`. 요청 본문에는 `reason`·`evidenceReference`만 있고 actor는 없다. |
| 운영자 인증 경계의 테스트 | 없었음, 이번에 추가 | `git grep "settlement.payout.operator\|SETTLEMENT_PAYOUT_OPERATOR"`가 테스트 파일에서 0건이었다. 형제 chain(`PayPalReportOperatorSecurityTest`)에는 있다. |
| 사람별 운영자 신원 | 없음 | 운영자는 설정값 하나(username, password)이고 `InMemoryUserDetailsManager`에 사용자 1명만 만든다(`:42-45`). 감사 행의 actor는 모든 운영자에게 같은 문자열이다. |
| 운영자 간 maker-checker | 없음 | 승인(`PayoutDispatchApprovalService.approve`)은 프로필 검증자(`verified_by`)를 읽지 않는다. `ActiveRecipient`에 검증자 필드가 없다. 검증자와 승인자가 같은 사람이어도 막을 수단이 없고, 신원이 하나라 구분할 수도 없다. |
| MFA | 없음 | HTTP Basic 단일 비밀번호. |
| 지급 일정 | 정의 없음 | 지급은 Seller 요청으로만 생긴다. 지급을 만드는 `@Scheduled`는 없다(`@Scheduled`는 `PayoutDispatchWorker`의 outbox 폴링과 `PayoutRiskMetrics`뿐). |
| 건당 한도, provider 잔액 사전 확인 | 찾지 못함 | `atlas-payment/src/main`과 `service/payout`에서 `insufficient`, 건당 상한 관련 코드를 찾지 못했다(git grep). 읽지 않은 경로에 있을 가능성은 배제하지 못한다. |

정리하면 "Seller가 만들고 운영자가 승인한다"는 두 주체 분리는 이미 구조로 강제된다. 없는 것은 운영자끼리의 분리, 사람별 신원, MFA다. 셋은 같은 뿌리를 가진다. 운영자 신원이 하나뿐이다.

## 사실 2: PayPal 공식 문서에서 확인한 전제

2026-11-25에 아래 페이지를 열어 본문을 확인했다. 페이지는 클라이언트 렌더링 셸이라 서버가 내려준 HTML에서 텍스트를 추출해 읽었다.

| 주제 | 확인한 내용 | 출처 |
| --- | --- | --- |
| 상인 권한 | Live에서 Payouts를 쓰려면 검증된 PayPal Business 계정이 필요하다. 신원 확인, 이메일 확인, 은행 계좌 또는 카드 연결을 마치고 **PayPal 지원에 Payouts 활성화를 요청**해야 한다. | [Set up accounts and environment for payouts](https://developer.paypal.com/payouts/use-payouts/set-up) (Last updated: September 10, 2026) |
| Funding | 계정에 충분한 잔액이 있어야 한다. 자금 연결, 잔액 이체, auto-funding을 안내한다. 수수료는 **송금하는 통화**의 잔액에서 금액과 함께 차감된다. | 같은 페이지, [Fees and transaction limits](https://developer.paypal.com/payouts/fees) (Last updated: October 14, 2026) |
| Provider fee | 송금자가 낸다. 비율은 보내는 국가와 국내/국제 구분에 따라 다르고 상한이 있으며 일반적으로 변동 수수료 2%라고 적혀 있다. 통화 변환이 필요하면 추가 수수료가 붙는다. 계약별 정확한 요율은 이 페이지가 아니라 Merchant Fees 페이지를 가리킨다(열지 않았다). | Fees 페이지 |
| 건당 한도 | USD는 등록 수취인 60,000, 미등록 수취인 20,000(건당). 한 배치의 총액에는 상한이 없다. | Fees 페이지 |
| 수취 국가 | 지원 국가 표는 국가별 기능 집합(Send/receive/withdraw 등)을 적는다. 이 표에서 국가 코드가 붙은 행을 세면 92행이고, 같은 문서군의 개요는 96개국이라고 적는다. 차이는 원인을 확인하지 못했다. **이 표에 South Korea 행은 없다.** | [Countries and supported features](https://developer.paypal.com/payouts/supported-features) |
| 통화 변환 | South Korea는 통화 변환이 불가능한 국가 목록(Asia)에 있다. ADR-0057의 같은 주장을 현재 페이지로 다시 확인했다. | [Currency conversion](https://developer.paypal.com/payouts/currency-conversion) (Last updated: October 14, 2026) |
| API 사전 조건 | 사전 조건으로 sandbox 설정, Business 계정 설정, 계정 funding을 든다. 멱등성은 `PayPal-Request-Id` 헤더와 `sender_batch_id`로 처리한다. | [Use Payouts API](https://developer.paypal.com/docs/payouts/standard/integrate-api) (Last updated: September 10, 2026) |

확인하지 못한 것:

- ATLAS 운영 법인이 Korea에 있다면 그 국가의 Business 계정이 Payouts를 **보낼 수 있는지**. 지원 국가 표에 South Korea가 없다는 사실만 확인했고, 그것이 곧 "보낼 수 없다"인지는 PayPal에 확인하지 않았다. **not confirmed.**
- PayPal 고객센터의 Payouts 신청 안내([help250](https://www.paypal.com/us/cshelp/article/what-is-the-payouts-api-formerly-known-as-mass-pay-and-how-to-apply-for-it-help250))는 HTTP 403으로 열리지 않았다. **not confirmed.** 위 "지원에 활성화 요청"은 개발자 문서의 서술만 근거다.
- Merchant Fees의 계약 요율, 승인 심사 기준과 소요 시간.
- Sandbox에서 별도 활성화 없이 지급이 만들어진 것([EXP-073](../../03-experiments/EXP-073-paypal-sandbox-payouts-response-shapes.md): 토큰 발급 200, 생성 201)은 Sandbox 사실이다. Live 권한이 같은 방식으로 열린다는 증거가 아니다.

## 대안

| 안 | 내용 | 얻는 것 | 잃는 것 |
| --- | --- | --- | --- |
| (a) 현 상태를 V1 Sandbox 한계로 명시 + production gate | 코드는 바꾸지 않고 위 사실과 gate 체크리스트를 문서로 고정한다. 인증 경계가 깨지는 회귀만 테스트로 막는다. | 변경이 작고 거짓 안전감이 없다. Live 전에 무엇이 없는지 한곳에서 보인다. | 운영자 간 분리는 코드로 강제되지 않는다. Live 전 gate가 사람의 절차에 의존한다. |
| (b) 코드에 maker-checker 강제(승인자 ≠ 프로필 검증자) | `approve`에서 `verified_by`와 actor를 비교해 같으면 거절한다. | 같은 사람이 검증과 승인을 하는 경로를 막는다. | 신원이 하나라 **현재 설정에서는 모든 승인이 거절된다.** 쓰려면 운영자 설정을 사용자 목록과 역할로 바꿔야 한다(설정 형식, 역할 2개, 기동 검증, 문서 변경). DB 수준 보호는 `seller_payout_profile.verified_by`와 `settlement_payout_operator_action.actor`가 다른 테이블이라 CHECK로 못 쓰고 trigger가 필요해 싸지 않다. 그 위에서도 비밀번호 공유와 MFA 부재는 그대로다. 이 비용은 구현해서 잰 값이 아니라 코드를 읽은 추정이다. |
| (c-1) MFA를 ATLAS에 구현(TOTP 등) | Basic chain에 2차 인증을 붙인다. | 앱 하나로 완결. | 인증, 등록, 복구, 분실 절차를 앱이 떠안는다. 운영자 인증은 인프라 계층의 책임이고 앱은 결과(principal)만 받아야 한다. 인증 로직이 둘이 되면 SSOT가 깨진다. |
| (c-2) MFA와 사람별 신원은 IdP 또는 gateway가 맡는다 | 앱은 인증된 principal 이름을 감사 actor로 받는다(지금 구조와 같다). | 앱 코드가 늘지 않는다. 사람별 신원이 생기면 (b)가 싸진다. | IdP나 gateway를 도입하고 운영해야 한다. 이 저장소에는 아직 없다. |

## 결정

1. **(a)를 V1 범위로 채택한다.** 운영자 maker-checker와 MFA는 V1 Sandbox에서 **의도적으로 없는 통제**이고, 이 문서와 코드 옆 Javadoc이 그 사실을 적는다. 근거: V1은 feature flag가 꺼진 Sandbox이며 Live 자격 증명을 쓰지 않는다. 움직이는 돈이 없으므로 위협은 "Sandbox 오염"이고, 이를 막으려고 신원 모델을 바꾸는 비용이 위협보다 크다. 이 판단은 Live가 열리는 순간 무효가 된다. 그래서 gate를 문서에 적는다.
2. **(b)는 구현하지 않는다.** 단일 신원에서는 의미가 없거나(전부 거절) 신원 모델 변경이 선행돼야 한다. 사람별 신원이 생기면 승인 전에 `verified_by`와 비교하는 한 줄 검사와 PostgreSQL 통합 테스트로 구현한다(gate 7).
3. **MFA는 (c-2)로 정한다.** MFA와 사람별 신원은 앱이 아니라 IdP나 gateway 책임이다. 앱이 기대는 전제(인증된 principal 이름이 사람 한 명을 가리킨다)는 `SettlementPayoutOperatorSecurityConfiguration` 클래스 Javadoc에 적었다.
4. **코드 변경은 테스트 1개로 한정한다.** 사실상 지급 승인과 프로필 검증의 유일한 관문인 운영자 인증 경계에 테스트가 없었다. `PayoutOperatorSecurityTest`가 두 불변식을 지킨다. (1) 자격 증명이 없거나 틀리면 401이고 어떤 서비스도 호출되지 않는다. (2) 감사 actor는 요청 본문이 아니라 인증 principal이다. 운영 코드의 동작은 바꾸지 않았고, Javadoc 한 블록만 추가했다.

## Production gate 체크리스트

Live 지급을 열기 전에 아래가 모두 닫혀야 한다. 닫는 증거가 코드가 아닌 항목은 증거 위치를 정하고 이 표에 링크한다.

| # | gate | 닫는 증거 | 담당 | 상태 |
| --- | --- | --- | --- | --- |
| 1 | PayPal Live Business 계정 검증과 **Payouts 활성화** | PayPal의 활성화 회신 | treasury | 미결 |
| 2 | **보내는 국가 적격성.** 운영 법인 국가가 Payouts 발송 가능 국가인지. 지원 국가 표에 South Korea가 없었다 | PayPal 담당자 서면 확인 | compliance, treasury | **not confirmed** |
| 3 | 수취 국가·통화. `supported-countries` 기본값은 `US`로 둔다. 국가를 늘릴 때마다 지원 국가 표와 통화 변환 제외 목록을 대조한다. South Korea KRW 자동 환전은 주장하지 않는다. DB는 `payout_currency = 'USD'`만 허용한다(`V1:1162`) | 국가별 대조 기록 | product, compliance | 미결 |
| 4 | Funding: USD 잔액이 금액과 수수료를 함께 덮어야 한다. ATLAS에 잔액 사전 확인이 없다. 선충전 또는 auto-funding 절차, KRW에서 USD로 바꾸는 주체와 환율 책임을 정한다 | treasury 절차 문서, 기존 `PayPalBalanceReport` 대사 | treasury | 미결 |
| 5 | Provider fee: 계약 요율 확인과 `PLATFORM_PAYOUT_FEE` 처리의 회계 승인 | 요율 확인, 재무 승인 | finance | 미결 |
| 6 | 건당 한도: USD 60,000(등록), 20,000(미등록). 앱 측 상한을 둘지, 둔다면 값 | 결정 기록. 구현하면 통합 테스트 | product | 미결 |
| 7 | 운영자 **사람별 신원과 MFA**를 IdP나 gateway가 제공하고, principal 이름이 사람 한 명을 가리킨다. 공유 비밀번호 Basic은 Live에서 쓰지 않는다. 그 뒤에 승인자 ≠ 프로필 검증자 검사와 PostgreSQL 통합 테스트(변경 전 실패, 후 통과)를 넣는다 | IdP 구성, 통합 테스트 | security, backend | **부분 충족**([ADR-0092](ADR-0092-payout-operator-person-identity-and-maker-checker.md), [EXP-096](../../03-experiments/EXP-096-operator-identity-maker-checker.md)). 충족: 사람별 신원(앱 안 account, IdP 아님), 승인자 ≠ 검증자 DB 강제와 PostgreSQL 통합 테스트(기준 코드는 같은 자격 증명의 검증·승인이 100/100 성공, 변경 후 거절), 공유 Basic은 기본 꺼짐. **미충족:** 로그인 시 TOTP 사용 강제와 step-up(등록만 요구), 계정 잠금·자격 증명 회전 정책, 재큐·provider 검토 해소 등 나머지 운영 조치의 4-eyes, IdP·gateway. Live 전 결정 필요 |
| 8 | 프로필 검토 증거: 검토에 무엇을 봤는지(소유 증빙, 세무 등)를 정하고, 증거 저장 위치를 정한다. 지금 `evidenceReference`는 형식만 검사한다 | compliance 기준 문서 | compliance | 미결 |
| 9 | 지급 일정: 요청 주기, 마감 시각, 보류 기간. 지금은 Seller 요청으로만 생기고 일정이 없다 | 정책 결정 | product, finance | 미결 |
| 10 | 감사 보관 기간과 검토 주기(`seller_payout_profile_action`, `settlement_payout_operator_action`) | 정책 결정 | compliance | 미결 |
| 11 | Live 자격 증명과 Live host는 현재 Sandbox host 검증이 막는다(ADR-0057). 이 검증을 푸는 변경은 위 gate와 같은 PR에서 하지 않는다 | 별도 PR | backend | 미결 |

## 검증

실행한 것(개발 머신 1대, 단일 실행):

- 새 테스트 `PayoutOperatorSecurityTest` 2건: `:atlas-application:test --tests com.atlas.application.PayoutOperatorSecurityTest`, tests=2 failures=0.
- 변이 확인 2건. 변이 1: 운영자 chain의 `hasRole(...)`를 `permitAll()`로 바꾸면 `anonymousAndInvalidCredentialsCannotApprovePayoutOrVerifyProfile`이 실패했다. 변이 2: `PayPalPayoutOperatorController`의 actor를 고정 문자열로 바꾸면 `auditActorComesFromAuthenticatedPrincipalNotFromRequestBody`가 실패했다. 두 변이는 되돌렸고 `git status`가 새 테스트 파일 외에 변경이 없음을 보였다.
- 아래 "한계"에 적은 것은 확인하지 않았다.

## 한계와 되돌리기

- 이 ADR은 Live 준비 완료를 주장하지 않는다. 표의 `미결`과 `not confirmed`는 그대로 남는다.
- 사실 1의 "없음"은 이 커밋의 코드에서 grep과 읽기로 확인한 결과다. 읽지 않은 경로(Seller JWT chain 세부, 건당 한도, 잔액 확인)는 위에 적은 대로 제한적이다.
- PayPal 문서는 2026-11-25 시점이고 바뀔 수 있다. 페이지의 "Last updated" 날짜를 함께 적었다.
- 새 테스트는 Mock MVC 수준이라 실제 필터 chain 순서(`@Order(1)`)와 다른 chain과의 상호작용은 확인하지 않는다.
- 되돌리기: 테스트 파일 삭제와 Javadoc 블록 삭제. 운영 동작은 바뀌지 않았으므로 데이터나 설정 되돌리기는 없다. 지급 기능 자체의 rollback은 ADR-0057의 flag off를 따른다.

## 참고

- [Set up accounts and environment for payouts](https://developer.paypal.com/payouts/use-payouts/set-up)
- [Fees and transaction limits](https://developer.paypal.com/payouts/fees)
- [Countries and supported features](https://developer.paypal.com/payouts/supported-features)
- [Currency conversion](https://developer.paypal.com/payouts/currency-conversion)
- [Use Payouts API](https://developer.paypal.com/docs/payouts/standard/integrate-api)
- [OWASP Multifactor Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html): 관리자 등 높은 권한 사용자에게 MFA를 요구하고 MFA를 서비스로 쓰는 것을 고려하라고 권고한다. 위협 모델에 따라 다르다는 단서가 있다.
- [NIST SP 800-53 AC-5 Separation of Duties](https://csf.tools/reference/nist-sp-800-53/r5/ac/ac-5/): 한 사람이 여러 핵심 역할을 하는 것을 신중히 보라는 보충 설명이 있다. NIST 원문이 아니라 제3자 사이트의 사본을 열어 확인했다.
