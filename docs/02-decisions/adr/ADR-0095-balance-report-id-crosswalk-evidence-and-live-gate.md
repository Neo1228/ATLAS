# ADR-0095: 잔액 보고서 대사의 ID 규칙은 실제 Sandbox 거래 기록으로 교차 검증하고, 실제 보고서 확인은 Live gate로 분리한다

- 상태: 채택(제안 PR, 병합은 통합 담당). V1 범위 완료. Live gate(LG-1)는 아래 기준으로 닫는다.
- 날짜: 2026-11-26
- 관련: [ADR-0026](ADR-0026-paypal-sandbox-payments.md)(D-085/D-087), [ADR-0033](ADR-0033-paypal-balance-report-reconciliation-history.md), [EXP-103](../../03-experiments/EXP-103-balance-report-id-crosswalk-real-sandbox.md), [EXP-081](../../03-experiments/EXP-081-paypal-double-vs-sandbox.md)
- 추적: [Q-006](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-170](../../00-governance/decision-log.md)

## 문제

Q-006의 대사 규칙(`Sale: Record ID == provider_capture_id`, `Refund: Record ID == provider_refund_id`이고 `Related Record ID == capture_id`)은 공개 문서와 직접 쓴 fixture에만 기댔다. 상태가 "실 merchant report 확인 대기(◐)"라서 무엇이 확인됐고 무엇이 못 하는 일인지, 언제 닫히는지가 적혀 있지 않았다.

## 결정

1. **ID 규칙의 근거를 실제 Sandbox 거래 기록으로 교차 검증한 것까지로 닫는다.** Transaction Search(`GET /v1/reporting/transactions`)가 돌려주는 `transaction_id`가 Payments v2의 capture ID, refund ID와 같고, 환불 기록의 `paypal_reference_id`(`paypal_reference_id_type=TXN`)가 원 capture ID와 같음을 확인했다([EXP-103](../../03-experiments/EXP-103-balance-report-id-crosswalk-real-sandbox.md)의 집계 표). Balance report의 `Record ID`·`Related Record ID` 정의("거래 고유 ID", "환불이 가리키는 원 판매 거래 ID")와 의미가 같다. 두 산출물의 ID가 같다는 PayPal의 보증은 여전히 없으므로, 이 확인은 규칙의 ID 쪽 근거이고 실제 보고서 확인을 대신하지 않는다(결정 3).
2. **파서를 공식 열 구성에 맞춘다(코드 변경).** 공식 샘플 CSV 전체를 파서에 넣었더니 수정 전에는 보고서 전체가 거절됐다. 원인은 `Fees/Scheme Fees` 행의 `Record ID`와 `Gross Transaction Amount`가 비어 있는 것이다. 또 순액·수수료를 문서에 없는 열 이름(`Net amount`, `Total fees net billed`)에서만 읽어 공식 열(`Net Transaction Amount`, `Total Net Fees`)은 항상 null이었다. 수정: 공식 열 이름도 읽는다. Record ID·총액이 비어도 되는 것은 `Sale`·`Refund`가 아닌 종류뿐이고 그 행은 `UNMATCHED`로 남는다. 빈 Record ID는 서로 `DUPLICATE`로 묶지 않는다. `Sale`·`Refund`의 ID·총액 필수 규칙은 그대로다.
3. **실제 merchant Balance report 확인은 Live gate LG-1로 분리한다.** 이 저장소의 Sandbox 자격 증명으로는 Balance Reconciliation CSV 자체를 받을 수 없음을 확인했다(외부 차단, EXP-103 "결과"). V1은 CSV 없이 exact-ID 규칙(불확실하면 `UNMATCHED`, 충돌이면 `MISMATCH`, fuzzy 매칭 없음)으로 동작한다.

## Live gate LG-1 (실제 보고서 확인)

