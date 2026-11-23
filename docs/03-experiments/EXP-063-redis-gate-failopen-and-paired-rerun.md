# EXP-063: Redis gate fail-open 진단과 보강 빌드 짝 비교

- 질문: [EXP-062](EXP-062-admission-gate-paired-comparison.md)의 Redis 팔에서 2,000/s의 두 run이 gate `fail_open`으로 느려졌다. 반환 경로(`confirm`/`release`)와 pending 반영 재시드를 넣은 현재 jar에서도 재현되는가? 재현되지 않는다면 `fail_open`은 어떤 조건에서 나는가? 보강 jar의 Redis gate와 precheck는 같은 조건에서 얼마나 다른가?
- 관련: [EXP-062](EXP-062-admission-gate-paired-comparison.md), [EXP-060](EXP-060-hot-sku-admission-gate.md), [ADR-0066](../02-decisions/adr/ADR-0066-redis-admission-gate.md), 점검표
- 상태: 탐색 실험(단일 호스트, 조건당 n=1~4). 수치는 모두 이 문서의 run에서 직접 측정했다. 측정하지 않은 것은 **미측정**으로 적었다.
- 날짜: 2026-11-23(보강 jar 빌드 10:19 이후 실행, 분석 파일 11:05)

## 요약

- 보강 jar의 Redis gate는 2,000/s 30초 run 13회(진단 3, 가설 6, 짝 비교 4)에서 `fail_open` 0회, 앱 ERROR 0줄, workflow 행 정확히 100이었다. EXP-062의 `fail_open` 309·440회는 **재현하지 못했다**.
- 명령 timeout을 5ms로 낮춘 결함 주입 run에서는 EXP-062과 같은 증상(`fail_open`, 앱 ERROR, 추가 workflow 행, k6 CPU 포화, 처리량 하락)이 나왔다. Redis 명령 timeout이 `fail_open`을 일으키고, `fail_open`이 PostgreSQL 경로로 부하를 내려보내 느려지게 만든다는 연쇄는 확인했다(대조 arm 있음).
- EXP-062의 두 run이 실제로 명령 timeout 때문이었는지는 이 실험으로 **확정하지 못했다**. 당시 WARN 예외 클래스를 남기지 않았고 같은 빌드로 재현하지 못했다.
- 보강 jar의 짝 비교(ABBA 4회)에서 처리량은 precheck 1,974/s, Redis 1,964/s로 구분되지 않는다. 비용 카운트는 Redis가 낫다(location 호출 0.0042 대 0.0133/요청, 추가 workflow 행 0 대 약 28).
- 권장 기본값: `COMMAND_TIMEOUT`은 **PT0.2S 유지**를 지지하는 자료만 있고 변경을 지지하는 자료는 없다. `BREAKER_COOLDOWN`은 PT2S 한 점만 측정했으므로 **미결**이다.

## 설계

- 하네스: 같은 jar, 같은 compose 스택, run마다 새 프로젝트. 모든 run은 2,000/s, 워밍업 10초(100/s), 측정 30초, 재고 100인 SKU 1개, location CPU 한도 2.
- 코드 변경은 하네스뿐이다. `load-tests/compose-hot-sku-coordinator.yaml`과 `load-tests/run-hot-sku-coordinator.sh`에 `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_COMMAND_TIMEOUT`(기본 PT0.2S)와 `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_BREAKER_COOLDOWN`(기본 PT2S)을 추가했다. 기본값은 앱 기본값과 같다. 값은 `PT<숫자>[S|M]` 형식만 받고, run마다 `gate-config.txt`에 timeout, cooldown, k6 CPU 한도를 남긴다. `shellcheck -S warning`은 경고 0이다.
- 관측 도구([`load-tests/redis-gate/`](../../load-tests/redis-gate/)): 1초 주기로 앱 `atlas_order_admission_gate_total{result}`(actuator prometheus)와 Redis `INFO commandstats`(eval·exists 호출, usec)를 기록하고, `docker logs -f`로 앱 로그를 받는다. 각 run 뒤에 Redis `SLOWLOG GET 20`과 `INFO commandstats`를 저장한다. 시작 때 `slowlog-log-slower-than`을 2,000µs로 낮췄다(기본 10,000µs).
- 단계와 실행 순서(실제 큐 순서):
  1. 진단: Redis, 기본 timeout, k6 CPU 1 — 3회.
  2. 가설 (a) `COMMAND_TIMEOUT=PT1S`(k6 CPU 1)와 (b) k6 CPU 2(기본 timeout)를 `a b a b a b`로 번갈아 각 3회.
  3. 짝 비교(k6 CPU 2): `P R R P R P P R`(P=precheck, R=Redis) 각 4회. 이어서 P/P 대조 2회(`p4-aa`). 큐에 함께 들어 있던 항목이라 그대로 보고한다.
  4. 결함 주입: Redis, `COMMAND_TIMEOUT=PT0.005S`, k6 CPU 2 — 2회 시도, 1회는 k6가 측정 중간에 끝나 요약이 없어 제외(아래).
