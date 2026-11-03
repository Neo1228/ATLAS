# ATLAS-campaign

- Gradle project: `:campaign-module`

Seller, Seller-owned SKU와 Campaign eligibility를 소유. Seller와 SKU는 별도 domain module이 아니라 이 모듈의 catalog/campaign 범위.

- `.service.SellerService`·`.service.SellerSkuService`·`.service.CampaignService`: 공개 contract; `.service.impl`: 등록·검증·workflow
- Seller registration은 server-generated UUID 반환. Seller-SKU registration은 `(sellerId, skuId)`, `initialStock`, global `maxActiveCheckouts` 설정.
- Campaign은 Seller-SKU를 참조. Campaign 생성 시 stock/K를 복제하지 않음; 다른 Campaign과 stock 및 FIFO/K 공유.
- `.dto.request`·`.dto.response`: contract; `.persistence.state.CampaignStatus`: shared lifecycle state; `.exception.CampaignException`: typed failure codes
- `.persistence.entity`·`.persistence.repository`: Seller/SellerSku/Campaign JPA mapping과 repository
- 의존: `inventory-module`의 `.service`와 `.dto.request`만. Campaign 소유 테이블만 변경.
- HTTP controller: `application-module`의 `com.atlas.application.controller.seller` 및 `.campaign`.
