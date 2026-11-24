# ADR-0060: 주문 소유 재고 예약 workflow를 영속화해 원격 location 예약을 복구한다

- 상태: Accepted — independent location service의 첫 order-side recovery layer
- 날짜: 2026-11-20
- 관련 결정: [ADR-0041](ADR-0041-bounded-admission-expiry-sweep.md)(bounded `SKIP LOCKED` sweep), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md)(외부 결과와 owner 적용 분리), [ADR-0058](ADR-0058-distributed-fulfillment-stage-one-baseline.md)(단일 저장소 기준선), [ADR-0059](ADR-0059-independent-inventory-location-service.md)(location별 독립 inventory runtime), [ADR-0063](ADR-0063-order-inventory-write-batching.md)(lease-fenced submit write batching), [ADR-0064](ADR-0064-order-inventory-just-in-time-lease-claims.md)(recovery just-in-time claim)

## 문제

주문이 두 fulfillment location에 재고를 예약할 때 한 location만 성공할 수 있고, 성공 commit의 응답이 유실되거나 다른 location 응답을 기다리는 사이 먼저 잡은 예약이 만료될 수 있다. 이 상태를 메모리만으로 추적하면 coordinator 종료 후 부분 예약을 찾거나 안전하게 정리할 수 없다. 재고가 없는 주문을 결제·출고 대상으로 진행하지 않도록, 재시도와 취소 이후에도 workflow가 복구 가능한 상태여야 한다.

## 가정과 성공 기준

- 합성 목표 입력은 1,000 order/s, order당 평균 3 SKU line, order당 최대 2개 location call이다. 이는 용량 가정이지 측정된 운영 트래픽이 아니다. 최대 fan-out에서는 location service에 초당 2,000 RPC가 필요하다.
- location reservation TTL의 초기 로컬 기본값은 2분이다. coordinator의 `minimum-reservation-remaining` 기본값은 10초이며, 기본 read timeout 2초의 5배를 남기는 초기 안전 여유다. 이 값은 clock skew·호출 latency·후속 payment 동작을 모두 증명하지 않으며 workload 결과에 따라 조정할 입력이다.
- 성공은 모든 location 예약이 TTL 여유를 갖고 존재할 때만 `RESERVED`로 표시하는 것이다. 부분 실패·응답 유실·프로세스 재시작 후 같은 idempotency key로 복구하거나 모든 예약을 보상한다. 결과를 알 수 없는 작업은 성공으로 추정하지 않는다.
- Inventory service의 처리량 측정은 [ADR-0059](ADR-0059-independent-inventory-location-service.md)와 [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md)에 있다. Coordinator의 동기 service-level submit은 [EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md)에서 concurrency 1–32로 측정했으며 최대 관측값은 528.99 workflow/s다. Public HTTP ingress, production inventory DB/network, 실제 multi-process scale-out과 운영 p99는 측정되지 않았다.

## 결정

