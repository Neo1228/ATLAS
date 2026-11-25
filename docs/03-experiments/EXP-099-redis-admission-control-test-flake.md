# EXP-099: RedisStockAdmissionControlTest 간헐 실패는 어디서 나는가

- 질문: 로컬 전체 `clean check`(헤드 `7655f063`)에서 `RedisStockAdmissionControlTest.releaseReturnsQuantityOnceAndConfirmedAdmissionsAreNeverReturned`가 한 번 실패했고 CI는 실패하지 않았다. 원인은 무엇이고, 테스트 결함인가 운영 코드 결함인가?
- 상태: 분석 완료(단일 호스트 로컬 재현, CI 미측정). 가설·반증 조건·지표·통제는 해당 측정 전에 따로 커밋했고(커밋 이력이 증거), 사전 등록 이후에 추가한 가설(H6~H9)은 해당 절에 표시했다.
- 날짜: 2026-11-25
- 관련: [ADR-0066](../02-decisions/adr/ADR-0066-redis-admission-gate.md), [EXP-063](EXP-063-redis-gate-failopen-and-paired-rerun.md), [점검표](experiment-checklist.md)

## 문제

`./gradlew clean check`가 재현 가능하게 통과해야 하는데, 입장 게이트 Redis 테스트가 가끔 실패하면 이 저장소에서 병합 전 검증을 돌리는 1인 운영자와 에이전트 여러 명이 실패가 자기 변경 때문인지 알 수 없다. 실패는 한 번뿐이고 로컬 24분짜리 전체 빌드 안에서 났다. 확인된 사실은 둘이다. 첫째, 실패 지점은 테스트 파일 101행 `assertThat(gate.admit("c", one)).isFalse()`("Expecting value to be false but was true")다. 둘째, 이 테스트 파일과 운영 클래스는 이 실패 이전부터 바뀐 적이 없다(`git log`에 `4e3c883b` 한 커밋). 그 밖의 것(실행 시점 호스트 부하, 예외 클래스, 컨테이너 상태)은 기록이 없다.

코드를 읽고 세운 가설의 출발점: `RedisStockAdmissionControl.admit`은 Redis 호출이 `RuntimeException`을 던지면 거절하지 않고 **통과(true)** 시키고 2초 동안 Redis를 건드리지 않는다(fail-open, 코드 주석과 ADR-0066). 테스트는 `gate()`에서 명령 timeout을 200ms로 둔다. 따라서 `a`나 `b`나 `c`의 Redis 호출이 한 번이라도 200ms를 넘기거나 연결에 실패하면 `c`가 `true`가 되어 101행이 정확히 이 메시지로 실패한다.

## 가설과 반증 조건

| ID | 가설 | 반증 조건(이 관측이면 기각) | 지표 | 사전 예측 | 결정에 미치는 영향 |
| --- | --- | --- | --- | --- | --- |
| H1 | 실패는 Redis 명령(또는 첫 연결 핸드셰이크)이 200ms를 넘겨 `RedisCommandTimeoutException`이 나고 fail-open으로 `true`가 된 것이다. 호스트 부하가 원인이다 | 실패한 run에서 `fail_open` 카운터가 0이고 `Admission gate unavailable` WARN도 없다. 또는 같은 환경에서 timeout을 30초로 키운 팔이 200ms 팔과 같은 비율로 실패한다 | run별 실패 여부, `fail_open` 카운터, WARN 예외 클래스, Redis 호출별 지연 | 자연 부하(현재 load1 약 5/11코어)에서 200ms 팔 실패율은 0~1% 수준이라 n=200이면 0건일 수 있다. 대신 호출 지연 최댓값이 200ms보다 얼마나 낮은지(여유)는 측정된다 | 확정이면 시간 값에 결과가 좌우되는 테스트를 결정적으로 고친다(모의 지연 없이 timeout을 키우고 `fail_open`을 단정). 운영 기본값 200ms는 EXP-063이 유지를 지지하므로 바꾸지 않는다 |
| H2 | 반환과 승인 사이의 실제 경쟁이나 Lua 논리 결함이다. Redis가 정상 응답했는데 `c`가 승인됐다 | 관측된 실패가 모두 `fail_open` 1 이상이면 H2는 그 실패를 설명하지 못하므로 기각한다(테스트는 단일 스레드이고 Lua 스크립트는 Redis에서 원자적이다). 실패가 한 건도 없으면 기각하지 못하고 실패율 상한만 얻는다 | 실패 run의 `fail_open` | 0건(코드상 단일 스레드 순차 호출) | `fail_open` 0인 실패가 한 건이라도 나오면 운영 결함이므로 ADR-0094와 회귀 테스트로 운영 코드를 고친다 |
| H3 | key 충돌이나 이전 실행의 잔여 상태다. `a`~`f` 같은 고정 key가 SKU처럼 유일하지 않아 같은 Redis에 남은 decision이 영향을 준다 | 같은 Redis에 시나리오를 두 번 연속 재생해도 단정이 모두 통과한다. 컨테이너가 클래스마다 새로 뜨고 재사용 설정이 없다 | 같은 컨테이너 2연속 재생의 단정 결과, `testcontainers.reuse.enable` 설정 | 통과(재생 때 `c`는 이미 `0`이 기록돼 있어 `false`) | 기각이면 변경 없음 |
| H4 | 컨테이너 준비 순서다. 포트는 열렸지만 Redis가 아직 연결을 받지 못해 첫 호출이 `RedisConnectionException`을 내고 fail-open이 된다. 클래스에서 처음 실행되는 테스트에서만 나야 한다 | WARN 예외 클래스가 `RedisConnectionException`인 실패가 한 건도 없다. 또는 실패가 첫 테스트가 아닌 곳에서만 난다 | 실패 run의 WARN 예외 클래스, 실패한 테스트의 클래스 안 실행 순서 | 0건 | 확정이면 첫 연결에서 재시도(ping)를 테스트에 넣는다 |
| H5 | 이웃 클래스와 같은 JVM(CI는 shard당 2 fork)에서 CPU·GC 경쟁이 커져 H1이 더 자주 난다 | 이웃을 붙인 팔의 실패율이 단독 팔 이하이고 호출 지연 분포도 이동하지 않는다 | 팔별 실패율, 호출 지연 p99·max | 단독 팔과 같다(0~1%) | 기각이면 shard 구성은 건드리지 않는다 |