- 가설 선택: (b)를 3단계에 사용했다. 이유는 (b)의 처리량 범위가 (a)보다 좁고 중앙값이 높기 때문이다. 단, 두 가설 모두 `fail_open`이 0이라 "`fail_open`을 줄였는가"로는 판정할 수 없었다(해석 절 참고).

## 환경

- 호스트 Apple M3 Pro, macOS 27.2.1, Colima VM 8 vCPU·13GiB, Docker 29.5.2. 이미지: postgres:18.6, eclipse-temurin:25-jre(JDK 25.0.5), redis:7.4-alpine, grafana/k6:2.3.0.
- 저장소 HEAD 9362d27. 앱 jar는 이 커밋 이후 빌드(2026-11-23 10:19)이며 `confirm`/`release`/pending 키가 들어 있음을 클래스 문자열로 확인했다. location jar는 기존 빌드.
- 컨테이너 CPU 한도: app 3, order-postgres 2, location 2, location-postgres 1, k6 1(진단·가설 a) 또는 2(그 외), Redis 1, mailpit 0.25. 합계 11.25(Redis 팔, k6 2) / 10.25(precheck 팔)로 VM 8 vCPU를 넘는다. 점검표의 "합계 ≤ vCPU−2"를 지키지 못했다.
- 호스트는 비어 있지 않았다. run 측정 구간 평균 load1이 4.3~12.5였고 다른 프로세스(다른 작업의 gradle·Docker 컨테이너)가 같은 호스트에서 돌았다. 측정 직전에 `uptime`과 구간 평균 load1을 run마다 저장했다.
- k6 CPU 수치는 `docker stats`의 코어 단위 %다. 한도 2일 때 한도 대비로 환산하려면 2로 나눈다.

## 결과 1: 진단 — fail_open은 어디서 나는가 (Redis, 기본 timeout, k6 CPU 1, n=3)

| 지표 | run 1 | run 2 | run 3 | 중앙값 (최소~최대) |
| --- | ---: | ---: | ---: | --- |
| completed/s | 1,944 | 1,924 | 1,962 | 1,944 (1,924~1,962) |
| dropped | 1,516 | 2,230 | 908 | 1,516 (908~2,230) |
| gate `fail_open` | 0 | 0 | 0 | 0 |
| 앱 ERROR 줄 | 0 | 0 | 0 | 0 |
| 추가 workflow 행(정상 100) | 0 | 0 | 0 | 0 |
| k6 CPU 평균/최대 (한도 100%) | 79.5/91.9 | 75.0/100.7 | 56.0/79.5 | 평균 75 (56~80) |
| Redis `eval` µs/호출 | 23.9 | 24.4 | 19.0 | 약 19~24 |
| 시드 경로 진입(`exists` 호출) | 190 | 234 | 180 | 약 180~234 |
| 느린 명령(>2ms) 건수 | 17 | 19 | 24 | 17~24 |
| 느린 명령 상위 20개 중 최대 | 5.0ms | 24.0ms | 8.3ms | 5.0~24.0ms |