1. `atlas-order`가 주문별 workflow, location별 명령, SKU allocation을 같은 PostgreSQL에 기록한다. workflow와 모든 location 명령은 remote HTTP 호출보다 먼저 한 transaction에서 생성한다. V62는 intent·상태·lease를, V63은 원격 reservation의 `remote_expires_at`을 저장하며, V64는 런타임 테이블명을 `order_inventory_*`로 정리한다. 이미 적용된 V62/V63은 Flyway history와 checksum 보존을 위해 수정하지 않고 V64에서 이름을 바꾼다. 이 번호는 main 병합 뒤 재번호한 값이며, 병합 전 branch 문서·이력에는 V50~V52로 적혀 있을 수 있다.
2. 각 location의 remote command는 `workflowId:fulfillmentLocationId` idempotency key를 사용한다. timeout은 결과 미확정이므로 `RETRY_PENDING`으로 두고 동일 key로 재호출한다. provider 호출은 DB transaction 밖에서 실행한다.
3. coordinator는 location UUID 순서로 순차 예약한다. location 중 하나가 재고 부족으로 확정 실패하면 이미 성공했거나 결과가 불확정인 나머지 location에 같은 key의 cancel을 보내고 `COMPENSATING`으로 전이한다. Inventory service의 cancel tombstone은 보상 명령이 늦은 reserve보다 먼저 도착하는 경우도 막는다.
4. 원격 reservation 만료 시각을 영속화하고 location별 `expiresAt`을 API 응답에 노출한다. 저장된 TTL이 10초 이하로 남았거나 성공 직후 이미 그 조건을 만족하면 `RESERVED`로 승격하지 않고 취소 보상을 수행한다. 다음 location 응답을 기다린 뒤에도 모든 예약의 잔여 TTL을 다시 확인한다. 만료/취소된 예약의 동일 key replay는 확정 실패로 처리한다.
5. recovery batch size는 poll당 work upper bound이며 lease를 batch 전체에 미리 주지 않는다. worker는 poll 시작 시 due cutoff을 고정하고 `FOR UPDATE SKIP LOCKED`로 workflow 한 건을 atomic claim한 직후 처리한다. 이 poll 중 새로 예약된 retry는 다음 poll에서만 다시 선택한다. Remote 호출은 transaction 밖에서 수행하고, 동일 token의 아직 유효한 lease만 half-life threshold에서 연장한다. 만료 token은 되살리지 않는다. 설정 시작 시 half lease가 connect timeout + read timeout + 1초 safety margin보다 긴지 확인한다. exponential backoff+jitter와 최대 8회 후 `NEEDS_REVIEW` 전이는 유지한다.
6. `/api/internal/order-inventory/**`는 별도 service token으로 보호하고 feature flag는 기본 비활성화한다. Compose 정의는 `load-tests/compose-order-coordinator.yaml`로 추적하고, service token을 담는 env 파일만 git-ignore된 `.local/`에 둔다. coordinator DB는 기존 root PostgreSQL volume과 분리하고, 기존 account module의 SMTP bean 구성을 위해 loopback-only Mailpit sink를 띄운다. Mailpit은 이 workflow의 업무 의존성이 아니다.
7. 메시지 broker와 Saga framework는 지금 추가하지 않는다. 현재 단일 coordinator의 durable state table과 bounded worker로 commit 후 응답 유실·부분 실패·재시작을 증명할 수 있다. 독립 소비자 fan-out, event replay, 처리량 측정이 필요해질 때 broker를 비교한다. 원래 계획 6단계의 질문("동기 호출의 지연·장애 전파를 비동기로 개선할 필요가 있는가")에 대해, EXP-053 분해 측정에서 원격 location을 즉시 응답 stub으로 바꿔도 처리율이 같게 포화했고 지배 비용은 order당 약 7.8개의 개별 DB transaction이었다. 즉 현재 병목은 동기 원격 호출이 아니라 주문 DB 쓰기이며, broker를 넣어도 같은 쓰기가 consumer 쪽으로 옮겨질 뿐이다. 복구 경로(예약 workflow, checkout 확정, 환불 재입고, payment recovery)는 모두 DB 상태를 bounded batch로 polling해 재시작·중복 처리를 이미 견딘다. broker는 서로 다른 소비자가 같은 사건을 독립적으로 받아야 하는 요구(예: 배송·알림·정산 분리)나 polling 비용이 측정으로 확인될 때 다시 비교한다.
8. Buyer cart checkout은 feature flag가 켜졌을 때만 이 workflow에 연결한다. flag가 꺼진 기본 경로는 기존처럼 `InventoryService.reserveAll`을 checkout transaction 안에서 호출한다. flag가 켜지면 checkout transaction은 cart lock, SellerOrder·item snapshot, SellerOrder별 workflow intent(`stage`, 원격 호출 없음), `purchase_order.reservation_workflow_id`(V66)를 한 번에 commit하고 checkout·주문을 `RESERVATION_PENDING`으로 둔다. commit 후 `CheckoutFinalizationService`가 transaction 밖에서 workflow를 진행하고, 모든 location이 잔여 TTL을 가진 `RESERVED`일 때만 checkout을 `RESERVED`로 승격하며 cart는 staging한 snapshot과 같을 때만 비운다. 한 SellerOrder라도 확정 실패하면 형제 workflow를 취소하고, 모두 해제된 뒤에만 checkout을 `FAILED`로 둔다. 결과가 불확실하면 `RESERVATION_PENDING`을 유지하고 recovery worker가 `recoverDue` 뒤 `finalizeDue`로 다시 확인한다.
   - 원격 예약 checkout의 결제는 **승인 → 원격 claim → 매입** 순서다(V69 `CLAIMED`). `submitCheckoutAuthorization`/`reconcileCheckoutAuthorization`은 PayPal 승인에서 멈추고, 모든 workflow가 `CLAIMED`일 때만 기존 `reconcileCheckout`으로 매입한다. claim이 `RESERVATION_EXPIRED` 등으로 확정 거절되면 매입 대신 승인을 void하고 남은 예약을 취소해 checkout을 `FAILED`로 끝낸다. claim 응답이 불확실하면 승인을 유지한 `PAYMENT_PENDING`으로 두고 payment recovery가 같은 reservation ID로 다시 claim한다(원격 claim은 멱등). 매입 결과가 불확실하면 claim한 재고를 되돌리지 않는다. claim 뒤 취소되면 원격 `restore`(CLAIMED→RELEASED)로 판매 가능 수량을 되돌린다. PayPal POST에는 `Prefer: return=representation`을 보낸다. 기본(minimal) capture 응답에는 `amount`가 없어 완료된 매입도 금액 대조 실패로 `UNKNOWN`이 됐다(Sandbox에서 헤더 유무만 바꿔 확인, [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md)).
   - checkout location 선택은 transaction 밖에서 조회한 **원격 location의 판매 가능 수량**을 근거로 한다. 원격 모드는 원격 예약만 재고를 차감해 로컬 `inventory_pool`이 판매량을 반영하지 못하므로, 로컬 값으로 고르면 매진된 location으로 계속 routing된다([EXP-054](../../03-experiments/EXP-054-location-split-allocation.md)). 우선순위 순으로 모든 line을 처리할 수 있는 단일 location을 먼저 고르고, 없으면 `split-allocation-enabled`일 때만 SKU 수량을 여러 location에 나눈다(기본 비활성). 분할 주문은 `fulfillmentLocationId`를 비우고 location별 내역을 workflow에 남긴다.