H1과 H4는 둘 다 fail-open 경로이고 예외 클래스로 구분된다. H2는 fail-open이 없는 실패를 뜻하므로 H1과 배타적이다.

## 통제

- 무변경 통제: 실제 테스트 클래스(수정 없음)를 팔 A로 반복한다. 탐침 복사본에서 timeout 200ms를 그대로 둔 팔이 A와 같은 결과여야 탐침을 믿는다.
- 음성 통제(탐지기가 울리는가): 탐침의 명령 timeout을 1ms로 낮춘다. 이 팔은 같은 101행 단정과 `fail_open` > 0으로 거의 모든 run에서 실패해야 한다. 실패하지 않으면 루프가 이 종류의 실패를 못 잡는 것이므로 0건 결과를 "재현 안 됨"으로 쓸 수 없다.
- 같은 환경 전후: 팔은 라운드마다 번갈아 실행해(`A, B, C, ...`) 호스트 부하 변화가 한 팔에만 몰리지 않게 한다. 라운드마다 `load average`를 기록한다.
- 표본: 팔마다 새 JVM 새 컨테이너로 n >= 200. 0건이면 세 번의 법칙으로 상한 3/n을 쓴다(n=200이면 1.5%). 이 호스트의 시간 값은 맥락일 뿐이고 결론은 건수와 불변식(`fail_open` 유무)으로만 낸다.
- 교란 요인 기록: 호스트 load average, 형제 에이전트의 부하 실험, Docker 컨테이너 수, 프로브 복사본과 실제 클래스의 차이.

## 사전 등록 이후 추가(측정 중 관찰에 따른 가설, 해당 팔의 측정 전에 커밋)

첫 탐침 실행(n=3, 자연 부하)에서 두 가지를 봤다. 하나는 Lettuce `CommandListener`로 잰 Redis 명령 지연이 1~3ms로 200ms보다 훨씬 낮다는 것이다. 다른 하나는 클래스에서 가장 먼저 실행되는 테스트가 바로 이 `release...` 테스트이고, 그 첫 `admit()` 전체(연결 포함)가 약 170~300ms 걸린다는 것이다. 이 관찰 때문에 아래 가설을 추가하고, 이 두 가설의 팔은 아직 실행하지 않았다.