- **fail_open이 시작되는 시점**: 시드 폭주(t=0)에서도 정상 부하 구간에서도 `fail_open` 증가가 한 번도 없었다. 1초 시계열에서 `fail_open`은 워밍업 전후와 측정 구간 내내 0이다. 따라서 "언제 시작하는가"는 이 조건에서는 답할 대상이 없다.
- 시드 폭주는 실제로 일어났다. 첫 평가가 미시드(-1)를 돌려 시드 경로에 들어간 요청이 run당 약 180~234건이고(`exists` 호출 수), 그 모두 timeout 없이 끝났다. 재시드(stock TTL 5초)도 30초 구간에 여러 번 지나갔다.
- 앱 WARN은 run마다 정확히 1줄이다. 예외 클래스 이름은 `ConnectionWatchdog`(lettuce 재연결 시도, "Connection refused")이며 k6 종료 후 약 6초에 찍혀 compose 종료로 Redis가 내려갈 때의 로그다. gate WARN(`Admission gate unavailable`, `settle failed`)은 0줄이다.
- 서버 측 명령 시간(진단 3회): `eval` 호출당 평균 19~24µs(`commandstats`는 워밍업 포함 누적, 호출 59,239~60,516건). >2ms 느린 명령은 run당 17~24건(약 6만 호출 중)이고 그중 상위 20개의 최대는 run별 5.0, 24.0, 8.3ms다. `slowlog`는 서버 실행 시간만 담으므로 클라이언트 대기·큐잉·네트워크는 포함하지 않는다(**미측정**).

## 결과 2: 가설 검정 (각 n=3, 2,000/s)

| 가설 | 바꾼 것 | completed/s | dropped | `fail_open` | k6 CPU 평균 (한도) |
| --- | --- | --- | --- | --- | --- |
| 기준(진단) | 없음 | 1,944 (1,924~1,962) | 1,516 (908~2,230) | 0/0/0 | 75 (56~80) / 100% |
| (a) timeout 1초 | `COMMAND_TIMEOUT=PT1S` | 1,765 (1,691~1,967) | 6,975 (932~9,195) | 0/0/0 | 75 (50~86) / 100% |
| (b) k6 CPU 2 | `K6_CPUS=2` | 1,970 (1,940~1,977) | 829 (634~1,745) | 0/0/0 | 70 (40~71) / 200% |

- 두 가설 모두 `fail_open`이 0이라 "`fail_open` 방지 효과"는 판정할 수 없다. 관측할 수 있었던 것은 처리량 흩어짐뿐이다.
- (a)의 낮은 두 run(1,765, 1,691)은 `fail_open` 0, ERROR 0, 추가 workflow 행 0인데도 느렸다. 이 두 run의 측정 구간 load1은 8.9와 12.5(12.5는 13개 Redis run 중 최고)였다. 그러나 1,924/s(load1 6.8)와 1,931/s(5.0)도 낮은 편이라 호스트 부하와 일관된 관계가 있다고 말할 수는 없다. 원인은 확인하지 않았다.
- (b)는 기준과 범위가 겹친다(1,940는 1,924~1,962 안). 효과가 있다고 말할 수 없고, 흩어짐이 좁다는 점만 3단계 사용 근거로 삼았다.

## 결과 3: 보강 jar 짝 비교 (2,000/s, k6 CPU 2, 모든 run)

run 순서와 개별 값이다.

| 순서 | run | 팔 | completed/s | dropped | k6 CPU 평균/최대 (한도 200%) | 구간 load1 | 추가 workflow 행 |
| ---: | --- | --- | ---: | ---: | --- | ---: | ---: |
| 1 | p4-precheck-1 | precheck | 1,965 | 998 | 63.7/124.3 | 4.70 | 26 |
| 2 | p4-redis-1 | Redis | 1,967 | 941 | 50.2/108.9 | 4.92 | 0 |
| 3 | p4-redis-2 | Redis | 1,961 | 1,054 | 81.8/126.6 | 10.44 | 0 |
| 4 | p4-precheck-2 | precheck | 1,970 | 838 | 35.0/42.2 | 8.02 | 26 |
| 5 | p4-redis-3 | Redis | 1,979 | 510 | 60.1/112.5 | 7.01 | 0 |
| 6 | p4-precheck-3 | precheck | 1,977 | 653 | 39.6/64.7 | 4.26 | 30 |
| 7 | p4-precheck-4 | precheck | 1,982 | 497 | 43.2/51.9 | 4.49 | 33 |
| 8 | p4-redis-4 | Redis | 1,931 | 1,989 | 55.3/96.3 | 5.02 | 0 |
| 9 | p4-aa-1 | precheck | 1,937 | 1,838 | 63.0/117.2 | 7.92 | 27 |
| 10 | p4-aa-2 | precheck | 1,966 | 968 | 52.3/72.7 | 6.15 | 29 |

