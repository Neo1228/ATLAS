# 기여 및 작업 절차

ATLAS는 문서와 코드의 변경 이력을 추적 가능하게 유지합니다. 현재 Gate 0 단계에서는 요구사항·결정·문서 규칙을 먼저 다루며, Gate 0 승인 전 코드·의존성·배포 작업은 하지 않습니다.

## 작업 단위
1. 시작 전에 관련 요구사항, 질문, 규칙, ADR을 확인합니다. 미정 정책은 임의로 확정하지 않습니다.
2. 작업을 하나의 검토 가능한 목적을 가진 작은 단위로 나누고, 변경 파일과 인수/검증 기준을 기록합니다.
3. 관계된 변경만 함께 커밋합니다. 무관한 변경은 분리합니다.
4. 독립 검토가 가능한 소단위 묶음이 준비되면 `.github/PULL_REQUEST_TEMPLATE.md`를 사용해 PR을 엽니다.
5. 최초 문서 전체를 한꺼번에 업로드하거나 대형 PR로 만들지 않습니다. 합의된 묶음을 순차적으로 검토·통합합니다.
6. 완료 후 실제 커밋/PR 식별자, 변경 범위, 실행한 검증과 결과를 `docs/09-delivery/milestone-log.md` 및 `session-handoff.md`에 기록합니다.

## 검증
- Markdown 문서 변경: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- 문서 링크·용어·결정 상태도 검토합니다. 실행하지 않은 검증을 성공으로 보고하지 않습니다.
- 향후 애플리케이션 스택과 품질 도구는 별도 결정 후 추가합니다. 현재 린트 설정은 Markdown 문서에만 적용됩니다.

## 브랜치 규칙
- 기본 브랜치는 `main`이며 직접 push하지 않는다. 빈 저장소의 최초 seed만 예외로 둔다.
- 작업 브랜치는 `<type>/<issue>-<short-topic>` 형식, 이슈가 없으면 `<type>/<short-topic>` 형식으로 만든다. 예: `docs/123-git-conventions`, `fix/checkout-timeout`, `docs/git-conventions`.
- type은 `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `build`, `chore`, `revert` 중 목적에 맞게 고르고, topic은 소문자 ASCII kebab-case로 쓴다. 한 브랜치에는 한 목적만 담는다.

## 커밋 규칙
- 제목 형식: `<type>(<scope>): <한국어 요약>`; scope가 불필요하면 생략한다.
- prefix/type/scope는 영문 소문자로, 제목 끝부분 요약은 한국어로 작성한다. 짧은 명령형 문구를 쓰고 마침표는 붙이지 않는다. 예: `docs(git): 브랜치와 PR 규칙 정리`.
- type은 `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`, `revert`를 사용한다. 기존 이력은 재작성하지 않는다.
- 커밋 하나에는 하나의 논리적 변경만 둔다. 관련 파일은 같은 변경 목적에 필요한 만큼만 포함하고, 무관한 변경은 별도 커밋/PR로 분리한다.
- 제목만으로 이유가 불명확하거나 호환성/정책 영향이 있으면 본문에 이유, 제약, 추적 ID(Q/FR/NFR/ADR/EXP)를 기록한다. 변경 이력에 비밀이나 개인정보를 넣지 않는다.

## PR 규칙
- PR은 `main`을 대상으로 하며, 단일 목적의 검토 가능한 작업 묶음으로 제한한다. 여러 작은 관련 커밋은 PR 하나로 묶을 수 있다.
- PR 제목도 `<type>(<scope>): <한국어 요약>` 형식을 따른다. 설명은 `.github/PULL_REQUEST_TEMPLATE.md`를 작성한다.
- 관련 Issue가 실제 존재할 때만 `Closes #N`을 쓴다. 없으면 요구사항/질문/ADR ID를 연결하고 Issue를 허위로 만들지 않는다.
- 미완성 공유/피드백 단계는 Draft PR로 연다. 검토 요청 전 로컬 lint/test, staged diff, CI 상태를 확인한다.
- `main`은 PR 필수, `Markdown lint` 필수, 선형 이력, force push/브랜치 삭제 금지로 보호한다. 1인 프로젝트이므로 승인자 수는 0이다. 병합은 Squash and merge만 허용하며 병합 후 작업 브랜치는 자동 삭제한다.

## 동기화와 안전한 Git 사용
- 시작 전 `git status --short --branch`; stage 전에 `git diff`; stage 후 `git diff --cached`와 `git status`를 확인한다.
- 관련 파일 경로만 `git add <path>`로 stage한다. `git add .`/`git add -A`는 보류 중인 다른 작업이 없음을 확인하기 전 사용하지 않는다.
- 작업 브랜치는 최신 `origin/main`에서 시작한다. 공유/게시된 커밋은 amend/rebase로 재작성하지 않고, 수정은 새 커밋으로 추가한다. `main` 직접 push와 force push는 하지 않는다.
- 충돌은 변경 의도를 확인해 해결하고, 해결 후 diff 및 관련 lint/test를 다시 실행한다. 강제 reset/clean 등 미추적 작업을 지울 수 있는 명령은 사용하지 않는다.

## 작성자 및 민감정보
- 로컬 저장소에만 Git 작성자 정보를 설정한다. GitHub 설정의 no-reply 이메일을 사용하고 계정과 일치하는지 확인한다.
- API key/token, 고객정보, 실결제 정보는 파일·로그·Issue/PR에 넣지 않는다. 예제 설정에는 placeholder만 사용한다. 유출 시 해당 credential을 즉시 폐기/교체한다.

## 추적 및 병합 후
- 작업 ID/목적/변경 파일/검증과 실제 commit SHA·PR URL을 milestone log와 session handoff에 기록한다. 생성 전 항목은 Planned로만 표시한다.
- 병합 후 원격 PR 상태와 CI를 확인하고, 다음 작은 작업은 별도 브랜치/PR로 시작한다. Gate 종료·범위 변경은 별도 사용자 승인 대상이다.
