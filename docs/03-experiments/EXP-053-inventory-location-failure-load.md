# EXP-053: hot-SKU 경쟁과 location 분산에 따른 재고 예약 처리

- 질문: 한 fulfillment location의 hot SKU에 주문 예약이 집중될 때 재고 불변식과 요청 처리율은 유지되는가? 같은 workload를 두 독립 location DB로 나누면 관측 결과는 어떻게 달라지는가?
- 환경: macOS host, Docker Linux VM 8 vCPU/약 12.6 GiB, JDK 25.0.5, PostgreSQL 18.6, Temurin 25 JRE, k6 2.3.0 image. Order coordinator profile은 host에서 실행한 application JVM과 Docker 내 k6(host.docker.internal 경유), 컨테이너 order DB·두 inventory service를 사용했다. Inventory DB pool은 location별 20 connection. Docker Compose로 두 inventory process와 두 PostgreSQL database 실행.
- 데이터: run마다 SKU를 새로 생성. Last-unit 입력은 capacity 1; throughput 입력은 location당 capacity 16,500 또는 단일 location capacity 33,000. 예약 TTL은 2분.
- 부하 입력: last-unit은 100 VU/100회 단발 경쟁. throughput은 arrival-rate 1,000 reservation/초 × 30초, pre-allocated VU 100, maximum 1,000. Two-location 입력은 모든 요청을 A/B에 거의 반씩 분배.
- 반복: 각 profile 1회 탐색 실행. 통계적 신뢰구간·운영 SLO로 해석하지 않음.

## 결과

| Profile | HTTP 결과 | 완료/유실 | p50 | p95 | p99 | 재고 invariant |
|---|---:|---:|---:|---:|---:|---|
| 마지막 1개 재고, 100 VU, capacity 1 | 1건 생성, 99건 conflict, HTTP 실패 0 | 100/100 | 42.37 ms | 54.29 ms | 56.38 ms | available 0, reserved 1, claimed 0 |
| 1 location, 1,000/s × 30s | HTTP failure 0 | 24,649 완료(약 776/s), 5,352 dropped iteration | 121.57 ms | 1.77 s | 1.90 s | 24,649 reserved, 나머지 capacity available; 초과 예약 0 |
| 2 locations/2 DBs, 같은 1,000/s × 30s | HTTP failure 0 | 30,001 완료(약 1,000/s), dropped iteration 0 | 0.621 ms | 1.20 ms | 2.50 ms | A 14,998, B 15,003 reservation; 각 DB capacity 보존 |
| 독립 저장소 smoke | API 2xx | A 한 건만 reserve | — | — | — | A available 0/reserved 1, B available 1/reserved 0 |
| Order coordinator, 2 locations, 250 order/s × 30s | HTTP failure 0.08% (6/7387), client checks 100% | 완료 7,356(244.9/s), 잔여 pending 25, dropped 120 | 28.8 ms | 865 ms | 1.14 s | 최종 DB workflow 전부 RESERVED 7,381, nonterminal 0 |
| Order coordinator, 같은 2 locations, 1,000 order/s × 30s | HTTP failure 0.05% (7/11769), client checks 99.99% | 완료 11,673(360.9/s), pending 89, rejected 1, dropped 18,238(563.8/s) | 2.62 s | 3.13 s | 3.39 s | 최종 DB workflow 전부 RESERVED 11,763, nonterminal 0 |
| 같은 profile, snapshot N+1 제거·pool 60·maxVUs 2,000 | HTTP failure 0.06% | 완료 16,299(470.9/s), dropped 13,612 | 3.50 s | 4.53 s | 4.80 s | 전부 RESERVED, nonterminal 0 |

Last-unit profile은 동시 마지막 재고에 대해 정확히 하나만 성공하는 안전성을 검증했다. Single-location throughput은 k6가 1,000 iteration/s를 유지하지 못했고 VU 상한 1,000에 도달했다. Server HTTP failure는 0이었으나 queueing으로 완료 처리율이 약 776/s에 머물고 iteration 5,352건이 load generator에서 drop됐다.

