# ADR-0058: 기존 주문 흐름을 분산 이행 프로젝트의 단일 저장소 기준선으로 사용한다

- 상태: Accepted — 1단계 범위 결정
- 날짜: 2026-11-20
- 관련 결정: [ADR-0010](ADR-0010-order-fulfillment-lifecycle.md)(주문 이행 상태), [ADR-0013](ADR-0013-pre-shipment-refund-recording.md)(배송 전 환불), [ADR-0017](ADR-0017-seller-fulfillment-locations.md)(Seller 이행 위치), [ADR-0018](ADR-0018-seller-order-shipments.md)(Seller 주문 출고), [ADR-0026](ADR-0026-paypal-sandbox-payments.md)(결제), [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md)(결제 복구), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md)(주문·결제 복구 경계)

## 문제

제안된 프로젝트의 1단계는 주문 제출부터 재고 예약, 결제, 출고·배송, 취소·환불까지의 상태와 불변식을 검증하는 것이다. ATLAS에는 이미 이 흐름의 대부분이 있으므로 새 시스템을 다시 만들면 같은 업무 규칙을 중복 구현하고 기존 동작과의 차이를 검증하기 어렵다. 반대로 현행 기능이 있다는 이유만으로 분산 환경의 장애 복구까지 검증됐다고 간주해서도 안 된다.

## 확인한 사실

- 프로젝트는 `atlas-order`, `atlas-inventory`, `atlas-payment`, `atlas-settlement` 모듈로 주문·재고·결제·정산 책임을 나눈 modular monolith다.
- 주문 진입은 `CartCheckoutService`/`CheckoutPaymentService`, 이행은 `OrderFulfillmentService`로 구분돼 있다. Seller fulfillment location과 shipment 개념도 이미 존재한다.
- 기존 통합 테스트에는 `LocationRoutedCheckoutIntegrationTest`, `InventoryConcurrencyIntegrationTest`, `OrderShipmentIntegrationTest`, `OrderRefundLifecycleIntegrationTest`, `BuyerFlowIntegrationTest`, `PaymentWorkflowCrashWindowIntegrationTest`가 있다. 기존 coverage는 주문·위치·동시성·shipment/refund를 다루지만, refund provider 응답 유실 뒤 owner-side 적용을 recovery worker가 마치는 crash-window 회귀 테스트는 없었다.
- 기본 PATH로 실행되는 JDK는 21.0.13.1이다. Java 25는 `/opt/homebrew/opt/openjdk@25`에 설치되어 있지만 Gradle이 자동 탐지하지 않아 최초 `:atlas-application:test` 시도가 `languageVersion=25` toolchain을 찾지 못하고 테스트 실행 전에 중단됐다. `JAVA_HOME`을 해당 설치 경로로 지정해 재실행한다.

## 결정

1. 1단계는 현행 PostgreSQL 기반 애플리케이션의 정상·실패 경로를 기준선으로 삼고, 누락된 정책이나 불변식이 발견될 때만 구현한다.
2. 검증 경계는 한 BuyerCheckout 전체가 아니라 한 SellerOrder다. 한 SellerOrder의 모든 품목은 하나의 persisted fulfillment location을 사용하지만, multi-seller checkout은 seller별 주문에 서로 다른 location을 쓸 수 있다. 주문 생성 → 재고 예약 → 결제 → 출고/배송 → 취소·환불 흐름은 기존 구현과 테스트를 재사용한다.
3. 경쟁 재고 예약과 요청 멱등성은 기존 구현·테스트를 재사용한다. Refund provider 응답 유실에서 복구 worker의 provider 재조회/재시도와 owner 적용까지 검증하는 회귀 테스트만 추가하고, 추가 구현은 테스트로 확인되는 결함이 있을 때만 한다.
4. 이 ADR의 1단계 범위에는 독립 inventory service, 별도 database, broker, Saga framework를 포함하지 않는다. 독립 transaction boundary와 부분 실패가 필요한 다음 단계의 설계는 별도 결정으로 기록한다.
5. 통합 테스트는 JDK 25와 현재 Docker context에 맞는 Testcontainers socket 설정으로 실행한다. 다음 단계로 넘어가기 전에 대상 테스트 결과를 기록한다.
6. `PaymentWorkflowCrashWindowIntegrationTest.lostRefundResponseIsRecoveredAndAppliedExactlyOnce`는 provider refund 성공 후 응답이 유실된 경우, 같은 provider request ID로 재시도하고 재고 복원·정산 reversal을 각각 한 번만 적용하는 것을 검증한다.

