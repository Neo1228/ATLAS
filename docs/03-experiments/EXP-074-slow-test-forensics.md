# EXP-074: 앱 테스트의 정체 불명 대기 구간은 무엇이고, 간헐 실패는 무엇인가

- 질문: [EXP-069](EXP-069-ci-shard-imbalance.md)가 남긴 "같은 클래스가 2~4초와 22~35초를 오가고 클래스 시간 합의 절반이 테스트 메서드·Spring 기동 밖에서 쓰인다"의 정체는 무엇인가. 그리고 `LocationFaultConvergenceIntegrationTest`가 CI에서 세 번 실패한 원인은 테스트 대기 부족인가, 수렴 결함인가.
- 상태: 조사와 테스트 코드 수정. 시간 측정은 로컬(8 vCPU Mac, Colima) 값이고 CI 러너에서 재지 않았다.
- 날짜: 2026-11-24
- 관련: Issue #608, [EXP-069](EXP-069-ci-shard-imbalance.md), [EXP-030](EXP-030-ci-build-duration.md), [ADR-0070](../02-decisions/adr/ADR-0070-parallel-backend-ci.md)

## 한 줄 결론

비싼 구간의 정체를 스레드 덤프로 확정했다. **끝난 클래스의 Spring 컨텍스트가 캐시에 살아 있고, 그 컨텍스트의 `@Scheduled` 작업이 이미 사라진 컨테이너의 DB에서 Hikari `getConnection`(기본 30초)에 막혀 있다. 테스트 워커는 그 컨텍스트를 일시 정지·퇴출·종료하는 순간 그 작업이 끝나기를 기다린다.** 클래스 종료 때 컨텍스트를 바로 닫는 리스너 하나로 클래스 시간 합이 약 390초에서 225초로, 최대/최소 편차가 0.65초에서 0.15초로 줄었다. 간헐 실패는 로컬에서 재현했고(base 4/900, 수정 0/900), 원인은 **테스트가 한 번만 부르고 끝내는 호출을 실제 호출자는 다시 부른다는 점**이다. 제품 결함인지는 확정하지 못했다(아래).

## 방법: 재현 하네스(저장소 밖 `/tmp/slow`)

`:atlas-application:test`를 fork 1개로 돌리면서 같은 시각에 다음을 기록한다. 하네스 자체는 저장소에 넣지 않았다(실험 도구이고 CI에 필요 없다).

- Gradle init script로 클래스 시작·종료 시각(ms), 테스트 JVM(`Gradle Test Executor`)에 `jcmd <pid> Thread.print`를 2초 간격(덤프 자체 0.13~0.27초)으로 수집, `docker ps` 수, load average, PostgreSQL 컨테이너의 `pg_stat_activity`.
- 모든 실행은 전역 잠금(`/tmp/atlas-fullcheck.lockdir`) 아래에서 `--no-build-cache`로 했고 load는 시작→끝으로 기록했다.
- 스레드 이름 집계, Test worker 스택 단계 분류(시간 = 덤프 간격 근사)는 스크립트로 했다.

## 비싼 구간의 정체(스레드 덤프 증거)

기준 실행(`base1`, fork 1, 클래스 115개)에서 `SellerOrderDiscoveryIntegrationTest`는 22초였고 테스트 메서드는 0.3초였다. 그 22초 동안 모든 덤프에서 Test worker는 컨텍스트 캐시 안에 있었다.

```text
"Test worker" #3 ... TIMED_WAITING (parking)
  at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:276)
  at org.springframework.context.support.DefaultLifecycleProcessor$LifecycleGroup.stop(DefaultLifecycleProcessor.java:654)
  at org.springframework.context.support.DefaultLifecycleProcessor.stopBeans(DefaultLifecycleProcessor.java:448)
  at org.springframework.context.support.DefaultLifecycleProcessor.onPause(DefaultLifecycleProcessor.java:330)
  at org.springframework.context.support.AbstractApplicationContext.pause(AbstractApplicationContext.java:1602)
  at org.springframework.test.context.cache.DefaultContextCache.pauseIfNecessary(DefaultContextCache.java:313)
  at org.springframework.test.context.cache.DefaultContextCache.pauseOnContextSwitchIfNecessary(DefaultContextCache.java:293)
  at org.springframework.test.context.cache.DefaultContextCache.put(DefaultContextCache.java:213)
  at org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate.loadContext(...)
```

