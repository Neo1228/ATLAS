# ATLAS 작업 진입 규칙

## 목적과 범위
- 목적: 주문·재고·결제·환불·정산의 정합성 및 장애 복구 검증.
- 장기 범위: A 결제 신뢰성, B 고동시성 한정판매, C 정산·대사.
- 사실과 합성 실험 구분. 사용자·거래·운영 성과를 근거 없이 주장하지 않음.

## 진행 원칙
- 문서는 프로젝트를 처음 보는 독자 기준으로 작성. 짧고 훑기 쉬운 개조식 문장, 자연스러운 한국어 우선. 표준 기술명·API·코드 식별자만 원문 표기.
- 상태 표기: `✓ 확정`, `△ 제안`, `? 미정`, `가정`, `실측`. 사용자 승인 전 제안을 확정하지 않음.
- 장기 범위, PSP, 개인정보·금전 정책, 핵심 도메인·아키텍처, Gate 전환은 사전 승인 대상.
- 정책·승인 기준은 [요구사항](docs/01-requirements/requirements-index.md)과 이 파일의 기술 규칙을 따름. 현재 Gate 상태는 root [README](README.md) 참조.
- 중요한 아키텍처·데이터 선택과 대안 이력은 [결정 로그](docs/00-governance/decision-log.md)에 기록. ADR ID와 상세 근거는 해당 문서의 절에서 관리.
- 코드 변경 전 요구사항·정책·검증 계약 확인.
- library·plugin 도입을 무조건 피하거나 자동 선택하지 않음. 기존 JDK/Spring/DB 기능, 유지관리 중인 library, 직접 구현을 비교. 요구 기능·호환성·운영 비용에 맞는 성숙한 library가 있으면 직접 재구현보다 우선. 기능 공백, 대안, 유지보수·보안·라이선스 비용, runtime/test/build 범위를 Issue/PR에 기록.
- Git 저장소 분리는 기본 보류. 독립 release/team/data ownership 또는 측정된 scaling·security 경계와 승인된 ADR이 있을 때만 검토.
- Java 구현 코드에는 FQCN 대신 import 사용. Gradle `check`의 Checkstyle 검사로 강제.
- Java 주석은 자연스러운 한국어 우선. 표준 type/API/protocol 이름은 원문 유지. 코드 설명을 반복하지 말고 이유·불변조건·외부 제약을 기록.
- 각 도메인은 업무 실패용 `*Exception` 하나와 `Code` enum을 제공. `application-module`의 `application.exception.GlobalExceptionHandler`가 RFC 9457 `ProblemDetail`과 안정된 `code`로 변환. `IllegalArgumentException`을 client validation 응답으로 노출하지 않음.
- HTTP GET/read query는 domain state를 변경하지 않음. 만료·retry·promotion은 상태 변경 요청 또는 scheduler가 처리. 조회는 마지막으로 저장된 상태 반환.
- Cross-cutting utility를 위해 `common` package/module을 선제 생성하지 않음. Clock/error mapping처럼 실제 application-wide policy만 root composition에 두고, 반복된 domain ownership이 생기면 ADR 검토.
- Public API: `.service`, `.dto.request`, `.dto.response`, `.exception`.
- 공유 lifecycle state는 `.persistence.state`; JPA entity는 `.persistence.entity`; repository는 모듈 내부 `.repository`. 다른 모듈의 entity/repository 직접 참조 금지.
- workflow·scheduled expiry는 `.service.impl`, provider는 `.provider.*`에 배치. HTTP adapter·전송 DTO·설정·공통 오류는 `application-module` 소유.
- PostgreSQL/native SQL 쓰기는 해당 모듈이 소유한 테이블에 한정. package 의존 검사만으로 SQL 소유권이나 모듈 간 transaction 경계를 보장할 수 없음. 쓰기 경로와 불변조건 transaction을 함께 검토.
- CRUD는 JPA, PostgreSQL 전용 동시성 SQL은 `.repository`의 JDBC/native query. Flyway가 schema를 소유하고 Hibernate는 `validate`만 수행. 불필요한 generic base/mapper/forwarding scheduler 금지.
- CQRS·CommandBus·command/query handler·mediator 및 `internal` 금지. 모듈 입력은 `*Request` record. HTTP request record는 전송 필드나 validation 규칙이 다를 때만 별도 작성; 단순 복제 DTO 금지.
- 문서·Issue·PR은 프로젝트 맥락을 모르는 독자를 기준으로 작성. 내부 shorthand/ID만 나열하지 말고, 복잡한 변경은 배경·사실/가정·선택 이유·대안·영향·검증을 본문에 설명하고 기준 문서에 연결.
- 흐름/상태가 복잡한 경우 Mermaid 등으로 시각화. 중복 그림 금지, 미정 경계 표시. 단순 변경에 도식 강제 금지.
- 아직 막지 못한 소비자 동작·계약·상태·불변조건·동시성·오류의 구체적 회귀 위험을 검증.
- 단순 연결, 전달, mapping, 우연한 기본값, 중복 assertion은 테스트하지 않음. 같은 위험을 여러 계층에서 반복 검증하지 않음.
- Mock은 외부 state/failure semantics의 fidelity가 낮아지는 지점을 검토. PostgreSQL SQL/locking은 Testcontainers의 실제 PostgreSQL 경계에서 검증.
- 외부 PSP 상태와 주문 상태, 내부 정산과 실제 지급 구분. 초기 범위에서 실결제·개인정보·판매사 지급 제외.
- 완료 검증은 변경된 실제 경로를 smoke 실행하고 관련 contract/invariant를 확인. Performance/capacity 주장은 workload·환경을 명시한 반복 측정으로만 제시; 코드 모양이나 단발 측정으로 추정하지 않음.