Order coordinator profile은 k6가 host의 internal API로 3 SKU line/2 location call을 보낸 결과다. 250 order/s는 목표의 약 98%(244.9/s)를 완료했고 p99 1.14 s였다. 같은 코드에 1,000 order/s를 요구하자 완료는 360.9/s(목표의 36%)로 포화됐고 client가 18,238 iteration을 drop했으며 p99는 3.39 s로 악화됐다. k6가 maximum 1,000 VU에 도달했으므로 이 결과만으로 server 한계와 load generator 한계를 분리할 수 없다. 어느 profile에서도 완료된 workflow는 전부 RESERVED로 수렴했고 nonterminal로 남은 것은 0이었다.

동일한 요청률을 두 location에 나눈 profile에서는 30초 동안 약 30,000개 예약을 완료하고 p95 1.20ms/p99 2.50ms를 관측했다. 두 profile은 같은 코드·machine·duration·target arrival을 사용했지만 각각 한 번 실행했다. 따라서 이는 독립 location으로 분산할 가치가 있음을 보이는 **방향성 실험**이지, 1,000/s production capacity를 보장하는 A/B 성능 증명이 아니다.

## 실패 주입과 디버깅 기록

- PostgreSQL integration suite의 9개 test(실패 0)는 100개 동시 last-unit 요청(성공 1·insufficient-stock 99), multi-line 둘째 SKU 부족 시 전체 rollback, claim/release 경합, expired claim과 `SKIP LOCKED` expiry race, compensation-before-reserve tombstone, key replay, service-token 검사를 검증했다.
- Inventory-only Toxiproxy run은 DB commit 뒤 downstream response를 끊었다. 첫 client 결과는 HTTP status `000`; 같은 key/facts retry는 기존 reservation ID와 `replayed=true`를 반환했고 stock은 한 번만 이동했다.
- Order coordinator Testcontainers 5건은 부분 location 실패 보상, 응답 유실 뒤 workflow 재생성·같은 key retry, 다음 location 4초 지연 중 TTL safety margin 아래로 떨어진 hold의 전 location 취소, internal token 거절/허용을 검증했다.
- Local feature flag가 test JVM에 상속돼 unrelated Spring context까지 remote client를 초기화하며 368개 중 194개 context-load failure가 발생했다. Test task에 feature default `false`를 고정하고 coordinator suite만 `@DynamicPropertySource`로 opt-in한 뒤, 같은 exported shell flag 상태에서 368/368 application tests가 통과했다.
- Local Toxiproxy/process-restart run은 commit 직후 response를 drop해 coordinator의 첫 결과를 `RETRY_PENDING`으로 만들고 application JVM을 강제 종료·재기동했다. 영속 workflow는 `RESERVED`로 수렴했고 kill 직전부터 완료까지 10초(1초 해상도), app healthy 이후 worker 추가 대기는 0초였다. 최종 stock은 `available 0 / reserved 1 / claimed 0`; 1회 실험이다.
- 첫 two-location k6 시도는 VU별 init 변수에서 서로 다른 SKU를 만들 수 있는 harness 오류로 setup한 SKU와 요청 SKU가 달랐다. `setup()`에서 SKU를 반환해 모든 VU가 같은 seeded SKU를 쓰도록 수정했다. 오류 응답에서 `INSUFFICIENT_STOCK`/누락 row를 확인했고, 해당 실행은 성능 결과에서 제외했다. 수정 뒤 4 request/s smoke와 위 1,000/s profile을 실행했다.

## 규모 산술과 미확인 범위

