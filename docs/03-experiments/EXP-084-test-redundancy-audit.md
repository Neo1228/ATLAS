# EXP-084: 테스트 중복 감사와 중복 검증 3건 제거

**상태:** 로컬 측정 · 공유 머신(다른 작업과 동시 실행) · 시간은 성능 근거가 아님

## 목적과 범위

`.omp/rules/test-hygiene.md`의 첫 번째 삭제 근거(더 낮은 계층이나 더 정확한 테스트가 같은 동작을 이미 검증)만 다룹니다. 목표는 테스트 수를 줄이는 것이 아니라, 같은 불변식을 목(mock)으로 한 번 더 고정한 테스트를 근거와 함께 찾는 것입니다. 시간 단축은 목표가 아니고 주장하지 않습니다.

## 기준선

- 대상 커밋: `546d8002`(origin/main), 변경 없는 worktree.
- 명령: `./gradlew --no-daemon --console=plain clean check` (`/tmp/afull.sh` 래퍼). 결과 `BUILD SUCCESSFUL`.
- JUnit XML 합계: 클래스 169개, 테스트 999건, 실패 0, 건너뜀 0. 소스에서 센 `@Test`/`@ParameterizedTest`는 926건(파라미터 테스트가 실제 실행 수를 늘림).
- `atlas-application` 테스트만 이 실행에서 실제로 돌았고(클래스별 시간 합 281초), 나머지 모듈의 `test` 작업은 Gradle 빌드 캐시에서 복원됐습니다(`FROM-CACHE`). 캐시된 모듈의 시간은 이전 실행 값입니다.
- 가장 오래 걸린 클래스(합 281초 중): `LocationFaultConvergenceIntegrationTest` 21.6초, `BuyerFlowIntegrationTest` 19.5초, `PayoutDispatchProviderIntegrationTest` 19.2초, `LocationReservationServiceIntegrationTest` 18.0초. 이 중 중복으로 입증된 것은 없어 모두 유지했습니다.

## 방법

1. 클래스별 테스트 메서드 이름과 SUT를 나열하고, 같은 SUT 메서드를 같은 단정 의도로 확인하는 쌍을 찾았습니다.
2. 테스트당 `verify`·`times`·`never` 밀도를 계산해 목 호출 횟수를 고정하는 클래스를 골랐습니다(상위: `CheckoutBehaviorTest` 4.2, `OrderReturnServiceImplTest` 4.0, `PayPalWebhookServiceTest` 3.5건/테스트).
3. 후보마다 본문을 읽고, 덮는 테스트가 실제 PostgreSQL 통합 테스트인지 확인했습니다.
4. 삭제 대상 테스트가 지키던 동작을 깨는 변이(mutation)를 본문에 넣고, 삭제 대상과 덮는 테스트가 모두 실패하는지 실행했습니다.

## 삭제한 테스트 3건

| 삭제한 테스트 | 같은 불변식을 덮는 테스트 | 변이 | 변이 결과 |
| --- | --- | --- | --- |
| `PayPalWebhookOperatorServiceImplTest.idempotencyKeyCannotBeReusedForDifferentActorEventOrReason` | `PaymentLifecycleIntegrationTest.webhookOperatorIdempotencyAndAuditSurviveQueueDeletion` (같은 키에 다른 actor/event/reason이면 모두 conflict, 감사 행 1건) | `PayPalWebhookOperatorServiceImpl`의 재생 검사에서 `!record.actor().equals(actor)` 조건 제거 | 삭제 대상 실패, 덮는 통합 테스트 실패 |
| `PayPalWebhookOperatorServiceImplTest.durableAuditHistoryStopsFourthRequeue` | `PaymentLifecycleIntegrationTest.threeRequeueLimitSurvivesWorkerDeletionAndQueueRecreation` (큐 행을 지워도 4번째 재큐 conflict, 감사 3건) | `manualRequeues >= MAX_MANUAL_REQUEUES`를 `>`로 변경 | 삭제 대상 실패, 덮는 통합 테스트 실패 |
| `PayPalWebhookServiceTest.orderCaptureRedeliveryRetriesReconciliationAfterFailure` | `PaymentLifecycleIntegrationTest.sameCaptureWebhookRedeliveryRetriesOrderReconciliationAfterEventCommit` (재조정 실패 뒤 같은 webhook 재전달이 재조정을 다시 호출, 이벤트 PROCESSED) | `PayPalWebhookService`에서 `orders.reconcilePayment` 호출 줄 제거 | 삭제 대상 실패, 덮는 통합 테스트 실패 |

변이는 변이당 1회 실행했습니다(표본 3건, 반복 없음). 변이 결과 확인 후 변이는 되돌렸고 저장소에는 남지 않습니다. 세 번째 변이는 남겨 둔 단위 테스트 `captureWebhookTransactionRunsInsideOwnerLockAndReconciliationRunsAfterRelease`와 `webhookAndRealOrderOwnerLockCompleteWithoutPaymentToOrderLockCycle`도 실패시켰습니다. 즉 주문 경로의 재조정 호출은 삭제 후에도 단위 테스트 2건과 통합 테스트 1건이 지킵니다.

