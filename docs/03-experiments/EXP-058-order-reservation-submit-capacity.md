# EXP-058: 원격 재고 submit의 transaction batching

**상태:** PostgreSQL Testcontainers·MockWebServer service-level probe를 batching 전 1회, 후 1·2·4·8 동시성은 각 2회, 후 16·32 동시성은 각 1회 실행했다. 표는 전 1회와 후 가장 최근 실행 결과이며 반복 분포나 운영 SLO가 아니다.

**날짜:** 2026-11-22 (측정일은 아래 환경 표와 같다.)

**관련:** [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md), [ADR-0063](../02-decisions/adr/ADR-0063-order-inventory-write-batching.md), [EXP-053](EXP-053-inventory-location-failure-load.md)

## 목적과 방법

ADR-0060에서 확인한 order-side DB commit 비용을 줄이기 위해 두 경계를 변경했다.

1. `submit`은 workflow intent 생성과 최초 lease claim을 한 짧은 local transaction으로 처리한다.
2. 새 workflow가 모든 location에서 `PENDING`인 성공 경로는 remote 결과를 메모리에 모은 뒤, location 상태와 최종 workflow 상태를 한 transaction으로 commit한다. Remote HTTP 호출은 transaction 밖에서 수행한다. Commit 전 프로세스가 종료되면 durable workflow가 동일 idempotency key로 재시도한다.

측정 harness `OrderInventoryReservationSubmitCapacityProbeTest`는 opt-in 탐색 probe였고 저장소에서 제거했다(과거 harness). 입력 조건과 지표는 다음과 같다.

## 입력 조건과 지표

| 항목 | 조건 |
| --- | --- |
| 호출 | 동기 `LocationReservationService.submit()` 직접 호출. controller·HTTP ingress를 거치지 않는다. |
| 부하 크기 | cell당 workflow 1,000개, 동시 호출 수 1·2·4·8·16·32 |
| 주문 구성 | SKU line 3개(첫 location 2개, 두 번째 location 1개), location 2개 |
| remote 호출 | workflow당 2개(location당 1회), cell당 2,000개. MockWebServer 두 인스턴스가 즉시 `RESERVED`를 반환한다. |
| DB | PostgreSQL 18.6 Testcontainers(별도 CPU·메모리 제한 없음) |
| 반복(n) | 변경 전: cell당 1회. 변경 후: 동시성 1·2·4·8은 각 2회(표는 가장 최근 실행), 16·32는 각 1회 |
| 유효 조건 | cell에서 workflow 1,000개가 모두 `RESERVED`이고 remote 요청이 2,000개일 때만 결과를 유효하게 본다. |
| 경과 시간(s) | 호출자 thread가 start barrier를 지난 뒤 첫 submit부터 모든 응답이 끝날 때까지다. 요청 object 생성과 DB 초기화는 제외한다. |
| 처리량(workflow/s) | 1,000 ÷ 경과 시간이다. 표의 경과 시간은 반올림 값이라 다시 나누면 마지막 자리가 다를 수 있다. |
| p50/p95/p99(ms) | `submit()` 호출 1,000건 각각의 시작–반환 시간에 대한 nearest-rank 백분위 |
| `xact_commits / workflow` | cell 전후 `pg_stat_database.xact_commit` 증가량 ÷ workflow 수(진단값). DB 전체 transaction counter의 집계이며 정확한 business transaction trace나 성능 gate가 아니다. |

## 환경

| 항목 | 측정 환경 |
|---|---|
| 측정일 | 2026-11-22 |
| 호스트 | macOS 27.2, Apple Silicon arm64, 11 논리 CPU, 18 GiB |
| JVM | OpenJDK 25.0.5.1 (2026-11-17) |
| Docker | client 29.10.0 / server 29.5.2 |
| DB | PostgreSQL 18.6 Testcontainers, 별도 CPU·메모리 제한 없음 |
| remote inventory | 두 MockWebServer 인스턴스, 즉시 성공 응답 |
| 측정 범위 | coordinator service·order DB·loopback HTTP; public HTTP ingress와 inventory DB 제외 |

## 결과

### Transaction batching 전후

Batching 전은 cell별 1회, 후 1·2·4·8은 각 2회, 16·32는 각 1회 실행했다. 표의 후 값은 가장 최근 실행이며, 반복 분포·confidence interval을 추정하지 않는다.

