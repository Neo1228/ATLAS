# ADR-0003: 도메인 모듈과 계층 경계
- 상태: ✓ Accepted
- 날짜: 2026-11-03
- 담당자: Project Owner
- 관련 결정: [D-037/D-038/D-047/D-050/D-051/D-053/D-054](../../00-governance/decision-log.md)

## 배경

ADR-0001에서 현재 Buyer 흐름의 Campaign, Admission, Inventory, Order/Checkout, Payment 경계와 단일 배포형 modular monolith를 결정했다. 기존 코드는 하나의 Gradle project에 모여 있어 의존 경계가 build에서 보장되지 않았고, 네 도메인 service가 SQL·row mapping까지 맡고 있었다. 사용자는 도메인별 `-module`, 명시적 계층, CQRS와 `internal` package 금지를 요구했다.

## 선택지

| 선택 | 절충 |
|---|---|
| 패키지만 분리 | 시작은 단순하나 모듈 간 compile dependency를 막지 못함 |
| 도메인별 Gradle module + 단일 실행 module — 선택 | build 의존과 소유권이 드러남. 설정과 모듈 간 API 연결이 필요하지만 운영 배포는 하나로 유지 |
| 도메인별 독립 서비스 | 독립 배포·데이터 소유 근거가 없음. 네트워크 장애와 분산 transaction까지 앞당겨 도입 |

## 결정

기존 다섯 business capability를 각각 Gradle subproject로 두고 `application-module`에서 조립한다. 모듈 추가·독립 배포를 뜻하지 않는다.

| Module | 소유 책임 | Gradle 의존 |
|---|---|---|
| `application-module` | Spring Boot, HTTP adapters, root error mapping, configuration, Flyway migrations | 다섯 도메인 모듈 |
| `campaign-module` | Seller·Seller-SKU 등록과 Campaign 자격·생성 | `inventory-module` |
| `admission-module` | Queue ticket·순번·idempotency·checkout slot·expiry | `campaign-module` |
| `inventory-module` | Seller-SKU inventory pool/reservation 및 재고 보존 규칙 | 없음 |
| `order-module` | Checkout·order lifecycle orchestration | `admission-module`, `inventory-module`, `payment-module` |
| `payment-module` | Payment attempts·상태·provider 결과 | 없음 |

- Seller, Seller-SKU, Campaign은 현재 구매 흐름에서 함께 사용. 별도 업무 흐름·API·데이터 소유 경계가 확인되지 않아 `campaign-module`에 유지; 독립된 경계가 생길 때 모듈 분리 검토 ([D-054](../../00-governance/decision-log.md)).

### 물리 디렉터리 구성

여섯 Gradle subproject의 물리 디렉터리는 저장소 root의 `ATLAS-<이름>` 형식을 사용한다: `ATLAS-application`, `ATLAS-campaign`, `ATLAS-admission`, `ATLAS-inventory`, `ATLAS-order`, `ATLAS-payment`. `settings.gradle`의 `projectDir` mapping으로 Gradle project path (`:application-module` 등)는 유지한다. Java package와 module dependency도 변경하지 않는다.

| 선택지 | 절충 |
|---|---|
| 기존 `*-module` 디렉터리를 root에 유지 | 설정은 단순하지만 Gradle 모듈 이름이 실제 폴더에서 바로 드러나지 않음 |
| `modules/<이름>` 하위에 그룹화 | 여섯 모듈을 한 번 더 클릭해야 하므로 root에서 바로 찾기 어려움 |
| `ATLAS-<이름>` 디렉터리를 root에 배치 — 선택 | 저장소 root에서 여섯 모듈을 바로 구분. 기존 Gradle project/task path는 유지 |
| Gradle project path도 `ATLAS-<이름>`으로 변경 | dependency와 task references까지 갱신해야 하므로 불필요한 영향 확대 |