삭제의 근거는 중복 검증(규칙 1)과 목이 현실을 대체한 점(규칙 5, 삭제 대상 3건 모두 저장소·서비스를 목으로 대체하고 `verify(..., never())`/`times(2)`로 호출 횟수를 고정)입니다. 삭제 전 확인한 것: 삭제 대상만 가진 단정이 있는지. 첫 두 건은 통합 테스트가 같은 입력과 같은 결과를 실제 DB로 확인했고, 세 번째는 "주문 경로에서 재조정이 소유자 잠금 해제 뒤에 실행된다"는 단정이 남는 `captureWebhookTransactionRunsInsideOwnerLockAndReconciliationRunsAfterRelease`에 있습니다.

## 기각한 후보와 이유

| 후보 | 기각 이유 |
| --- | --- |
| `order`/`payment`의 `OwnerLockRegistryTest` 두 개(본문 diff 0줄) | 두 모듈에 같은 이름의 `OwnerLockRegistry` 클래스가 각각 있고 테스트는 각자의 클래스를 검증합니다. 테스트 중복이 아니라 SUT가 둘인 것입니다(두 클래스 본문이 같은지는 비교하지 않았습니다). SUT 통합은 별도 설계 판단이라 이 감사에서 다루지 않았습니다. |
| `ExpirySweepPolicyTest`(order)와 `AdmissionExpirySweepPolicyTest` | 서로 다른 SUT 클래스입니다(본문 diff 40줄). |
| `InventoryLocationLayerBoundaryTest`와 `ModuleBoundaryTest` | `ModuleBoundaryTest`에는 `inventorylocation` 참조가 없습니다(grep 0건). 구조 테스트이므로 규칙상 보존 대상이기도 합니다. |
| `FxRateCallCostIntegrationTest.identicalAmountConversionsReuseTheCachedQuote`와 `CachingExchangeRateProviderTest.repeatedLookupsWithinTtlHitTheDelegateOnce` | 불변식은 겹치지만 EXP-041이 이 테스트를 측정 근거로 인용합니다. 클래스 전체가 1.8초이고 같은 Spring context를 공유해 삭제 이득이 없습니다. |
| `PayPalWebhookServiceTest.checkoutCaptureRedeliveryRetriesReconciliationAfterFailure` | 재조정 재시도 부분은 통합 테스트와 겹치지만, "체크아웃 경로의 재조정이 소유자 잠금 해제 뒤에 실행된다"는 단정은 이 테스트에만 있습니다. 이 단정을 깨는 변이는 실행하지 않았고 읽어서 확인했습니다. |
| `PayPalWebhookOperatorServiceImplTest`의 나머지 7건 | 재생 시 큐에 손대지 않음, 새 재큐의 큐·감사 기록, NOT_FOUND/CONFLICT 구분, lost update 경합, 페이지 경계는 같은 단정을 가진 통합 테스트를 찾지 못했습니다. |
| `PayPalWebhookRetryHistoryRedactionTest`와 `PayPalWebhookOperatorSecurityTest.authenticatedReadReturnsSafePage...` | 검증하는 DTO가 다릅니다(pending page·summary와 exhausted page). |
| 목 호출 밀도 상위 클래스(`CheckoutBehaviorTest`, `PayoutDispatchApprovalCommitServiceTest`, `AccountServiceTest` 등) | 서비스가 포트에 보내는 호출이 계약입니다. 같은 단정을 가진 더 낮은 계층·통합 테스트를 찾지 못했습니다. |
| `FxRateLatencyProbeTest`(1.7초, 단정은 `isNotEmpty()`), `InventoryFlashSaleContentionIntegrationTest`(5.1초, 최대 128 스레드) | 측정 도구를 `check`에 둔 것으로 규칙 4에 해당할 가능성이 높습니다. 그러나 EXP-041/EXP-044가 재현 방법으로 직접 인용하므로 옵트인 방식과 문서 수정이 함께 필요하고, 정리 종류(중복 제거 vs 실험 분리)가 달라 한 PR에 섞지 않았습니다. 후속 후보입니다. `FlashSale`의 oversell 불변식이 `InventoryConcurrencyIntegrationTest`와 같은지는 이름만 대조했고 본문 대조는 하지 않았습니다. |
| `LocationFaultConvergenceIntegrationTest`(21.6초, sleep·await 7곳) | 가장 느리지만 간헐 실패 로그나 반복 실행 증거를 찾지 못했습니다. 근거 없이 줄이지 않았습니다. |

## 한계

- 후보 탐색은 이름과 본문 읽기 기반이라 놓친 중복이 있을 수 있습니다. 커버리지 도구로 같은 분기를 지나는지 확인하지는 않았습니다(이 저장소에는 커버리지 gate가 없음).
- 변이는 수동으로 고른 3건이며 변이 점수를 계산한 것이 아닙니다.
- 기준선 시간은 공유 머신 값이고 일부 모듈은 캐시 복원 값이라 성능 근거로 쓰지 않습니다.
- 삭제 후 `clean check` 결과는 PR 본문에 기록합니다.
