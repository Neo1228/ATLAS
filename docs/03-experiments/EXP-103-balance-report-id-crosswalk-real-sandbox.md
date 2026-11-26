# EXP-103: 잔액 보고서 ID 대조 규칙을 실제 Sandbox 거래 기록과 공식 샘플 CSV로 교차 검증한다

- 질문: Q-006의 대사는 `Record ID == provider_capture_id`, 환불은 `Record ID == provider_refund_id`와 `Related Record ID == capture_id`라는 규칙에 기댄다([D-087](../00-governance/decision-log.md)). 이 규칙을 만든 근거는 공개 문서와 직접 쓴 fixture뿐이었다. 실제 PayPal Sandbox가 만든 거래의 ID가 이 규칙과 같은가. 파서가 가정한 헤더 이름은 공식 최신 CSV와 같은가.
- 상태: **완료.** 가설, 반증 조건, 결정 규칙은 실행 전에 별도 커밋(마크다운만)으로 확정했고, 결과는 그 뒤에 채웠다. Sandbox 거래 기록으로는 ID 규칙을 교차 검증했고(유형 1), 실제 Balance CSV는 이 저장소 자격 증명으로 받을 수 없음을 증명했다(유형 3). 후자의 확인은 [ADR-0095](../02-decisions/adr/ADR-0095-balance-report-id-crosswalk-evidence-and-live-gate.md)의 Live gate LG-1이 맡는다(유형 2).
- 날짜: 2026-11-26
- 관련: [EXP-081](EXP-081-paypal-double-vs-sandbox.md)(Sandbox 직접 호출 방식), [ADR-0026](../02-decisions/adr/ADR-0026-paypal-sandbox-payments.md), [ADR-0095](../02-decisions/adr/ADR-0095-balance-report-id-crosswalk-evidence-and-live-gate.md), [D-170](../00-governance/decision-log.md)

## 문제

Q-006은 "실 merchant report 확인 대기"라는 모호한 상태(◐)로 남아 있다. 확인할 수 있는 만큼 확인했는지, 못 하는 것은 왜 못 하는지가 적혀 있지 않다.

## 사전 등록에 앞서 이미 읽은 것(측정이 아니라 문서 읽기)

