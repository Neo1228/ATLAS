# ADR-0061 — 반품 재입고 출처를 실제 출고 shipment에 결속한다

- 상태: Accepted — 구현·통합 검증 완료
- 날짜: 2026-11-22
- 결정 ID: [D-124](../../00-governance/decision-log.md#d-124)
- 관련 결정: [ADR-0013 배송 전 환불](ADR-0013-pre-shipment-refund-recording.md), [ADR-0060 분할 출고](ADR-0060-order-inventory-reservation-workflow.md)

## 문제

한 `OrderItem`의 수량이 여러 shipment와 fulfillment location으로 나뉘면, 기존 반품 요청의 `(orderItemId, quantity)`만으로는 반품된 물리 수량의 출처를 결정할 수 없다. 환불 수량을 workflow의 location 순서대로 임의 분배하면 실제 출고 location이 아닌 다른 location에 재입고를 시도한다. 같은 SKU가 여러 location에 할당된 주문에서는 해당 location의 remote reservation이 충분하지 않아 재입고가 실패하거나 잘못된 재고가 늘어날 수 있다.

## 결정

1. 각 `order_return_item`은 nullable `shipment_id`로 출처 shipment를 보존한다(V72). HTTP return item은 선택 필드 `shipmentId`를 받고, 응답에도 확정된 shipment ID를 돌려준다.
2. 반품 요청은 주문 소유권과 delivered 상태 외에 shipment의 주문 소속, `OrderItem` 포함 여부, shipment별 누적 반환량을 확인한다. 해당 shipment에 출고된 수량에서 이미 요청·반품·환불된 수량을 뺀 잔여량을 넘으면 conflict다.
3. `shipmentId`를 생략한 기존 요청은 적격 delivered shipment가 정확히 하나일 때만 그 출처를 자동 확정한다. 여러 shipment가 같은 요청량을 공급할 수 있으면 conflict를 반환한다. Buyer는 같은 `OrderItem`의 다른 shipment 수량을 별도 return 요청으로 제출한다. 동일 idempotency key의 source ID 생략 replay는 최초 확정 출처와 일치하는 재시도로 취급한다. 명시한 ID가 기존 출처와 다르면 facts 재사용 conflict다.
4. 확인된 반품 환불은 `refund_id`로 return item과 source shipment를 읽고, 해당 shipment의 `fulfillment_location_id`·SKU·수량에 맞춰 workflow의 원격 reservation에 재입고 의도를 기록한다. 매입 환불이 확정된 owner-application transaction에 의도와 settlement posting을 기록하고, 원격 inventory 호출은 commit 뒤 동일 restock idempotency key로 수행한다.
5. 배송 전 직접 환불은 물리 출고가 없으므로 기존 workflow allocation 분배를 유지한다. local inventory 경로도 변경하지 않는다.
6. V72 이전 return row는 `shipment_id`가 NULL이다. 단일-location workflow에서만 source location을 유일하게 복원할 수 있다. split-location workflow에서 출처가 없는 기록은 location을 추정하지 않고 적용을 거절해 recovery/operator 검토 대상으로 남긴다.

## 대안과 절충

| 대안 | 결정 |
|---|---|
| workflow location 순서대로 환불 수량을 greedily 분배 | 거절. 실제 배송 위치와 다른 재고를 늘릴 수 있다. |
| 모든 클라이언트에 shipment ID를 필수로 요구 | 거절. single-shipment 기존 요청의 하위 호환을 불필요하게 깨뜨린다. |
| ID 생략 시 shipment를 임의 선택 | 거절. 여러 출처가 가능하면 정확성을 증명할 수 없다. |

## 검증과 한계

- `OrderShipmentIntegrationTest`는 HTTP 경로에서 ambiguous source 거절, 명시 source 성공, 동일 shipment 누적 반환 한도, 다른 shipment 반환을 검증한다.
- `RemoteReservationCheckoutIntegrationTest`는 검수 후 환불과 실제 원격 restock 요청을 검증한다. `LocationRestockServiceImplTest`는 여러 location의 item을 지정한 reservation에 나눠 기록하고 location별 할당량 초과를 거절한다.
- 과거 split-location return row의 출처는 migration으로 복원할 수 없다. 해당 기록은 수량과 location을 추측해 자동 수정하지 않는다.