## GitHub 작업 및 변경 관리
- Issue 양식과 PR template 사용. 복잡한 변경은 사실/가정, 이유·대안, 상태·불변조건 영향, 검증을 본문에 설명. 링크만으로 설명을 대신하지 않음.
- 변경 전 `git status --short --branch`; stage 전후 `git diff`, `git diff --cached`, `git status` 확인. 관련 경로만 stage하고 확인 전 `git add .`/`git add -A` 금지.
- 기본 branch는 `main`, 직접 push 금지(최초 seed 제외). Branch 이름 `<type>/<primary-issue>-<short-topic>`; type은 `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `build`, `chore`, `revert`, topic은 ASCII 소문자 kebab-case. 한 branch는 한 목적.
- Commit 제목은 `<type>(<scope>): <한국어 요약>`(scope 생략 가능). type/scope는 영문 소문자, 요약은 짧은 명령형, 마침표 없음. 한 commit은 하나의 논리 변경; 이유·제약·추적 ID가 필요하면 본문에 기록. 과거 제목은 기본적으로 수정하지 않음.
- 한 목적·완료 기준·독립 검토 경계는 Issue와 PR 하나로 묶음. 줄 수만으로 분리하지 않음. 같은 목적의 후속 결정은 기존 Issue에 누적하고, 목적·위험·책임자·배포/복구 경계가 독립될 때만 분리.
- PR template의 모든 checklist 항목 완료. 해당 없음은 구체적 사유 기재.
- `main`은 PR과 strict required checks(`Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks`), linear history 필수; 관리자도 적용.
- Required reviewers 0명; 승인 리뷰 정책은 별도 합의 필요. Merge는 squash only; branch logical commits는 보존, `main`에는 squash commit 하나 반영.
- 공유 이력 amend/rebase, 무승인 force push, `main` 직접 push, 미추적 작업을 지우는 reset/clean 금지. 승인된 예외는 별도 Issue, 대상 SHA, 백업 ref, `--force-with-lease`, protection 복원 기록 필요. 충돌 해결 뒤 diff 및 관련 lint/test 재실행.
- Git 작성자 정보는 local repository에만 설정하고 GitHub no-reply 주소 사용. API key/token, 개인정보, 실제 결제 정보는 코드·로그·Issue/PR에 기록 금지; placeholder만 사용. 유출 시 즉시 폐기·교체.
- README는 승인 범위·실행·모듈 구성을 소유. GitHub Issue/PR/commit은 진행 중 작업과 상세 변경 이력의 기준. Decision Log는 지속 보존할 결정 근거, EXP 보고서는 재현 가능한 측정 기록을 소유. 별도 roadmap/milestone/handoff/closeout 문서는 추가하지 않음.

## 재발 방지 기준
- 파일 확인은 정적 read 도구 사용. 사용자가 금지한 `view` command는 실행하지 않음.
- 이름 변경 전 실제 ecommerce 용어와 모듈 책임을 대조. 저장소 전체의 production/test/integration/resource/config/docs 참조를 검색; subagent 결과도 모든 호출자와 테스트까지 검증.
- 코드 patch는 최신 파일 경로·hashline을 확인한 뒤 최소 범위로 적용. 매 patch 뒤 대상 구간과 `git diff` 재확인. 경로 불일치·파서 경고가 나면 후속 patch 중단; 원인 수정 후에만 진행.
- Issue/PR 본문 수정 전 현재 본문, template, CI validator 확인. 필수 heading·checkbox 수, Issues 항목의 plain-text `Closes`/`Refs`와 Issue link 번호 일치, 요구사항·결정 ID 직접 링크를 검사. 중복 heading, 폐기된 이름/경로, 오래된 검증 수치 확인 후 원격 수정; 수정 뒤 본문과 `gh pr checks` 재확인.
- 미병합 문서 링크는 `main`이 아닌 PR branch 또는 현재 branch SHA 사용. 문서 수정 완료 뒤 링크 대상과 SHA 확인. PR 본문을 다시 쓸 때 기존 추적·위험·검증 기록 보존.
- 커밋 전 staged/unstaged/untracked를 각각 확인. subagent가 stage한 변경 포함 여부를 검토하고, 의도한 경로만 stage한 뒤 `git diff --cached` 검토. 전체 검증은 최종 working tree 기준으로 수행.
- 이미 확정된 결정은 다시 묻지 않음. 미결정 선택은 확인한 사실과 가정을 구분하고 출처·한계를 제시; 관련 선택지는 이점·비용·위험·후속 영향을 함께 비교. 권장안·이유와 정확히 필요한 결정을 한 번에 요청하며, 같은 범위의 질문은 묶음. 아키텍처·데이터·보안·범위가 실질적으로 달라질 때만 추가 확인.
- 한국어 독자를 우선. 설명은 짧고 자연스러운 한국어로 작성; 필요한 backend 용어만 English 사용. 문서·Issue·PR의 기존 중복과 구식 표현을 덧붙이지 말고 정리.
- 문서 수정 뒤 Markdown 렌더링·lint와 내부 링크를 확인. 폴더 번호 변경 시 Markdown, SQL 명령, README, ADR, workflow 등 저장소 전체의 경로 참조도 함께 갱신. 삭제한 문서·실험 ID·모듈 README 참조가 남지 않았는지 검색.
- 모든 문서는 처음 보는 독자 기준의 자연스러운 한국어·개조식으로 작성. 제품명·표준 기술명·API·코드 식별자는 원문 유지하고, 그 외 외국어 문장이나 억지 혼용은 피함. 표·Mermaid도 실제 렌더링 결과와 링크를 확인.
- ORM-generated ID 변경은 Spring Boot BOM의 실제 Hibernate version·generator 상태를 확인. `@Incubating` API와 범위 기록; Testcontainers/PostgreSQL에서 실제 persist 후 모든 대상 PK의 version·transaction 흐름 검증. Non-PK reference ID를 primary ID로 혼동하지 않음.
- Agent 작업 전 실제 repository root, branch, HEAD 확인. Agent summary는 완료 증거가 아님; parent가 변경 범위·호출자·diff와 미실행 검증을 직접 확인.
- Migration/benchmark SQL은 대상 PostgreSQL version에서 실행 검증. 전용 disposable database/schema만 사용하고 실행 시 DB 이름을 guard; 기존 `atlas` database를 테스트 대상으로 사용하지 않음. 실패한 SQL 실행은 결과로 기록하지 않으며 최종 성공 출력과 환경을 함께 보존.

## 진입점
- 실행·모듈 구성·현재 승인 범위: [README](README.md).
- 기능 계약·domain invariants·미결정 사항: [요구사항](docs/01-requirements/requirements-index.md).
- 결정·대안·대체 이력: [결정 로그](docs/00-governance/decision-log.md).
- 실측 결과: [EXP-013](docs/03-experiments/EXP-013-gate4-20-minute-calibration.md), [EXP-014](docs/03-experiments/EXP-014-id-strategy-comparison.md).
