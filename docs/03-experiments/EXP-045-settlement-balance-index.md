# EXP-045: 판매자 정산 잔액 조회의 전체 스캔 제거

**추적 이슈:** [#434](https://github.com/Neo1228/ATLAS/issues/434)

**상태:** 실측 완료 · 로컬, PostgreSQL 18.6

## 왜 이 실험을 했는가

`SettlementPayoutService.summary` 와 `balance` 는 판매자가 지급 화면을 열 때마다 호출된다. 그 구현은 `SettlementJournalRepository.sellerBalance` 로, 판매자의 **모든** `settlement_entry` 를 SUM 한다.

```sql
SELECT COALESCE(SUM(CASE WHEN account_code = 'SELLER_PAYABLE' THEN ... END), 0) AS payable,
       ...
FROM settlement_entry
WHERE seller_id = ?
```

그런데 `settlement_entry` 의 인덱스는 `(entry_id)` PK 와 `(journal_id, account_code, seller_id)` 유니크뿐이었다. **`seller_id` 단독 인덱스가 없었다.**

## 측정

4,600행 규모에서 같은 판매자의 잔액 집계를 `EXPLAIN (ANALYZE, BUFFERS)` 로 측정했다.

### 인덱스 없음 (수정 전)

```text
Aggregate  (cost=2993.51..2993.52 rows=1) (actual time=9.770..9.774 rows=1)
  Buffers: shared hit=2936
  ->  Seq Scan on settlement_entry  (cost=0.00..2993.50 rows=2) (actual time=9.221..9.758 rows=2)
        Buffers: shared hit=2936
```

**Seq Scan, 2,936 버퍼.** 판매자 1명의 잔액을 구하려고 테이블 전체를 읽었다.

### 인덱스 추가 후

```text
->  Bitmap Heap Scan on settlement_entry  (actual time=0.032..0.034 rows=2)
      Buffers: shared hit=4
      ->  Bitmap Index Scan on settlement_entry_seller_account_side_idx  (actual time=0.024..0.024)
```

**4 버퍼.** 2,936 → 4, **734배 감소.**

## 왜 문제가 심각한가

Seq Scan 은 테이블 크기에 선형이다. 원장은 **줄어들지 않는다.** 모든 판매자의 모든 capture·refund·payout 이 영원히 누적된다.

따라서:

- 판매자가 오래 활동할수록 자기 잔액 조회가 느려진다
- 판매자 수가 늘어날수록 테이블이 커져 **모든** 판매자의 조회가 느려진다
- 지급 화면은 판매자가 돈을 받기 위해 여는 화면이다. 느려지는 것이 곧 정산 지연이다

이것은 4,600행에서 9.8ms 였다. 100배 규모(46만 행)면 같은 비율로 약 1초, 그 이상이면 지급 화면이 사실상 응답하지 않는다.

## 수정

V53 마이그레이션으로 `(seller_id, account_code, entry_side)` 인덱스를 추가했다.

세 컬럼을 모두 담은 이유: `sellerBalance` 의 `WHERE` 는 `seller_id` 이고, `CASE` 가 `account_code`·`entry_side` 로 분기한다. 세 컬럼이 인덱스에 있으면 Index Only Scan 이 가능해 heap 접근이 사라진다.

## 한계 (정직하게)

1. **집계 자체는 여전히 선형이다.** 인덱스를 타도 판매자의 entry 수에 비례한다. 판매자당 수십만 건이 되면 여전히 느려진다. 그 경우 증분 잔액 테이블(rollup)이 필요하다.
2. **측정 규모가 4,600행이다.** 46만·460만 행에서의 실측은 하지 않았다. "선형"이라는 구조적 판단과 4,600행 실측만 근거다.
3. **로컬 단일 노드다.** 캐시가 데워진 상태(`shared hit`)이며, 콜드 캐시·디스크 IO 는 다르다.
4. **일반 `CREATE INDEX` 를 썼다.** 저장소 기존 마이그레이션(V39/V41/V42)과 같은 방식이다. 운영에서 무중단이 요구되면 `CONCURRENTLY` 로 분리해야 한다.

## 후속 후보

판매자당 entry 가 수십만 건을 넘으면 `settlement_seller_balance` rollup 테이블(증분 갱신)이 다음 단계다. 지금은 측정된 규모에서 인덱스로 충분하다.
