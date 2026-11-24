# ADR-0073: 앱 테스트 shard가 매 실행 받던 의존성을 verify 캐시에 넣어 Maven Central 429 노출을 없앤다

- 상태: 채택(제안 PR, 병합은 통합 담당)
- 날짜: 2026-11-24
- 근거: [EXP-075](../../03-experiments/EXP-075-ci-pipeline-reliability.md), [Issue #600](https://github.com/Neo1228/ATLAS/issues/600)
- 이어받는 결정: [ADR-0070](ADR-0070-parallel-backend-ci.md)(shard 병렬화), [EXP-030](../../03-experiments/EXP-030-ci-build-duration.md)

## 문제

앱 테스트 shard 4개가 매 실행 같은 jar 5개를 Maven Central에서 다시 받아서(실행당 요청 20건), 샤딩 이후 전체 검사 118건 중 3건이 HTTP 429로 실패했다(샤딩 이전 단일 job 124건은 0건).

## 결정

1. **`verify`의 Gradle 호출에 `resolveTestRuntimeClasspath`를 더한다.** `.github/gradle/resolve-test-runtime-classpaths.init.gradle`(CI 전용 init script)이 프로젝트마다 `testRuntimeClasspath`를 해석해 jar까지 받는다. main push의 `verify`가 저장하는 Gradle 캐시에 shard가 쓰는 jar가 들어가고, shard는 캐시를 읽기만 하는 지금 구조를 그대로 둔다.
2. **shard가 Maven Central에서 새로 받은 jar 수를 요약에 남긴다**(`DOWNLOADED_JARS n`). 캐시가 데워진 뒤 main push 실행에서 0이 아니면 캐시에 shard 의존성이 빠졌다는 신호다. 이 구조적 결함이 하루 넘게 보이지 않았던 것을 막기 위한 것이다.
3. **`docs-lint.yml`의 `PR checklist`를 열린 PR에서만 실행하고, 본문 검사 실패 메시지를 check annotation(`::error`)으로 낸다.** 병합된 PR의 `edited` 이벤트에서 `Invalid revision range`로 실패한 4건을 없애고, 작성 실수 11건이 "exit code 1"만 보이던 것을 고친다. 필수 체크 이름(`Markdown lint`, `PR checklist`, `Java 25 and PostgreSQL integration checks`)은 바꾸지 않는다. 닫힌 PR에서 `PR checklist`가 건너뛰어진 상태가 되지만 닫힌 PR은 병합 대상이 아니다.

## 대안과 버린 이유

[EXP-075의 대안 비교](../../03-experiments/EXP-075-ci-pipeline-reliability.md#대안-비교)가 근거와 함께 적었다. 요약:

- 저장소 미러: 외부 서비스가 둘째 신뢰 근원이 되고 이 저장소는 의존성 검증 메타데이터가 없다.
- Gradle 재시도 상향: Sonatype이 재시도 확대를 권하지 않고, 속성이 Gradle 문서에 없으며 효과를 검증할 수 없다.
- 한 job이 빌드해 공유: shard의 컴파일은 이미 캐시 적중이고 test 밖의 반복 작업이 job 합계의 8% 이하다.
- shard도 캐시를 쓴다: 의존성 항목이 늘어 캐시 한도를 잠식한다(setup-gradle 문서가 경고).

## 결과

측정 조건과 표본은 EXP-075에 있다. 여기에는 결정에 쓴 숫자만 옮긴다.

- shard가 새로 받는 jar: 변경 전 shard-job 12개 모두 5개, 변경 후(캐시가 데워진 뒤) 12개 모두 0개.
- 429에 노출되는 jar 요청: 실행당 20건에서 0건(정상 경로). 429 자체를 없애지는 못한다.
- 시간 단축은 주장하지 않는다. 개선 추정치(shard당 수 초)가 실행 편차(성공 실행 62건 표준편차 0.95분)보다 훨씬 작다.

운영 변경 그대로의 측정(draft PR #603, 재실행 3회): 12개 shard-job 모두 0개, job 합계 11.37, 11.25, 9.77분. 같은 날 변경 없는 성공 실행 62건의 중앙값 11.74분(표준편차 0.95분)과 구분되지 않는다.

## main push 실행의 역할과 병합 흐름별 설계

지금 push 실행은 두 가지 일을 한다. 섞어 두면 어느 쪽이 필요한지 판단하기 어렵다.

| 역할 | 하는 job | 이유 |
| --- | --- | --- |
| R1. main의 Gradle 캐시 갱신 | `verify` | PR은 캐시를 읽기만 하므로(`cache-read-only: ${{ github.event_name == 'pull_request' }}`) **main push가 캐시를 쓰는 유일한 지점**이다 |
| R2. 병합 결과의 통합 검증 | `app-tests`(shard 4개) | 병합 결과가 PR에서 테스트한 트리와 다를 때를 잡는다 |

- **보호 규칙 `strict: true`(최신 main 기준 필수)를 유지하고 squash만 허용하는 경우**: 병합 결과 트리는 PR에서 테스트한 트리와 같다. R2는 중복이다. 샤딩 이후 main push 실패 4건(전체 검사 51건 중)은 429 2건과 `LocationFaultConvergenceIntegrationTest`(타이밍 의존, 원인 미확정) 1건 실패가 2건이었다. 병합 결과의 결정적 결함으로 확인된 건은 이 표본에 없다(표본이 작아 일반화하지 않는다). 이 경우 push 실행에서 `app-tests`에 `if: github.event_name == 'pull_request'`를 더하면 push는 `verify` 하나(약 1분)로 R1을 하고, push 한 번당 job 합계가 약 12분에서 약 1분이 된다. 공개 저장소라 표준 러너 사용량은 무료([GitHub 문서](https://docs.github.com/en/billing/managing-billing-for-your-products/about-billing-for-github-actions))이므로 이득은 비용이 아니라 거짓 경보(위 4건)와 429 노출을 줄이는 것이다.
- **`strict`를 끄는 경우**: 두 PR이 각각 통과해도 병합 결과가 처음 합쳐지는 곳이 main push다. R2가 필요하다. 지금 workflow를 그대로 둔다.
- 이 PR은 push 정책을 바꾸지 않는다. 통합 담당이 병합 흐름을 정하면 위 한 줄로 반영한다. 어느 쪽이든 R1은 바뀌지 않는다.

## 실패 경로와 롤백

- **캐시가 오염되거나 깨지면**: Gradle이 jar를 다시 받는다. 이 변경 이전과 같은 상태이고 요약의 `DOWNLOADED_JARS`가 5로 돌아간다. 캐시 항목은 브랜치 범위라 PR이 main 캐시를 오염시킬 수 없다.
- **init script가 깨지면**(Gradle 업그레이드 등): `verify`가 실패해 `Java 25 and PostgreSQL integration checks`가 빨개진다. 조용히 지나가지 않는다.
- **의존성을 바꾼 PR**: 캐시 미적중이라 `verify`와 shard가 각자 새 jar를 받는다. 429 위험이 남는다. 이때는 재실행하면 된다.
- **병합 직후 첫 main push**가 캐시를 쓸 때까지는 효과가 없다.
- **롤백**: 이 PR을 되돌린다(워크플로 2개와 init script 파일 1개). 서버 쪽 상태가 없고 캐시 항목은 시간이 지나면 밀려난다.

## 한계

- 429가 왜 났는지(어느 VM이 왜 차단됐는지)는 설명하지 못했다. 이 변경은 원인이 아니라 노출을 줄인다.
- 변경 후 측정은 반복 3회, shard-job 12개다. 실패율은 429가 샤딩 이후 2.5%(3/118)로 드물어서 이 표본으로 줄었다고 말할 수 없다. 줄인 것은 jar 요청 수(20 → 0)이고 이것은 직접 셌다.
- 캐시를 데우는 임시 설정(`cache-read-only: false`)으로 PR 실행에서 측정했다. 운영 경로(main push가 쓰고 PR이 읽음)의 전체 사슬은 병합 뒤 첫 push와 그다음 PR에서 확인해야 한다.
