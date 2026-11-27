# ADR-0101: 폴링 질의 두 개에 부분 인덱스를 추가하고 환불 교착을 고치며, 큐 레이아웃·깨우기·락 구성·두 DB 원자성은 현행을 유지한다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-27
- 관련 결정: [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)
- 근거: [EXP-112](../../03-experiments/EXP-112-queue-wakeup-lock-crossdb-alternatives.md), 이슈 [#715](https://github.com/Neo1228/ATLAS/issues/715)

## 문제

큐 테이블 레이아웃(HOT 갱신, `fillfactor`, 자동 vacuum), 1초 폴링, 락 구성, 두 DB 사이 원자성을 "더 나은 대안이 있는가"로 측정했다. 결제 복구 worker가 1초마다 도는 질의 하나와 지급 worker의 청구 질의 하나는 완료된 이력 행 수에 비례해 읽었다(EXP-112 Q9). 락 순서 순열 시험은 같은 멱등 키 동시 환불에서 교착 하나를 드러냈다. 그 외 후보는 수치가 현행 유지를 지지했다.

## 결정

1. **V8 마이그레이션으로 부분 인덱스 2개를 추가한다.** 열·제약·행 의미는 그대로다.
   - `ix_payment_recovery_job_waiting_for_buyer ON payment_recovery_job (recovery_job_id) WHERE status = 'WAITING_FOR_BUYER'`
   - `ix_settlement_payout_dispatch_leased ON settlement_payout_dispatch_outbox (lease_until) WHERE status = 'LEASED'`
2. **같은 멱등 키 동시 환불의 교착을 고친다.** `PaymentRefundRepository.updateResult`가 UPDATE 앞에서 같은 행을 `SELECT 1 ... FOR UPDATE`로 먼저 잡는다(약한 락을 쥔 채 `FOR UPDATE`로 올리는 순열 제거). 락 순서 순열 시험(실코드 6,780 겹침)에서 `40P01` 1건, 수정 뒤 1,500회 0건. 동작·스키마·설정은 그대로이고 `RefundOperationRowLockIntegrationTest`가 수정을 되돌리면 250쌍 중 7쌍에서 실패한다.
3. **현행을 유지한다**(수치와 재검토 조건은 EXP-112): 폴링(`LISTEN/NOTIFY`는 8 클라이언트에서 커밋 처리량 0.37배, 32 클라이언트에서 0.21배), advisory 락 키 구성(64비트 충돌 0쌍), 지급 예약의 advisory 락(SERIALIZABLE은 처리량 0.351배·40001 89.8%), 두 DB 사이 workflow·outbox(2PC 시제품은 코디네이터 사망 시 같은 SKU 쓰기가 `lock_timeout`까지 100% 막히고 고아 prepared 트랜잭션이 vacuum 기준점을 고정). 큐 테이블 `fillfactor` 75(HOT +0.1%p, 크기 1.9배), 자동 vacuum 임계(p99 개선 없음), 임대 열 분리(WAL 0.99–1.00배), 배치 청구(호출 4.7–5.0배 감소, 절대 약 3 호출/s)는 깨끗한 창 2개에서 사전 등록 기준에 못 미쳐 바꾸지 않는다.

## 근거 (EXP-112, 같은 창에서 동시에 돌린 A0·A5 n=2쌍, 호스트 load1 7.9→11.6, 라벨은 `queuetx`뿐)

| 측정 | A0 현행 | A5 인덱스 2개 |
|---|---|---|
| `resumeExpiredApprovalWaits` 호출 p50 | 69.9–71.8 ms | 1.2 ms |
| 지급 `claim` 호출 p50 | 41.0–41.3 ms | 6.4–6.8 ms |
| `resume` 호출당 블록(57.5만 행) | 10,584 | 7 |
| 지급 `claim` 호출당 블록(20만 / 100만 행) | 7,402 / 36,030 | 10 / 11 |
| 결제 복구 청구 p99 | 31.1–33.6 ms | 30.5–35.2 ms |
| WAL(KB/완료 일감) | 5.97–6.06 | 5.88–5.90 |

- **사전 등록 규칙에서 벗어난 점**: EXP-112 결정 규칙은 Q9~Q11이 모두 성립하고 회귀가 없어야 출하였다. Q11의 "지급 claim p50 ≤ A0의 10%"가 발동했다(쌍 run 6.4–6.8 ms 대 41 ms = 15.6–16.6%, 6팔 동시 창 5.1–5.3 대 44.2–49.4 ms = 10.7–11.5%). Q7(p99 악화 ≤ 10%)도 한 창에서 A5 p99 비 1.41이 나와 발동했다(같은 창 안 A0도 17.6 → 22.7 ms로 흔들리는 꼬리 값이고, 청구 p50 비는 0.91–1.15, 쌍 run p99 비 0.98·1.05). 남은 시간은 backlog(약 1,900행)를 읽고 정렬하는 비용이라 이력 크기와 무관하며, 같은 질의는 6.3배 빨라졌다. 이 임계는 backlog 하한을 모르고 정했다. 그래서 출하하되 벗어났음을 기록한다.
- 바꾸지 않는 것의 근거: 실제 부하에서 `payment_recovery_job` HOT 비율은 3.4%지만 청구 단독은 78%이고(Q2), 10분 동안 죽은 튜플은 라이브 행의 14.1%에 그쳐 자동 vacuum이 돌지 않아도 청구 p99에 영향이 없었다(Q8, A2 p99 비 0.98–1.11). 깨우기(`LISTEN/NOTIFY`)는 지연 0.69–0.77 ms 대 폴링 402–510 ms로 낫지만 커밋 처리량이 8 클라이언트에서 0.51배이고 체크아웃 경로가 worker를 거치지 않아 출하하지 않는다(EXP-112 결과 2).

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

- 되돌리기(교착 수정): `PaymentRefundRepository.updateResult`에서 추가한 `SELECT 1 ... FOR UPDATE` 문장 하나를 지운다(마이그레이션·설정 변경 없음). 되돌리면 같은 키 동시 환불의 교착이 다시 생기고 회귀 시험이 그것을 잡는다.

- 인덱스 생성 중 잠금 대기: `lock_timeout`(앱 연결 5초)을 넘으면 마이그레이션이 실패하고 앱은 시작하지 않는다. 같은 문장을 `CONCURRENTLY`로 먼저 실행하면 피한다.
- 되돌리기(인덱스): `DROP INDEX IF EXISTS ix_payment_recovery_job_waiting_for_buyer; DROP INDEX IF EXISTS ix_settlement_payout_dispatch_leased;` 이전 코드는 이 인덱스를 몰라도 같은 결과를 낸다(질의 문장을 바꾸지 않았다). Flyway 이력 행은 남지만 인덱스 부재는 기능에 영향이 없고, 다시 올리려면 위 `CREATE INDEX IF NOT EXISTS`를 직접 실행한다.

## 한계

- 시간 값은 단일 호스트의 가짜 일감이다(PayPal·위치 호출은 200 ms·10 ms 대기로 대체). 깨끗한 같은 창 비교는 A0 대 A5 4쌍, A1~A4는 2창이다. 3 run씩 두 묶음은 호스트 load1 29~33, 6팔 동시 창 3은 끝에 다른 에이전트의 스택이 보여 오염으로 분리했다.
- 블록 수·HOT·WAL은 호스트 부하와 무관한 서버 쪽 개수다.
- 이력 50만(결제 복구)·10만(지급)을 넘는 규모에서 인덱스 크기와 갱신 비용은 측정하지 않았다. 지급 인덱스는 `LEASED` 행만 담아 크기가 backlog에 비례한다(8 KB).
- 사용자 승인은 확인되지 않았다.