| ID | 가설 | 반증 조건 | 지표 | 사전 예측 | 결정에 미치는 영향 |
| --- | --- | --- | --- | --- | --- |
| H6 | 실패는 호스트나 JVM 전체가 200ms 이상 멈춰(GC 정지, CPU 기아) 진행 중이던 명령의 timeout이 응답 처리보다 먼저 만료된 것이다. 오래 사는 테스트 JVM(512MB 힙, 앞선 클래스 수십 개)에서만 가능하다 | shard JVM 반복에서 Redis 테스트 구간과 겹친 GC 정지의 최댓값이 100ms 아래이고 실패도 0건이다. 또는 CPU 기아 팔(nice 19 hog 33개와 nice 19 JVM)에서 명령 지연 최댓값이 200ms에 한참 못 미치고 실패가 0건이다 | 팔별 실패율, Redis 테스트 구간과 겹친 GC 정지(`-Xlog:gc`), 탐침 팔의 명령 지연 상위 | 두 팔 모두 0건일 것으로 예측한다(자연 부하에서 명령 지연이 1~3ms이므로 200ms 정지가 흔하지 않다) | 한 건이라도 나오면 H1의 구체적 원인이 정지임을 보이고, 테스트의 timeout 의존을 없애는 근거가 된다 |
| H7 | 클래스에서 첫 `admit()`이 느린 것은 연결 수립(Netty·DNS 리졸버 초기화) 때문이며 명령 timeout 구간 밖이다. 따라서 이 지연은 200ms timeout에 영향이 없다 | timeout을 30ms로 낮춘 팔(`probe30`)이 첫 테스트에서 실패한다면 연결 구간이 timeout 안에 들어 있는 것이다 | 팔별(30ms) 실패율, 첫 admit 지연 | 30ms 팔도 실패 0건이다. 음성 통제(1ms)만 실패한다. 이 팔은 H7 커밋 전에 이미 실행 중이었다(예비 실행 n=3에서 T=50·100·150ms가 모두 통과한 것이 예측의 근거) | 기각이면 연결 시간이 timeout에 포함되므로 H1·H4의 설명이 바뀐다 |

## 사전 등록 이후 추가 2(본 루프 결과를 본 뒤, 연결 단계 측정 전에 커밋)

본 루프(팔 4개, 팔당 n=250)가 끝난 뒤에 이 절을 썼다. 본 루프 자체의 집계는 결과 절에 적는다. 이 절은 본 루프에서 관찰한 한 가지 때문에 추가한 가설이다. 관찰: timeout 30ms 팔의 실패 12건 중 10건이 `release...` 테스트의 `c` 단정이고, 그 run의 WARN 예외 클래스는 `RedisCommandTimeoutException`이 아니라 `RedisConnectionException`이었다(`fail_open`=3, 즉 `a`·`b`·`c` 모두 통과). 이는 H7(연결은 timeout 구간 밖)과 모순이다. 연결 단계의 측정 루프(`P3Connect`, 콜드 JVM마다 `connect()` 한 번과 첫 EVAL 한 번)는 이 절을 커밋하기 몇 분 전에 이미 시작했다. 따라서 이 루프의 결과는 본 루프를 본 뒤에 세운 가설의 검증이고 사전 등록 검증이 아니다.

| ID | 가설 | 반증 조건 | 지표 | 예측 | 결정에 미치는 영향 |
| --- | --- | --- | --- | --- | --- |
| H8 | 실패 경로는 첫 호출의 `client.connect()`가 끝나기 전 연결 초기화(채널이 열린 뒤 Lettuce의 HELLO 등 핸드셰이크)가 명령 timeout T 안에 끝나지 않아 `RedisConnectionException`이 나고 fail-open과 2초 breaker로 `a`·`b`·`c`가 모두 `true`가 되는 것이다. T가 작을수록 잘 난다 | timeout을 사실상 끄는 팔(T=10초)에서 잰 초기화 시간(`ConnectedEvent`에서 `ConnectionActivatedEvent`까지)이 T=30ms 팔의 실패 비율과 맞지 않는다. 즉 초기화 시간이 30ms를 넘는 run의 비율이 T=30 팔의 `connect()` 실패 비율과 표본 오차(95% 구간)를 넘게 다르다 | 팔별 `connect()` 실패 비율, 초기화 시간 분포, 예외 원인 문구 | 초기화 시간 > 30ms 비율은 T=30 팔 실패 비율(본 루프 4.8%)과 같은 구간. T=200은 n=400에서 0건 | 일치하면 기전을 확정한다. T=200 팔이 0건이면 원래 실패(T=200)는 이 기전의 꼬리일 가능성만 말하고, 재현 못 했다고 쓴다 |
| H9 | JVM이 CPU를 못 받는 상황(이 JVM만 nice 15, 다른 쪽은 nice 5 hog 22개)에서는 T=200에서도 초기화가 timeout을 넘어 같은 실패가 난다 | 이 팔에서 초기화 시간 최댓값이 200ms 아래이고 T=200 실패가 0건이다 | 팔별 T=200 실패 비율, 초기화 시간 p99·최댓값 | 초기화 시간이 올라가지만 200ms에는 못 미칠 것으로 본다(모르겠다) | 실패가 나오면 원래 실패의 원인 계열이 호스트 CPU 기아로 재현된 것이고, 테스트를 timeout 값에서 분리한다 |

## 대안