- 1,000 order/s/location × 2분 TTL에서 모든 예약이 미확정으로 남으면 120,000 header, 평균 3 item/order에서 360,000 item row/location이다.
- 60초 worker 중단은 약 60,000 due header를 만든다. 60초 내 backlog를 비우면서 새로 due되는 1,000/s도 처리하려면 aggregate expiry 처리량 약 2,000/s가 필요하다. 현재 default batch 100/1초는 이 가정을 충족하지 않는다.
- 측정은 단일 host의 동일 Docker VM 안에서 실행했다. Coordinator의 응답 유실 후 JVM 재시작은 1회 검증했지만 물리적으로 독립된 zone/network, PostgreSQL failover/partition, payment 결과 유실은 아직 실험하지 않았다.
- Order coordinator는 250 order/s에서 완료 처리율이 목표의 약 98%였고 1,000 order/s에서는 36%로 포화됐다. 병목 후보는 (1) coordinator의 순차 2-location 호출, (2) order DB workflow write/lease claim, (3) 단일 inventory location당 약 500 remote calls/s, (4) k6 client VU 상한이다. 이 실험은 후보를 나누지 못했으므로 다음 측정은 client 생성기를 분리하고 location call을 병렬화했을 때와 순차 유지 시를 같은 입력으로 비교해야 한다. Buyer checkout/payment에 연결된 production order routing으로 해석하지 않는다.

## Coordinator bottleneck decomposition

같은 1,000 order/s 입력에서 431–470/s에 머문 원인을 후보별로 분리했다. 모두 위와 같은 host/VM, 같은 30초 arrival-rate, k6 컨테이너에서 실행했다.

| 가설 | 방법 | 결과 | 판정 |
|---|---|---|---|
| k6 또는 servlet filter 상한 | token 없는 internal route에 1,000/s 요청(인증에서 즉시 거절, DB 접근 없음) | 30,001 요청 완료, 999.96/s, drop 0, p99 4.51 ms | 기각 |
| 순차 2-location remote 호출 | order당 1 location(3 line)으로 동일 1,000/s 측정 | 완료 311.7/s, drop 19,792 | 기각(단일 호출도 동일하게 포화) |
| inventory service 처리 비용 | 즉시 응답하는 local stub으로 2 location 대체 | 완료 343.9/s, drop 18,531 | 기각 |
| DB connection pool | Hikari 60으로 증설 후 동일 측정 | 완료 431.5/s(343.9 대비 +25.5%), p99 3.60 s | 부분 요인 |
| k6 VU 상한 | maxVUs 4,000으로 증설 | 완료 462.2/s(431.5 대비 +7.1%), p99 8.56 s | 부분 요인(서버가 포화) |
| snapshot N+1 read | location 수만큼 item 쿼리를 추가 실행하던 것을 단일 JOIN으로 변경 후 동일 측정 | 완료 470.9/s, drop 13,612, p99 4.80 s | 부분 요인(+9.0%) |

DB 계측(`pg_stat_database` delta): 수정 전 order당 7.79 transaction·5.70 insert·3.73 update, 수정 후 7.86 transaction·5.71 insert. 즉 snapshot 읽기를 쿼리 1회로 줄여도 transaction 수는 그대로다. 각 transaction이 별도 왕복이므로 order당 왕복이 처리율의 상한을 만든다.

### 판정

1,000 order/s는 이 로컬 구성에서 여전히 미달(최선 470.9/s, 목표의 47%)이다. 클라이언트·단일 location·inventory 서비스·snapshot N+1은 주범이 아니었다. 지배 비용은 order당 7.8개의 개별 DB transaction이며, remote 호출을 DB transaction 안에 넣지 않는다는 [ADR-0046](../02-decisions/adr/ADR-0046-order-payment-transactional-outbox.md) 경계 때문에 단순 병합만으로는 줄이기 어렵다. 다음 개선은 write batching 또는 상태 전이별 transaction 병합을 같은 경계를 유지하며 설계하고, worker claim 비용을 분리 측정하는 것이다.

## 해석과 한계

- Safety는 test/load 조건에서 확인했고 응답 유실 후 단일 coordinator 재기동의 liveness도 1회 관측했다. Overload liveness와 service-level behavior는 여전히 불충분하다. Single-location 과부하 시 server는 5xx 대신 대기했으며 load generator는 VU 상한에서 drop됐다. Fail-fast, bounded DB lock wait, retry budget, overload metrics는 다음 실험 대상이다.
- Two-location 결과의 큰 tail-latency 차이는 한 번의 synthetic run이다. 다른 load shape, client-side distribution, pool saturation, VM cache/warmness 영향을 배제하지 못했다.
- 2026-11-20 측정의 k6 실행기와 원본 fixture는 `.local` 전용이었다. 현재 사본은 reservation item의 `sellerId`를 누락하고 V65 이전 stock route를 사용하므로 현재 계약의 재현 기준으로 사용할 수 없다.
- V65 seller-scoped 계약을 반영한 추적 시나리오는 [distributed-coordinator.js](../../load-tests/distributed-coordinator.js)에 추가했으나, 현재 seller-scoped inventory stack에서의 실행 결과는 아직 없다. 최신 service-level Testcontainers probe는 [EXP-058](EXP-058-order-reservation-submit-capacity.md)에 기록한다.
- Buyer checkout 통합 경로는 아래의 소규모 단일 실행으로 별도 측정했다. 위 coordinator 단독 결과와 workload가 달라 직접 비교하지 않는다.