같은 덤프에서 이전 클래스 컨텍스트의 스케줄러 스레드는 이 스택에 있었다(발췌).

```text
"atlas-scheduler-4" ... TIMED_WAITING (parking)
  at java.util.concurrent.SynchronousQueue.poll
  at com.zaxxer.hikari.util.ConcurrentBag.borrow
  at com.zaxxer.hikari.pool.HikariPool.getConnection
  at org.springframework.jdbc.core.JdbcTemplate.queryForObject
  at com.atlas.settlement.repository.SettlementPayoutRepository.negativePayableExposure
  at com.atlas.settlement.service.impl.SettlementPayoutServiceImpl.riskSnapshot
```

- 일시 정지 단계의 덤프 26개 전부에서 이 스케줄러 스레드가 같은 위치에 있었다.
- 다른 비싼 구간은 `DefaultContextCache.evictLruContextIfNecessary → HikariDataSource.close → HikariPool.shutdown → awaitTermination`(퇴출한 컨텍스트의 풀을 닫는 중)이었다. 이것도 EXP-069 H4가 기각한 "캐시가 찼다"가 아니라, 캐시가 32에 닿기 전에도 컨텍스트가 살아 있다는 뜻이다. 퇴출 단계 12개 덤프에서 `HikariPool-N:connection-adder`가 죽은 DB에 재연결하며 잠자는 스택이 169회 보였다.
- 누적: 클래스 순번 15에서 Hikari 풀 10개, 스레드 129개였고 순번 120에서 풀 31개, 스레드 355개, `atlas-*` 스케줄러 스레드 124개, `HttpClient-SelectorManager` 87개로 **클래스를 지날수록 단조 증가**했다(`base1`, 풀 수는 서로 다른 `HikariPool-N` 이름 수). 로그에는 `HikariPool-N - Failed to validate connection`이 풀 8개에서 5~10회씩 남았다.
- JVM 종료 때도 같은 일이 있다. `base1`에서 마지막 클래스 뒤 약 78초 동안 Test worker가 종료 훅으로 남은 컨텍스트를 닫았다. CI 로그(run 45348273434, shard 4개)에서도 `ionShutdownHook` 로그 구간이 31~63초였다. 이 시간은 클래스 시간 합에 들어가지 않고 shard의 step 시간에는 들어간다.
- 컨테이너는 `@Container static`이라 클래스가 끝나면 사라진다. 컨텍스트의 DataSource URL은 `@DynamicPropertySource`로 그 컨테이너를 가리키므로 **다른 클래스가 그 컨텍스트를 재사용할 수 없다**. 즉 캐시에 남는 컨텍스트는 어떤 클래스에도 쓸모가 없다(앱 테스트 클래스 120개 중 `@SpringBootTest`+static `@Container`가 52개).

## 가설과 실험(한 번에 하나, 같은 하네스)

| 가설 | 바꾼 것 | 결과 | 판정 |
| --- | --- | --- | --- |
| H5. 일시 정지(Spring 7.0 context pausing)가 죽은 DB에 막힌 스케줄러를 기다린다 | `-Dspring.test.context.cache.pause=never` | 정지 단계 107·89초 → 0초. 클래스 합 411·367 → 298·293초. 그러나 퇴출 단계가 38 → 73초로 늘고 종료 단계도 늘어 벽시계 시간은 거의 같음(500·447 → 439·470초) | 일부 지지. 증상을 옮길 뿐 |
| H6. 대기 시간은 Hikari `connectionTimeout`(30초) | `spring.datasource.hikari.connection-timeout=3000` | `JdbcExpirySweepLockRepositoryTest` 34 → 2초, 클래스 합 275·266초, 벽시계 349·342초 | **지지**. 공백이 시간 제한에 비례해 줄었다 |
| H7. 막히는 작업은 `PayoutRiskMetrics.refresh`다 | 그 `@Scheduled`의 initial-delay·poll-interval을 24시간으로 | 클래스 합 274초, 정지 단계 0초(`D1`, 1회) | **지지**(표본 1회). 스택의 `riskSnapshot` 호출자와 일치 |
| H8. 클래스가 끝날 때 컨테이너가 살아 있는 동안 컨텍스트를 닫으면 대기가 없다 | `ContainerBoundContextCloser`(아래) | 클래스 합 223·226·226초, 벽시계 230·232·232초 | **지지** |

