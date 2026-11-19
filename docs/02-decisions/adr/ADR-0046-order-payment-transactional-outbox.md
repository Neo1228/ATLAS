# ADR-0046: payment recovery를 intent→provider→owner 적용 단일 workflow로 확장

- 상태: Accepted — 구현 추적: [#409](https://github.com/Neo1228/ATLAS/issues/409)
- 날짜: 2026-11-19
- 관련 결정: [ADR-0029](ADR-0029-order-payment-recovery-contract.md), [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md)

## 맥락

주문/checkout/refund 업무 상태와 PayPal side effect는 같은 DB transaction에 넣을 수 없다. 현재 외부 호출은 transaction 밖에서 실행해 DB connection을 provider 지연에 묶지 않지만, 그 사이 프로세스 종료를 복구할 durable workflow가 없다.

- 단일 주문은 `PAYMENT_SUBMITTED`를 commit한 뒤 `payments.submitInitial`을 호출한다 (`OrderServiceImpl.submitPaymentLocked`, 171–197행).
- buyer-wide checkout은 checkout/order 상태를 commit한 뒤 `payments.submitCheckout`을 호출한다 (`CheckoutPaymentServiceImpl.submit`, 87–126행).
- 환불 요청은 order-side refund record를 commit한 뒤 `payments.refundOrder/refundCheckout`을 호출한다 (`OrderRefundServiceImpl.refund`, 108–128행).
- Payment/refund provider terminal 상태와 `payment_recovery_job=COMPLETED`가 order/inventory/settlement transaction보다 먼저 durable할 수 있다. Recovery worker의 terminal shortcut은 owner effects를 적용하지 않고 작업을 닫는다.

이로 인해 결제 시도/환불 operation 없는 pending 업무가 남거나, provider 자금 이동은 완료됐는데 주문·재고·fulfillment·settlement는 미반영될 수 있다. 일반적인 throughput 가정이 아니라 코드의 실제 commit 경계에서 확인한 복구 불능이다.

## 결정

1. 별도 order outbox/queue나 broker를 추가하지 않고, 기존 PostgreSQL `payment_recovery_job`을 **intent → provider operation → owner application**의 단일 durable workflow로 확장한다. `work_stage`는 `PROVIDER` 또는 `APPLY`다. 기존 job은 provider operation별 하나를 유지한다.
2. `PaymentService`에 DB-only `prepare` 동작을 분리한다. Order/checkout transaction은 owner state, `PaymentAttempt`, 그리고 해당 `PROVIDER/PENDING` recovery job을 함께 commit한다. PSP HTTP 호출은 그 commit 이후에만 수행한다. attempt ID를 owner record에도 같은 transaction에서 기록한다.
3. Refund reservation transaction은 refund request/items, idempotent `PaymentRefundOperation`, recovery job, order-refund-to-operation link/payment status를 함께 commit한다. PSP refund 호출은 commit 이후에 수행한다.
4. Provider payment/refund가 terminal이어도 job을 완료하지 않는다. Payment/refund operation과 같은 transaction에서 job을 `APPLY/PENDING`으로 바꾼다. Buyer approval 대기는 `PROVIDER/WAITING_FOR_BUYER`로 유지하며 callback/webhook이 재개한다.
5. Order/checkout/refund service는 inventory claim/release/restoration, fulfillment, settlement journal, owner status를 적용하는 transaction 안에서 `payments.markOwnerApplied(...)`를 호출한다. 그 호출은 `APPLY` 단계 job을 완료한다. DB commit 전 종료 시 업무 변경과 completion이 모두 rollback되어 재전달되고, commit 후 종료 시 둘 다 반영된다.
6. Recovery worker는 `PROVIDER`에서만 PayPal operation을 실행/대조하고, `APPLY`에서는 저장된 결과를 owner에 반영한다. Terminal operation을 건너뛰고 완료 처리하지 않는다. Retry/lease는 기존 bounded `FOR UPDATE SKIP LOCKED`, lease token, capped jittered backoff, `NEEDS_REVIEW`를 사용한다.
7. 신규 외부 스택은 도입하지 않는다. 같은 PostgreSQL transaction 경계와 현재 DB queue가 이미 있으므로 별도 broker는 원자성을 향상시키지 않고 relay/replay/운영 복잡성을 추가한다. 실제 queue age/DB contention 측정이 수평 worker나 CDC/broker 이전의 근거가 된다.

## SSOT와 실패 처리

- PayPal에서 실제 승인/청구/환불된 금액과 provider IDs: `payment_attempt` / `payment_refund_operation` 및 검증된 webhook/provider 응답.
- 주문/checkout/refund 상태와 재고/fulfillment/settlement: 각 도메인 테이블. Settlement source ID와 inventory state transition으로 반복 적용을 멱등하게 유지한다.
- `payment_recovery_job`: 실행 의도, stage, retry/lease만 기록한다. 업무 사실이나 payment 상태의 대체 SSOT가 아니며 payload에 금액/개인정보를 복제하지 않는다.
- intent commit 전/rollback: 업무 상태, attempt/operation, job 모두 생성되지 않는다.
- intent commit 후 PSP 호출 전 종료: `PROVIDER/PENDING` job이 같은 attempt/operation을 dispatch한다.
- PSP 응답/프로세스 종료 후 local application 전: terminal result는 `APPLY/PENDING`으로 남고 worker가 order/refund effects를 적용한다.
- local application 중 종료: 업무 effects와 job completion은 한 transaction이므로 모두 rollback된다. 재전달이 같은 provider result와 owner key를 재사용한다.
- application commit 후 acknowledgement 전에 종료: acknowledgement는 같은 transaction이므로 해당 중간 상태는 없다.
- timeout/UNKNOWN: 같은 phase/refund의 저장된 PayPal request ID를 조회·재사용한다. Provider의 idempotency 보장 기간을 넘긴 ambiguous operation은 새 side effect를 무작정 보내지 않고 `NEEDS_REVIEW`로 보존한다.
- Declined payment에 활성 authorization이 남으면 업무 완료로 보지 않는다. 필요한 void를 provider 단계에서 끝낸 뒤 owner application을 완료한다.

## 동시성·확장·운영

DB queue는 bounded batch와 `FOR UPDATE SKIP LOCKED`를 사용해 여러 application replica가 경쟁 없이 claim하고, lease token으로 stale worker completion을 fencing한다. Provider 요청은 DB transaction 밖에 있고 phase별 고정 request ID를 사용하므로 lease 만료 후 중복 delivery도 같은 operation에 수렴한다. Job은 provider call 전체에 대해 exactly-once 실행을 주장하지 않는다.

관측 대상은 stage별 pending/waiting/needs-review count, oldest due age, provider terminal→owner application latency, retry/backoff, lease takeover/stale token, payment owner가 attempt/job 없이 pending인 orphan, terminal operation의 미적용 상태, DB connection acquisition/wait다.

구현한 지표는 `atlas.payment.recovery.jobs{status}`(전체), `atlas.payment.recovery.stage.jobs{work_stage,status}`, `atlas.payment.recovery.stage.oldest_due_age_seconds{work_stage}`, `atlas.payment.recovery.duration{operation_type,work_stage}`다. `work_stage=APPLY`의 pending count와 due age가 곧 provider terminal 이후 owner 적용까지 걸린 대기이므로, 별도 latency histogram 없이 기존 lease/backoff 구조를 그대로 관측한다. 실제 처리 지연 분포가 필요해지면 그때 histogram을 추가한다. Bounded batch와 connection budget을 지키며 DB 스펙 증설은 하지 않는다. `NEEDS_REVIEW` 작업은 자동 삭제하지 않고 제한된 operator requeue로 복구한다.

`payment_recovery_job`은 거래별 하나의 row라 provider stage와 owner application stage 모두에서 같은 attempt/refund operation을 연결한다. Backfill은 missing owner attempt/job, missing refund operation/link/job, terminal provider result와 unresolved owner를 idempotently 식별한다. Facts가 모호한 refund는 금액 이동을 자동 재시도하지 않고 migration 전 operator review를 요구한다. 완료/증빙 테이블의 retention과 unresolved job retention은 분리한다.

## 검증

- Crash injection: order/checkout/refund intent commit 뒤 provider call 전, provider terminal persistence 뒤 local application 전, local application 중간.
- 구현된 회귀 테스트: `PaymentWorkflowCrashWindowIntegrationTest`가 (1) checkout intent commit 후 provider dispatch 전 종료를 spy로 주입해 durable job이 같은 attempt로 결제를 개시하는지, (2) capture 결과만 저장된 상태에서 worker가 owner 적용(inventory claim, fulfillment 초기화, settlement 1건)을 한 번만 수행하고 job을 `APPLY/COMPLETED`로 닫는지 검증한다. 두 번째 시나리오는 worker를 두 번 더 실행해 중복 side effect가 없음을 확인한다.
- `PaymentRecoveryIntegrationTest`는 동시 claim fencing, lease 만료 재획득, `WAITING_FOR_BUYER` 전환, operator requeue 상한/멱등을 검증한다.
- Repeated API/webhook, concurrent replicas, lease expiry/stale worker, provider timeout/UNKNOWN, refund result loss, approval callback race.
- Declined-with-active-authorization → void, void success → cancellation/inventory release, capture → inventory/fulfillment/settlement, refund → inventory restoration/settlement reversal.
- 재시작 후 같은 provider operation과 owner transaction으로 수렴하고 settlement/inventory side effect가 중복되지 않는지 확인한다. Stage별 backlog age와 `NEEDS_REVIEW` alert도 확인한다.

## ADR-0029와의 관계

ADR-0029는 당시 worker/outbox 필요를 정당화할 구체적 unresolved-operation 증거가 부족해 client replay/provider retry를 선택했다. 이번 결정은 실재하는 commit 사이 유실 경계와 terminal recovery shortcut에 대한 증거에 근거해 그 범위를 대체한다. 기존 SSOT, provider idempotency, inventory-release, ambiguous-state 계약은 유지하며 PSP 호출은 계속 DB transaction 밖에서 수행한다.