## 대안과 trade-off

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 별도 프로젝트를 새로 생성하고 주문 흐름을 재구현 | 현행 checkout·inventory·shipment·refund 경로와 중복되며, 동일 기능의 정책 차이만 늘어난다. |
| 1단계부터 창고별 DB와 메시지 브로커 도입 | 단일 저장소에서 해결 가능한 정합성 문제와 분산 경계에서만 생기는 문제를 구분하기 어렵고 운영 복잡도가 먼저 증가한다. |
| 현재 구현을 테스트 없이 완료로 간주 | 코드와 테스트 클래스의 존재는 실행 결과가 아니다. 이 ADR을 쓴 2026-11-20에는 Java 25 toolchain이 없어 통합 테스트가 실행되지 않았다. |

## 검증 및 완료 기준

6개 대상 클래스는 Java 25 + Colima Testcontainers 환경에서 두 시점에 실행했다. 두 결과 모두 기능 회귀 결과이며 부하 성능 지표가 아니다.

| 시점 | 코드 상태 | 통과 테스트 수 | test case 시간 합계(JUnit XML) |
| --- | --- | ---: | ---: |
| 2026-11-20 (최초 기준선) | main 병합·id 재번호 전 | 83 (failure/error/skipped 0) | 26.834초 |
| 2026-11-23 (현재 기준선) | main 병합·재번호·테스트 정리 후, 전체 `clean check`(no-cache) 중 실행 | 90 (failure/error/skipped 0) | 19.701초 |

두 시점은 코드와 실행 환경이 다르므로 83과 90, 두 시간 합계를 서로 비교하지 않는다. 증가분 7개가 어느 변경에서 왔는지는 시점별 클래스별 수를 남기지 않아 분리하지 못했다. 최초 기준선(83, 26.834초)은 당시 근거로만 남기고, 현재 값은 아래 표를 따른다.

최초 기준선 이후 바뀐 점은 다음과 같다. 6개 클래스 이름과 실행 명령은 그대로다.

- main의 18개 commit을 병합했고 이 프로젝트의 Flyway·ADR·EXP·decision-log id를 main의 번호 뒤로 재번호했다(Flyway V62~V74, ADR-0058~0064, EXP-053~057, D-124~D-130).
- 병합 뒤 실패 회귀 테스트로 재현해 고친 결함 3건(claim 응답 유실 뒤 취소, 위치 조회 실패와 재고 0의 구분, restock 늦은 worker 덮어쓰기)이 있다([D-129](../../00-governance/decision-log.md), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)).
- 곁가지와 탐색용 test를 정리했다. Buyer NFR 러너·Compose, 운영 jar 안의 loadtest 결제 provider, 일회성 capacity probe·migration plan test를 삭제했다. 삭제한 harness로 얻은 수치는 해당 EXP 문서에 일회성 측정으로만 남아 있다. 6개 대상 클래스는 삭제 대상이 아니다.

2026-11-23 현재 기준선의 클래스별 테스트 수(JUnit XML, 합계 90)는 다음과 같다.

| 클래스 | 테스트 수 |
| --- | ---: |
| `LocationRoutedCheckoutIntegrationTest` | 5 |
| `InventoryConcurrencyIntegrationTest` | 3 |
| `OrderShipmentIntegrationTest` | 5 |
| `OrderRefundLifecycleIntegrationTest` | 24 |
| `BuyerFlowIntegrationTest` | 50 |
| `PaymentWorkflowCrashWindowIntegrationTest` | 3 |

같은 2026-11-23 no-cache `clean check` 전체는 11개 모듈 **746개 테스트**이며 skipped·failure·error가 모두 0이다. 모듈별 수는 `atlas-account` 29, `atlas-admission` 19, `atlas-application` 507, `ATLAS-campaign` 10, `ATLAS-cart` 7, `atlas-catalog` 37, `atlas-inventory` 5, `atlas-inventory-location` 20, `atlas-order` 48, `atlas-payment` 38, `atlas-settlement` 26이다(합 746).

