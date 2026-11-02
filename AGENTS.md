# ATLAS 작업 진입 규칙

## 목적과 범위
- 목적: 주문·재고·결제·환불·정산의 정합성 및 장애 복구 검증.
- 장기 범위: A 결제 신뢰성, B 고동시성 한정판매, C 정산·대사.
- 사실과 합성 실험 구분. 사용자·거래·운영 성과를 근거 없이 주장하지 않음.

## 진행 원칙
- 짧고 스캔하기 쉬운 문장. 자연스러운 경우 명사형/개조식 종결, 조사·수식어 최소화. 수치/단계는 표·불릿.
- 상태 구분: `✓ Confirmed`, `△ Proposed`, `? OPEN`, `ASSUMPTION`, `Measured`. 사용자 승인 전 제안을 확정으로 변경하지 않음.
- 미결정 정책은 [Assumptions & Open Questions](docs/00-governance/assumptions-and-open-questions.md)에 기록.
- 진행 상태: Gate 0/1 완료, Gate 2 Buyer 기준 승인, Gate 3 architecture/stack 기준 확정. Issue #72의 local current-flow runtime 승인; live provider, deployment, production capacity/SLO는 별도 승인·검증 대상.
- 코드 변경 전 요구사항·정책·검증 계약 확인. 중요한 설계 선택은 ADR로 기록.
- library·plugin 도입은 회피하거나 자동 선택하지 않음. 기존 JDK/Spring/DB 기능, 유지관리 중인 library, 직접 구현을 비교. 성숙한 library가 요구 기능·호환성·운영 비용에 맞으면 직접 재구현보다 우선 사용. capability gap, 대안, maintenance·security·license 비용, runtime/test/build scope를 Issue/PR에 기록.
- Repository split은 기본 금지. 독립 release/team/data ownership 또는 측정된 scaling·security 경계와 승인된 ADR이 있을 때만 분리.
- Java implementation code: FQCN 금지, import 사용. Gradle `check`의 Checkstyle 검사로 강제.
- Java 주석은 자연스러운 한국어 우선. 표준 type/API/protocol 이름은 English 유지. 주석은 코드 반복 대신 이유·invariant·외부 제약 설명.
- 각 도메인은 하나의 `*Exception`과 `Code` enum으로 업무 실패를 표현하고, `application-module`의 단일 `application.exception.GlobalExceptionHandler`가 이를 RFC 9457 `ProblemDetail` + stable `code`로 변환. Raw `IllegalArgumentException`을 client validation 응답으로 노출하지 않음.
- HTTP GET/read query는 domain state를 변경하지 않음. 만료·retry·promotion은 상태 변경 요청 또는 scheduler 소유; 조회는 last persisted state 반환.
- Cross-cutting utility를 위해 `common` package/module을 선제 생성하지 않음. Clock/error mapping처럼 실제 application-wide policy만 root composition에 두고, 반복된 domain ownership이 생기면 ADR 검토.
- 도메인 코드는 `<capability>-module`에 둠. 직접 하위 `.service`는 contract, `.dto.request`는 입력, `.dto.response`는 출력, `.exception`은 모듈 오류. Lifecycle state enum은 `.persistence.state`에서 소유하고 필요한 cross-module contract에 공유하되, JPA entity/repository는 공개하지 않음. 구현·scheduled workflow는 `.service.impl`, JPA mapping/CRUD는 `.persistence.entity`·`.persistence.repository`, provider는 nested `.provider.*`. HTTP는 `application-module`의 `.application.controller.<domain>`·`.application.controller.dto`; 공통 설정·오류도 application 소유. Direct/nested package 경계로 public contract와 내부 구현을 구분하며 `package-info.java` marker 금지.
- CRUD는 JPA; 근거 있는 PostgreSQL 동시성 SQL만 repository 내부 JDBC/native query로 작성. Flyway가 schema를 소유하고 Hibernate는 `validate`만 수행. 범용 base, mapper, forwarding-only scheduler, speculative layer 금지.
- CQRS·CommandBus·command/query handler·mediator 및 `internal` 금지. Module input은 `*Request` record. HTTP request record는 transport field shape/validation이 domain request와 다를 때만 별도로 둠; mapping 없는 거울 DTO 금지.
- 문서·Issue·PR은 프로젝트 맥락을 모르는 독자를 기준으로 작성. 내부 shorthand/ID만 나열하지 말고, 복잡한 변경은 배경·사실/가정·선택 이유·대안·영향·검증을 본문에 설명하고 기준 문서에 연결.
- 흐름/상태가 복잡한 경우 Mermaid 등으로 시각화. 중복 그림 금지, 미정 경계 표시. 단순 변경에 도식 강제 금지.
- 테스트는 소비자 관찰 가능 동작, 경계, 상태 전이, 불변조건, 동시성, 오류 계약에서 아직 막지 못한 구체적 회귀 위험이 있을 때만 추가. Wiring·forwarding·단순 mapping·우연한 기본값·중복 assertion만 고정하는 테스트 금지; 같은 동작을 반복 검증하는 테스트는 제거. PostgreSQL SQL/locking 의미가 대상이면 실제 PostgreSQL 경계에서 검증하고, 그 외에는 동작을 증명하는 가장 작은 테스트 경계를 사용. Mock은 fidelity 위험이 있을 때 피할 수 있지만 전면 금지하지 않음 ([REF-099](docs/00-governance/reference-register.md)).
- PR 템플릿 모든 체크박스 완료. 병합 전 `Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks` 통과.
- 외부 PSP 상태와 주문 상태, 내부 정산과 실제 지급 구분. 초기 범위에서 실결제·개인정보·판매사 지급 제외.
- 모든 작업은 Issue, trace ID, 변경 파일, 검증, commit/PR로 추적. 같은 목적의 후속 선택은 기존 Issue에서 이어가기.
- 독립된 목적·완료 기준·위험 경계만 분리. 병합 PR은 plain-text Closes #N, 일부 반영은 Refs #N; 같은 줄에 Issue link 추가.
- Markdown link 안에 close keyword를 넣지 않음. 필수 checks 통과 후 실제 merge SHA와 Issue 상태 기록.

## 진입점
- 문서 인덱스: [docs/README.md](docs/README.md)
- 프로젝트 범위: [Project Charter](docs/00-governance/project-charter.md)
- 운영 기준: [문서 인덱스](docs/README.md), [Change Control](docs/00-governance/change-control.md), [CONTRIBUTING](CONTRIBUTING.md). 승인·범위 변경은 사용자와 먼저 협의.
