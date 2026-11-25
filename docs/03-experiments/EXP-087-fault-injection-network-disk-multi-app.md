# EXP-087: 네트워크 분리·지연·손실, 디스크 가득 참, application 2대, 같은 key 재시도에서도 예약 불변식은 지켜지는가

- 질문: [EXP-085](EXP-085-fault-injection-repeated.md)가 "실행하지 않았다"고 적은 장애(네트워크 분리·지연·손실, 디스크 가득 참, application 여러 대, 장애 뒤 같은 idempotency key 재시도)에서도 네 불변식(응답받은 예약 유실 0, order 대 location 수 일치, 중복 예약 0, 90초 안 수렴)이 유지되는가? 그리고 EXP-085가 설명하지 못한 두 가지, (1) 일부 실행의 약 20초 수렴 꼬리, (2) EXP-070의 10.0초 복구 지연이 EXP-085에서 재현되지 않은 이유는 무엇인가?
- 상태: 진행 중. **이 문서의 "가설" 절은 매트릭스를 돌리기 전에 커밋했다**(그 커밋이 git 이력에 남아 있다). 결과 절은 실행 뒤에 추가한다.
- 날짜: 2026-11-25
- 관련: [EXP-085](EXP-085-fault-injection-repeated.md), [EXP-068](EXP-068-stage-evidence-map.md)(8단계 표), [EXP-070](EXP-070-real-stack-fault-injection.md), [EXP-071](EXP-071-production-like-fault-injection.md), [EXP-078](EXP-078-relay-lease-length.md), D-146

## 왜 했는가

EXP-085는 "증명하지 못한 것"에 이 장애들을 남겼다. 그리고 두 가지를 측정 없이 설명으로만 남겼다. 하나는 일부 실행의 약 20초 수렴 꼬리를 "복구 lease 30초가 풀린 뒤에야 worker가 잡기 때문"이라고 본 것(lease 만료 시각을 재지 않았다). 다른 하나는 EXP-070의 DNS 부정 캐시 10초 지연이 하네스에서 0.3~0.5초로 나온 것(이유를 확인하지 못했다). 이번에는 같은 하네스(`load-tests/fault-injection/`)를 확장해 이 둘을 가리고 빠진 장애를 같은 모델(요청 560건, 초당 40건, 부하 시작 3초 뒤 주입, 조건별 n=5)로 돌린다.

## 읽고 확인한 코드 사실 (설계 근거, 실행 결과 아님)

- 복구 worker(`LocationReservationWorker`)는 `poll-interval` PT1S(고정 지연)로 `recoverDue`를 부른다. `claimNextDue`는 `next_attempt_at <= now` **그리고** `(lease_token IS NULL OR lease_until <= now)`인 행만 잡는다(`LocationReservationRepository`). 요청 처리(`submit`)는 workflow를 저장하는 같은 트랜잭션 안에서 `lease_until = now + lease-duration`(기본 PT30S)으로 lease를 잡는다. 따라서 요청 스레드가 lease를 놓기 전에 응답하지 못하게 되면(DB가 죽었거나 JVM이 굶거나 프로세스가 죽었을 때) 그 행은 `lease_until`이 지나야 다른 worker가 집을 수 있다. lease 설정 키는 `atlas.order-inventory-reservation.recovery.lease-duration`이며 환경 변수 `ATLAS_ORDER_INVENTORY_RESERVATION_RECOVERY_LEASE_DURATION`으로 바꿀 수 있다.
- 같은 `Idempotency-Key`로 다시 요청하면 `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` 뒤 기존 행을 읽어 같은 workflow를 돌려준다(`LocationReservationRepository.save`). 같은 key에 다른 주문·allocation이면 `409`. 응답은 이미 `RESERVED`면 `200`(replayed), 새로 만들어 끝났으면 `201`, 진행 중이면 `202`. 다른 스레드가 lease를 쥐고 있으면 재요청은 처리하지 않고 현재 상태만 `202`로 돌려준다.

## 가설 (실행 전에 적음)

판정 기준: 아래 불변식 중 하나라도 어긋나면 그 가설은 틀린 것이고, 증거를 보존해 원인을 먼저 찾는다. 수치 예측은 EXP-085의 location B kill·`app-oom`·`app-cpu-throttle` 결과와 위 코드 읽기에서 계산한 것이다.

