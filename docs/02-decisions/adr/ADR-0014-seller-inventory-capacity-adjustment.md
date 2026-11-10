# ADR-0014: Seller 재고 capacity 조정

- 상태: 승인
- 날짜: 2026-11-05
- 결정: D-069
- 요구사항: FR-009

## 배경

Seller가 재고 수량을 정정할 때 `reserved`와 `claimed` 재고를 보호하고, 재시도·감사 이력을 보존해야 함.

## 결정

- `delta > 0`: `initialCapacity`와 `available`을 같은 값만큼 증가.
- `delta < 0`: `available`에서만 감소. 감소 후 `available >= 0` 유지.
- `reserved`와 `claimed`는 조정하지 않음. 보존식 `initialCapacity = available + reserved + claimed` 유지.
- 요청은 SKU, signed delta, reason, `Idempotency-Key`로 구성.
- 동일 key·동일 요청은 기존 결과 재응답. 동일 key·다른 요청은 conflict.
- pool 변경과 append-only adjustment audit를 PostgreSQL transaction 하나로 처리.

## 선택 이유와 대안

- 수량 전체 덮어쓰기는 예약·판매 완료 수량을 덮어쓸 수 있어 제외.
- 별도 event store나 event sourcing은 현재 요구에 불필요한 운영·모델 복잡도를 추가하므로 제외.
- 기존 Inventory와 PostgreSQL transaction을 재사용해 원자성·멱등성·이력을 확보.

## 결과와 한계

- 가용 재고보다 큰 감소는 거부. 예약·판매 완료 수량의 취소나 복구는 별도 업무 흐름.
- 요청자 인증·인가와 다중 창고·lot 재고는 현재 범위 밖.
- 기본 API는 `DEFAULT` sentinel을 advisory lock scope로 사용합니다.
- 위치 API는 seller-scoped `location_code`를 확인해 DEFAULT에는 같은 sentinel을,
  다른 위치에는 UUID를 사용합니다.
  DEFAULT row 생성 전후에도 잠금 identity는 같고 non-default 위치는 독립적으로 잠급니다.
- Explicit location ID가 없으면 기존 `POOL_NOT_FOUND`로 실패합니다.
  location row 생성 뒤 재시도해야 하며, lock 후 row가 생겨도 현재 요청을 계속하지 않습니다.
- audit unique key는 seller/location/SKU/idempotency key로 유지해 위치별 replay와 fact 비교를 보존합니다.
  같은 key를 다른 location에서 재사용해도 결과를 공유하지 않습니다.
- 위치 API는 location code 확인을 위해 read를 한 번 추가합니다. 위치별 잠금을 보존하면서
  DEFAULT 생성 경합을 막기 위한 의도된 비용입니다.
- PostgreSQL advisory lock과 unique constraint로 동시 동일 key 처리를 보호합니다.
- 검증: replay, key 재사용 conflict, available 초과 감소, overflow rollback, 동시 동일 key,
  재고 보존식 및 DEFAULT sentinel/non-default UUID 잠금 test.

## 관련 문서

- [결정 로그 D-069](../../00-governance/decision-log.md)
- [요구사항 FR-009](../../01-requirements/requirements-index.md)
- [재고 조정 Issue #126](https://github.com/Neo1228/ATLAS/issues/126)