9. 이름과 package는 모듈 소유와 기존 계층을 따른다. `atlas-application`은 `HttpLocationClient`와 내부 HTTP·보안 adapter를, `atlas-order`는 `LocationClient`·coordinator·workflow repository를 소유한다. 원격 실패 분류 `LocationClientException`은 기존 `OrderException`과 같은 `com.atlas.order.exception`에, checkout 확정을 맡는 `CheckoutFinalizationService`는 coordinator와 같은 `com.atlas.order.service`에 둔다. `atlas-inventory-location`은 `com.atlas.inventorylocation` 아래 `controller/service/dto/repository/exception/security` package를 사용하고, `InventoryLocationLayerBoundaryTest`가 의존 방향을 검증한다. 모듈을 가로지르는 범용 service 계층은 추가하지 않는다.
10. 취소는 실패 보상과 다른 상태로 기록한다. `cancel(workflowId)`는 V67에서 추가한 `CANCEL_PENDING`으로 먼저 전이하고(lease 초기화로 진행 중인 reserve worker를 fencing), 모든 location을 같은 idempotency key로 cancel한 뒤 `CANCELLED`로 끝낸다. 원격 결과가 불확실하면 `CANCEL_PENDING`으로 남아 recovery worker의 backoff로 재시도되고, 반복 `cancel` 호출은 원격 호출을 추가하지 않는다. `FAILED`는 시스템이 보상한 실패, `CANCELLED`는 요청된 취소라는 의미 차이를 상태로 남겨 조회·정산에서 구분한다.
11. SKU 식별자는 `seller_sku` 기본키와 같은 `(seller_id, sku_id)`로 다룬다. 서로 다른 Seller가 같은 SKU 문자열을 쓸 수 있으므로 inventory-location V3와 order V65에서 재고·예약 item·workflow item 키에 `seller_id`를 추가했다. 기존 row는 실제 Seller로 추측 배정하지 않고 legacy sentinel `00000000-0000-0000-0000-000000000000`으로 보존한다.
12. 원격 재고로 판매한 주문의 환불 재입고는 환불 확정 transaction에서 `order_inventory_restock`(V70)에 의도만 저장하고, commit 뒤와 recovery worker에서 원격 `POST /reservations/{id}/restock`을 `refund-restock:{refundId}` key로 호출한다. 원격 서비스는 restock ledger(V4)로 key별 1회 적용과 item별 누적 수량 ≤ 판매 수량을 보장하고, 일부 재입고된 판매분의 전체 `restore`는 거절한다. 일시 실패는 backoff로 재시도하고, 확정 거절이나 최대 재시도 초과는 수량을 추정 보정하지 않고 `NEEDS_REVIEW`로 남긴다.
13. 분할 주문의 출고는 location 단위다. 출고 요청에 `fulfillmentLocationId`(V71)를 받아 workflow의 해당 location 할당 SKU 수량 안에서만 허용한다. 단일 location 주문은 지정하지 않아도 되며, 지정하면 할당 location과 같아야 한다. 출고 위치가 할당과 어긋나면 원격 재고와 실제 출고 위치가 달라지고 환불 재입고 위치도 틀어지기 때문이다.
14. 예약 command 복구, checkout 확정, 환불 재입고는 처리 지연과 실패 원인이 다르므로 별도 bounded worker로 분리한다. 한 scheduled method에서 순차 실행하면 느린 원격 command batch가 checkout 확정과 환불 재입고를 밀어낸다. 세 worker는 전용 3-thread scheduler를 공유하되 각자 fixed-delay poll, batch 1–1000, 개별 cadence·batch override, 단계별 poll count·duration·failure metric을 가진다. 기존 PayPal webhook retry도 default expiry/auth scheduler와 분리해 1-thread pool로 실행한다. scheduler는 종료 시 in-flight 작업을 최대 10초 기다린다. 한 단계의 예외는 다른 단계의 실행을 막지 않고, 같은 단계는 local fixed-delay로 겹치지 않으며 여러 replica의 중복 claim은 각 durable repository의 DB lease/`SKIP LOCKED`가 방지한다. 한 worker가 batch를 오래 처리하면 같은 queue의 다음 poll은 늦어지므로 batch·lease·due age를 관측한다.

