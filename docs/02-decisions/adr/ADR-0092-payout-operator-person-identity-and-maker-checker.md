# ADR-0092: 지급 운영자를 사람별 account로 인증하고 프로필 검증자 ≠ 지급 승인자를 DB trigger로 강제한다

- 상태: 채택(제안 PR, 병합은 통합 담당). Sandbox 전용. Live 지급은 [ADR-0078](ADR-0078-payout-operator-controls-production-gate.md)의 나머지 gate를 닫은 뒤 별도 결정한다.
- 날짜: 2026-11-25
- 관련: [ADR-0078](ADR-0078-payout-operator-controls-production-gate.md), [ADR-0085](ADR-0085-v1-scope-channel-operator-and-payout-decisions.md), [ADR-0027](ADR-0027-account-jwt-authentication.md), [ADR-0057](ADR-0057-financial-operation-outbox-and-payout-reconciliation.md), [EXP-096](../../03-experiments/EXP-096-operator-identity-maker-checker.md)
- 추적: [Q-007, Q-013](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-162](../../00-governance/decision-log.md), [Issue #651](https://github.com/Neo1228/ATLAS/issues/651)

## 문제

운영자 인증이 설정값 하나(username, password)라서 감사 행의 actor가 사람을 구분하지 못하고, Seller 지급 프로필을 검증한 계정과 그 프로필로 만든 지급을 승인하는 계정이 같아도 막을 수 없었다(ADR-0078 사실 1). ADR-0078 결정 2와 ADR-0085는 "사람별 신원이 먼저, 그 위에 승인자 ≠ 검증자 검사"를 Live 전 조건(gate 7)으로 미뤘다. 이 ADR은 그 신원을 기존 account 모듈 위에서 가장 작게 만들고, 규칙을 경쟁 조건에서도 깨지지 않는 위치에 둔다.

## 결정

1. **운영자 = 기존 `user_account` + `payout_operator_account` 권한 행.** 새 인증 틀을 만들지 않는다. 인증은 기존 로그인(PBKDF2 `DelegatingPasswordEncoder`, `AuthAttemptLimiter`, 선택형 TOTP)으로 받은 access JWT를 쓰고, 운영자 chain(`@Order(1)`, `/api/internal/settlement/payouts/**`, `payout-profiles/**`)은 JWT를 검증한 뒤 `PayoutOperatorAuthenticationConverter`가 **요청마다** 권한 행(해제되지 않음), 계정 `ACTIVE`, 이메일 검증, **TOTP 등록 완료**를 DB에서 확인해 `ROLE_SETTLEMENT_PAYOUT_OPERATOR`를 준다. 해제와 정지가 토큰 수명(10분)을 기다리지 않는다. principal 이름이 account id이고 감사 actor가 된다.
2. **승인자 ≠ 검증자를 `settlement_payout_operator_action`의 BEFORE INSERT trigger로 강제한다(Flyway V5).** 감사 행이 저장되는 순간 DB가 검사한다. 읽는 값은 현재 profile 행의 `verified_by`가 아니라 **그 지급 outbox가 묶은 `profile_version`의 `PROFILE_VERIFIED` 감사 행**이다. 규칙 표:

   | 감사 행 action | 같으면 거절하는 이전 단계 |
   |---|---|
   | `PAYOUT_APPROVED`, `PAYOUT_APPROVAL_REPLAYED` | 묶인 버전의 `PROFILE_VERIFIED` actor |
   | `MANUAL_PAYOUT_SENT` | 같은 지급의 `MANUAL_PAYOUT_APPROVED` actor |
   | `MANUAL_PAYOUT_CONFIRMED` | 같은 지급의 `MANUAL_PAYOUT_SENT` actor |
   | (`seller_payout_profile_action`) `PROFILE_VERIFIED` | 그 프로필의 수취 account(운영자가 자기 계정의 프로필을 검증) |

   위반은 SQLSTATE 23514와 `..._separation_of_duties` 이름으로 올라오고 `GlobalExceptionHandler`가 **403 `SEPARATION_OF_DUTIES`**로 응답한다. 트랜잭션은 롤백돼 상태 전이·outbox·감사 행이 하나도 남지 않는다.
3. **동시 승인은 새 잠금 없이 기존 Seller 지급 잠금으로 직렬화된다**(`SettlementPayoutServiceImpl.approve`의 `lockSellerPayouts`). 다만 진 쪽이 `PAYOUT_APPROVED` 행을 한 번 더 남기는 기존 결함을 재현해(기준 커밋 100라운드 중 100라운드가 승인 행 2건) 고쳤다: 승인 직전 outbox가 이미 있으면 `PAYOUT_APPROVAL_REPLAYED`를 남긴다. 지급당 `PAYOUT_APPROVED`는 부분 unique 인덱스로 DB가 한 건으로 제한한다.
4. **actor 모양 규칙.** trigger는 actor가 UUID 모양(= account id)일 때만 규칙을 적용한다. rollback 모드의 단일 자격 증명 username은 UUID가 아니라 규칙이 적용되지 않는다. 이는 의도이고, "사람이 아닌 actor에는 이 규칙이 없다"는 한계다.
5. **rollback flag:** `atlas.settlement.payout.operator.identity=account`(기본) | `shared-credential`. 후자는 기존 HTTP Basic 단일 자격 증명 chain을 그대로 쓴다(WARN 로그). migration은 새 표·trigger·인덱스만 더하고 기존 행·열을 바꾸지 않으며, 되돌림 SQL이 `load-tests/operator-separation/undo.sql`에 있다.
6. **첫 운영자 부여(bootstrap)는 DB 권한자의 INSERT다**(`docs/operations-guide.md`). 앱에 부여 API를 두지 않는다. 표에 행이 없으면 운영자 경로는 모두 403이라 단일 공유 자격 증명이 영구 backdoor로 남지 않는다. 공유 자격 증명은 flag를 명시했을 때만 켜진다.

## 쌍별 4-eyes 판단 (상태를 바꾸는 운영자 조치를 모두 나열)

| 조치 | 쌍 | 4-eyes | 이유 |
|---|---|---|---|
| 프로필 검증 → 지급 승인(PayPal) | 검증자 ≠ 승인자 | **필요, 적용** | 수취 계정 확정과 돈이 나가는 결정이 한 사람에게 있으면 내부자 단독 사기가 가능 |
| 프로필 검증 ↔ 수취 account | 검증자 ≠ 수취 account | **필요, 적용** | 운영자 account도 Seller에 연결될 수 있는 일반 account라 자기 프로필을 검증할 수 있었다 |
| 수동 승인 → 수동 송금 기록 | 승인자 ≠ 기록자 | **필요, 적용** | 외부 송금을 승인한 사람이 "보냈다"고 단독 주장하면 사실 확인이 없다 |
| 수동 송금 기록 → 수동 확인 | 기록자 ≠ 확인자 | **필요, 적용** | 같은 주장을 같은 사람이 확인하는 것은 확인이 아니다 |
| 승인 취소(`cancel-before-dispatch`), 수동 실패(`manual-fail`) | 단독 | 불필요 | 돈이 나가는 것을 **막는** 쪽이라 단독 수행이 안전하다. 4-eyes를 걸면 사고 대응이 느려진다 |
| 재큐(`requeue`), provider 검토 해소(`resolve-provider-review`), 레거시 무이체 확인(`manual-no-transfer`) | 단독 | **적용하지 않음, 열린 위험** | provider 결과를 운영자가 판정하는 조치다. 같은 사람이 승인하고 재큐·판정까지 할 수 있다. 이 조치들은 증거 참조를 남기고 provider 사실과 대조되지만 4-eyes는 아니다. Live 전에 정책을 정해야 한다 |
| 계정 정지·해제, 결제 복구, PayPal 보고서·웹훅 운영자 | 단독 | 범위 밖 | 별도 chain(단일 자격 증명)이고 이번 이슈는 지급 경로만 다룬다 |

## 대안

| 안 | 얻는 것 | 잃는 것 |
|---|---|---|
| A0 하지 않음(ADR-0078 (a) 유지) | 변경 0 | gate 7 미결. 같은 사람 검증·승인을 막을 수 없음. 가장 강한 반론: V1은 Sandbox라 돈이 움직이지 않는다 |
| A1 IdP·gateway가 신원 제공(ADR-0078 (c-2)) | 신원·MFA·회전·잠금을 한 번에 | 인프라 도입·운영. 저장소에 없음. 이 설계와 배타적이지 않다: IdP가 생기면 converter만 바꾸고 trigger는 그대로 쓴다 |
| A2 설정에 Basic 사용자 N명 | 코드 작음 | 비밀번호가 환경 변수에 있고 회전·잠금·MFA 없음, 사용자 추가에 재배포 |
| A3 Basic 인증을 `user_account` 해시로 검증 | 해시 재사용 | 온라인 추측 한도·MFA 없음 |
| **A4 account JWT + 권한 표(선택)** | 해시·로그인 한도·TOTP·세션 폐기 재사용. 요청마다 해제 반영 | 운영자가 buyer 연결을 가진 일반 account여야 로그인됨(F8), 첫 부여는 DB 작업 |

강제 수단(EXP-096 E1, 각 시나리오 n=200): service 검사(S)와 조건부 SQL(C)은 **그 코드를 지나는 요청**만 막고, 규칙을 모르는 INSERT(x3)를 200/200 통과시킨다. trigger(T)만 x3에서 200/200 거절한다. "현재 profile 행"을 읽는 trigger(N)는 승인 도중 프로필이 다음 버전으로 넘어가는 경우(x2)를 200/200 놓친다. 그래서 trigger가 outbox가 묶은 버전의 감사 행을 읽는다.

## 다루지 않는 것 (정직한 한계)

- **MFA 강제 범위:** 운영자는 TOTP 등록 완료여야 하지만, 앱은 **그 토큰을 발급한 로그인이 TOTP를 거쳤는지** 검사하지 않는다(access JWT에 인증 수단 claim이 없다). 등록은 필수지만 로그인 시 사용은 account 로그인 규칙(등록되면 로그인이 challenge를 요구)에 의존한다. 행동별 재인증·step-up은 없다.
- **계정 잠금:** 기존 `AuthAttemptLimiter`의 시간 창 한도만 있다. 영구 잠금·알림은 없다.
- **자격 증명 회전·비밀번호 정책:** 기존 account 규칙(최소 12자)뿐이다. 운영자 전용 정책은 없다.
- **담합:** 검증자와 승인자가 공모하면 막지 못한다. 4-eyes의 본질적 한계다.
- **DB 권한자:** `payout_operator_account`에 INSERT하는 사람과 trigger를 끌 수 있는 사람은 DB 권한자다. 이 위협은 범위 밖이다.
- **사람 ≠ account:** principal 이름이 사람 한 명을 가리킨다는 것은 운영 절차(계정 공유 금지)에 기대고 앱이 검사할 수 없다. 한 사람이 두 account를 가지면 규칙을 우회한다.
- **소규모 팀의 가용성:** 운영자가 한 명이면 지급이 진행되지 않는다. 의도된 결과이고 운영자를 두 명 이상 부여해야 한다.
- 다른 운영자 chain(계정 정지, 결제 복구, PayPal 보고서·웹훅)은 그대로 단일 자격 증명이다.

## 결과

[EXP-096](../../03-experiments/EXP-096-operator-identity-maker-checker.md)에 가설별 판정이 있다. ADR-0078 gate 7은 "사람별 신원은 앱에서 충족, MFA·회전·잠금은 미충족"으로 갱신했고 나머지 gate는 그대로다.

## 되돌리기

1. flag: `ATLAS_SETTLEMENT_PAYOUT_OPERATOR_IDENTITY=shared-credential`로 재기동하면 기존 동작이다(통합 테스트로 확인).
2. migration: V5는 추가만 한다. 되돌림은 `undo.sql`(DROP INDEX, TRIGGER 2, FUNCTION 2, TABLE). 실행해 기존 행 해시가 V5 전·되돌림 후 같음을 확인했다(EXP-096).
3. 코드만 되돌리고 migration은 두면: 기준 커밋의 코드는 동시 승인에서 진 쪽이 `PAYOUT_APPROVED`를 한 번 더 쓰려다 부분 unique 인덱스에 걸려 오류가 난다(중복 행 대신 실패). 안전한 방향이지만 시끄러우므로 코드를 되돌릴 때는 `undo.sql`도 함께 실행한다. 기준 커밋의 Java 코드를 V5 스키마에서 실행해 보지는 않았다. SQL 수준에서 기존 actor 문자열의 감사 행 INSERT가 V5 이후에도 통과함만 확인했다.

## 배포 전 점검 (사전 부검에서 나온 항목)

V5의 부분 unique 인덱스는 지급 하나에 `PAYOUT_APPROVED` 행이 둘 이상 있으면 **생성에 실패**한다. 기준 커밋은 동시 승인에서 그런 행을 100라운드 중 100라운드 만들었다(EXP-096). Sandbox에서 동시 승인이 있었던 DB에는 이미 중복이 있을 수 있다. migration 전에 아래 결과가 0행인지 확인한다. 이 저장소의 baseline 주석은 아직 배포하지 않은 DB를 전제하지만, 배포된 DB가 없다는 것을 이 PR은 확인하지 못했다.

```sql
SELECT payout_id, count(*) FROM settlement_payout_operator_action
WHERE action = 'PAYOUT_APPROVED' GROUP BY payout_id HAVING count(*) > 1;
```
