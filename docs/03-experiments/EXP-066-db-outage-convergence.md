# EXP-066: order DB 연결이 끊겼다 돌아오기를 반복해도 확인한 예약이 사라지지 않는가

- 질문: coordinator와 order DB 사이 연결이 반복해서 끊기고 복구돼도, 호출자가 `RESERVED` 응답을 받은 예약이 사라지지 않고, location이 주인 없는 재고를 쥐지 않으며, 모든 workflow가 끝나는가?
- 상태: 탐색 실험. 조건별 n=1. **실험 코드는 저장소에 올리지 않았다**(아래 "코드를 올리지 않은 이유").
- 날짜: 2026-11-24
- 관련: [EXP-064](EXP-064-location-fault-convergence.md), [EXP-053](EXP-053-inventory-location-failure-load.md), [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md), [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md)

## 왜 했는가

[EXP-064](EXP-064-location-fault-convergence.md)는 location 쪽 장애(요청 유실, 응답 유실, 503)만 다뤘다. order DB 쪽 장애는 [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md)이 커밋 경계를 단일 쿼리로, [EXP-053](EXP-053-inventory-location-failure-load.md)이 JVM 재시작을 1회 확인했을 뿐이다. 연결이 반복해서 끊기는 동안 여러 workflow가 동시에 진행되는 경우는 확인한 적이 없다.

## 환경과 방법

- 환경: Apple 실리콘 Mac, Colima Docker VM, JDK 25.0.5.1, PostgreSQL 18.6(Testcontainers), Toxiproxy 2.12.0. 호스트 CPU·메모리 상세는 기록하지 않았다. 이 실험은 처리량이 아니라 상태 수렴을 보므로 시간 값은 참고용이다.
- 구성: 애플리케이션 JDBC → Toxiproxy → PostgreSQL. Toxiproxy의 프록시를 `enabled=false`로 바꾸면 listen 소켓을 닫고 열린 연결을 모두 끊는다. 다시 켜면 새 연결이 맺어진다. PostgreSQL 자체는 계속 살아 있어 이미 커밋된 데이터는 그대로이고 커밋 중이던 트랜잭션만 결과를 알 수 없다.
- location은 [EXP-064](EXP-064-location-fault-convergence.md)와 같은 메모리 모델(계약을 따르는 `MockWebServer`). location별 재고 40, 주문 80건이 각각 두 location에 1개씩 요구한다. 동시 12스레드로 `submit`한다.
- 장애: 별도 스레드가 80~300ms(`80 + random(220)`, seed 5)마다 프록시를 켰다 껐다 한다. 한 조건은 location 장애 없음, 다른 조건은 EXP-064와 같은 location 장애 폭풍(seed 31, 32)을 함께 넣는다. 장애 중에도 복구 worker(`recoverDue`)를 20회 돌린다.
- 설정: Hikari `connection-timeout` 1초, `read-timeout` 1초, `lease-duration` 6초, `base-backoff` 20ms, `max-backoff` 100ms. 기본값(lease 30초, read-timeout 2초)에서는 기대한 결과가 나오지 않았다(아래).
- 확인: 호출자가 `RESERVED` 응답을 받은 주문이 최종 `RESERVED`로 남는가(`lost_acknowledged`), 주문은 `RESERVED`인데 location이 쥐고 있지 않은가(`dangling`), location이 쥔 예약의 주인이 `RESERVED` workflow인가(`orphan_holds`), 진행 중 workflow 0, 재고 보존.
- 장애를 멈춘 뒤: 풀이 회복될 때까지 `SELECT 1`을 연속 25회 성공할 때까지 확인하고(시도 25~28회), `recoverDue`를 돌려 수렴시킨다.

## 결과 (조건별 1회)

| 항목 | location 장애 없음 | location 장애도 함께 |
| --- | --- | --- |
| DB 끊김 횟수 / 전환 횟수 | 5 / 10 | 8 / 16 |
| 장애 구간 길이 | 2,156ms | 3,523ms |
| `submit`이 응답을 돌려준 주문 / 그중 `RESERVED` | 61 / 38 | 60 / 24 |
| 호출자에게 예외로 전달된 횟수(`submit`·`recoverDue`) | 26 | 38 |
| 장애 종료 뒤 복구 라운드 / 시간 | 135 / 4,459ms | 149 / 5,150ms |
| `NEEDS_REVIEW` | 0 | 0 |
| 최종 상태 | `RESERVED` 40, `FAILED` 37 | `RESERVED` 40, `FAILED` 33 |
| 응답받은 `RESERVED`가 사라진 수 | 0 | 0 |
| 주문 쪽 `RESERVED`인데 location이 쥐지 않은 수 | 0 | 0 |
| 주인 없는 location 보유 | 0 | 0 |
| location별 보유 재고 | 40 / 40 | 40 / 40 |

- 호출자에게 전달된 예외는 모두 `DataAccessResourceFailureException`(연결 I/O 오류)이다. 80건 중 약 20건은 `submit`이 응답 없이 예외로 끝났다. 이는 "결과를 알 수 없음"이며, 같은 멱등 키로 다시 호출하면 이미 만들어진 workflow를 이어받는다.
- 재고는 location당 40이므로 최종 `RESERVED`가 40이 된 것은 재고가 정확히 소진됐음을 뜻한다. `FAILED` 37, 33건은 재고 부족 실패와 장애로 인한 실패가 섞여 있으며 분리하지 않았다.

## 설정과 복구 시간의 관계