## 대안과 trade-off

| 대안 | 장점 | 제외·선택 근거 |
|---|---|---|
| controller 안에서 순차 HTTP 호출만 수행 | 코드가 작고 초기 성공 경로가 빠름 | 프로세스 종료 시 진행 intent가 없어 commit 뒤 응답 유실을 복구하지 못하므로 제외 |
| 메모리 retry queue | DB 접근 없이 재시도 가능 | 재시작과 배포에서 queue가 사라져 safety·liveness를 증명하지 못하므로 제외 |
| Kafka/outbox를 즉시 도입 | 독립 consumer와 event replay 확장에 유리 | 지금은 단일 coordinator recovery에 필요한 consumer 경계가 없고 운영 요소만 늘어난다. DB workflow로 먼저 실패를 재현하고 처리량을 측정한 뒤 재검토 |
| Saga framework/TCC 라이브러리 | 표준화된 branch transaction abstraction | 현재 reservation·cancel 상태와 idempotency 계약은 직접 표현 가능하다. Seata TCC의 empty-compensation·suspension 교훈은 적용하되 framework 비용은 보류 |
| 예약 성공을 곧바로 완료 처리 | 경로·상태 수가 적음 | 다음 location 응답 지연 중 TTL이 끝난 reservation을 완료로 오인할 수 있어 expiry 저장과 최종 TTL 검사를 선택 |
| checkout transaction 안에서 `submit`으로 원격 예약까지 수행 | 호출 경로가 하나라 단순 | 원격 응답을 기다리는 동안 DB connection·cart lock을 잡고, 원격 성공 뒤 local rollback이면 소유 주문 없는 원격 예약이 남는다. intent만 같은 transaction에서 `stage`하고 원격 호출은 commit 뒤로 미룸 |
| 취소도 `COMPENSATING`/`FAILED`로 표현 | 상태 수가 적음 | 요청된 취소와 시스템 실패를 조회·정산에서 구분할 수 없고, 재시도 중 상태가 실패 보상 경로로 섞인다. `CANCEL_PENDING`/`CANCELLED`를 분리 |
| 매입 후 원격 claim, 실패 시 환불 | 기존 local 흐름(매입 후 claim)과 같은 순서 | 재고가 만료된 경우 고객에게 실제 청구 후 환불이 발생하고 환불 실패라는 새 실패 경로가 생긴다. PayPal AUTHORIZE intent가 이미 승인·매입을 분리하므로 승인 뒤 claim, claim 성공 시 매입을 선택 |
| 환불 transaction 안에서 원격 restock 호출 | 환불 상태와 재고가 한 번에 맞춰지는 것처럼 보임 | 원격 지연 동안 DB transaction을 잡고, 원격 성공 후 local rollback이면 환불 없이 재고만 늘어난다. 의도를 같은 transaction에 저장하고 원격 호출은 commit 뒤로 분리 |

