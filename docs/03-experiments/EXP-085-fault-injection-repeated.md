# EXP-085: 장애 주입을 조건별 5회 이상 반복하고 하네스를 저장소에 올리면 예약 불변식은 계속 지켜지는가

- 질문: [EXP-070](EXP-070-real-stack-fault-injection.md)의 감사 불변식(응답받은 예약 유실 0, order 대 location 수 일치, 중복 예약 없음)이 PostgreSQL 서버 재시작·크래시 복구, application CPU·메모리 압박에서도 유지되는가? 이미 한 장애(location 서버 `kill`)를 다시 돌리면 결과가 얼마나 흔들리는가?
- 상태: 탐색 실험. 조건별 n=5(추가 2조건 포함 9개 조건, 45회). **가설은 실행 전에 적었고** 그 커밋(`wip(experiment): 장애 주입 하네스 초안과 EXP-085 가설을 실행 전에 기록한다`)이 git 이력에 남아 있다. 하네스는 `load-tests/fault-injection/`에 올렸다.
- 날짜: 2026-11-25
- 관련: [EXP-068](EXP-068-stage-evidence-map.md)(8단계 표 A1·A2), [EXP-070](EXP-070-real-stack-fault-injection.md), [EXP-071](EXP-071-production-like-fault-injection.md), [EXP-066](EXP-066-db-outage-convergence.md), [EXP-072](EXP-072-kafka-outage-relay.md), [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md), D-144

## 왜 했는가

[EXP-068](EXP-068-stage-evidence-map.md)은 8단계 장애 주입 표에서 두 가지를 짚었다. (A1) "PostgreSQL 서버 재시작(WAL 재생)"과 "CPU·메모리 스트레스"는 실행하지 않았다. (A2) EXP-066·070·071·072의 장애 주입 결과는 조건별 n=1이고, 실행 도구는 저장소에 올리지 않아 저장소만으로는 재현할 수 없다. n=1로는 "이 조건에서 한 번 괜찮았다"만 말할 수 있고, 실행 사이에 얼마나 흔들리는지(같은 조건의 클라이언트 응답 분포)는 알 수 없다.

## 가설 (실행 전에 적음)

판정 기준: 아래 불변식 중 한 번이라도 어긋나면 그 가설은 틀린 것이고, 증거를 보존해 원인을 찾는다.

- H1 (핵심): 8개 조건(대조군 포함, 아래 "조건" 표의 앞 8개) 모두에서 모든 실행이 (a) 응답받은(200·201·202) 예약 유실 0, (b) order 대 location 수 일치, (c) 중복 예약 0, (d) 90초 안에 모든 workflow 종결을 만족한다.
- H2 (`docker restart`): PostgreSQL 컨테이너 이미지는 `SIGINT`(fast shutdown)로 멈추므로 종료 때 체크포인트가 기록돼 시작 로그에 `redo starts at`이 **없다**. 즉 "재시작"은 WAL 재생을 일으키지 않을 것이다. 이 경우 WAL 재생 근거는 아래 `kill`·`crash` 조건이 맡는다.
- H3 (컨테이너 `kill`, postmaster SIGKILL): 다시 시작할 때 5회 모두 `not properly shut down`과 `redo starts at`·`redo done at`이 로그에 남는다(실제 WAL 재생이 일어난다). 클라이언트가 `201`을 받은 예약은 5회 모두 재시작 뒤에도 남는다. 클라이언트 응답은 EXP-070의 n=1 값(`201` 280, `500` 4, `503` 276)과 같은 형태이고 `503`은 수백 건이다.
- H4 (`crash`, WAL writer SIGKILL): postmaster가 컨테이너를 내리지 않고 모든 backend를 다시 띄운다. 로그에 `redo starts at`이 남고, 장애 구간 응답 오류는 `kill`보다 적다(EXP-071 n=1에서 `500` 11, `503` 9).
- H5 (application CPU 압박): 불변식은 유지된다. 요청 일부가 시간 초과(상태 0)나 `5xx`로 끝날 수 있지만 DB에 닿은 요청은 정리된다. 처리 지연이 늘 것이다.
- H6 (application 메모리를 한도 64MiB 아래까지 채움): 대부분 OOM 없이 지나가고(`restart_count` 0), 불변식은 유지된다. JVM 증가분 때문에 일부 실행은 OOM으로 끝날 수 있어 예측이 갈린다.
- H7 (application 메모리를 한도 128MiB 넘게 채움): 커널이 JVM을 OOM kill하고 compose 재시작 정책이 다시 띄운다(5회 모두 `restart_count`≥1, `oom_killed` true). 진행 중 요청은 상태 0이나 `5xx`로 끝나지만 order DB에 커밋된 workflow는 복구 worker가 끝까지 처리해 불변식은 유지된다.
- H8 (location B 서버 `kill` 반복): 5회 모두 560건이 `RESERVED`로 수렴한다. 클라이언트 `201`/`202` 비율은 EXP-070 n=1(`201` 205, `202` 355)과 같은 방향이지만 실행마다 수십 건 흔들린다.
- H9 (대조군, 장애 없음): 5회 모두 `201` 560건이고 불변식이 모두 유지된다.
- H10 (복구 지연): order DB를 `kill`·`restart`하면 EXP-070이 찾은 DNS 부정 캐시(10초) 때문에 DB가 돌아온 뒤 첫 `201`까지 8초 이상 걸린다.

