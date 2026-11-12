# ADR-0003: 도메인 모듈과 계층 경계
- 상태: ✓ 확정
- 결정일: 2026-11-03
- 담당자: Project Owner
- 관련 결정: [D-037/D-038/D-047/D-050/D-051/D-053/D-054/D-061/D-066](../../00-governance/decision-log.md)

## 배경

ADR-0001에서 결정한 Buyer 흐름은 Campaign, Admission, Inventory, Order/Checkout, Payment 경계와 단일 배포형 modular monolith를 따른다. 기존 코드는 하나의 Gradle project에 모여 build에서 의존 경계를 보장하지 않았고, 네 도메인 service가 SQL·row mapping까지 담당했다. 사용자는 도메인별 `-module`, 명시적 계층, CQRS와 `internal` package 금지를 요구했다.

## 근거와 대안

| 선택 | 절충 |
|---|---|
| 패키지만 분리 | 시작은 단순하나 모듈 간 compile dependency를 막지 못함 |
| 도메인별 Gradle module + 단일 실행 module — 선택 | build 의존과 소유권이 드러남. 설정과 모듈 간 API 연결이 필요하지만 운영 배포는 하나로 유지 |
| 도메인별 독립 서비스 | 독립 배포·데이터 소유 근거가 없음. 네트워크 장애와 분산 transaction까지 앞당겨 도입 |

## 결정

ATLAS는 Spring Boot modular monolith. `:application-module`이 다른 Gradle project를 조립·실행하며, 후속 결정은 Catalog·Cart·Fulfillment·Settlement와 Account 경계를 확장. Gradle project 추가가 독립 서비스 분리를 뜻하지 않음.

| Gradle project path | 소유 책임 |
|---|---|
| `:application-module` | Spring Boot 실행, HTTP/controller, 보안·오류 설정, Flyway |
| `:account-module` | Account, Buyer/Seller identity 연결, JWT·refresh session |
| `:catalog-module` | Seller, Product/category/SKU, 가격과 판매 channel |
| `:cart-module` | Buyer-wide Cart와 CartItem |
| `:campaign-module` | Campaign과 판매 자격 |
| `:admission-module` | FIFO queue, ticket, idempotency, checkout slot |
| `:inventory-module` | Stock pool, reservation, fulfillment location, adjustment·transfer |
| `:order-module` | BuyerCheckout, SellerOrder/OrderItem, shipment, refund·return |
| `:payment-module` | PayPal provider operation, PaymentAttempt, webhook·recovery |
| `:settlement-module` | Confirmed capture/refund ledger, Seller allocation과 statement |

