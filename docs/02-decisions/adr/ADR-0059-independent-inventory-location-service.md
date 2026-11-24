# ADR-0059: fulfillment location별 inventory runtime과 저장소를 분리한다

- 상태: Accepted — 독립 location 기반 Stage 3 foundation
- 날짜: 2026-11-20
- 관련 결정: [ADR-0017](ADR-0017-seller-fulfillment-locations.md)(Seller fulfillment location), [ADR-0041](ADR-0041-bounded-admission-expiry-sweep.md)(bounded SKIP LOCKED sweep), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md)(외부 결과와 owner 적용의 분리), [ADR-0058](ADR-0058-distributed-fulfillment-stage-one-baseline.md)(단일 저장소 기준선)

## 문제

현재 checkout의 재고 예약은 주문과 같은 PostgreSQL transaction boundary 안에 있다. 이 경계에서는 inventory process/database가 독립적으로 단절되거나, 예약 commit 후 HTTP 응답만 유실되는 상태를 재현할 수 없다. 제안된 프로젝트가 다루려는 핵심은 이 부분 실패에서도 재고를 초과 예약하지 않고 같은 결과로 복구하는 것이다.

## 가정과 측정 입력

아래 수치는 서비스 운영 실적이 아니라 회복 경로와 저장 요구를 계산하기 위한 합성 workload다.

- location당 평시 1,000 order/s, 주문당 3 SKU line, 예약 TTL 2분을 가정한다.
- 모든 요청이 결제·취소 없이 TTL까지 남는 상한은 120,000개 reservation header와 360,000개 item row/location이다 (`rate × TTL`).
- 10,000 order/s가 2분 지속되는 burst-only 상한은 1,200,000 header와 3,600,000 item row/location이다.
- 1,000 order/s에서 expiry worker가 60초 중단되면 60,000 due reservation이 추가된다. 다음 60초 내 backlog를 비우면서 새로 due되는 1,000/s도 처리하려면 합산 약 2,000 reservation/s가 필요하다. 현재 기본 batch 100/초는 이 가정을 만족하지 않는다.
- 기존 ATLAS 주문 흐름의 Stage 1/2 회귀 검증은 [ADR-0058](ADR-0058-distributed-fulfillment-stage-one-baseline.md) 기준. 새 location service의 성능은 별도 로컬 workload로 측정하며 운영 SLO로 해석하지 않는다.

## 결정

