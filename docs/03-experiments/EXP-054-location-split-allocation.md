# EXP-054: 재고가 여러 location에 흩어졌을 때 단일 출고와 분할 출고의 수락 주문 수

- 질문: 전체 재고는 남아 있는데 한 location이 주문의 모든 SKU를 갖고 있지 않으면 주문이 실패하는가? 분할 출고를 허용하면 수락 주문 수와 분할 주문 수는 어떻게 바뀌는가?
- 데이터: Seller 1명, location 3곳(우선순위 W1 → W2 → W3). W1은 A 40·B 0, W2는 A 0·B 35, W3는 A 30·B 30(합계 A 70·B 65). 모든 주문은 A 1개와 B 1개이며 70건을 순서대로 checkout한다.
- 이론 상한: 단일 location만 허용하면 두 SKU를 모두 가진 W3로만 처리할 수 있어 30건이다. 분할을 허용하면 B 총량 65가 상한이다. 출고 용량·배송 제약은 없다는 가정이다.
- 실행 경로: `SplitAllocationCheckoutIntegrationTest`가 실제 cart → checkout → 주문 소유 예약 workflow 경로로 주문을 만든다. 주문 DB는 Testcontainers PostgreSQL 18.6이다. 원격 location 3곳은 원격 예약·취소로만 재고를 바꾸는 in-memory fake이고, 재고가 음수가 되면 초과 판매로 센다. 정책 순수 로직은 `LocationAllocationPlannerTest`에서 같은 데이터로 따로 검증했다.
- 반복: 결정적 입력이라 1회 실행으로 결과가 고정된다. 처리량·지연 측정이 아니다.

## 먼저 발견한 결함

원격 재고 모드에서 checkout의 location 선택은 로컬 `inventory_pool`의 판매 가능 수량을 근거로 삼았다. 그런데 원격 모드는 원격 예약만 재고를 차감하므로 로컬 pool은 판매량을 반영하지 않는다(`CartCheckoutServiceImpl`·`CheckoutPaymentServiceImpl`에서 원격 주문은 로컬 `reserveAll`·`claimAll`을 호출하지 않음을 확인). 따라서 원격 location이 매진된 뒤에도 같은 location으로 계속 routing하고, 원격에서 `INSUFFICIENT_STOCK`으로 실패한다.

조치: checkout transaction 밖에서 Seller의 활성 location별 원격 판매 가능 수량을 조회한다(`GET /availability/{sellerId}/{skuId}`). 등록되지 않은 SKU는 0을 반환하고, 알려지지 않은 location·일시 장애는 계획에서 제외한다. 그 snapshot으로 location을 고르되 snapshot과 실제 예약 사이의 경쟁은 원격 예약이 최종 판단한다. 재고가 그 사이 소진되면 checkout은 실패한다.

## 결과

| 정책 | 수락 | 거절 | 분할 주문 | W3 잔여(A+B) | 초과 판매 |
|---|---:|---:|---:|---:|---:|
| 단일 location만 | 30 | 40 | 0 | 0 | 0 |
| 단일 우선, 불가하면 분할 | 65 | 5 | 35 | 0 | 0 |

- 단일 정책은 W3가 소진된 뒤 남은 재고(W1 A 40, W2 B 35)가 있어도 40건을 거절했다. 거절은 원격 예약 실패가 아니라 checkout 단계의 계획 불가(409)로 끝났다.
- 분할 정책은 이론 상한 65건에 도달했다. 단일 location을 먼저 쓰므로 처음 30건은 W3에서 단일 출고되고, 이후 35건은 W1(A)·W2(B)로 나뉘었다. 수락 주문 65건 중 35건이 출고 2건을 만든다.

## 해석과 한계

- 분할 출고는 수락 주문을 늘리지만 배송 건수·배송비와 부분 실패 범위(한 location만 예약 성공)를 함께 늘린다. 부분 실패는 기존 workflow 보상으로 정리된다([ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md)).
- 단일 우선 정책은 두 SKU를 모두 가진 W3를 먼저 소진한다. 그 결과 이후 주문은 모두 분할된다. 분할 주문 수를 줄이려면 "분할이 불가피한 SKU 조합을 위해 공용 location을 아껴 두는" 정책이 가능하지만, 주문 도착 순서를 알아야 하므로 이번 범위에서 비교하지 않았다.
- 배송비·배송 기한·location별 출고 용량은 입력에 없다. 실제 정책 선택에는 이 비용 데이터가 필요하다.
- 원격 재고 조회는 checkout마다 location 수 × SKU 수만큼 HTTP 호출을 추가한다. 이번 실험은 그 지연을 측정하지 않았다.
- 분할 허용 여부는 `ATLAS_ORDER_INVENTORY_RESERVATION_SPLIT_ALLOCATION_ENABLED`(기본 `false`)로 정한다. 분할 주문의 `fulfillmentLocationId`는 비어 있고 location별 내역은 예약 workflow에 있다. 분할 주문은 출고마다 location을 지정해야 하고, location에 할당된 SKU 수량을 넘겨 출고할 수 없다(V71, `splitOrderShipsPerLocationWithinEachAllocation`). 지정 누락·할당되지 않은 location·초과 수량은 거절되고, W1·W2에서 각각 출고하면 주문 출고 상태가 `SHIPPED`가 된다.