### Buyer checkout과 원격 예약 연결 확인

- 조건: macOS의 Apple M3 Pro 11 CPU·메모리 18 GiB 공유 호스트, Colima PostgreSQL 18.6의 분리된 주문·재고 DB, JDK 25.0.5.1의 application과 inventory-location JVM 각 1개, k6 2.4.0. 자원 격리나 다른 호스트 부하 통제는 하지 않았다.
- 입력: Seller 1명, SKU 2개를 각 1개씩 주문하며 SKU별 초기 재고는 21개. session마다 cart PUT 2회와 checkout POST 1회를 수행했다. 초당 2 session의 constant-arrival-rate를 10초 동안 적용했고 VU는 최대 5개였다. 경계 iteration을 포함해 21회가 시작됐다.
- 결과: checkout 응답 검증 성공 21/21, session 오류와 dropped iteration 각각 0, checks 105/105. 명목 투입 시간 10초 기준 2.1 checkout/s이며 **포화 처리량이 아니다**. checkout POST 지연은 p50 64.777 ms, p95 100.731 ms, p99 231.964 ms; cart PUT을 포함한 session은 각각 98 ms, 134 ms, 352.4 ms였다.
- 실행 직후 DB snapshot에서 checkout·SellerOrder·workflow·원격 command 및 원격 reservation이 각각 21건 `RESERVED`, 원격 item은 42건이었다. SKU별 `initial 21 / available 0 / reserved 21 / claimed 0`을 확인했다. 이는 2분 TTL 만료 전의 snapshot이며 이후 상태를 검증한 결과는 아니다.
- 한계: 짧고 작은 단일 실행이라 p99가 불안정하고 통계적 대표성이나 개선 효과를 주장할 수 없다. 같은 조건의 변경 전 기준선, 포화점, 장애 복구, 결제와 claim은 측정하지 않았다. 격리된 Seller의 `DEFAULT` location은 fixture가 DB에 직접 삽입했으므로 Seller location 설정 및 재고 동기화도 측정 범위 밖이다.
- 이 실험의 재현 스크립트(`buyer/run.sh`)와 원본 k6 결과(`result.txt`)는 git-ignore된 로컬 디렉터리에만 있어 이 저장소만으로는 재현할 수 없다. 조건과 수치는 위 항목에 옮겨 적었다. 추적되는 가장 가까운 harness는 `load-tests/run-distributed-coordinator.sh`이지만 시나리오가 같지 않으므로 동일 재현으로 간주하지 않는다. 실제 결제·출고 완료량이 아니라 checkout 응답과 원격 예약 확인까지의 수치다.

### 실제 PayPal Sandbox 승인·매입 확인

