# inventory-module

Inventory pool/reservation과 `available + reserved + claimed = initial capacity` 보존 규칙을 소유.

- `.service.InventoryService`: 공개 contract; `.service.impl.InventoryServiceImpl`: validation·reserve/claim/release와 transaction
- `.dto.request`: pool/reserve inputs; `.dto.response`: `InventoryResponse`, `ReservationResponse`; `.persistence.state.ReservationStatus`: shared lifecycle state; `.exception.InventoryException`: typed failure codes
- `.persistence.entity`: `Pool`, `PoolId`, `Reservation`; `.persistence.repository`: Spring Data CRUD
- `.persistence.repository.JdbcStockRepository`: order lock과 guarded atomic stock update만 담당.
- HTTP adapter 없음; 다른 도메인 의존 없이 Inventory 소유 테이블만 변경.
