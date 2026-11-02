# 기여 및 작업 절차
모든 변경은 GitHub Issue부터 기록. Gate 2 Buyer 기준과 Gate 3 architecture/stack 기준 승인. [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) 범위의 local Buyer runtime은 승인됨. Live payment·cloud deployment·production load claim은 별도 승인 전 보류.

## 1. 작업과 Issue
1. 착수 전 GitHub Issue 생성. [버그 양식](.github/ISSUE_TEMPLATE/bug_report.yml) 또는 [제안 양식](.github/ISSUE_TEMPLATE/change_request.yml) 사용.
2. 문제·근거·범위·완료/검증 기준·관련 ID(Q/FR/NFR/BR/INV/ADR/EXP)를 기록. ID마다 기준 문서로 이동하는 Markdown link 제공; bare ID만 나열하지 않음.
3. 복잡한 변경은 본문에 사실/가정, 결정 이유, 대안·trade-off, 영향 상태/불변조건을 요약. 링크만 남기지 않음.
4. Issue 하나에 목적과 완료 기준을 묶기. 같은 묶음의 조사·선택은 기존 Issue에서 이어가기.
5. 관련 결정과 commit은 PR 하나로 검토. 질문·metric·파일마다 Issue/PR을 만들지 않음.
6. 목적·위험·책임자·완료 기준·배포/복구 경계가 독립될 때만 분리.
7. Branch명에 Issue 번호 포함. 관련 PR/commit 연결.
8. 완료 PR은 plain-text Closes #N, 일부 반영은 Refs #N. 같은 Issues 줄에 Issue link를 별도로 추가; close keyword는 Markdown link로 감싸지 않음.
9. 범위·검증·commit SHA·PR URL·merge SHA를 [Milestone Log](docs/09-delivery/milestone-log.md), [Session Handoff](docs/09-delivery/session-handoff.md)에 기록.
10. Issue 제목은 한국어. [버그]:/[제안]: 접두사 유지.

## 2. 문장·용어
- 핵심부터, 한 문단 한 요점. 짧은 문장과 자연스러운 개조식 우선.
- 프로젝트 맥락을 모르는 독자도 이해할 수 있게 작성. 내부 shorthand·ID만 남기지 말고 개념과 이유를 짧게 설명.
- 수치·범위·날짜는 단위와 기준을 함께 표시.
- API, retry, queue, transaction 등 표준 backend 용어는 English 사용 가능. 일반 설명에 불필요한 전문 용어·영한 병기 금지.
- 프로젝트 용어·약어는 첫 등장에 풀이하거나 [Glossary](docs/01-discovery/glossary.md) 연결.
- Java comment는 자연스러운 한국어 우선. 표준 type/API/protocol 이름은 English 유지. 동작을 그대로 반복하지 말고 이유·invariant·외부 제약만 설명.
- Domain failures are represented by one typed exception with a `Code` enum per module; the single `application.exception.GlobalExceptionHandler` maps them to ProblemDetail/code. Raw `IllegalArgumentException` is not an HTTP error contract; wrap expected invalid input in the owning module exception.
- 도메인 package: `.service` contract, `.dto.request` 입력, `.dto.response` 출력, `.exception` module error. Lifecycle state enum은 `.persistence.state` 소유이며 필요한 cross-module contract에서 공유한다; entity/repository는 모듈 내부. Workflow/scheduled expiry는 cohesive `.service.impl`; JPA mapping/CRUD는 `.persistence.entity`·`.persistence.repository`. HTTP controller·request DTO는 `application-module`의 `.application.controller.<domain>`·`.application.controller.dto`. Flyway schema owner, Hibernate `validate`; `package-info.java` marker 없음.
- 직접 Service contract 호출 우선. Module input은 `*Request` record; HTTP request record는 body/path/header 조합이나 transport 검증 등 실제 차이가 있을 때만 분리하고, mapping 없는 중복 DTO는 만들지 않음. CQRS·CommandBus·handler·mediator·`internal` 금지. PostgreSQL lock·sequence·guarded update 같은 SQL 이점이 명확한 경로만 repository에서 JDBC/native query 사용; forwarding-only scheduler, generic mapper/base layer를 만들지 않음. 경계는 [ADR-0003](docs/06-decisions/adr/ADR-0003-gradle-module-boundaries.md)·[ADR-0004](docs/06-decisions/adr/ADR-0004-jpa-and-sql-boundary.md).
- 테스트는 사용자 동작·계약·불변조건·상태전이·오류·동시성처럼 실제 결함 위험을 막는 범위로 제한. Wiring, forwarding, mock echo, incidental default, duplicate assertions 테스트는 만들지 말고 중복 테스트는 삭제. PostgreSQL 전용 SQL/locking은 PostgreSQL integration test, 그 외 동작은 가장 작은 유효 경계에서 검증. Mock은 무조건 배제하지 말고 production fidelity 손실이 큰 경우 실제 dependency 사용 ([REF-099](docs/00-governance/reference-register.md)).