H7과 H6이 같이 설명하는 구조: 컨텍스트는 컨테이너보다 오래 살고, 10초 뒤부터 30초 간격으로 도는 작업이 죽은 DB를 두드리며 풀 대기 30초에 걸리고, 그 순간 워커 스레드가 컨텍스트를 건드리면 같이 기다린다. 어느 클래스가 비싸 보이느냐는 "직전에 어떤 컨텍스트가 살아 있었고 그 작업이 마침 대기 중이었는가"로 정해지므로 실행마다 달라진다. 이것이 EXP-069의 사실 5(같은 클래스가 2초와 32초 사이를 오감)를 설명한다.

기각된 것: EXP-069 H2(fork CPU 경쟁), H4(캐시 한도 32). 스케줄러 풀 스레드 자체는 `waitForTasksToCompleteOnShutdown(true)`+10초 대기이고, 스택이 대기 중인 정확한 잠금(스케줄러 `stop`의 10초인지 풀 닫기인지)은 라이브러리 소스에서 끝까지 확인하지 않았다. 이 부분은 덤프의 스택까지만 확인했다.

## 수정: 클래스가 끝나면 그 컨텍스트를 닫는다

`ContainerBoundContextCloser`(테스트 `TestExecutionListener`, `META-INF/spring.factories`로 등록)가 `afterTestClass`에서 **테스트 클래스(와 상위 클래스)에 static `@Container`가 있으면** `markApplicationContextDirty(EXHAUSTIVE)`로 컨텍스트를 캐시에서 빼고 닫는다. 컨테이너가 아직 살아 있을 때 닫혀야 풀이 정상 종료되므로, 클래스 어노테이션이 `@SpringBootTest`, `@Testcontainers` 순서이던 4개 클래스(`AccountAuthenticationIntegrationTest`, `CatalogIntegrationTest`, `ProductCategoryHierarchyIntegrationTest`, `PayPalReportMatchQueryIntegrationTest`)를 나머지 48개와 같은 `@Testcontainers`, `@SpringBootTest` 순서로 맞췄다(JUnit은 확장 `afterAll`을 등록 역순으로 부른다). `main` 코드는 건드리지 않았다.

버린 대안: (1) 전역 `pause=never`는 증상을 옮길 뿐이다(H5). (2) `connectionTimeout`을 3초로 줄이면 대기는 줄지만 풀·스케줄러 스레드가 계속 쌓이고 의미 있는 연결 실패도 3초에 끊는다(H6은 원인 확인용). (3) 모든 클래스에 `@DirtiesContext`: 이미 9개 클래스에 있으나 호출 규칙이 사람에게 맡겨진다. 리스너는 규칙 하나로 막고, `@Container static`이 있는 클래스에만 적용돼 컨테이너 없이 컨텍스트를 공유하는 테스트에는 영향이 없다.

### 전후 측정(같은 하네스, fork 1, `--no-build-cache`, 각 실행은 전역 잠금 아래)

| 실행 | 벽시계(초) | 클래스 합(초) | 15초 초과 클래스 | 최대 클래스(초) | Split | JdbcExpiry | LocFault | 일시 정지 단계(초) | 최대 Hikari 풀 | load 시작→끝 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 기준 | 500 | 411 | 6 | 34.1 | 13.1 | 34.1 | 18.2 | 107 | 32 | 3.68→2.41 |
| A2 기준 | 447 | 367 | 6 | 34.3 | 12.1 | 34.3 | 18.4 | 89 | 32 | 2.80→3.42 |
| F1 수정 | 230 | 223 | 1 | 17.3 | 7.7 | 1.6 | 14.3 | 0 | 1 | 14.80→5.67 |
| F2 수정 | 232 | 226 | 1 | 17.1 | 7.7 | 1.6 | 14.2 | 0 | 1 | 5.67→5.11 |
| F3 수정 | 232 | 226 | 2 | 17.2 | 9.2 | 1.7 | 15.4 | 0 | 1 | 5.11→4.96 |