Gradle dependency graph는 각 디렉터리의 `build.gradle`이 기준. `ModuleBoundaryTest`가 허용 경계를 검증하며, 모듈 간 호출은 public `.service`와 DTO 계약 사용. [README의 모듈 개요](../../../README.md#현재-기능과-모듈) 참고.

### 물리 디렉터리 구성

10개 Gradle module의 물리 디렉터리는 `ATLAS-<이름>` 형식. `settings.gradle`이 Gradle project name과 directory를 매핑하며, Java package와 dependency boundary는 module 내부 계약을 따릅니다.

| 선택지 | 절충 |
|---|---|
| 기존 `*-module` 디렉터리를 root에 유지 | 설정은 단순하지만 Gradle 모듈 이름이 실제 폴더에서 바로 드러나지 않음 |
| `modules/<이름>` 하위에 그룹화 | 여섯 모듈을 한 번 더 클릭해야 하므로 root에서 바로 찾기 어려움 |
| `ATLAS-<이름>` 디렉터리를 root에 배치 — 선택 | 저장소 root에서 여섯 모듈을 바로 구분. 기존 Gradle project/task path는 유지 |
| Gradle project path도 `ATLAS-<이름>`으로 변경 | dependency와 task references까지 갱신해야 하므로 불필요한 영향 확대 |

Java package 구조는 D-047/ADR-0003을 유지합니다. 검토 결과 중복·미사용 근거가 있는 package/class가 없어 삭제하지 않았습니다. [REF-092](../../00-governance/reference-register.md)는 Gradle `projectDir` mapping 기능을 뒷받침할 뿐, 디렉터리 구성을 보편적 best practice로 입증하지 않습니다. 구현·검증은 [PR #90](https://github.com/Neo1228/ATLAS/pull/90).

### 계층과 데이터 접근

- **공개 API:** `.service`, `.dto.request`, `.dto.response`, `.exception`. 업무 오류는 모듈별 typed exception 하나와 `Code` enum으로 표현.
- **공유 상태:** lifecycle enum은 `.persistence.state`에 두고 필요한 cross-module contract에서만 공유.
- DB에 저장하는 상태·단계 enum은 service 계약에서 사용되더라도 `.persistence.state`에 둡니다.
  Request/response record는 `.dto.request`/`.dto.response`에 두며,
  service가 소비한다는 이유만으로 workflow enum을 `.service`에 두지 않습니다.
- **저장소 계층:** JPA entity는 `.persistence.entity`, repository는 모듈 내부 `.repository`. 다른 모듈의 entity/repository 직접 접근 금지; 연관 관계에는 scalar ID 사용.
- **구현 계층:** workflow와 scheduled expiry는 `.service.impl`, provider 구현은 `.provider.*`. Entity 이름에 중복 `Entity` 접미사 금지.
- **도메인 명칭:** 책임이 드러나는 용어 사용. Inventory의 일반명 `Pool`은 `InventoryPool`로 구체화. Java 타입/ID/repository/DTO/service operation만 변경; DB schema와 API 응답 필드는 유지 ([D-053](../../00-governance/decision-log.md)).
- **CRUD·schema:** 일반 CRUD는 JPA. Schema는 Flyway 소유, Hibernate는 `ddl-auto=validate`.
- **특화 SQL:** PostgreSQL 전용 lock·sequence·guarded update만 `.repository`에서 JDBC/native SQL로 처리.
- **HTTP:** controller·transport DTO·설정·오류 변환은 `application-module` 소유. HTTP/domain request 분리는 입력 모양이나 validation 규칙이 다를 때만 허용.
- **Transaction:** 불변조건 기준으로 경계를 정함. Admission counter+ticket, Inventory pool+reservation은 한 transaction; PSP 호출은 DB transaction 밖.
- **금지 pattern:** CQRS, CommandBus, handler/mediator, `internal`, `package-info.java` marker 금지. 새 pattern은 구체 요구와 ADR 승인 필요.
- **Test:** consumer-visible behavior, module boundary, state transition, error contract, concurrency 검증에 한정. PostgreSQL 전용 동작은 PostgreSQL로 검증; wiring/중복 위험 테스트 금지 ([REF-099](../../00-governance/reference-register.md)).

## 결과와 한계

- `application-module`은 Spring Boot application을 조립·실행. Gradle dependency graph와 ArchUnit test가 module 방향·public contract를 검사. 현재 module 책임은 [README](../../../README.md#현재-기능과-모듈) 기준.
- JPA와 PostgreSQL SQL의 범위·절충은 ADR-0004에 기록한다. 독립 서비스 추출·추가 persistence framework는 근거와 사용자 승인 없이 진행하지 않는다.

## 판단 근거

- Google Cloud, [Promote modular design](https://docs.cloud.google.com/architecture/framework/performance-optimization/promote-modular-design): 명확한 경계와 모듈화 비용의 균형 (REF-087).
- Gradle 9.8.2, [Multi-Project Builds](https://docs.gradle.org/9.8.2/userguide/multi_project_builds.html): subproject 의존과 이름 규칙 (REF-092).
- Alibaba COLA 작성자, [The Simplicity of COLA](https://www.alibabacloud.com/blog/the-simplicity-of-cola-an-alibaba-open-source-application-architecture_597234): 불필요한 CommandBus를 줄이고 직접 읽히는 흐름을 선택한 작성자 경험. 보편적 지침이나 ATLAS 선택의 유일한 근거는 아님 (REF-089).
- Meituan Engineering, [领域驱动设计在互联网业务开发中的实践](https://tech.meituan.com/2017/12/22/ddd-in-practice.html): 업무 의미에 따른 capability 경계 사례. ATLAS 경계나 성능을 입증하지 않음 (REF-094).
- Spring JDBC와 PostgreSQL 잠금 근거: [REF-056/057/073](../../00-governance/reference-register.md).
- 개인 실무자 참고: [REF-095](../../00-governance/reference-register.md)는 모듈 경계와 내부 계층을 따로 검토하고 복잡도에 맞게 층을 줄이는 관점. [REF-096](../../00-governance/reference-register.md)는 결제 pending/unknown에서 같은 attempt를 재사용하는 사례. 두 자료 모두 업계 표준의 증거가 아님.
- ArchUnit, [User Guide](https://www.archunit.org/userguide/html/000_Index.html): annotation·package metadata 없이 bytecode package 의존 규칙을 검사 (REF-098). 규칙은 ATLAS가 명시한 allowlist에만 적용.
