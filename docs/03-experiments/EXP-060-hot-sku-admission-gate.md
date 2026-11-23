# EXP-060: 재고보다 훨씬 많은 주문이 한 SKU에 몰릴 때 PostgreSQL만으로 버티는가

- 질문: 재고 100인 SKU 하나에 초당 100~2,000건이 몰리면, 거절될 주문이 order DB에 남기는 비용 때문에 처리량이 무너지는가? 무너진다면 어느 지점인가?
- 상태: 탐색 실험(비율당 1~2회, 단일 호스트). 운영 SLO나 용량 보증이 아니다.
- 관련: [ADR-0066](../02-decisions/adr/ADR-0066-redis-admission-gate.md), [EXP-053](EXP-053-inventory-location-failure-load.md), [ADR-0053](../02-decisions/adr/ADR-0053-shared-infra-adoption-gates.md)(도입 조건)

> 해석 주의(2026-11-23, [ADR-0068](../02-decisions/adr/ADR-0068-external-research-direction-review.md)): 기준선(run2)과 gate(gate1/2)는 서로 다른 세션이고 비율당 1~2회다. 기준선의 반복 변동만 약 27%(1,000/s에서 383.0 vs 484.7)라 퍼센트 개선과 절대 처리량은 근거로 쓰지 않는다. 방어 가능한 결론은 "거절 주문이 durable 쓰기를 만든다"와 "그 쓰기를 없애면 포화 지점이 크게 밀린다"는 자릿수 수준이다. 같은 세션 짝 반복 비교와 gate 변형(Redis·precheck) 비교는 별도 실험으로 한다.

## 환경과 입력

- 격리된 새 Compose 프로젝트(측정 때마다 생성·삭제). application 3 CPU/2 GiB(Hikari 10), order PostgreSQL 2 CPU/2 GiB, inventory-location 1 CPU/1 GiB(Hikari 20)와 전용 PostgreSQL 1 CPU/1 GiB, k6 1 CPU/1 GiB, Redis 7.4 1 CPU/512 MiB(영속화 끔). JDK 25, PostgreSQL 18.6. 모든 구성요소가 한 호스트에 있다.
- 입력: 위치 1개, 재고 100인 SKU, 주문 1건=SKU 1줄. 도착률 100/300/500/1,000/2,000 주문/초 × 20초(constant-arrival-rate, VU 300~3,000). 같은 입력으로 gate를 끈 기준선(run1·run2)과 켠 실행(gate1·gate2)을 비교했다.
- 기준선은 [ADR-0046](../02-decisions/adr/ADR-0046-order-payment-transactional-outbox.md)의 원칙(원격 호출을 DB 트랜잭션 밖에서 실행)을 그대로 따르는 현재 구현이다.
- 재현: `load-tests/run-hot-sku-coordinator.sh`(gate는 `GATE=redis`). 결과 원본은 `.local/hot-sku-coordinator/`에 있고 저장소에는 올리지 않는다.

## 결과

| 도착률 | 기준선 completed/s (run2) | 기준선 dropped | 기준선 거절 주문당 DB tx | gate completed/s (gate1 / gate2) | gate dropped | gate 주문당 DB tx |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 99.9 | 0 | 5.66 | 99.9 / 100.0 | 0 / 0 | 0.15 / 0.18 |
| 300 | 299.8 | 0 | 5.89 | 288.0(이상치) / 299.7 | 203 / 0 | 0.08 / 0.07 |
| 500 | 499.6 | 0 | 5.84 | 499.3 / 498.3 | 0 / 0 | 0.04 / 0.04 |
| 1,000 | 383.0(run1 484.7) | 10,239(run1 8,963) | 5.94 | 992.2 / 991.3 | 140 / 152 | 0.02 / 0.02 |
| 2,000 | run1 456.0 | run1 28,498 | 5.76 | 1,959.2 / 1,938.1 | 779 / 1,096 | 0.01 / 0.01 |

- 기준선에서 거절된 주문은 모두 `order_inventory_reservation_workflow` 행을 1개(FAILED) 남기고 주문당 약 5.7~5.9개의 DB 트랜잭션을 썼다. gate를 켜면 거절 주문은 workflow 행도 location 호출도 만들지 않아 workflow 행 증가는 승인된 100건에 머물렀다.
- 기준선은 500/s까지 드롭 0이었고 1,000/s부터 completed/s가 383~485로 포화됐다(전 구간에서 p50이 수 초). 2,000/s의 기준선은 run2에 요약이 없어 run1 값만 적었다.
- 불변식: 모든 실행에서 승인(RESERVED)은 100을 넘지 않았고 location 용량 보존(available+reserved+claimed=initial)이 유지됐다.
- gate1의 300/s는 이상치다(드롭 203, p95 약 1.6 s). 원인은 조사하지 못했고, 같은 스택의 워밍업에서도 fail_open 10건이 있었다. gate2의 300/s는 정상이었다.

## 실패 주입: Redis 중지(1,000/s, 시작 후 약 8초에 컨테이너 중지)

| 구성 | completed/s | dropped | FAILED workflow 행 | 오버셀 |
| --- | ---: | ---: | ---: | --- |
| breaker 없음(1회) | 355 | 7,299 | 5,294 | 없음 |
| breaker 적용(down2 / down3) | 707 / 730 | 3,774 / 3,472 | 8,562 / 8,861 | 없음 |

- breaker 없이는 장애 후 구간이 약 190/s로 기준선(383~485/s)보다 낮았다. 요청마다 죽은 Redis의 command timeout(200ms)을 기다리는 것이 원인이라는 해석은 **추정**이다(계측으로 분리하지 않았다). breaker(기본 cool-down 2초) 뒤에는 장애 후 구간이 약 500~660/s로, 기준선의 포화 구간과 비슷했다.
- 즉 Redis 장애 시 안전성(오버셀 없음, 5xx 없음)은 유지되지만, 처리량은 기준선 수준으로 돌아갈 뿐 그보다 나아지지 않는다. 복구 후 breaker가 닫히는 동작은 측정하지 못했다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 이 구성에서 PostgreSQL만으로는 약 1,000 주문/초부터 처리량이 포화되고, 포화 원인 후보 중 하나는 거절 주문마다 남는 durable 쓰기다(거절이 쓰기를 만들지 않게 하자 같은 입력에서 포화가 사라졌다). 단, 같은 호스트의 CPU 한도 안에서의 결과다.
- 증명하지 못한 것: 비율당 1~2회라 신뢰구간이 없다. Redis가 app과 같은 호스트에 있어 네트워크 지연과 CPU 경쟁이 실제 배포와 다르다. 2,000/s 이상의 포화점, k6 생성기 한계와 앱 한계의 구분, Redis 재시작 후 복구는 측정하지 않았다. gate가 없는 쪽을 개선하는 대안(거절 주문의 쓰기를 줄이는 PostgreSQL 내부 최적화)은 비교하지 못했다.
