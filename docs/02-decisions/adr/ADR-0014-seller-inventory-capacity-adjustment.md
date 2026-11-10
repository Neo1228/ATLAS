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
- Advisory lock은 seller/SKU/idempotency key로 고정하고 location UUID에 의존하지 않습니다.
  DEFAULT 위치 생성 전후에도 같은 요청은 같은 잠금을 사용합니다.
- audit unique key는 seller/location/SKU/idempotency key로 유지합니다.
  다른 위치에 같은 key를 재사용해도 결과는 공유하지 않고 잠금만 직렬화합니다.
- PostgreSQL advisory lock과 unique constraint로 동시 동일 key 처리를 보호합니다.
- 검증: replay, key 재사용 conflict, available 초과 감소, overflow rollback, 동시 동일 key,
  재고 보존식 및 default/location 경로의 stable lock-key test.

## 관련 문서

- [결정 로그 D-069](../../00-governance/decision-log.md)
- [요구사항 FR-009](../../01-requirements/requirements-index.md)
- [재고 조정 Issue #126](https://github.com/Neo1228/ATLAS/issues/126)