| 대안 | 기각 또는 채택 기준 |
| --- | --- |
| 아무것도 하지 않는다 | N회 재현이 0이고 원인을 확정하지 못하면 이것이 결론이다. 운영 코드를 추측으로 바꾸지 않는다 |
| 테스트를 `@RepeatedTest`나 재시도로 감싼다 | 원인을 가리고 `test-hygiene`가 금지한다. 채택하지 않는다 |
| 테스트의 명령 timeout을 키운다 | H1이 확정될 때만 채택한다. `unreachableRedisFallsThroughInsteadOfRejectingOrders`는 timeout 동작을 따로 검증하므로 그 테스트는 200ms를 유지한다 |
| 운영 `command-timeout` 기본값을 키운다 | EXP-063이 200ms 유지를 지지했고, 이 실험은 운영 경로의 자료를 새로 만들지 않는다. 채택하지 않는다 |

## 사전 부검

- 루프가 호스트 부하를 못 만들어 0건이면 "H1을 기각했다"가 아니라 "재현하지 못했다, 상한 x%"라고 쓴다. 호출 지연 여유가 측정되므로 H1의 가능성은 지연 분포로 따로 말한다.
- 탐침 복사본이 실제 테스트와 달라 결과가 갈릴 수 있다. 그래서 무변경 통제를 둔다.
- 컨테이너 수 상한(6)과 형제 에이전트의 Docker 사용을 넘기지 않도록 루프는 순차 실행하고 한 번에 컨테이너 1개만 쓴다.
- 되돌리기: 테스트만 바꾸는 PR이면 커밋 되돌리기로 끝난다. 별도 worktree에서 이 PR의 커밋을 되돌려 변경 전 상태와 `cmp`로 같음을 확인한다.

## 방법

- 실행 단위: 새 JVM(`-Xmx512m`, Gradle 테스트 worker 기본 힙과 같게)에서 JUnit Platform Launcher로 클래스를 한 번 실행하고, 클래스마다 Testcontainers가 `redis:7.4-alpine`을 새로 띄운다. Gradle을 거치지 않으므로 CI의 shard 필터는 쓰지 않았고, shard 팔만 `atlas.test.shard=1/4`로 필터를 켰다(Redis 테스트는 가중치표 기준 shard 1/4에 배정된다).
- 팔: `real`(수정 전 실제 테스트 클래스, 변경 없음), `probe200`(같은 소스 복사본에 계측 추가: 명령 지연 `CommandListener`, 호출별 지연, `fail_open` 카운터, 명령 timeout을 시스템 속성으로 받음, timeout 200ms), `probe30`(timeout 30ms), `neg1`(timeout 1ms, 음성 통제). 팔은 라운드마다 `real, probe200, probe30, neg1` 순서로 번갈아 실행했다.
- 연결 단계: 운영 설정 클래스(`RedisStockAdmissionControlConfiguration`)로 클라이언트를 만들어 콜드 JVM마다 `connect()` 한 번과 첫 `EVAL` 한 번만 잰다(`ConnectedEvent`에서 `ConnectionActivatedEvent`까지가 핸드셰이크). timeout 30, 200, 10000ms를 라운드마다 번갈아 실행했다.
- CPU 기아: 이 JVM만 `nice -n 15`, 같은 시간에 `nice -n 5`인 `yes` 프로세스 22개. 다른 프로세스(`nice 0`)를 방해하지 않고 유휴 CPU만 쓰도록 했다. 측정된 load1은 중앙값 약 110~200으로 계획한 것(약 100)보다 높았다.
- 판정 규칙: 한 run의 어떤 테스트라도 실패하면 그 run은 실패. 원인 귀속은 `fail_open` 카운터(탐침 팔)와 WARN 줄(실제 클래스, `unreachable...` 테스트가 항상 `RedisConnectionException` 1줄을 내므로 그 밖의 `RedisConnectionException`·`RedisCommandTimeoutException` 줄이 있으면 fail-open 증거)로 한다.

## 결과 1: 자연 부하, 클래스 단독, 콜드 JVM (팔당 n=250, load1 중앙값 10.0, 최대 16.7)

| 팔 | run 실패 | 실패한 테스트(건) | `fail_open` 0인 실패 |
| --- | --- | --- | --- |
| `real` (수정 전 실제 클래스) | **0/250** (세 번의 법칙 상한 1.2%) | - | 해당 없음 |
| `probe200` (무변경 통제) | **0/250** | - | 해당 없음 |
| `probe30` | 12/250 (4.8%) | release 10, concurrent 2, manySkus 1 | 0 |
| `neg1` (음성 통제) | 250/250 | release 247, concurrent 250, manySkus 250 | 0 |

