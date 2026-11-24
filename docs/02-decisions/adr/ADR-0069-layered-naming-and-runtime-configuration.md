# ADR-0069: 계층형 이름과 불필요한 런타임 설정 줄이기

- 상태: Accepted(구현·검증 완료)
- 날짜: 2026-11-23
- 관련 결정: [ADR-0058](ADR-0058-distributed-fulfillment-stage-one-baseline.md), [ADR-0059](ADR-0059-independent-inventory-location-service.md), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md), [ADR-0066](ADR-0066-redis-admission-gate.md), [ADR-0067](ADR-0067-event-log-kafka-relay.md)
- 근거 실험: [EXP-053](../../03-experiments/EXP-053-inventory-location-failure-load.md), [EXP-060](../../03-experiments/EXP-060-hot-sku-admission-gate.md), [EXP-062](../../03-experiments/EXP-062-admission-gate-paired-comparison.md), [EXP-063](../../03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md)

## 문제

새 주문 이행 코드를 추가하면서 `OrderInventory`처럼 모듈과 도메인 범위를 반복하는 접두어가 늘었고, `Coordinator`, `Planner`, `Finalizer`처럼 저장소의 계층 이름과 맞지 않는 클래스명도 생겼다. 서비스 계약과 구현체의 패키지 배치, 테스트 위치도 기존 구조와 일관되지 않았다.

설정에서도 세 recovery 워커의 poll 간격과 batch 크기를 각각 조정할 수 있게 하고, 기본값을 중첩 fallback으로 반복했다. 워커별로 값을 다르게 설정한 실험은 없었다. 이렇게 노출한 설정은 사용자가 관리해야 할 항목만 늘릴 뿐, 현재는 별도 운영 이점을 입증하지 못한다.

`origin/main`의 Java main 클래스 451개를 이름의 마지막 단어로 분류하면 `Request` 71개, `Response` 58개, `Repository` 49개, `Service` 42개, `Controller` 31개, `Impl` 22개, `Configuration` 12개다. Order 모듈에는 `service`와 `service/impl`이 각각 10개, `repository`가 10개 있으며 DTO도 요청·응답 패키지로 나뉘어 있다. 신규 코드도 별도 명명 체계를 만들기보다 이 구조를 따라야 한다.

## 대안과 절충

- 기존 이름과 배치를 유지한다: 수정 위험은 가장 낮지만, 계층 경계가 이름에서 드러나지 않고 모듈명과 도메인명이 반복된다.
- 모든 구체 클래스에 인터페이스를 만들고 새 adapter 계층을 둔다: 형식은 균일해지지만, 단일 구현뿐인 계산 정책까지 추상화해 코드와 탐색 비용을 늘린다.
- 기존 패키지 규약을 따르되 실제 호출 경계에만 인터페이스를 둔다: 선택. 단일 구현의 순수 계산 로직은 구체 정책 클래스로 유지한다.

## 결정

