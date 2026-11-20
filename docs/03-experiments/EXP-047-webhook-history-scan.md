# EXP-047: 웹훅 재시도 이력 요약의 전체 스캔 제거

**추적 이슈:** [#434](https://github.com/Neo1228/ATLAS/issues/434)

**상태:** 실측 완료 · 로컬, PostgreSQL 18.6

## 왜 이 실험을 했는가

`PayPalWebhookRetryHistoryService.summary()` 는 운영자 대시보드에 최근 24시간 재시도 outcome 별 건수를 보여준다.

```sql
SELECT outcome, COUNT(*) AS outcome_count
FROM paypal_webhook_retry_attempt_history
WHERE COALESCE(finished_at, claimed_at) >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
  AND COALESCE(finished_at, claimed_at) <= CURRENT_TIMESTAMP
GROUP BY outcome
```

`COALESCE(...)` 술어는 **인덱스를 사용할 수 없다.** 그리고 이력 테이블은 append-only 라 절대 줄지 않는다.

## 측정

200,000행을 넣고 `EXPLAIN (ANALYZE, BUFFERS)` 로 비교했다.

### 수정 전

```text
->  Seq Scan on paypal_webhook_retry_attempt_history
      Buffers: shared hit=2705
```

**2,705 버퍼, Seq Scan.**

### 수정 후

생성 컬럼 + 인덱스:

```sql
ALTER TABLE paypal_webhook_retry_attempt_history
    ADD COLUMN recorded_at TIMESTAMPTZ
        GENERATED ALWAYS AS (COALESCE(finished_at, claimed_at)) STORED;

CREATE INDEX paypal_webhook_retry_history_recorded_idx
    ON paypal_webhook_retry_attempt_history (recorded_at, outcome);
```

쿼리를 `recorded_at` 으로 바꾼 뒤:

```text
->  Index Only Scan using paypal_webhook_retry_history_recorded_idx
      Buffers: shared hit=24 read=7
```

**31 버퍼, Index Only Scan. 약 87배 감소.**

## 왜 생성 컬럼인가

`COALESCE` 를 SQL 에서 없애야 인덱스가 동작한다. 저장 생성 컬럼으로 두면:

- 애플리케이션 쓰기 경로를 바꾸지 않아도 된다 (`finished_at` 만 갱신하면 자동 반영)
- 기존 행도 `ALTER TABLE` 시점에 채워진다
- 값이 항상 일관된다 (트리거처럼 갱신 누락 위험이 없다)

`(recorded_at, outcome)` 로 두 컬럼을 인덱스에 넣은 이유: `WHERE` 는 `recorded_at` 범위, `GROUP BY` 는 `outcome` 이다. 둘 다 있으면 heap 접근 없이 Index Only Scan 이 된다.

## 한계 (정직하게)

1. **2쿼리 중 첫 번째만 고쳤다.** `summary()` 의 두 번째 쿼리(`paypal_webhook_retry` 전체 `COUNT(*) FILTER`)는 여전히 전체 스캔이다. 그 테이블은 `PENDING`/`EXHAUSTED` 부분 인덱스가 있어 상황이 다르지만, 별도 측정이 필요하다.
2. **로컬 단일 노드, 워밍된 캐시다.**
3. **일반 `CREATE INDEX` 를 썼다.** 운영 무중단이 필요하면 `CONCURRENTLY` 로 분리해야 한다.
4. **생성 컬럼은 저장 공간을 쓴다.** 8바이트 × 행 수다. 이력 테이블 크기를 감안하면 수용 가능하지만, 아카이빙 정책이 생기면 함께 재검토해야 한다.

## 후속 후보

`paypal_webhook_retry` 의 `COUNT(*) FILTER` 집계도 단일 `GROUP BY state` 로 바꾸고, 완료·소진 행 아카이빙 정책을 정하는 것이 다음 단계다.