- 음성 통제는 울렸다. `neg1`의 release 실패 247건은 모두 이번 실패와 같은 `assertThat(admit("c", ...)).isFalse()`("Expecting value to be false but was true")이고 `fail_open`=3(a·b·c 모두 통과)이다. 나머지 3 run에서 release 테스트가 통과한 이유는 확인하지 않았다.
- 탐침 복사본은 실제 클래스와 같은 결과(둘 다 0/250)이므로 계측이 결과를 바꾸지 않았다고 본다(건수 기준. 지연 분포는 실제 클래스에서 재지 않았다).
- 탐침 팔에서 실패한 테스트 770건(이 절의 `probe30` 13 + `neg1` 747, 결과 3의 `probe200` 10)은 전부 그 테스트의 `fail_open` > 0이었다. `fail_open`=0인 실패는 0건이다.
- `probe30` 실패 12 run 중 release 10 run의 WARN은 `RedisConnectionException`(연결 초기화 timeout, 아래 결과 2)이었고, 2 run(concurrent·manySkus)은 `RedisCommandTimeoutException`이었다.
- 지연 여유(`probe200`, n=250): Redis 명령 지연(Lettuce `CommandListener`, 1ms 단위) 상위값의 run별 최댓값은 p50 7ms, p99 23ms, 최댓값 99ms(테스트 인스턴스 1,500개). 200ms 이상은 0건, 100ms 이상도 0건. 첫 테스트의 `admit()` 호출 하나의 최대 시간(연결 포함)은 p50 207ms, p99 458ms, 최댓값 474ms로 200ms를 자주 넘지만 통과했다. 연결 시간 자체는 timeout 밖이다(결과 2).

## 결과 2: 연결 초기화는 명령 timeout 안에 있다 (콜드 JVM, 팔당 n=400, load1 중앙값 8.9)

| timeout | 첫 호출 실패 | 실패 내용 | 핸드셰이크 ms p50 / p95 / p99 / 최댓값 | 핸드셰이크 >= 30ms / >= 50ms / >= 100ms |
| --- | --- | --- | --- | --- |
| 30ms | 15/400 (3.75%, Wilson 95% 2.3~6.1%) | `Connection initialization timed out after 30 millisecond(s)` 9, `Command timed out` 6 | 22 / 34 / 46 / 67 | 45 / 2 / 0 |
| 200ms (운영 기본값) | **0/400** (상한 0.75%) | - | 21 / 37 / 46 / 60 | 59 / 3 / 0 |
| 10000ms | 0/400 | - | 21 / 37 / 46 / 73 | 74 / 2 / 0 |

- `connect()` 전체는 p50 257~261ms, 최댓값 500~568ms(Netty·DNS 리졸버 초기화 포함)인데 timeout 200ms에서도 실패하지 않는다. 즉 연결 전체가 아니라 연결 직후 핸드셰이크 구간만 timeout에 걸린다. 첫 `EVAL`은 p50 6ms, 최댓값 24ms(timeout 10000 팔).
- 핸드셰이크가 30ms를 넘는 비율(11~19%)이 30ms 팔의 실패율(3.75%)과 맞지 않는다. 핸드셰이크 시간만으로 30ms 팔의 실패율을 설명하지는 못한다(원인 미확정, 아래 H8).

## 결과 3: CPU 기아 (JVM `nice 15`, hog `nice 5` 22개, load1 중앙값 약 110~200)

| 측정 | 팔 | 실패 | 비고 |
| --- | --- | --- | --- |
| 연결 단계 (콜드 JVM마다 connect + 첫 EVAL) | timeout 200ms | 2/40 | `Connection initialization timed out after 200 millisecond(s)` 1, `Command timed out after 200 millisecond(s)` 1. 핸드셰이크 p50 82, p99 234, 최댓값 234ms |
| 연결 단계 | timeout 30ms | 33/41 (80%) | 핸드셰이크 p50 55ms |
| 연결 단계 | timeout 10000ms | 0/41 | 핸드셰이크 p50 81, 최댓값 248ms |
| 클래스 단독 | `real` (수정 전) | **6/45 run** | release `c` 단정(101행) 2, concurrent(84행) 2, manySkus(163행) 3. 101행 실패 2 run의 WARN은 `RedisConnectionException` 2줄(1줄은 `unreachable` 테스트 몫, 나머지 1줄이 release 테스트의 첫 연결) |
| 클래스 단독 | `probe200` | 8/45 run | release 5(`fail_open`>0), 그 밖 5 |

- 수정 전 실제 클래스가 CPU 기아에서 **이번 실패와 같은 단정(101행, "Expecting value to be false but was true")으로 실패했고**, 그 run의 WARN은 fail-open 증거(연결 초기화 timeout)였다. 같은 부하에서 같은 클래스의 concurrent·manySkus 테스트도 같은 이유(명령 timeout 뒤 breaker 2초 동안 모두 통과)로 실패한다. 이 두 테스트는 이번 로컬 실패에서는 실패하지 않았다.

## 결과 4: 지연 주입과 변이 확인 (결정적 팔)

