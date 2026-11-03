# ATLAS-inventory

- Gradle project: `:inventory-module`

Inventory pool/reservation과 `available + reserved + claimed = initial capacity` 보존 규칙을 소유.

- Pool identity는 `(sellerId, skuId)`. 해당 Seller-SKU를 참조하는 Campaign은 하나의 shared pool 사용.
- `.service.InventoryService`: 공개 contract; `.service.impl.InventoryServiceImpl`: validation·reserve/claim/release와 transaction
- `.dto.request`: pool/reserve inputs; `.dto.response`: `InventoryResponse`, `ReservationResponse`; `.persistence.state.ReservationStatus`: shared lifecycle state; `.exception.InventoryException`: typed failure codes
- `.repository`: `PoolRepository`·`ReservationRepository` for JPA CRUD; `JdbcStockRepository` for PostgreSQL locks/guarded updates. All repositories remain module-private.
- HTTP adapter 없음; 다른 도메인 의존 없이 Inventory 소유 테이블만 변경.
