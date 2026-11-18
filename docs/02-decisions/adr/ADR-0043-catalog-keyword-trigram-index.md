# ADR-0043: Catalog keyword 검색은 trigram index를 쓰는 predicate로 처리한다

- 상태: Proposed
- 날짜: 2026-11-18
- 추적: [Issue #344](https://github.com/Neo1228/ATLAS/issues/344)

## 맥락

`GET /api/products`의 키워드 필터는 다음 조건이었습니다.

```sql
AND (CAST(:keyword AS text) = '' OR position(CAST(:keyword AS text) in lower(p.name)) > 0)
```

`position()`은 인덱스로 표현할 수 없습니다. 플래너는 `product_listing_sort_idx`를 정렬 순서로 훑으면서 매 행을 `Filter`로 검사하고, LIMIT이 채워질 때까지 멈추지 못합니다. EXP-036에서 100k 상품·`needle`(10건 매칭) 기준으로 **409.979 ms, shared hit 92,826, 99,990행 탈락**을 측정했습니다. EXP-035의 읽기 부하에서 이 라우트가 가장 느렸던 이유입니다.

EXP-028/ADR-0040은 정렬·cursor 경로를 이미 최적화했습니다. 남은 병목은 정렬이 아니라 **조건의 형태**였습니다.

## 결정

1. 조건을 `lower(p.name) LIKE '%' || escaped(keyword) || '%' ESCAPE '\'`로 바꿉니다.
2. V43에서 `pg_trgm` 확장과 `product_name_trgm_idx` GIN index를 만듭니다.
3. `%`, `_`, `\`는 이스케이프해 이전과 동일하게 **리터럴**로 매칭합니다.

## 근거

- `LIKE '%…%'`는 trigram GIN index가 처리할 수 있는 대표적 형태입니다. 앞쪽 와일드카드는 trigram에 유리하고, 뒤쪽 와일드카드도 부분 문자열 검색에 그대로 동작합니다.
- 서비스가 이미 키워드를 trim·lowercase 하므로(`ProductServiceImpl.listProductsPageInternal`), `lower(p.name)`과 조합해도 대소문자 의미가 바뀌지 않습니다.
- 선택도가 낮은 키워드(`broad`, `catalog`)에서는 플래너가 기존 정렬 index를 선택했습니다(1.1 ms). 즉 **한쪽으로 고정되지 않습니다**.

측정(100k, warm cache):

| 키워드 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| `needle` (10건) | 409.979 ms / 92,826 hits | 8.725 ms / 554 hits |
| `broad` (거의 전 행) | — | 1.156 ms (기존 정렬 index) |

## 기각한 대안

| 대안 | 기각 이유 |
| --- | --- |
| `to_tsvector` 전문 검색 + GIN | 부분 문자열이 아니라 단어 단위라 `size_42`, `100%` 같은 기존 동작이 바뀜. 다국어 형태소 분석도 필요 |
| `lower(name)` B-tree prefix index | 앞쪽 와일드카드 검색에는 쓸 수 없음 |
| 애플리케이션 메모리 캐시 | 100k×2 SKU를 앱에 들고 있어야 하고 무효화·메모리 비용이 큼. DB 문제를 우회할 뿐 |
| 그대로 두고 DB만 확장 | 쿼리 형태가 원인인데 자원을 늘리는 접근. EXP-035에서 DB가 이미 병목임을 확인 |
| 별도 검색 엔진 도입 | 운영 비용·동기화 실패 모드가 크고, 현재 규모에서 정당화되지 않음 |

## 결과와 한계

- **권한**: `CREATE EXTENSION`에는 권한이 필요합니다. 관리형 PostgreSQL에서 애플리케이션 롤이 불가하면 DBA가 V43 이전에 `pg_trgm`을 설치해야 합니다.
- **operator class 스키마**: `gin_trgm_ops`는 확장 소유 스키마에 있습니다. Flyway가 target schema를 `search_path`로 두면 unqualified 이름이 해석되지 않습니다. V43은 `pg_extension`→`pg_namespace`로 소유 스키마를 조회해 qualify 하므로, per-schema migration에서도 동작합니다.
- GIN index는 쓰기 비용과 저장 공간을 늘립니다. 상품명 갱신이 잦아지면 `fastupdate`·`gin_pending_list_limit`을 재검토해야 합니다.
- 1–2글자 키워드는 trigram 선택도가 낮아 index 이득이 줄고 기존 경로로 떨어집니다.
- EXPLAIN 수준의 개선이며 HTTP p95 개선은 별도 부하 측정으로 확인해야 합니다.
