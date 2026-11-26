# EXP-083: Reorder idempotency record 크기·증가·조회 측정

**상태:** 로컬 합성 데이터 측정 · 운영 규모/부하 근거 아님 · 연결: [Q-012](../01-requirements/requirements-index.md#assumptions-and-open-questions), [ADR-0077](../02-decisions/adr/ADR-0077-keep-reorder-idempotency-records.md), [Issue #620](https://github.com/Neo1228/ATLAS/issues/620)

## 목적과 범위

`order_reorder_request`는 Reorder 요청의 `Idempotency-Key`를 남겨 같은 요청의 재시도가 Cart에 다시 합산되지 않게 합니다. 이 record를 영구 보존해도 되는지, TTL 정리나 Order cascade가 필요한지 판단할 수치를 얻습니다. 측정 대상은 행당 크기, 요청당 증가량, 이 테이블을 읽는 쿼리, 대안이 추가하는 비용입니다. 부하 상황의 처리량과 지연은 범위 밖입니다.

## 테이블과 접근 경로 (코드에서 확인)

- DDL: `V1__baseline.sql`의 `order_reorder_request(seller_id uuid, buyer_ref varchar(256), idempotency_key varchar(256), source_order_id uuid, confirm_merge boolean, created_at timestamptz)`. index는 PK `(seller_id, buyer_ref, idempotency_key)` 하나뿐입니다. FK는 `seller`와 `purchase_order`(`source_order_id`)를 가리키고, `source_order_id`에는 index가 없습니다.
- 운영 코드가 이 테이블에 보내는 SQL은 `OrderReorderRepository`의 두 문장뿐입니다(`git grep -l order_reorder_request`로 확인, 나머지 두 곳은 테스트).
  - `SELECT source_order_id, confirm_merge ... WHERE seller_id = ? AND buyer_ref = ? AND idempotency_key = ?` (PK 전체 일치)
  - `INSERT ... ON CONFLICT (seller_id, buyer_ref, idempotency_key) DO NOTHING`
- Order 삭제 기능은 없습니다. 운영 코드의 `DELETE FROM`은 계정 MFA, 인증 throttle bucket, wishlist, PayPal webhook retry뿐입니다.
- 행은 Reorder가 성공해 Cart 합산까지 끝난 요청에서만 남습니다. 서비스는 record 삽입과 `carts.mergeItems`를 한 트랜잭션에서 실행하고, 합산이 거절되면 record도 롤백됩니다(통합 테스트가 409 뒤 `count(*) = 0`을 확인합니다). 같은 key의 재시도와 충돌은 행을 더하지 않습니다.
- 같은 source Order에 서로 다른 key로 보낸 요청은 각각 행이 됩니다. 행 수는 Order 수가 아니라 성공한 Reorder 요청 수로 늘어납니다.

## 환경·데이터·workload

- macOS arm64 · Colima Docker: PostgreSQL 18.6 image `postgres:18.6`(`PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on aarch64`), 일회용 container 하나(실험 뒤 제거), 설정 override 없음(`shared_buffers=128MB`, `full_page_writes=on`, `wal_compression=off`, `checkpoint_timeout=5min`).
- 스키마: `V1__baseline.sql` 전체를 적용한 DB에서 `CREATE TABLE m (LIKE order_reorder_request INCLUDING ALL)`로 컬럼·PK가 같은 복제 테이블을 만들어 측정했습니다. FK는 저장 크기를 바꾸지 않아 복제하지 않았습니다.
- 데이터 profile 두 가지. `seller_id`는 1개 고정, `source_order_id`는 무작위 uuid, `confirm_merge`는 10%만 true, `created_at`은 1초 간격.
  - typical: `buyer_ref`와 `idempotency_key`를 36자 UUID 문자열로 생성한 로컬 profile이다. 실제 길이 분포는 **외부 차단**: production에서 `SELECT length(buyer_ref), length(idempotency_key), count(*) FROM order_reorder_request GROUP BY 1, 2 ORDER BY 1, 2` 집계가 필요하다. V1은 길이별 용량을 확정하지 않고 profile 범위만 근거로 삼는다.
  - maxlen: 둘 다 252자(상한 256자에 가까운 값)인 로컬 경계 profile이다. production에서 이 길이가 나타나는지는 **외부 차단**이며, 위 집계 결과가 없으면 maxlen profile을 전형값으로 취급하지 않는다.
- 크기 측정은 `VACUUM (ANALYZE)` 뒤 `pg_relation_size`, `pg_indexes_size`, `pg_total_relation_size`로 읽었다. 기존 1천·1만·10만·100만 행의 profile별 n=1 측정 뒤 typical 100만 행을 추가 n=3으로 반복해 입력 생성 변동을 측정했다(결과는 아래 EXP-105 종결 결과). 나머지 행 수와 maxlen 반복 여부는 결정: 범위 밖; 용량 판단은 100만·1천만 typical 직접 측정으로 닫고, 재검토 조건은 maxlen profile이 production p95 입력으로 확인될 때다.
- 재현: [EXP-083-reorder-idempotency-retention.sql](EXP-083-reorder-idempotency-retention.sql).

## 결과

### 행당 크기 (측정, 1회씩)

| profile | 행 수 | heap | PK index | 합계(`pg_total_relation_size`) | 행당 합계 |
| --- | ---: | ---: | ---: | ---: | ---: |
| typical | 1,000 | 155,648 | 180,224 | 376,832 | 376.8 B |
| typical | 10,000 | 1,490,944 | 1,646,592 | 3,178,496 | 317.8 B |
| typical | 100,000 | 14,901,248 | 16,171,008 | 31,113,216 | 311.1 B |
| typical | 1,000,000 | 148,946,944 | 160,899,072 | 309,919,744 | 309.9 B |
| maxlen | 1,000 | 630,784 | 942,080 | 1,613,824 | 1,613.8 B |
| maxlen | 10,000 | 6,307,840 | 9,502,720 | 15,851,520 | 1,585.2 B |
| maxlen | 100,000 | 63,021,056 | 95,068,160 | 158,138,368 | 1,581.4 B |
| maxlen | 1,000,000 | 630,161,408 | 953,401,344 | 1,583,767,552 | 1,583.8 B |

- 행 한 개의 컬럼 값 크기(`pg_column_size`의 평균): typical 144 B, maxlen 584 B. 행이 2 KB TOAST 기준보다 작아 별도 TOAST 저장은 일어나지 않습니다.
- PK index가 heap보다 큽니다(typical 1.08배, maxlen 1.51배). PK가 `buyer_ref`와 `idempotency_key` 전체를 담고, 무작위 key라 leaf page가 가득 차지 않기 때문입니다.
- 행당 합계는 100만 행에서 typical 약 310 B, maxlen 약 1.58 KB로 수렴했습니다.

### 요청당 증가 (측정, typical, n = 5,000)

typical 100만 행 테이블에서 `CHECKPOINT` 뒤 `pgbench -n -t 5000 -c 1`로 단일 행 `INSERT`를 5,000번 실행했습니다(트랜잭션 5,000개, 실패 0).

- 저장 증가: `pg_total_relation_size`가 1,581,056 B 늘었습니다. 요청당 316.2 B이고 위 표의 행당 합계와 일치합니다.
- WAL: LSN 차이가 28,662,032 B(요청당 약 5.7 KB). 바로 이어 5,000건을 더 넣었을 때 21,926,944 B(요청당 약 4.4 KB). 무작위 key라 요청마다 서로 다른 index leaf page를 건드려 full page image가 대부분이라고 해석합니다. 이 해석은 `pg_walinspect`로 분해해 확인했습니다(아래 EXP-105 종결 결과: FPI 93%).

### 조회 (측정, typical 100만 행, 각 1회)

- Repository `find`와 같은 형태의 쿼리 `EXPLAIN (ANALYZE, BUFFERS)`: `Index Scan using m_pkey`, `Index Searches: 1`, 읽은 page 5개(shared hit 3 + read 2). 없는 key는 shared hit 4. 실행 시간은 0.065 ms와 0.028 ms이지만 단일 실행이고 다른 작업이 함께 도는 공유 머신이라 성능 근거로 쓰지 않습니다.
- seq scan을 일으키는 운영 쿼리는 없습니다(위 접근 경로 두 문장이 전부이고 둘 다 PK 일치).

### 대안이 더하는 비용 (측정, typical 100만 행)

| 항목 | 값 |
| --- | --- |
| TTL 정리용 `created_at` index | 22,487,040 B(행당 22.5 B, 합계 309.9 MB 대비 +7.3%) |
| Order cascade용 `source_order_id` index | 31,563,776 B(행당 31.6 B, +10.2%) |
| TTL 정리 500행 배치, `created_at` index 없음 | seq scan: 9,181 page read, 499,987행 필터. 90.4 ms(1회, 지연은 참고만) |
| TTL 정리 500행 배치, `created_at` index 있음 | 대상 선별 14 page(`shared hit=10 read=4`) |

index 없는 경우의 데이터 배치는 만료 대상(오래된 행)이 heap 끝에 몰린 최악에 가깝습니다. 배치마다 이만큼 읽는다는 뜻이 아니라, index가 없으면 배치당 테이블 전체까지 읽을 수 있다는 상한 사례입니다.

### 정리 뒤 같은 key 재요청 (측정, 로컬 통합 테스트, n = 1, 커밋하지 않은 임시 확인)

`BuyerFlowIntegrationTest.terminalCartOrderCanBeReorderedWithCurrentCatalogAndIdempotentCartMerge`(Testcontainers PostgreSQL 18.6)에 임시로 다음을 더해 실행하고 되돌렸습니다. 같은 key 재시도 뒤 Cart 수량은 5입니다. 이때 `DELETE FROM order_reorder_request WHERE idempotency_key = ?`로 record를 지우고 같은 key·같은 본문으로 다시 요청하면 **HTTP 200이고 같은 SKU 수량이 7**이 되었습니다(Reorder가 Cart에 한 번 더 합산됨). 새 Order가 만들어지는 것이 아니라 Cart 수량이 중복 합산되는 실패입니다. 명령: `./gradlew :atlas-application:test --tests 'com.atlas.BuyerFlowIntegrationTest.terminalCartOrderCanBeReorderedWithCurrentCatalogAndIdempotentCartMerge'` → BUILD SUCCESSFUL.

## 외삽과 후속 측정

- 기존 선형 외삽은 100만 행 typical 약 3.1 GB, maxlen 약 15.8 GB였다. typical 1천만 행은 이번에 직접 측정했으며 결과와 외삽 오차는 아래 EXP-105 종결 결과에 기록한다. maxlen 1천만 행은 결정: 범위 밖; 실제 길이 분포가 없어 최대값 profile을 10배 키우는 값의 정책 효용이 없고, 재검토 조건은 production 집계에서 252자 키가 p95 이상일 때다. 기존 15.8 GB 숫자는 선형 외삽으로 유지한다.
- 실제 운영의 key 길이 분포와 Reorder 요청률은 외부 차단이다. 필요한 입력은 위 길이 집계와 일별 성공 Reorder 수이며, 이 입력 전까지 V1은 기록을 영구 보존하고 local profile을 production 분포로 간주하지 않는다.

## 해석과 한계

- 이 측정은 합성 데이터와 한 대의 로컬 container에서 얻었습니다. 운영 규모, 지연, 장애 비용의 근거가 아닙니다.
- 행당 크기는 입력 길이와 함께 달라진다. Typical 1천만 행의 직접 측정은 아래에 기록한다; 100만 행 이전의 기존 측정값도 삭제하지 않는다.
- bloat·autovacuum·복제 지연은 **외부 차단**: production `pg_stat_user_tables`의 `n_live_tup`, `n_dead_tup`, `autovacuum_count`, `last_autovacuum` 및 `pg_stat_replication`의 `write_lag`, `flush_lag`, `replay_lag`가 필요하다. 저장소에서 `git grep -n -E 'DELETE FROM order_reorder_request|UPDATE order_reorder_request' -- '*.java' '*.sql'` 결과는 0건이다. V1은 append-only로 영구 보존한다; 재검토 조건은 production에서 `n_dead_tup > 0` 또는 `replay_lag`가 운영 SLO를 초과할 때다.
- 재시도 창은 **외부 차단**: 입력은 API 계약에 승인된 `Idempotency-Key` 재시도 기간과 만료 키 처리 규칙이다. 저장소 검색에서 Reorder TTL 설정은 0건이고, record 삭제 뒤 같은 key 요청이 Cart를 다시 합산한 n=1 측정(line 71)이 있다. V1은 record를 영구 보존하고 TTL 정리를 하지 않는다; 재검토 조건은 계약에 유효 기간과 만료 뒤 거절 동작이 모두 명시될 때다.
- 요청 빈도 제한은 **결정: 범위 밖, 배포별 proxy 정책**이다. 저장소 검색 `git grep -n -i -E 'limit_req|limit_conn|rate.?limit|request.rate|bucket4j' -- config compose.yaml load-tests`에서 0건이며, ADR-0048은 인증 경로에 한해 proxy limiter 부재를 기록한다. Reorder 전용 애플리케이션 limiter도 없다. 대안인 앱 내 고정 한도는 배포별 budget 없이 정하지 않는다; 재검토 조건은 production ingress가 Reorder route budget을 정하거나 해당 route의 429 rate-limit count가 1건 이상일 때다.

## EXP-105 종결 결과

재실행 표기: 아래 측정은 이 에이전트가 같은 SQL(`EXP-083-reorder-idempotency-retention.sql`, `postgres:18.6` 일회용 컨테이너, `V1__baseline.sql` 적용)로 직접 실행했다. 컨테이너·볼륨은 `atlasexp=t5db` 라벨을 붙이고 끝에 지웠다. 도구 설치 0건. 원자료(psql 출력)는 저장소에 올리지 않았고 집계 값은 아래 표가 전부다. 호스트는 다른 실험과 공유(load average 13~19)였으므로 크기(바이트)만 결론에 쓰고 시간은 맥락으로 둔다.

| 항목 | 유형 | 결과 |
| --- | --- | --- |
| G12 typical 1천만 행 크기와 선형 외삽 | 실행함(1) | 측정 3,104,980,992 B, 외삽 3,099,197,440 B 대비 +0.19% |
| 100만 행 typical 반복(입력 생성 변동) | 실행함(1) | 4회 추가, 합계 309,960,704~310,312,960 B(원래 측정 309,919,744 B 포함 5회 범위 0.13%) |
| 요청당 WAL의 FPI 해석 | 실행함(1) | 아래 |
| maxlen 반복·1천만 행 | 결정(2) | 위 측정 방법 절과 외삽 절의 결정 |
| 운영 길이 분포, 재시도 창, bloat·복제 지연 | 외부 차단(3) | 위 외삽 절의 외부 차단 문장(필요한 입력과 V1 동작을 적음) |

### G12: 1천만 행 직접 측정

가설(G12): 100만 행 이후 선형 외삽(1천만 행 typical 약 3.1 GB)이 실제 측정과 ±15% 안이다. 반증 조건: 오차 15% 초과.

| 행 수 | heap | PK index | 합계(`pg_total_relation_size`) | 행당 합계 | `created_at` index | `source_order_id` index |
| --- | --- | --- | --- | --- | --- | --- |
| 1,000,000(이번 4회) | 148,946,944 B | 160,940,032~161,292,288 B | 309,960,704~310,312,960 B | 310.0~310.3 B | 22,487,040 B | 31,563,776 B |
| 10,000,000(n=1) | 1,489,461,248 B | 1,615,077,376 B | 3,104,980,992 B | 310.5 B | 224,641,024 B | 315,441,152 B |

외삽은 100만 행의 행당 합계 309.9 B를 10배 한 3,099,197,440 B였고 실제는 3,104,980,992 B로 +0.19%다. 반증되지 않았다(가설 유지). 1천만 행에서 PK index는 heap의 1.08배(100만 행과 같음)이고 행당 합계는 310.5 B로 100만 행의 310.0~310.3 B와 같다. 대안 index의 크기도 10배에 가까운 선형이다(`created_at` +7.2%, `source_order_id` +10.2%). TTL 정리 500행 배치를 `created_at` index 없이 돌린 `EXPLAIN (ANALYZE)` 실행 시간은 1,194 ms(100만 행 90 ms의 약 13배, 공유 호스트라 시간은 맥락)이고 index가 있으면 2.7 ms다. 표본은 1천만 행에서 n=1이다(대사: 한 번에 25분이 걸려 반복하지 않았다). 결정: 1천만 행을 넘는 구간은 측정하지 않는다. 대안: 1억 행. 재검토 조건: 운영 예상 보존 행 수가 5천만 행을 넘거나(typical 약 15.5 GB) 행당 합계가 이 표의 310.5 B에서 10% 넘게 벗어날 때. maxlen 1천만 행(약 15.8 GB 외삽)은 위 외삽 절의 결정 그대로다.

### 요청당 WAL의 FPI 비중

100만 행 typical, `autovacuum=off`, 한 번의 `CHECKPOINT` 뒤 단일 행 `INSERT`를 `pgbench -n -t 5000 -c 1`로 5,000번, 이어서 같은 절차로 한 번 더(매 회 직전에 `CHECKPOINT`) 실행하고 `pg_walinspect`의 `pg_get_wal_stats`로 WAL을 레코드와 full page image로 가른 결과(원래 문서의 "FPI가 대부분이라고 해석하지만 `pg_waldump`로 확인하지 않았다"를 대체한다):

| 회 | WAL 전체 | 레코드(FPI 제외) | FPI | FPI 비중 | WAL 레코드 수 | 요청당 WAL | 요청당 FPI 제외 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 28,613,656 B | 1,731,329 B | 26,741,176 B | 93.5% | 15,090 | 5,723 B | 346 B |
| 2 | 28,870,936 B | 1,783,702 B | 26,945,128 B | 93.3% | 15,101 | 5,774 B | 357 B |

해석: 원래 문서의 요청당 WAL 5.7 KB·4.4 KB는 거의 전부(93%) full page image다. 무작위 key라 요청마다 다른 index leaf page를 `CHECKPOINT` 이후 처음 건드리기 때문이라는 설명과 맞는다. 레코드 자체는 요청당 약 350 B(요청당 WAL 레코드 3개)다. 원래 문서의 두 번째 측정(4.4 KB)이 첫 측정보다 작았던 이유(체크포인트 없이 이어 실행해 이미 dirty인 page가 늘어난 것)는 이번 두 회가 모두 `CHECKPOINT` 직후라 재현하지 않았다. 결정: "체크포인트 없이 이어 실행하면 FPI가 줄어든다"는 부분은 이 측정으로 확인하지 않았고 주장하지 않는다. 재검토 조건: 복제 지연·WAL 증가가 운영 지표로 문제가 될 때(위 외부 차단 항목의 입력이 생길 때).
