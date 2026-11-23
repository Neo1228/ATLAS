# EXP-061: 독립 consumer N개와 재생을 PostgreSQL 이벤트 로그가 감당하는가

- 질문: 예약 상태 변경을 settlement ledger, availability projection, audit이 각자 offset으로 100% 받고 버그 수정 뒤 과거부터 다시 읽어야 한다면, PostgreSQL의 append-only 로그와 consumer group별 cursor로 충분한가? 충분하지 않은 지점은 어디인가?
- 상태: 탐색 실험(구성당 1회, 단일 호스트, 합성 이벤트). 제품 요구 수치(소비자 3개, 초당 500건, 7일 재생)는 **제안값**이고 실측한 운영 비율이 아니다.
- 관련: [ADR-0067](../02-decisions/adr/ADR-0067-kafka-not-adopted.md), [ADR-0053](../02-decisions/adr/ADR-0053-shared-infra-adoption-gates.md)(브로커 도입 조건)

## 정정(2026-11-23): 이 실험은 lag만 쟀고 무손실을 검증하지 않았다

아래 결과의 offset은 `event_seq`(identity)다. PostgreSQL의 시퀀스 번호는 INSERT 시점에 할당되고 행은 COMMIT 시점에 보인다. 번호가 작은 트랜잭션이 늦게 커밋되면 `event_seq > cursor`로 읽는 consumer가 그 행을 영영 건너뛴다([Dudycz](https://event-driven.io/en/ordering_in_postgres_outbox/), [Sequin](https://blog.sequinstream.com/postgres-sequences-can-commit-out-of-order)). lag은 `max(event_seq) − min(cursor)`라 건너뛴 행을 잡지 못한다. writer도 pgbench 단문 autocommit이라 재정렬 창이 가장 좁은 최선 조건이었다.

재현(`load-tests/event-log-gap-check.sh`, 쓰기 8개가 번호를 받은 뒤 0~150ms 임의 지연 후 커밋, 쓰기 종료 뒤 consumer를 모두 비운 최종 상태 기준):

| consumer 방식 | 쓴 이벤트 | 놓친 이벤트 |
| --- | ---: | ---: |
| 현행(`event_seq > cursor`) | 3,200 | 1,013 |
| xmin 안전(`tx_id < pg_snapshot_xmin(pg_current_snapshot())`) | 3,200 | 0 |

따라서 아래 표의 lag·재생 수치는 **처리량 상한의 참고값**일 뿐 "PostgreSQL이 offset·fan-out·재생을 충족한다"는 근거가 아니다. 1회 실행·합성 지연이라 놓친 비율 자체(약 32%)는 일반화하지 않는다. 의미 있는 것은 현행 방식에서 손실이 재현됐고 xmin 방식에서는 재현되지 않았다는 점이다.

## 환경과 방법

- `load-tests/run-event-log-fanout.sh`: throwaway PostgreSQL 18.6 컨테이너(2 CPU/2 GiB). 이벤트 로그는 `event_seq`(identity, 단조 증가 offset)·`workflow_id`·`aggregate_version`(유일)·`event_type`·`occurred_at`, consumer cursor는 group별 `last_event_seq`다.
- writer는 pgbench로 연결 4개에서 초당 고정 속도로 1건씩 insert하고, consumer는 연결당 1 group으로 `poll`(cursor 이후 batch만큼 읽고 cursor 전진)을 sleep과 반복한다. lag = 최신 `event_seq` − 가장 느린 cursor, 1초마다 샘플링했다.
- 끝난 뒤 새 group이 처음부터 끝까지 읽는 시간으로 재생 속도를 쟀다(행 수만 센다. payload 전송·역직렬화·네트워크는 포함하지 않는다).
- 이벤트는 합성이며 앱의 workflow trigger를 통하지 않는다. 앱 경로에 이벤트를 적재하는 오버헤드는 측정하지 않았다.

## 결과

| 쓰기/s | consumer | batch/poll | lag p50 / p99 / 최대(건) | 종료 시 lag |
| ---: | ---: | --- | --- | ---: |
| 500 | 3 | 500 / 200 ms | 35 / 98 / 98 | 5 |
| 2,000 | 3 | 500 / 200 ms | 160 / 384 / 384 | 20 |
| 5,000 | 3 | 500 / 200 ms | 43,887 / 75,276 / 75,276 | 75,276 |
| 5,000 | 12 | 500 / 200 ms | 44,280 / 76,284 / 76,284 | 76,284 |
| 10,000 | 12 | 5,000 / 100 ms | 579 / 1,229 / 1,229 | 951 |

- 5,000/s에서 lag이 계속 늘어난 것은 PostgreSQL 한계가 아니다. batch 500을 200ms마다 읽는 consumer의 상한이 연결당 약 2,500건/s라서 생긴 harness 한계이며, consumer를 3개에서 12개로 늘려도 lag이 같다는 점이 이를 뒷받침한다. batch 5,000·poll 100ms로 바꾸자 10,000/s에서도 lag이 1,229건(약 0.12초치)에 머물렀다.
- 쓰기는 4개 연결·2 CPU에서 10,000건/s(30초에 약 300,000건)까지 목표 속도를 달성했다.
- 재생: 약 15만~30만 건을 새 group이 읽는 데 0.1초 안팎이었다(행 수 기준 약 80만~330만 건/s). payload 전송을 뺀 값이라 상한 추정이다.
- 저장: 이벤트당 약 171바이트(인덱스 포함). 제안한 7일×500건/s = 약 3억 건은 약 52 GB로 **추정**된다(선형 외삽, 미측정). 파티션 없는 단일 테이블에서의 vacuum·삭제·백업 비용은 측정하지 않았다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 단문 autocommit 쓰기에서 10,000건/s·12개 consumer까지 읽기 쪽 처리량(행 수 기준)은 PostgreSQL이 감당했다. **무손실은 증명하지 못했고**, 위 정정대로 현행 cursor는 손실이 재현된다.
- 증명하지 못한 것: 앱 경로에 이벤트를 적재하는 trigger/outbox 오버헤드, consumer가 다른 서비스·DB에 있을 때의 전달, 실제 payload 크기, 7일 보관의 vacuum/파티션 운영, 다중 노드와 장애 시 consumer 재시작. 이 중 어느 하나가 요구가 되면 이 결론은 다시 봐야 한다.
