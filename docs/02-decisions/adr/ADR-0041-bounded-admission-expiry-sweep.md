# ADR-0041: Bounded, keyset-based admission expiry sweep

- 상태: Proposed
- 날짜: 2026-11-17
- 추적: [Issue #309](https://github.com/Neo1228/ATLAS/issues/309)

## 맥락

Order/checkout expiry는 ADR-0039에서 batch/time budget으로 bounded되었습니다. Admission queue expiry(`AdmissionServiceImpl.expireDueTickets`)는 모든 due seller ID를 조회한 뒤 seller마다 due SKU를 추가 조회하고 pair마다 `REQUIRES_NEW` transaction으로 counter lock·bulk expiry·FIFO promotion을 수행했습니다. Candidate list와 transaction fan-out에 상한이 없었고, 여러 application instance가 같은 counter lock에서 기다릴 수 있었습니다.

V1의 partial index `admission_ticket_expiry_idx (expires_at)`는 due range를 찾지만 cursor tie-break `(expires_at, ticket_id)`를 제공하지 않아 ordered candidate pagination에 incremental sort가 필요했습니다.

## 결정

### Candidate scan과 progression

- V41은 기존 partial expiry index를 `(expires_at, ticket_id)` composite B-tree로 교체합니다. Candidate query가 반환하는 `seller_id`, `sku_id`는 page가 작으므로 index에 INCLUDE하지 않아 index footprint를 제한합니다.
- Worker는 due ticket row를 `ORDER BY expires_at, ticket_id`로 LIMIT 조회하고, keyset cursor `(expires_at, ticket_id)`를 기억해 다음 tick에서 이어 읽습니다. Candidate window는 `2 × batchSize`; batch 기본 100, 허용 범위 1–1,000입니다.
- 같은 window 내 ticket rows는 seller-SKU key로 deduplicate합니다. 한 hot SKU의 수천 ticket이 candidate window를 채우더라도, window cursor가 row 단위로 앞으로 진행해 뒤의 seller-SKU pairs가 같은 첫 페이지에 영구 고정되지 않습니다.
- Cursor는 process memory에 저장되고 `synchronized` scheduler method가 갱신합니다. Candidate page가 끝까지 소진되고 남은 row가 없으면 cursor를 초기화합니다. Process restart는 cursor를 초기화해 안전하게 처음부터 재시작합니다.

### Work budget, locking, transaction

- Tick loop는 최대 batchSize unique seller-SKU attempts 또는 time budget(기본 PT2S)에 도달하면 멈춥니다. Candidate query에는 JDBC statement timeout을, per-pair `REQUIRES_NEW` transaction에는 같은 policy timeout을 적용합니다.
- 각 pair는 기존 counter lock, bulk expiry, FIFO promotion 경로를 사용합니다.
- Multi-instance counter lock은 `FOR UPDATE SKIP LOCKED`로 비차단 획득합니다. Busy pair는 skip되며 ticket-row cursor는 계속 전진하고, cursor wrap 이후 재시도합니다.
- Missing counter·pair-specific transaction failure는 해당 pair만 로그하고 남은 batch를 계속 처리합니다.

설정 키: `atlas.admission.expiry-sweep-batch-size`, `atlas.admission.expiry-sweep-time-budget`; 환경 변수 `ATLAS_ADMISSION_EXPIRY_SWEEP_BATCH_SIZE`, `ATLAS_ADMISSION_EXPIRY_SWEEP_TIME_BUDGET`.

## Index와 측정

V41은 `admission_ticket_expiry_idx`를 다음 partial covering path로 교체합니다.

```sql
CREATE INDEX admission_ticket_expiry_idx
    ON admission_ticket (expires_at, ticket_id)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');
```

Candidate query는 seller/SKU columns를 heap에서 bounded page rows에 대해서만 읽습니다. EXP-029의 1,000,000 synthetic rows(500k due + 500k future)에서 old timestamp-only index는 first page 176.840 ms, cursor page 188.910 ms(Incremental Sort)였고, V41 index는 각각 2.369 ms / 2.585 ms(Index Scan, Sort 없음), index size 39 MB였습니다.

## 대안과 절충

- **Group by seller-SKU + LIMIT:** full due ticket range를 매 tick scan/aggregate해야 했습니다. 500k due row measurement는 약 118 ms/6,024 buffers였습니다. LIMIT은 group 결과만 제한해 scan/cursor progression 문제를 해결하지 못해 기각했습니다.
- **Ticket-row LIMIT 후 JVM distinct만:** duplicate rows가 busy/hot pair로 채워질 경우 다른 pair를 starvation시킬 수 있습니다. Keyset cursor로 row scan position을 진전시킵니다.
- **All-pairs one transaction:** 여러 counter/queue row lock과 rollback scope가 커지므로 per-pair transaction을 유지합니다.
- **INCLUDE seller_id/sku_id:** 1M active rows에서 covering index는 72 MB였고, no-INCLUDE composite index는 39 MB입니다. Candidate page가 최대 2k rows여서 limited heap fetch를 감수하고 smaller index를 선택했습니다.

## 한계

- DB cursor candidate page는 composite expiry index로 ordered range scan되며 query timeout을 가집니다. Query timeout이 반복적으로 초과되면 해당 tick은 candidate를 처리하지 않고 다음 tick에 같은 cursor에서 재시도합니다. Database가 계속 timeout 상태면 expiry progress도 멈춥니다.
- A single seller-SKU bulk update/promotion transaction can exceed the tick budget; transaction timeout bounds it. A timeout rolls back that pair and the keyset proceeds to later candidates; the pair is retried after cursor wrap.
- Keyset cursor is process-local; restart resets it. Ticket expiry and per-pair operations are idempotent, so replay is safe.
- EXP-029 is local warm-cache SQL-only synthetic data; not production SLA or concurrent throughput evidence.

## 검증

- `AdmissionExpiryCandidateIndexMigrationIntegrationTest`: V41 replacement, first/cursor plans use composite index without Sort/Seq Scan.
- `ScheduledExpiryMultiInstanceIntegrationTest`: 25 due seller-SKU pairs drain over 3 ticks with batch 10; two application contexts skip a held counter and later expiry completes.
- `AdmissionExpiryTransactionIntegrationTest`: an unlocked pair commits while a later locked pair is skipped and expires on a later tick.
- `AdmissionServiceTest`: missing counter does not abort later candidate pairs.
- `AdmissionExpirySweepPolicyTest`: batch/time validation.
- EXP-029 reproduces the old/new first and cursor plans.
