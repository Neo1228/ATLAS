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
| `order`/`payment`의 `OwnerLockRegistryTest` 두 개(본문 diff 0줄) | 두 모듈에 같은 이름의 `OwnerLockRegistry` 클래스가 각각 있고 테스트는 각자의 클래스를 검증합니다. 테스트 중복이 아니라 SUT가 둘인 것입니다. 실행함(EXP-105): 두 클래스를 `diff`로 비교하면 `package` 줄, `@Component` import와 어노테이션, `public` 한정자까지 7줄만 다르고 락 로직 본문은 같습니다. 결정: 클래스 통합은 모듈 경계(order와 payment가 서로 의존하지 않음) 설계 판단이라 테스트 감사 범위 밖이며, 시험은 SUT가 둘인 동안 지우지 않습니다. 재검토 조건: 두 클래스 중 한쪽만 고쳐지고 다른 쪽이 따라가지 않은 변경이 `git log`에 1건 나타나거나 `ModuleBoundaryTest`가 공용 모듈을 허용하게 바뀔 때. |
| `ExpirySweepPolicyTest`(order)와 `AdmissionExpirySweepPolicyTest` | 서로 다른 SUT 클래스입니다(본문 diff 40줄). |
| `InventoryLocationLayerBoundaryTest`와 `ModuleBoundaryTest` | `ModuleBoundaryTest`에는 `inventorylocation` 참조가 없습니다(grep 0건). 구조 테스트이므로 규칙상 보존 대상이기도 합니다. |
| `FxRateCallCostIntegrationTest.identicalAmountConversionsReuseTheCachedQuote`와 `CachingExchangeRateProviderTest.repeatedLookupsWithinTtlHitTheDelegateOnce` | 불변식은 겹치지만 EXP-041이 이 테스트를 측정 근거로 인용합니다. 클래스 전체가 1.8초이고 같은 Spring context를 공유해 삭제 이득이 없습니다. |
| `PayPalWebhookServiceTest.checkoutCaptureRedeliveryRetriesReconciliationAfterFailure` | 재조정 재시도 부분은 통합 테스트와 겹치지만, "체크아웃 경로의 재조정이 소유자 잠금 해제 뒤에 실행된다"는 단정은 이 테스트에만 있습니다. 실행함(EXP-105, 병합 전 `origin/main`의 별도 worktree에서 변이 3종, 매번 원본과 `cmp` 일치로 복원): (a) 체크아웃·주문 재조정 두 호출 모두를 소유자 잠금 안으로 옮기면 이 테스트와 `captureWebhookTransactionRunsInsideOwnerLockAndReconciliationRunsAfterRelease` 등 3건 중 3건이 실패, (b) 체크아웃 재조정만 잠금 안으로 옮기면 3건 중 2건 실패(이 테스트와 `captureWebhookTransactionRunsInsideOwnerLockAndReconciliationRunsAfterRelease`. 후자는 `findByAttemptId`가 잠금 밖이라는 단정을 공유하지만 이 변이는 그 호출 위치도 함께 옮겼기 때문), (c) `findByAttemptId`는 잠금 밖에 두고 체크아웃 재조정 호출만 `withPayPalWebhookOwnerLock` 안으로 옮기면 3건 중 **이 테스트 1건만 실패**하고 나머지 2건은 통과. 변이 없는 대조군은 3건 모두 통과(`BUILD SUCCESSFUL`). 결론: "체크아웃 경로 재조정이 소유자 잠금 해제 뒤에 실행된다"는 단정은 이 테스트만 가지고 있음이 실행으로 확인됐고 그래서 유지합니다(근거: 변이 (c), n=1). |
| `PayPalWebhookOperatorServiceImplTest`의 나머지 7건 | 재생 시 큐에 손대지 않음, 새 재큐의 큐·감사 기록, NOT_FOUND/CONFLICT 구분, lost update 경합, 페이지 경계는 같은 단정을 가진 통합 테스트를 찾지 못했습니다. |
| `PayPalWebhookRetryHistoryRedactionTest`와 `PayPalWebhookOperatorSecurityTest.authenticatedReadReturnsSafePage...` | 검증하는 DTO가 다릅니다(pending page·summary와 exhausted page). |
| 목 호출 밀도 상위 클래스(`CheckoutBehaviorTest`, `PayoutDispatchApprovalCommitServiceTest`, `AccountServiceTest` 등) | 서비스가 포트에 보내는 호출이 계약입니다. 같은 단정을 가진 더 낮은 계층·통합 테스트를 찾지 못했습니다. |
| `FxRateLatencyProbeTest`(1.7초, 단정은 `isNotEmpty()`), `InventoryFlashSaleContentionIntegrationTest`(5.1초, 최대 128 스레드) | 측정 도구를 `check`에 둔 것으로 규칙 4에 해당할 가능성이 높습니다. 그러나 EXP-041/EXP-044가 재현 방법으로 직접 인용하므로 옵트인 방식과 문서 수정이 함께 필요하고, 정리 종류(중복 제거 vs 실험 분리)가 달라 한 PR에 섞지 않았습니다. 후속 후보입니다. 실행함(EXP-105): `FlashSale`의 oversell 불변식(`available`이 음수가 아님, `available + reserved` 보존)을 `InventoryConcurrencyIntegrationTest`와 본문으로 대조했습니다. 후자가 `accepted == capacity`, `available == 0`, `reserved == capacity`, `claimed == 0`, 합 = 초기 용량(`assertConserved`)을 같은 PostgreSQL 위에서 확인하므로 oversell·보존 불변식은 겹칩니다. 겹치지 않는 부분은 `FlashSale`의 처리량 보고와 128 스레드 규모뿐입니다. 결정: 이 감사(중복 제거 한 종류)에서는 건드리지 않고 "실험을 `check`에서 옵트인으로 분리"하는 별도 정리로 남깁니다. 재검토 조건: EXP-041/EXP-044의 재현 방법을 옵트인 명령으로 바꾸는 PR이 열리거나, 두 클래스 중 하나가 CI에서 간헐 실패를 1건 낼 때. |
| `LocationFaultConvergenceIntegrationTest`(21.6초, sleep·await 7곳) | 가장 느리지만 이 감사 시점에는 간헐 실패 로그나 반복 실행 증거를 찾지 못했습니다. 근거 없이 줄이지 않았습니다. 이후 [EXP-097](EXP-097-ci-observation.md)이 CI에서 LocationFault 계열 간헐 실패를 관측했고(원인은 시험 내용이 아니라 죽은 DB를 기다리는 컨텍스트 종료 지연이라고 봤으며 #609로 다뤘다) 그 결과를 따릅니다. 결정: 중복 근거가 아니므로 줄이지 않습니다. 재검토 조건: EXP-097의 재수집에서 이 클래스가 다시 실패하거나, 클래스 시간 합이 shard 시간의 10%를 넘을 때. |

## 한계

- 후보 탐색은 이름과 본문 읽기 기반이라 놓친 중복이 있을 수 있습니다. 커버리지 도구로 같은 분기를 지나는지는 보지 않았습니다. 결정: 이 저장소에는 커버리지 gate가 없고(테스트 정리 기준 5번) 도구를 새로 도입하는 것은 이 감사의 범위를 넘습니다. 대신 후보마다 변이 실행으로 "같은 단정이 있는가"를 직접 확인했습니다. 재검토 조건: 저장소가 커버리지 gate를 도입하거나, 놓친 중복이 리뷰에서 1건 지적될 때.
- 변이는 수동으로 고른 3건에 EXP-105의 3종을 더한 것이며 변이 점수를 계산한 것이 아닙니다. 결정: 변이 점수 계산(예: PIT 도입)은 도구 도입이라 범위 밖이고, 삭제 판단은 후보별 변이 실행으로 충분합니다. 재검토 조건: 삭제 PR이 한 번에 10건 이상으로 커질 때.
- 기준선 시간은 공유 머신 값이고 일부 모듈은 캐시 복원 값이라 성능 근거로 쓰지 않습니다.
- 삭제 후 `clean check` 결과는 PR 본문에 기록합니다.

## EXP-105 종결 결과

재실행 표기: 변이 3종과 무변경 대조(PayPalWebhookServiceTest 3/3 등)와 롤백 드릴은 이전 에이전트가 보고한 값이며 이 에이전트는 다시 실행하지 않았다: local-only(이전 에이전트 보고).

이 문서의 인벤토리 삼각형은 1건이었다: 후보표의 체크아웃 재조정 잠금 경계 주장. 결정: 변이로 검증한 유일한 단정이므로 테스트를 유지한다. 기존 테스트 3건만 실행했으며 신규 `check` 테스트는 없고, production 코드도 바꾸지 않았다.

| 변이 | 변경 | 결과 |
|---|---|---|
| no-change | `./gradlew --no-daemon --console=plain :atlas-application:test --tests 'com.atlas.application.service.PayPalWebhookServiceTest'` | 3/3 통과, `BUILD SUCCESSFUL` |
| negative 1 | 체크아웃·주문 재조정 모두를 owner-lock lambda 안으로 이동 | 3/3 실패 |
| negative 2 | 체크아웃 재조정과 그 안의 `findByAttemptId`를 lock 안으로 이동, 주문 재조정은 밖에 둠 | 2/3 실패; `findByAttemptId`도 lock 밖이어야 한다는 별도 단정이 추가로 발동 |
| negative 3 | 원래 `findByAttemptId`는 밖에 두고 체크아웃 `reconcile` 호출만 `withPayPalWebhookOwnerLock`으로 감쌈 | 대상 테스트 1/3 실패, 다른 2건 통과 |

각 변이에서 위의 동일 Gradle 명령을 실행했다. 확인한 표본은 변이별 한 번이며 신뢰도 구간을 주장하지 않는다. 매 변이 뒤 저장한 production 파일과 복원 파일이 `cmp`로 일치함을 확인했다. 세 번째 변이는 의도한 checkout-only 단정을 격리했다. 실험은 JDK 25, 단일 JVM의 세 테스트였고 Docker/PostgreSQL 의존 테스트는 포함되지 않았다. 이 변이 결과는 기존 테스트가 더 정확한 통합 테스트와 중복되지 않는다는 근거다.

추가 확인: 두 모듈의 `OwnerLockRegistry` 본문 차이는 package, `@Component` import/annotation, visibility까지 7줄이었다. `InventoryFlashSaleContentionIntegrationTest`의 oversell/conservation assertion은 `InventoryConcurrencyIntegrationTest`의 PostgreSQL 검증과 겹치고, 처리량 보고와 128-thread 범위는 다르므로 테스트를 삭제하지 않고 옵트인 분리 후보로 결정했다.

되돌리기 드릴: 현재 EXP-084 차이를 임시 worktree의 `origin/main`에 `git apply "$PATCH"`로 적용한 뒤 `git apply -R "$PATCH"`로 역적용했다. `git diff --exit-code`와 `cmp`가 통과해 원본 파일과 바이트 단위 일치했다. 컴파일 코드는 바꾸지 않았다.

### Claims (claim | evidence | confidence)

| Claim | Evidence | Confidence |
| --- | --- | --- |
| 체크아웃 재조정은 owner lock 해제 뒤에 실행되며 이 단정은 유지 중인 테스트로 지켜진다 | no-change 3/3 통과; lock-boundary negative mutation 대상만 1/3 실패; 각 mutation 뒤 `cmp` 복원 | local-only |