## 3. 검증
- Markdown: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- 링크·용어·결정 상태도 확인. 실행하지 않은 검증은 성공으로 기록하지 않음.
- Java: FQCN 금지, import 사용. [Checkstyle 설정](config/checkstyle/checkstyle.xml)이 `./gradlew check`에서 위반 탐지 ([REF-068](docs/00-governance/reference-register.md)).
- CI: Markdown lint·PR checklist와 JDK 25 `./gradlew check`; Checkstyle, compiler warnings, PostgreSQL/Testcontainers 통합 테스트 포함. Docker 필요. Colima 설정은 [README](README.md). CI Temurin은 build/test 전용; production JDK 배포판 선택 아님.

## 4. Branch
- 기본 branch: `main`; 직접 push 금지 (최초 seed 제외).
- 이름: `<type>/<primary-issue>-<short-topic>`; 예: `docs/123-pr-rules`, `fix/42-timeout`.
- type: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `build`, `chore`, `revert`.
- topic은 ASCII 소문자 kebab-case. 한 branch는 한 목적.

## 5. Commit
- 제목: `<type>(<scope>): <한국어 요약>`; scope 생략 가능.
- type/scope는 영문 소문자, 요약은 한국어. 짧은 명령형, 마침표 없음.
- 예: `docs(git): 브랜치와 PR 규칙 정리`
- 한 commit은 한 논리 변경. 이유가 불분명하거나 정책/호환성 영향이 있으면 본문에 이유·제약·추적 ID 기록.
- 과거 제목 수정은 기본 금지. 사용자 명시 승인과 별도 절차 필요.

## 6. Pull Request
- 한 목적·완료 기준·검토 경계에 관련 commit과 Issue를 PR 하나로 묶기.
- 같은 목적의 후속 결정은 기존 Issue 댓글로 누적. 독립 목적·위험·책임자·완료 기준·배포/복구 경계만 분리.
- [PR template](.github/PULL_REQUEST_TEMPLATE.md) 필수. 복잡한 변경은 근거·대안·영향·검증을 짧은 한국어로 작성.
- 복잡한 flow/state에만 Mermaid 사용. 모든 checkbox 완료; N/A는 구체적 이유 작성.
- `main` 보호 설정: PR 필수, strict required checks (`Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks`), linear history. 관리자도 적용 대상. Required reviewer는 0명; 승인 리뷰 정책은 별도 합의 필요.
- Merge policy: repository currently allows squash only. Keep logical commits separately on the branch; PR preserves their individual links, main receives one squash commit.

## 7. 안전한 Git
- 시작 전 `git status --short --branch`.
- stage 전 `git diff`, stage 후 `git diff --cached`와 `git status` 확인.
- 관련 경로만 `git add <path>`. 다른 변경 여부 확인 전 `git add .`/`git add -A` 금지.
- 공유 이력 amend/rebase 금지. 명시 승인된 예외는 별도 Issue, 대상 SHA, 백업 ref, `--force-with-lease`, protection 복원 기록 필요.
- `main` 직접 push·무승인 force push·미추적 작업을 지우는 reset/clean 금지. 충돌 해결 뒤 diff 및 관련 lint/test 재실행.

## 8. 계정·민감정보
- Git 작성자 정보는 local repository에만 설정. GitHub no-reply 주소 사용.
- API key/token, 개인정보, 실제 결제 정보를 코드·로그·Issue/PR에 기록 금지. 예시는 placeholder만 사용. 유출 시 즉시 폐기/교체.

## 9. 병합 후
- 실제 Issue/commit/PR 상태·SHA·검증만 [Milestone Log](docs/09-delivery/milestone-log.md)와 [Session Handoff](docs/09-delivery/session-handoff.md)에 기록.
- 병합과 CI 확인 후 다음 작업은 별도 branch에서 시작. Gate 종료·범위 변경은 사용자 승인 필요.
