# ATLAS-campaign

- Gradle project: `:campaign-module`

`campaign-module`은 Seller, Seller-SKU 등록과 Campaign 생성을 함께 소유. 현재 구매 흐름에서 Seller-SKU 등록은 Campaign 생성에 필요한 정보이며, 독립된 업무 흐름이나 데이터 소유 경계가 없어 모듈 분리 없이 유지 ([D-054](../docs/00-governance/decision-log.md)).

- `.service.SellerService`·`.service.SellerSkuService`·`.service.CampaignService`: 모듈 API. `.service.impl`: 입력 검증과 업무 흐름 처리.
- Seller 등록 시 서버가 UUID 생성. Seller-SKU 등록 시 `(sellerId, skuId)`, `initialStock`, global `maxActiveCheckouts` 설정.
- Campaign은 Seller-SKU를 참조. Campaign 생성 시 stock/K를 복제하지 않으며, 같은 Seller-SKU를 참조하는 Campaign끼리 재고와 FIFO/K 공유.
- `.dto.request`·`.dto.response`: 모듈 간 요청·응답 형식. `.persistence.state.CampaignStatus`: Campaign 상태. `.exception.CampaignException`: 업무 오류와 error code.
- `.persistence.entity`: Seller/SellerSku/Campaign JPA mapping. `.repository`: 모듈 내부 repository.
- 의존: `inventory-module`의 `.service`·`.dto.request` API만 사용. 다른 모듈 테이블 직접 변경 금지.
- HTTP controller는 `application-module`의 `com.atlas.application.controller.seller`·`.campaign`에 배치.