- 기준 A는 2회뿐이다(3회째는 다른 측정에 장비를 넘기려고 중단했다). 같은 클래스의 실행 간 최대-최소 차이 평균은 기준 0.65초(최대 30.7초, `PoolExhaustionResponseIntegrationTest`), 수정 0.15초(최대 1.5초)다. 기준 쪽 표본은 둘이라 분산 추정이 거칠다.
- `base1`(리스너·init script 이전의 첫 전체 실행, 클래스 합 328초, 벽시계 약 410초)과 A1·A2 사이에도 100초 가까운 차이가 있어 기준 자체가 흔들린다는 점이 EXP-069와 같다. 수정 후 세 실행은 합 223~226초로 모였다.
- 실험 H5~H8의 나머지 값: B1·B2(합 298·293), C1·C2(275·266), D1(274). 15초 넘는 클래스는 수정 후에도 `LocationReservationServiceIntegrationTest`(17초), `LocationFaultConvergenceIntegrationTest`(14초), `BuyerFlowIntegrationTest`(14초)이고 이쪽은 테스트 메서드 시간이 대부분이다.
- 수정 F 세 실행은 load가 첫 실행 시작 때 14.8로 높았다(바로 앞의 CPU 점유 실험 직후). 이 오염이 있어도 수정 쪽이 좋게 나왔지만 조건이 완전히 같지는 않다. 로컬 8 vCPU 값이고 CI 러너(ubuntu-latest)에서는 재지 않았다. CI에서 얼마가 줄지는 **추정**이다(CI의 종료 훅 구간 31~63초와 shard 편차가 같은 원인이라는 정황까지만 확인).
- 수정 후 `:atlas-application:test` 전 실행에서 실패 0건(tests 전부 통과).

## `LocationFaultConvergenceIntegrationTest` 간헐 실패

### CI 세 실행의 공통점(아티팩트 `test-results-app-3`를 받아 확인)

