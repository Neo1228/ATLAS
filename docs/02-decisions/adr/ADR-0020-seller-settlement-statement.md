# ADR-0020 — Seller 내부 정산 명세 조회

- 상태: 승인
- 결정: D-075
- 추적: [Issue #163](https://github.com/Neo1228/ATLAS/issues/163)
- 요구사항: [FR-003 / Q-006](../../01-requirements/requirements-index.md)
- 관련 결정: [D-066 / D-068 / D-071](../../00-governance/decision-log.md)
- 관련 ADR: [ADR-0011](ADR-0011-gross-capture-settlement-ledger.md), [ADR-0013](ADR-0013-pre-shipment-refund-recording.md), [ADR-0016](ADR-0016-buyer-wide-cart-multi-seller-checkout.md)

## 맥락

`settlement-module`은 capture journal, BuyerCheckout의 Seller별 allocation, 배송 전 local refund reversal을 기록하지만 Seller 명세를 조회하는 경로는 없음. Multi-Seller checkout의 gross를 Seller별로 중복 계산하지 않으며, 보존된 posting fact를 읽는 경로가 필요.

ATLAS는 local ledger simulation. PSP evidence 대조, Seller 지급, 실제 자금 이동을 제공하지 않음.

## 결정

- Read-only endpoint: `GET /api/sellers/{sellerId}/settlement-statement`.
- 필수 기간은 UTC Instant `[from,to)`. `from < to`; 기간 기준은 업무 event 시각이 아닌 `settlement_journal.recorded_at`.
- 한 Seller의 원장 movement를 한 row로 표시:
  - Campaign Order capture: Seller journal gross를 양수.
  - BuyerCheckout capture: parent checkout gross가 아닌 해당 Seller의 `settlement_seller_allocation`만 양수.
  - Refund reversal: 원 capture를 수정하지 않고 음수 row 추가.
- Summary는 전체 기간 기준. `capturedAmountWon`은 양수, `refundReversalAmountWon`은 음수, `netLedgerChangeWon`은 두 값의 합. Opening/closing balance나 실제 지급액은 표시하지 않음.
- 기간 합계는 `BigInteger` 사용. PostgreSQL `SUM(BIGINT)`의 `numeric` 결과는 여러 유효 row 합이 개별 `BIGINT` 범위를 넘을 때도 정밀도 보존. Row 금액은 저장 범위에 맞춰 `long` 유지.
- Row order는 `(recorded_at ASC, journal_id ASC)`. Cursor v1은 Seller/from/to/마지막 `recorded_at`·`journal_id`에 결합; page size default `10`, maximum `100`. Limit 변경은 허용.
- 각 GET은 read-only PostgreSQL `REPEATABLE_READ` transaction에서 page rows와 전체 period totals를 함께 조회. 조회는 journal/allocation만 읽고 payment·Inventory·Order를 변경하지 않음.
- Cursor는 stateless. 여러 HTTP page 요청은 각각 새 DB snapshot이므로 요청 사이 새 posting이 발생하면 후속 page 또는 totals가 달라질 수 있음. 이 API는 immutable close/report가 아니며, 전체 기간 재조회는 그 시점의 현재 ledger view를 제공.
- Seller 존재 여부를 Catalog에 질의하지 않음. 해당 기간 row가 없는 synthetic sellerId는 빈 page와 0 totals. `sellerId`는 인증·인가·tenant isolation이 아님.
- 현재 schema에 신규 index/migration 추가 없음. Query plan과 representative local data가 필요성을 보일 때만 query index를 검토. 성능·용량 주장은 하지 않음.

## 이유와 대안

| 선택 | 대안 | 이유·절충 |
|---|---|---|
| `recorded_at` 기준 | Order 생성·Payment event 시각 | 내부 명세의 기록 시점을 재현 가능하게 유지; 서로 다른 업무 시각을 혼합하지 않음 |
| Seller allocation 기준 | checkout gross를 각 Seller에 반복 | 다중 Seller에서 총액 중복을 방지; allocation 합은 원 checkout gross와 대응 |
| Signed ledger movement | 잔액·payout 추정 | 현재 데이터는 internal receivable/payable posting뿐; bank/PSP settlement evidence 없음 |
| Stateless keyset cursor | offset, persistent statement snapshot | `recorded_at`과 journal ID로 deterministic query; 서버 측 snapshot 저장은 local read path의 범위를 넘으며 cross-request 고정은 보장하지 않음 |
| 기존 PostgreSQL/JdbcTemplate | 별도 reporting DB·Search·broker | 현재 ledger source와 transaction 경계를 재사용. Query plan 증거 전 추가 schema·infra 금지 |

## 결과와 한계

- Settlement module이 statement read use case, validation, cursor, persistence projection을 소유. Application은 HTTP binding과 Service 호출만 담당.
- `SettlementException.INVALID_REQUEST`는 공통 `ProblemDetail` mapping으로 HTTP 400.
- Page와 totals는 한 요청 안에서 일관되지만 여러 page 요청을 하나의 고정 snapshot으로 묶지 않음. 동시에 새로 기록되는 movement가 있으면 전체 기간 재조회가 필요.
- Read-only query는 기존 journal/idempotency/posting transaction을 변경하지 않음. 신규 외부 dependency, migration, index 없음.
- Seller 인증/tenant isolation, payout, external PSP/bank reconciliation, fee/reserve, tax, accounting correction은 미정 또는 제외.
- PostgreSQL integration tests verify capture, Seller allocation, refund reversal, period boundary, cursor paging/scope, aggregate beyond one row's long range, and read-only behavior. 성능 실측·부하 시험은 수행하지 않음.

## 외부 참고자료

- [REF-121](../../00-governance/reference-register.md): Meituan 여행 정산 플랫폼의 명세·대사 흐름만 비교. 해당 시스템의 규모, broker, 분산 아키텍처는 ATLAS에 적용하지 않음.