Java package 구조는 D-047/ADR-0003 그대로 유지. 감사 결과 중복·미사용 근거가 있는 package/class가 없어 삭제하지 않음. [REF-092](../../00-governance/reference-register.md)는 Gradle `projectDir` mapping 지원을 확인하는 근거; folder grouping 자체를 보편적 best practice라 주장하지 않음. 구현·검증은 [PR #90](https://github.com/Neo1228/ATLAS/pull/90).

### 계층과 데이터 접근

- 공개 API: `.service`, `.dto.request`, `.dto.response`, `.exception`. 업무 오류는 모듈별 typed exception 하나와 `Code` enum으로 표현.
- 공유 상태: lifecycle enum은 `.persistence.state`에 두고 필요한 cross-module contract에서만 공유.
- 저장소 계층: JPA entity는 `.persistence.entity`, repository는 모듈 내부 `.repository`. 다른 모듈의 entity/repository 직접 접근 금지; 연관 관계는 scalar ID 사용.
- 구현 계층: workflow와 scheduled expiry는 `.service.impl`, provider adapter는 `.provider.*`. Entity 이름에 중복 `Entity` 접미사 금지.
- 도메인 명칭은 책임이 드러나는 실무 용어를 사용한다. Inventory 모듈의 일반명 `Pool`은 `InventoryPool`로 구체화; Java 타입/ID/repository/DTO/service operation만 변경하고 DB schema와 API 응답 필드는 유지 ([D-053](../../00-governance/decision-log.md)).
- 기본 CRUD는 JPA. Schema는 Flyway 소유, Hibernate는 `ddl-auto=validate`.
- PostgreSQL 전용 lock·sequence·guarded update만 `.repository`에서 JDBC/native SQL로 처리.
- HTTP controller·transport DTO·설정·오류 변환은 `application-module` 소유. HTTP/domain request 분리는 입력 모양이나 validation 규칙이 다를 때만 허용.
- Transaction 경계는 불변조건 기준. Admission counter+ticket, Inventory pool+reservation은 한 transaction; PSP 호출은 DB transaction 밖.
- CQRS, CommandBus, handler/mediator, `internal`, `package-info.java` marker 금지. 새 pattern은 구체 요구와 ADR 승인 필요.
- Test는 consumer-visible behavior, module boundary, state transition, error contract, concurrency 검증에 한정. PostgreSQL 특화 동작은 PostgreSQL로 검증; wiring/중복 위험 테스트 금지 ([REF-099](../../00-governance/reference-register.md)).

## 결과와 재검토

- `application-module`만 실행·배포한다. Gradle dependency graph와 ArchUnit package rules가 module 방향·공개 contract를 검사한다; 각 module README가 소유권을 설명한다.
- 기존 다섯 capability 경계와 Buyer 흐름을 유지한다. `application-module`은 실행·HTTP·migration 조립, 각 domain module은 JPA CRUD와 필요한 DB 특화 repository만 소유한다.
- Payment phase는 `PaymentPhase`로 명명한다. 기존 V4 migration checksum을 바꾸지 않고 V5에서 `operation` column/constraint를 `payment_phase`로 rename한다.
- 다섯 capability 경계는 ADR-0001의 승인 범위 그대로다. 독립 team/release/data ownership 또는 측정된 운영·성능 요구가 생길 때만 module 수를 재검토한다.
- JPA와 PostgreSQL SQL의 범위·절충은 ADR-0004에 기록. 독립 서비스 추출·추가 persistence framework는 근거와 사용자 승인 없이 진행하지 않는다.

## 근거

- Google Cloud, [Promote modular design](https://docs.cloud.google.com/architecture/framework/performance-optimization/promote-modular-design): 명확한 경계와 modularity 비용 균형 (REF-087).
- Gradle 9.8.2, [Multi-Project Builds](https://docs.gradle.org/9.8.2/userguide/multi_project_builds.html): subproject 의존과 이름 규칙 (REF-092).
- Alibaba COLA 작성자, [The Simplicity of COLA](https://www.alibabacloud.com/blog/the-simplicity-of-cola-an-alibaba-open-source-application-architecture_597234): CommandBus 의존 제거·직접 읽히는 흐름의 작성자 경험; 보편적 지침이나 ATLAS 선택의 유일한 근거는 아님 (REF-089).
- Meituan Engineering, [领域驱动设计在互联网业务开发中的实践](https://tech.meituan.com/2017/12/22/ddd-in-practice.html): 업무 의미 기반 capability 경계 사례; ATLAS 경계나 성능을 입증하지 않음 (REF-094).
- Spring JDBC 선택과 PostgreSQL locking 근거: [REF-056/057/073](../../00-governance/reference-register.md).
- 개인 실무자 참고: [REF-095](../../00-governance/reference-register.md)는 module boundary와 내부 계층을 별도 검토하고 복잡도에 맞춰 층을 줄이는 관점, [REF-096](../../00-governance/reference-register.md)는 결제 pending/unknown과 같은 attempt 재사용 사례. 둘 다 업계 표준의 증거가 아니라 참고 사례.
- ArchUnit, [User Guide](https://www.archunit.org/userguide/html/000_Index.html): bytecode-based package dependency rules without annotations or package metadata (REF-098). Rules describe only ATLAS's explicit allowlist.
