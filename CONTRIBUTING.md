# 기여 및 작업 절차
모든 변경은 Issue부터 기록. 현재 Gate 0 승인, Gate 1 discovery 진행 중. Gate 2·아키텍처·구현·의존성·배포는 별도 승인 전 보류.

## 1. 작업과 Issue
1. 착수 전 GitHub Issue 생성. [버그 양식](.github/ISSUE_TEMPLATE/bug_report.yml) 또는 [제안 양식](.github/ISSUE_TEMPLATE/change_request.yml) 사용.
2. 문제·근거·범위·완료/검증 기준·관련 ID(Q/FR/NFR/BR/INV/ADR/EXP) 기록.
3. 복잡한 변경은 본문에 사실/가정, 결정 이유, 대안·trade-off, 영향 상태/불변조건을 요약. 링크만 남기지 않음.
4. 한 Issue는 한 응집된 작업 묶음. 커밋마다 Issue를 만들지 않음. 관계된 Issue/commit은 하나의 PR에 통합 가능.
5. 이슈 번호를 branch명에 포함. 모든 관련 PR/commit 연결. 완료 시 `Closes #N`, 일부만 반영 시 `Refs #N`.
6. 완료 후 실제 범위·검증·commit SHA·PR URL을 [Milestone Log](docs/09-delivery/milestone-log.md)와 [Session Handoff](docs/09-delivery/session-handoff.md)에 기록.
7. Issue 제목은 한국어. `[버그]:`/`[제안]:` 접두사 유지.

## 2. 문장·용어
- 짧은 문장, 한 문단 한 핵심. 자연스러운 경우 명사형/개조식 종결 우선; 조사·수식어 최소화.
- 수치·범위·날짜·완료 기준은 단위/기준과 함께 표 또는 불릿으로 표시.
- 일반적인 backend 용어(API, retry, queue, transaction)는 English 사용 가능. 어색한 직역·반복 병기 금지.
- 프로젝트 고유 용어·약어는 처음 등장 때 풀이하거나 [Glossary](docs/01-discovery/glossary.md) 연결.

## 3. 검증
- Markdown: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- 링크·용어·결정 상태도 확인. 실행하지 않은 검증은 성공으로 기록하지 않음.
- 현재 자동화는 Markdown lint와 PR checklist. 앱 품질 도구는 기술 스택 승인 뒤 추가.

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
- 하나의 목적·완료 기준·검토 경계로 구성. 관련 commit과 Issue는 함께 묶고, 커밋/이슈마다 PR을 만들지 않음.
- 독립 목적·위험·배포/복구 경계는 분리. 파일 수·commit 수만으로 나누지 않음.
- [PR template](.github/PULL_REQUEST_TEMPLATE.md) 사용. 복잡한 변경은 문제·결정 근거·대안·영향·검증을 본문에 직접 작성.
- 흐름·상태·아키텍처가 복잡하면 Mermaid 사용. 기존 도식이 있으면 요약 후 링크. 모든 checkbox 완료; 해당 없음은 이유 작성.
- `main` 필수 checks: `Markdown lint`, `PR checklist`. Squash merge만 허용, linear history 필수, force push/deletion 금지. 승인자 수 0, 자동 branch 삭제.

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
