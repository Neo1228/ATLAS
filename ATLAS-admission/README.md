# ATLAS-admission

- Gradle project: `:admission-module`

Queue ticket·순번·idempotency·checkout slot과 expiry를 소유.

- `.service.AdmissionService`: 공개 contract; `.service.impl.AdmissionServiceImpl`: workflow와 scheduled expiry
- `.dto.request`: join input/release reason; `.dto.response.QueueTicketResponse`: output; `.persistence.state.TicketStatus`: shared lifecycle state; `.exception.AdmissionException`: typed failure codes
- `.persistence.entity.QueueTicket`·`.repository.TicketRepository`: ticket CRUD/query
- `.repository.JdbcQueueRepository`: Seller-SKU counter lock/sequence, expiry와 FIFO promotion SQL. Counter는 별도 CRUD entity 없이 이 저장소가 관리.
- HTTP controller: `application-module`의 `com.atlas.application.controller.admission`; GET은 저장 상태만 반환.