| 동시 호출 수 | 전: 처리량 (workflow/s) | 후: 처리량 (workflow/s) | 전: xact commits / workflow | 후: xact commits / workflow | 후: 최종 상태 |
|---:|---:|---:|---:|---:|---|
| 1 | 52.19 | 59.03 | 6.811 | 3.959 | RESERVED 1,000/1,000 |
| 2 | 96.18 | 113.77 | 6.795 | 3.919 | RESERVED 1,000/1,000 |
| 4 | 172.00 | 199.73 | 6.724 | 3.604 | RESERVED 1,000/1,000 |
| 8 | 339.44 | 358.84 | 5.902 | 3.602 | RESERVED 1,000/1,000 |
| 16 | — | 475.14 | — | 4.107 | RESERVED 1,000/1,000 |
| 32 | — | 528.99 | — | 3.245 | RESERVED 1,000/1,000 |

### Batch 이후 지연 및 remote 요청

| 동시 호출 수 | 경과 시간 (s) | p50 (ms) | p95 (ms) | p99 (ms) | remote 요청 |
|---:|---:|---:|---:|---:|---:|
| 1 | 16.939 | 16.025 | 23.008 | 34.909 | 2,000 |
| 2 | 8.789 | 16.960 | 21.579 | 28.418 | 2,000 |
| 4 | 5.007 | 19.859 | 25.575 | 32.579 | 2,000 |
| 8 | 2.787 | 20.540 | 32.433 | 47.768 | 2,000 |
| 16 | 2.105 | 32.510 | 50.588 | 55.888 | 2,000 |
| 32 | 1.890 | 57.144 | 95.490 | 109.212 | 2,000 |

가장 높은 관측 처리량은 동시 호출 32개의 528.99 workflow/s(목표의 52.9%)이며 p95 95.49 ms, p99 109.212 ms다. 이는 시험한 범위의 최고 단일 실행이지 실제 포화점이나 운영 성능을 의미하지 않는다. 합성 1,000 order/s 목표에는 미달했다. 동시성 1·2·4·8의 후행 처리량은 기존 단일 실행 대비 약 5.7–18.3% 높았고, xact commit 진단값은 감소했지만 한 번의 비교로 일반적 개선률을 주장하지 않는다. `xact commits / workflow`는 각 실행의 `pg_stat_database` delta 관측값이며 동시 실행, stats snapshot, 계측 query의 영향을 받는다. 정확한 business transaction 수로 해석하지 않는다.

## 한계와 해석

- probe는 Java service method를 직접 호출한다. Public controller ingress, authentication, HTTP server thread pool 및 실제 inventory DB/network latency를 포함하지 않는다.
- MockWebServer는 즉시 성공만 반환한다. Timeout, 응답 유실, 재고 부족, retry·compensation, multi-process/replica는 측정하지 않는다.
- 후행 실행은 1·2·4·8 동시성에서 각 두 번, 16·32에서 각 한 번이다. 문서 표는 두 번 실행한 셀의 가장 최근 결과만 제시하며 신뢰 구간·재현성 분포를 제공하지 않는다.
- 모든 remote 호출을 끝낸 뒤 local 결과를 batch commit하므로, commit 전 crash 시 같은 idempotency key로 replay한다. Cancellation/lease loss 시 batch의 일부만 남지 않는지는 `LocationReservationServiceIntegrationTest`(`cancellationFencingRollsBackAllBufferedRemoteReservationResults`)로 검증한다. 이 test는 2026-11-23 no-cache 실행에서 15건 중 하나로 통과했다.
- 기존 local k6 runner(2026-11-20)는 seller-scoped `sellerId`와 V65 stock route를 반영하지 않아 현재 API의 기준 측정으로 사용하지 않는다. 수정한 [distributed coordinator 시나리오](../../load-tests/distributed-coordinator.js)로 현재 seller-scoped stack의 public HTTP API를 측정한 결과는 [EXP-059](EXP-059-order-reservation-load-and-idempotent-replay.md)에 있다. 두 실험은 workload와 경계가 달라 직접 비교하지 않는다.

## 재현

- 재현할 수 없다. 측정 harness `OrderInventoryReservationSubmitCapacityProbeTest`를 저장소에서 제거했고, 원본 stdout도 보존하지 않았다. 표의 수치는 당시 기록을 옮긴 것이다.
- 같은 질문을 다시 확인하려면 위 입력 조건(cell당 1,000 workflow, 동시 호출 수 1·2·4·8·16·32, location 2개, workflow당 remote 2회, MockWebServer 즉시 응답)으로 probe를 새로 작성해야 한다.
