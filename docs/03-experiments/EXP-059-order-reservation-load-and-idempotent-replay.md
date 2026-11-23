# EXP-059 — 주문 재고 coordinator 과부하와 응답 유실 replay

## 목적과 범위

- Tracked K6 scenario와 runner로 실제 coordinator HTTP API의 offered load, 응답 지연, dropped iteration, DB 수렴을 측정한다.
- 성공 응답이 coordinator에서 생성된 뒤 client에 전달되지 않는 상황에서 같은 body·`Idempotency-Key` 재전송이 하나의 durable workflow로 수렴하는지 확인한다.
- 이 실험은 `POST /api/internal/order-inventory/reservations` synthetic workload다. Buyer browse/cart/checkout 전체, PayPal Sandbox 결제, 사용자 session, production SLO를 검증하지 않으며 [NFR-002](../01-requirements/requirements-index.md)의 10,000 Buyer sessions/60분 및 2×/4× profile을 충족하지 않는다.

## 환경과 방법

- macOS arm64 호스트(11 logical CPU/18 GiB), Docker VM(8 CPU/약 12.6 GiB). Application은 호스트 JVM, PostgreSQL·inventory-location service는 Docker Compose의 격리된 새 project/volume에서 실행했다. 이전 `atlas-inventory` stack과 DB volume은 재사용·수정하지 않았다.
- High-VU profile의 K6 container만 4 CPU/6 GiB로 제한했다. Application·PostgreSQL·inventory-location container에는 고정 CPU/memory quota를 두지 않았다. 같은 host/VM의 자원을 공유하므로 결과는 용량 한계나 다른 머신과의 비교가 아니다.
- Scenario: unique `RUN_ID`, seller-scoped stock, workflow당 3 SKU line, location 1개 또는 2개. 2-location profile은 workflow당 두 location API reservation call을 수행한다. Stock conservation과 coordinator DB workflow terminal state를 runner에서 확인했다.
- 실행 도구: [`distributed-coordinator.js`](../../load-tests/distributed-coordinator.js), [`run-distributed-coordinator.sh`](../../load-tests/run-distributed-coordinator.sh). `iterations.rate`는 K6 summary 전체 실행·drain 구간 평균이며 설정한 arrival rate와 다르다. 설정 arrival rate, `iterations`, `dropped_iterations`를 함께 해석한다.

## 결과 — 실제 service workload

| 설정 arrival / duration / location | VU 상한·K6 자원 | Iterations / K6 summary rate | Dropped | HTTP p95 / p99 | 최종 DB workflow |
|---|---|---:|---:|---:|---:|
| 50/s × 5초 / 2 | 20 VU | 251 / 44.89/s | 0 | 209.77 / 374.34 ms | 251 `RESERVED`, nonterminal 0 |
| 250/s × 30초 / 2 | 1,000 VU | 7,329 / 243.84/s | 172 | 1,026.77 / 1,535.06 ms | 7,329 `RESERVED`, nonterminal 0 |
| 1,000/s × 30초 / 1 | 4,000 VU | 15,195 / 381.52/s | 14,806 | 10,124.31 / 10,378.86 ms | 15,195 `RESERVED`, nonterminal 0 |
| 1,000/s × 30초 / 2 | 4,000 VU | 13,365 / 326.10/s | 16,637 | 11,974.19 / 12,243.99 ms | 13,365 `RESERVED`, nonterminal 0 |
| 1,000/s × 30초 / 1 | 10,000 VU, K6 4 CPU/6 GiB | 22,003 / 363.95/s | 7,899 | 26,803.69 / 27,122.84 ms | 14,681 `RESERVED`, nonterminal 0 |
| 1,000/s × 30초 / 2 | 10,000 VU, K6 4 CPU/6 GiB | 17,111 / 275.47/s | 12,257 | 33,446.55 / 33,700.99 ms | 13,464 `RESERVED`, nonterminal 0 |

- 4,000-VU profile은 시작된 요청의 `http_req_failed=0`이었지만 target arrival을 유지하지 못해 dropped iteration이 발생했다. Persist된 workflow는 모두 terminal `RESERVED`였다.
- 10,000-VU two-location profile의 status counter는 HTTP `201=11,948`, `202=873`, status `0=4,280`을 기록했다. Workflow row 13,464개는 응답 `201/202` 12,821개보다 643개 많았다. Request-key별 응답과 DB commit의 상관관계는 수집하지 않았으므로 각 차이를 특정 실패 원인으로 단정할 수 없다. 다만 status 0과 row 차이는 결과가 불명확한 transport window를 보여준다.
- 같은 profile의 `http_req_waiting` p95/p99는 33,445.95/33,699.69 ms였고 `http_req_connecting` p95는 7.47 ms였다. Tail은 주로 응답 대기 구간에 있었다. DB pool·애플리케이션 자원 고갈이 원인이라고 확정할 증거는 없으며, 이 run은 resource-isolation 진단이 아니다.
- High-load runner는 dropped iteration 또는 iteration/DB row 불일치가 있으면 실패 상태를 반환한다. 최종 row가 `RESERVED`라는 사실은 목표 부하를 처리했다는 의미가 아니다.