- DB 연결이 끊긴 호출이 쥔 lease가 풀려야 다른 worker가 그 workflow를 가져간다. `LocationReservationRepository`의 due 조회와 claim 쿼리가 `lease_until <= now`인 행만 고르기 때문이다.
- 설정 검증(`LocationReservationConfiguration.validateLeaseTiming`)은 "lease의 절반이 connect + read timeout + 1초보다 커야 한다"를 강제한다. 그래서 기본 connect 250ms, read-timeout 2초에서 lease를 줄일 수 있는 하한은 약 6.5초다. 이 실험은 read-timeout 1초, lease 6초와 기본값(read-timeout 2초, lease 30초) 두 설정으로 측정했다.
- 연결이 끊긴 직후 커넥션 풀에는 끊긴 연결이 남아 있어 처음 몇 번의 호출이 실패한다. 이 실험은 풀이 회복될 때까지 `SELECT 1`이 연속 25회 성공하는 것을 확인한 뒤 복구를 측정했다(시도 25~28회).

| lease | read-timeout | 복구 시간(location 장애 없음 / 있음) |
| --- | --- | --- |
| 6초 | 1초 | 4,459ms / 5,150ms |
| 30초(기본) | 2초(기본) | 28,777ms / 29,404ms |

기본 lease에서의 측정은 조건별 n=1이고 복구 대기 상한은 120초였다. 복구 시간은 lease 길이에 거의 비례한다. lease를 6초와 30초 두 값으로만 비교했고 중간 값은 측정하지 않았다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 위 조건(80건, 반복 끊김, 축소한 timeout과 lease)에서 응답받은 예약이 사라지지 않았고, 두 쪽 상태가 어긋나지 않았으며, 장애 종료 뒤 모든 workflow가 끝났다.
- 증명하지 못한 것:
  - 조건별 n=1이다. 끊김 횟수가 5회와 8회로 다르게 나온 것처럼 타이밍에 따라 결과가 달라질 수 있다.
  - lease 6초와 30초 이외 값에서의 수렴 시간, 그리고 DB가 끊긴 동안 새 주문이 쌓이는 규모가 커질 때의 복구 시간(여기서는 주문 80건).
  - PostgreSQL 서버가 죽었다 다시 뜨는 경우(WAL 재생, 커밋 유실 가능성). 이 실험에서 PostgreSQL은 계속 살아 있었다.
  - 장애 중 커밋 결과를 알 수 없던 트랜잭션이 실제로 커밋됐는지는 구분하지 않았다. 결과적으로 어긋남이 없었다는 것만 확인했다.
  - location은 메모리 모델이다. 실제 `InventoryReservationServiceImpl`과의 동작은 코드를 읽어 맞췄을 뿐 검증하지 않았다.
- 새 결함은 발견하지 못했다. 결함이 없다는 증거는 아니다.

## 코드를 올리지 않은 이유

- 이 실험은 Testcontainers 두 개(PostgreSQL, Toxiproxy)를 추가로 기동하고 DB 끊김 타이밍에 의존해 CI에서 불안정할 수 있다. 확인하고 싶었던 것은 한 번 실행해 보는 증거이지 회귀 방지가 아니므로 저장소 테스트로 두지 않았다.
- 하네스 소스(Java 4개 파일, 759줄)는 저장소 밖 `~/Documents/ATLAS-backups/fault-injection-local/`에 보관했고 `.git/info/exclude`로 로컬에서만 가린다. 따라서 이 문서의 수치는 **저장소만으로는 재현할 수 없다**. 위 "환경과 방법"의 모든 파라미터를 적은 것은 같은 실험을 다시 구현할 수 있게 하려는 것이다.

## 도구 조사와 현재 상태

| 필요 | 도구 | 현재 상태 |
| --- | --- | --- |
| TCP 수준 연결 끊김·지연 | Toxiproxy 2.12.0 | 사용 중([EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md), 이 실험). 프록시 `enabled` 토글로 끊김, toxic으로 지연·reset |
| 컨테이너 kill·pause·stop·rm, 지연·손실(netem), CPU·메모리 스트레스 | Pumba(`alexei-led/pumba`) | **설치하지 않았고 실행하지 않았다.** README는 Docker, containerd, Podman 컨테이너를 대상으로 `kill`, `stop`, `pause`, `rm`, `netem delay`·`loss`, `stress`를 제공한다고 적는다. Colima Docker VM에서 `tc netem`이 동작하는지는 특권 컨테이너(`alpine` + `iproute2`)로 `tc qdisc add dev lo root netem delay 1ms`가 성공하는 것까지만 확인했다 |
| Kubernetes 장애 주입 | Chaos Mesh | 이 저장소는 Kubernetes 없이 Docker Compose로 실행하므로 대상이 아니다. Chaos Mesh가 Kubernetes 전용이라는 점은 이 세션에서 다시 확인하지 않은 일반 지식이다 |
| 스레드 덤프·JFR | `jcmd`, `jstack`, `jfr`, `jhsdb`(JDK 25.0.5.1에 포함) | 사용 가능함을 확인했다. 이번 실험에서는 멈춤(hang)이 관찰되지 않아 실제로 쓰지 않았다 |

다음에 hang이 관찰되면 정할 절차(아직 실행하지 않은 계획): 테스트 JVM이 설정한 시간 안에 끝나지 않을 때 `jcmd <pid> Thread.print`로 스레드 덤프를 5초 간격 3회 남기고, 풀 고갈이면 `Hikari` 메트릭(active, pending)과 함께 기록한다. 이 절차가 필요한 경우가 생기기 전까지는 도구를 추가하지 않는다.

## 재현

저장소만으로는 재현되지 않는다. 같은 실험을 다시 구현하려면 [EXP-064](EXP-064-location-fault-convergence.md)의 location 모델과 위 파라미터를 사용한다.
