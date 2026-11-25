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
  - typical: `buyer_ref`와 `idempotency_key`가 모두 무작위 UUID 문자열(36자). 실제 클라이언트가 보내는 key 형태가 아니라 가정입니다.
  - maxlen: 둘 다 252자(상한 256자에 가까운 값). 상한에 가까운 입력을 가정한 값입니다.
- 크기 측정은 `VACUUM (ANALYZE)` 뒤 `pg_relation_size`, `pg_indexes_size`, `pg_total_relation_size`로 읽었습니다. 행 수 4단계(1천, 1만, 10만, 100만)마다 한 번씩, profile당 4회 측정했고 반복은 하지 않았습니다. 같은 입력을 다시 넣었을 때의 변동은 측정하지 않았습니다.
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
- WAL: LSN 차이가 28,662,032 B(요청당 약 5.7 KB). 바로 이어 5,000건을 더 넣었을 때 21,926,944 B(요청당 약 4.4 KB). 무작위 key라 요청마다 서로 다른 index leaf page를 건드려 full page image가 대부분이라고 해석합니다. 이 해석은 `pg_waldump`로 확인하지 않았습니다.

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

## 추정 (측정값을 선형 외삽한 것, 측정하지 않음)

- 100만 행 측정의 행당 합계로 환산하면 1,000만 행은 typical 약 3.1 GB, maxlen 약 15.8 GB입니다. 100만 행을 넘는 구간은 측정하지 않았고, 행 수가 커지면 index 높이와 page 분할이 달라질 수 있습니다.
- 실제 운영의 key 길이 분포와 Reorder 요청률은 알지 못합니다. 위 두 profile은 하한과 상한에 가까운 가정입니다.

## 해석과 한계

- 이 측정은 합성 데이터와 한 대의 로컬 container에서 얻었습니다. 운영 규모, 지연, 장애 비용의 근거가 아닙니다.
- 행당 크기는 입력 길이에 선형입니다. 정책 판단에는 "행 수 × 310 B ~ 1.6 KB"만 필요하고, 이 곱은 측정된 구간(100만 행 이하)에서만 확인했습니다.
- bloat, autovacuum 동작, 백업·복제 지연에 미치는 영향은 측정하지 않았습니다. 이 테이블은 삭제와 갱신이 없어 dead tuple이 생기지 않는다는 것은 코드에서 확인한 사실입니다.
- 클라이언트가 실제로 필요한 재시도 창은 확인하지 못했습니다. 저장소의 API 문서와 컨트롤러는 `Idempotency-Key`의 유효 기간을 정의하지 않습니다.
- 프록시 계층의 요청 빈도 제한이 Reorder에 걸려 있는지는 확인하지 못했습니다. 애플리케이션 코드에는 이 endpoint의 빈도 제한이 없습니다.