이 ADR 이후 분산 이행 단계(ADR-0059~0064)에서 추가하거나 바꾼 테스트 클래스의 같은 실행 기준 테스트 수는 다음과 같다. 위 6개 클래스와 별개이며, 단일 실행 기준 수이므로 이후 변경으로 달라질 수 있다.

| 클래스 | 테스트 수 | 관련 ADR |
| --- | ---: | --- |
| `InventoryReservationIntegrationTest` | 13 | [ADR-0059](ADR-0059-independent-inventory-location-service.md) |
| `InventoryLocationLayerBoundaryTest` | 6 | [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md) |
| `LocationReservationServiceIntegrationTest` | 15 | ADR-0060, [ADR-0064](ADR-0064-order-inventory-just-in-time-lease-claims.md) |
| `RemoteReservationCheckoutIntegrationTest` | 15 | ADR-0060, [ADR-0061](ADR-0061-return-restock-source-shipment.md) |
| `SplitAllocationCheckoutIntegrationTest` | 2 | ADR-0060 ([EXP-054](../../03-experiments/EXP-054-location-split-allocation.md)) |
| `LocationAllocationPolicyTest` | 2 | ADR-0060 (EXP-054) |
| `LocationRestockServiceImplTest` | 2 | ADR-0061 |
| `CheckoutCancelWithoutAttemptIntegrationTest` | 3 | ADR-0060 |
| `CheckoutCaptureFencePostgresIntegrationTest` | 4 | ADR-0060 |

이전 ADR 본문에 적힌 다른 테스트 수(예: 604, 이후 제거한 probe)는 해당 시점의 값이며 현재 값이 아니다. 현재 코드의 통과 여부는 최신 `./gradlew clean check`로 다시 확인해야 한다.

```sh
export JAVA_HOME="$(brew --prefix openjdk@25)"
export PATH="$JAVA_HOME/bin:$PATH"
# 아래 두 줄은 macOS에서 Colima로 Docker를 쓰는 환경에만 필요하다. Docker Desktop이나 Linux에서는 생략한다.
export DOCKER_HOST="$(docker context inspect colima --format '{{ .Endpoints.docker.Host }}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew --no-daemon :atlas-application:test \
  --tests 'com.atlas.inventory.InventoryConcurrencyIntegrationTest' \
  --tests 'com.atlas.order.LocationRoutedCheckoutIntegrationTest' \
  --tests 'com.atlas.order.OrderShipmentIntegrationTest' \
  --tests 'com.atlas.order.OrderRefundLifecycleIntegrationTest' \
  --tests 'com.atlas.BuyerFlowIntegrationTest' \
  --tests 'com.atlas.payment.PaymentWorkflowCrashWindowIntegrationTest'
```

`lostRefundResponseIsRecoveredAndAppliedExactlyOnce`는 PSP refund 처리 후 응답 유실로 결과가 `UNKNOWN`인 동안 재고·정산 효과가 적용되지 않고, recovery worker가 같은 provider request ID로 결과를 재확인한 뒤 재고 복원과 refund journal을 각각 1회만 적용하는 것을 검증한다. 이 6개 클래스 결과는 기존 단일 애플리케이션·PostgreSQL 경로의 1단계 회귀 기준선이다. 별도 창고 DB·브로커·독립 시스템 사이의 장애 복구나 성능 목표는 증명하지 않는다.

## 재검토 조건

- 기존 테스트가 주문-재고-결제-이행 불변식을 증명하지 못하거나 재현 회귀가 발생하면 1단계 범위에서 테스트와 최소 구현을 추가한다.
- 독립 창고 장애나 상태 불일치가 단일 저장소 경계에서 재현되지 않는 한 별도 창고 DB 도입을 앞당기지 않는다.
- 단계별 구현이 기존 프로젝트의 목적과 충돌하거나 제안된 사용자 문제를 보여주지 못하면 프로젝트 방향을 재검토한다.
