# ADR-0025: Limited coupon capacity and redemption lifecycle

- 상태: ✓ 구현·full Gradle check 통과 (42 suites, 218 tests, 0 failures/errors/skips); `bootJar` 성공
- 결정일: 2026-11-09
- 관련 작업: [Issue #189](https://github.com/Neo1228/ATLAS/issues/189)
- 선행 결정: [ADR-0024](ADR-0024-seller-coupon-allocation.md)
- 검증 근거: [EXP-023](../../03-experiments/EXP-023-coupon-redemption-capacity.md) — local k6, Prometheus/Grafana/Postgres exporter, measured limitations.

## 배경

ADR-0024의 Seller coupon은 제한 없이 재사용됩니다. Seller가 총 사용 횟수를 제한하려면 단순 카운터만으로 부족합니다. 동시에 진행되는 checkout, PSP의 unknown/pending 결과, 취소·만료·reconciliation이 같은 redemption을 중복 차감하거나 조기 반환하지 않도록 상태와 transaction 경계를 정해야 합니다.

## 결정

- `maxRedemptions`는 선택 값. `null`은 ADR-0024의 unlimited 동작 유지, 양수는 Coupon 전체의 global cap.
- Seller-scoped GET reports configured cap and current reserved/redeemed/remaining counts. Unlimited V1 coupons return null usage counts because they do not create redemption ledger rows.
- Per-customer cap은 제외. 현재 `buyerRef`는 인증된 customer identity가 아님.
- Capped Coupon은 SellerOrder 생성 transaction 안에서 PostgreSQL 조건부 `UPDATE`로 한 건 예약. 조건은 `reserved_count + redeemed_count < max_redemptions`; counter update와 redemption row 저장은 같은 transaction.
- 여러 SellerOrder coupon 예약은 checkout과 함께 atomic. 하나라도 한도 초과면 전체 checkout rollback.
- Redemption state:

  ```mermaid
  stateDiagram-v2
      [*] --> RESERVED: checkout cap 확보
      RESERVED --> REDEEMED: payment capture 확인
      RESERVED --> RELEASED: 결제 불가 확정
      RESERVED --> RESERVED: pending / unknown / void pending
      REDEEMED --> REDEEMED: refund는 cap 복원 안 함
      RELEASED --> [*]
  ```

- `CAPTURED`에서만 `reserved_count--`, `redeemed_count++`. 명확한 pre-payment cancel/expiry 또는 최종 decline/void에서만 reservation 반환.
- `PAYMENT_PENDING`, `UNKNOWN`, `VOID_PENDING`에서는 capacity를 유지해 in-flight PSP 결제의 과발급 방지.
- Deactivate는 신규 reservation만 차단. 이미 예약한 주문은 capture/reconciliation 가능.
- Idempotent reserve/redeem/release. 같은 order replay는 counter를 재변경하지 않음.
- Refund는 redemption을 반환하지 않음. Cap은 성공한 capture 횟수로 정의.
- Redis, distributed lock, broker, shard counter, 새 dependency 도입 없음. Primary-key row lock/conditional update가 먼저. Hot-row 한계는 local k6 및 PostgreSQL 지표로 측정한 뒤 판단.

## 근거와 대안

| 안 | 이점 | 위험/비용 | 결정 |
|---|---|---|---|
| Capture 때만 cap 차감 | 예약 상태 없음 | 결제 전에 capacity를 초과 예약해 capture 시점에 처리 불가 주문 발생 | 기각 |
| Checkout에서 즉시 최종 차감 | 구현 단순 | 결제 실패·취소·unknown 복구가 cap을 과소/과다 계산 | 기각 |
| Checkout reservation + capture finalization | 초과 발급 차단, 결제 실패·reconciliation을 상태로 표현 | PSP 미확정 동안 capacity가 일시 점유되고 counter/ledger 필요 | 채택 |
| Per-customer limit | 개인별 반복 사용 제어 | 현재 buyer 식별자는 인증 identity가 아님 | 보류 |
| Redis/distributed lock | 여러 DB shard/worker에 확장 가능 | 현재 단일 local PostgreSQL 범위에 불필요한 consistency plane 추가 | 기각 |

## 판단 근거

- [Meituan: InnoDB locks and transaction isolation](https://tech.meituan.com/2014/08/20/innodb-lock.html)은 indexed 조건과 lock footprint의 관계를 설명합니다. PostgreSQL 구현에서는 Coupon primary key에만 조건부 update를 적용합니다.
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html)은 concurrent update의 row-lock 대기와 READ COMMITTED predicate 재평가를 설명합니다. Counter는 read-then-write가 아닌 단일 conditional update로 보호.
- [Shopify discount-code FAQ](https://help.shopify.com/en/manual/discounts/discounts-faq)는 total-use와 customer-use 제한을 구분합니다. V2는 현재 도메인에 맞는 global cap만 지원.
- [Meituan promotion capacity evaluation](https://tech.meituan.com/2016/09/28/stress-test-before-promotion.html)은 controlled baseline과 load evidence의 필요성을 뒷받침합니다. 해당 수치는 ATLAS의 운영 capacity로 사용하지 않음.
- Limited code 하나는 counter row 하나를 직렬화하므로 인기 code가 hot row가 될 수 있습니다. k6와 기존 Prometheus/Grafana/Postgres exporter로 같은 local profile의 unlimited/capped 경로를 비교. Threshold는 측정 전 설정하지 않음.
- No Tempo/Alloy/Toxiproxy: 단일 application→PostgreSQL lock-contention 가설에 trace pipeline이나 network fault proxy가 필요하지 않음.

## 검증

- Concurrent checkout은 cap보다 많은 reservation을 허용하지 않음; counter와 ledger row 수 일치.
- Multi-seller checkout의 한 coupon이 거절되면 주문·cart clear·inventory·다른 coupon counter 모두 rollback.
- Capture, definitive decline/void, cancel, expiry, pending/unknown, reconciliation별 counter transition.
- PSP outcome이 unknown이면 예약 유지; capture 후 refund는 redeemed count 유지.
- Same idempotency key, duplicate capture/reconcile, repeated release에서 중복 변경 없음.
- Local Testcontainers PostgreSQL concurrency/failure tests, full Gradle check, short matched k6 baseline/candidate, application bootJar.
- Exact workload, hardware, environment, counters, latency/error summaries를 실험 문서에 기록. JSON traces/artifacts는 commit하지 않음.
