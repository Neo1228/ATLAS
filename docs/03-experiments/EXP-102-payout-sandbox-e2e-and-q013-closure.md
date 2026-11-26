# EXP-102: 지급 흐름은 실제 PayPal Sandbox에서 앱 전체로 끝까지 도는가, Q-013의 남은 통제는 어디까지 코드로 닫는가

- 질문: (a) 정산 원장 → Seller 프로필 → 운영자 검증·승인 → 워커 전송 → provider 항목 상태 → journal까지의 지급 경로 전체가 **앱, PostgreSQL, 실제 PayPal Sandbox**로 성립하는가? (b) Q-013에 남은 운영자 통제(로그인 시 TOTP 강제, 잠금·회전, 재큐·provider 검토 해소의 4-eyes)를 어느 것은 구현하고 어느 것은 근거를 들어 결정으로 끝내는가? (c) 보내는 국가(운영 법인 소재국) 적격성은 공개 자료로 확정되는가?
- 상태: **완료.** 가설, 반증 조건, 결정 규칙은 실행 전에 별도 커밋(마크다운만)으로 고정했고 결과는 그 뒤에 채웠다. 가설 표와 결정 규칙은 고치지 않았다. 앱 전체 지급 E2E는 실제 Sandbox에서 15/15가 기대 종결 상태였다(유형 1). 운영자 MFA 세션과 money-moving 4-eyes는 PostgreSQL 통합 테스트로 구현했다(유형 1). 영구 잠금과 정기 회전은 DECIDED-OUT(유형 2), South Korea 발송 적격성은 EXTERNAL-BLOCKED(유형 3).
- 날짜: 2026-11-26
- 관련: [Issue #671](https://github.com/Neo1228/ATLAS/issues/671), [ADR-0057](../02-decisions/adr/ADR-0057-financial-operation-outbox-and-payout-reconciliation.md), [ADR-0078](../02-decisions/adr/ADR-0078-payout-operator-controls-production-gate.md), [ADR-0085](../02-decisions/adr/ADR-0085-v1-scope-channel-operator-and-payout-decisions.md), [ADR-0092](../02-decisions/adr/ADR-0092-payout-operator-person-identity-and-maker-checker.md), [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md), [EXP-077](EXP-077-payout-dispatch-provider-integration.md), [EXP-096](EXP-096-operator-identity-maker-checker.md)
- 기준 커밋: `origin/main` e0a2660a

## 문제 (한 문장)

FR-019, BR-019, Q-013이 `◐`, `△`로 끝나 있다. 이유는 셋이다. 지급 경로를 앱 전체로 실제 Sandbox에 붙여 본 적이 없고(EXP-073은 앱을 거치지 않고 API를 직접 호출했고, EXP-077은 provider를 가짜 서버로 대신했다), 운영자 통제 네 가지가 "범위 밖"으로만 적혀 있고, 보내는 국가 적격성이 `not confirmed`다. 발생 빈도와 금액은 측정할 수 없어 쓰지 않는다. 영향은 Live 지급을 열기 전의 판단 근거가 비어 있다는 것이다.

## 이미 아는 것과 모르는 것 (측정 전)

- 안다(EXP-073): Sandbox에서 별도 활성화 없이 지급 생성이 201이었고, 항목 `SUCCESS`와 `UNCLAIMED`를 관측했다. 이것은 API 직접 호출이었다.
- 안다(EXP-077, EXP-096): 워커와 승인 경로는 가짜 PayPal과 PostgreSQL에서 통과하고, 승인자 ≠ 검증자는 DB trigger로 강제된다.
- 모른다: 앱 전체 경로가 실제 응답에서 성립하는지, 기본 환율 공급자(BOK ECOS) 키가 없는 환경에서 어떤 공급자로 돌아가는지, 운영자 JWT가 TOTP를 거친 로그인에서 나왔는지 앱이 검사하는지.

## 가설과 반증 조건

| # | 가설 | 지표 | 기대값 | 반증 조건 | 이 값이 정하는 결정 |
|---|---|---|---|---|---|
| H1 | 실제 Sandbox에서 앱 전체 경로(Seller 프로필 `US`·USD, 운영자 검증·승인, 워커 전송)가 성립해 항목 `SUCCESS`, payout `CONFIRMED`, payout journal 1건, 차변 = 대변이 된다 | 독립 지급 n=3에서 위 네 조건을 모두 만족한 지급 수 | 3/3 | 한 건이라도 항목이 `SUCCESS`가 아닌 채 끝나거나, journal이 0건이나 2건 이상이거나, PayPal을 직접 조회한 항목 수가 1이 아니거나, 직접 조회한 금액·수수료가 DB와 다르다 | 3/3이면 FR-019/BR-019를 유형 1(검증)로 끝낸다. 권한·계정 오류(HTTP 상태와 본문 키로 증명)면 유형 3(외부 차단)과 V1 결정 |
| H2 | PayPal 계정이 없는 이메일 수취인은 항목 `UNCLAIMED`가 되고 journal 0건이며 예약이 유지된다 | n=2에서 `UNCLAIMED` 상태, payout journal 수, 예약 잔액 | 2/2, 0건, 유지 | journal이 생기거나 예약이 풀린다 | 위반이면 INV-016 코드 결함으로 수정 |
| H3 | 같은 승인 요청(같은 Idempotency-Key)을 provider 성공 뒤 다시 보내도 PayPal 항목은 늘지 않고 journal도 늘지 않는다 | 재생 뒤 PayPal 직접 조회 항목 수, journal 수 | 1, 1 | 둘 중 하나라도 늘어난다 | 위반이면 멱등 결함 |
| H4 | 현재 코드는 **TOTP를 등록하기 전에 비밀번호만으로 만든 세션**의 access JWT도 지급 운영자 경로에서 받아 준다(등록은 검사하지만 그 세션이 TOTP를 거쳤는지는 보지 않는다) | 등록 전 세션 JWT로 운영자 경로를 호출한 n=20 중 통과 수 | 변경 전 20/20 통과, 변경 후 0/20 통과 | 변경 전에 이미 거절한다(가설 기각, 변경 불필요) | 20/20이면 세션별 MFA 검증 표식을 구현한다. 변경 후 TOTP를 거친 세션 20/20은 통과해야 한다(오거절 0) |
| H5 | 현재 코드는 같은 운영자가 지급을 승인하고 provider 검토를 `NOT_PAID`로 해소할 수 있고, 그러면 같은 잔액으로 두 번째 지급을 만들 수 있다 | 같은 account가 승인과 해소를 한 비율, 이후 재요청 가능 여부, n=20 | 변경 전 20/20, 변경 후 0/20(DB 거절 23514), 다른 운영자 해소는 20/20 통과 | 변경 전에 이미 막혀 있다 | 20/20이면 돈의 상태를 바꾸는 해소 조치에 DB trigger 4-eyes를 넣는다(마이그레이션 V6) |
| H6 | 공개 PayPal 문서는 South Korea 사업자 계정의 Payouts **발송** 가능 여부를 명시하지 않는다 | 공개 페이지에서 South Korea가 발송 국가로 나오는 행과 명시 문장의 수 | 0 (지원 표에 행 없음, 명시 문장 없음) | 어느 페이지든 명시 문장이나 행이 있다 | 0이면 유형 3: PayPal에 물을 정확한 질문과 V1 결정(Live 지급 주장 안 함). 있으면 그 문구를 인용해 갱신 |
| H7 | 기존 `AuthAttemptLimiter`만으로 온라인 비밀번호 추측이 시간 창당 한도로 묶인다. 영구 잠금은 운영자 로그인을 막는 공격 수단이 된다 | 틀린 비밀번호 n=30 중 429 비율과, 한도 도달 뒤 올바른 비밀번호의 거절 여부 | 한도 뒤 429, 올바른 비밀번호도 일시 거절 | 한도 없이 30번 모두 401이 나온다 | 한도가 있으면 영구 잠금·회전 정책은 결정으로 끝내고 재검토 조건을 둔다. 없으면 구현 대상 |

## 통제

- 변경 없음 통제: 변경 전 코드(`origin/main`)에서 같은 측정을 먼저 돌려 H4, H5, H7의 기준값을 얻는다.
- 음성 통제(탐지기가 반응해야 한다): provider 진실 대조기에 일부러 틀린 금액과 존재하지 않는 batch ID를 넣어 불일치를 보고하는지 확인한다. H4와 H5의 탐지기는 변경 후 코드에서 의도적으로 규칙을 깬 변이로 반응을 확인한다.
- 같은 환경 전후: H4, H5는 같은 PostgreSQL 컨테이너 버전과 같은 테스트 입력으로 변경 전후를 비교한다.

## 표본 크기와 한계

- H1은 n=3, H2는 n=2다. 0/n 결과는 "3/n" 규칙으로 쓴다(예: 0/3이면 실패율 95% 상한 100%, 아무것도 보장하지 못한다). 3/3은 이 계정, 이 날의 Sandbox에서 반복되었다는 뜻이지 Live 일반화가 아니다.
- H4, H5는 결정적 코드 경로라 n=20은 확률이 아니라 입력 변형(계정 20개)이다. 0/20 통과는 "이 경로를 지나는 요청이 통과하지 않았다"만 말한다.
- 공유 호스트(다른 에이전트가 CPU와 Docker를 함께 쓴다)라 시간 값은 맥락으로만 적고 결론은 횟수와 불변식으로 낸다.

## 교란 요인

| 요인 | 영향 | 배제 방법 |
|---|---|---|
| Sandbox 상태·지연이 날마다 다르다 | 항목이 `PENDING`에 오래 머문다 | 종료 조건은 시간이 아니라 항목 상태. 폴링 상한을 두고 상한 초과는 실패가 아니라 "미종결"로 기록한다 |
| 환율 공급자 | 기본 BOK ECOS는 키가 없다 | 키 없이 쓸 수 있는 공급자(`frankfurter`)로 고정하고 그 사실을 적는다. 결제 쪽 FX는 기존 하네스처럼 `SYNTHETIC_FIXTURE`다 |
| 수취인 계정 | Sandbox 개인 계정 1개만 있다 | 같은 계정으로 n=3을 받는다. 계정이 다르다는 일반화는 하지 않는다 |
| 합성 FX 라운딩 상한 | 소액은 1% 상한으로 자동 전송이 거절된다 | 거절되지 않는 금액(10,000원)으로 고정하고 상한 거절 자체는 단위 테스트가 이미 지킨다 |
| TOTP 단계 재사용 방지 | 같은 30초 안의 두 번째 코드가 거절된다 | 로그인 사이에 다음 단계를 기다린다 |

## 층 이동 계획

한 번에 한 층만 본다. E2E가 실패하면 순서대로 좁힌다. (1) 하네스 없이 OAuth 토큰과 지급 생성을 직접 호출해 권한을 가른다. (2) 앱이 만든 요청 본문과 provider 응답의 키 집합을 직접 조회와 대조한다. (3) DB 상태(outbox, 감사 행, journal)를 본다. 하위 층이 통과하기 전에는 상위 층의 원인을 단정하지 않는다.

## 결정 대안 (구현 전에 비교)

| 안 | 얻는 것 | 잃는 것 |
|---|---|---|
| 없음(현상 유지, 문서만 정정) | 코드 변경 0 | H4, H5가 참이면 Live 전에 사람 절차에 의존하는 구멍이 남는다 |
| 세션에 MFA 검증 표식을 DB 열로 기록(V6) | 로그인 시 TOTP 사용을 요청마다 검증, 경쟁 없음 | 마이그레이션 1건, 로그인·갱신 경로 수정 |
| 등록 시각과 세션 생성 시각 비교(마이그레이션 없음) | 열 추가 없음 | 등록 확정 순간 로그인과 겹치면 표식 없는 세션이 통과할 수 있는 좁은 경쟁 |
| 해소 조치 4-eyes를 service 검사로만 | 코드 작음 | EXP-096 E1에서 규칙을 모르는 INSERT가 200/200 통과: 우회 가능 |
| 해소 조치 4-eyes를 DB trigger로(V6) | 우회 경로 없음 | 운영자 최소 2명 필요(이미 ADR-0092의 전제) |
| IdP·gateway로 MFA·잠금·회전을 한 번에 | 완결 | 도입·운영 비용, 저장소에 없음. 후속 선택지로 남긴다 |

## 사전 부검 (이 변경이 운영에서 실패하는 방법)

- V6가 기존 데이터에서 실패한다: V6는 열 추가와 함수 교체만 한다. 기존 행은 건드리지 않고, 되돌림 SQL을 같이 시험한다(되돌리기 훈련).
- 기존 운영자 세션이 갑자기 거절된다: 의도한 결과다(다시 로그인하면 된다). 운영 절차 문서에 적는다.
- 운영자가 1명이면 해소가 막힌다: ADR-0092와 같은 의도된 결과다.
- 새 trigger가 재생(replay) 요청을 거절해 멱등성이 깨진다: 재생은 INSERT 없이 반환하는 경로이고, 재생 시험으로 확인한다.

## 결정 규칙 (실행 전 확정)

1. H1 3/3이면 유형 1. 권한·계정 오류가 증명되면 유형 3. 그 사이(일부만 성공, 원인 불명)는 층 이동 계획대로 좁히고, 원인을 못 찾으면 그 사실과 좁힌 범위를 쓰고 유형 3으로 끝내지 않는다(다시 실행한다).
2. H4, H5가 기각되지 않으면 구현한다. 기각되면 구현하지 않고 기각 증거를 적는다.
3. H6이 0이면 유형 3이다. 문서에 "확인하지 못했다"로 끝내지 않고 질문, 필요한 입력, V1 결정을 적는다.
4. H7은 결과와 무관하게 영구 잠금·회전 정책을 이번에 구현하지 않는다(근거를 결과 절에 쓴다). 한도 자체가 없으면 그때만 구현한다.

## 추가 사전등록 H4b (2026-11-26)

| 가설 | 지표 | 기대값 | 반증 조건 | 결정 |
|---|---|---|---|---|
| MFA가 꺼졌다가 새 secret으로 재등록되면 이전 enrollment 때 2차 인증한 refresh family는 지급 운영자 경로에 다시 들어오지 못한다 | 같은 PostgreSQL account에서 disable → 새 secret enrollment/confirm 후, 예전 access JWT와 새 MFA challenge access JWT를 각각 검사하는 n=20 | 변경 전 이전 family 통과 20/20; 변경 후 이전 family 통과 0/20, 새 family 거절 0/20 | 변경 전 이미 이전 family가 거절됨 | 기존 `mfa_verified_at`만으로는 enrollment 재설정을 구별 못 하므로, 현재 `account_mfa_secret.enabled_at`보다 그 family의 인증 표식이 이후인지 비교한다 |

- 등록 시각 비교는 로그인 순간의 추측이 아니라 MFA enrollment generation 경계를 구분하는 용도다. Disable 후에는 MFA secret row가 삭제되고 re-enrollment는 새 `enabled_at`을 만든다. H4b에서는 그 경계 이후 처음 재사용된 이전 토큰이 반증 사례인지 먼저 실행해 측정한다.

## 사전등록 이탈 기록

- 코드 검토에서 `PAYOUT_REQUEUED`가 provider 호출 전에 재전송할 수 있다는 점을 확인했다. 같은 `sender_batch_id`·요청 ID는 중복 전송을 막지만, 첫 요청이 provider에 도달하지 않았다면 재큐가 실제 지급을 처음 만들 수 있다. 이 조치는 money-moving으로 분류한다.
- 이 하위 질문은 초기 사전등록 표에 없었다. 이를 보완하는 회귀 테스트의 변경 전 기준 실행이 이미 수행됐으므로 H1–H7과 같은 사전등록·확증 실험이라고 주장하지 않는다. 변경 전 결과는 탐색 근거로만 기록하고, 수정 뒤 테스트·변이 결과도 이 프로토콜 이탈을 그대로 표시한다.

## 결과 (2026-11-26)

### 실행 환경과 도구

- 앱: `atlas-application.jar`(이 브랜치, `clean` 없이 bootJar), PostgreSQL 18.6 컨테이너, Toxiproxy 2.12.0, Mailpit v1.34.0(모두 기존 `compose.toxiproxy.yaml` 이미지, 컨테이너에 `atlasexp=t2` 라벨). 실제 PayPal Sandbox(`api-m.sandbox.paypal.com`)로만 나갔다. Sandbox 자격 증명은 하네스가 `.env.local`에서 프로세스 안에서만 읽었고 출력하지 않았다.
- 새로 설치한 도구: 없다. 하네스의 기존 `playwright` Chromium과 Node 내장 `fetch`, Python 표준 라이브러리만 썼다. 공개 환율은 `api.frankfurter.dev`(ECB 기준, 가입 불필요)이며 JVM `hosts` 파일에 이 호스트만 추가로 고정했다.
- 호스트가 공유 환경이라 시간 값은 적지 않는다. 판정은 횟수와 불변식으로 한다.
- 제외한 층: 결제 쪽 FX는 기존 하네스와 같은 합성 값이다. 지급 쪽 FX는 실제 Frankfurter 값(1345.4 KRW/USD, 기준일 2026-11-25)을 지급 스냅샷에 고정했다.

### 실행 중 드러나 고친 결함 3건 (E2E를 처음 돌렸을 때 순서대로 막힌 것)

| # | 증상 | 원인(확인 방법) | 수정과 검증 |
|---|---|---|---|
| D1 | `provider=frankfurter`에서 앱이 기동하지 않는다 | `CachingExchangeRateProvider`가 구현 클래스 `BokEcosExchangeRateProvider`에 직접 의존해 `No qualifying bean of type ...BokEcosExchangeRateProvider`로 컨텍스트가 실패한다(이전 에이전트의 기동 로그, local-only) | 위임 대상을 인터페이스와 `@Qualifier`로 받는다. 실제 컴포넌트 선언으로 두 설정을 확인하는 시험 2건. 한정자 제거 변이 2건이 각각 실패하고 되돌리면 통과한다 |
| D2 | 지급이 `PROVIDER_REJECTED`로 끝나는데 이유를 알 수 없다 | 예외 메시지가 상태 코드만 가졌다. 하네스 로그에서 `403 name=NOT_AUTHORIZED`가 처음 보였다(수정 후 실행 a4) | 예외 메시지에 PayPal 오류 이름과 `details[].issue` 코드만 싣는다(문자 집합 `[A-Za-z0-9_. -]`만, 수취인 값은 버린다). 워커가 WARN으로 남긴다. 시험 1건, 변이 2건이 실패한다 |
| D3 | 실제 Sandbox가 지급 생성을 403 `NOT_AUTHORIZED`로 거절한다 | 지급 OAuth 등록이 `scope=https://uri.paypal.com/services/payments/payouts`를 요청했다(이 문자열에 한정된 현상이다: `/services/` 없는 `https://uri.paypal.com/payments/payouts`는 payouts scope를 유지하고 지급 생성 201이었다, 2026-11-26 재실행 n=1). 같은 자격 증명으로 직접 호출해 가렸다: scope 없는 토큰은 지급 생성 201, scope를 요청한 토큰은 `scope` 응답이 `openid` 한 개이고 지급 생성 403, 존재하지 않는 배치 조회도 403(scope 없는 토큰은 400 `VALIDATION_ERROR`) | scope 요청을 제거한다(ADR-0057 갱신). 시험은 scope가 비어 있음을 지킨다. 변이(scope 되살림)가 실패한다. 수정 전 실행 a4는 지급 1건이 `FAILED`로 끝났고 수정 후 a5, a6, a7은 5/5가 기대 종결 상태였다 |

D3 때문에 EXP-073(2026-11-25)의 "별도 활성화 없이 생성 201"은 scope를 요청하지 않은 직접 호출의 사실이었고, 앱 경로는 그동안 한 번도 실제 Sandbox에서 지급을 만든 적이 없었다는 것이 이번에 처음 드러났다.

### H1, H2, H3: 앱 전체 경로 (실행 a7, 변경 후 최종 코드)

독립 지급 5건을 만들었다(Seller 3명: 성공 3건은 같은 Seller가 기간을 달리해 요청, 미등록 2건은 각각 새 Seller. 지급마다 별도 결제 캡처 1건). 앞 3건은 PayPal Sandbox에 등록된 개인 계정 수취인, 뒤 2건은 PayPal 계정이 없는 이메일 수취인이다. 지급 요청 10,000원, Frankfurter 1345.4 KRW/USD로 7.44 USD(744센트)다. 같은 구성의 이전 실행 a5, a6도 같은 결과였다(a5는 3 SUCCESS 2 UNCLAIMED, a6은 3 SUCCESS 2 UNCLAIMED. 합계 15건 중 15건이 기대 종결 상태).

| 지급 | 수취인 | payout 상태 | 항목 상태(DB) | PayPal 직접 조회 항목 상태, 항목 수, 금액, 수수료 | journal 수(줄 수) | 차변 = 대변(원) | 예약 잔액 | 승인 재생 응답, outbox 수, 승인 행 수 |
|---|---|---|---|---|---|---|---|---|
| 1 | 등록 계정 | `CONFIRMED` | `SUCCESS` | `SUCCESS`, 1, 7.44 USD, 0.15 USD | 1(4) | 10,212 = 10,212 | 0 | 202, 1, 1 |
| 2 | 등록 계정 | `CONFIRMED` | `SUCCESS` | `SUCCESS`, 1, 7.44 USD, 0.15 USD | 1(4) | 10,212 = 10,212 | 0 | 202, 1, 1 |
| 3 | 등록 계정 | `CONFIRMED` | `SUCCESS` | `SUCCESS`, 1, 7.44 USD, 0.15 USD | 1(4) | 10,212 = 10,212 | 0 | 202, 1, 1 |
| 4 | 미등록 이메일 | `UNCLAIMED` | `UNCLAIMED` | `UNCLAIMED`, 1, 7.44 USD, 0.15 USD | 0 | 0 | 10,000 | 202, 1, 1 |
| 5 | 미등록 이메일 | `UNCLAIMED` | `UNCLAIMED` | `UNCLAIMED`, 1, 7.44 USD, 0.15 USD | 0 | 0 | 10,000 | 202, 1, 1 |

- 성공 지급의 journal 줄 네 개는 합이 10,212원이다: 지급 10,000원 + 환율 반올림 비용 10원 + 수수료 0.15 USD를 올림한 202원(`PLATFORM_PAYOUT_FEE`). DB 금액과 수수료는 PayPal 직접 조회와 일치했고, `sender_item_id`도 일치했다. PayPal 직접 조회의 배치 응답 최상위 키 집합은 `batch_header, items, links, total_items, total_pages`다.
- 배치 상태는 항목이 `SUCCESS`가 된 뒤에도 `PROCESSING`으로 남는 경우가 있었다(a7 지급 1, 2 직접 조회 시점). 앱은 항목 상태로 판정하므로 영향이 없었다.
- 승인 재생(같은 Idempotency-Key)은 지급 5건 모두에서 202였고, 4초 뒤에도 outbox 1건, 승인 행 1건, provider 배치 ID가 재생 전과 같았다. PayPal 직접 조회 항목 수는 1이었다(H3).
- H1: 3/3. 반증 조건(한 건이라도 항목이 `SUCCESS`가 아님, journal 0 또는 2건 이상, PayPal 직접 조회 항목 수가 1이 아님, 금액·수수료 불일치)에 걸린 지급은 0건이다. 3/3은 이 계정, 이 날의 Sandbox에서 반복된 것이고 Live로 일반화하지 않는다(위 사전등록의 한계 그대로).
- H2: 2/2. `UNCLAIMED`, journal 0, 예약 10,000원 유지. PayPal은 이 수취인에 대해서도 수수료 0.15 USD를 보였지만 앱은 `UNCLAIMED`에서 수수료를 DB에 올리지 않는다(`provider_fee_minor`가 비어 있음).
- H3: 5/5.
- 사전등록된 음성 통제 중 존재하지 않는 batch ID: 첫 실행 a1에서 하네스가 잘못 읽은 값(`sql()`이 앞쪽 빈 열을 잘라 batch ID 열이 밀렸다)으로 PayPal을 직접 조회했고 HTTP 400이 돌아와 하네스가 중단됐다. 계획한 통제는 아니지만 대조기가 존재하지 않는 ID에 반응한다는 관측이다(1/1). `sql()`은 뒤쪽 줄바꿈만 자르도록 고쳤다. 틀린 금액을 일부러 넣는 통제는 실행하지 않았다: 금액 비교는 문자열 동등 비교 한 식이고, 이번 판정은 이 비교가 일치하는 쪽(15/15)만 주장하기 때문이다.

### 소액 제약과 funds 실패 응답

- 10,000원 지급의 환율 반올림 비용은 10원(0.1%)이라 1% 상한 안이다. 상한을 넘는 소액의 자동 전송 거절은 이번에 다시 재지 않았다(단위 테스트가 지킨다).
- 직접 호출로 본 funds 실패의 실제 응답: 등록 수취인 건당 한도(60,000 USD) 아래인 59,999.00 USD 생성 요청은 HTTP 422, 본문 키 `debug_id, information_link, links, message, name`, `name=INSUFFICIENT_FUNDS`였다(1/1, 돈은 움직이지 않았다). 앱은 POST의 4xx를 `DEFINITIVE_NO_SEND`로 분류하므로 이 응답은 지급 `FAILED`(`PROVIDER_REJECTED`)가 된다. 이 경로의 앱 전체 재현은 하지 않았다(큰 금액의 정산 원장을 만들어야 한다). 근거는 분류 코드와 4xx 시험이다.
- 같은 직접 호출로 Sandbox 지급 두 건(0.74, 7.44 USD)을 수취 계정에 실제로 만들었다(201, `PENDING`). 최소 금액이나 funding 사전 확인은 이번에 시험하지 않았다.

### H4, H4b, H5, 재큐: 코드 변경 전후 (PostgreSQL 통합 테스트, n=20 입력 변형)

변경 전 기준은 이번 커밋의 규칙 한 줄씩을 되돌린 코드로 같은 PostgreSQL 컨테이너 버전, 같은 시험 입력으로 돌렸다(기준 규칙 복원 방식이며 `origin/main` 자체를 다시 빌드한 것은 아니다).

| 가설 | 지표 | 기준 | 변경 후 | 기대 |
|---|---|---|---|---|
| H4 (등록 전 비밀번호 세션) | 운영자 경로 통과 | 20/20 | 0/20 | 일치 |
| H4b (MFA 재등록 이전 세션) | 이전 family가 새 enrollment 뒤에 통과 | 20/20 | 0/20 (오거절: 새 MFA 세션 0/20) | 일치 |
| H5 (승인자가 같은 지급의 provider 검토를 `NOT_PAID`로 해소하고 두 번째 지급 요청) | 같은 account 해소 성공, 이후 두 번째 지급 요청 성공 | 20/20, 20/20 | 0/20, 0/20 (다른 운영자 오거절 0/20) | 일치 |
| 재큐(탐색, 사전등록 이탈) | 승인자 또는 승인 재생자가 재큐 | 허용(규칙을 provider 검토와 함께 빼면 시험 3건, 재큐만 빼면 2건 실패. 2026-11-26 재실행으로 1건에서 2건으로 고침) | 403 `SEPARATION_OF_DUTIES`, 직접 INSERT는 23514, 다른 운영자는 200 | 사전등록 표에 없던 하위 질문이라 확증이 아니라 탐색 |

H4b는 사전등록 후 별도로 추가한 가설이다(위 "추가 사전등록 H4b"). H4와 H5의 `20/20 → 0/20`은 코드 경로가 결정적이라 확률이 아니라 입력 변형 20개(계정 20개)다.

### H7: 로그인 한도 (실제 앱, 실행 a7)

틀린 비밀번호 30회: 401이 30회, 429가 0회. 앞 10회는 지연 30ms 이상(비밀번호 해시 계산), 11회부터 20회는 모두 30ms 미만(해시 계산 전에 거절). 그 뒤 올바른 비밀번호는 401로 거절됐고, 실패 카운터는 30이 아니라 10에서 멈췄다. 사전등록 표의 기대값 "한도 뒤 429"는 상태 코드 면에서는 틀렸다: 한도 초과도 같은 401(`INVALID_CREDENTIALS`)로 응답한다(계정 존재 여부 노출을 피하는 기존 설계, `AuthAttemptLimiterIntegrationTest`가 지킨다). 반증 조건인 "한도 없이 30번 모두 401"은 한도가 있으므로 충족되지 않았다. 판정: 한도는 있고(카운터 10, 올바른 비밀번호도 거절, 시간 창이 지나면 풀림은 기존 통합 테스트), 결정 규칙 4에 따라 영구 잠금과 정기 회전은 구현하지 않는다(ADR-0094 결정 4, 5).

### H6: 공개 문서에서 보내는 국가(South Korea) (2026-11-26 재열람)

- 열람: `https://developer.paypal.com/payouts/supported-features`(Last updated: September 10, 2026), `/payouts/currency-conversion`(Last updated: October 14, 2026), `/payouts/use-payouts/set-up`, `/payouts/fees`. 서버가 내려준 HTML에서 텍스트를 추출했다.
- 지원 국가 표: `Send, receive, and withdraw` 53행, `Fully localized` 23행, `Send, receive, and withdraw in local currency` 14행, `Receive and withdraw` 2행(India, Mexico). 합 92행. South Korea 행은 0행이다(`KR` 코드 행도 0).
- 같은 페이지의 feature set 정의 원문: `Send, receive, and withdraw` = "Merchants can send payouts from this country. PayPal account holders can receive and withdraw money."; `Receive and withdraw` = "Merchants cannot send payouts from this country. PayPal account holders can receive and withdraw money."; 표 도입 문장 = "The following table lists countries eligible for payouts and their available feature sets:".
- `currency-conversion` 페이지의 `South Korea` 문구는 "Currency conversion is not available for payouts to the following countries and regions"(Asia 목록) 안에 있다. 이는 수취 통화 자동 환전이며 South Korea 등록 merchant가 보낼 수 있는지와 다른 문장이다.
- 후보 PayPal 본사 페이지 6개(`paypal.com/us|kr/business/payouts-api`, `/kr/business`, `/us/cshelp/article/...help250`, `/us|kr/webapps/mpp/country-worldwide`)는 `urllib`로 6/6, 헤드리스 Chromium으로 4/4 HTTP 403이었다. 읽지 못했다.
- 판정: H6 기대값 "0"이 맞다(표 행 0, South Korea를 발송 국가로 명시한 문장 0). 공개 자료로는 South Korea 발송 가능 여부를 확정할 수 없고, 반대로 불가를 명시하지도 않는다. 이는 유형 3이다: 질문, 필요한 입력, V1 결정은 ADR-0078 gate 2에 있다.

### 판정표 (claims table)

| 항목 | 이전 → 이후 | 종결 유형 | 근거 |
|---|---|---|---|
| FR-019 앱 전체 Sandbox E2E | `◐` (앱 전체 Sandbox E2E 기록 없음) → `✓` | 1 검증 | 실제 Sandbox, 앱·PostgreSQL 전체, 15/15 기대 종결(`SUCCESS` 9, `UNCLAIMED` 6), 항목 직접 조회 일치(a6, a7 10/10의 금액·수수료 대조), journal은 `SUCCESS`에서만 1건 |
| BR-019 | `◐` (Sandbox 앱 전체 검증 전) → `✓` | 1 검증 | 승인 재생 응답 202는 15/15, 재생 뒤 outbox, 승인 행, provider 배치 ID가 변하지 않음은 a6, a7의 10/10 |
| Q-013 운영자 MFA 세션 | 미충족 → 충족 | 1 검증 | H4 20/20 → 0/20, H4b 20/20 → 0/20, 변이 실패 확인 |
| Q-013 money-moving 4-eyes (재큐, provider 검토, 송금 없음 확정) | 미충족 → 충족 | 1 검증 | H5 20/20 → 0/20, 재큐 변이 실패 확인, DB trigger |
| Q-013 영구 잠금과 정기 회전 | 범위 밖 → 결정 | 2 DECIDED-OUT | 기존 한도가 있음(H7), 재검토 조건은 ADR-0094 결정 4, 5 |
| Q-013 sender country (South Korea) | 발송 가능 여부 미해결 → 외부 차단 | 3 EXTERNAL-BLOCKED | 위 H6, ADR-0078 gate 2 |
| 지급 OAuth scope, 오류 사유 가시성, Frankfurter 기동 | 앱 경로 미작동 → 수정 | 1 검증 | D1, D2, D3 |

### 한계와 실행하지 않은 것

- 수취 계정은 Sandbox 개인 계정 1개와 미등록 이메일 2종이다. 계정이 다르면 결과가 같다는 일반화는 하지 않는다. 같은 Sandbox 계정, 같은 날 실행이라 날짜 간 변동은 모른다.
- 앱 전체 경로에서 `INSUFFICIENT_FUNDS`를 일으키지 않았다(위 근거는 직접 호출 1회와 분류 코드).
- Live 지급, Live 자격 증명, Live host는 사용하지 않았다(코드가 막는다: `PayPalPropertiesTest.liveEndpointRequiresExplicitOptIn`).
- H4, H5의 변경 전은 기준 규칙 복원으로 재현했고 원본 `origin/main`의 별도 빌드는 아니다.
- 이전 에이전트가 기록한 변경 전 지표와 변이 로그(H4 20/20, H5 20/20, 재큐 변경 전 실패)는 이번에 위 복원 방식으로 같은 값이 다시 나왔다. 이전 에이전트의 기동 로그 인용(D1)만 local-only다.

## 삭제한 파일의 내용

저장소에 올리지 않은 로컬 증거 파일의 집계다(원본은 지우지 않았고 `/tmp` 아래에 있다).

- 하네스 실행 로그 a1~a7: 결과 JSON 한 줄(지급별 상태, journal 수, 직접 조회 값)과 앱 로그의 WARN 줄. a1은 `sql()` 열 밀림으로 중단, a2는 앱이 지급 거절 사유를 남기지 않아 원인 불명으로 종료, a3는 같은 상태, a4는 사유 로그로 `403 name=NOT_AUTHORIZED issues=[NOT_AUTHORIZED]`를 보여 주었고, a5, a6, a7이 3 SUCCESS 2 UNCLAIMED였다(a6, a7은 재생과 한도 측정을 포함). 원본 결과 JSON은 하네스 `RESULTS_DIR`에만 쓰고 저장소에 두지 않는다.
- 직접 호출 기록: scope 없는 토큰의 scope 문자열은 24개 서비스(`payments/payouts`는 목록에 없음, 그래도 생성 201), scope 요청 토큰은 `openid` 1개. 생성 201 두 건(0.74, 7.44 USD)과 `INSUFFICIENT_FUNDS` 422 한 건의 응답 키 집합.
- 변이 결과: 서로 다른 변이 13건 중 12건은 시험이 실패했고 되돌리면 파일 비교가 같았다(MFA 표식 검사 제거 2건 실패, enrollment 비교 제거 1건, MFA 로그인 표식 제거 12건, provider 검토와 재큐 규칙 함께 제거 3건, 재큐 규칙만 제거 2건(2026-11-26 재실행으로 1건에서 2건으로 고침), 승인 재생 제외 1건, 송금 없음 규칙 제거 1건, 환율 공급자 한정자 제거 2건 각 1건, OAuth scope 되살림 1건, 오류 요약 제거 1건, 이메일 허용 1건). 살아남은 변이 1건은 비밀번호 로그인에도 MFA 표식을 찍는 변이이며 동등 변이다: 표식이 MFA enrollment 시각보다 이전이면 거절하는 비교(`>= enabled_at`)가 있어, 비밀번호 로그인 세션은 표식이 있어도 등록 이전이라 거절되고, 등록 이후의 비밀번호 로그인은 MFA challenge를 반환할 뿐 세션을 만들지 않으므로 관측 가능한 동작이 같다.
- 공개 문서 스냅샷: 지원 국가 표 92행의 feature set 집계와 국가 행 부재, 환율 변환 제외 목록의 South Korea 줄, PayPal 본사 페이지 403 목록.
- 하네스 컨테이너: `atlasexp=t2` 라벨 컨테이너와 네트워크는 실행 종료 시 `compose down`이 제거했고 남은 컨테이너는 0개다.

## 재실행 결과 (2026-11-26, [Issue #707](https://github.com/Neo1228/ATLAS/issues/707))

위 결과·판정표 중 근거가 "에이전트 보고"나 로컬 전용이던 주장을 같은 날 다시 실행했다. 실제 Sandbox 호출은 같은 앱, 같은 자격 증명(값은 어디에도 쓰지 않았다)으로 `api-m.sandbox.paypal.com`에만 보냈고 금액은 USD 0.01이다. DB 트리거·MFA·결제 변이는 커밋된 시험을 한 번에 하나씩 변이시켜 대상 클래스를 돌리고 되돌린 뒤 `git show origin/main:<경로> | cmp`로 같음을 확인했다. 변이 확인은 통과·실패만 보므로 호스트 부하와 무관하다(호스트 잠금을 잡지 않았다). 아래 Sandbox 호출은 provider 수준에서 같은 응답을 재현한 것이다.

### 실제 Sandbox 지급 호출(provider 수준)

| 주장 | 다시 실행한 것 | 관측(n) | 판정 |
| --- | --- | --- | --- |
| D3: `scope=https://uri.paypal.com/services/payments/payouts`를 요청한 토큰은 scope가 `openid`뿐이고 지급 생성이 403 `NOT_AUTHORIZED`다 | 그 scope로 토큰을 받고 `POST /v1/payments/payouts` | 토큰 200, `scope` 목록 `[openid]`, 지급 생성 403 `name=NOT_AUTHORIZED`, `issues=[NOT_AUTHORIZED]`(n=1). `scope=openid`만 요청해도 같은 403. 임의의 문자열을 scope로 요청해도 토큰 200, `scope=[openid]`, 지급 403(n=1) | 재현 ✓ |
| D3: scope 없이 받은 토큰은 지급 생성 201이다(앱 경로 `PayPalOAuth2ClientConfiguration`은 scope를 요청하지 않는다) | scope 없는 토큰으로 지급 생성 2건(등록된 구매자 계정 수취, 등록되지 않은 이메일 수취) | 둘 다 201, 배치 상태 `PENDING`. 코드는 읽기만 했다(`.scope(...)` 없음). 토큰의 `scope` 문자열은 지금 30개이고 `https://uri.paypal.com/payments/payouts`(`/services/` 없음)는 있으나 `https://uri.paypal.com/services/payments/payouts`는 없다. 위 "scope 문자열 24개" 기록과 지금의 30개가 다른 원인은 확인하지 않았다 | 재현 ✓(개수는 달라짐) |
| D3 보강(문서에 없던 사실): `/services/`가 없는 `https://uri.paypal.com/payments/payouts`를 요청하면 | 같은 토큰 요청과 지급 생성 1건 | 토큰이 payouts scope를 유지하고 지급 생성 201(n=1) | 새 관측. "payouts scope를 요청하면 토큰이 `openid`로 줄어든다"는 문장은 `/services/payments/payouts` 문자열에만 해당한다(아래 문장 정정 참고) |
| D3: 존재하지 않는 배치 조회·잘못된 본문은 scope 없는 토큰에서 400 `VALIDATION_ERROR`, scope 요청 토큰에서는 403이다(권한 검사가 먼저) | 빈 `items`로 지급 생성 | 전체 권한 토큰: 400 `VALIDATION_ERROR`. 잘못된 scope 토큰: 403 `NOT_AUTHORIZED`(n=1씩) | 재현 ✓ |
| 응답 모양: 생성 201 `PENDING` → 조회 `PROCESSING` → `SUCCESS`, 미가입 이메일은 항목 `UNCLAIMED`(배치 `SUCCESS`) | 지급 3건 생성 뒤 `GET /v1/payments/payouts/{id}` 5번 | 생성 직후 `PENDING`, 약 8초 뒤 배치 `PROCESSING`·항목 `PENDING`, 약 35초 뒤 배치 `SUCCESS`·항목 `SUCCESS`(구매자 수취). 미가입 이메일 수취는 항목 `UNCLAIMED`·배치 `SUCCESS`(각 n=1) | 모양 재현 ✓. 앱 전체의 15/15(`SUCCESS` 9, `UNCLAIMED` 6)는 아래 별도 행 |
| 같은 `sender_batch_id`로 다시 보내기(PAYOUT_REQUEUED가 의존) | 같은 `sender_batch_id`를 같은 `PayPal-Request-Id`로, 새 `PayPal-Request-Id`로 다시 생성(합계 3번) | 3번 모두 400 `USER_BUSINESS_ERROR`, 이슈 "Batch with given sender_batch_id already exists"(필드 `SENDER_BATCH_ID`). 200 재생이 아니고 응답에 원래 배치 ID나 링크가 없다 | EXP-073의 "중복은 400" 기록과 일치 ✓. 문서·ADR-0094에서 "같은 배치 ID 재전송이 원래 배치를 돌려준다"고 쓴 문장은 찾지 못했다 |
| EXP-102 요약의 "지급 OAuth scope" 판정 | 위 두 행 | 앱 경로(scope 없음)는 지급을 만들고, 요청 문자열이 `/services/payments/payouts`일 때만 403이다 | 재현 ✓ |

### 운영자 MFA·4-eyes DB 트리거 변이

기준선: `PayoutOperatorMakerCheckerIntegrationTest` + `AccountAuthenticationIntegrationTest` 26개 통과, 실패 0, skipped 0(`PayoutOperatorMakerCheckerIntegrationTest` 단독으로는 15개이고, EXP-096의 8개에서 #680이 늘렸다). 시험 DB는 Flyway V1~V6 마이그레이션에서 만든 Testcontainers PostgreSQL 18.6이라 변이한 마이그레이션이 곧 시험 DB다.

| 변이(정확한 내용) | 실패한 시험 수(이름) | `cmp` |
| --- | --- | --- |
| V6 승인-검증자 규칙 끔(`IF NEW.action IN ('PAYOUT_APPROVED','PAYOUT_APPROVAL_REPLAYED')` → `('NONE')`) | 3(`theSameOperatorCannotApproveThePayoutOfAProfileItVerified`, `theRuleComparesWithTheVerifierOfTheProfileVersionBoundToTheOutbox`, `concurrentApprovalsByTwoOperatorsLeaveExactlyOneApprovalAndTheVerifierIsAlwaysRejected`) | 일치 |
| 수동 송금 승인≠송금 규칙 끔(`MANUAL_PAYOUT_SENT` → `NONE`) | 1(`manualPayoutStepsRequireDifferentOperators`) | 일치 |
| 송금≠확인 규칙 끔(`MANUAL_PAYOUT_CONFIRMED` → `NONE`) | 1(같은 시험) | 일치 |
| provider 검토 3종과 재큐 규칙 모두 끔 | 3(`anOperatorWhoReplayedTheApprovalCannotRequeueTheSamePayout`, `requeueThatCanStartAnUnacceptedPayoutRequiresAnotherOperator`, `aProviderReviewCannotBeResolvedByTheOperatorWhoApprovedThePayout`) | 일치 |
| 재큐 규칙(`PAYOUT_REQUEUED`)만 끔 | **2**(`anOperatorWhoReplayedTheApprovalCannotRequeueTheSamePayout`, `requeueThatCanStartAnUnacceptedPayoutRequiresAnotherOperator`) | 일치 |
| provider 검토만 끔(재큐 유지) | 1(`aProviderReviewCannotBeResolvedByTheOperatorWhoApprovedThePayout`) | 일치 |
| `PROVIDER_REVIEW_PAID`만 / `PROVIDER_REVIEW_NOT_PAID`만 뺌 | 각 1(같은 검토 시험) | 일치 |
| 승인 재생을 이전 승인 집합에서 제외(`prior.action IN (...)` → `('PAYOUT_APPROVED')`) | 1(`anOperatorWhoReplayedTheApprovalCannotRequeueTheSamePayout`) | 일치 |
| 송금 없음 확정 규칙 끔(`MANUAL_NO_TRANSFER_CONFIRMED` → `NONE`) | 1(`aLegacyNoTransferConfirmationCannotComeFromTheOperatorWhoRecordedTheTransfer`) | 일치 |
| 사람 계정이 아닌 행동자 면제 가드 삭제 | 1(`actorsThatAreNotPersonAccountsAreNotSubjectToTheRule`) | 일치 |
| 운영자 행동 트리거 통째로 삭제(`DROP TRIGGER ...`) | 9 | 일치 |
| V5 프로필 검증자≠수취인 규칙 끔 | 1(`anOperatorCannotVerifyThePayoutProfileOfItsOwnAccount`) | 일치 |
| `AccountRepository.isActivePayoutOperator`의 MFA 표식 검사(`mfa_verified_at >= mfa.enabled_at`) 제거 | 2(`aSessionThatNeverPassedTheSecondFactorIsRejectedEvenAfterTheAccountEnrols`, `aSessionFromAnOlderMfaEnrollmentIsRejectedAfterTheSecretIsReplaced`) | 일치 |
| 위 비교를 `mfa_verified_at IS NOT NULL`로 약화(enrollment 비교 제거) | 1(`aSessionFromAnOlderMfaEnrollmentIsRejectedAfterTheSecretIsReplaced`) | 일치 |
| MFA 로그인이 표식을 찍지 않음(`issueTokens(account, true)` → `false`) | 12 | 일치 |
| 세션 family를 쓰지 않음(`isPayoutOperator(accountId, sessionFamilyId)` → 임의 UUID) | 12 | 일치 |
| `GlobalExceptionHandler`가 `23514`를 403 `SEPARATION_OF_DUTIES`로 바꾸지 않음 | 8 | 일치 |
| 비밀번호 로그인에도 MFA 표식을 찍음(문서의 동등 변이) | 0(살아남음) | 일치 |
| MFA 로그인 표식 제거를 `:atlas-account`의 `AccountServiceTest`(단위, 23개)에 적용 | 0(살아남음) | 일치 |

결과: 트리거 분기와 MFA 검사는 변이 하나도 빠짐없이 시험을 실패시켰다(재현 ✓). 문서가 동등 변이라고 한 비밀번호 로그인 표식 변이만 살아남았다(재현 ✓, 이유는 위 문서의 설명대로 `>= enabled_at` 비교가 막는다). 모순 한 건: 위 표의 "재큐 규칙만 제거"는 문서가 시험 1건이 실패한다고 적었지만 이번에는 **2건**이 실패했다(승인 재생자가 재큐하는 시험과 재큐는 다른 운영자가 해야 한다는 시험). 문서의 같은 두 문장(재큐 행, 변이 결과 목록)을 이 값으로 고쳤다. 새 관측: `AccountServiceTest`(단위)는 MFA 로그인 표식 제거를 잡지 못하고 통합 시험만 잡는다(문서는 단위 시험이 잡는다고 쓰지 않았다).

되돌림 SQL("되돌리기 훈련")은 커밋된 시험에 없다(`git grep 'DROP COLUMN mfa_verified_at'`는 V6 머리 주석에만 걸린다). 이번에 라벨 `atlasexp=claims` PostgreSQL 18.6에서 V1~V6 적용, 되돌림 SQL 실행(컬럼 사라짐 확인), V6 재적용을 한 번 수동으로 확인했다. 자동 시험이 아니라 1회 수동 확인이다.

### 결제 쪽 변이와 앱 전체 지급 E2E

기준선(변이 없음, `--no-build-cache`): `PayPalOAuth2ClientConfigurationTest` 2, `CachingExchangeRateProviderTest` 8, `FrankfurterExchangeRateProviderWiringTest` 2, `PayPalPayoutsRestClientTest` 10, 모두 통과(합계 22). 문서는 원래 변이 문자열을 남기지 않아 같은 의도로 다시 만들어 적용했다.

| 변이 | 실패한 시험 | `cmp` |
| --- | --- | --- |
| D1: `BokEcosExchangeRateProvider`의 `@Qualifier("exchangeRateProviderDelegate")` 제거 | 1(`bokEcosProviderRemainsTheDefaultDelegateOfTheSharedCache`) | 일치 |
| D1: `FrankfurterExchangeRateProvider`의 같은 한정자 제거 | 1(`frankfurterProviderIsWrappedByTheSharedCache`) | 일치 |
| D3: 지급 OAuth 등록에 `scope(".../services/payments/payouts")`를 되살림 | 1(`payoutsRegistrationIsSeparateAndRequestsNoScope`) | 일치 |
| D2: 지급 HTTP 오류 메시지에서 `errorSummary(payload)`를 뺌 | 1(`definitiveRejectionCarriesOnlyThePayPalErrorNameAndIssueCodesNotRecipient...`) | 일치 |
| D2: 오류 코드 정규식이 `@`를 허용(이메일 모양 값이 메시지에 실림) | 1(같은 시험) | 일치 |

결과: 변이 5건 모두 지정 시험이 실패했고 복원 뒤 `cmp`가 일치한다(재현 ✓, 문서의 "한정자 제거 변이 2건 각각 1건, scope 되살림 1건, 오류 요약 제거 1건, 이메일 허용 1건"과 같다). 새 관측: `CachingExchangeRateProvider` 생성자 두 곳의 매개변수 `@Qualifier`를 지워도 시험은 통과한다(2건). 구성마다 위임 빈이 하나뿐이라 타입만으로 주입되기 때문이며, 시험이 지키는 것은 구성 요소 선언의 한정자(위 첫 두 행)다. 문서가 이 두 변이를 주장한 적은 없다.

D1의 기동 실패 증상(`No qualifying bean of type ...BokEcosExchangeRateProvider`)은 이전 에이전트의 기동 로그 인용이라 이번에 재현하지 못했다. 시도: #680의 `exchange` 패키지 수정 hunk 3개를 되돌리고 시험을 돌렸으나 현재 시험이 수정 후 생성자 시그니처를 써서 `compileTestJava`가 실패했다(수정 전 코드에 새 시험을 얹을 수 없다). 수정 전 앱 기동은 전체 스택이 필요한 확인이라 이번에 하지 않았다. 증상 기록은 `local-only`로 남기고, 수정은 위 한정자 변이 2건이 지킨다.

### 앱 전체 지급 E2E(실제 Sandbox, 15/15)

`load-tests/paypal-real-fault/run-local.sh`를 `REAL_SANDBOX_OPT_IN=YES PAYOUT_E2E=YES`로 3번 돌렸다(문서는 실행 명령을 적지 않았고 이 하네스의 지급 모드는 한 번에 5건으로 고정이다: 등록된 개인 계정 수취 3건 `SUCCESS` 기대, 새 이메일 수취 2건 `UNCLAIMED` 기대). 앱은 이 브랜치의 `bootJar`, PostgreSQL·Toxiproxy·Mailpit 컨테이너(`atlasexp=claims`, 하네스가 종료 때 제거, 남은 것 0), 실제 PayPal Sandbox였다. 통과·실패만 보는 확인이라 호스트 잠금 없이 `nice`로 돌렸다.

| 관측 | 결과(3회, 지급 15건) | 문서 주장 |
| --- | --- | --- |
| 기대 종결 상태 | 15/15: `SUCCESS` 9, `UNCLAIMED` 6. 3회 모두 종료 코드 0, `payoutE2E verified` | 15/15(`SUCCESS` 9, `UNCLAIMED` 6) ✓ |
| journal | `SUCCESS` 9건에서만 각 1건(4줄, 균형), `UNCLAIMED` 6건은 0건. 예약 금액은 `SUCCESS` 0, `UNCLAIMED` 10,000원 유지 | "journal은 `SUCCESS`에서만 1건" ✓ |
| 승인 재생 응답 | 202 15/15, 재생 뒤 outbox·승인 행·provider 배치 ID 변화 없음 15/15 | 202 15/15 ✓ (불변은 문서가 a6·a7 10/10으로 적었고 이번은 15/15) |
| 항목 직접 조회 | 15/15에서 항목 1개, 금액 7.44 USD, 수수료 0.15 USD, `sender_item_id` 일치 | a6·a7 10/10의 금액·수수료 대조 ✓ |

재현 ✓: 문서의 E2E 주장 전부가 같은 값으로 다시 나왔다. 다시 실행하지 못한 것: PayPal 호출의 정확한 횟수(앱 로그에 provider 호출 줄이 없고 하네스가 세지 않는다. 지급 생성·조회·상세 조회가 지급 건수에 비례한다는 사실만 알고 있다). 문서의 a1~a4 실패(`sql()` 열 밀림, 403 `NOT_AUTHORIZED`)는 현재 코드에서 재현되지 않으며 이는 수정이 들어갔다는 뜻과 같다. 새로 만든 Sandbox 데이터: 지급 15건(각 7.44 USD 상당, 수수료 0.15 USD).

### 확신도 갱신과 문장 정정

- 판정표의 "Q-013 운영자 MFA 세션"과 "Q-013 money-moving 4-eyes" 행: 변이 실패 확인을 위 두 표로 다시 확인했다(재현). 확신도는 바꾸지 않는다.
- 판정표의 "지급 OAuth scope, 오류 사유 가시성, Frankfurter 기동" 행과 D3 서술: "payouts scope 요청이 토큰을 `openid`로 줄인다"는 `https://uri.paypal.com/services/payments/payouts` 문자열에 한정한다는 단서를 이 문서·ADR-0057·ADR-0078 문장에 붙였다. `/services/`가 없는 문자열은 payouts scope를 유지한다(Sandbox, 2026-11-26, n=1).
- 호출 횟수(Sandbox REST, EXP-103 재실행과 합산한 토큰 요청 33번을 포함): 지급 생성 10(201 3, 403 3, 400 4), 지급 조회 5(200), 그 밖 호출은 EXP-103 재실행 절에 적었다(전체 526). 만든 Sandbox 데이터: 지급 3건 USD 0.01(구매자 계정 2, 미등록 이메일 1).