## Controlled response-loss replay

- Fresh isolated stack에서 20 workflow/s × 5초, 2 locations, `COORDINATOR_TRANSPORT_RETRIES=1`로 실행했다. Proxy는 각 고유 key의 첫 POST를 실제 coordinator에 전달하고 upstream response body를 읽은 뒤 client 응답만 폐기했다. K6가 같은 request body와 idempotency key로 재전송하도록 했다.
- Proxy 집계: upstream response 폐기 101건, same-key replay 101건. K6는 실제 iteration 101건(설정 100건보다 1건 많은 constant-arrival boundary), dropped 0건, 최종 HTTP `200` replay 101건, script-level retry 20건/회복 20건, replay metric 101건, check 105 pass/0 fail을 기록했다. K6 summary의 status 0은 20건이었다.
- DB 수렴: iteration 101건, workflow 101건, 모두 `RESERVED`, nonterminal 0; runner exit 0. `200`은 이미 완료된 동일 workflow의 replay 응답이며 K6에서 수락·`RESERVED`로 계산한다.
- Proxy replay 101건과 K6 script-level retry counter 20건은 같은 지표가 아니다. K6 HTTP client 내부 retry도 관측되어 script counter만으로 전체 transport replay 수를 세지 않는다. 따라서 proxy의 forwarded request 수와 K6 custom counter를 구분한다.
- 이 fault probe는 로컬 proxy와 synthetic workflow만 사용했다. PayPal capture의 UNKNOWN 복구나 사용자 client의 모든 network stack을 대신 검증하지 않는다.

## Two-process recovery correctness smoke

- Fresh Compose project와 coordinator/location PostgreSQL을 사용했다. Recovery worker가 비활성화되고 location endpoint가 거부되는 application instance에 K6 `5 workflow/s × 5초`를 보내 25개 iteration/workflow를 생성했다. K6 runner는 성공했고, worker를 다시 켜기 전 workflow는 25 nonterminal·0 `RESERVED`였다.
- Staging instance를 종료한 뒤 같은 coordinator DB에 두 개의 독립 JVM을 실행했다. 첫 instance만 동작하는 동안 1/25 workflow가 `RESERVED`가 된 것을 확인한 뒤 두 번째 instance를 시작했다. 두 process 모두 batch size 1·1초 poll로 recovery를 수행했다.

| 관측 | 결과 |
|---|---:|
| Coordinator workflow | 25 total, 25 `RESERVED`, nonterminal 0 |
| Recovery claim metric | JVM A 15, JVM B 10 (두 metric 합계 25) |
| 두 번째 JVM 시작 후 drain | 10초 |
| Inventory location A | reservation 25, distinct order 25, `RESERVED` 25 |
| Inventory location B | reservation 25, distinct order 25, `RESERVED` 25 |

- 이 결과는 같은 PostgreSQL을 쓰는 두 application process에서 `SKIP LOCKED` recovery와 location idempotency가 중복 reservation 없이 수렴한 correctness smoke다. 단일 host·작은 표본이며 고정 자원 또는 처리량 비교가 아니다.

## 판단과 미결 사항

- 1,000 workflow/s offered load는 모든 location/VU profile에서 dropped iteration 없이 지속되지 않았고 high-VU에서는 response tail이 수십 초까지 늘었다. 기존 submit probe 최고 528.99 workflow/s와 recovery probe 최고 775 workflow/s도 각각 다른 synthetic workload이므로 직접 비교하지 않는다([EXP-056](EXP-056-order-inventory-recovery-capacity.md), [EXP-058](EXP-058-order-reservation-submit-capacity.md)).
- [EXP-013](EXP-013-gate4-20-minute-calibration.md)은 profile당 10분, [EXP-016](EXP-016-buyer-checkout-comparison.md)은 120초 profile이다. NFR-002의 전체 Buyer 60분·2×/4× run, fixed node resource envelope, provider/network/PayPal path와 장시간 안정성은 별도 과제로 남는다. 현재 tracked `buyer-checkout.js`는 constant-arrival `checkout-only`와 단일 iteration PayPal Sandbox smoke/verify를 분리한다. 이 구성으로 full payment/capture 장시간 workload를 실행하지 않았고, 실제 Sandbox에 대량 결제를 보내는 profile도 만들지 않았다.
- 위 two-JVM smoke는 correctness 검증만이며 처리량·운영 SLO 또는 고정 resource envelope에 대한 근거가 아니다.
