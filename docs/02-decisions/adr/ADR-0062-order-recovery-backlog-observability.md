# ADR-0062: 주문 재고 recovery backlog age를 stage별로 관측한다

- 상태: Accepted — 구현·통합 검증 완료
- 날짜: 2026-11-22
- 결정 ID: [D-125](../../00-governance/decision-log.md#d-125)
- 관련 결정: [ADR-0031](ADR-0031-bounded-application-scheduler.md)(bounded scheduler), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)(order inventory recovery), [ADR-0064](ADR-0064-order-inventory-just-in-time-lease-claims.md)(just-in-time recovery lease)
- 측정: [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md)(backlog 처리량), [EXP-057](../../03-experiments/EXP-057-checkout-recovery-index.md)(checkout recovery access path)

## 문제

예약 workflow, checkout finalization, 원격 환불 재입고는 서로 다른 queue와 retry policy를 가진다. Poll count, claimed/processed count와 duration만으로는 작업이 단순히 없었는지, 오래된 due backlog가 쌓였는지를 알 수 없다. 한편 finalizer는 매 poll `RESERVATION_PENDING` checkout을 `created_at` 순서로 bounded 조회하지만 이에 맞는 state/order index가 없어 checkout 이력이 커지면 전체 scan·sort가 발생한다. `CANCEL_PENDING`의 `updated_at`은 cancel retry에서도 갱신되므로 안정적인 취소 대기 age가 아니다.

## 결정

1. 세 recovery stage는 기존 poll·duration·failure metric에 더해 age gauge를 노출한다.
   - `atlas.order.inventory.recovery.oldest_due_age_seconds{stage="reservation"|"restock"}`: due `next_attempt_at` 중 가장 오래된 시각 기준.
   - `atlas.order.inventory.recovery.oldest_pending_age_seconds{stage="checkout-finalization",state="RESERVATION_PENDING"|"CANCEL_PENDING"}`: checkout reservation intent의 생성 시각 또는 최초 cancellation 요청 시각 기준.
   - 빈 queue는 `0`으로 보고한다. Gauge는 상태와 시각에 필요한 indexed query만 사용하며 worker 수·batch 기본값을 이 측정만으로 조정하지 않는다.
2. `BuyerCheckout.cancellation_requested_at`은 최초 `CANCEL_PENDING` 전이에서만 설정한다. 같은 상태를 재시도하며 `updated_at`이 바뀌어도 취소 대기 시작 시각은 유지한다. V74는 기존 pending row의 정확한 최초 시각을 복원할 수 없으므로 migration 당시 `updated_at`으로 근사 backfill하고, `CANCEL_PENDING`에는 timestamp가 반드시 존재한다는 check constraint를 둔다.
3. V73의 `buyer_checkout_recovery_state_created_idx (state, created_at, checkout_id)`는 reservation-pending finalizer scan과 정렬 순서를 지원한다. V74의 `buyer_checkout_cancel_pending_age_idx (cancellation_requested_at, checkout_id)`는 원격 `CANCEL_PENDING` age query를 지원하는 partial index다.
4. Worker scheduler pool size, batch size와 poll cadence는 변경하지 않는다. [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md)은 two-location·3 SKU line, 1,000 workflow success-only backlog의 24-cell synthetic matrix(cell당 1회 실행)다. Poll당 상한(batch size) 내 workflow를 just-in-time lease claim하며 같은 JVM의 poller object를 tight-loop로 호출했다. 최고 775.00 workflow/s(poller object 32개, batch 50)로 합성 1,000 workflow/s 목표에는 미달했지만, scheduler fixed delay와 실제 multi-replica를 검증하지 않아 운영 capacity나 worker sizing 근거가 아니다.

## 대안과 trade-off

- Scrape마다 무제한 `COUNT(*)`를 실행하는 queue-size gauge는 backlog가 커질수록 observability query 자체가 부하를 만들 수 있어 추가하지 않는다.
- `CANCEL_PENDING` age에 `updated_at`을 쓰면 retry가 값을 초기화해 오래된 작업을 숨길 수 있으므로 거절한다.
- 같은 PostgreSQL host에서 단독 test와 전체 clean check로 두 번 실행했고, 각 access path는 warm-up 1회 뒤 `EXPLAIN (ANALYZE, BUFFERS)` 5회의 Execution Time median을 기록했다([EXP-057](../../03-experiments/EXP-057-checkout-recovery-index.md)). V73 index는 `RESERVATION_PENDING` 조회를 4.174 → 0.049 ms(단독), 4.367 → 0.056 ms(전체 check)로 낮췄고(78.0–85.2배), V74 migration index가 선택된 취소 age query는 20.163 → 19.114 ms, 20.941 → 18.315 ms(5.2–12.5% 낮음)였다. 100,000행 synthetic checkout 기준의 로컬 관측이며 production SLO가 아니다.
- Index write/storage 비용, 반복 분포와 production cancel backlog 비율은 측정하지 않았다. Index 추가는 stage-specific metric correctness와 plan 근거에 한정하며 운영 처리량 주장을 하지 않는다.

## 검증과 한계

- stage별 gauge 값(stage tag, 빈 queue의 0, due age)을 단언하는 자동 test는 현재 저장소에 없다. 구현 당시 두었던 gauge 단위 test(`OrderInventoryRecoveryWorkerTest`)는 이후 test 정리 과정에서 제거했다. gauge 이름과 tag는 `LocationReservationWorker`, `CheckoutFinalizationWorker`, `LocationRestockWorker`의 등록 코드가 정의한다.
- 현재 남은 자동 검증은 `BuyerCheckoutTest`가 확인하는 불변식(반복 `CANCEL_PENDING` 갱신이 최초 취소 요청 시각을 되돌리지 않는다)이다. V73/V74 index의 query plan 효과는 [EXP-057](../../03-experiments/EXP-057-checkout-recovery-index.md)의 일회성 측정이며, 그 harness는 저장소에 보존하지 않았으므로 자동 회귀 test는 없다.
- 전체 검증: `./gradlew clean check`; backlog 처리량 matrix([EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md))는 보존하지 않은 일회성 probe의 측정 기록이다.
