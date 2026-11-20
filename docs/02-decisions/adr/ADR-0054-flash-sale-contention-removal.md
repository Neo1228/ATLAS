# ADR-0054: 秒杀 경합은 인프라가 아니라 중복 직렬화를 제거해 해결한다

- 상태: Accepted — 실측 근거 있음
- 날짜: 2026-11-20
- 관련: [EXP-042](../../03-experiments/EXP-042-db-concurrency-guard-audit.md), [EXP-044](../../03-experiments/EXP-044-flash-sale-inventory-contention.md), [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)
- 추적: [#434](https://github.com/Neo1228/ATLAS/issues/434)

## 배경

ADR-0053 은 공유 캐시·브로커·외부 락을 "측정된 병목이 없다"는 이유로 도입하지 않기로 했다. 그 판단의 근거였던 EXP-042 는 **최대 32 동시 클라이언트**였다.

이 ADR 은 그 전제를 검증한다. 소량 재고에 수백 명이 몰리는 秒杀 조건에서 실제로 무엇이 먼저 무너지는가?

## 측정

실서비스 경로(`InventoryService.reserve`, HTTP 계층만 제거)를 Testcontainers PostgreSQL 18.6 에서 호출했다. 재고가 충분한 조건(`hot-row`)으로 품절 fast-fail 효과를 분리했다.

| 동시 구매자 | tps | p50 | p95 |
|---:|---:|---:|---:|
| 8 | 278 | 24ms | 44ms |
| 32 | 386 | 80ms | 88ms |
| 64 | 408 | 148ms | 177ms |
| 128 | **460 (정체)** | **264ms** | 311ms |

처리량은 정체되는데 지연은 동시성에 정비례했다. 완전 직렬화다.

## 원인

`JdbcLocationRepository.select` 가 매 예약마다 세 가지를 했다.

1. `INSERT INTO inventory_pool ... ON CONFLICT DO NOTHING` — 쓰기
2. `SELECT ... FOR UPDATE OF pool` — 배타 락
3. 후보 location 조회

같은 SKU 의 모든 예약이 2번에서 직렬화됐다. **본 차감 UPDATE 에 도달하기도 전에** 락 대기가 발생했고, 대기 동안 커넥션을 점유했다. Hikari 가 10개이므로 9개가 유휴 대기에 묶여 유효 병렬성은 1이 됐다.

1·2번은 불필요했다.

- pool 은 SKU 등록 시 이미 생성된다 (`ProductServiceImpl.registerSku` → `createInventoryPool` → `ensurePool`)
- 재고 정확성은 `stock.reserve` 의 조건부 UPDATE(`WHERE available >= ?`)가 보장한다. 후보 선택이 낡아도 0 행이 돌아오고 호출자는 품절로 처리한다

## 결정

`select` 를 **읽기 전용**으로 바꾼다. 중복 쓰기와 중복 배타 락을 제거한다. 호출자 transaction 안에서 실행되므로 선택과 차감의 원자성은 유지된다.

## 결과

| 지표 | 수정 전 | 수정 후 | 변화 |
|---|---:|---:|---:|
| hot-row tps @128 | 460 | 643 | +40% |
| hot-row p50 @128 | 264ms | 183ms | −31% |
| flash-sale tps @64 | 675 | 1,855 | +175% |
| flash-sale p50 @64 | 79ms | 19ms | −76% |

정확성 불변식은 전 구간 유지: 성공 수 = min(시도, 재고), `available ≥ 0`, `available + reserved = 재고`.

## 왜 인프라를 추가하지 않았는가

**측정된 병목이 "인프라 부족"이 아니라 "중복 락"이었다.** 원인을 제거하니 인프라 추가 없이 40~175% 개선됐다. 여기서 Redis·Kafka·외부 락을 먼저 넣었다면:

- 락 대기는 그대로 남았을 것이다. 외부 락도 같은 SKU 를 직렬화해야 한다
- dual-write·캐시 정합성이라는 새 문제가 생겼을 것이다
- 개선의 원인을 잘못 귀속시켜, 다음 병목에서 같은 실수를 반복했을 것이다

ADR-0053 의 결론은 **유지되지만 근거가 강화된다**: 이제 "저부하라서 필요 없다"가 아니라 "극한에서도 병목이 인프라가 아니었다"이다.

## 남은 한계

1. **핫로우는 여전히 직렬화된다.** 조건부 UPDATE 는 단일 행 배타 락이 본질이다. 제거한 것은 중복 직렬화다. 128 동시 p50 183ms 는 여전히 높다.
2. **단일 행 상한이 있다.** 로컬 약 600–900 tps. 이 이상은 재고 분할(버킷)·사전 예약 토큰·대기 없는 실패(`pg_try_advisory_xact_lock`)가 필요하다.
3. **HTTP 계층 미포함.** Tomcat·필터·직렬화 비용이 빠져 실제 API p95 는 더 높다.
4. **단일 노드·단일 실행.** 네트워크·복제·다중 replica, 반복 분산을 측정하지 않았다.

## 다음 병목 (측정으로 확인된 것)

인프라가 아니라 **경계 설정**이 다음 순서다. 이들은 코드로 존재를 확인했고 아직 고치지 않았다.

| 결함 | 영향 |
|---|---|
| PayPal HTTP 타임아웃 없음 | 외부 지연 시 스레드·커넥션 무한 점유 |
| DB `statement_timeout` 없음 | 느린 쿼리 하나가 커넥션 영구 점유 |
| Hikari `connection-timeout` 기본 30s | 부하 시 스레드가 30초씩 묶임 |
| Tomcat 200 : Hikari 10 | 20:1 비율로 스레드 대기 |
| 요청 단위 admission control 없음 | 100배 부하를 그대로 수용 |

## 참고 사례

| 사례 | 관찰 | 시사 |
|---|---|---|
| Flipkart Big Billion Days 준비 | 락 경합이 CPU 보다 먼저 스레드를 고갈 → 5xx·CrashLoop. bounded gate + 429 로 해결 | 동시성 상한 필요 |
| Alibaba PostgreSQL 秒杀 | 실패 구매자가 불필요하게 락을 대기. `pg_try_advisory_xact_lock` 으로 즉시 실패 | 대기 없는 실패 검토 |
| Alibaba RDS | 활성 세션 14,700·CPU 포화. SQL throttling 으로 복구 | statement timeout 필수 |
