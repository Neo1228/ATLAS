# ATLAS-inventory

- Gradle project: `:inventory-module`

Inventory 재고 pool과 reservation을 소유. 재고 보존식: `available + reserved + claimed = initial capacity`.

- Pool 식별자: `(sellerId, skuId)`. 같은 Seller-SKU를 참조하는 Campaign은 재고 pool을 공유.
- `.service.InventoryService`: 다른 모듈에 제공하는 계약. `.service.impl.InventoryServiceImpl`: 입력 검증·예약 상태 변경·transaction 처리.
- `.dto.request`·`.dto.response`: 모듈 간 요청/응답 형식. `InventoryPoolResponse`·`ReservationResponse` 포함.
- `.persistence.entity`: JPA entity. `.persistence.state.ReservationStatus`: reservation lifecycle 상태.
- 모듈 내부 `.repository`: `InventoryPoolRepository`·`ReservationRepository`는 JPA CRUD, `JdbcStockRepository`는 PostgreSQL lock·조건부 재고 변경 담당.
- HTTP adapter 없음. 다른 도메인 테이블을 직접 변경하지 않음.
