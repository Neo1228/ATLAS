# EXP-103: 잔액 보고서 ID 대조 규칙을 실제 Sandbox 거래 기록과 공식 샘플 CSV로 교차 검증한다

- 질문: Q-006의 대사는 `Record ID == provider_capture_id`, 환불은 `Record ID == provider_refund_id`와 `Related Record ID == capture_id`라는 규칙에 기댄다([D-087](../00-governance/decision-log.md)). 이 규칙을 만든 근거는 공개 문서와 직접 쓴 fixture뿐이었다. 실제 PayPal Sandbox가 만든 거래의 ID가 이 규칙과 같은가. 파서가 가정한 헤더 이름은 공식 최신 CSV와 같은가.
- 상태: **사전 등록(실행 전).** 아래 가설, 반증 조건, 결정 규칙은 실행 전에 확정했다. 결과 절은 실행 뒤에 채운다.
- 날짜: 2026-11-26
- 관련: [EXP-081](EXP-081-paypal-double-vs-sandbox.md)(Sandbox 직접 호출 방식), [ADR-0026](../02-decisions/adr/ADR-0026-paypal-sandbox-payments.md), [ADR-0095](../02-decisions/adr/ADR-0095-balance-report-id-crosswalk-evidence.md), [D-170](../00-governance/decision-log.md)

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