순서 1~8이 ABBA 짝 비교, 9~10이 precheck 대 precheck 대조다.

중앙값(최소~최대):

| 팔 | n | completed/s | dropped(요청 60,000) | 주문DB tx/요청 | location 호출(tx)/요청 | 추가 workflow 행 | k6 CPU 평균 (한도 200%) |
| --- | ---: | --- | --- | --- | --- | --- | --- |
| precheck | 4 | 1,974 (1,965~1,982) | 746 (497~998) | 0.0271 (0.0269~0.0275) | 0.0133 (0.0125~0.0146) | 28 (26~33) | 41 (35~64) |
| Redis | 4 | 1,964 (1,931~1,979) | 998 (510~1,989) | 0.0246 (0.0238~0.0256) | 0.0042 (0.0041~0.0044) | 0 (0~0) | 58 (50~82) |
| precheck(대조 2회) | 2 | 1,952 (1,937~1,966) | 1,403 (968~1,838) | 0.0277 (0.0266~0.0287) | 0.0140 (0.0139~0.0140) | 28 (27~29) | 58 (52~63) |

- 불변식: 위 run 전부(진단 3, 가설 6, 짝 비교 10) RESERVED=100, 응답은 201/409뿐(`other` 0), 비종결 workflow 0. Redis 13회(진단 3, 가설 6, 짝 비교 4)는 gate `fail_open` 0, 앱 ERROR 0. "추가 workflow 행"은 workflow 행 수에서 정상값 100을 뺀 값이다.
- completed/s 비 Redis/precheck 중앙값은 0.995(범위 0.974~1.007). P/P 대조의 두 값 차가 1.5%라 이 차이는 잡음 안에 있다.
- dropped는 두 팔 모두 요청 60,000건 대비 0.8~3.3%로 점검표의 0.1% 기준을 넘는다. 즉 두 팔 모두 offered load 근처에 붙어 있어 처리량 knee를 재지 못했다.
- 카운트 차이는 잡음보다 크다. location 호출/요청은 Redis 0.0042 대 precheck 0.0133(범위가 겹치지 않음, 약 3.2배). 추가 workflow 행은 Redis 0, precheck 26~33으로 precheck의 음성 캐시가 차기 전에 지나간 요청이 durable 경로로 내려간 것이다. 주문DB tx/요청도 Redis가 낮았다(0.0246 대 0.0271, 범위 겹치지 않음).

## 결과 4: 결함 주입 — timeout 5ms (k6 CPU 2)

| 지표 | 값 |
| --- | ---: |
| completed/s | 268 |
| dropped | 49,900 |
| gate admitted / rejected / `fail_open` | 0 / 17 / 10,016 |
| workflow 행(정상 100) | 10,015 |
| 주문DB tx/요청 | 6.09 |
| location 호출(tx)/요청 | 1.03 |
| 앱 ERROR 계열 줄 | 205 |
| k6 CPU 평균/최대 (한도 200%) | 103/137 |
| 주문DB CPU 평균, location CPU 평균 | 96, 109 |
| 느린 명령(>2ms) 건수 | 0 (slowlog 길이) |
| 불변식 | RESERVED=100, 비종결 0, 201/409 외 응답 3건 |

