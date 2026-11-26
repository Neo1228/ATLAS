# ADR-0094: 지급 운영자 세션의 2차 인증과 money-moving 해소 조치를 DB에서 분리한다

- 상태: 채택. Sandbox/V1 운영자 경계만 다룬다. Live 지급 개방은 ADR-0078의 모든 gate가 충족된 뒤 별도 승인한다.
- 날짜: 2026-11-26
- 관련: [ADR-0057](ADR-0057-financial-operation-outbox-and-payout-reconciliation.md), [ADR-0078](ADR-0078-payout-operator-controls-production-gate.md), [ADR-0085](ADR-0085-v1-scope-channel-operator-and-payout-decisions.md), [ADR-0092](ADR-0092-payout-operator-person-identity-and-maker-checker.md), [EXP-102](../../03-experiments/EXP-102-payout-sandbox-e2e-and-q013-closure.md)
- 추적: [Q-013](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-169](../../00-governance/decision-log.md), [Issue #671](https://github.com/Neo1228/ATLAS/issues/671)

## 문제

ADR-0092는 운영자를 account JWT로 식별하고 TOTP 등록을 요구하지만, MFA 등록 전에 비밀번호만으로 만든 access JWT도 등록 뒤 지급 경로에서 통과한다. 또한 DB trigger는 프로필 검증자와 지급 승인자를 분리하지만, 재큐·provider 결과 판정·수동 송금 없음 판정은 같은 사람이 할 수 있다. 일부 조치는 provider transfer를 시작하거나 payout reservation을 해제하므로, 서비스 코드만의 규칙으로는 직접 INSERT를 막을 수 없다.

## 측정한 사실

- 기준 코드와 같은 PostgreSQL 통합 시나리오에서 등록 전 password-only access session은 지급 운영자 경로를 20/20회 통과했다. TOTP enrollment 완료 뒤 password-only login은 MFA challenge를 반환하고 token을 만들지 않았지만, 이미 발급한 session은 살아 있었다.
- 기준 코드에서 같은 account가 payout을 승인한 뒤 provider review `NOT_PAID`를 판정한 경우 20/20회 성공했고, 그 결과 반환된 payable로 두 번째 payout을 20/20회 요청할 수 있었다.
- 기준 코드에서 `MANUAL_PAYOUT_SENT`를 기록한 사람이 같은 payout을 `MANUAL_NO_TRANSFER_CONFIRMED`로 끝내는 HTTP 경로는 200이었다.
- 기준 코드에서 같은 approver가 outbox를 `NEEDS_REVIEW`에서 재큐하는 경로는 200이었다. 이 측정은 사전등록 표에 없는 재큐 위험을 코드 검토 후 탐색적으로 추가한 것으로, EXP-102에 사전등록 이탈로 표시한다.
- `PAYOUT_REQUEUED`는 같은 stable provider ID와 payload를 쓴다. 그러나 최초 POST가 provider에 도달하지 않았다면 재큐가 첫 실제 transfer를 만들 수 있다. 따라서 중복 방지는 4-eyes 필요성을 대체하지 않는다.
- `AuthAttemptLimiterIntegrationTest`는 time-window limit을 넘은 올바른 비밀번호도 차단하는 기존 정책을 검증한다. 기본값은 LOGIN subject 15분 창 10회, origin 15분 창 60회다. 이는 영구 계정 잠금과 다른, 만료되는 rate limit이다.

## 결정

1. **MFA 사용을 현재 enrollment generation에 묶는다(V6).** `refresh_token_family.mfa_verified_at`을 추가한다. 비밀번호만으로 발급된 family는 NULL이고, TOTP 또는 일회용 recovery code로 MFA challenge를 완료한 family만 발급 시각을 기록한다. `PayoutOperatorAuthenticationConverter`는 JWT `sid` family와 현재 `account_mfa_secret.enabled_at`을 함께 읽고 `family.mfa_verified_at >= mfa.enabled_at`일 때만 운영자 권한을 준다. Refresh는 같은 family와 표식을 이어 간다. 이미 존재하던 session은 `NULL`이라 거절되며 새 MFA challenge가 필요하다.
2. **Family 생성 시각만으로 MFA를 추론하지 않는다.** MFA disable/re-enrollment는 새 `enabled_at`을 만든다. 이전 enrollment에서 MFA를 거친 session은 새 enrollment 시각보다 오래되어야 하고, 새 enrollment의 TOTP challenge를 마친 session만 통과한다(H4b, EXP-102).
3. **money-moving payout 조치의 4-eyes를 PostgreSQL trigger로 강제한다(V6).**

   | 조치 | 구분 | 4-eyes 규칙 | 이유 |
   |---|---|---|---|
   | `PAYOUT_APPROVED` | 송금 intent 생성 | 프로필 버전 검증자와 달라야 함(기존 V5) | recipient 검증과 송금 승인을 분리 |
   | `PAYOUT_REQUEUED` | provider가 최초 요청을 받지 않았다면 실제 transfer를 시작할 수 있음 | 같은 payout의 `PAYOUT_APPROVED` 또는 `PAYOUT_APPROVAL_REPLAYED` actor와 달라야 함 | ambiguous 상태를 한 사람이 재개해 돈을 보내지 못하게 함 |
   | `PROVIDER_REVIEW_PAID` | payout journal을 확정 | 같은 payout의 승인·승인 replay actor와 달라야 함 | provider 결과 판정과 지급 결정을 분리 |
   | `PROVIDER_REVIEW_NOT_PAID` | reservation을 풀어 같은 payable의 새 payout 요청을 가능하게 함 | 같은 payout의 승인·승인 replay actor와 달라야 함 | 잘못된 무이체 판정으로 중복 지급되지 않도록 함 |
   | `MANUAL_NO_TRANSFER_CONFIRMED` | SENT/NEEDS_REVIEW payout을 FAILED로 전환하고 reservation 해제 | 같은 payout의 `MANUAL_PAYOUT_SENT` actor와 달라야 함 | 송금 기록자가 단독으로 송금 부재를 선언해 이중 지급하지 않도록 함 |
   | `MANUAL_PAYOUT_APPROVED` → `MANUAL_PAYOUT_SENT` → `MANUAL_PAYOUT_CONFIRMED` | manual transfer | 기존 V5 trigger대로 인접 단계 actor를 분리 | 승인자가 송금 기록·확인을 혼자 완료하지 않도록 함 |
   | `PAYOUT_CANCELLED`, `MANUAL_PAYOUT_FAILED` | 송금 차단 | 단독 허용 | payout을 만들거나 payable을 풀어 다시 송금할 수 있게 하지 않음 |

   service 검사만 쓰는 대안은 EXP-096에서 규칙을 모르는 직접 INSERT 200/200을 허용했으므로 기각한다. Trigger는 action audit row가 저장되는 시점에 검사하고, 위반은 SQLSTATE `23514` / `SEPARATION_OF_DUTIES`로 전체 transaction을 rollback한다.
4. **영구 계정 잠금은 V1에서 추가하지 않는다.** 기존 LOGIN rate limit을 사용한다. 영구 잠금은 password guessing을 늦추는 대신 공격자가 운영자 계정을 계속 잠가 지급 운영을 막을 수 있다. 재검토 기준은 운영자 계정 하나에서 15분 내 10회 LOGIN subject limit에 도달한 사건이 운영에서 한 번이라도 관측되거나, 실제 credential attack incident가 확인되는 때다. 그때 alert·temporary lockout·복구 절차를 함께 검토한다.
5. **정기 password rotation은 추가하지 않는다.** 자격 증명 교체가 필요한 사건(credential compromise, 운영자 역할 철회, 법규·계약상 주기 요구)이 확인되면 즉시 회전 절차를 검토한다. 재검토 입력은 확인된 compromise incident 또는 보존 기간/회전 주기를 요구하는 문서화된 compliance 조건이다. 그때 acceptance criterion은 변경 전후 기존 refresh family 폐기와 새 credential login 성공을 통합 테스트로 증명하는 것이다.
6. **South Korea sender eligibility는 public sources만으로 확정하지 않는다.** 2026-11-26에 PayPal Developer `Countries and supported features` 페이지의 markdown endpoint를 다시 열었다. 표는 92개 국가 행을 싣고, feature-set 설명은 `Send, receive, and withdraw`를 “Merchants can send payouts from this country”라고 정의한다. South Korea 행은 없고, 페이지의 currency-conversion exclusion에 `South Korea`가 있다. 이 exclusion은 수취 국가의 자동 환전에 대한 문구이지 South Korea에 등록된 merchant가 송금할 수 없다는 문구가 아니다. PayPal에 질문할 내용은 “A business account registered in the Republic of Korea (South Korea) as the sender: can it initiate PayPal Payouts in Live, and if yes, which funding source and payout currencies are enabled?”이다. 서면 답변과 계정별 Live Payouts enablement 증거 전까지 V1은 Live payout 가능성을 주장하지 않는다.

## 대안과 trade-off

| 안 | 장점 | 기각 이유 또는 비용 |
|---|---|---|
| MFA enrollment 시각과 family created_at 비교 | schema 수정 없음 | enrollment/login 경합에서 password-only family가 enrollment 이후에 만들어질 수 있음 |
| access JWT에 `amr` claim만 넣기 | DB 열 없음 | 이미 발급한 access JWT와 refresh 경로의 인증 수단을 일관되게 무효화·연결하기 어렵고, 현재 권한 확인은 요청마다 DB를 읽음 |
| money-moving 분리를 service 검사로만 함 | 빠른 구현 | 직접 SQL/다른 저장 경로가 규칙을 우회함(EXP-096) |
| 재큐도 단독 허용 | 동일 ID 재사용으로 중복 방지 | 최초 POST가 미접수였다면 재큐가 새 transfer를 처음 시작할 수 있음 |
| 영구 잠금과 강제 주기 rotation | 정책이 간단해 보임 | 계정 잠금 DoS 및 규칙 근거 없음. 기존 throttle·비밀번호 재설정으로 V1 운영을 제한하고 명확한 incident/규제 입력 때 재검토 |
| Korea sender를 public 표 부재만으로 불가 판정 | 차단 정책이 간단 | 표의 부재는 명시적 거절이 아니므로 PayPal에 서면 확인 필요 |

## 검증, 한계, 되돌리기

- EXP-102에 baseline/after 수치, PostgreSQL 통합 테스트, 변이 결과, 실제 Sandbox E2E 및 public-source 판정을 집계한다. 실행하지 않은 검증은 완료로 적지 않는다.
- V6는 column 추가와 trigger function 교체만 한다. 기존 row는 갱신하지 않는다.
- 되돌림 순서: 먼저 애플리케이션 코드를 V6 이전으로 되돌리고 flag를 꺼서 운영자 경로를 닫는다. 그 다음 `refresh_token_family.mfa_verified_at`을 제거하고 V5 trigger function body를 복원한다. V6 column을 제거한 채 V6 코드를 실행하면 operator query가 실패하므로 schema downgrade 전에 code rollback이 필수다.
- Payout provider feature flag와 worker flag는 기본 `false`다. Sandbox E2E에서 사용한 실제 Sandbox credential은 repository/workflow에 저장하지 않는다.

## 참고

- [PayPal countries and supported features](https://developer.paypal.com/payouts/supported-features)
- [PayPal currency conversion](https://developer.paypal.com/payouts/currency-conversion)
- [OWASP Multifactor Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)
- [ADR-0092](ADR-0092-payout-operator-person-identity-and-maker-checker.md)
- [EXP-096](../../03-experiments/EXP-096-operator-identity-maker-checker.md)
