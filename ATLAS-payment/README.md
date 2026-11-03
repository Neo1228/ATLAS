# ATLAS-payment

- Gradle project: `:payment-module`

Payment attempt 상태와 Fake PSP 연동을 소유.

- `.service.PaymentService`: 공개 contract; `.service.impl.PaymentServiceImpl`: attempt 전이·짧은 DB transaction·provider 호출 순서
- `.dto.response.PaymentResponse`: output; `.persistence.state`: shared `PaymentState` and persistence-only `PaymentPhase`; `.exception.PaymentException`: typed failure codes
- `.persistence.entity.PaymentAttempt`·`.repository.PaymentAttemptRepository`: JPA CRUD와 idempotent initial insert
- `.provider.fake.FakePsp`: local test/runtime adapter; 실제 PSP 연결 아님
- PSP 호출은 DB transaction 밖. 신규 provider·분산 결제 보장은 현재 범위 밖.
