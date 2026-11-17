# ADR-0041: Bounded, keyset-based admission expiry sweep

- 상태: Accepted
- 날짜: 2026-11-17
- 추적: [Issue #309](https://github.com/Neo1228/ATLAS/issues/309), follow-up [Issue #315](https://github.com/Neo1228/ATLAS/issues/315)

## 맥락

Order/checkout expiry는 ADR-0039에서 batch와 시간 예산으로 제한됐지만 admission queue expiry(`AdmissionServiceImpl.expireDueTickets`)는 모든 due seller ID를 조회한 뒤 seller마다 due SKU를 추가 조회하고 pair마다 `REQUIRES_NEW` transaction으로 counter lock·bulk expiry·FIFO promotion을 수행했습니다. Candidate list와 transaction fan-out에 상한이 없고, 여러 application instance가 같은 counter lock에서 blocking 대기할 수 있었습니다.

V1 partial index `admission_ticket_expiry_idx (expires_at)`는 만료 시각 조회는 지원하지만 `(expires_at, ticket_id)` cursor tie-break가 없어 ordered pagination에 incremental sort가 필요했습니다.

## 결정

### Candidate scan과 progression

- V41은 기존 expiry partial index를 `(expires_at, ticket_id)` composite B-tree로 교체합니다. Candidate query가 반환하는 seller/SKU는 bounded page에서만 heap-fetch하며 index에 INCLUDE하지 않아 footprint를 제한합니다.
- Worker는 due ticket rows를 `ORDER BY expires_at, ticket_id LIMIT candidateWindow`로 읽습니다. Cursor는 마지막 consumed `(expires_at, ticket_id)`이며 다음 tick은 tuple comparison `> (cursorExpiresAt, cursorTicketId)`에서 이어갑니다. Candidate window는 `2 × batchSize`, batch 기본 100, 허용 범위 1–1,000입니다.
- 같은 window의 ticket rows는 seller-SKU key로 deduplicate합니다. Cursor는 row 단위로 진행해 hot seller-SKU의 중복 rows가 이후 pairs를 영구 starvation시키지 않습니다.
- Cursor는 process memory에 저장되고 scheduler method는 `synchronized`입니다. Empty page는 cursor를 초기화합니다. Process restart도 cursor를 초기화하지만 per-pair expiry는 idempotent라 replay가 안전합니다.

### Budget, locking, transaction

- 설정 키: `atlas.admission.expiry-sweep-batch-size`, `atlas.admission.expiry-sweep-time-budget`; 환경 변수 `ATLAS_ADMISSION_EXPIRY_SWEEP_BATCH_SIZE`, `ATLAS_ADMISSION_EXPIRY_SWEEP_TIME_BUDGET`.
- Batch 기본값 100, time budget 기본값 PT2S. Budget은 **1초 이상의 정수 초**만 허용합니다. Spring/JDBC transaction/query timeout API의 초 단위 resolution보다 작은 설정은 실제 bound와 다르게 적용될 수 있어 거부합니다.
- Candidate query에는 configured time budget의 JDBC statement timeout을 적용합니다. Query 성공 후 deadline이 이미 지났더라도 첫 candidate pair는 처리/skip해 cursor를 진전시킵니다. 그렇지 않으면 느리지만 성공하는 동일 첫 page가 매 tick deadline을 소진해 영구 반복될 수 있습니다.
- 첫 pair와 이후 pair의 `REQUIRES_NEW` transaction timeout은 각 실행 시점의 **남은 deadline seconds**를 기준으로 설정하고, 정수초 resolution 때문에 최소 1초를 보장합니다. Deadline이 지난 뒤 시작한 첫 pair는 최대 1초 transaction timeout을 받고, 이후 pair는 시작하지 않습니다. 따라서 tick은 configured budget에 최대 약 1초의 pair-timeout allowance와 commit/scheduler overhead가 더해질 수 있습니다; PT2S가 모든 경우의 strict wall-clock ceiling이라는 뜻은 아닙니다.
- 다른 instance가 counter row를 점유하면 `FOR UPDATE SKIP LOCKED`로 기다리지 않고 pair를 건너뛰며 cursor를 진행합니다. Missing counter·pair-specific transaction failure는 해당 pair만 로그하고 batch의 나머지를 계속 처리합니다.
- Candidate query가 statement timeout/error로 실패하면 결과가 없으므로 cursor를 변경하지 않고 다음 tick에서 재시도합니다. 반복 failure의 WARN은 instance당 최대 1분에 한 번 출력해 1초 polling에 비례한 로그 flood를 막습니다. DB가 candidate 한 row도 timeout 내 읽지 못하는 동안에는 안전한 cursor advance가 불가능하므로 expiry progress는 query 회복까지 정지할 수 있습니다.

각 pair의 기존 counter row serialization, bulk expiry update, FIFO promotion 순서는 유지합니다.

## Index와 측정

V41은 `admission_ticket_expiry_idx`를 다음 partial index로 교체합니다.

```sql
CREATE INDEX admission_ticket_expiry_idx
    ON admission_ticket (expires_at, ticket_id)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');
```

Candidate query는 seller/SKU columns를 bounded page rows에 대해서만 heap에서 읽습니다. EXP-029의 1,000,000 synthetic rows(500k due + 500k future)에서 old timestamp-only index는 first page 176.840 ms, cursor page 188.910 ms(Incremental Sort)였고, V41 index는 각각 2.369 ms / 2.585 ms(Index Scan, Sort 없음), index size 39 MB였습니다.

## 대안과 절충

- **Group by seller-SKU + LIMIT:** full due ticket range를 매 tick scan/aggregate해야 했습니다. 500k due row measurement는 약 118 ms였습니다. LIMIT은 output만 제한하고 scan/progression 문제를 해결하지 못해 기각했습니다.
- **Ticket-row LIMIT 후 JVM distinct만:** hot/busy pair의 중복 rows가 window를 차지해 다른 pair를 starvation시킬 수 있습니다. Keyset cursor로 scan position을 진전시킵니다.
- **All pairs in one transaction:** 여러 counter/queue row lock과 rollback scope가 커지므로 per-pair transaction을 유지합니다.
- **INCLUDE seller_id/sku_id:** 1M active rows에서 covering index는 72 MB였고 no-INCLUDE composite index는 39 MB였습니다. 최대 2k row heap fetch를 감수하고 작은 index를 선택했습니다.
- **Strictly abort at deadline even after query:** query가 budget을 소진하고도 성공하면 첫 page를 매 tick 다시 읽을 수 있습니다. Cursor progress를 위해 첫 candidate pair에만 최대 1초 timeout allowance를 둡니다.

## 한계

- Query timeout이 매번 발생하면 cursor를 안전하게 이동할 수 없어 작업은 DB 회복 전까지 진행되지 않습니다. WARN은 1분 단위로 제한되며, metrics/alerting은 별도 운영 이슈입니다.
- Pair transaction이 deadline 직전에 시작되거나 deadline 이후 first-candidate allowance를 쓰면 budget보다 최대 약 1초 더 걸릴 수 있습니다. JDBC/Spring timeout보다 오래 blocking하는 외부 작업은 pair transaction 내부에 없어야 합니다.
- Keyset cursor는 process-local이며 restart 시 초기화됩니다.
- EXP-029는 로컬 warm-cache SQL-only synthetic data이며 production SLA나 동시 처리량 evidence가 아닙니다.

## 검증

- `AdmissionExpiryCandidateIndexMigrationIntegrationTest`: V41 replacement, first/cursor plans use composite index without Sort/Seq Scan.
- `ScheduledExpiryMultiInstanceIntegrationTest`: 25 due seller-SKU pairs drain over 3 ticks with batch 10; two application contexts skip a held counter without blocking and complete on later tick.
- `AdmissionExpiryTransactionIntegrationTest`: unlocked pair commits while another pair's counter is locked; later tick expires the skipped pair.
- `AdmissionServiceTest`: missing counter isolation and a candidate query that returns after the deadline still leads to first-pair progress.
- `AdmissionExpirySweepPolicyTest`: batch/time configuration validation including whole-second minimum.
- EXP-029 reproduces old/new first and cursor query plans.