- 앱 WARN 예외 클래스(이름만): `RedisCommandTimeoutException` 158줄, `RedisConnectionException` 1줄(첫 요청의 연결). 나머지는 `org.hibernate.orm.jdbc.error` 등 PostgreSQL 경로 과부하 로그다.
- `fail_open` 시계열: 측정 시작 1초 뒤부터 매초 증가했다(1초째 40, 2초째 457, 3초째 263, 4초째 597 …, 5·6·8초째 등 일부 초는 0). 측정 시작 전(워밍업·idle)에도 누적 870회가 있었다. 5ms timeout에서는 부하의 시작과 함께 바로 난다. 증가가 없는 초가 끼는 것은 breaker 쿨다운(2초) 동안 새로 Redis를 시도하지 않고 곧바로 통과시키는 구간과 시계열 샘플 간격(최대 2.8초 공백) 때문이다.
- Redis 서버는 한가했다(Redis CPU 평균 14%, 느린 명령 0). 따라서 이 증상은 Redis 서버가 느려서가 아니라 클라이언트 timeout 한도(5ms)가 실제 응답 시간보다 짧아서 난 것으로 보이지만, 클라이언트 쪽 지연은 재지 않아 추정이다.
- 같은 조건의 두 번째 run은 k6가 측정 약 21초 지점에서 요약 없이 끝났다(로그가 70%에서 끊김). 원인은 확인하지 않았고 이 run은 결과에서 제외했다.
- n=1이다. 증상의 유무(대조: 짝 비교 Redis 4회는 `fail_open` 0)는 방어 가능하지만 크기(268/s, 10,016회)는 절대값으로 쓰지 않는다.

## 해석

- **측정한 것**: 보강 jar에서 기본 timeout(200ms)으로는 2,000/s에서 Redis 명령이 timeout 되지 않았다(13 run, `fail_open` 0, WARN 클래스에 timeout 0). timeout을 5ms로 낮추면 EXP-062의 증상 묶음이 그대로 나온다. 증상 묶음은 `fail_open`↑, 앱 ERROR↑, workflow 행↑, 주문DB·location CPU↑, k6 CPU 포화, completed/s↓이다.
- **추론한 것**: EXP-062의 두 run도 같은 연쇄(명령 timeout → `fail_open` → PostgreSQL 경로)를 탔다고 보는 것이 가장 자연스럽다. 그러나 그 run들의 규모는 `fail_open` 309·440회, workflow 행 409·540으로 이번 결함 주입(10,016회, 10,015행)보다 훨씬 작다. 즉 짧은 timeout 묶음 한두 번이 breaker 쿨다운(2초)만큼 통과시킨 형태로 보이고, 지속적 장애는 아니었다. 이 해석은 당시 로그가 없어 **확정할 수 없다**.
- **EXP-062 재현 실패의 가능한 이유(전부 추정)**: ① 당시 Redis 팔은 반환 경로·pending 반영이 없던 빌드다. 그 빌드에서의 Lua 스크립트가 더 가벼웠으므로 빌드 차이만으로 timeout이 늘었다고 보기 어렵다. ② 호스트 간섭. 당시 k6 CPU 평균이 86~92%(한도 100%)로 포화였고 호스트는 공유 상태였다. 이번 run에서는 load1과 처리량 사이에 일관된 관계를 찾지 못했다(위 가설 a 참고). ③ 우연한 stall(GC, 컨테이너 CPU 쿼터 스로틀 등). 세 가지를 분리하는 실험은 하지 않았다.
- **breaker 구조의 약점(코드 읽기에서 확인, 동작은 위 결과 4에서 관찰)**: timeout 1회가 쿨다운 2초 동안 모든 요청을 통과시킨다. 2,000/s에서는 한 번의 timeout이 약 4,000건을 PostgreSQL 경로로 내려보낼 수 있다. 통과된 요청은 기존(gate 없는) 경로 비용(요청당 DB tx 약 6, 결과 4의 6.09)을 그대로 낸다.
- **처리량 비교**: 보강 jar에서 Redis와 precheck는 2,000/s에서 구분되지 않는다. 이는 두 팔이 offered load에 붙어 있어서일 수도 있다(knee 미측정). 구분되는 것은 비용 카운트다. Redis는 location 호출과 durable 행을 더 많이 줄이고, 그 대가로 Redis 의존과 위 breaker 약점을 진다.

## 권장 기본값

