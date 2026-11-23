# EXP-056: 주문 재고 recovery backlog 처리량

**상태:** 300-workflow historical baseline과 최종 1,000-workflow·two-location scale matrix를 각각 한 번 실행했다. 24개 cell 모두 성공했으며 운영 SLO가 아니다. 이전 one-location 1,000-workflow 실행은 목표 workload보다 가벼워 제외했다.

**날짜:** 2026-11-22 (측정일은 아래 환경 표와 같다.)

**관련:** [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md), [ADR-0062](../02-decisions/adr/ADR-0062-order-recovery-backlog-observability.md), [ADR-0064](../02-decisions/adr/ADR-0064-order-inventory-just-in-time-lease-claims.md), [EXP-058](EXP-058-order-reservation-submit-capacity.md), [EXP-059](EXP-059-order-reservation-load-and-idempotent-replay.md)

## 목적과 방법

주문 재고 예약 recovery의 durable claim/lease 경로에서 batch size와 동시 poller 수가 backlog drain 처리량에 미치는 영향을 확인한다. 최종 측정은 PostgreSQL 18.6 Testcontainers에 workflow 1,000개를 staging한 뒤 두 MockWebServer의 성공 응답을 사용해 모두 `RESERVED`로 수렴하는 시간을 잰다. Timer는 staging과 worker 생성 후 시작해 drain 과정만 포함한다.

최종 matrix는 poller object 1·2·4·8·16·32개와 batch size 25·50·100·250의 24개 조합이다. 각 poller는 별도 `LocationReservationWorker` 객체이며, 같은 JVM·coordinator·MeterRegistry·DB connection pool·MockWebServer 두 개를 공유한다. batch size는 poll 한 번의 처리 상한이며, workflow는 하나씩 just-in-time claim한 뒤 처리한다. `dueThrough`는 poll 시작 시각으로 고정해 새 retry를 같은 poll에서 재선택하지 않는다. `poll()`은 backlog가 남아 있는 동안 즉시 반복 호출하며 production의 fixed-delay 1초 대기는 적용하지 않는다. 각 workflow는 SKU line 3개를 두 location에 분배하고 location별 remote reservation 1회, 총 2,000 remote request를 수행한다. 모든 workflow가 `RESERVED`이고 요청 2,000개가 관측될 때만 유효하다.

측정 harness는 일반 CI에서 실행하지 않는 opt-in 탐색 probe(`OrderInventoryRecoveryCapacityProbeTest`)였다. 이 probe는 저장소에 보존하지 않았다. 아래 수치는 그 실행 결과를 기록한 것이며 현재 저장소의 명령으로는 재현할 수 없다.

최종 two-location matrix의 각 조합은 한 번 실행했다. 측정 행은 당시 JUnit XML의 `system-out`에 기록됐다. 첫 two-location 시도는 test worker 메모리 부족으로 시간 초과되어 폐기했다. MockWebServer request 기록을 cell마다 비우고, workflow just-in-time claim과 fixed due cutoff을 적용한 재실행만 표에 사용했다. 300-workflow 표는 이전 probe revision의 historical baseline이며 workload·worker 배치가 달라 직접적인 개선률 비교에 사용하지 않는다. exploratory one-location 결과도 최종 표에서 제외했다.

## 환경

| 항목 | 측정 환경 |
|---|---|
| 측정일 | 2026-11-22 |
| 호스트 | macOS 27.2, Apple Silicon arm64 |
| 논리 CPU / 메모리 | 11 / 18 GiB |
| JVM | OpenJDK 25.0.5.1 (2026-11-17) |
| Docker | client 29.10.0 / server 29.5.2 |
| DB | PostgreSQL 18.6 Testcontainers, 별도 CPU·메모리 제한 없음 |
| 원격 inventory | 같은 테스트 프로세스의 MockWebServer, 성공 응답만 사용 |
| 최종 workload | location 2개, workflow당 SKU line 3개, location별 remote reservation 1회; cell당 backlog 1,000개 |

## 결과

### Historical baseline: workflow 300개

이 matrix는 이전 probe revision에서 동시 poller 1·2·4개와 batch size 10·50·100을 각각 한 번 실행한 결과다. 당시에는 한 worker 객체의 `poll()`을 여러 thread에서 동시 호출했다.

| 동시 poller 수 | Batch size | Backlog | 경과 시간 (s) | 처리량 (workflow/s) | 최종 상태 |
|---:|---:|---:|---:|---:|---|
| 1 | 10 | 300 | 3.041 | 98.64 | RESERVED 300/300 |
| 1 | 50 | 300 | 3.708 | 80.91 | RESERVED 300/300 |
| 1 | 100 | 300 | 3.134 | 95.73 | RESERVED 300/300 |
| 2 | 10 | 300 | 1.795 | 167.10 | RESERVED 300/300 |
| 2 | 50 | 300 | 1.463 | 205.09 | RESERVED 300/300 |
| 2 | 100 | 300 | 1.930 | 155.44 | RESERVED 300/300 |
| 4 | 10 | 300 | 1.192 | 251.67 | RESERVED 300/300 |
| 4 | 50 | 300 | 1.146 | 261.72 | RESERVED 300/300 |
| 4 | 100 | 300 | 1.186 | 252.89 | RESERVED 300/300 |

### 확장 matrix: workflow 1,000개

