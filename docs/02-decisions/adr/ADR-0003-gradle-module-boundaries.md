# ADR-0003: 도메인 모듈과 계층 경계
- 상태: ✓ 확정
- 날짜: 2026-11-03
- 담당자: Project Owner
- 관련 결정: [D-037/D-038/D-047/D-050/D-051/D-053/D-054/D-061/D-066](../../00-governance/decision-log.md)

## 배경

ADR-0001에서 결정한 Buyer 흐름은 Campaign, Admission, Inventory, Order/Checkout, Payment 경계와 단일 배포형 modular monolith를 따른다. 기존 코드는 하나의 Gradle project에 모여 build에서 의존 경계를 보장하지 않았고, 네 도메인 service가 SQL·row mapping까지 담당했다. 사용자는 도메인별 `-module`, 명시적 계층, CQRS와 `internal` package 금지를 요구했다.

## 선택지

| 선택 | 절충 |
|---|---|
| 패키지만 분리 | 시작은 단순하나 모듈 간 compile dependency를 막지 못함 |
| 도메인별 Gradle module + 단일 실행 module — 선택 | build 의존과 소유권이 드러남. 설정과 모듈 간 API 연결이 필요하지만 운영 배포는 하나로 유지 |
| 도메인별 독립 서비스 | 독립 배포·데이터 소유 근거가 없음. 네트워크 장애와 분산 transaction까지 앞당겨 도입 |

## 결정

기존 business capability를 각각 Gradle subproject로 두고 `application-module`에서 조립한다. 후속 결정은 Catalog, Cart, Fulfillment, Settlement 경계를 확장했으며 모듈 추가나 독립 배포를 뜻하지 않는다.

| 모듈 | 소유 책임 | Gradle 의존 |
|---|---|---|
| `application-module` | Spring Boot 실행, HTTP controllers, 공통 오류 처리·설정, Flyway migration | 도메인 모듈 |
| `catalog-module` | Seller·Product·Seller-SKU·현재 가격 | `inventory-module` |
| `cart-module` | Seller별 Buyer Cart·CartItem | `catalog-module` |
| `campaign-module` | Campaign과 판매 자격 | `catalog-module`, `inventory-module` |
| `admission-module` | Queue ticket·순번·idempotency·checkout slot·만료 | `campaign-module`, `catalog-module` |
| `inventory-module` | 공유 재고 pool·reservation·수량 보존 | 없음 |
| `order-module` | Checkout·주문·OrderItem·fulfillment lifecycle·결제 조정 | `admission-module`, `cart-module`, `catalog-module`, `inventory-module`, `payment-module`, `settlement-module` |
| `payment-module` | Payment attempt·상태·Fake PSP 결과 | 없음 |
| `settlement-module` | 확인된 gross capture의 내부 이중기입 원장 | 없음 |

현재 모듈 수와 디렉터리 목록은 역사적 기록이 아닌 [README](../../../README.md#모듈-구조)를 기준으로 합니다. Gradle 의존 그래프는 해당 모듈의 `build.gradle`에서 확인합니다.

### 물리 디렉터리 구성

여섯 Gradle subproject의 물리 디렉터리는 저장소 최상위의 `ATLAS-<이름>` 형식입니다: `ATLAS-application`, `ATLAS-campaign`, `ATLAS-admission`, `ATLAS-inventory`, `ATLAS-order`, `ATLAS-payment`. `settings.gradle`의 `projectDir` mapping으로 Gradle project path (`:application-module` 등)는 유지합니다. Java package와 모듈 의존성은 바뀌지 않습니다.

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

## 결과와 재검토

- `application-module`만 실행·배포한다. Gradle dependency graph와 ArchUnit package 규칙이 모듈 방향·공개 계약을 검사한다. 소유권은 이 ADR과 [README](../../../README.md#모듈-구조)에 설명한다.
- 다섯 기능 모듈과 Buyer 흐름을 유지한다. `application-module`은 실행·HTTP·migration을 조립하고, 도메인 모듈은 JPA CRUD와 필요한 DB 특화 repository를 소유한다.
- Payment phase는 `PaymentPhase`로 명명한다. 기존 V4 migration checksum을 바꾸지 않고 V5에서 `operation` column/constraint를 `payment_phase`로 rename한다.
- 다섯 capability 경계는 ADR-0001의 승인 범위 그대로다. 독립 team/release/data ownership 또는 측정된 운영·성능 요구가 생길 때만 module 수를 재검토한다.
- JPA와 PostgreSQL SQL의 범위·절충은 ADR-0004에 기록한다. 독립 서비스 추출·추가 persistence framework는 근거와 사용자 승인 없이 진행하지 않는다.

## 근거

- Google Cloud, [Promote modular design](https://docs.cloud.google.com/architecture/framework/performance-optimization/promote-modular-design): 명확한 경계와 모듈화 비용의 균형 (REF-087).
- Gradle 9.8.2, [Multi-Project Builds](https://docs.gradle.org/9.8.2/userguide/multi_project_builds.html): subproject 의존과 이름 규칙 (REF-092).
- Alibaba COLA 작성자, [The Simplicity of COLA](https://www.alibabacloud.com/blog/the-simplicity-of-cola-an-alibaba-open-source-application-architecture_597234): 불필요한 CommandBus를 줄이고 직접 읽히는 흐름을 선택한 작성자 경험. 보편적 지침이나 ATLAS 선택의 유일한 근거는 아님 (REF-089).
- Meituan Engineering, [领域驱动设计在互联网业务开发中的实践](https://tech.meituan.com/2017/12/22/ddd-in-practice.html): 업무 의미에 따른 capability 경계 사례. ATLAS 경계나 성능을 입증하지 않음 (REF-094).
- Spring JDBC와 PostgreSQL 잠금 근거: [REF-056/057/073](../../00-governance/reference-register.md).
- 개인 실무자 참고: [REF-095](../../00-governance/reference-register.md)는 모듈 경계와 내부 계층을 따로 검토하고 복잡도에 맞게 층을 줄이는 관점. [REF-096](../../00-governance/reference-register.md)는 결제 pending/unknown에서 같은 attempt를 재사용하는 사례. 두 자료 모두 업계 표준의 증거가 아님.
- ArchUnit, [User Guide](https://www.archunit.org/userguide/html/000_Index.html): annotation·package metadata 없이 bytecode package 의존 규칙을 검사 (REF-098). 규칙은 ATLAS가 명시한 allowlist에만 적용.
