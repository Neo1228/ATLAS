# ADR-0057: 금융 provider 작업을 durable operation으로 실행하고 증거로 원장을 확정한다

- 상태: Accepted — Sandbox-only 구현. Live 송금은 별도 출시 gate.
- 날짜: 2026-11-22
- 관련: [ADR-0026](ADR-0026-paypal-sandbox-payments.md), [ADR-0029](ADR-0029-order-payment-recovery-contract.md), [ADR-0030](ADR-0030-paypal-currency-boundary.md), [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md), [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)
- 추적: [D-123](../../00-governance/decision-log.md#d-123), [Issue #456](https://github.com/Neo1228/ATLAS/issues/456)

## 문제와 RDD 기준선

ATLAS는 buyer capture·refund 결과를 provider resource ID, idempotency key, `UNKNOWN`, durable recovery job으로 재조정한다. 반면 Seller payout은 내부 상태 전이만 있고 외부 송금 요청 ID·provider status·recipient snapshot이 없다. `SettlementPayoutService.fail()`은 `SENT`도 `FAILED`로 전이할 수 있고, `pendingAmount()`는 `FAILED`를 예약액에서 제외했다. 외부 송금 뒤 응답이 모호한 경우 실패 처리하면 같은 payable을 새 지급으로 재예약할 수 있는 코드 경로다.

새 PostgreSQL 통합 회귀 테스트 `sentPayoutCannotBeFailedWithoutProviderEvidence`를 먼저 추가했다. 변경 전 실제 PostgreSQL에서 기대한 conflict가 발생하지 않아 실패했고, `SENT → FAILED`를 막은 뒤 payout reservation·same-period replay 및 기존 pre-send 실패 재시도 테스트를 포함한 13개 PostgreSQL integration tests가 통과했다. 이는 실제 중복 송금 발생의 주장이 아니라 상태 기계가 허용한 잠재 위험의 재현이다.

## 결정

### 1. 범위와 domain ownership

- `atlas-payment`는 buyer capture/refund와 PayPal provider contract를 소유한다. 기존 `payment_attempt`, `payment_refund_operation`, `payment_recovery_job`은 durable provider evidence·unknown recovery를 이미 제공하므로 전면 rewrite하지 않는다.
- `atlas-settlement`은 Seller payable, payout reservation, fee/reserve policy, immutable ledger journal을 소유한다.
- `atlas-application`은 두 domain 사이의 operator/API orchestration, payout dispatch outbox, scheduler, provider reconciliation과 metrics를 소유한다. Migration은 계속 `atlas-application`에서 관리한다.
- 외부 PayPal call은 DB transaction 밖에서 실행한다. Payout 승인 상태와 outbox intent는 같은 PostgreSQL transaction에서 commit한다. Provider 결과는 application orchestration을 통해 settlement의 조건부 상태 전이에 반영한다.
- Capture/refund/payout은 같은 provider-operation 원칙(immutable identity, explicit UNKNOWN, bounded retry, verified-result-only ledger effect)을 따른다. 저장소/queue를 합치는 변경은 측정된 중복 비용 또는 운영 실패가 있을 때만 후속 decision으로 검토한다.

#### Refund commitment과 payout reservation의 교차

- `OrderRefundService`는 PayPal refund를 보내기 전에 seller·refund·amount별 settlement hold를 같은 DB transaction에 기록한다. `PENDING`/`UNKNOWN` refund는 hold를 유지하고, 최종 provider 성공은 hold를 refund journal로 원자적으로 바꾸며, provider가 미환불을 확정하면 hold를 해제한다.
- 새 payout 가능액은 `max(0, SELLER_PAYABLE − pending payout reservations − full pending refund commitments)`다. 실제 refund journal은 `SELLER_RESERVE`를 먼저 소비하고 부족분만 `SELLER_PAYABLE`에서 차감한다. Hold는 경제적으로 보수적이며 dispatch 이전 payout이 같은 돈을 다시 예약하지 못하게 한다.
- V1은 미확정 환불의 전체 금액을 신규 payout 가능액에서 차감한다. 최종 refund journal은 `SELLER_RESERVE`를 우선 소비하므로 이는 Seller liquidity를 일시적으로 더 보수적으로 제한하지만 duplicate payout을 막는다. 환불이 서로 다른 순서로 완료될 때 reserve/payable 몫을 재배정하려면 별도 commitment ledger가 필요하므로 후속 증거 전까지 도입하지 않는다.
- Refund와 payout dispatch는 같은 Seller advisory transaction lock을 사용한다. Refund가 먼저 commit하면 `REQUESTED`/`APPROVED` payout은 `CANCELLED`되고 provider call은 시작하지 않는다. Worker는 provider POST 전 `APPROVED` 또는 operator가 재queue한 `UNKNOWN`/`NEEDS_REVIEW`를 `DISPATCHING`으로 조건부 전이하며 같은 lock을 쓴다.
- `DISPATCHING` 이후에는 이미 network send가 시작됐을 수 있어 payout을 취소하지 않는다. Buyer refund는 계속 진행하며 provider outcome/negative seller payable은 별도 risk signal과 reconciliation 대상으로 남긴다. 외부 transfer가 가능한 critical section을 DB transaction으로 감싸지 않는다.
- 신규 refund hold를 처음 만들 때 미전송 payout을 preempt한다. 동일 refundId/facts의 hold replay는 이후 생성된 net-available payout을 취소하지 않는다. `recordRefund`는 기존 `RESERVED`/`POSTED` hold만 journal로 전환하며, hold 없는 posting은 conflict로 거부한다.

### 2. Durable dispatch와 idempotency

- 기존 Seller payout request는 금액을 caller가 정하지 않고, 현재 payable에서만 산출하며 seller-period/idempotency 제약으로 예약한다.
- Operator 승인 transaction은 payout을 `APPROVED`로 전이하고 payout별 `payout_dispatch_outbox` intent를 함께 기록한다. Worker는 commit 이후에만 claim한다.
- Outbox에는 payout ID, deterministic `sender_batch_id`, `sender_item_id`, request payload hash, provider batch/item ID, status, due/lease, attempt count와 safe error code를 저장한다. `sender_batch_id`는 payout UUID에서 생성하고 retry마다 동일하게 쓴다.
- `request_payload_sha256`는 immutable financial/provider facts와 목적 분리된 recipient HMAC fingerprint를 포함한다. Plaintext email이나 사전 대입 가능한 unkeyed email hash는 포함하지 않으며 AES-GCM ciphertext는 별도 저장한다.
- Worker는 bounded batch와 PostgreSQL `FOR UPDATE SKIP LOCKED` lease, exponential backoff, max attempts/age, `NEEDS_REVIEW`를 사용한다. Provider call은 retry·worker restart 뒤에도 같은 batch identity로만 반복한다.
- PayPal은 `sender_batch_id`를 30일간 중복 방지 identity로 취급하며 HTTP 5xx 후 동일 ID retry를 허용한다. 30일이 지나도 outcome이 모호하면 새 ID로 blind retry하지 않고 `NEEDS_REVIEW`로 올린다.
- 첫 구현은 PostgreSQL outbox만 사용한다. Payout volume/backlog age와 DB contention을 측정하기 전에 Redis/Kafka/SQS를 추가하지 않는다. Broker 도입은 outbox가 합의한 backlog-age 예산을 지속적으로 넘고 DB queue가 병목임을 실험으로 확인한 뒤 별도 ADR로 판단한다.

### 3. 상태와 provider truth

`settlement_payout`은 Seller payable의 경제적 상태를, outbox는 외부 실행/reconcile 작업 상태를 소유한다.

```text
REQUESTED → APPROVED → DISPATCHING → SENT → CONFIRMED
  ↘ CANCELLED (refund/operator preemption before dispatch)
  ↘ FAILED (definitive no-send before external attempt)
UNKNOWN → DISPATCHING (same-ID retry; max age/attempt stays reserved)
NEEDS_REVIEW → DISPATCHING (operator requeue only; same immutable batch/item IDs)
DISPATCHING → UNKNOWN (response lost; keep reserve/retry same ID)
SENT → UNCLAIMED → CONFIRMED
DISPATCHING/SENT/UNKNOWN/UNCLAIMED → NEEDS_REVIEW
DISPATCHING/SENT/UNKNOWN/UNCLAIMED/NEEDS_REVIEW → FAILED only after provider or audited manual no-transfer evidence
```

- `SENT`는 PayPal batch 접수일 뿐 recipient 수령 완료를 뜻하지 않는다. Batch `PENDING`/`PROCESSING`과 item `UNCLAIMED`는 payable reservation을 유지한다.
- Batch `SUCCESS`만으로 journal을 만들지 않는다. Payout item API `transaction_status=SUCCESS`와 amount/currency/fee snapshot 대조가 통과해야 `CONFIRMED`로 전이하고 payout journal을 한 transaction에서 한 번만 기록한다. Webhook event는 `PAYMENT.PAYOUTS-ITEM.SUCCEEDED`이며 event payload만으로 확정하지 않는다.
- `currency_conversion`이 응답에 있으면 USD→USD, 송금액과 동일한 양쪽 금액, 환율 1을 모두 확인해야 한다. 비-USD 수령, 부분 변환 정보 또는 값 불일치는 자동 원장 반영 없이 `NEEDS_REVIEW`에 둔다.
- `UNKNOWN`은 POST 응답 timeout/reset 이후 provider outcome을 모르는 상태다. `FAILED`로 바꾸거나 새 sender ID로 재전송하지 않는다. 동일 batch ID 재조회·retry로 복구하고, limit/age 초과 시 `NEEDS_REVIEW`에 두며 금액은 계속 예약한다.
- Item `BLOCKED`, `RETURNED`, `REFUNDED`, 모순 amount/currency는 자동 release·retry하지 않고 `NEEDS_REVIEW`와 별도 reversal 정책으로 보낸다. 일반 operator fail은 REQUESTED/APPROVED에만 허용하며, dispatch 후 manual no-transfer는 evidence reference와 감사 기록을 요구한다.
- `FAILED`/`DENIED` item은 provider item ID와 명시적 USD fee 0이 있는 경우에만 자동 실패 처리한다. Fee 누락·비0은 `NEEDS_REVIEW`를 유지한다. V1은 fee-only failure journal을 지원하지 않으므로 manual no-transfer resolution에도 zero-fee evidence가 필요하다.
- V1은 payout webhook을 소비하지 않는다. Bounded polling과 batch/item GET이 authoritative evidence다. 향후 webhook은 idempotent inbox에 저장해 polling을 깨우는 signal로만 쓰며 payload만으로 journal을 확정하지 않는다.
- Outbox `NEEDS_REVIEW`는 operator audit/idempotency 아래에만 `PENDING`(batch ID 미확정) 또는 `WAITING_PROVIDER`(batch ID 확정)로 재queue한다. Batch ID가 있으면 GET만 재실행하고, 없으면 29일 duplicate window 안에서 같은 ID만 사용한다. Payout state는 authoritative provider result가 도착할 때까지 예약을 유지한다.

### 4. Recipient와 통화·회계 정책

- Public payout request body로 임의 receiver를 받지 않는다. Payout은 seller-owned, verified payout profile에서만 recipient를 결정하고, 승인 시 profile version과 recipient snapshot을 고정한다. Raw email/recipient는 response·log·metric tag에 노출하지 않는다.
- V1 PayPal provider amount는 USD. Recipient profile은 USD 수령 동의와 provider가 지원하는 country/currency eligibility를 보유한다. PayPal Payouts는 separate API permission을 요구하며, 공식 currency conversion 문서는 South Korea를 자동 환전 제외 지역으로 명시한다. KRW 자동 환전이나 모든 지역 지원을 주장하지 않는다. 미지원/미확정 profile은 자동 dispatch가 아니라 manual review 상태로 유지한다.
- 승인 시 payout-time KRW/USD FX snapshot을 고정한다. `providerAmountUsdMinor = ceil(reservedPayableWon × 100 / fxRateKrwPerUsd)`로 cent 단위 금액을 올림해 Seller의 KRW payable보다 provider 송금 가치가 작아지지 않게 한다. `providerDebitBookWon = ceil(providerAmountUsdMinor × fxRateKrwPerUsd / 100)`와 reserved payable 차이는 `PLATFORM_PAYOUT_FX_ROUNDING` expense로 기록한다. 자동 송금은 FX rounding expense가 payable의 1% 이하인 경우만 허용하며, 초과 시 작은 잔액에 1-cent 최소 단위 비용이 과도하게 전가되지 않도록 manual review로 보낸다.
- Provider가 실제 보고한 payout fee는 V1에서 Seller payable에서 차감하지 않고 `PLATFORM_PAYOUT_FEE` expense로 기록한다. USD fee와 payout의 KRW book value는 고정 FX snapshot으로 계산한다. Journal은 `SELLER_PAYABLE` debit + payout fee/rounding expense debit = `PSP_CLEARING` credit을 만족해야 한다. Provider가 다른 funding currency/fee semantics를 보고하거나 합계가 맞지 않으면 `NEEDS_REVIEW`; 차이를 숨기거나 임의로 zero 처리하지 않는다.
- PayPal Payouts country eligibility, sender-account permission, recipient currency conversion은 provider contract와 Sandbox 실측으로 확인한다. Country/profile support는 versioned config/DB policy로 유지하며 live-money readiness와 Sandbox feature readiness를 혼동하지 않는다.

### 5. Security와 rollout

- `ATLAS_PAYPAL_PAYOUTS_ENABLED`와 worker flag는 `false` 기본값. Payouts OAuth scope `https://uri.paypal.com/services/payments/payouts`는 buyer checkout OAuth registration과 분리한다. 기존 PayPal endpoint validation은 Sandbox/local test host만 허용하며 Live host/credential 사용 금지.
- Automatic approval은 Seller가 연결한 active verified account email, operator-reviewed country, USD 수령 동의를 고정하고 outbox intent와 `APPROVED`를 원자적으로 저장한다. 지원하지 않거나 증거가 부족한 profile은 자동 송금에서 제외한다.
- Payouts 활성 상태의 manual fallback은 별도 operator 경로이며 provider outbox가 없어야 한다. Manual approve/sent/confirm/no-transfer resolution은 제한된 audit reason/evidence reference와 idempotency key를 요구하고, provider-managed payout의 evidence gate를 우회할 수 없다.
- Recipient ciphertext는 AES-GCM v2이며 envelope에 key ID가 있고 AAD가 version·key ID에 결합된다. Keyring은 active 및 prior decryption key를 지원한다. Rotation은 overlap 배포로 진행하며, 기존 v1 ciphertext가 남아 있으면 이전 키를 `legacy-v1`로 유지하고, 저장된 모든 row가 retired되기 전에는 decryption key를 제거하지 않는다.
- Recipient 주소는 암호화·검증된 계정 identity로만 관리한다. Provider credential과 recipient PII는 outbox log, HTTP DTO, CI output에 기록하지 않고 Micrometer tag에도 넣지 않는다.
- 관측 지표는 outbox ready/submitted/unknown/needs-review 수, oldest age, attempt 수, provider latency/status, 최종 payout journal 수와 refund/negative-payable exposure로 제한한다. Tag는 operation/status만 사용하며 sellerId·email·payoutId·providerBatchId는 금지한다.
- Feature flag off면 payout provider call과 worker dispatch는 0건. Rollback은 flag off; persisted outbox/provider IDs/journals 보존, payout state나 원장 행을 삭제하지 않는다.

## 대안과 trade-off

| 대안 | 결정 |
|---|---|
| 기존 수동 `SENT`/`CONFIRMED` 표시 유지 | 구현은 단순하지만 provider evidence·idempotency가 없어 ambiguous send 뒤 재예약 위험이 남아 기각. Provider 미지원 profile에는 별도 audit된 manual route를 유지할 수 있음. |
| Seller API controller에서 PayPal POST 동기 실행 | DB commit/HTTP timeout 경계와 재기동 복구를 보장하지 못해 기각. |
| Capture/refund/payout recovery queue를 즉시 하나로 합침 | 검증된 payment recovery와 module ownership/FK를 바꾸는 비용이 큼. Semantics를 맞추되 물리 queue 통합은 증거가 생길 때까지 기각. |
| Kafka/Redis/SQS를 먼저 추가 | payout 처리량·backlog 기준이 없고 ledger/outbox atomicity를 더 어렵게 하므로 후순위. |
| PayPal의 local currency conversion을 가정 | South Korea conversion exclusion과 recipient country/currency 부재를 무시하게 되어 기각. |

## 결과, 한계, 되돌리기

- `SENT → FAILED` generic operator 전이는 금지한다. `FAILED`/reservation release는 provider의 definitive item 실패 또는 no-transfer manual evidence가 outbox·operator audit에 기록된 경우에만 허용한다.
- Refund intent는 payout availability에 보수적으로 반영한다. 동일 Seller advisory lock 아래 refund가 먼저 commit되면 unsent payout은 `CANCELLED`; dispatch fence가 먼저 commit되면 payout reservation을 유지하고 refund가 만드는 negative payable/credit exposure를 별도로 관찰한다.
- Provider item `SUCCESS`는 item ID·sender ID·USD amount/currency·fee 검증을 통과한 뒤에만 payout journal 1건으로 반영한다. Seller principal, cent-rounding expense, provider fee 합계와 `PSP_CLEARING` credit이 균형을 이룬다.
- Unsupported recipient/country에는 PayPal 자동 전송을 만들지 않는다. 별도 operator manual route는 provider outbox가 없어야 한다. Manual approve/sent/confirm/no-transfer resolution은 bounded evidence reference·operator identity·idempotency key를 기록하고, provider-managed payout의 evidence gate를 우회할 수 없다.
- Outbox는 승인 후 worker 장애/응답 유실에도 동일 payout identity를 보존한다. PostgreSQL queue·metrics·migration의 operational cost가 생기므로 backlog와 oldest age를 관측한다. Kafka/Redis는 병목 실험 전 도입하지 않는다.
- Seller payout profile country/PayPal eligibility와 USD 수령 지원이 없는 계정은 자동 송금 대상이 아니다. South Korea seller payout은 provider currency support가 확인되기 전까지 manual path에 남는다.
- Payment provider Sandbox access may lack Payouts permission. Permission absent이면 integration contract/MockWebServer/PostgreSQL tests를 통과하더라도 Sandbox end-to-end transfer는 미검증으로 기록한다. Live money movement는 본 ADR 범위 밖.
- Rollback은 feature flag off와 worker 중지. 이미 provider에 제출된 batch는 삭제/재송금하지 않고 동일 sender_batch_id로 대조한다. Recipient key rotation은 이전 key를 decryption keyring에 유지하고 pending/review row의 ciphertext를 먼저 확인한다.

## 검증 기준

- 일반 `fail()`은 `SENT/UNKNOWN/UNCLAIMED`에서 conflict를 반환한다. `REQUESTED/APPROVED` pre-send failure와 명시적 provider/manual no-transfer evidence는 별도 경로로 검증한다.
- Refund reservation과 payout dispatch의 동시 경쟁은 하나의 Seller lock으로 직렬화한다. Refund 선행이면 미전송 payout 취소·POST 0건, dispatch 선행이면 payout hold 유지와 pending refund commitment 관측을 확인한다.
- Provider response loss, same-ID retry, duplicate error/HATEOAS, item status lag, worker crash/lease expiry, max-age review에서 새 batch 중복 생성이나 unknown reservation 조기 해제가 없어야 한다.
- Batch `SUCCESS`만으로는 정산하지 않는다. 일치하는 item success는 FX rounding·provider fee expense를 포함해 balanced journal 1건만 생성하며, provider fee/no-transfer 증거가 불충분하면 manual review로 유지한다.
- Capture/refund recovery suites show no FX/idempotency/webhook/ownership regressions; V1 payout polling remains bounded and does not require webhook delivery.
- Testcontainers/PostgreSQL, MockWebServer and Sandbox opt-in results are recorded separately; no unmeasured production SLO is claimed.

## 참고

- [Issue #456](https://github.com/Neo1228/ATLAS/issues/456)
- [PayPal create batch](https://developer.paypal.com/api/payments.payouts-batch/v1/payouts-post): sender batch duplicate window and same-ID 5xx retry.
- [PayPal apps, scopes, and credentials](https://developer.paypal.com/apps-scopes-and-credentials): Payouts 권한에 사용하는 OAuth scope.
- [PayPal Payouts workflow](https://developer.paypal.com/docs/payouts/standard/integrate-api): asynchronous batch/item statuses, polling/webhooks.
- [PayPal Payouts webhook events](https://developer.paypal.com/payouts/webhooks): batch events omit item-level data.
- [PayPal country/currency restrictions](https://developer.paypal.com/docs/payouts/standard/reference/currency-conversion/): South Korea conversion exclusion.
