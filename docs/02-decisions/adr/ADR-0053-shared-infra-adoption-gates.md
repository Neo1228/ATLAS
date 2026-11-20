# ADR-0053: 공유 캐시·메시지 브로커·외부 락은 측정 근거가 생길 때까지 도입하지 않는다

- 상태: Accepted — 조사 기록 · 즉시 도입하지 않음
- 날짜: 2026-11-20
- 관련 결정: [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md), [ADR-0051](ADR-0051-payment-fx-cache-single-flight.md), [EXP-038](../../03-experiments/EXP-038-scale-out-limits-and-infra.md), [EXP-041](../../03-experiments/EXP-041-payment-external-fx-dependency.md), [EXP-042](../../03-experiments/EXP-042-db-concurrency-guard-audit.md), [EXP-043](../../03-experiments/EXP-043-settlement-db-failure-atomicity.md)
- 추적: [#434](https://github.com/Neo1228/ATLAS/issues/434)

## 배경

"캐시·메시지 큐·분산 락을 도입하면 좋다"는 일반론은 이 저장소에서 반복 검토됐다. 결론은 항상 같았다: **PostgreSQL 이 이미 그 일을 하고 있고, 측정된 병목이 아니다.**

그런데 그 판단은 개별 ADR 에 흩어져 있었다. 새 요구가 들어올 때마다 "그때 뭐라고 정했더라"를 다시 찾아야 했다. 이 ADR 은 세 후보를 한 곳에 모아, **각 후보마다 도입 임계값과 기각 이유를 명시**한다.

원칙은 하나다: **측정되지 않은 병목을 위해 인프라를 추가하지 않는다.** 인프라는 장애 지점과 운영 부담을 늘린다. 그 비용은 측정된 이득으로만 정당화된다.

## 판정 요약

| 후보 | 판정 | 기각/보류 근거 | 도입 임계값 |
| --- | --- | --- | --- |
| 공유 캐시(Redis) | **보류** | 측정된 읽기 핫 경로가 없다. FX 캐시는 이미 초당 0.033회 수준 | 검증된 ECOS quota 의 80% 도달 또는 429 발생 |
| 메시지 브로커(Kafka/RabbitMQ) | **현 요구에 대해 기각** | PostgreSQL lease 큐가 이미 `SKIP LOCKED` 로 안전. broker 는 dual-write 문제를 해결하지 않음 | 가장 오래된 due job 이 15분간 60초 초과 + 큐가 원인 |
| 외부 분산 락(Redis/etcd) | **기각** | DB row lock·조건부 UPDATE·멱등성으로 정확성 방어. advisory 락은 PSP 호출을 넘지 못함 | 2대 이상에서 same-owner 대기가 latency 예산 초과 + 커넥션 점유 악화 |

---

## 1. 공유 캐시 (Redis) — 보류

### 캐시로 해결하려 했던 것

FX 고시 캐시가 **프로세스 로컬**이다(`CachingExchangeRateProvider`). replica 3대면 TTL 만료마다 최대 3회 외부 호출이 나간다. 공유 캐시를 두면 그 배수를 1로 줄일 수 있다.

### 측정된 것

`CachingExchangeRateProvider` 는 60초 TTL, 10분 max-stale, `volatile` 필드, 로컬 `ReentrantLock` 단일 비행을 쓴다(`CachingExchangeRateProvider.java:28-55,61-85`).

EXP-041 실측:
- 캐시 도입 전: 결제 3건 → 최소 3회 호출, 환율 변환 5건 → 5회
- 캐시 도입 후: warm 상태 결제 3건 → **0회**
- replica 2대 cold TTL 창 → **각 1회 = 총 2회**

즉 **측정된 60초 창에서 2회**, 단순 평균 **약 0.033회/초**다. 저부하 테스트였고, API quota 와 고부하 거동은 측정하지 않았다(`EXP-041:169-175`).

### 왜 지금 도입하지 않나

**공유 캐시가 고칠 측정된 읽기 핫 경로가 없다.**

EXP-038 은 목록/검색/상세 p95 를 13/9/6ms 로 측정했다(각 2,400 요청, 드롭 0). 고부하 구간의 실패 원인은 **Hikari 커넥션 풀 대기**였고, 특정 읽기 쿼리의 열이 아니었다(`EXP-038:33-47,132-146`).

환율 캐시는 **초당 0.033회**를 줄이려고 Redis·네트워크 홉·장애 지점·운영 부담을 추가하는 셈이다. 투자 대비 이득이 측정되지 않았다.

또한 **환불은 캐시를 쓰지 않는다.** 저장된 스냅샷만 읽는다(`ADR-0051:27-30`). 따라서 환불 경로에는 공유 캐시가 개입할 여지가 없다. 결제 시작 경로만 대상인데, 그 경로의 외부 호출은 이미 초당 0.033회다.

### 캐시를 도입한다면

- 고시값 + fetch 시각을 공유 키로 두고, TTL·stale 정책을 기존과 동일하게 유지
- replica 간 동시 refresh 를 억제(coordination 또는 부정 캐시)
- **결제 attempt 의 불변 스냅샷은 절대 공유 캐시로 대체하지 않는다** — 확정된 결제 금액은 스냅샷이 정본이다

### 캐시 도입 임계값

> 검증된 ECOS quota 의 **80%** 에 도달하거나, quota 로 인한 **429 가 발생**하면 도입을 재검토한다.

80% 여유는 이 저장소의 기존 기준이 아니라 **제안된 가드**다. 현재 quota 와 운영 호출률을 모르므로 **이 임계값은 지금 평가할 수 없다.** EXP-041 의 `http.client.requests` 측정 방식을 그대로 쓰면 된다.

---

## 2. 메시지 브로커 (Kafka/RabbitMQ) — 현 요구에 대해 기각

### 브로커로 해결하려 했던 것

PostgreSQL 폴링·큐 경합을 줄이거나, 독립 consumer 로 fan-out 하려는 것.

### 이미 있는 것

- 결제 복구: `payment_recovery_job`, bounded `FOR UPDATE SKIP LOCKED` claim, lease token, worker 폴링·메트릭(`PaymentRecoveryRepository.java:181-194`; `PaymentRecoveryWorker.java:103-151`)
- PayPal webhook 재시도: PostgreSQL lease 큐, 1행 `SKIP LOCKED`, 60초 lease, 최대 8회(`PayPalWebhookRetryRepository.java:16-18,88-123`)

ADR-0038 은 broker 가 multi-consumer routing 을 준다는 점을 인정하면서도, **DB/broker dual-write 문제를 해결하지 않는다**고 적었다(`ADR-0038:61-73`). ADR-0046 은 broker 이전 전에 **큐 적체·DB 경합 증거**를 요구하며, replica 확장에서도 안전하게 claim 된다는 점을 명시한다(`ADR-0046:26,43-47`).

### 실제로 없는 능력

지금 없는 것은 **독립 subscriber offset 을 가진 범용 event stream / fan-out** 이다. 현재 두 큐는 **work-item 큐**다 — 하나의 작업을 하나의 worker 가 가져간다.

**그 능력이 필요한 consumer 요구가 현재 없다.** 있다면 그건 성능이 아니라 기능 요구이므로 별도로 정당화해야 한다.

### 브로커를 도입한다면

dual-write 를 피해야 한다. **transactional outbox 또는 CDC relay** 로 DB commit 과 발행을 원자적으로 묶고, idempotent consumer·재시도·DLQ·운영자 처리를 함께 설계해야 한다. Kafka(retained partitioned replay)와 RabbitMQ(routing) 중 무엇인지는 **replay 의미 요구**가 결정한다.

### 브로커 도입 임계값

> 가장 오래된 due job 나이가 **15분간 60초를 초과**하고, 그 원인이 worker/provider 처리량이 아니라 **PostgreSQL 큐 폴링/락**임이 측정되면 재검토한다.

60초는 현재 lease 길이에 맞춘 **검토 트리거**이며 기존 복구 SLO 가 아니다. ADR-0046 이 요구한 큐 나이 측정은 아직 대표 부하에서 수행되지 않았다.

---

## 3. 외부 분산 락 (Redis/etcd) — 기각

### 외부 락으로 해결하려 했던 것

`OwnerLockRegistry` 는 JVM 로컬이다(`OwnerLockRegistry.java:8-29`). replica 2대가 같은 주문에 동시 결제를 제출하면 두 스레드가 병렬 진행한다(`OrderServiceImpl.java:63,595-596`, `PaymentServiceImpl.java:44,1032`).

### 왜 정확성 문제가 아닌가

EXP-042 실측:

| 경로 | 결과 |
| --- | --- |
| 동시 결제 제출 4건 | attempt **1개**, create-order **1회** |
| 동시 전액 환불 4건 | 환불 합계 **17,200원 = 정확히 1건** |

DB 방어선이 실제로 작동한다: order/payment row lock, `PESSIMISTIC_WRITE`, refund advisory transaction lock(`OrderRepository.java:27-28`; `PaymentAttemptRepository.java:40-53`; `PaymentRefundRepository.java:40-42`).

로컬 락이 무효해도 남는 것은 **중복 provider 호출과 락 대기**다. provider 호출은 고정 request-id 로 멱등이므로 금전 사고가 아니다.

### 외부 락이 advisory 락을 이기는 유일한 조건

**측정된 same-owner 대기가 사용자 지연이나 커넥션 풀 문제를 일으키고**, 그 락이 **DB transaction 시작 전에** 획득되어야 하거나 **transaction 을 넘어** PSP 호출 구간을 감싸야 할 때다.

중요한 구조적 사실: **advisory transaction lock 은 PSP 네트워크 호출을 감쌀 수 없다.** 감싸면 transaction 과 커넥션을 호출 내내 열어둬야 한다. 현재 주문 코드는 **의도적으로 provider 를 DB transaction 밖에서** 호출한다(`OrderServiceImpl.java:171-197`; `ADR-0046:8-14`). 이 설계를 되돌리면서까지 외부 락을 넣는 것은 개악이다.

### 외부 락을 도입한다면

- DB transaction **전에** lease 기반 락 획득
- 만료·갱신, 그리고 **stale holder 가 commit 하지 못하게 하는 fencing token**
- DB row lock·조건부 UPDATE·provider 멱등성은 **그대로 유지** (외부 락은 정확성 방어가 아니라 대기 감소 장치)

### 외부 락 도입 임계값

> 2대 이상에서 same-owner 경합을 측정하고, advisory/DB 락 대기가 해당 경로의 latency 예산을 초과하며 **커넥션 풀 점유 악화를 유발**함이 확인되고, A/B 실험에서 외부 락이 SLO 를 회복시키고 커넥션 점유를 낮출 때만.

**현재 확인 불가:** owner-lock 대기 메트릭, 경로별 수치 SLO, 다중 replica 경합 측정 결과가 모두 없다. `distributed-readiness.md:54-58,131-133` 도 같은 이유로 "경합을 먼저 측정"하라고 적는다.

---

## 이 ADR 이 답하지 않는 것

- **quota·운영 호출률**: 측정되지 않았다. 임계값 1 은 지금 평가 불가다.
- **복구 큐 나이**: 대표 부하에서 측정되지 않았다.
- **다중 replica same-owner 경합**: 측정되지 않았다.
- **DB 연결 손실**: EXP-043 이 `pg_terminate_backend` 로 원자성을 검증했다. 별도 프록시 인프라 없이 결정적 테스트가 가능함을 확인했으므로, **Toxiproxy 는 도입하지 않는다.**

## 결과

인프라를 추가하지 않았다. 대신 **각 후보의 도입 조건을 수치로 남겼다.** 이후 누군가 "Redis 를 넣자"고 할 때, 이 문서의 임계값을 충족하는 측정을 제시해야 한다.

측정되지 않은 병목에 인프라를 추가하는 것은 **복잡도를 늘리고 장애 지점을 늘리면서 이득을 증명하지 못하는 일**이다. 이 저장소는 그 선택을 하지 않는다.