## 조건

하네스: `load-tests/fault-injection/run-fault-injection.sh`(실행), `compose.yaml`(스택), `reservation_load.py`(부하), `audit_invariants.py`(감사·요약). 한 명령으로 실행되고 끝나면 프로젝트(컨테이너·네트워크·볼륨)를 지운다.

- 스택(실행마다 새로 띄움): application JVM 1개(2 CPU·1 GiB 한도, `-XX:MaxRAMPercentage=60`), inventory-location JVM 2개(창고 A·B), PostgreSQL 18.6 3개(order 1, 창고별 1), mailpit. 이미지는 `eclipse-temurin:25-jre`·`postgres:18.6`, jar는 2026-11-25 `origin/main`(`fdbc106b`)에서 만든 `bootJar`. 결제는 `disabled`. 복구 worker는 기본값(poll 1초, lease 30초).
- 부하: 요청마다 주문 1건, 창고 A에 SKU 2개·창고 B에 SKU 1개(EXP-070과 같은 모양). 초당 40건을 14초간 일정 속도로(총 560건), 클라이언트 스레드 64개, 요청 시간 초과 15초, **재시도 없음**. 실행 전에 같은 스택에 초당 20건 5초 워밍업(다른 SKU).
- 장애: 부하 시작 3초 뒤 주입. 지속 시간 기본 6초(`FAULT_FOR`).
- 감사(DB에서 직접 센다, 이 실행이 만든 행만): (a) 200·201·202로 응답받은 요청이 끝에 order DB에서 `RESERVED`가 아니면 유실, (b) order `RESERVED` workflow 수 = 창고 A 예약 수 = 창고 B 예약 수 = 세 SKU의 reserved 수량, (c) order workflow와 각 창고에서 `order_id` 중복 0·예약 항목 수량이 1이 아닌 행 0, (d) 90초 안에 `PENDING`·`RETRY_PENDING`·`COMPENSATING`·`COMPENSATION_REQUIRED`·`CANCEL_PENDING`이 0.
- 장애 종류:

| 조건 | 주입 방법 | 비고 |
| --- | --- | --- |
| none | 없음 | 대조군 |
| order-db-restart | `docker restart -t 10`(order DB) | 정상 종료 후 시작 |
| order-db-kill | `docker kill`(postmaster SIGKILL), 6초 뒤 `docker start` | 크래시 복구 |
| order-db-crash | WAL writer 프로세스만 `kill -9`, postmaster가 backend를 다시 띄움 | 컨테이너는 그대로 |
| app-cpu | application cgroup 안에서 CPU 바쁜 루프 6개를 6초 | 가설 H5 |
| app-memory | application cgroup 안 `/dev/shm`에 한도보다 약 64 MiB 모자라게(504~512 MB) 6초 | 가설 H6 |
| app-oom | 같은 방법으로 한도를 약 128 MiB 넘게(694~705 MB) 6초 | 가설 H7 |
| location-b-kill | `docker kill`(location B JVM), 6초 뒤 `docker start` | EXP-070 반복 |
| app-cpu-throttle | `docker update --cpus 0.2`(CFS 하드 한도)를 6초, 뒤에 `--cpus 2`로 복구 | **실행 뒤에 추가**(편차 1 참고) |
| order-db-kill, 즉시 시작 | `docker kill` 직후 `docker start`(`FAULT_FOR=0`) | **실행 뒤에 추가**(H10 확인용) |

- 호스트: Apple 실리콘 Mac, Colima Docker VM(8 CPU, 약 13 GB). **같은 VM에서 다른 에이전트의 Gradle·Testcontainers·compose 스택이 같이 돌고 있었다.** 본 실행 40회의 전후 스냅샷 80개에서 호스트 1분 load average는 3.4~10.6(중앙값 5.7). 그래서 시간 값은 맥락으로만 적고 결론은 개수와 불변식에 둔다.
- 실행 순서: location-b-kill, none, app-oom, order-db-restart, order-db-kill, order-db-crash, app-cpu, app-memory(각 5회 연속, 무작위화하지 않음), 이어서 app-cpu-throttle, order-db-kill 즉시 시작. 본 실행 전에 조건별 n=1 시험 실행을 한 번 했고 집계에서 뺐다(편차 2).

## 결과(각 n=5, 요청 560건)

### 불변식

| 조건 | n | 유실 0 | 수 일치 | 중복 없음 | 수렴 | 4개 모두 |
| --- | --- | --- | --- | --- | --- | --- |
| none | 5 | 5 | 5 | 5 | 5 | 5 |
| order-db-restart | 5 | 5 | 5 | 5 | 5 | 5 |
| order-db-kill | 5 | 5 | 5 | 5 | 5 | 5 |
| order-db-crash | 5 | 5 | 5 | 5 | 5 | 5 |
| app-cpu | 5 | 5 | 5 | 5 | 5 | 5 |
| app-memory | 5 | 5 | 5 | 5 | 5 | 5 |
| app-oom | 5 | 5 | 5 | 5 | 5 | 5 |
| location-b-kill | 5 | 5 | 5 | 5 | 5 | 5 |
| app-cpu-throttle(추가) | 5 | 5 | 5 | 5 | 5 | 5 |
| order-db-kill 즉시 시작(추가) | 5 | 5 | 5 | 5 | 5 | 5 |

45회 모두 네 불변식이 유지됐다. 불변식 위반은 0건이다.

### 클라이언트 응답(실행 5회의 최소 / 중앙값 / 최대, 요청 수)

| 조건 | 201 | 202 | 500 | 503 | 상태 0(응답 없음) | 응답은 실패인데 DB는 `RESERVED` |
| --- | --- | --- | --- | --- | --- | --- |
| none | 560 / 560 / 560 | 0 | 0 | 0 | 0 | 0 |
| order-db-restart | 524 / 533 / 534 | 0 | 2 / 5 / 6 | 20 / 23 / 30 | 0 | 0 / 1 / 1 |
| order-db-kill | 287 / 290 / 291 | 0 | 4 / 5 / 5 | 264 / 265 / 269 | 0 | 0 / 0 / 1 |
| order-db-crash | 554 / 555 / 556 | 0 | 4 / 5 / 6 | 0 | 0 | 0 / 0 / 1 |
| app-cpu | 560 / 560 / 560 | 0 | 0 | 0 | 0 | 0 |
| app-memory | 560 / 560 / 560 | 0 | 0 | 0 | 0 | 0 |
| app-oom | 148 / 150 / 166 | 0 | 0 | 0 | 394 / 410 / 412 | 0 / 1 / 1 |
| location-b-kill | 135 / 136 / 139 | 421 / 424 / 425 | 0 | 0 | 0 | 0 |
| app-cpu-throttle(추가) | 474 / 489 / 501 | 0 | 0 | 59 / 71 / 86 | 0 | 20 / 32 / 38 |
| order-db-kill 즉시 시작(추가) | 534 / 536 / 537 | 0 | 3 / 5 / 5 | 18 / 19 / 22 | 0 | 0 / 0 / 1 |

