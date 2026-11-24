# EXP-044: 秒杀 재고 경합의 실제 병목과 제거 (실서비스 경로 측정)

**추적 이슈:** [#434](https://github.com/Neo1228/ATLAS/issues/434)

**상태:** 실측 완료 · 로컬, PostgreSQL 18.6 컨테이너, JDK 25

## 왜 이 실험을 했는가

EXP-042 는 "DB 가 동시성을 이미 막으니 외부 락·큐가 필요 없다"고 결론지었다. 그 결론은 **정확성**에 대해서는 맞다. 그러나 그 실험은 최대 32 동시 클라이언트였고, **처리량과 지연**을 측정하지 않았다.

그래서 물었다: 소량 재고에 수백 명이 동시에 몰리면(秒杀) 어떻게 되는가? 이 질문 없이 "인프라 불필요"를 결론짓는 것은 저부하 측정으로 극한을 단정하는 오류다.

### 이전 측정의 방법론 결함

처음에는 `docker exec psql` 로 손으로 쓴 SQL 을 돌렸다. 이 측정은 세 번 틀렸다.

1. `UPDATE ... RETURNING` 을 서브쿼리로 감싸 성공을 셀 수 없었다.
2. `docker exec` 에 `-i` 가 없어 stdin 이 전달되지 않아 모든 요청이 무효였다.
3. 세미콜론을 빠뜨려 두 statement 가 하나로 붙었다.

손으로 쓴 SQL 은 프로덕션 경로의 transaction 경계·advisory lock·idempotency 를 재현하지 못한다. **그래서 측정을 폐기하고 실제 `InventoryService.reserve` 를 호출하는 방식으로 다시 만들었다.**

## 어떻게 측정했는가

테스트: `atlas-application/src/test/java/com/atlas/inventory/InventoryFlashSaleContentionIntegrationTest.java`

- Testcontainers PostgreSQL 18.6, 실제 `InventoryService`·`SellerService`·`ProductService` 빈 사용
- HTTP 계층만 제거하고 **프로덕션 경로 그대로** 호출
- 두 시나리오:
  - `flashSale`: 재고 50, 구매자별 4회 시도 → 대부분 품절로 실패하는 실제 秒杀
  - `hotRow`: 재고 100만 → **모두 성공하는 조건**으로 품절 fast-fail 효과를 제거한 순수 락 경합
- 정확성 불변식 동시 검증: 성공 수 ≤ 재고, `available ≥ 0`, `available + reserved = initial_capacity`

## 측정 결과

### 수정 전 — 병목의 정체

`[hot-row]` (모두 성공, 순수 락 경합):

| 동시 구매자 | 시도 | tps | p50 | p95 |
|---:|---:|---:|---:|---:|
| 8 | 32 | 278 | 24ms | 44ms |
| 32 | 128 | 386 | 80ms | 88ms |
| 64 | 256 | 408 | 148ms | 177ms |
| 128 | 512 | **460 (정체)** | **264ms** | 311ms |

**처리량은 460 tps 에서 정체되는데 지연은 동시성에 정비례해 증가했다.** 완전 직렬화의 지문이다.

`[flash-sale]` (재고 50):

| 동시 구매자 | 시도 | 성공 | 품절 | tps | p50 |
|---:|---:|---:|---:|---:|---:|
| 8 | 32 | 32 | 0 | 432 | 18ms |
| 32 | 128 | 50 | 78 | 595 | 47ms |
| 64 | 256 | 50 | 206 | 675 | 79ms |

품절 fast-fail 덕에 tps 가 조금 올랐지만, 여전히 지연은 동시성에 비례했다.

### 원인

예약 경로는 `InventoryServiceImpl.tryReserve` → `JdbcLocationRepository.select` → `JdbcStockRepository.reserve` 순서다. `select` 가 매 예약마다 세 가지를 했다:

1. `INSERT INTO inventory_pool ... ON CONFLICT DO NOTHING` — **쓰기**
2. `SELECT ... FOR UPDATE OF pool` — **배타 락**
3. `advisorySelect` — 후보 조회

같은 SKU 에 대한 모든 예약이 2번에서 직렬화됐다. 본 UPDATE(`available = available - ?`)에 도달하기도 전에 락 대기가 발생했고, 대기하는 동안 커넥션을 계속 점유했다. Hikari 커넥션이 10개이므로 **9개가 유휴 대기에 묶이고 유효 병렬성은 1**이 됐다.

1·2번은 **불필요**했다. pool 은 SKU 등록 시 이미 만들어진다(`ProductServiceImpl.registerSku` → `createInventoryPool` → `ensurePool`). 재고 정확성은 `stock.reserve` 의 조건부 UPDATE(`WHERE available >= ?`)가 보장한다. 후보 선택이 낡아도 그 UPDATE 가 0 행을 돌려주고 호출자는 품절로 처리한다.

## 수정

`JdbcLocationRepository.select` 를 **읽기 전용**으로 바꿨다. upsert 와 `FOR UPDATE` 를 제거하고 후보 조회만 남겼다. 호출자 transaction 안에서 실행되므로 선택과 차감 사이의 원자성은 그대로다.

## 수정 후 측정 (동일 조건, 동일 테스트)

| 지표 | 수정 전 | 수정 후 | 변화 |
|---|---:|---:|---:|
| hot-row tps @128 | 460 | **643** | **+40%** |
| hot-row p50 @128 | 264ms | **183ms** | **−31%** |
| hot-row p95 @128 | 311ms | **262ms** | −16% |
| flash-sale tps @64 | 675 | **1,855** | **+175%** |
| flash-sale p50 @64 | 79ms | **19ms** | **−76%** |
| flash-sale tps @32 | 595 | **934** | +57% |

정확성 불변식은 전 구간에서 유지됐다: 성공 수 = min(시도, 재고), `available ≥ 0`, `available + reserved = 재고`.

## 남은 한계 (정직하게)

1. **핫로우 자체는 여전히 직렬화된다.** 조건부 UPDATE 는 단일 행에 대한 배타 락이 본질이다. 제거한 것은 *중복* 직렬화다. 128 동시에서 p50 183ms 는 여전히 높다.
2. **처리량 상한이 있다.** 단일 행 UPDATE 는 로컬에서 약 600–900 tps 수준이다. 이 이상이 필요하면 재고 분할(버킷), 사전 예약 토큰, 또는 대기 없는 실패(`pg_try_advisory_xact_lock`) 검토가 필요하다.
3. **HTTP 계층을 포함하지 않았다.** Tomcat 스레드·필터·직렬화 비용은 빠져 있다. 실제 API p95 는 이보다 높다.
4. **단일 노드 로컬이다.** 네트워크·복제·다중 replica 경합은 다루지 않았다.
5. **64 동시 flash-sale 의 1,855 tps 는 표본이 작다.** 한 번의 실행이며 반복 분산을 측정하지 않았다.

## 확인된 별도 결함 (아직 미수정)

측정 과정에서 다음을 코드로 확인했다. 이들은 이번 실험의 범위 밖이며 별도로 다룬다.

| 결함 | 근거 | 영향 |
|---|---|---|
| PayPal HTTP 타임아웃 없음 | `PayPalRestClient` 에 connect/read timeout 부재 | 외부 지연 시 스레드·커넥션 무한 점유 |
| DB `statement_timeout` 없음 | 설정 부재 | 느린 쿼리 하나가 커넥션 영구 점유 |
| Hikari `connection-timeout` 기본 30s | 설정 부재 | 부하 시 스레드가 30초씩 묶임 |
| Tomcat 200 : Hikari 10 | 기본값 | 20:1 비율로 스레드 대기 |
| 요청 단위 admission control 없음 | 코드 부재 | 100배 부하를 그대로 수용 |

## 참고 사례 (인도·중국 하이퍼스케일)

| 사례 | 관찰 | ATLAS 적용 |
|---|---|---|
| Flipkart Big Billion Days 준비 | 락 경합이 CPU 보다 먼저 스레드를 고갈시켜 5xx·CrashLoop. **bounded gate + 429 fast-fail** 로 해결 | 요청 단위 동시성 상한이 필요 |
| Alibaba PostgreSQL 秒杀 | 핫로우에서 실패 구매자가 불필요하게 대기. `pg_try_advisory_xact_lock` 으로 대기 없이 실패 | 대기 없는 실패 검토 근거 |
| Alibaba RDS | 활성 세션 14,700 폭증·CPU 포화. **SQL throttling** 으로 복구 | statement timeout·동시성 상한 |

출처는 이슈 #434 에 기록한다.