수정은 `RedisStockAdmissionControlTest.gate()`의 명령 timeout을 200ms에서 30초로 바꾸는 한 줄이다(코드 변경은 이 줄과 주석뿐). 아래 비교는 모두 같은 환경에서 변경 전 소스와 수정 소스를 번갈아 실행했다.

| 조건 | 변경 전(timeout 200ms) | 수정(timeout 30초) |
| --- | --- | --- |
| 서버→클라이언트 첫 바이트 300ms 지연 TCP 프록시, 클래스 복사본, n=10 | **10/10 run 실패**(release 10, concurrent 10, manySkus 10) | 0/10 (테스트 40/40 통과) |
| 테스트가 띄운 Redis 컨테이너를 연결이 생긴 뒤 0.4초 `docker pause`/0.15초 `unpause` 반복, **실제 클래스 파일에서 변이**(30초→200ms를 `sed`로 되돌린 사본), n=12 | **12/12 run 실패**(release 4, concurrent 7, manySkus 12) | 0/12 |
| CPU 기아 (결과 3와 같은 조건), 번갈아 n=60 | 3/60 (manySkus 3, release 1) | 0/60 (상한 5%) |

- 변이 절차: 수정한 파일을 `/tmp`에 백업하고, `Duration.ofSeconds(30)`을 `Duration.ofMillis(200)`으로 바꾼 사본을 컴파일해 위 팔에서 실패함을 확인한 뒤, 작업 파일이 백업과 바이트 단위로 같음을 `cmp`로 확인했다(변이는 사본에만 했고 작업 파일은 바꾸지 않았다).
- 변경 전 소스를 쓴 모든 팔의 합(CPU 기아 `real` 6/45 + `orig` 3/60 = 9/105)을 수정 0/60과 비교하면 단측 Fisher 정확 검정 p=0.015이다. 두 팔은 서로 다른 시간대에 측정한 것이라 합산은 참고용이고, 같은 라운드에서 번갈아 잰 쌍(3/60 대 0/60)만의 p는 0.12로 유의하지 않다. 결정적 팔(지연 주입 10/10 대 0/10, pause 12/12 대 0/12)이 수정의 효과를 보여 준다.
- H3 통제: 같은 Redis 컨테이너에 `release...` 시나리오를 `@RepeatedTest(3)`로 연속 3번 재생해도 3/3 통과했다. 컨테이너 재사용 설정은 없다(저장소에 `testcontainers.reuse.enable` 없음, 사용자 `testcontainers.properties` 없음).

## 결과 5: shard JVM(이웃 클래스와 같은 JVM) (n=12)

`atlas.test.shard=1/4` 필터로 같은 JVM에서 클래스 27개(테스트 132건)를 실행했다. Redis 테스트 4건은 매번 81~84번째로 실행됐다. 12 run, 48/48 통과. Redis 테스트 구간(0.85~1.64초)과 겹친 GC 정지(`-Xlog:gc`)의 최댓값은 20.7ms다. 이 팔에서는 `ModuleBoundaryTest` 1건이 매 run 실패했다. 실험용 실행기(기본 패키지 클래스)가 클래스 경로에 있어서 생긴 인공물로 보이나 **원인은 확인하지 않았다**. Redis 테스트와 무관하다.

## 가설 판정