- Controller는 `application/controller/order`, 서비스 계약은 `order/service`, 구현체는 `order/service/impl`, 저장소는 `order/repository`, 요청·응답 DTO는 각각 `order/dto/request`와 `order/dto/response`에 둔다.
- 재고 위치 모듈과의 이름 충돌을 피할 때만 `LocationReservation...` 접두어를 사용한다. `OrderInventory...`처럼 모듈과 도메인을 반복하는 접두어는 제거한다.
- Controller와 워커에서 사용하는 예약, 재입고, checkout 확정, 이벤트 서비스는 인터페이스와 `*Impl` 구현체로 나눈다. 서로 다른 구현 전략을 제공하는 location client, stock admission, event publisher도 계약과 구현을 분리한다. 반면 I/O가 없는 단일 allocation 계산은 `LocationAllocationPolicy`로 두고 인터페이스를 추가하지 않는다.
- Spring 빈 조립과 조건부 활성화는 `order/config`에서 담당한다. 구현 클래스가 설정 책임까지 맡지 않도록 한다.
- DB에서 읽은 이벤트 `ReservationEvent`는 `order/persistence/projection`에 유지한다. 서비스 경계에는 `ReservationEventResponse` DTO만 노출하고, 구현체에서 projection과 DTO를 변환한다. HTTP/Kafka 구현은 `application/service/impl`, relay 워커는 `application/service`, controller는 `application/controller/order`에 둔다.
- 기존 HTTP 경로, DB 테이블·열, migration 이력, metric 이름과 외부 property 이름은 유지한다. 클래스와 패키지 이름을 바꿔도 외부 계약은 바꾸지 않는다.
- 세 recovery 워커의 개별 poll·batch 설정과 중첩 fallback은 공용 poll 간격·batch 크기로 합친다. 워커별 설정 차이를 뒷받침하는 실험이 없기 때문이다. 공용 값은 운영 부하에 따라 조정할 수 있게 유지한다.
- 비밀값, 배포별 주소, 기능 플래그, 실험에서 실제로 변경한 요청률·재고량·gate 방식·timeout은 설정으로 유지한다. 재고 TTL, 멱등성 보존, timeout·lease처럼 상태 안전성에 영향을 주는 설정은 근거 없이 제거하지 않는다.
- 실행 스크립트와 결과는 `.local/`에 두고, 재현에 필요한 Compose 정의와 관측 도구 코드는 `load-tests/`에 추적한다. git-ignore 영역에만 원본 자료가 남은 EXP-053은 저장소만으로 재현할 수 없다고 밝힌다.
- 추상과 구체의 경계(2026-11-24 추가). 이름을 정할 때 두 가지를 묻는다. 첫째, 내일 기술이나 공급자를 바꾸면 이 이름도 고쳐야 하는가. 둘째, 이 이름은 바깥(다른 모듈이 기대하는 기능)을 설명하는가, 안쪽(구현 기술)을 설명하는가.
  - 다른 모듈이 부르는 계약(interface)은 역할 이름으로 짓고 기술명을 넣지 않는다. 기술이 바뀌어도 이름이 유지돼야 하기 때문이다(`StockAdmissionControl`, `PaymentProvider`, `LocationClient`).
  - 구현체는 기술을 앞에 붙여 구체적으로 짓는다(`RedisStockAdmissionControl`, `PrecheckStockAdmissionControl`, `PayPalPaymentProvider`, `HttpLocationClient`). 한 계약에 구현이 둘 이상이면 이름만으로 구분돼야 한다. 구현이 하나뿐이면 `*Impl`을 쓴다.
  - 기술 설정 클래스는 안쪽 이름이므로 기술을 드러낸다(`RedisStockAdmissionControlConfiguration`).
  - 계약 자체가 공급자에 묶여 있으면 공급자 이름이 정확한 이름이다. `PayPalPayoutsProvider`는 PayPal 요청 id와 USD만을 전제로 하므로 `PayoutProvider`로 부르면 공급자를 바꿀 수 있다고 잘못 알린다. 공급자가 둘이 되는 시점에 요청·응답 타입과 함께 일반화한다.
  - `Service`는 역할을 말하지 않는다. 역할이 분명하면 그 역할의 단어를 쓴다(store, client, provider, sender, publisher). 요청이 큐나 workflow에 들어가기 전에 받을지 결정하는 일은 업계에서 admission control이라고 부르는 확립된 용어이므로 admission 계약을 `StockAdmissionService`에서 `StockAdmissionControl`로 바꿨다. 처음에는 ADR-0066의 문서 용어를 따라 `Gate`로 지었으나 gate는 admission control 안의 하위 메커니즘(capacity gate)을 가리키는 말이고 Fowler의 Gateway(외부 시스템 접근을 감싸는 객체)와도 다른 뜻이어서 계약 이름으로 쓰지 않는다. 속성(`admission-gate`)과 metric(`atlas.order.admission.gate`) 이름은 외부 계약이라 유지한다.
  - 인프라 구성 요소는 역할 뒤에 기술을 붙인다(`order-postgres`, `location-postgres`, `node-exporter`). 운영자가 서버 목록에서 무엇이 도는지 바로 알아야 하기 때문이며 저장소의 Compose 서비스 이름이 이미 이 방식이다.