- "응답은 실패인데 DB는 `RESERVED`"는 클라이언트가 200·201·202 이외(5xx·시간 초과)를 받았지만 order DB에 `RESERVED` workflow가 남은 요청 수다. 위반이 아니다(응답을 알 수 없던 요청이 실제로는 성공). 같은 key로 재요청하면 재생되는지는 이 하네스가 재시도하지 않아 확인하지 않았다.
- 응답받은 `201`·`202` 중 `RESERVED`로 남지 않은 요청은 45회 모두 0건이다.

### 장애가 실제로 일어났는가(증거)와 복구 시간

| 조건 | order DB 로그 `not properly shut down` | `redo starts at` | application 재시작 | 커널 OOM 이벤트 |
| --- | --- | --- | --- | --- |
| order-db-restart | 0/5 | 0/5 | 0/5 | 0/5 |
| order-db-kill | 5/5 | 5/5 | 0/5 | 0/5 |
| order-db-kill 즉시 시작 | 5/5 | 5/5 | 0/5 | 0/5 |
| order-db-crash | 5/5 | 5/5 | 0/5 | 0/5 |
| app-oom | 0/5 | 0/5 | 5/5 | 5/5 |
| 나머지 | 0 | 0 | 0 | 0 |

- 크래시 복구(`redo starts at`·`redo done at`)는 `kill` 5회, 즉시 시작 `kill` 5회, `crash` 5회 모두 로그에 있었다. `docker restart`는 5회 모두 정상 종료로 시작해 WAL 재생이 없었다(H2 그대로).
- DB가 다시 열린 뒤 첫 `201`까지: `restart` 0.40~0.50초, `kill`(6초 뒤 시작) 0.45~0.54초, `kill`(즉시 시작) 0.31~0.38초(각 n=5, 시작 명령 반환 시각 기준). 마지막 오류 응답은 그 뒤 1.1초 안에 끝났다.
- **수렴 시간(부하 종료 뒤 모든 workflow가 끝날 때까지, 맥락용)**: 대부분 0.4~0.6초지만 `restart` 3/5, `crash` 1/5, `app-oom` 3/5, 즉시 시작 `kill` 2/5는 19.5~21.4초였다. `app-cpu-throttle`은 5/5가 24.8~25.5초, `location-b-kill`은 9.7~14.3초(location JVM 기동 포함)다. location을 뺀 15초 이상 14회는 모두 "응답은 실패인데 DB는 `RESERVED`"인 요청이 1건 이상(throttle은 20~38건) 있었다. 반대로 그런 요청이 있었어도 0.4~0.6초에 수렴한 실행이 6초 `kill`에 2회 있어 둘의 관계는 필요조건 쪽이지 충분조건이 아니다.
- 이 20초 꼬리는 서버가 응답하지 못한 요청이 남긴 workflow가 lease(`lease-duration` PT30S)가 풀린 뒤에야 복구 worker에 잡히는 것과 시간이 맞는다(부하 시작 후 약 33초). 이 해석은 lease 만료 시각을 직접 재지 않았으므로 **확인하지 않은 설명**이다.

## 가설 대조

