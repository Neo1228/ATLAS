# EXP-075: CI 의존성 다운로드 429는 어디서 오고 job마다 반복되는 일은 얼마인가

- 질문: main push CI가 테스트가 아니라 Maven Central HTTP 429로 두 번 실패했고 두 번 다 `Application tests (3/4)` 한 shard였다. 왜 그 shard였고, 같은 일이 구조적으로 반복되는가? job 합계 약 11분 중 job마다 반복하는 의존성 해석·컴파일은 실제로 얼마인가?
- 상태: GitHub 실행 로그 분석과 draft PR(#598, #603)의 실제 CI 측정. **확정한 것, 표본이 작은 것, 확인하지 못한 것을 구분해 적었다.**
- 날짜: 2026-11-24
- 관련: [ADR-0073](../02-decisions/adr/ADR-0073-ci-dependency-cache-warming.md), [ADR-0070](../02-decisions/adr/ADR-0070-parallel-backend-ci.md), [EXP-030](EXP-030-ci-build-duration.md), [EXP-069](EXP-069-ci-shard-imbalance.md), [Issue #600](https://github.com/Neo1228/ATLAS/issues/600)

## 측정한 사실

### 1. 429는 3건이었고 shard가 3번으로 몰리지 않았다

`gh run list --workflow backend-ci.yml --limit 300`이 돌려준 실행 300건(2026-11-19 11:39Z~11-24 11:37Z)에서 실패한 13건의 로그를 `gh run view --log-failed`로 분류했다. 아래 표는 job 하나가 120초를 넘고 취소되지 않은 "전체 검사" 실행만 센 값이라 실패가 12건이고, 나머지 1건은 120초 미만에 끝난 단일 job 실행이다(429 아님).

| 구조 | 전체 검사 실행 | 429로 실패 | 테스트 실패 등 |
| --- | --- | --- | --- |
| 단일 job(ADR-0070 이전) | 124 | **0** | 7 |
| 샤딩(ADR-0070 이후) | 118 | **3**(2.5%) | 2 |

샤딩 이후 429 3건은 다음과 같다. 세 번 모두 `Received status code 429 from server: Too Many Requests`가 같은 jar 5개에서 났고(`lz4-java-1.11.2`, `zstd-jni-1.5.6-10`, `prometheus-metrics-exposition-formats-1.7.0`, `prometheus-metrics-exposition-textformats-1.7.0`, `snappy-java-1.1.10.7`), Gradle은 17~32초 만에 `BUILD FAILED`로 끝났다.

| 실행 | 이벤트 | 시각(UTC) | 실패한 job |
| --- | --- | --- | --- |
| 45231283583 | pull_request | 11-23 17:46 | `Application tests (1/4)` |
| 45330809510 | push main | 11-24 05:05 | `Application tests (3/4)` |
| 45344744453 | push main | 11-24 07:01 | `Application tests (3/4)` |

**과제 설명의 "3번 shard만 두 번"에는 세 번째 사례(PR, 1번 shard)가 빠져 있었다.** shard 번호에 의미가 있다는 증거는 이 표본에 없다. 첫 두 건이 같은 shard일 확률은 25%이고, 세 건 중 적어도 두 건이 같은 shard일 확률은 62.5%다.

### 2. 가설을 세우고 검증했다(왜 3번 shard였나)

| 가설 | 검증 | 결과 |
| --- | --- | --- |
| H1. shard마다 캐시 키·복원된 캐시가 다르다 | 실패한 3번과 성공한 4번 shard 로그의 `Cache hit for restore-key`를 비교 | **기각.** 둘 다 같은 `verify[...]-241f9802...` 항목을 복원했다. shard의 정확한 키(`app-tests[...]-<sha>`)는 어떤 job도 저장하지 않으므로(shard는 읽기 전용) 항상 다른 job(`verify`)의 항목으로 대체된다 |
| H2. 시작 시각이 달랐다 | 같은 실행의 job `started_at` | **기각.** 3번 shard는 다른 shard와 같은 초에 시작했다(05:05:23~24, 07:01:22) |
| H3. shard마다 받는 jar가 다르다 | draft PR #598에서 shard 4개가 테스트 전에 새로 받은 jar를 센 값 | **기각.** 12 shard-job 모두 같은 5개(위 목록)를 받았다 |
| H4. Maven Central이 egress IP 단위로 일시 차단한다 | Sonatype 공식 FAQ | **정황은 맞고 검증하지 못했다.** 아래 참조 |
| H5. 우연 | 위 확률 | **배제하지 못했다.** 표본이 3건이다 |

H4의 근거: [Sonatype의 429 안내](https://central.sonatype.org/faq/429-error/)는 한도를 "조직·네트워크·CI 인프라의 합산 트래픽"으로 매기고 초기 차단은 의도적으로 짧다고 쓴다. [공유 egress 안내](https://central.sonatype.org/faq/429-shared-egress/)는 호스티드 CI가 여러 조직과 같은 IP를 쓰면 개별 사용량이 합리적이어도 한도에 걸릴 수 있다고 쓴다. 이 저장소는 GitHub 호스티드 러너를 쓰므로 4개 VM이 같은 초에 시작해도 VM마다 다른 출구 IP와 그 IP의 현재 상태를 만난다고 보는 것이 가장 단순한 설명이다. 러너 IP는 로그에 남지 않아 **이것을 직접 확인하지는 못했다.** 3건이 약 13시간 반 안에 몰려 있다는 사실(11-23 17:46~11-24 07:01)은 시간 단위 차단과 맞지만 표본이 3건이라 증거로 부족하다.

### 3. 결함의 구조적 원인은 로그로 확인됐다

429가 "왜 났는가"는 설명하지 못했지만 "왜 노출되는가"는 확인했다. draft PR #598은 테스트 전에 `:atlas-application:testRuntimeClasspath`를 해석하고 `~/.gradle/caches/modules-2/files-2.1`의 jar 수를 전후로 세는 진단 step을 넣었다(저장소에 병합하지 않음).

- shard가 복원한 캐시(`verify`가 저장한 것)에는 jar 242개(150 MB)가 있었다. 해석 뒤에는 247개(162 MB)였다. **새로 받은 5개가 429가 난 5개와 같다.** application 테스트의 런타임 전용 전이 의존성(`kafka-clients`의 snappy·lz4·zstd, `micrometer-registry-prometheus`의 exposition formats)이고, `verify`는 `check -x :atlas-application:test`라 `testRuntimeClasspath`를 해석하지 않는다.
- shard 4개는 캐시를 읽기만 하므로 **모든 실행, 모든 shard가 이 5개를 Maven Central에서 다시 받는다**: 실행당 jar 요청 20건(4 × 5). 12 shard-job(3회 × 4) 전부 5개였다. 해석과 다운로드에 걸린 시간은 중앙값 6.0초(범위 2.8~9.0초, n=12)다.
- 단일 job 구조에서는 같은 job이 이 jar를 받고 main에서 캐시에 저장했으므로 PR은 받지 않았다. 124건에서 429가 0건이었다는 사실과 맞다. 샤딩이 이 의존성의 캐시 소유자를 없앴다는 것이 원인에 가장 가깝다.
- 이 구조는 [setup-gradle 문서](https://github.com/gradle/actions/blob/ed408507eac070d1f99cc633dbcf757c94c7933a/docs/setup-gradle.md#select-which-jobs-should-write-to-the-cache)가 `cache-read-only` shard의 대가("추가 의존성을 다시 받는다")로 직접 적고 해법으로 "캐시를 쓰는 job에 모든 의존성을 받는 단계를 추가"를 권하는 경우와 같다.

### 4. Gradle의 재시도는 이미 있고, 짧다

내가 연 Gradle 사용자 안내서 페이지(build_environment, dependency_resolution 등 5개)에는 이 재시도 속성이 없었다. 9.8.2 배포본의 바이트코드(`ErrorHandlingModuleComponentRepository`, `NetworkingIssueVerifier`)를 `javap`로 읽었다.

- 408과 429는 일시적 오류로 보고 재시도한다.
- 재시도 횟수는 시스템 속성 `org.gradle.internal.repository.max.tentatives`(기본 3), 첫 대기는 `org.gradle.internal.repository.initial.backoff`(기본 1000ms)이고 대기는 두 배씩 늘어난다. 기본값이면 총 대기가 3초 안팎이다.
- 횟수를 다 쓰면 그 저장소는 이후 빌드 내내 비활성화된다(`Repository %s is disabled due to earlier error`). 실패 로그에서 이 문구는 보이지 않았다(기본 로그 수준).
- **이 속성들은 공식 문서에 없다.** 바이트코드에서 찾은 값이라 다음 Gradle 버전에서 바뀔 수 있다.

### 5. job별로 반복하는 일은 작다(실측)

draft PR #598의 `--profile` 보고서(shard 4개 × 2회 = 8개, `verify` 2개)를 파싱했다. 단위는 초다.

| 항목 | shard 8개 | verify 2개 |
| --- | --- | --- |
| Gradle 총 시간 | 100.6~156.2(평균 약 134) | 10.6, 16.9 |
| JVM 시작 + 설정 | 3.5~5.9 | 6.3~8.8 |
| `test` 외 task 합(컴파일·jar 포함) | 2.4~4.0(평균 3.4) | 4.7, 5.8 |
| `:atlas-application:test` | 95.2~147.5(평균 약 125) | (다른 모듈 test 8개, 합 0.5~0.6) |
| 의존성 해석(configuration별 보고서 합) | 0.5~0.9 | 1.1~1.3 |

- shard의 컴파일 task는 전부 `FROM-CACHE`다(Gradle 요약: task 19개 중 실행 10, 캐시 9). verify에서 다른 모듈의 `test`도 `FROM-CACHE`/`UP-TO-DATE`라 0.1초 안팎이다.
- shard의 Gradle 시간에서 `test`가 차지하는 비율은 약 94%다(평균 126초 / 134초). job 시간으로 보면 중앙값 162초(2026-11-19~24의 성공 실행 113건 기준, Gradle step 중앙값 148.5초) 중 Gradle 밖의 체크아웃·setup·캐시 복원은 약 13초다.
- 따라서 **"job마다 같은 의존성 해석·컴파일을 반복한다"는 추정은 크지 않았다**: shard당 test 밖의 Gradle 작업은 평균 약 8.6초(JVM 시작·설정 5.2 + 컴파일·jar 등 3.4)이고, jar 다운로드 약 6초(진단 step 중앙값, 별도 JVM에서 잰 값이라 상한에 가깝다)가 `test` task 시간 안에 섞여 있다. 4 shard 합 약 58초는 job 합계 약 11.7분의 8% 이하다. 한 job이 빌드해 산출물을 공유하는 방식(d)은 줄일 수 있는 양이 이 크기이므로 업로드·다운로드 비용과 복잡도를 감당하지 못한다.

### 6. `docs-lint.yml` 실패는 16건이었고 결함 4건이 섞여 있었다

같은 기간 300건 중 실패 16건은 모두 `PR checklist` job이었다(`Markdown lint`는 0건).

| 원인 | 건수 | 분류 |
| --- | --- | --- |
| PR 본문의 "요구사항·결정·규칙 ID" 칸 | 10 | 작성 실수(검사가 의도대로 막음) |
| PR 본문의 Issues 형식 | 1 | 작성 실수 |
| `fixup!` 커밋 제목 | 1 | 작성 실수 |
| **이미 병합된 PR의 `edited` 이벤트**에서 `fatal: Invalid revision range` | 4 | **워크플로 결함** |

결함 4건(#568, #569, #571, #573)은 모두 이미 병합된 PR이 06:26Z(병합 8~81분 뒤)에 갱신되어(`edited`) 실행됐고 `git log BASE..HEAD`가 `Invalid revision range`로 실패했다. head 커밋이 체크아웃에 없다는 것까지는 로그로 확인했다. 병합 때 head 브랜치가 지워졌기 때문이라는 설명은 정황이다(#573은 병합 2분 뒤 `head_ref_deleted` 이벤트를 확인했고 나머지 3건은 확인하지 않았다). 병합된 PR에 빨간 표시만 남는 소음이고 병합을 막지는 않았다. 또한 작성 실수 12건 중 본문 검사 11건은 `console.error`만 써서 check annotation이 "exit code 1"뿐이었다(메시지는 전체 로그에만 있다).

## 대안 비교

| 대안 | 판단 | 이유 |
| --- | --- | --- |
| (a) verify가 testRuntimeClasspath를 받아 캐시에 넣는다 | **채택** | 원인(캐시 소유자 부재)을 직접 고친다. 공식 문서가 권한 방식이다. 새 외부 의존이 없다 |
| (b) 저장소 미러 | 기각 | 외부 서비스가 둘째 신뢰 근원이 된다. `gradle/verification-metadata.xml`이 없어 미러가 바꾼 jar를 검증하지 못한다. 공식 문서로 신뢰할 수 있는 미러를 이 작업에서 확인하지 못했다. Sonatype은 저장소 관리자(캐싱 프록시)를 권하나 이 저장소 규모에는 과하다 |
| (c) Gradle 재시도 상향 | 기각 | Sonatype이 "더 세게 재시도하지 말라, 더 나빠진다"고 쓴다. 속성은 문서에 없다. 차단 길이를 몰라 효과를 검증하지 못한다(429를 인위로 만들 수 없다) |
| (d) 한 job이 빌드해 공유 | 기각 | 위 5절: 줄일 일이 job당 한 자릿수 %다 |
| (e) shard도 캐시를 쓴다 | 기각 | shard 4개가 각자 항목을 쓰고 의존성 항목이 달라지면 10 GB 한도(현재 3.3 GB)를 잠식한다. setup-gradle 문서가 경고한다 |
| (f) 실패 shard 자동 재실행 | 보류 | 새 구조에서 남는 위험은 의존성을 바꾼 PR뿐이다. 그때는 수동 재실행으로 충분한지 먼저 본다 |

## 적용과 측정

변경은 두 가지다. `verify`의 Gradle 호출에 모든 프로젝트의 `testRuntimeClasspath`를 해석하는 CI 전용 init script task(`.github/gradle/resolve-test-runtime-classpaths.init.gradle`)를 더한다. 앱 테스트 shard가 새로 받은 jar 수를 요약에 남긴다.

초안은 init script를 root project의 task 하나에서 모든 프로젝트의 configuration을 해석하게 만들었고 **로컬 실행에서 `Resolution of the configuration ':atlas-account:testRuntimeClasspath' was attempted without an exclusive lock`로 실패했다.** 그래서 프로젝트마다 자기 configuration을 해석하는 task를 만들고 이름만 `./gradlew ... resolveTestRuntimeClasspath`로 불러 모든 프로젝트에서 실행되게 했다.

### 측정 조건

- 러너: GitHub 호스티드 `ubuntu-latest`, 2026-11-24 11:46~12:45Z. 같은 PR(draft #598)에서 `gh run rerun`으로 같은 커밋을 반복했다. 진단 step은 전후 모두 같다.
- 변경 측정은 PR 실행에서도 verify가 캐시를 쓰게 한 임시 설정(`cache-read-only: false`)이다. PR ref 범위에 저장된 캐시를 다음 반복이 읽는다. 운영 설정에는 넣지 않았고 main push에서는 원래 쓰기다. 첫 실행(워밍)은 shard가 verify와 동시에 시작해 캐시가 아직 없으므로 측정에서 뺐다.

### 결과: shard가 Maven Central에서 받는 jar

| 구분 | 반복 | shard-job | 새로 받은 jar | 해석+다운로드(진단 step, 초) |
| --- | --- | --- | --- | --- |
| 변경 전 | 3 | 12 | **5개씩(12/12)** | 중앙값 6.0, 범위 2.8~9.0 |
| 변경 후(워밍 뒤) | 3 | 12 | **0개(12/12)** | 중앙값 3.6, 범위 1.6~7.2 |

변경 후에도 해석에 1.6~7.2초가 든다. 다운로드가 아니라 메타데이터 해석과 Gradle 시작 이후 처리이며 이 크기는 이번 조사에서 더 쪼개지 않았다.

### 결과: 운영에 넣을 변경 그대로(draft PR #603)

운영에 넣을 워크플로 그대로이고 `verify`의 PR 캐시 쓰기만 임시로 켰다. 첫 실행은 캐시가 없는 상태, 이어진 재실행 3회는 PR ref에 저장된 캐시를 읽는 상태다. 새로 받은 jar 수는 운영 워크플로의 `DOWNLOADED_JARS` 요약(파일 시각 기준)으로 셌고, 진단 PR의 전후 파일 수 비교와 같은 값(5, 0)을 냈다.

| 구분 | 실행 | shard가 새로 받은 jar | job 합계(분) | 벽시계(분) |
| --- | --- | --- | --- | --- |
| 변경 없는 main의 11월 24일 성공 실행 | 62 | (진단 PR 기준 5개씩) | 중앙값 11.74, 표준편차 0.95, 범위 9.88~13.75 | 중앙값 3.25 |
| 변경 후 첫 실행(캐시 없음) | 1 | 4개 shard 모두 5개 | 10.93 | 3.18 |
| 변경 후 재실행(캐시 있음) | 3 | **12개 shard-job 모두 0개** | 11.37, 11.25, 9.77(중앙값 11.25, 표준편차 0.73) | 3.30, 3.17, 3.10 |

- **job 합계의 차이는 주장하지 않는다.** 변경 후 3회는 변경 없는 62건의 분포 안에 있고, 표본이 3개이며, 개선 추정치(shard당 수 초, 진단 step 중앙값 차이 2.4초 × 4 shard ≈ 10초)는 표준편차(0.95분 = 57초)보다 작다. 진단 PR의 변경 전 3회(11.98, 11.78, 11.30분)와도 구분되지 않는다.
- `verify` job은 변경 후 재실행에서 33~40초였고 11월 19~24일 성공 실행 113건의 중앙값은 59초지만, 이 PR은 워크플로만 바꿔 Gradle 빌드 캐시가 전부 적중하므로 같은 조건의 비교가 아니다. 이 변경이 `verify`에 더하는 시간(jar 5개를 받는 2~3초)은 따로 분리해 재지 않았다.
- 4개 shard의 시간 편차(재실행 3회에서 118~182초)는 [EXP-069](EXP-069-ci-shard-imbalance.md)의 주제이고 이 변경과 무관하다.

### docs-lint 수정 확인

- 닫은 PR #603의 제목을 수정하자(`edited`) 수정한 워크플로에서 `PR checklist`가 `skipped`로 끝났다(`Markdown lint`는 `success`).
- 같은 PR을 다시 열고 본문을 일부러 틀리게 하자 `PR checklist`가 실패했고 check annotation에 `PR 본문 검토 체크리스트: PR 본문에 template 기준 9개 검토 항목을 포함해야 합니다.`가 보였다(수정 전에는 `Process completed with exit code 1`뿐이었다).
- 병합된 PR에서 `Invalid revision range`가 나던 것을 **같은 방식으로 재현하지는 못했다**(병합된 PR을 만들 수 없는 실험 PR이다). 수정은 그 실패 조건(병합·종료된 PR의 `edited`)에서 job을 건너뛰게 한 것이고, 건너뛰기가 동작함은 위에서 확인했다.

## 해석과 한계

- **429의 원인은 설명하지 못했다.** 실패의 직접 원인(Maven Central이 429를 돌려줌)과 노출 구조(shard 4개가 매 실행 같은 jar 5개를 받음)는 확인했다. 그 VM이 왜 차단됐는지는 IP를 못 봐서 확인하지 못했다. "3번 shard"는 표본 3건에서 우연과 구별되지 않았다.
- 변경은 429를 없애는 것이 아니라 **429에 노출되는 요청을 실행당 20건에서 0건으로 줄인다**(캐시가 데워진 정상 경로). 의존성을 바꾼 PR(캐시 미적중)에서는 verify와 shard가 각자 다시 받으므로 같은 위험이 남는다.
- 병합 직후 첫 main push 실행이 캐시를 쓰기 전까지는 PR의 shard도 5개를 받는다. 이 변경 자체의 PR 실행에서 요약이 5를 보이는 것이 정상이다.
- 시간 단축은 주장하지 않는다. 변경 후 job 합계는 표본이 작고(3회) 실행 편차(11월 24일 성공 실행 62건의 중앙값 11.7분, 표준편차 0.95분)가 개선 추정치(shard당 수 초)보다 훨씬 크다.
- 429가 아닌 테스트 실패는 이 문서의 범위가 아니다. 샤딩 이후 실패 2건은 `LocationFaultConvergenceIntegrationTest` 1건 실패였다(타이밍 의존 테스트).

## 재현

```sh
# 실패 분류(실행 로그)
gh run view <run id> --log-failed | grep -E 'Received status code|Could not download'
# 진단: 캐시 복원 직후와 shard 테스트 직전의 jar 수
find ~/.gradle/caches/modules-2/files-2.1 -type f -name '*.jar' | wc -l
# init script 로컬 확인
./gradlew -I .github/gradle/resolve-test-runtime-classpaths.init.gradle resolveTestRuntimeClasspath
```