- **`COMMAND_TIMEOUT`: PT0.2S 유지.** 지지하는 자료: 13개 run에서 gate `eval` 약 77만 번(`commandstats` 누적 합 769,035, 워밍업 포함)에 timeout 0회. 느린 명령(>2ms) 상위 20개의 최대는 slowlog 항목을 받은 10개 run에서 2.1~50.1ms였다(2.1~24.0ms 7회, 39.9~50.1ms 3회). 가장 큰 50.1ms가 200ms의 4분의 1이라 서버 실행 시간 기준으로 약 4배의 여유가 있었다. 13 run 중 3 run(p4-redis-4, t3a-timeout1s-2, t3b-k6cpu2-1)은 마지막 샘플에서 slowlog 항목을 받지 못했다. PT1S로 올려도 개선되지 않았다(가설 a, 중앙값 1,765로 기준 1,944보다 낮고 `fail_open` 0). 5ms는 폭주를 낸다. 즉 5ms~200ms 사이에서 한계가 어디인지는 **미결**이다(찾지 않았다).
- **`BREAKER_COOLDOWN`: 미결.** PT2S 한 점만 측정했다. 결과 4에서 쿨다운이 통과 구간을 길게 만든다는 정성적 관찰은 있으나 다른 값(PT0.5S, PT5S)은 측정하지 않았다.
- 한계: 이 권장은 단일 SKU, 단일 앱 인스턴스, 단일 Redis 노드, 호스트 load1 4~12 조건의 값이다. 다중 인스턴스에서의 동시 연결·timeout 분포는 **미측정**이다.

## 보강 설계안 (제안, 전부 미측정)

fail-open을 없애는 대신 fail-open이 일어났을 때 비용을 제한하는 방향이다. 구현은 이 작업 범위가 아니다.

1. **fail-open 시 precheck로 폴백.** breaker가 열렸을 때 곧바로 PostgreSQL workflow로 통과시키지 않고 이미 있는 `PrecheckStockAdmissionService`(location 읽기 + 품절 음성 캐시)를 거친다. 두 gate의 장점(Redis 정상 시 낮은 location 호출, 장애 시 durable 쓰기 차단)을 합친다. 검증은 `REDIS_KILL_AFTER`나 timeout 5ms 결함 주입에서 `fail_open` 구간의 workflow 행·주문DB tx를 비교한다.
2. **계수 기반 breaker.** timeout 1회가 아니라 짧은 창에서 N회 연속·비율 초과일 때만 연다. 일시적 stall 한 번이 쿨다운 2초를 통째로 소모하지 않게 한다.
3. **통과 동시성 상한(bulkhead).** 통과된 요청 수를 세마포어로 제한해 PostgreSQL 경로가 쓰러지지 않게 한다. 초과분은 거절이 아니라 짧은 대기 후 거절(503/409 재시도 안내) 중 하나를 정해야 하며, 정책 선택은 미결이다.
4. **관측 보강.** `fail_open`을 사유(`timeout`, `unseeded`, `breaker_open`)별 태그로 나눈다. 이번에는 사유를 로그의 WARN 클래스로만 구분할 수 있었다.

## 남은 과제 (미실행)

- 결함 주입 timeout 5ms의 재시도(1회는 k6 조기 종료로 제외)와 5ms~200ms 사이 경계 탐색. 미측정.
- `BREAKER_COOLDOWN` 다른 값(PT0.5S, PT5S) 비교. 미측정.
- 샘플러를 끈 대조 run, 다수 SKU, 다수 앱 인스턴스, 처리량 knee(2,000/s 초과) 측정. 미측정.
- 위 보강 설계안(precheck 폴백, 계수 기반 breaker, bulkhead, 사유별 태그)의 구현과 검증. 미착수.
- 점검표의 n≥5 반복과 부호 검정·신뢰구간. 미실행.

## 한계