| 항목 | 내용 |
| --- | --- |
| 닫는 기준 | 실제 merchant가 받은 Balance Reconciliation CSV 한 개를 매처에 dry-run으로 넣어(결제·주문·재고·정산 변경 없음) 결과가 아래를 모두 만족한다 |
| 기준 1 | 보고서가 `INVALID_REQUEST`로 거절되지 않는다(파서 통과) |
| 기준 2 | `Sale`·`Refund` 행은 모두 `MATCHED`이거나, 그 보고서 기간에 ATLAS가 만들지 않은 거래임을 provider 기록(`GET /v2/payments/captures/{id}` 404 또는 다른 merchant 거래)으로 설명한 행이다 |
| 기준 3 | 나머지 행(`Fees`, `Withdraw money` 등)은 사유 코드 `UNSUPPORTED_PROVIDER_RECORD_TYPE`, `BANK_STATEMENT_REQUIRED_FOR_TRANSFER_ID`로 `UNMATCHED`이며 `MISMATCH`·`DUPLICATE`는 0이다 |
| 필요한 외부 입력 | (a) Report access가 켜진 merchant 계정(Live 또는 Sandbox business)의 보고서 한 개: 기간 안에 ATLAS 결제 `Sale` 1건 이상과 환불 1건 이상 포함. (b) 같은 기간의 ATLAS DB. 보고서를 받으려면 PayPal 지원을 통한 report access 활성화와 계정 설정의 Secure FTP 사용자 생성이 필요하고, 생성에 최대 48시간이 걸린다(공식 안내) |
| 기록 위치 | 결과는 EXP 새 문서에 행 종류별 집계(MATCHED/UNMATCHED 사유별 건수)로 적는다. 보고서 원본·계정 식별자는 올리지 않는다 |
| 닫히기 전 V1 동작 | 불확실한 행은 `UNMATCHED`로 남아 운영자가 본다. 잘못 `MATCHED`가 되는 경로는 없다(Record ID와 provider ID 정확 일치 + 상태·통화·부호 있는 총액 일치가 모두 필요) |
| 재검토 트리거 | Live 전환 gate([ADR-0078](ADR-0078-payout-operator-controls-production-gate.md))를 닫기 시작하는 시점, 또는 Report access가 켜진 계정을 얻은 시점 중 빠른 쪽. 이 gate는 지급 gate 11개와 별개의 대사 gate다 |

## 대안

| 대안 | 판단 |
| --- | --- |
| 아무것도 하지 않고 ◐ 유지 | 확인한 것과 못 하는 것이 섞여 읽는 사람이 완료 여부를 알 수 없다. 기각 |
| Sandbox SFTP에서 보고서를 받아 확인 | 이 저장소에는 구매자(personal) 계정 자격 증명만 있고 merchant 계정 로그인, report access 활성화, SFTP 사용자가 없다. 막힘(외부) |
| 보고서 대신 Transaction Search를 대사 원천으로 바꾸기 | 이 저장소 자격 증명으로 동작하지만(범위 `reporting/search/read`) 색인 지연이 약 1.7~2.2시간이고 대사 원천 변경은 D-085를 뒤집는 별도 결정이다. V1 범위 밖. LG-1이 반복해서 막히면 새 ADR로 다시 고려한다 |
| 공식 샘플 CSV를 fixture로 복사해 CI에서 확인 | 공식 샘플은 Sale 18건 외에는 ATLAS 거래와 짝이 없는 합성 값이라 ID 대조를 검증하지 못한다. 대신 열 구성만 합성 ID 한 건씩으로 회귀 테스트에 고정했다 |

## 사전 부검(이 결정이 실패하는 방법)

- **실제 Balance report의 Sale `Record ID`가 capture ID가 아닌 경우:** 해당 행은 `UNMATCHED`가 되고(거짓 MATCHED 없음), LG-1 기준 2에서 드러난다. 위험은 거짓 확신이 아니라 운영자 확인 건수 증가다.
- **공식 열 이름이 다시 바뀌는 경우:** 필수 열(Record ID, Record Type, 통화, 총액)이 없으면 보고서 전체를 거절하고(`INVALID_REQUEST`), 선택 열은 null로 남는다. 문서가 열 이름으로 읽으라고 안내한다.
- **빈 Record ID 행을 `Sale`·`Refund`로 위장:** 두 종류는 ID·총액이 없으면 보고서를 거절한다(테스트와 변이로 고정).

## 되돌리기

코드 변경은 커밋 하나(`git revert`)로 되돌린다. 되돌림 훈련 결과는 EXP-103 "되돌리기 훈련"에 적었다. DB 스키마 변경은 없다(`record_id`는 이미 `NOT NULL`이고 빈 문자열을 받고, `gross_amount`는 이미 nullable이다).