1. 새 runtime은 root Gradle project `:inventory-location-module`, 디렉터리 `ATLAS-inventory-location`, package `com.atlas.inventorylocation`로 구성한다. 이는 기존 `ATLAS-inventory` 재고 책임을 별도 process로 호스팅하고 새 도메인 이름을 만들지 않는다.
2. 각 service instance는 하나의 `FULFILLMENT_LOCATION_ID`만 소유하고, 해당 location의 PostgreSQL만 읽고 쓴다. Compose 구성은 location A/B와 각자의 database를 분리해 장애·정합성 경계를 재현한다.
3. `inventory_location_stock`의 불변식은 `initial_capacity = available + reserved + claimed`; guarded SQL update와 DB CHECK constraint 양쪽에서 음수 및 초과 예약을 막는다.
4. Reserve는 `(fulfillment_location_id, idempotency_key)`로 멱등 처리한다. 동일 key·동일 order/items는 기존 reservation을 반환하고 facts 변경 재사용은 conflict다. 여러 SKU 중 하나라도 부족하면 header·item·모든 stock update가 같은 local transaction에서 rollback된다.
5. Compensation이 reserve보다 먼저 도착할 수 있다. `POST /reservations/cancel`은 같은 idempotency key와 order id로 `CANCELLED` tombstone을 기록하고, 뒤늦게 온 reserve는 `RESERVATION_CANCELLED`로 거절한다. 이미 예약된 경우 row lock 아래 stock을 release한다. Order-side coordinator가 이 계약을 사용하며 durable orchestration과 recovery policy는 [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)에 기록한다.
6. Claim·release는 reservation row lock과 `RESERVED` 조건부 transition으로 직렬화한다. claim/release 경합에서 한 terminal state만 적용된다. 2분 TTL은 로컬 검증용 기본값이며, 만료 sweep은 bounded batch와 `SKIP LOCKED`를 사용하고 다음 poll에서 잠긴 row를 재시도한다.
7. `/internal/v1/**` API는 32자 이상 service token을 constant-time 비교로 검사한다. Compose host port는 loopback에만 bind한다. 운영 배포에서는 secret manager와 TLS/mTLS ingress로 보강한다. 로컬 token은 운영 credential이 아니다.
8. Inventory service 자체에는 message broker나 Saga framework를 추가하지 않는다. Order-side durable workflow·partial compensation·restart recovery는 [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)에 구현·기록했다. Feature flag가 켜지면 Buyer checkout도 원격 reservation·PayPal authorization/capture/claim 흐름에 연결되며, 전체 Buyer 10,000-session/60-minute·2×/4× workload와 production SLO는 별도 미검증이다. Broker는 외부 consumer fan-out·event replay 또는 측정된 DB polling 병목이 생길 때 비교한다.
9. coordinator 부하 scenario(`load-tests/distributed-coordinator.js`)와 Compose 정의(`load-tests/compose-inventory-location.yaml`)는 저장소에 추적한다. fault-injection fixture와 원본 실행 결과·service token은 git-ignore된 `.local/distributed-fulfillment/`에 두며, GitHub에는 production code, functional regression test, 측정 조건·결과·한계만 기록한다.

## 대안과 trade-off

| 대안 | 선택 또는 제외 근거 |
|---|---|
| 기존 modular monolith과 공유 PostgreSQL만 유지 | Stage 1/2에 충분하고 기준선으로 재사용한다. 하지만 독립 DB/process 장애와 remote-response-loss 정합성은 보여주지 못한다. |
| 같은 PostgreSQL 안에 location schema만 분리 | 코드 경계는 만들 수 있지만 DB 장애·connection pool·resource pressure를 location별로 분리하지 못한다. |
| 별도 service/DB와 row-level guarded update | location process/database 장애를 실제로 재현하고, 마지막 재고 경쟁의 승자를 DB invariant로 증명할 수 있어 선택한다. |
| cache GET/DECR로 재고 예약 | read와 decrement 사이 경쟁으로 oversell이 가능하다. Alibaba Cloud hot-spot 글의 e-commerce 사례를 참고해 단일 guarded update와 보존 불변식을 기준으로 삼는다. |
| Seata TCC를 즉시 도입 | TCC의 retry/idempotency·compensation 요구를 참고하되, 현재 한 location의 로컬 reserve/claim/release 상태에는 별도 transaction framework보다 명시적 DB 상태 전이가 단순하다. Cross-service coordinator가 필요해지면 다시 비교한다. |

## 실패 시나리오와 검증 결과