| Poller objects | Batch size | Backlog | 경과 시간 (s) | 처리량 (workflow/s) | 최종 상태 |
|---:|---:|---:|---:|---:|---|
| 1 | 25 | 1,000 | 11.097 | 90.11 | RESERVED 1,000/1,000 |
| 1 | 50 | 1,000 | 10.641 | 93.98 | RESERVED 1,000/1,000 |
| 1 | 100 | 1,000 | 10.034 | 99.66 | RESERVED 1,000/1,000 |
| 1 | 250 | 1,000 | 9.648 | 103.64 | RESERVED 1,000/1,000 |
| 2 | 25 | 1,000 | 6.075 | 164.62 | RESERVED 1,000/1,000 |
| 2 | 50 | 1,000 | 6.039 | 165.60 | RESERVED 1,000/1,000 |
| 2 | 100 | 1,000 | 6.185 | 161.69 | RESERVED 1,000/1,000 |
| 2 | 250 | 1,000 | 6.123 | 163.33 | RESERVED 1,000/1,000 |
| 4 | 25 | 1,000 | 3.524 | 283.75 | RESERVED 1,000/1,000 |
| 4 | 50 | 1,000 | 3.532 | 283.13 | RESERVED 1,000/1,000 |
| 4 | 100 | 1,000 | 3.719 | 268.87 | RESERVED 1,000/1,000 |
| 4 | 250 | 1,000 | 3.705 | 269.88 | RESERVED 1,000/1,000 |
| 8 | 25 | 1,000 | 1.820 | 549.45 | RESERVED 1,000/1,000 |
| 8 | 50 | 1,000 | 1.724 | 580.14 | RESERVED 1,000/1,000 |
| 8 | 100 | 1,000 | 1.745 | 572.97 | RESERVED 1,000/1,000 |
| 8 | 250 | 1,000 | 1.804 | 554.47 | RESERVED 1,000/1,000 |
| 16 | 25 | 1,000 | 1.357 | 736.87 | RESERVED 1,000/1,000 |
| 16 | 50 | 1,000 | 1.295 | 772.17 | RESERVED 1,000/1,000 |
| 16 | 100 | 1,000 | 1.309 | 764.18 | RESERVED 1,000/1,000 |
| 16 | 250 | 1,000 | 1.348 | 741.95 | RESERVED 1,000/1,000 |
| 32 | 25 | 1,000 | 1.437 | 695.85 | RESERVED 1,000/1,000 |
| 32 | 50 | 1,000 | 1.290 | 775.00 | RESERVED 1,000/1,000 |
| 32 | 100 | 1,000 | 1.553 | 643.84 | RESERVED 1,000/1,000 |
| 32 | 250 | 1,000 | 1.340 | 746.05 | RESERVED 1,000/1,000 |

최고 관측값은 poller object 32개·batch 50의 775.00 workflow/s이며, backlog 1,000개가 1.290초에 모두 `RESERVED`로 수렴했다. 각 cell에서 remote request 2,000건을 확인했다. 이 결과는 합성 1,000 order/s 목표의 77.5%이며 목표를 달성하지 못했다. Batch size별 순위는 poller 수에 따라 달랐고, 단일 실행만으로 최적값이나 원인을 단정하지 않는다. Historical 300-workflow 및 exploratory one-location 결과는 workload가 달라 직접 비교하지 않는다.

## 한계와 해석

- 최종 matrix는 한 JVM 안에서 poller object를 분리하고 `poll()`을 tight loop로 호출한 측정이다. production scheduler의 1초 fixed delay/dispatch, 별도 application process/host, independent connection pool, replica 간 네트워크는 검증하지 않았다.
- MockWebServer는 실제 inventory service의 network latency, 장애, quota를 재현하지 않는다. 두 location의 즉시 성공 경로만 측정했고 retry/compensation 경로는 포함하지 않았다.
- 각 최종 설정은 한 번 실행했다. 반복 분포, confidence interval, 개별 workflow p95/p99, 장시간 처리 또는 DB CPU·I/O 포화도를 추정하지 않는다. `elapsedSeconds`는 finite backlog 전체의 drain 시간이며, 첫 OOM timeout 실행은 결과에서 제외했다.
- 1,000개 backlog는 유한한 drain workload이지 1,000 order/s의 지속 유입률이 아니다. 실제 outage backlog recovery와 운영 capacity를 증명하지 않는다.
- 이 결과는 현재 호스트·JVM·Docker·DB 구성에 종속된다. 운영 worker 수나 batch 기본값은 변경하지 않았다. Multi-process/replica, 실패 혼합 workload, backlog age와 DB 자원 관측이 남은 검증이다.
- 처리량(workflow/s)은 backlog 1,000개(300개 표는 300개)를 반올림 전 경과 시간으로 나눈 값이다. 표의 경과 시간으로 다시 나누면 마지막 자리가 다를 수 있다(예: 1,000 ÷ 1.290 = 775.19, 표의 값 775.00).

## 재현

- 재현할 수 없다. 측정 harness `OrderInventoryRecoveryCapacityProbeTest`는 opt-in 탐색 probe였고 저장소에서 제거했다. 측정 행은 당시 JUnit XML의 `system-out`에만 있었으며 보존하지 않았다.
- 같은 질문을 다시 확인하려면 위 입력(PostgreSQL 18.6 Testcontainers, 두 MockWebServer 즉시 성공 응답, workflow당 SKU line 3개, backlog 1,000개, poller object 1·2·4·8·16·32개 × batch 25·50·100·250)으로 probe를 새로 작성해야 한다.
- 저장소에 남은 관련 회귀는 `LocationReservationServiceIntegrationTest`의 lease·just-in-time claim 검증(2026-11-23 no-cache 실행 15건 통과)이며 처리량을 측정하지 않는다.
