# ATLAS-order

- Gradle project: `:order-module`

Checkout과 Order 수명주기·payment coordination을 소유.

- Order에는 queue에서 유래한 `campaignId`와 Seller-SKU의 `sellerId`·`skuId`를 보존.
- `.service.OrderService`: 공개 contract; `.service.impl.OrderServiceImpl`: checkout, payment/cancellation 전이, expiry와 scheduled expiry
- `.dto.response.OrderResponse`: output; `.persistence.state.OrderState`: shared lifecycle state; `.exception.OrderException`: typed failure codes
- `.persistence.entity.PurchaseOrder`·`.persistence.repository.OrderRepository`: JPA mapping, CRUD, order query/lock
- `.persistence.repository.JdbcBuyerLockRepository`: buyer 단위 PostgreSQL advisory lock만 담당.
- 의존: Admission·Inventory·Payment의 `.service`, request/response DTO, 그리고 서비스 계약이 공유하는 `.persistence.state`; 다른 모듈 테이블 직접 변경 금지.
