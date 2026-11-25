# EXP-085: 장애 주입을 조건별 5회 이상 반복하고 하네스를 저장소에 올리면 예약 불변식은 계속 지켜지는가

- 질문: [EXP-070](EXP-070-real-stack-fault-injection.md)의 감사 불변식(응답받은 예약 유실 0, order 대 location 수 일치, 중복 예약 없음)이 PostgreSQL 서버 재시작·크래시 복구, application CPU·메모리 압박에서도 유지되는가? 이미 한 장애(location 서버 `kill`)를 다시 돌리면 결과가 얼마나 흔들리는가?
- 상태: 탐색 실험. 이 문서의 **가설은 실행 전에 적었다**(작성 시각은 git 이력으로 확인할 수 있다). 결과 절은 실행 뒤에 채운다.
- 날짜: 2026-11-25
- 관련: [EXP-068](EXP-068-stage-evidence-map.md)(8단계 표 A1·A2), [EXP-070](EXP-070-real-stack-fault-injection.md), [EXP-071](EXP-071-production-like-fault-injection.md), [EXP-066](EXP-066-db-outage-convergence.md), [EXP-072](EXP-072-kafka-outage-relay.md), [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md), D-144

## 왜 했는가

[EXP-068](EXP-068-stage-evidence-map.md)은 8단계 장애 주입 표에서 두 가지를 짚었다. (A1) "PostgreSQL 서버 재시작(WAL 재생)"과 "CPU·메모리 스트레스"는 실행하지 않았다. (A2) EXP-066·070·071·072의 장애 주입 결과는 조건별 n=1이고, 실행 도구는 저장소에 올리지 않아 저장소만으로는 재현할 수 없다. n=1로는 "이 조건에서 한 번 괜찮았다"만 말할 수 있고, 실행 사이에 얼마나 흔들리는지(같은 조건의 클라이언트 응답 분포)는 알 수 없다.

## 가설 (실행 전에 적음)

판정 기준: 아래 불변식 중 한 번이라도 어긋나면 그 가설은 틀린 것이고, 증거를 보존해 원인을 찾는다.

- H1 (핵심): 7개 조건(대조군 포함) 모두에서 모든 실행이 (a) 응답받은(200·201·202) 예약 유실 0, (b) order 대 location 수 일치, (c) 중복 예약 0, (d) 90초 안에 모든 workflow 종결을 만족한다.
- H2 (`docker restart`): PostgreSQL 컨테이너 이미지는 `SIGINT`(fast shutdown)로 멈추므로 종료 때 체크포인트가 기록돼 시작 로그에 `redo starts at`이 **없다**. 즉 "재시작"은 WAL 재생을 일으키지 않을 것이다. 이 경우 WAL 재생 근거는 아래 `kill`·`crash` 조건이 맡는다.
- H3 (컨테이너 `kill`, postmaster SIGKILL): 다시 시작할 때 5회 모두 `not properly shut down`과 `redo starts at`·`redo done at`이 로그에 남는다(실제 WAL 재생이 일어난다). 클라이언트가 `201`을 받은 예약은 5회 모두 재시작 뒤에도 남는다. 클라이언트 응답은 EXP-070의 n=1 값(`201` 280, `500` 4, `503` 276)과 같은 형태이고 `503`은 수백 건이다.
- H4 (`crash`, WAL writer SIGKILL): postmaster가 컨테이너를 내리지 않고 모든 backend를 다시 띄운다. 로그에 `redo starts at`이 남고, 장애 구간 응답 오류는 `kill`보다 적다(EXP-071 n=1에서 `500` 11, `503` 9).
- H5 (application CPU 압박): 불변식은 유지된다. 요청 일부가 시간 초과(상태 0)나 `5xx`로 끝날 수 있지만 DB에 닿은 요청은 정리된다. 처리 지연이 늘 것이다.
- H6 (application 메모리를 한도 64MiB 아래까지 채움): 대부분 OOM 없이 지나가고(`restart_count` 0), 불변식은 유지된다. JVM 증가분 때문에 일부 실행은 OOM으로 끝날 수 있어 예측이 갈린다.
- H7 (application 메모리를 한도 128MiB 넘게 채움): 커널이 JVM을 OOM kill하고 compose 재시작 정책이 다시 띄운다(5회 모두 `restart_count`≥1, `oom_killed` true). 진행 중 요청은 상태 0이나 `5xx`로 끝나지만 order DB에 커밋된 workflow는 복구 worker가 끝까지 처리해 불변식은 유지된다.
- H8 (location B 서버 `kill` 반복): 5회 모두 560건이 `RESERVED`로 수렴한다. 클라이언트 `201`/`202` 비율은 EXP-070 n=1(`201` 205, `202` 355)과 같은 방향이지만 실행마다 수십 건 흔들린다.
- H9 (대조군, 장애 없음): 5회 모두 `201` 560건이고 불변식이 모두 유지된다.
- H10 (복구 지연): order DB를 `kill`·`restart`하면 EXP-070이 찾은 DNS 부정 캐시(10초) 때문에 장애 종료 뒤 수렴까지 대체로 8초 이상 걸린다.

## 조건

(실행 뒤에 채움)