- [공식 Balance Reconciliation report 문서](https://developer.paypal.com/reports/balance)를 열어 열 목록을 읽었다. `Record ID`는 거래 고유 ID, `Related Record ID`는 "환불이 가리키는 원 판매 거래 ID"로 정의돼 있다. `Original Record ID` 열은 이 판에 없다. 금액 열은 `Gross Transaction Amount`, `Total Gross Fees`, `Total Net Fees`, `Net Transaction Amount`다.
- 공식 샘플 CSV(REF-124)를 내려받아 열었다. `Sale`, `Refund/Referenced`, `Refund/Non-Referenced`, `Fees/Scheme Fees` 행이 있고 풋터 열은 `File Number`, `Total Records`, `Total Files`다.
- 파서 코드는 수수료를 `total_fees_net_billed`, 순액을 `net_amount`라는 헤더에서 읽는다. 이 이름이 공식 열과 다르다는 의심이 있다(H6에서 시험한다).

## 가설과 반증 조건

| 번호 | 가설 | 반증 조건 | 지표와 기대값 | 결정 |
| --- | --- | --- | --- | --- |
| H1 | 이 저장소의 Sandbox 앱 자격 증명으로 토큰 발급 뒤 Transaction Search(`GET /v1/reporting/transactions`)가 200을 돌려준다 | 401/403/404나 `NOT_AUTHORIZED` | 상태 코드와 응답 키 집합. 기대 200 | 반증이면 ID 교차 검증을 외부 차단(유형 3)으로 적고 막은 입력(권한)을 명시한다 |
| H2 | Balance Reconciliation CSV 자체는 REST로 받을 수 없다(merchant portal/SFTP 전달) | 문서나 실제 호출에서 CSV를 돌려주는 REST 경로가 있다 | 문서 열람 결과와 후보 경로 호출 상태 | 참이면 CSV 형식은 공식 문서·샘플 근거로 두고 Live gate를 세운다 |
| H3 | 실제 Sandbox에서 authorize+capture한 건 n≥5 각각에 대해 Transaction Search의 `transaction_id`가 capture 응답 `id`와 같다 | n건 중 한 건이라도 capture ID와 같은 `transaction_id` 기록이 없다 | 일치 건수/n. 기대 5/5(0건 반증이면 규칙 상한은 3/n 규칙으로 적는다) | 일치하면 `Sale: Record ID == capture_id` 규칙의 ID 쪽이 실제 거래로 교차 검증됨 |
| H4 | 부분·전액 환불 n≥5 각각에 대해 환불 기록 `transaction_id`가 환불 응답 `id`와 같고, 기록의 원 거래 참조(`paypal_reference_id`, 형식 `TXN`)가 capture `id`와 같다 | 한 건이라도 두 값 중 하나가 다르거나 참조가 없다 | 일치 건수/n. 기대 5/5 | 일치하면 `Refund` 행 규칙(Record ID == refund_id, Related == capture_id)이 교차 검증됨 |
| H5 | 음성 통제: 조작한 ID(실제 capture ID의 마지막 글자 변경)와 최상위 Order ID는 어떤 `transaction_id`와도 같지 않다. 매처에서는 `UNMATCHED`가 된다 | 조작한 ID나 Order ID가 기록과 일치한다 | 일치 건수. 기대 0 | 0이 아니면 탐지기가 못 막는 것이므로 규칙을 고친다 |
| H6 | 파서는 공식 샘플 CSV 전체를 오류 없이 읽고, 수수료·순액 열을 값 있는 행에서 채운다 | 파싱이 실패하거나 수수료·순액이 값이 있는데도 모든 행에서 null이다 | 읽은 행 수와 수수료·순액 non-null 행 수. 기대: 읽기 성공, non-null 행 수 > 0 | 거짓이면 헤더 이름을 고치고 테스트 + 변이 검증 |
| H7 | 샘플 CSV의 `Refund/Non-Referenced`, `Fees/*` 행은 매처에서 `MATCHED`가 되지 않고 이유가 붙은 `UNMATCHED`가 된다(오탐 0) | 이 행 중 하나라도 `MATCHED` | `MATCHED` 건수. 기대 0 | 0이 아니면 매처를 고친다 |

## 통제

- 변경 없음 통제: 같은 샘플 CSV를 수정 전 파서로 먼저 돌려 H6 수치를 얻는다.
- 음성 통제: H5(조작 ID, Order ID), 그리고 헤더 이름을 일부러 틀린 CSV로 H6 탐지기가 불일치를 잡는지.
- 같은 환경 전후 비교: 같은 CSV, 같은 JVM에서 수정 전후.

## 표본 크기와 한계

- H3, H4는 n=5 이상. 0/n이 나오면 규칙 3으로 상한(3/n)을 적는다. 5/5는 "이 Sandbox 계정에서 5건이 모두 같았다"는 뜻이지 모든 merchant 설정에서 같다는 증명이 아니다.
- Sandbox 거래 기록이 Transaction Search에 나타나기까지 지연이 있을 수 있다(공식 문서는 최대 수 시간을 안내한다). 지연은 제한된 시간 안에서 재조회하고, 끝내 안 나오면 "기록이 나타나지 않음"으로 적는다. 이를 일치로 해석하지 않는다.
- Transaction Search는 Balance report와 다른 산출물이다. 같은 거래를 두 곳에서 같은 ID로 부른다는 보장은 PayPal 문서가 하지 않는다. 이 실험이 닫는 것은 "ATLAS가 저장하는 capture/refund ID가 PayPal이 거래 기록에 쓰는 ID와 같다"까지다.

## 결정 규칙(실행 전 확정)

1. H1이 참이고 H3, H4가 참: Q-006을 `✓ V1`로 바꾸고 ID 대조 규칙의 근거를 실제 Sandbox 거래 레코드로 적는다.
2. H2가 참: CSV 형식은 문서·샘플 근거로 두고, "첫 실제 merchant 보고서를 dry-run으로 매처에 넣어 모든 행이 `MATCHED`이거나 이유가 설명됨"을 기준으로 하는 Live gate를 만든다.
3. H6, H7이 반증되면 코드를 고친다(테스트가 변이 검증에서 실패해야 한다).
4. H1이 반증되면 필요한 외부 입력(권한)을 적고 V1은 그 없이 위 2의 상태로 진행한다.

## 환경과 방법

- 대상: `api-m.sandbox.paypal.com`과 공식 문서 호스트만. 스크립트는 다른 base URL이면 중단한다. Live endpoint는 부르지 않았다. 자격 증명은 git 제외 `.env.local`에서 프로세스 안에서만 읽었고 출력하거나 문서에 옮기지 않았다.
- 새 Sandbox 결제: `intent=AUTHORIZE` 주문 생성(ATLAS 본문과 같은 `payment_source.paypal.experience_context`) → Sandbox 구매자 계정으로 headless Chromium이 승인 화면을 눌러 승인(`load-tests/paypal-real-fault/real-flow.mjs`의 화면 순서) → authorize → capture(`final_capture=true`, `Prefer: return=representation`) → refund. 7건(금액 7.01~7.67 USD), 환불 8건(전액 4, 부분 4: 1.00 두 건, 0.50과 0.75). 호출은 모두 2xx(201)였다. 호출 스크립트는 1회용이라 올리지 않았다(`real-flow.mjs`는 ATLAS 앱을 거치는 하네스이고 이 실험은 앱 없이 직접 호출했다).
- 거래 기록 조회: `GET /v1/reporting/transactions?fields=all&page_size=100`(자격 증명 범위 `uri.paypal.com/services/reporting/search/read` 포함). 응답의 `transaction_info.transaction_id`, `transaction_event_code`, `paypal_reference_id`, `paypal_reference_id_type`, `transaction_amount`를 읽었다.
- 기존 기록: 같은 Sandbox 앱이 이전 실험(EXP-086, EXP-094 등)에서 만든 거래가 30일 창에 133건 색인돼 있다. 이 중 `T0006`(결제 수취) 109건과 `T1107`(환불) 5건을 읽기 전용으로 Payments v2(`GET /v2/payments/captures/{id}`, `GET /v2/payments/refunds/{id}`)와 대조했다. 이 대조는 색인 지연을 피하려고 **사전 등록 뒤에 추가한 보강**이다(아래 교란 요인).
- 공식 샘플: REF-124의 CSV를 내려받아 바이트 수준으로 확인했다(UTF-8, BOM 없음, CRLF 43개 = 44행, 행마다 73열). 파서에 그대로 넣고 서비스에는 적대적 후보(모든 ID에 확정된 CAPTURE 후보)를 줘서 `MATCHED`가 어디까지 번지는지 봤다.
- 새로 설치한 도구: 없음. 이미 있던 node v26.13.1, Playwright 1.64.0(저장소 하네스 의존성), curl 8.7.1, Python 3.15.2의 `csv`로 읽기만 했다. 컨테이너는 만들지 않았다(PostgreSQL 통합 테스트는 Gradle이 쓰는 Testcontainers, 종료 뒤 자동 제거).
- 시간은 재지 않았다(공유 머신). 판정은 건수와 불변식으로만 한다.

## 결과

### H1, H2: 접근 가능성

| 시도 | 결과 |
| --- | --- |
| 토큰 발급 | 200. 키: `access_token, app_id, expires_in, nonce, scope, token_type`. 범위에 `reporting/search/read`가 있다 |
| `GET /v1/reporting/transactions` | 200. 키: `account_number, end_date, last_refreshed_datetime, links, page, start_date, total_items, total_pages, transaction_details`. 항목 키: `auction_info, cart_info, incentive_info, payer_info, shipping_info, store_info, transaction_info` |
| `GET /v1/reporting/balances` | 200. 키: `account_id, as_of_time, balances, last_refresh_time` |
| `GET /v1/reporting/reports`, `/v1/reporting/balance-reconciliation`, `/v2/reporting/reports`, `/v1/reports` | 모두 **404**, 본문 키 없음(빈 객체) |
| 공식 문서 [balance](https://developer.paypal.com/reports/balance) | 보고서는 "PayPal website and the SFTP server"로 제공된다. REST 경로 설명 없음. 하루 1회, 현지 정오 전에 생성 |
| 공식 문서 [access](https://developer.paypal.com/reports/access) | live와 sandbox 모두 SFTP(`reports.sandbox.paypal.com`)로 받는다. 전제: PayPal 지원에 report access 활성화 요청, 계정 설정의 Secure FTP에서 사용자 생성(최대 48시간) |
| `reports.sandbox.paypal.com:22` | TCP 연결 성공, ssh-rsa 호스트 키 응답(자격 증명 없이 호스트 키만 확인). 로그인 시도는 하지 않았다(SFTP 사용자 없음) |
| Sandbox 구매자 계정 로그인 후 `/myaccount/settings/`, `/businessmanage/account/accountAccess`, `/reports/`, `/reports/dlog` | 설정 두 경로는 `/myaccount/profile/`로 리다이렉트됐다. `/reports/`, `/reports/dlog`는 그대로 열렸지만 본문(697자, 769자)에 "reports"·"Secure FTP" 문자열이 없다(n=4 경로). 구매자(personal) 계정이라 merchant 보고서 메뉴가 없다 |

판정: H1 **참**(Transaction Search 200). H2 **참**(Balance CSV를 주는 REST 경로는 문서에도 없고 후보 5개가 404). Balance CSV를 받으려면 merchant 계정으로 로그인해 report access와 SFTP 사용자를 만들어야 하는데, 이 저장소의 자격 증명은 REST 앱 키와 구매자 계정뿐이다 → **외부 차단(유형 3)**. 필요한 외부 입력은 ADR-0095 LG-1에 적었다.

### H3, H4, H5: 거래 기록 ID와 Payments v2 ID

기존 Sandbox 기록(이 앱이 만든 30일 창, 읽기 전용):

| 측정 | 값 |
| --- | --- |
| 기록 수 | 133건(`T0006` 109, `T1107` 5, 그 외 19: `T0001`, `T1105`, `T1503`, `T1900`) |
| `T0006` 109건: `GET /v2/payments/captures/{transaction_id}` 200 / `id == transaction_id` / 금액·통화 일치 / 금액 양수 | 109 / 109 / 109 / 109 |
| `T0006`의 `transaction_id`가 서로 다른 값 | 109 / 109 |
| `T1107` 5건: `GET /v2/payments/refunds/{transaction_id}` 200 / `id == transaction_id` | 5 / 5 |
| `T1107`: 절댓값 금액 일치 / 부호 음수 | 5 / 5, 5 / 5 |
| `T1107`: `paypal_reference_id`가 refund의 `up` 링크 capture ID와 같음 / 그 ID가 같은 창의 `T0006` 기록 | 5 / 5, 5 / 5 |
| `paypal_reference_id_type` | 환불 5건과 수취 `T0006` 모두 `TXN` |
| ID 모양 | `[0-9A-Z]{17}` 109 / 109 (`T0006`), 5 / 5 (`T1107`). 공식 샘플의 Record ID 35건(Sale 18, Referenced Refund 3, Non-Referenced Refund 14)과 Related Record ID 3건도 같은 모양 |
| 음성 통제(마지막 글자를 바꾼 가짜 ID) | 기록 ID 집합과 일치 0 / 114 |

실행 뒤에 새로 만든 Sandbox 결제(7건, 환불 8건):

| 측정 | 값 |
| --- | --- |
| 결제 흐름(생성 200, 승인, authorize 201, capture 201, refund 201) | 7 / 7 |
| 거래 기록이 색인된 뒤 `transaction_id == capture id` (H3) | 7 / 7(`T0006`, 금액·통화 일치 7 / 7) |
| 환불 기록 `transaction_id == refund id` / `paypal_reference_id == capture id` / 절댓값 금액 일치 (H4) | 8 / 8, 8 / 8, 8 / 8(부호 음수 8 / 8) |
| 음성 통제: 가짜 capture ID 7 + Order ID 7과 일치 (H5) | 0 / 14 |
| authorization ID가 `transaction_id`로 나타남 | 0 / 7(대신 `T0006` 기록의 `paypal_reference_id`가 authorization ID와 7 / 7 같다, 유형 `TXN`) |

H3 **참**(기존 109 / 109 + 새 7 / 7), H4 **참**(기존 5 / 5 + 새 8 / 8), H5 **참**(0 일치). 규칙 3: 0 반증 건수이므로 109건에 대해 "한 건도 어긋나지 않았다"이고, 이 Sandbox 앱 1개에서의 관측이다(모든 merchant 설정 증명 아님).

### H6, H7: 공식 샘플 CSV와 파서

| 측정 | 수정 전 파서 | 수정 후 파서 |
| --- | --- | --- |
| 공식 샘플(39행) 파싱 | **거절**(`INVALID_REQUEST`). 원인: `Fees/Scheme Fees` 4행의 Record ID와 총액이 비어 있다 | 39행 모두 읽음 |
| 수수료 non-null 행 / 순액 non-null 행(39행 중) | 해당 없음(거절). 수정 전 코드가 읽는 열 이름은 `Total fees net billed`, `Net amount`라 공식 열(`Total Net Fees`, `Net Transaction Amount`)은 항상 null이다. 합성 CSV 단위 테스트가 이를 가렸다 | 35 / 35 |
| Related Record ID 행 / Original Record ID 행 | 해당 없음 | 3 / 0 (공식 판에 `Original Record ID` 열이 없다) |
| 서비스 결과(모든 ID에 확정 CAPTURE 후보를 준 적대적 입력) | 해당 없음 | `Sale` 18 → `MATCHED` 18. `Refund/Referenced` 3, `Refund/Non-Referenced` 14 → `MISMATCH`(`PROVIDER_ID_TYPE_CONFLICT`). `Fees` 4 → `UNMATCHED`(`UNSUPPORTED_PROVIDER_RECORD_TYPE`). `DUPLICATE` 0 |
| 공식 샘플의 Referenced Refund 3건 | | 부모(Related Record ID)가 같은 파일의 `Sale` 3 / 3, 총액 음수 3 / 3 |

H6 **반증**(수정 전: 샘플 전체를 거절하고 수수료·순액 열 이름이 공식과 다르다. 기대는 "읽기 성공, non-null > 0"이었다). 수정했다. H7 **참**: 적대적 후보에서도 `Sale`이 아닌 행은 `MATCHED`가 되지 않았다(0 / 21; `Sale` 18건의 `MATCHED`는 후보 금액을 행 값으로 만든 구성상의 결과다).

### 수정과 검증

- 코드: `CsvPayPalBalanceReportParser`(공식 열 이름 허용, `Sale`·`Refund`가 아닌 행의 빈 Record ID·총액 허용), `PayPalBalanceReportReconciliationService`(빈 Record ID는 `DUPLICATE`로 묶지 않음, null 총액 비교), `PayPalBalanceReportRow`(빈 Record ID 허용, `record_id`는 DB에서 이미 `NOT NULL`이고 빈 문자열을 받는다).
- 새 테스트 2건: 서비스 단위 테스트(공식 열 이름, Sale·Referenced·Non-Referenced·Fees 두 건), PostgreSQL 통합 테스트(빈 Record ID·총액 행 저장과 재업로드 복원).
- 수정 전 코드에서 서비스 단위 테스트 실패 확인(작성 당시 픽스처, Record ID가 있는 Fees 행): `PayPalBalanceReportReconciliationServiceTest` 13개 중 새 테스트 1개 실패(수수료 단정, 열 이름 불일치). 최종 테스트 전체를 수정 전 소스에 돌린 결과는 아래 "수정 전 소스에 최종 테스트" 항목.
- 변이 검증(변이 → 대상 테스트 실패 → 되돌림 → `cmp` 동일), 6회(아래 표의 마지막 행은 두 변이가 같은 동작이라 한 행으로 합쳤다):

| 변이 | 실패한 테스트 |
| --- | --- |
| 순액 열 별칭 `net transaction amount` 제거 | 서비스 단위 1 |
| 수수료 열 별칭 `total net fees` 제거 | 서비스 단위 1 |
| 빈 Record ID를 `DUPLICATE` 묶음에 포함(`isBlank` 조건을 `true`로) | 서비스 단위 1, 통합 1 |
| `PayPalBalanceReportRow`가 빈 Record ID를 다시 거절 | 통합 1 |
| `Sale`·`Refund` 아닌 행도 ID·총액 필수(예전 규칙; `reconcilable`을 항상 참으로 바꾸는 변이와 조건식을 예전 식으로 되돌리는 변이, 2회) | 둘 다 서비스 단위 1, 통합 1 |

- 수정 후 `PayPalBalanceReport*` 테스트(서비스 단위 13, 통합 5, 보안 1, 스키마 2) 통과, 실패 0, skipped 0.

## 결정 대안 비교

| 대안 | 판단 |
| --- | --- |
| 아무것도 하지 않음(◐ 유지) | 기각. 확인한 것과 못 하는 것이 섞여 있다 |
| Transaction Search 교차 검증 + 파서 수정 + Live gate(채택) | ID 규칙 근거를 실제 기록으로 닫고, 못 하는 것은 기준 있는 gate로 분리한다 |
| merchant 계정을 만들어 SFTP 보고서 확보 | 이 저장소로는 불가(report access 활성화는 PayPal 지원 요청, 계정 로그인 필요) |
| 대사 원천을 Transaction Search로 교체 | 범위 밖. D-085 선택을 뒤집는 새 ADR이 필요하고, 색인 지연이 약 1.7~2.2시간이다 |

## 사전 부검과 되돌리기 훈련

- 부검은 [ADR-0095](../02-decisions/adr/ADR-0095-balance-report-id-crosswalk-evidence-and-live-gate.md)에 적었다. 핵심: 실제 ID가 달라도 결과는 거짓 `MATCHED`가 아니라 `UNMATCHED`다.
- 되돌리기 훈련(실제 수행): 코드 커밋을 임시 브랜치에서 `git revert`한 뒤 `PayPalBalanceReport*`를 실행했다. 되돌린 테스트 19개 통과, 공식 샘플 탐침은 다시 실패(거절). 브랜치를 지우고 원래 브랜치로 돌아와 변경이 그대로임을 확인했다.

## 교란 요인

| 요인 | 영향과 처리 |
| --- | --- |
| Transaction Search 색인 지연 | `last_refreshed_datetime`이 현재보다 102~132분 뒤처졌다(관측 30여 회, 30분 단위로 갱신). 새 결제(00:19~00:20 UTC)는 약 2시간 뒤(02:17 UTC, 갱신 시각 00:29:59Z)에 처음 조회됐고 그 전 조회는 404(`Data for the given start date is not available`) 또는 0건이었다. 처리: 지연을 이유로 불일치로 읽지 않았고, 기존 기록(109건)을 보강했으며, 새 결제는 색인이 따라잡은 뒤(약 2시간 뒤) 같은 스크립트로 다시 조회했다(한 번 더 조회해 15/15 확인). 사전 등록에는 "수 시간 지연 가능"만 있었고 기존 기록 보강은 실행 중에 추가했다 |
| Sandbox 계정 1개, 앱 1개 | 결과는 이 Sandbox merchant 설정에서의 관측이다 |
| 모든 거래가 USD, 카드가 아닌 PayPal 지갑 결제 | Balance report의 카드·다통화·fee 행은 이 실험에서 다루지 않았다 |
| 공식 샘플은 PayPal이 만든 합성 파일 | 열 구성 근거다. ID 대조 근거가 아니다 |
| HTTP 수준 캡처(mitmproxy 등) | 쓰지 않았다. 필요한 정보(상태, 키 집합, 필드 값)가 응답 본문에서 직접 얻어졌고, 같은 정보를 TLS 복호화로 더 얻을 것이 없었다 |

## 주장과 증거 (주장 표)

| 주장 | 증거 | 확신도 |
| --- | --- | --- |
| Sandbox 거래 기록의 `transaction_id`는 Payments v2 capture ID와 같다 | 기존 109 / 109 + 새 7 / 7, 가짜 ID 0 / 114 일치 | confirmed |
| 환불 기록의 `transaction_id`는 refund ID, `paypal_reference_id`는 원 capture ID다 | 기존 5 / 5 + 새 8 / 8 | confirmed |
| 위 두 줄이 Balance report의 `Record ID`·`Related Record ID`에도 그대로 적용된다 | 공식 문서 정의와 ID 모양 일치(공식 샘플 Record ID 35건과 Related Record ID 3건 모두 `[0-9A-Z]{17}`)만 있고, 같은 ID라는 PayPal 보증은 없다 | decided(LG-1이 실제 보고서로 닫는다) |
| 파서는 공식 샘플 39행 전체를 읽고 `Sale` 외 행은 `MATCHED`가 되지 않는다 | 파서 수정 후 탐침 39행, 단위 테스트, 변이 6건 | confirmed(로컬 테스트, 공식 샘플 1개) |
| Balance Reconciliation CSV는 이 저장소 자격 증명으로 받을 수 없다 | REST 후보 5개 404, 공식 문서의 전달 경로(웹·SFTP), 구매자 계정에 Secure FTP 메뉴 없음, SFTP 사용자 없음 | external-blocked(증거: 위 표) |
| 실제 merchant 보고서의 열 구성이 공식 샘플과 같다 | 공식 문서가 "계정 설정에 따라 다를 수 있다"고 명시 | decided(LG-1 기준 1) |
| Transaction Search는 실시간이 아니다 | 지연 102~132분, 관측 30여 회 | local-only |

## 하지 않은 것

- 실제 merchant Balance report 수신과 매처 dry-run: LG-1(외부 입력 필요).
- Sandbox SFTP 로그인: 사용자가 없다. 호스트 키 응답만 확인했다.
- 카드, 다통화, 수수료·정산 행의 실제 값 확인.
- Payments API 호출 중 앱(`PayPalRestClient`)을 거친 경로: 이 실험은 직접 호출이다.

## 삭제한 파일의 내용

저장소에 올리지 않은 것: 1회용 호출 스크립트(`flow.mjs`, `search.mjs`, `existing.mjs` 등)와 원자료(`raw_flow.json`, `raw_search.json`, `raw_existing30.json`)는 `/tmp` 아래에 로컬로만 있다. 원자료에는 거래 ID, 구매자 정보가 들어 있어 올리지 않는다. 위 표가 집계본이다.

- 새 결제 호출 순서와 본문: 주문 `{intent: AUTHORIZE, purchase_units: [{reference_id, amount: {currency_code: USD, value}}], payment_source: {paypal: {experience_context: {return_url, cancel_url, user_action: PAY_NOW}}}}` → authorize 본문 `{}` → capture 본문 `{amount, final_capture: true}`와 `Prefer: return=representation` → refund 본문 `{}`(전액) 또는 `{amount}`(부분). 호출마다 새 `PayPal-Request-Id`.
- 거래 기록 조회: `GET /v1/reporting/transactions?start_date=<ISO>&end_date=<ISO>&fields=all&page_size=100&page=<n>`. 날짜 형식 `YYYY-MM-DDTHH:MM:SS-0000`.
- 샘플 열 분석 명령: `csv.reader`로 `RD` 헤더의 `Record Type`/`Record Subtype`별 건수와 Record ID 모양(`[0-9A-Z]{17}`)을 셌다. 출력: Sale 18, Refund/Referenced 3, Refund/Non-Referenced 14, Fees/Scheme Fees 4(Record ID 빈 값).