| ID | 판정 | 근거 |
| --- | --- | --- |
| H1 (명령·연결 timeout 뒤 fail-open) | **기전 확인(재현함), 원래 사건은 확정 못 함** | 탐침 팔의 실패 770건 모두 `fail_open` > 0, 실제 클래스도 CPU 기아에서 같은 101행으로 6/45 실패하고 WARN이 fail-open 증거였다. 자연 부하에서는 `real` 0/250이다. 원래 실패의 예외 클래스는 기록이 없어서 같은 원인이었다고 확정하지 못한다 |
| H2 (실제 경쟁·Lua 결함) | 근거 없음 | `fail_open`=0인 실패 0건. 실패율의 상한이 아니라 "관측된 모든 실패가 fail-open으로 설명된다"는 뜻이다. 운영 코드는 건드리지 않는다 |
| H3 (key 충돌·잔여 상태) | 기각 | 같은 컨테이너에서 3회 연속 재생 3/3 통과, 재사용 설정 없음. 코드상으로도 잔여 decision은 이 단정을 `true`로 만들 수 없다(`c`의 decision은 `0`) |
| H4 (컨테이너 준비 순서) | 기각하지 못함, 지지 근거 없음 | 실패 WARN 예외는 `RedisConnectionException`(연결 초기화 timeout)이었지만 이는 "컨테이너가 준비되지 않아서"가 아니라 "Redis가 정상이어도 핸드셰이크가 timeout 안에 못 끝남"(`ConnectedEvent` 발생 뒤 timeout)이다. 컨테이너 시작 직후 실패는 구분하지 못했다. 이 테스트가 클래스에서 항상 첫 번째로 실행되므로(250/250) 콜드 JVM 첫 연결을 맨 먼저 치르는 것은 사실이다 |
| H5 (이웃 클래스, 같은 JVM) | 지지하지 못함 | shard JVM 0/48, 단독 0/250(자연 부하). 표본이 작아 차이가 없다고 말하지는 못한다(상한 6%) |
| H6 (GC 정지·JVM 정지) | 지지하지 못함(GC) | shard JVM에서 Redis 구간과 겹친 GC 정지 최댓값 20.7ms. CPU 기아는 실제로 같은 증상을 만든다(결과 3) |
| H7 (연결은 timeout 밖) | **기각** | 30ms 팔이 12/250 실패했고 9건은 `Connection initialization timed out after 30 millisecond(s)`. 사전 예측(30ms 팔 0건)이 틀렸다 |
| H8 (핸드셰이크 시간이 30ms 팔 실패율을 결정) | 질적으로만 확인, 양적 판정 기준은 충족하지 못함 | 연결 초기화 timeout이 실패의 9/15를 차지한다. 그러나 사전에 적은 기준(핸드셰이크 > 30ms 비율 ≈ 30ms 팔 실패율)과 달리 비율이 11~19% 대 3.75%로 어긋난다. 나머지 6/15는 첫 명령 timeout이다. 핸드셰이크 계측이 Lettuce 내부 timeout 구간 전체를 덮지 않는 것으로 보이나 확인하지 않았다 |
| H9 (CPU 기아에서 200ms도 넘김) | **확인** | 연결 단계 2/40, 클래스 6/45. 사전 예측("200ms에는 못 미칠 것")은 틀렸다(핸드셰이크 최댓값 234~248ms) |

## 결정

- **테스트만 고친다**(`RedisStockAdmissionControlTest.gate()`의 명령 timeout 200ms → 30초). 이 테스트들이 검증하려는 것은 승인·반환·확정 논리와 SKU 시드이고 timeout이 아니다. 운영 `command-timeout` 기본값(200ms)은 바꾸지 않는다. 이유는 EXP-063이 200ms 유지를 지지했고, 운영의 fail-open이 설계 의도이기 때문이다(ADR-0066).
- `unreachableRedisFallsThroughInsteadOfRejectingOrders`도 같은 `gate()`를 쓰므로 timeout이 30초가 되지만 영향이 없다. 이 테스트의 WARN 예외 클래스는 매번 `RedisConnectionException`(연결 거부)이고 timeout을 기다리지 않는다(`probe200` 250/250, WARN 줄 250개, `RedisCommandTimeoutException` 0).
- 운영 의미: 부팅 직후 첫 요청이 CPU가 극도로 모자란 호스트에서 연결 초기화 timeout을 만나면 fail-open(PostgreSQL 경로)으로 처리된다. 설계상 허용된 동작이므로 코드는 바꾸지 않는다. 이 문서의 수치(콜드 핸드셰이크 p99 46ms)는 `command-timeout` 설정을 줄이려는 사람이 하한을 알 수 있게 남긴다.
- 원래 로컬 실패(`7655f063`, 24분 전체 빌드)는 **같은 원인이었다고 확정하지 못한다**. 같은 단정이 같은 기전(fail-open)으로 재현되는 조건(호스트 CPU 기아)이 존재함은 확인했다. 그 당시 호스트 부하와 예외 클래스는 기록이 없다.

## 주장 표

| 주장 | 증거 | 확신도 |
| --- | --- | --- |
| 이 테스트가 101행에서 실패하는 경로가 있다: Redis 호출이 timeout을 넘기면 fail-open이 되어 `c`가 `true`가 된다 | 음성 통제 247/250, `probe30` 10건, 지연 주입 10/10, pause 변이 12/12, 모두 `fail_open`>0 | 확인됨(confirmed) |
| 실제 클래스(수정 전)가 CPU 기아에서 같은 단정으로 실패한다 | `real` 6/45 run(101행 2건), 같은 부하에서 `orig` 3/60 | 확인됨(로컬, 단일 호스트) |
| 자연 부하(load1 약 10/11코어)에서 수정 전 실제 클래스의 실패율 | 0/250, 상한 1.2% | 확인됨(0건, 상한만 말한다) |
| 원래 로컬 실패가 이 기전이었다 | 같은 단정, 재현 가능한 조건 존재. 당시 예외 클래스·부하 기록 없음 | 확정하지 못함 |
| 연결 초기화(핸드셰이크)가 명령 timeout 안에 있고 콜드 JVM에서 p99 46ms, CPU 기아에서 최대 약 250ms | 결과 2, 3 | 확인됨(로컬) |
| 수정(30초)이 이 실패 경로를 없앤다 | 지연 주입 0/10(대조 10/10), pause 0/12(변이 12/12), CPU 기아 0/60 | 확인됨(수정으로 결정적 팔이 바뀜). CPU 기아만의 쌍 비교는 p=0.12로 유의하지 않음 |
| 운영 코드 결함은 없다 | 실패 770건이 모두 `fail_open`으로 설명됨 | 이 실험의 범위에서는 근거 없음. 없다고 증명하지는 않았다 |
| GC 정지가 원인이다 | shard JVM 12회, 겹친 최대 정지 20.7ms | 지지하지 못함(n=12) |
| CI 환경(shard당 2 fork, GitHub runner)에서의 실패율 | 측정하지 않았다 | 확인하지 못함 |
| `ModuleBoundaryTest`가 실험용 실행기에서 실패한 이유 | 조사하지 않았다 | 확인하지 못함 |

