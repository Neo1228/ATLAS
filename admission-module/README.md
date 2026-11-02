# admission-module

Queue ticket·순번·idempotency·checkout slot과 expiry를 소유하는 도메인 모듈.

- `.service.AdmissionService`: 공개 contract; `.service.impl.AdmissionServiceImpl`: workflow와 scheduled expiry
- `.dto.request`: join input/release reason; `.dto.response.QueueTicketResponse`: output; `.persistence.state.TicketStatus`: shared lifecycle state; `.exception.AdmissionException`: typed failure codes
- `.persistence.entity.QueueTicket`·`.persistence.repository.TicketRepository`: ticket CRUD/query
- `.persistence.repository.JdbcQueueRepository`: counter lock/sequence, 만료와 FIFO promotion에 필요한 SQL. Counter는 별도 CRUD entity 없이 이 저장소가 관리.
- HTTP controller: `application-module`의 `com.atlas.application.controller.admission`; GET은 저장 상태만 반환.
