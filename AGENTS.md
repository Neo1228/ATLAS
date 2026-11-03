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
- Git 저장소 분리는 기본 보류. 독립 release/team/data ownership 또는 측정된 scaling·security 경계와 승인된 ADR이 있을 때만 검토.
- Java implementation code: FQCN 금지, import 사용. Gradle `check`의 Checkstyle 검사로 강제.
- Java 주석은 자연스러운 한국어 우선. 표준 type/API/protocol 이름은 English 유지. 주석은 코드 반복 대신 이유·invariant·외부 제약 설명.
- 각 도메인은 하나의 `*Exception`과 `Code` enum으로 업무 실패를 표현하고, `application-module`의 단일 `application.exception.GlobalExceptionHandler`가 이를 RFC 9457 `ProblemDetail` + stable `code`로 변환. Raw `IllegalArgumentException`을 client validation 응답으로 노출하지 않음.
- HTTP GET/read query는 domain state를 변경하지 않음. 만료·retry·promotion은 상태 변경 요청 또는 scheduler 소유; 조회는 last persisted state 반환.
- Cross-cutting utility를 위해 `common` package/module을 선제 생성하지 않음. Clock/error mapping처럼 실제 application-wide policy만 root composition에 두고, 반복된 domain ownership이 생기면 ADR 검토.
- Public API: `.service`, `.dto.request`, `.dto.response`, `.exception`.
- 공유 lifecycle state는 `.persistence.state`; JPA entity는 `.persistence.entity`, repository는 module-private `.repository`. 다른 모듈의 entity/repository 직접 참조 금지.
- Workflow와 scheduled expiry는 `.service.impl`; provider는 `.provider.*`. HTTP adapters·transport DTO·설정·공통 오류는 `application-module` 소유.
- CRUD는 JPA, PostgreSQL 전용 동시성 SQL은 `.repository`의 JDBC/native query. Flyway owns schema; Hibernate `validate`. Generic base/mapper/forwarding scheduler 금지.
- CQRS·CommandBus·command/query handler·mediator 및 `internal` 금지. Module input은 `*Request` record. HTTP request record는 transport field shape/validation이 domain request와 다를 때만 별도로 둠; mapping 없는 거울 DTO 금지.
- 문서·Issue·PR은 프로젝트 맥락을 모르는 독자를 기준으로 작성. 내부 shorthand/ID만 나열하지 말고, 복잡한 변경은 배경·사실/가정·선택 이유·대안·영향·검증을 본문에 설명하고 기준 문서에 연결.
- 흐름/상태가 복잡한 경우 Mermaid 등으로 시각화. 중복 그림 금지, 미정 경계 표시. 단순 변경에 도식 강제 금지.
- 테스트는 아직 막지 못한 소비자 동작·계약·상태·불변조건·동시성·오류의 구체적 회귀 위험을 대상으로 함.
- Wiring, forwarding, 단순 mapping, incidental defaults, 중복 assertion은 테스트하지 않음. 동일 위험을 여러 layer에서 반복 검증하지 않음.
- PostgreSQL SQL/locking은 실제 PostgreSQL 경계에서 검증. 나머지는 가장 작은 유효 경계 사용. Mock은 fidelity 손실에 따라 판단 ([REF-099](docs/00-governance/reference-register.md)).
- 외부 PSP 상태와 주문 상태, 내부 정산과 실제 지급 구분. 초기 범위에서 실결제·개인정보·판매사 지급 제외.

## GitHub 작업 및 변경 관리
- Issue는 제공된 양식으로 작성하고 요구사항·근거·대안·trade-off·영향·검증을 기록. 복잡한 작업은 식별자별 근거를 연결하고, 실제로 실행하지 않은 검증은 성공으로 기록하지 않음.
- 변경 전 `git status --short --branch`; stage 전후 `git diff`, `git diff --cached`, `git status` 확인. 관련 경로만 stage하고 확인 전 `git add .`/`git add -A` 금지.
- 기본 branch는 `main`, 직접 push 금지(최초 seed 제외). Branch 이름 `<type>/<primary-issue>-<short-topic>`; type은 `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `build`, `chore`, `revert`, topic은 ASCII 소문자 kebab-case. 한 branch는 한 목적.
- Commit 제목은 `<type>(<scope>): <한국어 요약>`(scope 생략 가능), type/scope 영문 소문자, 짧은 명령형 요약·마침표 없음. 한 commit은 한 논리 변경; 이유가 불분명하거나 정책/호환성 영향이 있으면 본문에 이유·제약·추적 ID 기록. 과거 제목 수정은 기본 금지.
- 한 목적·완료 기준·검토 경계의 Issue와 commit은 PR 하나로 묶고, 같은 목적의 후속 결정은 기존 Issue에 누적. 독립 목적·위험·책임자·완료 기준·배포/복구 경계만 분리.
- PR template 필수. 모든 checklist 항목 완료; N/A에는 구체적인 사유 작성.
- 복잡한 변경은 근거·대안·영향·검증 설명; Mermaid는 복잡한 flow/state에만 사용.
- `main`은 PR과 strict required checks(`Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks`), linear history 필수; 관리자도 적용.
- Required reviewers 0명; 승인 리뷰 정책은 별도 합의 필요. Merge는 squash only; branch logical commits는 보존, `main`에는 squash commit 하나 반영.
- 공유 이력 amend/rebase, 무승인 force push, `main` 직접 push, 미추적 작업을 지우는 reset/clean 금지. 승인된 예외는 별도 Issue, 대상 SHA, 백업 ref, `--force-with-lease`, protection 복원 기록 필요. 충돌 해결 뒤 diff 및 관련 lint/test 재실행.
- Git 작성자 정보는 local repository에만 설정하고 GitHub no-reply 주소 사용. API key/token, 개인정보, 실제 결제 정보는 코드·로그·Issue/PR에 기록 금지; placeholder만 사용. 유출 시 즉시 폐기/교체.
- 실제 Issue/commit/PR 상태·SHA·검증만 [Milestone Log](docs/09-delivery/milestone-log.md)와 [Session Handoff](docs/09-delivery/session-handoff.md)에 기록. 병합 및 CI 확인 뒤 별도 branch에서 다음 작업 시작. Gate 종료·범위 변경은 사용자 승인 필요.
## 진입점
- 문서 인덱스: [docs/README.md](docs/README.md)
- 프로젝트 범위: [Project Charter](docs/00-governance/project-charter.md)
- 운영 기준: [문서 인덱스](docs/README.md), [Change Control](docs/00-governance/change-control.md), [GitHub 작업 및 변경 관리](AGENTS.md#github-작업-및-변경-관리).
