# EXP-030: Backend CI / Gradle build duration

**상태:** 로컬 실측 · GitHub Actions step 측정 · 운영 SLA 아님

## 목적과 범위

`Backend CI`의 wall-clock을 측정해 병목을 특정하고, 최적화 변경의 before/after를 동일 조건에서 비교한다. 대상은 저장소 자체의 build/test 실행 시간이며, 애플리케이션 런타임 성능은 범위 밖이다.

## 측정 방법

- **CI 실측:** `gh api repos/.../actions/runs/<id>/jobs`의 step별 `started_at`/`completed_at` 차이.
- **CI run 분포:** 최근 `backend-ci.yml` run 25개의 `createdAt`–`updatedAt` 차이(초).
- **로컬 baseline/after:** 같은 머신에서 `--rerun-tasks --no-build-cache --no-daemon`으로 `check` 전체를 재실행하고 `/usr/bin/time -p`의 `real` 값을 사용.
- 로컬 측정은 Docker 기반 PostgreSQL Testcontainers를 포함한다.

## 결과

### CI step 분해 (run 44040216748, main)

| Step | Duration |
| --- | ---: |
| Set up job | 3s |
| Checkout | 5s |
| setup-java (with Gradle cache) | 7s |
| `docker info` | 5s |
| **`./gradlew --no-daemon check`** | **218s** |
| post steps | 1s |

최근 25개 run의 전체 wall-clock은 중앙값 **약 255초**였고, Gradle 스텝이 그 대부분이었다.

### Gradle profile (atlas-application 포함 3개 모듈 재실행)

| 구간 | 시간 |
| --- | ---: |
| Startup | 2.231s |
| Loading/Configuring projects | 1.346s |
| **Task execution** | **3m18.57s** |
| `:atlas-application:test` | **2m58.11s** |
| `:atlas-application:compileTestJava` | 2.431s |
| `:atlas-application:compileJava` | 1.460s |

즉 설정·컴파일 최적화 여지는 작고, `:atlas-application:test`가 사실상 전부다. 이 모듈은 테스트 302개 중 39개가 Testcontainers를 쓰고 23개가 각기 다른 `@DynamicPropertySource` 조합으로 Spring context를 띄운다.

### Fork 병렬화 A/B (동일 머신, 전체 `check` 재실행)

| Test forks | wall-clock | baseline 대비 |
| --- | ---: | ---: |
| 1 (기존) | 202.49s | — |
| **2** | **133.43s** | **−34%** |
| 4 | 118.87s* | −41% |

\* forks=4는 `:atlas-application:test`만 측정한 값(180s→118.87s)이며, forks=2 대비 추가 이득은 약 4초에 그쳤다. 수익 체감이 뚜렷해 CI 기본값은 2로 정했다.

`:atlas-application:test` 단독: forks=1 **180s** → forks=2 **123s** → forks=4 **118.9s**.

## 해석과 한계

- 병목은 테스트 실행이며, 두 fork만으로도 측정된 이득의 대부분을 얻는다. 설정·컴파일·캐시 계층은 1~2초 수준이라 손댈 근거가 약하다.
- 남은 시간은 Spring context 기동(23개 조합)과 Testcontainers 시작 비용이다. 이를 더 줄이려면 context/DynamicPropertySource 공유, 컨테이너 재사용 같은 테스트 구조 변경이 필요하고, 테스트 격리를 깨뜨릴 위험이 있어 별도 근거가 필요하다.
- 로컬 측정은 11 vCPU 머신이고 GitHub Actions runner의 CPU/IO 특성이 다르다. 따라서 위 배수는 CI에서 그대로 재현된다고 주장하지 않으며, PR의 CI run에서 별도로 확인한다.
- CI run wall-clock에는 queue 대기 시간이 포함될 수 있다.

## 적용

- `build.gradle`: 모든 `Test` 태스크에 `maxParallelForks = ATLAS_TEST_FORKS`(로컬 기본 1), `forkEvery = 0`.
- `backend-ci.yml`: PR에서 이전 run 자동 취소, Gradle cache action, `ATLAS_TEST_FORKS=2`, 실패 시 test report artifact 업로드, `timeout-minutes: 25`.

## PR #312 실제 runner 결과

GitHub Actions runner는 `docker info`에서 **4 CPU / 15.61 GiB**로 확인했습니다. 단일 run은 환경 차이가 있어 원인 attribution으로 사용하지 않습니다.

| Run | 대상 | Gradle step | 관측 |
| --- | --- | ---: | --- |
| `44040216748` | 기존 main baseline | 218s | 61-task full check |
| `44099437861` | forks=2, cache warm-up 전 | 252s | 전체 job 272s; 개선을 보이지 않음 |
| `44101485246` | forks=2, 코드 변경 PR | 203s | 61 tasks all executed; 전체 job 238s |
| `44101459322` | 변경 없는 source 재실행 | 13s | 61개 중 50개 FROM-CACHE, `atlas-application:test`도 캐시 복원 |

**결론:** Local 11-vCPU forks=2의 −34%는 runner 결과로 일반화하지 않습니다. Full execution 한 샘플은 baseline보다 15s 짧았으나, 한 번의 paired run이 아니므로 forks 효과라고 단정하지 않습니다. 13s run은 test output cache-hit이라 fresh test 실행으로 계산하지 않습니다. CI는 사용자가 선택한 2 forks를 유지하고, 로컬 기본 fork는 개발 머신의 발열/메모리 부담을 줄이도록 1로 둡니다.

`gradle/actions/setup-gradle`의 build cache는 입력이 동일한 태스크 출력(테스트 결과 포함)을 복원할 수 있습니다. 따라서 CI 결과에는 **실제 실행**과 **cache restore**를 구분해 보고해야 합니다.