| 가설 | 결과 |
| --- | --- |
| H1 불변식 유지 | 맞음. 45회 모두 위반 0 |
| H2 restart는 WAL 재생 없음 | 맞음. 5/5 `redo starts` 없음 |
| H3 kill은 WAL 재생, 응답 형태는 EXP-070과 같음 | 맞음. 5/5 재생. `201` 287~291·`500` 4~5·`503` 264~269로 EXP-070 n=1(280·4·276)과 같은 범위 |
| H4 crash는 kill보다 오류가 적음 | 대체로 맞음. `500` 4~6·`503` 0(EXP-071 n=1은 `500` 11·`503` 9로 이보다 많았다. 하네스가 달라 이유는 모른다) |
| H5 CPU 압박은 시간 초과·`5xx` 가능 | **틀림(조건이 약했다)**. 560건 모두 `201`, 최대 지연 0.19~0.59초. 바쁜 루프 6개로는 JVM을 굶기지 못했다. 그래서 하드 한도 조건을 추가했다 |
| H6 메모리 한도 근처는 OOM 없음 | 맞음. 5/5 OOM 없음, 지연 변화 없음(최대 0.04~0.26초) |
| H7 한도 초과는 OOM kill과 재시작 | 맞음. 5/5 커널 OOM 이벤트와 컨테이너 재시작. 약 10초 동안 연결 거절(상태 0) |
| H8 location B kill 반복은 수십 건 흔들림 | **틀림**. `201` 135~139, `202` 421~425로 5회 사이 흔들림이 4건 안이다. EXP-070 n=1(`201` 205, `202` 355)과의 차이(약 70건)는 반복 편차가 아니라 설정 차이다(아래) |
| H9 대조군 | 맞음. 5/5 `201` 560 |
| H10 DNS 부정 캐시 10초 지연 | **틀림**. `restart`·`kill`(6초 뒤)·`kill`(즉시) 모두 DB가 열린 뒤 0.3~0.5초에 첫 `201`. EXP-070의 10초 지연은 이 하네스에서 재현되지 않았다. 이유는 확인하지 못했다(아래) |

### 설명이 필요한 차이

- **location B 응답 형태가 EXP-070보다 `202`가 많다.** `docker start`가 돌아온 뒤에도 location JVM이 뜨는 데 5.1~10.5초(location B 로그의 `Started ... in`, 10회)가 더 걸려 실제 중단은 6초가 아니라 약 11~16초였다(수렴이 장애 종료 뒤 13.8~17.8초). 기록하지 않은 EXP-070의 조건(자원 한도 없음, 기동 시간)과 다른 것이 원인이라고 추정하지만 이 실행으로 가려내지는 않았다.
- **EXP-070의 10초 지연이 재현되지 않았다.** 후보: (가) 이 하네스는 compose 서비스 이름으로 접속하는 것은 같으나 그 실험의 `networkaddress.cache.negative.ttl`·재시작 타이밍 조건을 기록하지 않아 같은지 모른다, (나) 해당 지연은 그 실험 스택의 Docker DNS 동작에 의존했다. 어느 쪽인지, 또는 지연이 사라진 것인지 이 실행으로 구분하지 않았다. EXP-070의 결론을 이 결과로 뒤집지는 않고 "이 하네스에서는 재현되지 않았다"만 적는다.

### 이 하네스가 위반을 잡아내는가

시험 실행(집계 제외) 중 하네스 오류로 location B가 다시 시작되지 않은 실행이 1회 있었고, 감사는 `lost` 421·수 불일치·미수렴을 보고했다(시험 실행 1회, 의도한 음성 대조군이 아니라 우연한 것이다). 불변식을 일부러 깨뜨리는 음성 대조군은 만들지 않았다. 따라서 "감사가 위반을 놓치지 않는다"는 이 1건으로만 지지된다.

## 편차(계획과 다르게 한 것)

