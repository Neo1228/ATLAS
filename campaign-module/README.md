# campaign-module

Campaign과 SKU eligibility를 소유하는 도메인 모듈.

- `.service.CampaignService`: 공개 contract; `.service.impl.CampaignServiceImpl`: validation·workflow·Inventory 호출·transaction
- `.dto.request`: create input; `.dto.response.CampaignResponse`: output; `.persistence.state.CampaignStatus`: shared lifecycle state; `.exception.CampaignException`: typed failure codes
- `.persistence.entity.Campaign`·`.persistence.repository.CampaignRepository`: 기본 CRUD용 JPA mapping/repository
- 의존: `inventory-module`의 `.service`와 `.dto.request`만. Campaign 소유 테이블만 변경.
- HTTP controller: `application-module`의 `com.atlas.application.controller.campaign`.