## 실패 시나리오와 검증 결과

- `LocationReservationServiceIntegrationTest` 15건 통과(2026-11-23 no-cache 실행): 부분 실패 보상, 취소와 반복 취소, 취소 결과가 불확실할 때의 `CANCEL_PENDING` 유지, claim 응답 유실 뒤 취소(restore), admission gate 거절 시 workflow 미생성, 운영자 release, TTL 여유 부족 보상, coordinator 재생성 뒤 lost-response 복구, service-token 요구. Lease 회귀는 active lease 선점 방지, 후속 workflow just-in-time claim, poll 중 새 retry 재실행 방지, fan-out 중 lease renewal, expired token 부활 거부, cancellation 중 buffered 결과 전체 rollback을 검증한다.
- `RemoteReservationCheckoutIntegrationTest` 15건 통과(2026-11-23 no-cache 실행, flag on, 원격 location과 PayPal은 MockWebServer): 예약 승격, 만료 취소, 불확실 결과 recovery, 대기 중 취소, 원격 거절 시 쿠폰 해제, 위치 조회 실패(재시도 가능)와 재고 0(409)의 구분, PayPal 승인 → claim → capture 순서, 만료 시 승인 void, 불확실한 claim·capture의 재고 보호, claim된 workflow 취소 시 restore, restock 늦은 worker 덮어쓰기 방지, 불확실한 restock 재시도, 반품의 source shipment restock을 검증한다. 이 ADR 초기 본문에는 19건으로 적었으나 테스트 정리 뒤의 현재 값은 15건이다.
- `InventoryReservationIntegrationTest`(2026-11-23 no-cache 실행 13건)에 restore 검증을 추가했다. CLAIMED만 RELEASED로 되돌리고 replay는 멱등이며, RESERVED 예약은 restore를 거절하고, `available + reserved + claimed = initial`이 유지된다.
- Local Toxiproxy/process-kill run: location A의 remote commit 뒤 response를 끊었을 때 coordinator는 `RETRY_PENDING`으로 응답했고 stock은 `available 0 / reserved 1 / claimed 0`이었다. Proxy를 복구한 뒤 coordinator JVM을 강제 종료·재기동하자 persisted workflow가 `RESERVED`에 도달했으며, kill 직전부터 수렴까지 10초(벽시계 1초 해상도), app healthy 이후 추가 대기는 0초로 관측됐다. 최종 stock은 동일하게 `0/1/0`; 단일 로컬 실행이며 운영 SLO가 아니다.
- `minimum-reservation-remaining=10s`는 2초 read timeout의 5배라는 정책상 초기 여유이며 실측된 clock skew 또는 payment 완료 시간의 상한이 아니다. 실제 Buyer checkout과 연결할 때는 TTL 내 claim/renewal 정책과 payment 지연 분포를 측정해야 한다.
- Local 2-location profile에서 250 order/s × 30초는 7,356건 완료(244.9/s), p95 865 ms·p99 1.14 s였고 전 workflow가 RESERVED로 수렴했으며 nonterminal 0이었다.
- 같은 환경의 1,000 order/s × 30초는 11,673건 완료(360.9/s), p99 3.39 s, client dropped iteration 18,238건이었다. 안전성은 유지됐지만 로컬 조건의 1,000 order/s 처리량은 달성하지 못했고 k6가 1,000 VU 상한에 도달해 server 한계와 client 생성기 한계를 아직 분리하지 못했다.
- 위 두 profile은 [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md)에 각 1회 탐색 실행으로 기록했으며 production SLO가 아니다.
- `EXP-053`의 역사적 1,000 order/s 분해는 token 없는 route 999.96/s·drop 0(p99 4.51 ms), 1 location·즉시 응답 stub 311.7/s·343.9/s, Hikari 60(+25.5%), maxVUs 4,000(+7.1%), snapshot N+1 단일 JOIN화(+9.0%)를 기록했다. 당시 최선 470.9/s였고 지배 비용은 order당 약 7.8개의 DB transaction이었다. Remote 호출은 transaction 밖에 둔 채 [ADR-0063](ADR-0063-order-inventory-write-batching.md)의 lease-fenced local write batching을 적용했다. [EXP-058](../../03-experiments/EXP-058-order-reservation-submit-capacity.md)의 Testcontainers/MockWebServer service probe에서 concurrency 32의 최고 관측값은 528.99/s, p95 95.49 ms, p99 109.212 ms다. 합성 1,000 order/s 목표는 여전히 미달이며 해당 probe는 운영 capacity를 증명하지 않는다.
- recovery worker 기본 batch 100/1초는 poll이 cadence 안에 끝난다는 가정에서 instance당 최대 100 workflow scan/claim/s 이론 상한이다. [EXP-056](../../03-experiments/EXP-056-order-inventory-recovery-capacity.md)의 안전한 just-in-time claim matrix는 same JVM에서 최고 poller object 32개·batch50의 775.00 workflow/s(1.290초, remote request 2,000건, 모두 `RESERVED`)를 기록했다. 합성 1,000/s에는 미달했으며 fixed-delay scheduler·별도 process/replica는 측정하지 않았다. Worker 기본값은 바꾸지 않았다.
- compensation 실패는 `COMPENSATION_REQUIRED`로 재시도되고 최대 재시도 후 `NEEDS_REVIEW`로 남는다. 이미 출고되거나 결제된 주문의 물리적 보상은 이 단계 범위 밖이다.

