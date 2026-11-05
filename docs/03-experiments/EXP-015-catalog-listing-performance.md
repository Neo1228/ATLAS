# EXP-015: Catalog listing 성능 측정

**상태:** 미실행 · local-only

## 목적

D-067·ADR-0012의 Seller/Product 목록 조회 동작 비교. 처리량·SLO·사용자 수용량 산정 목적 아님. 변경된 운영 설정·production telemetry 없음.

## 작업부하

| Seed 상품 수 | Seller 수 | Seller-SKU 수 | 페이지 크기 |
|---:|---:|---:|---:|
| 10,000 | 100 | 20,000 | 100 |
| 100,000 | 100 | 200,000 | 100 |

100개 Seller에 균등 분배. 검색 데이터 분포: `broad` 10%, `needle` 1% (`needle`은 `broad` subset). 고정 seed는 재실행 가능. 상품별 SKU 2개. `ON CONFLICT` upsert만 사용하며 기존 데이터 삭제·truncate 없음. 작은 데이터로 큰 seed를 축소하지 않음. 정확한 크기 비교는 별도 빈 `atlas_catalog_perf` database에서 시작.

k6 workload: global 목록, Seller 목록, `broad` 검색, `needle` 검색 각 100 iteration. 단일 executor·1 VU, 총 400회 serialized request. 요청별 1페이지, `limit=100`. `items`/`nextCursor`, 상품·두 SKU shape, Java UUID natural-order key, Seller scope, cursor-last-item 일치 검사. 모든 check와 HTTP failure에 실패 임계값 적용. Summary JSON 보존. 성능값은 이 합성 요청 비교에만 유효하며 예상 사용자/RPS capacity 아님.

## 격리·사전 확인

로컬 PostgreSQL·애플리케이션만 사용. 기존 `atlas` DB 사용 금지. 애플리케이션 연결 URL과 `psql` 대상 모두 `atlas_catalog_perf`로 고정. 별도 local PostgreSQL instance 또는 해당 용도로만 쓰는 빈 `atlas_catalog_perf` database/schema 준비. 연결 정보·Docker/PostgreSQL 버전·JDK·OS/architecture: `[미확인]`.

```sh
psql 'postgresql://localhost:5432/atlas_catalog_perf' -v ON_ERROR_STOP=1 -c 'SELECT current_database();'
```

출력 DB가 `atlas_catalog_perf`인지 확인. Seed SQL도 다른 현재 DB에서 예외로 중단. 아래 URL의 user/password/host/port: `[로컬 환경값]`.

## 실행 절차

저장소 root에서 수행. PostgreSQL instance·credential은 기존 local 설정 사용. `product_count`는 `10000` 또는 `100000`만 지정. Seed SQL은 현재 database 이름도 확인.

```sh
# 선택 A: 10,000 상품 / 20,000 SKU
psql 'postgresql://localhost:5432/atlas_catalog_perf' -v ON_ERROR_STOP=1 -v product_count=10000 -f docs/03-experiments/EXP-015-catalog-listing-performance.sql

# 또는 선택 B: 100,000 상품 / 200,000 SKU
psql 'postgresql://localhost:5432/atlas_catalog_perf' -v ON_ERROR_STOP=1 -v product_count=100000 -f docs/03-experiments/EXP-015-catalog-listing-performance.sql

ATLAS_DB_URL=jdbc:postgresql://localhost:5432/atlas_catalog_perf ./gradlew :application-module:bootRun
mkdir -p artifacts
# 선택 A: 10,000 상품 / 20,000 SKU
BASE_URL=http://localhost:8080 SELLER_ID=00000000-0000-7000-8000-000000000015 SUMMARY_FILE=artifacts/exp015-10000.json k6 run load-tests/catalog-listing.js
# 또는 선택 B: 100,000 상품 / 200,000 SKU
BASE_URL=http://localhost:8080 SELLER_ID=00000000-0000-7000-8000-000000000015 SUMMARY_FILE=artifacts/exp015-100000.json k6 run load-tests/catalog-listing.js
```

100k seed 후 10k seed를 실행해도 초과 row는 삭제되지 않음. 정확한 비교에는 별도 빈 DB 사용. 실행 전 API/psql 대상 모두 `atlas_catalog_perf`인지 확인. k6/API 실행은 local-only. DB 상태·실측 성능 확인 전 결과 미기입.

## 기록

| Seed | Scenario | p50 | p95 | p99 | 오류 | Notes |
|---|---|---:|---:|---:|---:|---|
| `[미실행]` | global | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` |
| `[미실행]` | seller | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` |
| `[미실행]` | search_broad (10%) | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` |
| `[미실행]` | search_needle (1%) | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` | `[미실행]` |

추가 기록: commit SHA `[미기록]`; schema/seed row count `[미실행]`; PostgreSQL version/settings `[미확인]`; CPU·memory `[미확인]`; EXPLAIN (ANALYZE, BUFFERS, SETTINGS) `[미실행]`.

## 확장 기준

측정 DB 정합성, 응답 계약·정렬·cursor 확인, 재현 가능한 row count, 반복 결과 및 HTTP–DB 병목 근거 확보 후 별도 결정. Prometheus/Grafana/Tempo/Alloy 기본 추가 없음. OpenTelemetry·DB explain·pool/lock/host 관측 stack은 별도 proposal과 local-only opt-in gate 충족 시에만 검토. RPS/TPS·SLO·capacity 결론 없음.

## 참고

- [D-067·ADR-0012](../02-decisions/adr/ADR-0012-catalog-listing-order.md)
- [REF-115](../00-governance/reference-register.md): API page-size 기준
- [EXP-014](EXP-014-id-strategy-comparison.md): DB 비교 실험
