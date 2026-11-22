# ADR-0052: 정산 원장을 4계정 구조로 확장하고 지급을 상태 기계로 관리한다

- 상태: Accepted — 구현 추적: [#432](https://github.com/Neo1228/ATLAS/issues/432)
- 날짜: 2026-11-20
- 관련 결정: [ADR-0011](ADR-0011-gross-capture-settlement-ledger.md), [ADR-0013](ADR-0013-pre-shipment-refund-recording.md), [ADR-0016](ADR-0016-buyer-wide-cart-multi-seller-checkout.md)

## 맥락

정산 원장은 capture 와 refund 만 기록했다. `settlement_entry` 는 `PSP_CLEARING`·`SELLER_PAYABLE` 두 계정만 썼고, ADR-0011 이 **"수수료, 보류금, 순지급액, 지급 일정·실행, 실제 송금"** 을 미구현으로 명시했다. 판매자는 "얼마가 팔렸다(gross)"만 볼 수 있었고 **"수수료를 떼면 얼마를 언제 받는가"** 에 답할 수 없었다.

## 결정

### 1. 4계정 복식부기

| 계정 | 의미 | 차변 | 대변 |
|---|---|---|---|
| `PSP_CLEARING` | PSP 에서 받을 돈 | refund | capture |
| `SELLER_PAYABLE` | 판매자에게 줄 돈 | refund 잔여분, 지급 확정 | capture 잔여분 |
| `PLATFORM_FEE` | 플랫폼 수수료 수익 | — | capture 시 수수료 |
| `SELLER_RESERVE` | 보류금 | refund 우선 차감 | capture 시 보류 |

capture 분개:

```text
PSP_CLEARING   차변 gross
SELLER_PAYABLE 대변 (gross - fee - reserve)
PLATFORM_FEE   대변 fee
SELLER_RESERVE 대변 reserve
```

차변 합 = 대변 합 = gross. 금액이 0 인 계정은 entry 를 만들지 않으므로, 요율이 0 이면 기존 두 계정 구조와 동일해진다(`SettlementZeroRateIntegrationTest` 로 고정).

### 2. 정책은 capture 시점에 스냅샷

수수료율·보류율은 `atlas.settlement.fee-rate-bps` / `reserve-rate-bps` 로 주입하고, 적용 결과와 **적용 요율**을 `settlement_journal` 에 함께 기록한다. 정책을 바꿔도 과거 분개는 재계산되지 않는다 — 환율 스냅샷(ADR-0030)과 같은 원칙이다.

버림(floor)을 쓴다. 반올림하면 판매자 몫이 gross 를 넘거나 플랫폼이 원 단위 미만을 임의로 가져갈 수 있다.

### 3. refund 는 보류에서 먼저 차감

보류금은 환불·분쟁 대비 재원이다. refund 가 발생하면 `SELLER_RESERVE` 를 먼저 차감하고 부족분만 `SELLER_PAYABLE` 에서 뺀다. 보류가 충분하면 지급 가능 잔액은 줄지 않는다.

### 4. 지급은 별도 상태 기계

```text
REQUESTED → APPROVED → SENT → CONFIRMED (또는 FAILED)
```

- `settlement_payout` 이 상태를 소유하고, `(seller_id, period_start, period_end)` unique 로 같은 기간 중복 지급을 막는다.
- 전이는 **조건부 UPDATE** 로만 한다(`WHERE state = expected`). 두 replica 가 동시에 확정해도 하나만 성공한다.
- 지급 확정 시 `SELLER_PAYABLE` 차변 / `PSP_CLEARING` 대변 분개를 **같은 transaction** 에서 기록한다. 원장 밖 상태로만 두면 statement 잔액이 영원히 줄지 않는다.
- 지급 금액은 호출자가 정하지 않는다. **원장 잔액이 결정**한다(보류 제외).

### 5. 미확정 지급은 잔액을 예약한다

지급 journal 은 확정 시점에만 기록된다. 따라서 확정 전 지급이 잔액을 예약하지 않으면, 서로 다른 기간의 요청이 같은 잔액을 각각 예약해 총 지급액이 잔액을 넘는다.

- `payable = 원장 SELLER_PAYABLE 잔액 − (REQUESTED/APPROVED/SENT 합계)`
- 같은 seller 의 지급 요청은 `pg_advisory_xact_lock` 으로 직렬화해 잔액 확인과 예약 사이의 경쟁을 막는다.
- 같은 (seller, 기간) 또는 같은 idempotency key 재요청은 **잔액 검사보다 먼저** 기존 지급을 돌려준다. 그렇지 않으면 첫 요청이 예약한 잔액 때문에 재요청이 "잔액 없음"으로 실패한다.

## 검증 (실제 PostgreSQL, mock 없음)

`SettlementFeeReservePayoutIntegrationTest` 가 실제 분개를 쓰고 합계를 직접 계산한다.

| 시나리오 | 검증 내용 |
|---|---|
| capture 분해 | fee/reserve/seller 몫, 차변 합 = 대변 합, journal 요율 스냅샷 |
| checkout 다중 seller | seller 별 자기 몫, journal 전체 복식부기 |
| refund 순서 | 보류 우선 차감, 잔여는 payable |
| 지급 요청 | 금액은 원장이 결정, 같은 기간 중복 요청은 기존 지급 반환 |
| 지급 확정 | 분개 생성, payable 0, 멱등 |
| 동시 확정 | journal 1건만 생성 |
| 잔액 예약 | 다른 기간 요청이 같은 잔액 이중 예약 불가, 동시 요청도 1건 |
| 0% 요율 | 기존 두 계정 구조 유지(회귀) |

`SettlementPolicyTest` 가 반올림 경계와 정책 검증을 고정한다.

## 대안과 trade-off

| 대안 | 채택하지 않은 이유 |
|---|---|
| 지급을 원장 밖 상태로만 관리 | statement 잔액이 줄지 않아 "지급했는데 아직 줘야 할 돈"이 남는다 |
| 수수료를 별도 테이블로 | 복식부기 항등식이 깨지고 잔액 계산이 두 곳으로 갈라진다 |
| 지급 금액을 호출자가 지정 | 잔액보다 큰 지급을 요청할 수 있다 |
| 지급 전 잔액을 materialized view 로 | 환불·수수료 변경 시 재계산 시점이 어긋난다. 항상 entry 에서 계산하는 편이 정확하다 |
| 외부 송금 API 연동 | 송금 주체·정산 주기가 정해지지 않았다. 이 ADR 은 원장·상태까지 책임지고, 실제 송금은 별도 경계로 둔다 |

## 한계

- **실제 송금은 이 결정 당시 구현하지 않았다.** 당시 원장은 provider 송금 결과를 가장하지 않았고 상태 기계만 정의했다. 후속 Sandbox-only provider execution과 reconciliation 계약은 [ADR-0057](ADR-0057-financial-operation-outbox-and-payout-reconciliation.md)에서 결정하며, Live/production 송금은 별도 출시 gate로 둔다.
- 수수료율·보류율 기본값은 0 이다(기존 동작 보존). 실제 값을 정하려면 정산 정책 결정이 선행돼야 한다.
- 지급 주기(월/주) 자동화는 없다. 기간은 호출자가 지정한다.
- 과거 checkout journal 의 seller 귀속은 V52 가 `settlement_seller_allocation` 으로 보완하지만, 운영 데이터 규모에서의 성능은 측정하지 않았다.