1. `app-cpu`가 아무 영향을 주지 못해(H5) 실행 뒤에 `app-cpu-throttle`을 추가했다. 이 조건에는 사전 가설이 없다. 결과: `503` 59~86건, 요청 최대 지연 3.3~4.1초, 응답은 실패인데 DB는 `RESERVED`인 요청 20~38건, 불변식은 유지됐다. 같은 이유로 `order-db-kill` 즉시 시작도 H10 확인용으로 실행 뒤에 추가했다.
2. 본 실행 전에 조건별 n=1 시험 실행을 한 번 했다. 시험에서 하네스 오류 3개를 고쳤다: 컨테이너 조회가 종료된 컨테이너를 빼던 것(`docker compose ps -a`로 변경), OOM 판정이 재시작 정책 때문에 `State.OOMKilled`가 지워져 `docker events`로 바꾼 것, 정상 종료 후 수렴 시간 측정에 환경 스냅샷 시간이 섞이던 것. 시험 결과는 집계에 넣지 않았다.
3. 조건별 5회 연속 실행이라 시간대 효과(공유 머신 부하 변동)가 조건과 섞인다. 무작위화하지 않았다.
4. EXP-070과 같지 않은 점: 컨테이너 자원 한도 있음(application 2 CPU·1 GiB 등), location을 `restart` 정책 없이 러너가 시작, application만 `restart: unless-stopped`, Python 부하 생성기(EXP-070은 도구 미공개), 매 실행 새 스택.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것(이 스택, 이 조건, 조건별 n=5): 위 장애 9종과 대조군에서 응답받은 예약 유실·order 대 location 불일치·중복 예약이 0건이었다. PostgreSQL을 SIGKILL하고 크래시 복구한 뒤에도 클라이언트가 `201`을 받은 예약이 모두 남았다(`kill` 5회 287~291건, 즉시 시작 `kill` 5회 534~537건, `crash` 5회 554~556건). application을 커널 OOM으로 죽였을 때도 order DB에 커밋된 workflow는 모두 끝까지 처리됐다.
- 증명하지 못한 것:
  - n=5는 드문 경쟁을 잡기에 작다. "0/5"는 5% 미만의 위반율을 배제하지 못한다(5회 모두 통과할 확률은 위반율 p가 13%여도 약 50%).
  - 컨테이너 SIGKILL은 커널에 이미 쓴 데이터를 잃지 않는다. fsync 설정·정전·VM 디스크 캐시 유실·WAL 손상은 검증하지 못했다. "커밋된 행이 남았다"는 프로세스 크래시에 대한 말이다.
  - 단일 호스트 단일 Docker VM이고 다른 작업과 자원을 나눠 썼다. 시간 값은 일반화하지 않는다.
  - 장애 구간이 6초(application OOM은 재시작 약 10초)로 lease 30초보다 짧다. lease보다 긴 중단은 EXP-066의 메모리 모델 결과에만 근거한다.
  - Kafka 중단(EXP-072는 n=1 그대로), 디스크 가득 참, 네트워크 분리·지연·손실(EXP-070·071은 n=1 그대로), application 여러 대·로드 밸런서, 장애 중 재시도(같은 idempotency key 재생), relay를 가진 application kill은 실행하지 않았다.
  - CPU 압박은 "바쁜 루프"는 효과가 없었고 "하드 한도 0.2 CPU"만 효과가 있었다. 실제 운영의 이웃 간섭과 같다고 보지 않는다.

## 재현

```bash
./gradlew :atlas-application:bootJar :atlas-inventory-location:bootJar
FAULTS="order-db-kill app-oom" RUNS=5 load-tests/fault-injection/run-fault-injection.sh
```

결과는 `.local/fault-injection/<시각>/<조건>/run-<n>/`(git 무시)에 `requests.jsonl`, `result.json`, `meta.json`, 컨테이너 로그, 실행 전후 `uptime`·`docker stats` 요약이 남고 끝에 조건별 표를 출력한다. jar를 Docker VM이 읽을 수 없는 경로(예: `/tmp`)에 만들면 `APP_JAR`·`LOCATION_JAR`로 읽을 수 있는 경로를 준다. Docker 한 대에서만 확인했다.

## 후속 제안(적용하지 않음)

- 이 하네스를 기준으로 Kafka 중단, 네트워크 분리, lease보다 긴 중단, application 2대와 같은 idempotency key 재요청을 같은 방식으로 반복한다.
- 20초 꼬리의 원인(lease 만료)을 복구 worker 로그나 workflow `lease_until` 조회로 직접 확인한다.
- EXP-070의 DNS 부정 캐시 10초 지연이 어떤 조건에서 나오는지 `networkaddress.cache.negative.ttl`을 바꿔 가며 같은 하네스로 가린다.
