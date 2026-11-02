# ADR-0001: 초기 아키텍처 방향
- 상태: ✓ 구조·기술 방향 승인. 구현·배포는 별도 승인 대상.
- 승인일: 2026-11-02
- 담당: Project Owner
- 관련 요구사항: [FR-001~003](../../02-requirements/requirements-index.md), [NFR-001~002](../../02-requirements/requirements-index.md)

## 배경
- 주문·재고·결제·정산의 정합성과 장애 복구를 검증할 구조 필요.
- 팀 규모, traffic, 배포 제약 미정. 독립 서비스 운영 근거 없음.

## 판단 기준
- Module별 data ownership·transaction boundary 검증 가능성.
- 낮은 운영 부담과 재현 가능한 integration/failure test.
- 별도 queue/cache/broker는 측정된 bottleneck과 비교 가능한 이점이 있을 때만 검토.
- 장기 범위 A/B/C 유지.

## 대안과 trade-off

| 대안 | 설명 | 이점 | 비용·위험 |
|---|---|---|---|
| A. Modular Monolith First (선택) | 한 deployment 안에서 module boundary와 data ownership 유지 | transaction·test 단순화, 경계 명확화 | 독립 확장·배포 제한, 경계 약화 위험 |
| B. Microservices | 도메인별 service·deployment·storage 분리 | 독립 배포·확장 | network failure, distributed transaction, 운영 부담 증가 |
| C. 단순 monolith | 단일 application, 강제된 module/data boundary 없음 | 시작이 단순 | coupling 증가, 변경·검증 어려움 |