## main 통합 뒤 감사와 통합 테스트로 찾아 고친 결함

main 18커밋(#421~#443)과 통합한 뒤 읽기 전용 감사 3건과 통합 테스트로 아래를 확인했다. 결함마다 먼저 실패하는 회귀 테스트로 재현한 다음 고쳤다.

| 증상 | 원인 | 수정 | 재현 테스트(수정 전 결과) |
| --- | --- | --- | --- |
| 원격 claim 응답이 유실된 뒤 취소하면 `NEEDS_REVIEW`로 끝난다 | 로컬 위치는 `RESERVED`인데 원격은 이미 `CLAIMED`다. 원격 `cancel`은 `CLAIMED`를 `INVALID_STATE`로 거부하고 클라이언트가 PERMANENT로 분류한다 | `cancel`이 PERMANENT `INVALID_STATE`로 거부될 때만 `restore`로 전환한다. `restore`도 `CLAIMED`가 아니면 거부하므로 그 밖의 상태는 계속 `NEEDS_REVIEW`로 드러난다 | `cancelAfterLostClaimResponseRestoresRemoteClaimedStockInsteadOfNeedingReview` (`CANCELLED` 기대, `NEEDS_REVIEW` 실제) |
| 위치 재고 조회가 일시 실패해도 "재고 없음"(409)으로 응답한다 | `remoteCandidates`가 조회 실패 위치를 조용히 건너뛰어, 후보가 비면 재고 부족과 구분되지 않는다 | 일시 실패(`RETRYABLE_UNKNOWN`)가 있었던 seller의 계획이 비면 503 `INVENTORY_UNAVAILABLE`로 응답한다. 엔드포인트가 없는 위치(PERMANENT)는 원격 관리 대상이 아니므로 그대로 건너뛴다 | `unconfirmedAvailabilityIsRetryable...`, `confirmedZeroAvailabilityRemainsAConflict...` |
| 늦게 깨어난 restock worker가 더 새로운 claim의 결과를 `NEEDS_REVIEW`로 덮어쓴다 | `finish`가 `PENDING`만 확인하고 claim 소유권은 확인하지 않는다. claim은 `next_attempt_at` 시각 lease만 쓴다 | claim마다 오르는 `attempt_count`를 소유권 토큰으로 삼아 `finish`가 일치할 때만 갱신한다. 스키마 변경은 없다 | `staleRestockClaimantCannotOverwriteTheNewerClaimsOutcome` (`PENDING` 기대, `NEEDS_REVIEW` 실제) |

- 영향 판단: 세 번째 결함은 restockKey가 멱등이라 재고를 두 번 늘리지 않는다. 피해는 거짓 `NEEDS_REVIEW` 경보이므로 중간 심각도로 봤다.
- 한계: 첫 번째와 두 번째는 MockWebServer로 원격 응답을 재현했다. 실제 location 서비스 프로세스를 띄운 검증이 아니다.

## main #443 capture fence와 claim-before-capture의 결합

통합 중 충돌을 main 쪽으로 풀자 기존 테스트 3건(`unknownClaimKeepsAuthorization...`, `expiredRemoteReservationVoids...`, `unknownCaptureAfterClaim...`)이 실패했다. main의 `reconcileCheckout`은 `AUTHORIZED`에서 곧바로 capture를 이어 가므로 claim보다 capture가 앞서는 순서가 생겼다. 비취소 경로는 승인까지만 진행하는 `reconcileCheckoutAuthorization`을 유지하고, 취소 경로만 main의 `reconcileCheckoutForCancellation`(capture fence)을 쓰도록 합쳤다. 두 메서드를 통합해 하나로 만들지 않은 이유는 한쪽은 capture를 막고 다른 쪽은 claim 이후에만 capture를 허용하는 서로 다른 불변식이기 때문이다. 제 추가 메서드를 호출처가 없다고 잘못 판단해 한 번 지웠다가 이 테스트로 복원했다.

## 앞당겨진 7단계 요소와 아직 없는 증거

- 분할 할당: EXP-054가 단일 위치만 허용할 때와 분할을 허용할 때의 주문 수락 차이를 측정했다. 재고가 위치에 흩어진 3단계 상황을 재현하는 데 필요해 앞당겼다.
- 출고 위치 기준 반품 재입고([ADR-0061](ADR-0061-return-restock-source-shipment.md)): 분할 출고 뒤 반품 재고를 출고한 위치로 되돌리지 않으면 위치별 재고 보존이 깨진다. 이 정합성 때문에 앞당겼다.
- 아직 없는 것: 부분 취소와 부분 환불의 위치별 증거(7단계), 독립 결제 시스템(5단계)은 구현하지 않았다. PayPal unknown-result 복구와 claim-before-capture 테스트는 있으나 결제를 별도 시스템으로 분리한 근거는 아니다.

## 재검토 조건

- feature flag를 실제 Buyer checkout에서 활성화하기 전, PayPal authorization→remote claim→capture, payment 지연 중 reservation TTL, 사용자 취소 경쟁을 실제 inventory runtime과 고정 자원에서 end-to-end 검증한다.
- 같은 조건의 end-to-end local workload에서 coordinator와 recovery의 성공률·p95/p99·backlog drain/age를 계속 확인한다. 현재 service probe는 public ingress·실 inventory DB·다중 process를 포함하지 않으므로, 1,000 order/s를 주장하기 전 고정 자원에서 SQL/lock wait, connection wait, remote latency, poller cadence를 분리 측정한다. 처리량 목표를 못 맞췄다는 이유만으로 broker 도입이나 worker 기본값 변경을 하지 않는다.
- TTL margin이 configured downstream timeout의 5배를 확보하지 못하거나 location service와 coordinator 간 clock 차이로 오판이 관찰되면 margin/clock 계약을 재설계한다.

## 참고 자료

- Alibaba Cloud, [How to Process Hot Spot Data in E-commerce Scenarios](https://www.alibabacloud.com/blog/how-to-process-hot-spot-data-in-e-commerce-scenarios_596491) — hot-SKU contention을 평균 처리량과 분리해 검증하는 배경.
- Apache Seata, [TCC Mode](https://seata.apache.org/docs/user/mode/tcc/) — Try/Confirm/Cancel 경계의 비교 자료; framework는 도입하지 않음.
- Alibaba Cloud, [Seata TCC idempotence, empty rollback and suspension](https://www.alibabacloud.com/blog/599647) — cancel-before-reserve와 늦은 Try를 함께 다뤄야 하는 근거.
- Flipkart Tech Blog, [When Good Locks Go Bad: Diagnosing a System Meltdown Under Load](https://blog.flipkart.tech/when-good-locks-go-bad-diagnosing-a-system-meltdown-under-load-0b168c363143) — tail latency와 lock queue를 평균 처리량과 별도로 관찰하는 배경.
