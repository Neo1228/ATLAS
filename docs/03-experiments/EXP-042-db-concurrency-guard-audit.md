# EXP-042: 동시성 보호 장치 전수 점검 (대부분 불필요한 변경을 피함)

**추적 이슈:** [#422](https://github.com/Neo1228/ATLAS/issues/422), [#424](https://github.com/Neo1228/ATLAS/issues/424)

**상태:** 실측 완료 · 로컬, PostgreSQL 18.6 컨테이너

## 왜 이 실험을 했는가

"다중 인스턴스에서 JVM 로컬 락이 무효하니 보호가 깨질 것"이라는 가설로 시작했다. 그런데 이 가설을 그대로 믿고 코드를 바꾸면, 이미 DB 가 막고 있는 것을 중복으로 막게 된다. 그래서 **경로별로 "DB 만으로 충분한가"를 측정**했다.

## 결과 요약

| 경로 | 보호 장치 | 측정 결과 | 판정 |
|---|---|---|---|
| 재고 차감 | `UPDATE … WHERE available >= ?` | 8 client 63,537 tps / 32 client **194,219 tps** | DB 충분 |
| 결제 제출 동시 4건 | 상태 commit 후 early-return | attempt **1개**, create-order **1회** | DB 충분 |
| 동시 전액 환불 4건 | `lockAttempt` + `FOR UPDATE` + 누적 상한 | 환불 합계 **17,200원 = 정확히 1건** | DB 충분 |
| 쿠폰 사용 | `findWithLock` + `reserveIfCapacity` | 코드 확인 | DB 충분 |
| 반품 검수 | `lockOrder` + 상태 검사 | 코드 확인 | DB 충분 |
| 만료 sweep cursor | 소비 지점까지만 전진 | 코드 확인 | starvation 없음 |

## 측정 1: 재고 핫 로우 (가설 반증)

처음 세운 가설은 "`inventory_pool` 단일 행에 동시 요청이 몰려 row lock 이 직렬화한다"였다.

| 동시 client | latency avg | tps |
|---:|---:|---:|
| 1 | 0.106 ms | 9,394 |
| 8 | 0.126 ms | 63,537 |
| 32 | 0.165 ms | **194,219** |
| 64 | 0.353 ms | 181,245 |

**가설이 틀렸다.** 단일 행 UPDATE 는 이 규모에서 병목이 아니다. 로컬 overlay fs / `synchronous_commit=on` 조건이며, 이 결과 자체를 운영 SLA 로 주장하지 않는다. 중요한 것은 "핫 로우를 이유로 샤딩·큐를 도입할 근거가 없다"는 판단이다.

## 측정 2: 동시 결제 제출

동시 4 요청:

```text
attempts=1 calls={POST /v2/checkout/orders=1, POST /v1/oauth2/token=1}
```

**주의: 이 측정을 처음에 잘못 읽었다.** `takeRequest` 로 stub 큐를 통째로 비우며 개수를 세어 `2` 를 얻고 "create-order 2회"로 오판했다. 실제로는 create-order 1 + oauth token 1 이었다. 경로별로 세도록 고친 뒤 `1` 임을 확인했다.

## 측정 3: 동시 전액 환불

같은 결제(17,200원)에 서로 다른 idempotency key 로 전액 환불 4건을 동시에 시도했다.

```text
captured_won=17200 refunded_won=17200
```

**환불 합계가 정확히 1건**이다. `lockAttempt` + `refundedAmountForAttempt` 누적 상한이 DB 에서 동시 요청을 직렬화했다. JVM 락이 무효여도 금전 사고가 나지 않는다.

## 해석

- `OwnerLockRegistry` 는 **정합성 필수가 아니라 경쟁 완화 장치**다. 각 경로가 DB 조건부 갱신·row lock·누적 상한으로 최종 방어선을 갖는다.
- 따라서 "JVM 락을 `pg_advisory_xact_lock` 으로 교체"하는 작업은 **정합성 개선이 아니다**. 경쟁 빈도를 측정해 지연이 문제일 때만 검토할 일이다.
- 이번 실험에서 실제로 고친 것은 두 가지다: 결제 환율 API 캐시(#421)와 구매자 반품 목록 API(#427). 둘 다 동시성이 아니라 **외부 의존과 접근성** 문제였다.

## 한계

- 모든 측정이 단일 JVM 또는 단일 DB 컨테이너 로컬 환경이다. replica 3대 이상에서 네트워크 지연이 더해진 경쟁은 측정하지 못했다.
- "DB 충분"은 로컬 조건에서의 관측이다. 락 대기가 길어지는 운영 조건(커넥션 풀 포화)에서는 지연이 달라질 수 있다.
- 쿠폰·반품 검수·sweep cursor 는 측정하지 않고 코드 확인만 했다. 코드 경로상 조건부 갱신·row lock 이 있으나, 부하 측정은 하지 않았다.

## 교훈

"동시성 문제가 있을 것"이라는 가설을 세우면, 그것을 **보호 장치가 이미 있는지 먼저 확인**해야 한다. 이 실험 전에 세운 가설 3개(핫 로우, provider 중복, owner 락) 중 실제로 결함이었던 것은 **없었다**. 측정이 없었다면 불필요한 샤딩·fence 컬럼·advisory lock 을 도입했을 것이다.

provider fence 컬럼과 V51 마이그레이션은 실제로 구현했다가 되돌렸다. 그 기록은 #424 에 남아 있다.