- 조건당 n: 진단 3, 가설 각 3, 짝 비교 4+4(대조 2), 결함 주입 유효 1. 신뢰구간과 부호 검정은 하지 않았다. 점검표의 n≥5(효과 30% 미만이면 10 이상)를 못 채운다.
- 순서는 고정된 ABBA다. 호스트가 다른 프로세스와 공유돼 load1이 4~12.5로 흔들렸고, 시간 추세·호스트 부하를 직접 통제하지 못했다.
- 샘플러 비용 미비교: Redis 팔에서만 `docker exec redis-cli INFO` 호출이 초당 3회 추가된다(precheck 팔은 없다). 샘플러를 끈 대조 run은 없다. Redis 팔에 불리한 방향의 비대칭이지만 크기는 **미측정**이다.
- CPU 한도 합이 VM vCPU를 넘는다(10.25~11.25 대 8). k6 CPU는 진단·가설 a에서 한도 1, 나머지에서 2다. 짝 비교의 k6 CPU 최대는 코어 환산 127%로 한도 2 대비 63%이고 평균은 최대 82%(한도 2 대비 41%)다.
- 워밍업이 10초·100/s로 점검표의 권장(목표의 50%, 30~60초)에 못 미친다. 측정 30초이고 5초 버킷 정상상태 확인은 하지 않았다. stock TTL(5초) 재시드는 구간에 포함됐다(시드 경로 진입 수로 확인). Hikari 풀 변동·checkpoint는 포함 여부를 기록하지 않았다.
- 재고 100인 단일 SKU, 요청의 99.8% 이상이 설계상 거절이다. 다수 SKU와 꼬리 SKU의 gate 오버헤드는 **미측정**이다. 단일 SKU는 "메커니즘 확인"에만 쓴다.
- 두 팔 모두 dropped가 0.1% 기준을 넘는다. completed/s는 offered 2,000에 붙어 있어 knee 이하의 차이를 구분할 수 없다.
- p99는 드롭된 반복이 빠진 완료분 값이라 표에 싣지 않았다(결함 주입의 p99 11.5초도 같은 이유로 하한이다).
- 결함 주입의 두 번째 run은 k6 비정상 종료로 제외했다.
- 느린 명령 통계는 서버 실행 시간만이다. 클라이언트 쪽 지연(lettuce 대기, 스레드 큐잉)은 **미측정**이다.

## 주장 등급

| 주장 | 등급 | 근거 |
| --- | --- | --- |
| 보강 jar의 Redis gate는 workflow 행이 정확히 100(추가 0), precheck는 26~33 추가 | A | 결정론적 카운트, 짝 비교 8 run 전부 |
| location 호출/요청 Redis 0.0042 대 precheck 0.0133, 약 3.2배(범위 비겹침, n=4+4) | A(카운트) | 호출 수 카운트. 배율은 이 단일 SKU 조건 한정 |
| 불변식(오버셀 0, RESERVED=100, 비종결 0) | A | 유효 run 20개 전부(결함 주입 run은 201/409 외 응답 3건 있음) |
| timeout 5ms에서 `fail_open` 연쇄가 EXP-062 증상 묶음을 재현한다 | A(정성적 메커니즘) | 대조 arm(기본 timeout)에서 증상 없음 |
| 기본 timeout 200ms에서 2,000/s 13 run 동안 timeout 0 | A | gate 카운터와 WARN 클래스 |
| Redis와 precheck의 2,000/s 처리량은 구분되지 않는다 | B(하한/상한만) | 범위가 겹침, A/A 잡음 1.5%, offered에 붙음 |
| EXP-062의 `fail_open`이 명령 timeout 때문이었다 | 추정 | 당시 로그 없음, 재현 실패 |
| 어느 timeout 값이 최적이다 | 주장하지 않음 | 5ms와 200ms, 1초 3점만 측정 |
| 처리량 절대값, p99, 개선 퍼센트 | C | 쓰지 않는다 |

## 재현

```bash
GATE=redis RATES=2000 DURATION=30s \
  LOCATION_CPUS=2 K6_CPUS=2 \
  load-tests/run-hot-sku-coordinator.sh
# 변형: GATE=precheck
#       ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_COMMAND_TIMEOUT=PT0.005S   (결함 주입)
#       ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_BREAKER_COOLDOWN=PT2S      (기본값)
```

결과 원본은 `.local/redis-failopen/exp061/<라벨>/`(`analysis.json`, `series.csv`, `app.log`, `redis-slowlog.last`)와 각 run의 k6 요약에 있고 git에서 제외되어 저장소만으로는 재현·검증할 수 없다. 디렉터리 이름 `exp061`은 작업 당시 이름이며 EXP-061과 무관하다. 집계 도구는 `load-tests/redis-gate/`의 `aggregate.py`·`analyze.py`·`sampler.py`다.
