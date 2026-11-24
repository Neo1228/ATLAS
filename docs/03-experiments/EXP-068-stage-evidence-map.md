# EXP-068: 단계 5·6·7·8의 시나리오가 기존 테스트와 문서로 어디까지 증명돼 있는가

- 질문: 처음 계획의 5단계(결제 결과 불명 조정), 6단계(비동기 이벤트), 7단계(분할 출고·부분 취소·환불), 8단계(장애 주입)의 시나리오 중 이미 테스트로 확인된 것과 비어 있는 것은 무엇인가?
- 상태: 조사. 코드·테스트를 새로 만들지 않았다. 테스트 이름은 소스에서 추출한 실제 메서드 이름이며, 통과 여부는 2026-11-24 전체 검사(777건, 실패 0)를 근거로 한다.
- 날짜: 2026-11-24
- 관련: [ADR-0010](../02-decisions/adr/ADR-0010-order-fulfillment-lifecycle.md), [ADR-0026](../02-decisions/adr/ADR-0026-paypal-sandbox-payments.md), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md), [EXP-064](EXP-064-location-fault-convergence.md)

## 왜 했는가

남은 단계마다 테스트를 새로 쌓으면 저장소만 무거워진다. 먼저 이미 있는 근거를 시나리오별로 대응시키고, 대응이 없는 곳만 후속 작업으로 둔다.

## 방법

관련 테스트 클래스의 `@Test` 메서드 이름을 소스에서 추출해 시나리오에 손으로 대응시켰다. 이름만 보고 대응한 것이라 테스트가 실제로 그 시나리오를 충분히 단언하는지는 일부만 읽어 확인했다(아래 "확인 수준"). 결제 9개 클래스 53건, 출고·환불 12개 클래스 78건을 대상으로 했다.

## 5단계: 결제 결과가 불명한 상황

| 시나리오 | 대응하는 테스트 |
| --- | --- |
| 승인 응답 유실 | `PaymentLifecycleIntegrationTest.lostAuthorizationResponseIsUnknownAndRecoversWithTheSameProviderRequestId` |
| 매입 응답 유실 | `PaymentLifecycleIntegrationTest.lostCaptureResponseIsRecoveredFromThePayPalOrder` |
| 환불 응답 유실 | `PaymentWorkflowCrashWindowIntegrationTest.lostRefundResponseIsRecoveredAndAppliedExactlyOnce`, `OrderRefundLifecycleIntegrationTest.lostRefundResponseStaysUnknownAndSameKeyReplayReusesProviderRequestId` |
| 지연된 성공(승인 마감 뒤) | `PaymentApprovalAbandonmentIntegrationTest.lateApprovalAfterTheDeadlineStillConvergesToCaptureWithoutReleasingInventory` |
| 중복 통지 | `PaymentLifecycleIntegrationTest.webhookSignatureIsVerifiedAndDuplicateCaptureEventIsIdempotent`, `sameCaptureWebhookRedeliveryRetriesOrderReconciliationAfterEventCommit` |
| 결제 성공과 예약 만료 경쟁 | `RemoteReservationCheckoutIntegrationTest.expiredRemoteReservationVoidsAuthorizationInsteadOfCapturing`, `CheckoutCaptureFencePostgresIntegrationTest.committedFenceBeatsConcurrentCaptureReservation` |
| 취소와 매입 경쟁 | `PaymentCaptureCancellationFenceTest.cancellationFenceStopsAnotherServiceInstanceFromReservingCapture` |
| 여러 인스턴스·worker 동시 처리 | `PaymentRecoveryIntegrationTest.onlyOneConcurrentWorkerClaimsAnOperationAndExpiredLeaseFencesStaleCompletion`, `ConcurrentPaymentPreparationIntegrationTest.concurrentPreparationCreatesExactlyOneAttempt` |
| 프로세스 중단 뒤 재개 | `PaymentWorkflowCrashWindowIntegrationTest.intentCommittedBeforeProviderDispatchIsRetriedByTheDurableJob`, `persistedCaptureWithoutOwnerApplicationIsAppliedOnceByTheRecoveryWorker` |

