# ADR-0047: 승인 화면 이탈 결제에 deadline을 두고 provider 조회로 확정한다

- 상태: Accepted — 구현 추적: [#411](https://github.com/Neo1228/ATLAS/issues/411)
- 날짜: 2026-11-19
- 관련 결정: [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md)

## 맥락

결제 승인 대기는 외부 신호(구매자 callback)에 의존한다. 그런데 구매자가 PayPal 승인 화면을 닫기만 하면 그 신호가 영원히 오지 않는다. 코드에서 확인한 사실은 다음과 같다.

- `PaymentRecoveryWorker`는 `AUTHORIZATION_PENDING` + approval URL이면 `WAITING_FOR_BUYER`로 넘기고 반환한다.
- `claimDue`와 `expireExhausted`는 모두 `status = 'PENDING'`만 대상으로 한다. `WAITING_FOR_BUYER`는 claim 대상도, `MAX_ATTEMPTS`/`RECOVERY_WINDOW_EXPIRED` 대상도 아니다.
- `WAITING_FOR_BUYER`를 되돌리는 경로는 buyer callback(`activateAttemptFromBuyer`)과 provider phase 재개(`resumeAttempt`)뿐이며, 둘 다 외부 신호가 있어야 한다.
- 주문·checkout 만료 sweep은 `RESERVED`만 후보로 조회한다. `PAYMENT_PENDING`/`PAYMENT_UNKNOWN`은 만료 대상이 아니다.
- 캠페인 구매자의 후속 결제는 `PAYMENT_SUBMITTED/PAYMENT_PENDING/PAYMENT_UNKNOWN/PAYMENT_VOID_PENDING/CANCEL_PENDING`을 unresolved로 보고 `CONFLICT`로 거절한다.

결과적으로 승인 화면 이탈 한 번으로 재고 예약, 캠페인 checkout slot, 후속 결제, 운영자 가시성이 함께 막힌다. 결제 실패도 성공도 아닌 상태를 정리할 주체가 없고, 이 상태는 `NEEDS_REVIEW` 알림에도 잡히지 않는다.

## 결정

1. `payment_attempt.approval_expires_at`을 추가하고, 승인 대기에 진입하는 시점에 `now + atlas.payment.approval-ttl`(기본 30분)을 기록한다. PayPal approval 유효기간보다 짧게 두어 provider가 먼저 만료시키는 상황을 만들지 않는다.
2. `resumeExpiredApprovalWaits(limit)`가 deadline이 지난 `WAITING_FOR_BUYER` job을 bounded batch로 `PENDING`으로 되돌린다. `SKIP LOCKED`와 partial index(`state = 'AUTHORIZATION_PENDING' AND approval_expires_at IS NOT NULL`)로 후보 스캔을 초과분에 비례하게 유지한다. 재개 사유는 `last_error_code = 'APPROVAL_WINDOW_EXPIRED'`로 남긴다.
3. worker는 재개된 job에서 **provider에 실제 상태를 먼저 조회**한다(`resolveExpiredApproval`). 승인 화면을 닫았다는 사실만으로 void를 보내지 않는다. 승인 화면을 닫았더라도 capture가 이미 확정됐을 수 있기 때문이다.
4. 조회 결과가 승인 전(`AUTHORIZATION_PENDING` 유지)이면 결제를 `VOIDED`로 닫고 승인 대기 flag를 내린다. 이 시점에는 PayPal authorization이 존재하지 않으므로(승인 후 우리가 `/authorize`를 호출해야 생성된다) 취소할 자금이 없다. 따라서 provider cancel 호출 없이 로컬 상태만 닫는다.
5. 닫힌 결제는 ADR-0046의 `APPLY` 단계로 넘어간다. 재고 해제·주문 terminal 전이·캠페인 slot 정리는 기존 `applyPayment` 경로가 owner transaction 안에서 수행한다. 새로운 정산·재고 로직을 만들지 않는다.
6. 조회가 capture/void 확정을 돌려주면 그 결과를 그대로 `APPLY`로 넘겨 정상 복구한다. 재고를 해제하지 않는다.
7. 조회가 계속 `UNKNOWN`이면 새 side effect를 보내지 않고 기존 규칙대로 `NEEDS_REVIEW`로 보존한다.

## SSOT와 실패 처리

- 승인 대기 여부의 SSOT는 `payment_attempt`의 상태·`approval_expires_at`·provider 응답이다. job은 실행 의도만 담는다.
- deadline은 참고값이며, **provider 조회 결과가 항상 우선한다.** deadline이 지났어도 provider가 승인/청구를 확정했으면 그쪽이 사실이다.
- deadline 이전 callback은 기존 경로 그대로 승인 처리된다. 늦은 callback과 worker 확정은 `payment_attempt` row lock으로 직렬화되므로, 둘 중 먼저 잡은 쪽이 상태를 확정하고 다른 쪽은 확정된 상태를 읽고 종료한다.
- capture 확정 후 재고 해제 금지, void 확인 전 재고 해제 금지 규칙은 그대로다.

## 동시성·확장·운영

- 재개 sweep은 `batchSize`로 상한을 두고 매 poll에서 한 번 실행한다. 후보가 없으면 `UPDATE`가 0행이므로 비용이 인덱스 탐색에 그친다.
- 배포 시점 backfill은 `first_enqueued_at + 30분`을 기준으로 한다. 이미 오래된 행은 즉시 due가 되어 한 번에 정리되고, 새 행은 정상 window를 받는다.
- 관측 지표: `atlas.payment.recovery.approval.resumed`(재개 건수), `atlas.payment.recovery.stage.jobs{work_stage="PROVIDER",status="WAITING_FOR_BUYER"}`(대기 중인 승인 수), `atlas.payment.recovery.stage.oldest_due_age_seconds`.

## 검증

- `PaymentApprovalAbandonmentIntegrationTest`
  - 콜백 없이 deadline 경과 → 결제 `VOIDED`, 주문 `FAILED`, 재고 `available` 복원, job `COMPLETED`.
  - deadline 경과 후 실제 승인·capture 확정 → 재고 해제 없이 `PAID`, 정산 1건, job `COMPLETED`.
  - deadline 이전 → `WAITING_FOR_BUYER` 유지, 재고 예약 유지, capture 요청 0건.
- `PaymentRecoveryIntegrationTest`가 기존 fence·lease·operator requeue 계약을 계속 검증한다.

## ADR-0046과의 관계

ADR-0046은 provider 결과와 owner 적용 사이의 유실을 닫았다. 이 결정은 그 앞단, 즉 **provider 작업이 시작되지도 끝나지도 않은 채 대기만 하는 상태**를 닫는다. 두 결정 모두 같은 `payment_recovery_job`을 사용하며, 이 결정은 `WAITING_FOR_BUYER`에 deadline과 재개 경로를 추가할 뿐 stage 구조를 바꾸지 않는다.
