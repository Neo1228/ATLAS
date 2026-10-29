# 기여 및 작업 절차

ATLAS는 문서와 코드의 변경 이력을 추적 가능하게 유지합니다. 현재 Gate 0 단계에서는 요구사항·결정·문서 규칙을 먼저 다루며, Gate 0 승인 전 코드·의존성·배포 작업은 하지 않습니다.

## 작업 단위와 Issue 선행
1. 계획된 코드·문서·설정 변경은 시작 전에 GitHub 이슈 하나를 만든다. 버그는 버그 이슈 양식, 기능/정책/문서 제안은 변경 제안 이슈 양식을 사용한다.
2. 이슈에는 문제/근거, 제안 또는 재현 조건, 인수·검증 기준, 관련 Q/FR/NFR/BR/INV/ADR/EXP ID, 독립된 작업 범위를 기록한다. 범위가 크면 하위 이슈로 나눈다.
3. 한 이슈는 하나의 응집된 작업 묶음을 추적한다. 여러 작은 커밋은 같은 이슈 아래 둘 수 있으므로 커밋마다 이슈를 만들지 않는다.
4. 이슈 번호를 브랜치명에 넣고, 모든 관련 PR/커밋을 이슈에 연결한다. 단일 PR이 수락 기준을 끝내면 `Closes #N`; 일부만 처리하면 `Refs #N`을 쓴다. 여러 PR에 걸친 경우 마지막 완료 PR만 이슈를 닫는다.
5. 이슈가 생성되고 범위/검증 기준이 분명해진 뒤에만 파일 변경을 시작한다. 긴급 복구도 가능한 즉시 이슈를 만들고 기록한다. 사소한 변경은 기존 관련 이슈에 묶는다.
6. 독립 검토가 가능한 소단위 PR은 `.github/PULL_REQUEST_TEMPLATE.md`를 따르고, 최초 문서 전체를 한꺼번에 업로드하지 않는다.
7. 완료 후 실제 이슈/커밋/PR 식별자, 변경 범위, 실행한 검증과 결과를 `docs/09-delivery/milestone-log.md` 및 `session-handoff.md`에 기록한다.
이슈 제목은 한국어로 작성하고, 양식에서 붙이는 `[버그]:`/`[제안]:` 접두사를 유지한다. PR/커밋 제목은 `<type>(<scope>): <한국어 요약>` 형식을 따른다.

## 검증
- Markdown 문서 변경: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- 문서 링크·용어·결정 상태도 검토합니다. 실행하지 않은 검증을 성공으로 보고하지 않습니다.
- 향후 애플리케이션 스택과 품질 도구는 별도 결정 후 추가합니다. 현재 린트 설정은 Markdown 문서에만 적용됩니다.

## 브랜치 규칙
- 기본 브랜치는 `main`이며 직접 push하지 않는다. 빈 저장소의 최초 seed만 예외로 둔다.
- 작업 브랜치는 `<type>/<primary-issue>-<short-topic>` 형식으로 만든다. PR이 관련 이슈 여러 개를 묶으면 대표 Issue 하나를 브랜치명에 넣고 나머지도 PR에서 연결한다. 예: `docs/123-issues-and-pr-batching`, `fix/42-timeout`.
- type은 `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `build`, `chore`, `revert` 중 목적에 맞게 고르고, topic은 소문자 ASCII kebab-case로 쓴다. 한 브랜치에는 한 목적만 담는다.

## 커밋 규칙
- 제목 형식: `<type>(<scope>): <한국어 요약>`; scope가 불필요하면 생략한다.
- prefix/type/scope는 영문 소문자로, 제목 끝부분 요약은 한국어로 작성한다. 짧은 명령형 문구를 쓰고 마침표는 붙이지 않는다. 예: `docs(git): 브랜치와 PR 규칙 정리`.
- type은 `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`, `revert`를 사용한다. 과거 제목 수정은 일반 작업에서 하지 않고, 명시적 사용자 승인 및 절차가 있는 예외만 허용한다.
- 커밋 하나에는 하나의 논리적 변경만 둔다. 관련 파일은 같은 변경 목적에 필요한 만큼만 포함하고, 무관한 변경은 별도 커밋/PR로 분리한다.
- 제목만으로 이유가 불명확하거나 호환성/정책 영향이 있으면 본문에 이유, 제약, 추적 ID(Q/FR/NFR/ADR/EXP)를 기록한다. 변경 이력에 비밀이나 개인정보를 넣지 않는다.

## PR 규칙
- PR은 `main`을 대상으로 하나의 응집된 검토/수락 경계를 다룬다. 하나의 PR에 작은 관련 커밋 여러 개와 서로 의존/관련된 이슈 여러 개를 함께 묶을 수 있다. 커밋마다 또는 이슈마다 PR 하나씩 만들지 않는다.
- 서로 무관한 목적, 위험, 수락 기준은 PR을 분리한다. 분리 기준은 commit count가 아니라 독립 리뷰 가능성, 릴리스/복구 경계, 변경 간 의존성이다.
- PR 제목도 `<type>(<scope>): <한국어 요약>` 형식을 따른다. 설명은 `.github/PULL_REQUEST_TEMPLATE.md`를 작성한다.
- 연관된 이슈를 모두 명시하고, 완료된 이슈만 각각 `Closes #N`; 부분 작업/다음 PR이 남으면 `Refs #N`을 쓴다.
- `main`은 PR 필수, `Markdown lint` 필수, 선형 이력, force push/브랜치 삭제 금지로 보호한다. 1인 프로젝트이므로 승인자 수는 0이다. 병합은 Squash and merge만 허용하며 병합 후 작업 브랜치는 자동 삭제한다.

## 동기화와 안전한 Git 사용
- 시작 전 `git status --short --branch`; stage 전에 `git diff`; stage 후 `git diff --cached`와 `git status`를 확인한다.
- 관련 파일 경로만 `git add <path>`로 stage한다. `git add .`/`git add -A`는 보류 중인 다른 작업이 없음을 확인하기 전 사용하지 않는다.
- 공유 이력은 기본적으로 amend/rebase로 재작성하지 않는다. 사용자가 이력 정정을 명시 승인하면 별도 Issue에 대상 SHA/영향을 기록하고 백업 ref를 만든 뒤 `--force-with-lease`로만 반영한다. branch protection은 최소 시간만 완화하고 즉시 복원하며, 관련 브랜치/PR/SHA를 모두 갱신한다. `main` 직접 push와 무승인 force push는 금지한다.
- 충돌은 변경 의도를 확인해 해결하고, 해결 후 diff 및 관련 lint/test를 다시 실행한다. 강제 reset/clean 등 미추적 작업을 지울 수 있는 명령은 사용하지 않는다.

## 작성자 및 민감정보
- 로컬 저장소에만 Git 작성자 정보를 설정한다. GitHub 설정의 no-reply 이메일을 사용하고 계정과 일치하는지 확인한다.
- API key/token, 고객정보, 실결제 정보는 파일·로그·Issue/PR에 넣지 않는다. 예제 설정에는 placeholder만 사용한다. 유출 시 해당 credential을 즉시 폐기/교체한다.

## 추적 및 병합 후
- 작업 ID/목적/변경 파일/검증과 실제 commit SHA·PR URL을 milestone log와 session handoff에 기록한다. 생성 전 항목은 Planned로만 표시한다.
- 병합 후 원격 PR 상태와 CI를 확인하고, 다음 작은 작업은 별도 브랜치/PR로 시작한다. Gate 종료·범위 변경은 별도 사용자 승인 대상이다.