- 조건: 위와 같은 로컬 구성에 `ATLAS_PAYMENT_PROVIDER=paypal`, 실제 PayPal Sandbox REST API, 가입 없는 ECB 기준환율(`ATLAS_EXCHANGE_RATE_PROVIDER=frankfurter`)을 사용했다. checkout은 Seller 1명·SKU 2개(각 1개, 단가 10,000원)이며, 원격 예약 TTL은 기본 2분이다. 구매자 승인은 저장소에 보존하지 않는 로컬 Playwright 스크립트가 PayPal 게스트 카드 결제 화면에서 Luhn 유효 테스트 카드로 수행했다.
- 만료 경로: checkout 생성 후 원격 예약 TTL이 지난 뒤 승인했다. 원격 claim이 만료로 거절됐고 매입 요청 없이 승인이 void돼 checkout·주문 `FAILED`, 결제 `VOIDED`가 됐다.
- 정상 경로: checkout 생성부터 승인 callback까지 79초. 최종 DB에서 checkout·주문 `PAID`, workflow·location command `CLAIMED`, 결제 `CAPTURED`(USD 15.06), 원격 reservation `CLAIMED` 1건, SKU별 `available 1 / reserved 0 / claimed 1`, settlement entry 2건이었다.
- 관찰: 첫 정상 경로 실행의 승인 callback 응답 본문은 `PAYMENT_UNKNOWN`이었지만, 같은 초에 checkout이 `PAID`로 기록됐다.
- 원인 분석(가설 → 검증 → 결과): 가설은 "기본 capture 응답에 amount가 없어 ATLAS의 금액 대조가 실패한다"였다. 같은 조건의 Sandbox AUTHORIZE 주문 2건(각 USD 1.00)을 승인·authorize한 뒤 `Prefer` 헤더 유무만 바꿔 capture했다. 두 건 모두 HTTP 201·`COMPLETED`였지만, 헤더가 없으면 응답 key가 `id/links/status`뿐이었고 `Prefer: return=representation`이면 `amount`를 포함했다. 따라서 저장된 금액·통화와 일치할 때만 반영하는 규칙 때문에 완료된 매입이 `UNKNOWN`으로 분류된 것이 확인됐다. 이후 수렴은 같은 attempt의 재대조(주문 조회)로 이뤄진 것으로 보이나 callback 중복 여부는 분리하지 않았다.
- 조치와 재측정: PayPal POST에 `Prefer: return=representation`을 추가하고, 테스트 PayPal stub도 헤더가 없으면 amount 없는 응답을 돌려주도록 바꿨다. 같은 Sandbox 절차로 다시 실행했을 때 승인 callback 응답이 곧바로 `PAID`·`CAPTURED`였다(1회).
- 환불·원격 재입고: 같은 절차로 결제한 주문에서 Seller가 SKU 1개(10,000원)를 HTTP로 환불했다. PayPal 환불 `COMPLETED`, 주문 환불 `PARTIALLY_RECORDED`, 재입고 의도 `COMPLETED`(1회 시도), 원격 restock ledger 1건·수량 1이었다. 환불한 SKU는 `available 2 / reserved 0 / claimed 0`, 환불하지 않은 SKU는 `available 1 / claimed 1`로 남았다(1회).
- 한계: 실제 구매자 PayPal 계정 승인, webhook 수신(공개 HTTPS URL 필요), 전체 환불·반품 검수 경로, 매입 거절 응답 형태는 확인하지 않았다. 각 경로는 1회 실행이다. Sandbox 결과는 운영 PayPal의 지연·실패율을 대표하지 않는다. PayPal 게스트 화면의 버튼 구성이 실행마다 달라 Playwright 승인 스크립트는 후보 버튼을 순서대로 찾았다. 실행·승인·환불 스크립트(`run-sandbox.sh`, `refund-sandbox.sh`, `stop-sandbox.sh`, Playwright 승인 helper)는 Git에서 제외된 `.local/distributed-fulfillment/` 아래의 일회성 로컬 도구였고 저장소에 보존하지 않으므로 이 절차는 저장소만으로 재현할 수 없다. 자격 증명은 Git에서 제외된 `.env.local`에 뒀다.

## 참고

- Flipkart Tech Blog, [When Good Locks Go Bad: Diagnosing a System Meltdown Under Load](https://blog.flipkart.tech/when-good-locks-go-bad-diagnosing-a-system-meltdown-under-load-0b168c363143) — queueing fallacy와 fail-fast under load 참고.
- Alibaba Cloud, [How to Process Hot Spot Data in E-commerce Scenarios](https://www.alibabacloud.com/blog/how-to-process-hot-spot-data-in-e-commerce-scenarios_596491) — hot-spot inventory workload를 평균 입력과 분리.
- Apache Seata, [TCC Mode](https://seata.apache.org/docs/user/mode/tcc/) — retryable branch action과 compensation 의미 비교; framework 미도입.