계획의 "독립된 가상 결제 시스템"은 만들지 않았다. 저장소는 실제 PayPal Sandbox를 외부 결제 시스템으로 쓰고([ADR-0026](../02-decisions/adr/ADR-0026-paypal-sandbox-payments.md)), 테스트는 `MockWebServer`로 만든 PayPal 대역을 쓴다. 이 대체가 계획의 의도(상태 저장소를 가진 독립 외부 시스템에서 결과 불명 재현)를 충족하는지는 판단이 갈린다. `MockWebServer` 대역의 응답 규칙이 실제 PayPal과 같은지는 이 조사에서 비교하지 않았다. 다만 실제 Sandbox를 상대로 한 근거가 따로 있다. [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md)은 capture 응답을 12초 지연시켜(클라이언트 read timeout 10초 초과) 응답을 유실시키면 3/3에서 `PAYMENT_UNKNOWN`이 되고, PayPal에는 capture가 1건 있으며, reconcile 호출 뒤 3/3이 `PAID/CAPTURED`로 수렴하고 PayPal capture 1건에 ATLAS의 capture·payment·order·amount·ledger가 각각 1건으로 일치했다고 기록한다(복구 worker를 끈 조건, n=3).

## 6단계: 비동기 이벤트

| 시나리오 | 근거 |
| --- | --- |
| 이벤트 유실 없음(커밋 순서 역전) | `ReservationEventReadIntegrationTest`, [EXP-061](EXP-061-event-log-fanout-and-replay.md) |
| 상태 변화마다 정확히 한 건, 연속 version | `ReservationEventTriggerIntegrationTest` |
| 전송 실패 뒤 cursor 보존과 재전달 | `ReservationEventKafkaIntegrationTest` |
| 읽기 순서, 재읽기, 단조 cursor, 그룹 독립 | `ReservationEventConsumerContractIntegrationTest`(EXP-067, PR #518에서 병합 대기) |
| 중복 제거, 다중 프로세스 소비자, 브로커 중단 | **없음**. 저장소에 소비자 구현이 없다([ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md)가 소비자 책임으로 둠) |

## 7단계: 분할 출고·부분 취소·환불

| 시나리오 | 대응하는 테스트 |
| --- | --- |
| 단일 창고 대 분할 출고의 수용 가능 주문 수(30 대 65) | `SplitAllocationCheckoutIntegrationTest.singleLocationPolicyStopsAtThirtyWhileSplitReachesSixtyFive` |
| 창고별 출고 | `SplitAllocationCheckoutIntegrationTest.splitOrderShipsPerLocationWithinEachAllocation` |
| 부분 환불과 증분 출고 | `OrderShipmentIntegrationTest.partialRefundAllowsIncrementalShipmentsAndReturnOnlyAfterNetDelivery` |
| 품목·수량 단위 환불, 재입고 | `OrderRefundLifecycleIntegrationTest.partialAndFullRefundsReplayAndRestoreOnlyRequestedUnitsWithoutChangingPaidState`, `mixedInspectionRefundsOnlyPassedLineAndFailedQuantityRemainsUnavailable` |
| 누적 반품 수량 한도 | `returnQuantityLimitIncludesEarlierRefundsAndPassedReturnsAcrossCases` |
| 환불 금액 한도(매입 USD 초과 금지, 반올림) | `PaymentLifecycleIntegrationTest.partialRefundsReuseFxSnapshotAndCapRoundingAtCapturedUsdAmount` |
| 동시 환불·반품·출고 | `concurrentRetryCreatesOneRefundAndRestoresStockOnce`, `concurrentDeliveredReturnRequestsCannotClaimSameUnitsTwice`, `OrderShipmentIntegrationTest.concurrentFinalRefundAndDispatchSerializeOnOrder` |
| 환불 결과 불명 | `unknownRefundRetainsQuantityUntilProviderGetConfirmsCompletion` |
| 정산 실패 시 환불 상태 롤백 | `settlementFailureRollsBackRefundStateInventoryAndReversalBeforeRetry` |
| 반품 재입고를 원 출고 위치로 | `RemoteReservationCheckoutIntegrationTest.inspectedReturnRestocksAtItsSourceShipmentReservation` |

## 8단계: 장애 주입

| 장애 | 근거 | 수준 |
| --- | --- | --- |
| location 요청·응답 유실, 503 | [EXP-064](EXP-064-location-fault-convergence.md) | 메모리 모델, seed 5개 |
| 동시 claim·cancel | EXP-065 | 메모리 모델 |
| order DB 연결 반복 끊김 | EXP-066(PR #515) | 조건별 n=1, 코드 미공개 |
| PostgreSQL 커밋 경계, PayPal 매입 응답 유실 | [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md) | 단일 쿼리·소표본 |
| 프로세스 종료 | [EXP-053](EXP-053-inventory-location-failure-load.md) | 1회 |
| 컨테이너 kill·pause, 네트워크 지연·손실 | EXP-070, EXP-071(PR #548) | 조건별 n=1, 코드 미공개 |
| CPU·메모리 스트레스 | 없음 | 미실행 |
| PostgreSQL 서버 재시작(WAL 재생) | 없음 | 미실행 |
| 브로커(Kafka) 중단 | EXP-072(PR #548) | 조건별 n=1, 코드 미공개, 파티션 1개 |

## 비어 있는 것(후속 후보)

1. 소비자 중복 제거와 다중 프로세스 읽기: 소비자 구현이 생기기 전에는 검증할 대상이 없다.
2. PayPal 대역의 응답 규칙이 실제 Sandbox와 같은지 비교. 어긋나면 5단계 근거 중 대역에 기대는 단위·통합 테스트가 흔들린다. 실제 Sandbox 근거는 EXP-050의 capture 응답 유실 3건과 worker 활성 조건 매트릭스다.
3. 계획 3.3의 창고 선택 정책(출고 용량, 배송 기한, 배송 비용, 창고별 실패율): `LocationAllocationPolicy`는 위 표의 재고 기반 테스트만 있고, 용량·기한·비용·실패율 입력은 이 조사에서 확인하지 못했다.
4. 컨테이너 수준 장애(Pumba), PostgreSQL 컨테이너 kill·내부 프로세스 크래시, Kafka 중단은 EXP-070·071·072(PR #548)에서 조건별 n=1로 실행했다. Kafka 중단에서는 relay가 이벤트를 잃지 않았고 중복은 `pause`에서만 나왔다. 파티션이 여러 개인 경우와 relay를 가진 application의 kill은 아직 실행하지 않았다.
5. 배송 실패와 carrier 연동: [ADR-0010](../02-decisions/adr/ADR-0010-order-fulfillment-lifecycle.md)에 carrier 연동이 범위 밖으로 적혀 있는 것으로 읽었다. 이 조사에서 해당 문장을 다시 검증하지는 않았다.

## 확인 수준과 한계

- 테스트 이름과 시나리오의 대응은 사람이 이름으로 판단한 것이다. 테스트가 실제로 해당 경쟁이나 장애를 일으키는지는 `lostCaptureResponseIsRecoveredFromThePayPalOrder` 등 몇 개의 이름과 클래스 구성만 확인했고, 53건·78건 전부를 읽지는 않았다.
- "통과"는 전체 검사 777건 통과에 기댄 것이며 개별 테스트의 안정성(반복 실행 시 재현 여부)은 확인하지 않았다.
- 표는 "존재"를 보일 뿐 "충분함"을 보이지 않는다. 한 시나리오에 테스트가 하나 있다는 것이 모든 조합을 덮는다는 뜻이 아니다.