## 되돌리기

코드 변경은 테스트 파일 한 줄과 주석이다. 별도 worktree에서 이 PR의 테스트 커밋을 `git revert`한 뒤 `cmp`로 `origin/main`의 같은 파일과 바이트 단위로 같음을 확인했다(결과: 같음, 아래 "되돌리기 확인" 참조).

## 삭제한 파일의 내용

실험 구동 스크립트와 원자료(run별 JSON 줄, 로그)는 저장소에 올리지 않았다. 아래가 방법과 집계 전부다.

- 실행기: `junit-platform-launcher`로 클래스 하나를 `Launcher.execute`하는 약 40줄짜리 `main`(기본 패키지). 테스트마다 `이름|상태|ms|예외 메시지`를 출력하고 끝에 `System.exit(0)`. 실행 명령: `java -Xmx512m -Dp3.timeoutMs=<ms> -cp <classes>:<Gradle test runtimeClasspath> P3Run <팔> <클래스>`. 클래스 경로는 임시 Gradle init script로 `sourceSets.test.runtimeClasspath.asPath`를 파일에 써서 얻었다.
- 탐침 복사본: 실제 테스트 소스에서 (1) 클래스 이름 변경, (2) `Duration.ofMillis(200)`을 `Duration.ofMillis(Long.getLong("p3.timeoutMs", 200L))`로, (3) 컨테이너에 라벨 `atlasexp=p3`, (4) `gate.admit(`을 호출 시간을 재는 정적 메서드로, (5) `RedisClient.addListener(CommandListener)`로 명령 지연 기록, (6) `SimpleMeterRegistry`를 모아 `@AfterEach`에서 `fail_open` 카운터 출력. 로직(단정, 시나리오)은 바꾸지 않았다.
- 지연 주입 프록시: 서버→클라이언트 방향 연결의 첫 바이트를 300ms 늦추는 `ServerSocket` 기반 TCP 프록시(약 40줄). 테스트의 `"redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort()`만 프록시 주소로 바꾼 복사본.
- pause 주입: 테스트가 띄운 컨테이너(라벨 `org.testcontainers=true`, 이미지 `redis:7.4-alpine`)에 `docker exec redis-cli info clients`의 `connected_clients`가 2 이상(테스트 연결 생김)일 때만 `docker pause`, 0.4초 뒤 `docker unpause`, 0.15초 쉼을 반복. 연결 전에 pause하면 컨테이너 시작 대기가 실패해(`ContainerLaunchException`) 첫 시도(변이 15건 실패)는 버렸다.
- 집계: run마다 한 줄 JSON(팔, 라운드, load1, 테스트별 상태·메시지, WARN 예외 클래스 건수)을 `/tmp`에 쌓고 팔별로 센 값이 위 표다. 팔별 n은 표에 적었다. 루프 소요: 본 루프 1,000 run 약 55분, 연결 단계 1,200 run 약 13분, CPU 기아 연결 단계 약 20분, CPU 기아 클래스 단독 90 run 약 35분, 수정 비교 120 run 약 40분, shard 12 run 약 29분.
- 컨테이너: 루프는 순차 실행이라 한 번에 Testcontainers 컨테이너 1개(측정 대조용으로 직접 띄운 `atlasexp=p3` 라벨 Redis 1개)만 썼고, 종료 뒤 라벨 `atlasexp=p3` 컨테이너 0개, `redis:7.4-alpine` 이미지 컨테이너 0개를 확인했다. Testcontainers가 띄운 컨테이너는 라벨을 못 붙이므로(위 탐침 복사본 제외) 이미지로 확인했다.
- 교란 요인: 같은 호스트에서 다른 에이전트의 부하 실험(load1 5~16, CPU 기아 구간은 최대 약 240)이 돌았다. 호스트 스왑 사용량이 8GB 중 약 6.9GB였다(측정 시작 시점 1회). 본 루프 1,000 run은 연속 55분이라 라운드마다 팔을 번갈아 실행해 시간대 영향이 한 팔에 몰리지 않게 했다.