- H1 (핵심): 아래 모든 신규 조건의 모든 실행이 네 불변식을 만족한다. 하나라도 깨지면 H1은 틀린다.
- H2 (location B 네트워크 분리 6초, 블랙홀): 분리 중 location B로 가는 호출은 연결은 되지만 응답이 없어 read-timeout(2초)에서 실패하고 workflow는 `RETRY_PENDING`이 된다. 예측: `202` 200~320건(조건별 5회 모두), `5xx`와 상태 0은 0건, 560건 모두 `RESERVED`, 장애 종료 뒤 15초 안 수렴(lease 꼬리 없음, 요청 스레드가 실패를 직접 받아 lease를 놓으므로). `location-b-kill`(202가 421~425건)보다 `202`가 적다. 분리가 정확히 6초에 끝나고 JVM 기동 시간이 없기 때문이다.
- H3 (분리 40초, lease 30초보다 김): 부하가 끝난 뒤에도 분리가 계속된다. 예측: 5회 모두 560건 `RESERVED`, 불변식 유지. 단 재시도 백오프(1초 시작, 최대 1분, 최대 8회)가 분리 종료 뒤 최대 60초까지 수렴을 늦출 수 있어 장애 종료 뒤 수렴이 10초 이상인 실행이 1회 이상 있을 것이다. 이 가설이 틀리는 경우는 `FAILED`·`NEEDS_REVIEW`로 끝난 workflow가 생기는 것이며, `202`로 응답한 요청이 그렇게 끝나면 "202는 최종 성공의 약속이 아니다"라는 발견이다.
- H4 (application↔location B 구간 지연: 각 방향 300ms±50ms, 6초): 새 연결의 SYN이 connect-timeout(250ms)을 넘기므로 새 연결이 실패하고 유지 중인 연결만 느려진다. 예측: 불변식 유지, 대조군(560건 `201`)보다 `201`이 적고(5회 모두), `5xx`는 0건, 최대 응답 지연이 0.6초를 넘는다.
- H5 (같은 구간 패킷 손실 30% + 지연 20ms, 6초): TCP 재전송이 read-timeout(2초)·connect-timeout 안에 끝나지 않는 요청이 생겨 `202`가 늘지만 유실은 없다. 예측: 불변식 유지, `202`가 5회 중 4회 이상 20건 이상.
- H6 (order DB 데이터 볼륨 가득 참, 128MiB tmpfs에 남은 공간을 모두 채워 6초 유지한 뒤 해제): PostgreSQL 로그에 `No space left on device`가 5회 모두 남는다. 쓰기 요청은 장애 구간에 `5xx`로 끝난다(`201`은 커밋된 것만). 공간을 풀면 서버가 컨테이너 재시작 없이 계속 동작하거나 크래시 복구를 거쳐 돌아온다. 예측: 불변식 유지, PANIC 여부는 예측하지 않고 기록한다. 컨테이너가 종료되면(tmpfs는 컨테이너가 멈추면 사라진다) 그것은 하네스 한계가 아니라 발견으로 기록한다.
- H7 (application 2대, 장애 없음): 560건 모두 `201`, 두 인스턴스가 각각 280±14건 처리, 불변식 유지, 중복 예약 0(복구 worker 2개가 동시에 돌아도).
- H8 (application 2대 + order DB `kill`): 1대 결과(`201` 287~291, `500` 4~5, `503` 264~269)와 같은 형태이고 불변식 유지.
- H9 (application `kill` 6초 뒤 시작, 1대와 2대): 1대는 재시작이 끝날 때까지 약 10초 이상 연결 거절(상태 0)이라 `app-oom`(상태 0이 394~412건)과 같은 형태다. 2대는 한 인스턴스로 가는 절반만 영향을 받아 상태 0이 1대의 절반 안팎(150~250건)이다. 죽은 인스턴스가 lease를 쥔 채 남긴 workflow는 살아 있는 인스턴스의 worker가 lease 만료 뒤 끝까지 처리한다. 불변식 유지.
- H10 (장애 뒤 같은 key 재시도, `order-db-kill`·`app-oom`·`app-cpu-throttle` 3종): 장애로 `200/201/202`를 받지 못한 요청을 같은 key로 1초 간격으로 최대 60초 재시도하면 5회 모두 전부 `200` 또는 `201`을 받는다. 그리고 수렴 뒤 560개 key를 한 번씩 다시 보내면 전부 `200`(replayed)이고 `201`이 없다. 예약·workflow 수는 재시도 전후로 같고 중복 0. 특히 `app-cpu-throttle`처럼 응답은 실패인데 DB에는 `RESERVED`인 요청(EXP-085: 20~38건)은 재시도에서 `201`이 아니라 `200`을 받는다.
- H11 (이상 징후 1: 20초 꼬리의 원인은 lease다): `app-cpu-throttle`의 수렴 꼬리는 lease 길이를 줄이면 따라서 줄어든다. 예측(부하 종료 뒤 수렴 시간, 중앙값 기준): lease PT30S 약 25초(EXP-085는 24.8~25.5초), PT15S 약 10초, PT10S 약 5초. 부하 종료 직후 비종결 workflow의 DB 값은 `next_attempt_at <= now`(이미 시각은 됐다)이고 `lease_until - now`가 꼬리 길이와 ±1.5초 안에서 일치한다. lease를 PT10S로 줄여도 꼬리가 15초 이상이면 H11은 틀리고 원인은 다른 곳(백오프, 연결 풀, DNS 부정 캐시)에 있다. `order-db-restart`(EXP-085에서 3/5가 꼬리)도 같은 방식으로 lease PT30S 대 PT10S를 비교한다.
- H12 (이상 징후 2: 10.0초 지연의 조건은 JVM 이름 조회 캐시 수명이다): JVM은 성공한 이름 조회를 30초(`networkaddress.cache.ttl` 미설정 기본값) 기억한다. 하네스는 스택 기동 직후 약 10~20초 안에 장애를 주입해 `order-postgres` 조회 결과가 아직 캐시에 있으므로 DB가 내려가 있어도 조회를 다시 하지 않고(기억한 IP로 연결 시도만 거절됨) 복구가 즉시 된다. 반면 앱이 한동안 새 연결을 만들지 않다가(긍정 캐시 만료) 장애를 만나면 재연결 때 이름 조회가 실패(컨테이너 종료 중에는 Docker DNS가 이름을 못 푼다)하고, 이 실패가 10초 동안 부정 캐시된다. 예측(`order-db-kill`, 호스트 이름 접속, 부하 시작 전 유휴 시간 `IDLE_BEFORE_LOAD`로 조작, DB가 다시 열린 뒤 첫 `201`까지, 조건별 n=3): 유휴 0초는 1.5초 이하(3/3), 유휴 40초는 8~11초(3회 중 2회 이상), 유휴 40초에서 DB를 IP로 접속하면 1.5초 이하(3/3), 유휴 40초에서 `networkaddress.cache.negative.ttl=0`이면 1.5초 이하(3/3). `docker restart`와 컨테이너 재생성(IP가 바뀔 수 있음)은 예측 없음, 결과만 기록한다. 유휴 40초에서도 지연이 없으면 H12는 틀리고 이번 조작으로 원인을 찾지 못한 것이다.

