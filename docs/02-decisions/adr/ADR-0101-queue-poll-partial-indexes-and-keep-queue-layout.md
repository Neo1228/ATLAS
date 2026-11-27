# ADR-0101: 폴링 질의 두 개에 부분 인덱스를 추가하고, 큐 테이블 레이아웃·깨우기는 현행을 유지한다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-27
- 관련 결정: [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)
- 근거: [EXP-112](../../03-experiments/EXP-112-queue-wakeup-lock-crossdb-alternatives.md), 이슈 [#715](https://github.com/Neo1228/ATLAS/issues/715)

## 문제

큐 테이블 레이아웃(HOT 갱신, `fillfactor`, 자동 vacuum), 1초 폴링, 락 구성, 두 DB 사이 원자성을 "더 나은 대안이 있는가"로 측정했다. 결제 복구 worker가 1초마다 도는 질의 하나와 지급 worker의 청구 질의 하나는 완료된 이력 행 수에 비례해 읽었다(EXP-112 Q9). 그 외 후보는 수치가 현행 유지를 지지했다.

## 결정

1. **V8 마이그레이션으로 부분 인덱스 2개를 추가한다.** 열·제약·행 의미는 그대로다.
   - `ix_payment_recovery_job_waiting_for_buyer ON payment_recovery_job (recovery_job_id) WHERE status = 'WAITING_FOR_BUYER'`
   - `ix_settlement_payout_dispatch_leased ON settlement_payout_dispatch_outbox (lease_until) WHERE status = 'LEASED'`
2. 큐 테이블의 `fillfactor`, 자동 vacuum 임계, 임대 열 분리 재설계, 배치 청구, `LISTEN/NOTIFY` 깨우기는 바꾸지 않는다(EXP-112 수치와 재검토 조건은 그 문서).

@@DECISION_NUMBERS@@

## 운영 절차 (이미 채워진 큰 테이블)

- 일반 `CREATE INDEX`는 만드는 동안 그 표의 쓰기를 막는다. Flyway는 마이그레이션을 트랜잭션 안에서 돌리므로 `CONCURRENTLY`를 직접 쓸 수 없다. 큰 운영 DB는 **배포 전에** 같은 이름·같은 정의로 먼저 만든다. V8의 `CREATE INDEX IF NOT EXISTS`가 그 뒤에는 건너뛴다.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payment_recovery_job_waiting_for_buyer
    ON payment_recovery_job (recovery_job_id) WHERE status = 'WAITING_FOR_BUYER';
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_settlement_payout_dispatch_leased
    ON settlement_payout_dispatch_outbox (lease_until) WHERE status = 'LEASED';
```

- 확인: `SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;`가 0행이어야 한다(실패해 INVALID로 남았으면 `DROP INDEX CONCURRENTLY` 후 다시). 사용 확인은 EXPLAIN이 새 인덱스를 고르는지로 한다.
- V7과 독립: V8은 V7의 내용에 기대지 않아 병합 순서가 바뀌어도 각각 적용된다. 이미 V8이 적용된 DB에 나중에 V7이 들어오면 Flyway가 순서 어긋남으로 검증에 실패하므로(`outOfOrder` 기본 꺼짐) 병합 순서는 V7, V8 순을 권한다.

## 실패 경로와 되돌리기

- 인덱스 생성 중 잠금 대기: `lock_timeout`(앱 연결 5초)을 넘으면 마이그레이션이 실패하고 앱은 시작하지 않는다. 같은 문장을 `CONCURRENTLY`로 먼저 실행하면 피한다.
- 되돌리기: `DROP INDEX IF EXISTS ix_payment_recovery_job_waiting_for_buyer; DROP INDEX IF EXISTS ix_settlement_payout_dispatch_leased;` 이전 코드는 이 인덱스를 몰라도 같은 결과를 낸다(질의 문장을 바꾸지 않았다). Flyway 이력 행은 남지만 인덱스 부재는 기능에 영향이 없고, 다시 올리려면 위 `CREATE INDEX IF NOT EXISTS`를 직접 실행한다.

## 한계

@@LIMITS@@
