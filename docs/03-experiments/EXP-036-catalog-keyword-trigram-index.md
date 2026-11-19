# EXP-036: keyword search predicate와 pg_trgm index

**추적 이슈:** [#344](https://github.com/Neo1228/ATLAS/issues/344)

**상태:** Measured — 100k dataset에서 EXPLAIN(ANALYZE, BUFFERS) 비교

## 목적

EXP-035의 읽기 부하에서 가장 느린 라우트는 `needle` 검색이었습니다. 원인 후보는 두 가지였습니다.

1. 키워드 조건이 **인덱스를 쓸 수 없는 형태**여서 listing 순서로 전체 행을 훑는지.
2. 단순히 100k 규모에서 정렬 비용이 큰지.

## 기존 형태와 문제

```sql
AND (CAST(:keyword AS text) = '' OR position(CAST(:keyword AS text) in lower(p.name)) > 0)
```

`position()`은 expression index 없이는 조건으로 쓸 수 없습니다. 플래너는 `product_listing_sort_idx`를 정렬 순서대로 훑으면서 `Filter`로 매 행을 검사하고, LIMIT 101행이 채워질 때까지 멈추지 못합니다. 선택도가 낮은 키워드일수록 거의 전체 테이블을 읽습니다.

측정(100k 상품, warm cache):

```text
Limit (actual time=6.073..409.938 rows=10)
  -> Index Scan using product_listing_sort_idx on product p
        Filter: status='ACTIVE' AND POSITION('needle' IN lower(name)) > 0
        Rows Removed by Filter: 99990
        Buffers: shared hit=92826
Execution Time: 409.979 ms
```

10건을 찾기 위해 99,990행을 버렸고 shared buffer를 92,826개 읽었습니다.

## 결정

1. 조건을 **인덱스 가능한 형태**로 바꿉니다.

   ```sql
   AND (CAST(:keyword AS text) = ''
       OR lower(p.name) LIKE '%' || replace(replace(replace(
               CAST(:keyword AS text), '\', '\\'), '%', '\%'), '_', '\_')
           || '%' ESCAPE '\')
   ```

   `LIKE`는 `pg_trgm` GIN index로 처리할 수 있습니다. 서비스가 키워드를 이미 trim·lowercase 하므로(`ProductServiceImpl`) 의미는 동일합니다. `%`, `_`, `\`는 이스케이프해 **리터럴로** 매칭되도록 유지했습니다. 이전 `position()` 동작과 같습니다.

2. V43에서 `pg_trgm` 확장과 GIN index를 추가합니다.

   ```sql
   CREATE EXTENSION IF NOT EXISTS pg_trgm;
   CREATE INDEX IF NOT EXISTS product_name_trgm_idx
       ON product USING gin (lower(name) gin_trgm_ops);
   ```

## 결과

### 선택도가 높은 키워드 (`needle`, 100k 중 10건)

| 항목 | 변경 전 | 변경 후 | 개선 |
| --- | ---: | ---: | ---: |
| Execution Time | 409.979 ms | 8.725 ms | **47배** |
| Shared buffers | 92,826 | 554 | **168배** |
| Rows Removed by Filter | 99,990 | 0 | — |
| Plan | sort index 전체 스캔 | `Bitmap Index Scan on product_name_trgm_idx` | — |

### 선택도가 낮은 키워드 (`broad`, `catalog` — 거의 전 행 매칭)

| 키워드 | Plan | Execution Time |
| --- | --- | ---: |
| broad | `Index Scan using product_listing_sort_idx` | 1.156 ms |
| catalog | `Index Scan using product_listing_sort_idx` | 1.055 ms |

플래너는 trigram index가 이득이 없는 경우 기존 정렬 index 경로를 그대로 선택합니다. 즉 **선택도에 따라 두 경로를 모두 사용**하며, 나쁜 쪽으로 고정되지 않습니다.

## 회귀 검증

`CatalogIntegrationTest.keywordSearchTreatsLikeWildcardsAsLiteralCharacters`를 추가했습니다.

- `100%` 검색이 `100 percent`를 매칭하지 않음(=`%`가 와일드카드가 아님).
- `size_42` 검색이 `sizeX42`를 매칭하지 않음(=`_`가 와일드카드가 아님).
- `cotton` 같은 일반 부분 문자열 검색은 그대로 동작.

## 한계와 위험

- `CREATE EXTENSION`에는 권한이 필요합니다. 관리형 PostgreSQL에서 애플리케이션 롤이 확장을 설치할 수 없다면 **DBA가 V43 이전에 `pg_trgm`을 설치**해야 합니다. 이 조건을 마이그레이션 주석에 남겼습니다.
- operator class는 확장이 설치된 스키마 소유입니다. Flyway가 target schema를 `search_path`로 두고 실행하는 환경(테스트의 per-schema migration)에서 unqualified `gin_trgm_ops`는 `operator class does not exist`로 실패합니다. V43은 `pg_extension`에서 소유 스키마를 조회해 동적으로 qualify 합니다. 이 실패는 마이그레이션 통합 테스트가 실제로 잡았습니다.
- GIN index는 쓰기 비용과 저장 공간을 늘립니다. 상품명 갱신이 많은 워크로드라면 `fastupdate`와 `gin_pending_list_limit`을 별도로 검토해야 합니다. 현재 상품 생성/수정 빈도는 낮습니다.
- `LIKE '%…%'`는 **앞쪽 와일드카드**라 trigram이 가장 유리한 형태이지만, 1–2글자 키워드는 trigram 선택도가 나빠 index 이득이 줄어듭니다. 그 경우에도 기존 정렬 index 경로로 떨어집니다.
- 이 실험은 EXPLAIN 수준의 비교이며, HTTP p95 개선은 EXP-035 시나리오를 다시 돌려 확인해야 합니다.

## 재현

```bash
# 1) 시드
docker compose -f compose.yaml -f .local/compose.capacity-node.yaml exec -T postgres \
  psql -U atlas -d atlas -v sellers=200 -v products_per_seller=500 \
  -f - < load-tests/seed-catalog-read.sql

# 2) 계획 비교 (변경 전 predicate는 position(...), 변경 후는 LIKE ... ESCAPE)
docker compose -f compose.yaml -f .local/compose.capacity-node.yaml exec -T postgres \
  psql -U atlas -d atlas -c "EXPLAIN (ANALYZE, BUFFERS) <listing query>"
```