- inventory-location의 PostgreSQL integration suite(`InventoryReservationIntegrationTest`)는 응답을 받지 못한 caller가 같은 key로 재시도, multi-line 중간 stock 부족 rollback, 100개 동시 last-unit 경쟁, claim-vs-release, expiry-vs-late-claim, token 누락을 검증한다. 이 ADR 작성 당시에는 9건이었고, restore 검증 등을 더한 2026-11-23 no-cache 실행에서는 13건이 모두 통과했다.
- Two-location local smoke run에서 같은 SKU capacity 1을 location A/B 각각에 만들고 A에만 reservation을 보냈다. 관측 결과 A=`available 0 / reserved 1`, B=`available 1 / reserved 0`; DB도 별도 container다.
- Toxiproxy local-only run은 DB commit 뒤 downstream 첫 응답을 실제로 끊었다 (`curl` status 000). 같은 idempotency key retry는 `replayed=true`와 기존 reservation ID를 반환했고 stock은 `available 0 / reserved 1 / claimed 0`으로 한 번만 이동했다.
- 단일 location k6 100-VU/100-iteration hot-key run(1회): 예약 성공 1, stock conflict 99, HTTP failure 0, `http_req_duration` p95 54.29 ms/p99 56.38 ms. 이는 correctness burst test이며 sustained capacity 값이 아니다.
- 단일 location sustained 1,000 request/s × 30초 run(1회): 24,649 reservation 완료, 5,352 dropped iteration, HTTP failure 0, p95 1.77 s/p99 1.90 s. 관측 처리량 776/s는 완료 건수를 k6 전체 실행 시간으로 나눈 값이다(24,649 ÷ 776 ≈ 31.8초, 역산). dropped iteration은 k6가 가용 VU를 찾지 못해 시작하지 못한 iteration이며 server 오류가 아니다. k6는 최대 1,000 VU에 도달했다. 즉 안전성은 유지했으나 해당 로컬 조건에서 1,000/s 목표 처리량은 달성하지 못했다. 실제 원인을 hot-row contention, pool wait, client VU ceiling으로 분해한 뒤 동일 조건으로 재측정해야 한다.
- 동일한 1,000/s × 30초 입력을 두 independent location DB에 분배한 run(1회): 30,001 reservation 완료(약 1,000/s, 30,001 ÷ 30초), dropped iteration 0, HTTP failure 0, p95 1.20 ms/p99 2.50 ms. 완료 건은 A 14,998, B 15,003이다(합 30,001). 한 번의 로컬 비교이며 scale-out의 방향성만 보여준다. 단일 location run과의 p95 차이(1.77 s 대 1.20 ms)는 실행 순서와 VM cache 상태를 통제하지 않은 상태의 관측이다.
- 측정 환경: macOS host의 Docker Linux VM 8 vCPU/약 12.6 GiB, JDK 25, PostgreSQL 18.6, Temurin 25 JRE, k6 2.3.0, location별 Hikari pool 20. 각 profile은 1회 탐색 실험이며 반복·오차범위가 없어 운영 성능 주장이 아니다.
- 전체 방법과 산술 가정: [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md). 로컬 scenario source는 GitHub에 올리지 않는다.

## 재검토 조건

- location 분산이 높은 입력에서도 p95/p99와 dropped iteration을 개선하지 못하면 stock update, pool sizing, client VU ceiling을 분리 측정하고 hot-SKU admission/bucketing 대안을 검토한다.
- 2,000 due reservation/s 목표가 bounded sweep으로 충족되지 않으면 expiry batch, worker replica 수, row-lock duration을 각각 조정하고 PostgreSQL 통합 테스트로 확인한다.
- Buyer checkout·payment와 coordinator의 연결(feature flag 활성)에 대한 end-to-end 근거는 [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md)의 소규모 단일 실행(Buyer checkout 21회, PayPal Sandbox 경로 각 1회)뿐이다. 전체 Buyer 10,000-session/60분·2×/4× workload, 장애 복구를 섞은 end-to-end 실행, production SLO는 충족했다고 주장하지 않는다.

## 참고 자료

- Flipkart Tech Blog, [When Good Locks Go Bad: Diagnosing a System Meltdown Under Load](https://blog.flipkart.tech/when-good-locks-go-bad-diagnosing-a-system-meltdown-under-load-0b168c363143) — queueing/fail-fast 교훈과 lock contention 하 tail-latency 관찰을 참고.
- Alibaba Cloud, [How to Process Hot Spot Data in E-commerce Scenarios](https://www.alibabacloud.com/blog/how-to-process-hot-spot-data-in-e-commerce-scenarios_596491) — hotspot을 일반 평균과 분리해 측정하고 atomic stock change를 요구하는 근거.
- Apache Seata, [TCC Mode](https://seata.apache.org/docs/user/mode/tcc/) — branch transaction의 Try/Confirm/Cancel retry 의미를 비교; Seata framework는 도입하지 않음.