- 모듈과 패키지 이름(2026-11-24 추가). Gradle 문서의 권고(하위 프로젝트는 기본 이름 = 디렉터리 이름, 소문자 하이픈)와 Spring Modulith의 모듈 정의(모듈은 기반 패키지의 직접 하위 패키지)를 따른다.
  - Gradle 프로젝트와 디렉터리는 같은 이름 `atlas-<모듈>`이다. 대문자 접두어와 `-module` 접미어, 이를 이어 주던 `projectDir` 매핑은 쓰지 않는다. 부팅 jar 이름도 프로젝트 이름에서 나오므로 `atlas-application.jar`다.
  - 모듈의 루트 패키지는 `com.atlas.<모듈>` 한 단계이며 서로 겹치지 않는다. `inventory-location`은 `com.atlas.inventorylocation`이다. `com.atlas.inventory` 아래에 다른 모듈을 두면 `inventory..`를 허용하는 규칙이 그 모듈까지 허용한다.
  - 다른 모듈이 import하는 공개 어휘(상태 enum, 저장 계약의 값 타입)는 `model`, 내부(DB 행, SQL 결과)는 `persistence.*`다. 안쪽 이름(`persistence`)을 바깥이 쓰지 않는다.
  - application의 `controller` 하위 패키지는 엔드포인트가 속한 도메인 모듈 이름이다(호출자 `seller`, `buyer`로 나누지 않는다). 이 규칙은 `PackageLayoutRulesTest`가 검사한다.
  - 계약 하나에 구현이 하나고 로직 없이 위임만 하는 클래스는 만들지 않는다. SQL 구현이 계약을 직접 구현한다(`JdbcPayoutDispatchStore`).
  - 도메인 모듈의 공개 `service` 패키지에는 다른 모듈이 부르는 계약(interface)과 값 타입만 둔다. 구현과 한 모듈 안에서만 쓰는 구체 클래스(정책, 암호화, 메일 발송 같은 내부 협력자)는 `service.impl`에 둔다. 이는 Spring Modulith의 "공개 인터페이스와 내부 구현 컴포넌트" 구분과 같다. `ModuleBoundaryTest.domainModulesExposeServicesOnlyAsContracts`가 검사한다. 구현 기술이 이름에 드러나는 구현은 기술을 앞에 붙인다(`CsvPayPalBalanceReportParser`).

## 검증과 한계

- DTO 경계와 구현체 등록을 수정한 뒤 `ModuleBoundaryTest` 4건과 `ReservationEventKafkaIntegrationTest` 1건이 통과했다. 수정 전 전체 모듈 실행에서는 507건 중 3건이 실패했으며, 두 건은 persistence projection이 서비스 계약에 노출된 경계 위반이고 한 건은 Spring 테스트가 인터페이스를 구현체로 등록한 오류였다. projection/DTO를 분리하고 테스트가 `ReservationEventServiceImpl`을 등록하도록 수정했다.
- 최종 `./gradlew clean check --no-build-cache --rerun-tasks --no-daemon --console=plain`: **746 tests, skipped 0, failures 0, errors 0**, `BUILD SUCCESSFUL`(10분 40초).
- Markdown lint 130개 파일, 변경 shell 6개(`bash -n` 및 ShellCheck), 변경 Python 4개(byte-compile), Compose 설정 2개가 모두 통과했고, 변경 Markdown 26개에서 깨진 상대 링크가 없었다.
- 명명 변경 자체가 성능 향상을 뜻하지 않는다. 성능 개선을 주장하지 않는다.
- 설정 배선 확인용으로 hot-SKU runner를 100 req/s, 10초 측정(워밍업 별도), stock 100, k6 CPU 1, 단일 호스트로 실행했다. Redis gate 기본 timeout 1회(요청 1,001건)와 `PT0.05S` override 1회(1,001건) 모두 RESERVED 100, dropped 0, workflow nonterminal 0건, 앱 오류 로그 0줄이었다. `PT0.05S`로 먼저 돌린 임시 실행(1,000건)에서 본 오류 48줄은 같은 조건의 재실행에서 재현되지 않아 원인을 확인하지 못했으며 결론의 근거로 삼지 않는다. n=1이라 성능 측정이 아니다.
- 단일 호스트 결과는 다중 노드, 실제 네트워크 분리 또는 운영 트래픽의 성능을 증명하지 않는다. 설정 항목을 줄인 결정은 실험에 없는 값을 성능 기준으로 주장하려는 것이 아니라, 근거 없는 중복 노출을 피하기 위한 유지보수성 선택이다.