## 승인된 방향
- **Architecture:** Modular Monolith First. 한 deployment 안에서 module boundary와 data ownership 유지.
- **Stack:** JDK 25 LTS, Spring Boot 4.1.2, PostgreSQL 18.6, Gradle Wrapper 9.8.2. Spring Modulith 2.1.2 and Testcontainers 2.0.5 are test-scope; Flyway uses the Boot 4 starter ([D-040](../../00-governance/decision-log.md), [REF-066/067](../../00-governance/reference-register.md)). CI uses Temurin 25; production JDK distribution/license remains OPEN.
- PostgreSQL is the durable system of record. Module ownership and short-transaction rules are [D-038](../../00-governance/decision-log.md); campaign counter-row allocation is [D-039](../../00-governance/decision-log.md). Local schema/migrations are implemented under [Issue #72](https://github.com/Neo1228/ATLAS/issues/72); production isolation and lock recovery remain OPEN.
- 초기 설계에 별도 queue/cache/broker 없음. Load test에서 bottleneck과 비교 가능한 이점을 확인한 경우에만 추가 검토 ([EXP-012](../../08-experiments/experiment-registry.md)).
- Gate 2 current Buyer journey와 Gate 3 design 기준 승인. [Issue #72](https://github.com/Neo1228/ATLAS/issues/72)에서 local current-flow runtime만 추가 승인; live payment·deployment·production capacity claim은 별도 대상.

## 상위 아키텍처

```mermaid
flowchart LR
    Buyer[Buyer / Seller] --> App["ATLAS application<br/>Java + Spring Boot<br/>single deployment"]
    App --> DB[("PostgreSQL<br/>durable system of record")]
    App --> PSP["Fake PSP<br/>local only; live API OPEN"]
    App -. "load test에서 benefit 확인 시" .-> Optional["Optional queue / cache / broker"]
```

## Gate 3 초기 module·data 설계
Module scope는 현재 Buyer 흐름에 한정. 이름·경계는 실제 invariant와 dependency 기준으로 검증; 장기 capability 전체를 지금 module로 고정하지 않음.

| Module | 현재 slice의 책임 | 경계 |
|---|---|---|
| Campaign | 현재 campaign/SKU와 sale-open 상태 | Eligibility 정책은 OPEN |
| Admission | Ticket, key, sequence, retry·re-entry | 순번 기록은 D-039 |
| Inventory | Pool, reservation, claim/release, 재고 conservation | Lock 전략은 EXP-002에서 비교 |
| Order/Checkout | Buyer 흐름 orchestration, test window, order state transitions | PSP operation state는 Payment module 소유 |
| Payment | Provider operation state, AUTHORIZE/CAPTURE, reconciliation | PayPal product/API는 OPEN |

Tenant security, 확장 catalog, Settlement/Reconciliation은 장기 capability map에서 유지. 현재 module contract·구현 범위로 확정하지 않음.

```mermaid
flowchart LR
    Buyer[Buyer] --> Admission
    Admission -->|"public eligibility API"| Campaign
    Campaign -->|"public pool-creation API"| Inventory
    Buyer --> Checkout["Order / Checkout"]
    Checkout -->|"public API"| Admission
    Checkout -->|"public API"| Inventory
    Checkout -->|"public API"| Payment
    Campaign --> DB[("PostgreSQL<br/>one database / schema")]
    Admission --> DB
    Inventory --> DB
    Checkout --> DB
    Payment --> DB
    Payment --> PSP["Fake PSP<br/>local only; live API OPEN"]
```

- **Data ownership:** 한 PostgreSQL database/schema. Module별 table과 write ownership, cross-module 호출은 public API. 다른 module의 repository에 직접 접근 금지.
- **Transaction:** 필요한 domain invariant만 원자화하는 짧은 transaction. Module API는 같은 local transaction에 참여할 수 있으나, PSP network call은 transaction 밖. Isolation, 실제 경계·schema·lock recovery는 미정.
- **Queue sequence:** Admission initializes/locks its campaign counter row and inserts the ticket in the same transaction ([D-039](../../00-governance/decision-log.md)). First eligible join creates the row lazily; committed order is stored in PostgreSQL. Same-campaign contention remains an approved workload measurement target, not an observed result. PostgreSQL sequence gaps/commit-order mismatch make it unsuitable here ([REF-057](../../00-governance/reference-register.md)).
- **Boundary checks:** Spring Modulith 2.1.2 test scope; `ApplicationModules.verify()` is part of Gradle checks ([REF-055/063/064](../../00-governance/reference-register.md)). It verifies package boundaries/cycles, not domain correctness or performance.
- Capacity envelope는 승인된 Q-008 profile 사용. 단위·계산·미정 입력은 아래 표에 정리; production target은 없음.
- Local inventory baseline: atomic conditional `UPDATE` and per-order transaction lock. Production lock strategy/performance comparison remains [EXP-002](../../08-experiments/experiment-registry.md); no capacity claim.
- Local runtime code and failure-path tests are tracked by [Issue #72](https://github.com/Neo1228/ATLAS/issues/72). Live provider, deployment, and load verification remain separate.

## Capacity envelope — synthetic, report-only

| 항목 | 현재 입력·계산 | 해석 |
|---|---|---|
| Session arrival | 10,000 sessions/60m ≈ 2.8 sessions/s; 2×/4× ≈ 5.6/11.1 sessions/s ([D-031~D-033](../../00-governance/decision-log.md)) | 합성 input. production demand나 처리량 목표 아님 |
| Stage request rate | Stage별 offered/achieved RPS를 측정 | sessions/s만으로 계산 불가. Session당 request 수와 phase별 분포 필요 |
| Completed-session TPS | 측정 구간 내 완료된 Buyer session / 초 | offered arrival rate와 별도. PENDING/UNKNOWN은 완료가 아니라 in-flight/backlog |
| In-flight concurrency | 안정 구간에서 `L ≈ λ × W` ([REF-022](../../00-governance/reference-register.md)) | 같은 stage의 arrival rate λ와 latency W 필요. 현재 W 미측정이므로 숫자 산출 불가 |
| DB QPS | 선언한 query 범위의 query/초만 기록 | API RPS와 다른 단위. 현재 DB QPS 실측 없음 |

10k/60m과 2×/4×는 report-only synthetic profile. Burst duration, think time, phase, concurrency, request mix, test environment, resource configuration, production SLO는 OPEN ([Q-008](../../00-governance/assumptions-and-open-questions.md)). 따라서 현재는 resource sizing이나 production capacity를 주장하지 않음. Baseline 대비 stress 결과와 bottleneck을 비교하는 설계 기준만 제공 ([EXP-010/012](../../08-experiments/experiment-registry.md)).

## 재검토 조건
- 독립된 team/deployment 필요 발생.
- Module 간 변경·장애 결합 증가.
- Load test에서 단일 deployment 또는 PostgreSQL bottleneck 반복 확인.
- Data ownership 변경 또는 실측 결과가 현재 가설을 반증.

## 근거와 한계
- [REF-023](../../00-governance/reference-register.md): Shopify의 inventory 사례는 MySQL·Shopify 환경에 한정. PostgreSQL schema나 처리량 목표로 일반화하지 않음.
- [REF-050/052~055](../../00-governance/reference-register.md): modular monolith, boundary, package 설계 사례. Shopify 회고는 domain 이름만으로 runtime dependency를 추정하지 말라고 경고. Thoughtworks 자료는 설계 의견이며 benchmark 아님. Spring Modulith는 경계 검사 도구 후보일 뿐.
- [REF-056~058](../../00-governance/reference-register.md): PostgreSQL lock/sequence semantics와 vendor inventory 사례. 배포 PostgreSQL version은 OPEN. Vendor 성능 주장은 독립 검증되지 않음.