## 조건과 방법 선택

공통: [EXP-085](EXP-085-fault-injection-repeated.md)의 스택·부하·감사 그대로(application 2 CPU·1GiB, location 2개, PostgreSQL 18.6 3개, 요청 560건, 초당 40건 14초, 클라이언트 재시도 없음, 장애는 부하 시작 3초 뒤, 지속 6초). 조건별 n=5(DNS 칸은 n=3 이상, 핵심 칸 n=5). 호스트가 공유 Docker VM이므로 시간 값은 맥락이고 결론은 개수와 불변식에 둔다.

| 조건 | 주입 방법 | 선택 이유와 대안 |
| --- | --- | --- |
| `proxy-none` | Toxiproxy를 application과 location B 사이에 두고 장애 없음 | 분리 조건과의 차이가 프록시 때문이 아님을 보이는 대조군 |
| `location-b-partition` | Toxiproxy `timeout` toxic(timeout=0, 양방향)으로 application↔location B 데이터를 6초 막음(연결은 열리지만 응답이 오지 않음) | `docker network disconnect`는 location B와 그 PostgreSQL의 연결까지 끊고 재연결 때 IP가 바뀔 수 있어 DNS 효과와 섞인다. Toxiproxy는 application↔location B 한 쌍만 끊고 IP가 그대로다. 저장소에 이미 Toxiproxy 구성(`load-tests/toxiproxy/`)이 있다. 블랙홀은 SYN 무응답(연결 시간 초과)과 다르다는 한계가 있다 |
| `location-b-partition-long` | 같은 방법, 40초(`FAULT_FOR=40`, 수렴 대기 150초) | lease 30초보다 긴 중단(EXP-085 한계) |
| `net-delay` | application과 location B 컨테이너의 네트워크 네임스페이스에 `tc netem` 사이드카(NET_ADMIN, 실행마다 시작하고 `--rm`으로 제거)로 상대 IP로 가는 패킷에만 지연 300ms 지터 50ms를 6초 | Pumba도 같은 `tc netem`을 쓰며 Docker 소켓 마운트가 필요하다. 소켓 없이 두 이웃 컨테이너만 건드리도록 직접 사이드카를 썼다. prio qdisc와 u32 필터로 location B 쪽 패킷만 지연시켜 order DB·location A 경로는 건드리지 않는다 |
| `net-loss` | 같은 방법, 손실 30% + 지연 20ms | 위와 같음 |
| `order-db-disk-full` | order DB 데이터 볼륨을 128MiB tmpfs 볼륨으로 바꾸고 `dd`로 남은 공간을 모두 채워 6초 유지한 뒤 채움 파일 삭제 | 호스트 디스크에 닿지 않는 상한 있는 볼륨이 필요하다. Docker 볼륨 쿼터는 이 VM의 파일시스템이 지원하지 않는다(확인하지 않음, 가정). tmpfs는 컨테이너가 멈추면 사라지는 한계가 있다 |
| `app-kill` | 첫 번째 application 컨테이너 `docker kill`, 6초 뒤 `docker start` | `app-oom`(EXP-085)과 같은 효과를 메모리 압박 없이 직접 내는 조건 |
| `none+2apps`, `order-db-kill+2apps`, `app-kill+2apps` | application 컨테이너를 같은 order DB 앞에 하나 더 두고(`app2`, 첫 번째가 healthy가 된 뒤 시작해 Flyway 경합을 피함) 부하 생성기가 두 주소를 요청 번호로 번갈아 부른다 | 로드 밸런서 없이 클라이언트 쪽 라운드 로빈이다. nginx 경유 EXP-071과 달리 업스트림 제외 같은 LB 효과를 섞지 않는다. 두 인스턴스 모두 복구 worker가 켜져 있다 |
| `order-db-kill+retry`, `app-oom+retry`, `app-cpu-throttle+retry` | 부하 종료 뒤 `200/201/202`를 받지 못한 요청을 같은 key로 1초 간격 최대 60초 재시도하고, 이어서 560개 key 전부를 한 번 더 보냄 | 감사는 재시도 후의 `200/201/202`까지 응답받은 것으로 센다. 수렴 시간은 재시도가 끝난 시각부터 잰다 |
| `app-cpu-throttle@lease{30,15,10}s`, `order-db-restart@lease{30,10}s` | 복구 lease를 `RECOVERY_LEASE`로 바꾼다(환경 변수) | 이상 징후 1 |
| `dns:*` 칸 | `order-db-kill`(즉시 시작, `FAULT_FOR=0`)에 `IDLE_BEFORE_LOAD`(0 또는 40초), DB 접속 대상(호스트 이름 또는 컨테이너 IP), JVM `networkaddress.cache.negative.ttl`(기본 10 또는 0), 컨테이너 조작(`kill`+`start`, `docker restart`, 컨테이너 재생성)을 바꿈 | 이상 징후 2 |

감사: 위 네 불변식에 더해 (1) 부하 종료 직후 비종결 workflow의 `lease_until`·`next_attempt_at`·`attempt_count`(DB 시계 기준 남은 시간)와 실제 수렴 시각의 비교, (2) DB가 다시 열린 뒤 첫 `201`까지의 시간, (3) 재시도 실행의 재시도 결과와 전체 재전송 결과를 기록한다.

## 결과

(실행 뒤에 작성)