실행 45181797918(main 2026-11-23 13:13), 45231697943(main 17:48), 45342680146(PR #578, 2026-11-24 06:46)은 모두 **`Application tests (3/4)` shard**에서 `concurrentClaimAndCancelOnTheSameWorkflowNeverDiverge(boolean) faulty=true`의 311번 줄 단언 `[order holds stock the location does not] expected: 0 but was: 1`이 깨졌다. 세 실행 모두 `in_flight_after_recovery=0`, `thrown=0`, `orphan=0`, 그리고 `dangling=1`이다(예: `states={CANCELLED=37, CLAIMED=2, RESERVED=1}`). 즉 **복구 루프가 400회를 다 쓰지 못한 것이 아니다**(inFlight는 이미 0이었다). 테스트 시간은 정상 범위(1.1~1.3초)였다. 같은 파일의 다른 8개 테스트는 통과했다.

### 로컬 재현

- 기준(main `8f41d0e7`)에 실패 시 DB 상태와 두 location의 요청 이력을 남기는 진단 출력과 `faulty=true` 반복(30회, 300회)만 더해 돌렸다. 반복 횟수만 바꿨고 로직은 그대로다.
- 결과: R0 1/30, RB1 0/300, RB2 1/300, CPU 점유 프로세스 8개를 붙인 RBH 3/300(load 7→20). 합계 **5/930 실패**. 모두 같은 형태다.

```text
DANGLING claimOutcome=PENDING order=RESERVED a=CLAIMED b=RESERVED workflow=RESERVED attempts=1 error=INVENTORY_HTTP_503
  commands=...01:CLAIMED,...02:RESERVED
HISTORY A [reservations:OK 201, claim:OK 200]
HISTORY B [reservations:OK 201, claim:HTTP_503]
```

claim이 location A에는 적용되고 B에서 503을 받아 `PENDING`을 돌려준 뒤 workflow가 `RESERVED`로 남았고, 같은 시각에 들어온 cancel 2건은 효과가 없었다. `LocationReservationRepository.requestCancellation`은 **claim 호출 중인 lease를 빼앗지 않는다**("claim 결과가 기록된 뒤 취소가 claim을 되돌린다"는 코드 주석). 그 뒤 장애 주입이 멈추고 테스트는 cancel을 다시 보내지 않으므로 상태가 그대로 남아 감사가 `dangling=1`로 센다.

### 분류

- **테스트 대기 부족이 아니다**: 400회 루프는 inFlight 0으로 끝났다. 타임아웃을 늘려도 이 실패는 바뀌지 않는다. 그래서 타임아웃은 건드리지 않았다.
- **테스트가 호출자의 재시도를 빼먹었다**: 실제 호출자 두 곳(`CheckoutPaymentServiceImpl.claimRemoteInventory`는 PENDING이면 결제 상태를 그대로 두고, `finishRemoteCancellation`과 `CheckoutFinalizationServiceImpl.compensate`는 cancel 응답이 종결 상태가 아니면 끝내지 않고 반환)는 종결하지 못하면 체크아웃 상태를 유지해 나중에 다시 호출하는 구조다. 테스트는 각 호출을 한 번만 보내고 끝낸다. 수정은 종결하지 못한 claim·cancel을 최대 20라운드 동안 다시 보내도록 했다(복구 루프 `recoverDue`와 같은 25ms 간격). 수정 후 **0/930**(RF1 0/300, RF2 0/300, CPU 점유 RFH 0/300).
- 통계는 약하다. 기준 5/930 대 수정 0/930은 같은 신호와 같은 메커니즘이 있어 설득력이 있지만 표본이 작고 기준 실행끼리도 0/300과 3/300으로 흔들린다.

### 제품 결함 가능성(확정하지 못함, 보고)

위 해석은 "호출자가 항상 다시 부른다"는 전제 위에 있다. 두 호출자가 비종결 응답을 돌려주는 것까지는 코드로 확인했지만, **체크아웃 상태 기계가 모든 경로에서 claim/cancel을 반드시 다시 구동하는지(특히 `CANCEL_PENDING` 체크아웃의 재시도 주체)는 끝까지 확인하지 못했다.** 한 location만 claim되고 cancel 요청이 무시된 workflow가 `RESERVED`로 남는 동안 재고가 한 location에서 `CLAIMED`로 묶여 있다. 재시도 주체가 없다면 이것은 제품 결함이다. 후속으로 "claim 중 lease 때문에 무시된 cancel을 `CANCEL_PENDING`으로 기록해 두고 claim 종료 후 복구 워커가 처리하는가"를 확인할 것을 제안한다.

## 한계와 약한 곳

- 시간은 8 vCPU Mac(Colima VM 8 vCPU, 12.6GiB)의 fork 1개 값이다. CI(ubuntu-latest, `ATLAS_TEST_FORKS=2`, shard)에서는 측정하지 않았다. 종료 훅 구간 31~63초는 CI 로그에서 본 정황일 뿐 같은 원인이라는 증명이 아니다.
- 기준 A는 2회, H6·H7(C, D)은 2회·1회다. "최소 3회"를 채운 것은 수정 F뿐이다. H7은 표본 1회라 확정이 아니라 일치다.
- 로드는 다른 에이전트의 Docker·Gradle 작업과 겹쳤을 수 있다. 전역 잠금으로 Gradle은 직렬화했지만 load는 2.4~5.7 사이에서 변했다.
- `PayoutRiskMetrics`가 유일한 막힌 작업이라는 증명은 아니다. 스택에서 본 것이 그것이고, 그 작업을 끈 실험(D1)에서 정지 단계가 0이었다.
- `ContainerBoundContextCloser`는 컨텍스트 캐시를 클래스 간에 재사용하지 않게 한다. 이미 재사용되지 않던 컨텍스트(서로 다른 컨테이너 URL)에만 적용했지만, 앞으로 컨테이너를 공유하는 클래스(JVM당 한 번 띄우는 방식)를 만들면 리스너가 그 이점을 지운다. 그때는 이 리스너의 조건(static `@Container`)을 다시 봐야 한다.
- 일시 정지는 Spring Framework 7.0의 새 기능이다(공식 문서 확인). 이 저장소가 Spring Boot 4.1.2, Spring Framework 7.0.9를 쓰는 동안만 해당된다.

## 롤백

`META-INF/spring.factories`와 `ContainerBoundContextCloser*.java`를 지우면 원래 동작으로 돌아간다. 어노테이션 순서 변경 4곳과 `LocationFaultConvergenceIntegrationTest`의 재시도·진단 출력은 독립적으로 되돌릴 수 있다. 다른 테스트가 컨텍스트가 닫히는 것에 기대면 해당 클래스에서만 실패한다(전체 실행에서는 발견되지 않았다).
