# EXP-117: 분산 배포 전제에서 남은 업그레이드 후보(F1·F2·F3·G1·C4·E3)를 재현으로 판정한다

- 상태: 측정 전 사전 등록(이 절까지). 결과는 아래 "결과" 절에 측정 뒤 덧붙인다.
- 날짜: 2026-11-27
- 관련: [#729](https://github.com/Neo1228/ATLAS/issues/729), [ADR-0038](../02-decisions/adr/ADR-0038-durable-payment-recovery-workflow.md), [ADR-0047](../02-decisions/adr/ADR-0047-abandoned-approval-deadline.md), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [ADR-0072](../02-decisions/adr/ADR-0072-infrastructure-inventory-and-assumptions.md), [ADR-0077](../02-decisions/adr/ADR-0077-keep-reorder-idempotency-records.md), [EXP-064](EXP-064-location-fault-convergence.md), [EXP-083](EXP-083-reorder-idempotency-retention.md), [EXP-086](EXP-086-admission-k-ttl-and-authorize-window.md)

## 배포 전제

단일 서버가 아니라 다음을 전제로 판단한다. 후보마다 결과가 어느 전제에 기대는지 따로 적는다.

| 기호 | 전제 |
|---|---|
| P-N | load balancer 뒤 app 인스턴스 여러 개가 같은 PostgreSQL을 쓴다 |
| P-K | rolling 배포와 프로세스 강제 종료(`kill -9`, OOM)가 처리 중 작업을 버린다 |
| P-F | DB primary failover와 replica가 있다(스트리밍 복제) |
| P-L | app, location DB, PayPal 사이에 지연과 분할이 생긴다 |

## 후보와 문제 한 문장 (코드 읽기, 측정 아님)

| 후보 | 문제 한 문장 | 기대는 전제 |
|---|---|---|
| F1 | 결제 복구 기본값(`max-attempts 8`, `base-backoff PT1S`, `max-backoff PT15M`, `max-age PT24H`)에서 backoff가 시도마다 두 배라 8회 소진까지 약 1~2분이고, ADR-0038이 적은 "15분 상한·24시간 창"에 닿기 전에 PayPal 장애·분할이 2분만 넘어도 미확정 결제가 모두 `NEEDS_REVIEW`(수동)로 간다 | P-L, P-K |
| F2 | 비동기 복제 failover로 결제 시도 커밋이 사라지면, 그 커밋 뒤에 나간 PayPal 호출의 결과는 ATLAS DB에 흔적이 없고 PayPal 쪽 기록과 대조해야만 보인다 | P-F |
| F3 | reorder 멱등 기록을 영구 보존하는 ADR-0077 결정은 100만 행 이하에서만 측정됐다 | 없음(공유 DB 키라 인스턴스 수와 무관) |
| G1 | lease가 만료된 coordinator의 location 쓰기가 늦게 도착하면 새 coordinator의 결정과 어긋날 수 있다(fencing token 부재) | P-N, P-L, P-K |
| C4 | 비동기 복제에서 primary가 확인한 커밋이 failover 뒤 사라질 수 있다 | P-F |
| E3 | event relay가 `pg_snapshot_xmin` horizon 아래만 읽으므로 xid를 쥔 긴 트랜잭션이 그 시간만큼 이벤트 전달을 멈춘다. CDC(논리 복제)는 커밋 순서로 읽어 이 정지가 없다 | P-N(다른 인스턴스의 트랜잭션도 horizon을 잡음) |

## 가설과 반증 조건 (측정 전에 적음)

| 번호 | 가설 | 반증 조건 | 방법 |
|---|---|---|---|
| H1 | 기본값에서 provider 조회가 매번 실패하면 `PAYMENT_ATTEMPT` job은 8번째 claim 뒤 `NEEDS_REVIEW`(`MAX_ATTEMPTS`)가 되고, 그 8회 동안 DB에 기록된 재예약 지연(`available_at - updated_at`) 합은 300초 미만이다 | 8번째 claim 뒤 `PENDING`이거나 지연 합 ≥ 300초 | 실제 worker + PayPal stub 503 + PostgreSQL 통합 시험. 시간은 `available_at`을 당겨 건너뛰고 기록된 지연 값을 합한다 |
| H2 | 처리 중 강제 종료로 lease가 만료된 claim도 `attempt_count`를 쓴다. 같은 job을 잡고 8번 죽으면 `expireExhausted`가 `NEEDS_REVIEW`로 만든다. 그래서 `max-attempts`만 키우는 수정은 독이 든 job(poison job)이 인스턴스를 죽이는 횟수도 같이 키운다 | 현재 코드에서 버려진 claim 8회 뒤 `NEEDS_REVIEW`가 아님 | 같은 시험에서 claim만 하고 결과를 쓰지 않은 채 lease를 만료시킨다 |
| H3 | 버려진 claim을 따로 세는 열(V12)과, 결과를 기록한 재시도에는 창(`max-age`)이 먼저 묶이도록 올린 `max-attempts` 기본값으로 (a) H1 시나리오의 job은 창 전에는 `NEEDS_REVIEW`가 되지 않고 provider 회복 뒤 다음 claim에서 `COMPLETED`가 되며 (b) H2 시나리오는 여전히 버려진 claim 8회 뒤 `NEEDS_REVIEW`가 된다 | (a)나 (b) 중 하나라도 어긋남 | 같은 통합 시험, 수정 전 코드에서 실패·수정 뒤 통과, 변이 확인 |
| H4 | 비동기 스트리밍 복제(`synchronous_standby_names` 비어 있음)에서 primary가 확인한 커밋 중 일부가 primary 강제 종료 → standby 승격 뒤 사라진다. standby가 끊긴 동안 확인된 커밋 N건은 N건 모두 사라진다 | 끊긴 경우 손실 < N, 또는 부하 중 종료 5회 모두 손실 0이고 끊긴 경우도 손실 0 | PostgreSQL 18 컨테이너 2개(primary, standby), 확인된 커밋 번호를 클라이언트가 기록하고 승격 뒤 대조 |
| H5 | `synchronous_standby_names = '*'`(`synchronous_commit = on`)에서 같은 시나리오 손실은 0이고, 대신 standby가 끊기면 커밋 확인이 멈춘다(10초 안 확인 0건). 커밋 지연 비율(sync/async)은 같은 세션 3회 이상으로 잰다 | sync에서 손실 ≥ 1, 또는 standby 단절 중 커밋이 확인됨 | 같은 컨테이너 구성, 측정 잠금 아래 `pgbench` |
| H6 | 결제 시도와 복구 job은 PayPal 호출 전에 커밋된다(ADR-0038). 그래서 H4의 손실 구간에 든 시도는 PayPal에는 있고 ATLAS에는 없다. 기존 잔액 보고서 대사는 그 거래 행을 `UNMATCHED`로 분류한다 | 코드가 PayPal 호출 뒤에 커밋함, 또는 대사가 ATLAS에 없는 거래를 `UNMATCHED`로 분류하지 않음 | 코드 읽기 + 기존 대사 시험 재실행 |
| H7 | lease 만료 뒤 늦게 도착하는 location 쓰기(reserve·claim·cancel·release·restore)는 현재 상태 기계와 취소 tombstone이 거절하거나 같은 결과로 수렴한다. coordinator 둘(같은 DB, 각자 lease)과 lease보다 긴 location 지연에서 주문·location 불일치 0건 | 불일치 ≥ 1건 | PostgreSQL 통합 시험(늦은 도착을 붙잡았다 푸는 방식) |
| H8 | xid를 쥔 트랜잭션이 T초 열려 있으면 그 사이 커밋된 이벤트는 relay 읽기에서 약 T초(±1초) 늦게 보인다. app 세션은 `statement_timeout 30초`·`idle_in_transaction_session_timeout 60초`로 묶인다 | 지연이 T와 무관 | PostgreSQL 컨테이너, relay 읽기 질의를 그대로 실행 |
| H9 | reorder 멱등 기록 1,000만 행에서 행당 크기는 100만 행 측정값(typical 약 310 B, EXP-083)의 ±15% 안이고, 키 조회 버퍼는 5 이하다 | 벗어남 | PostgreSQL 컨테이너, 합성 데이터, `EXPLAIN (ANALYZE, BUFFERS)` |

## 판정 규칙 (측정 전에 적음)

- 채택: 가설이 문제를 재현하고(H1·H2·H4·H7 중 해당), 수정의 효과를 같은 방법으로 측정했을 때(H3·H5)만 코드·시험·변이·되돌리기·운영 가이드와 함께 채택한다.
- 기각: 재현되지 않거나 수정 효과를 우리 쪽에서 잴 수 없으면 증거와 수치 재검토 조건을 적고 기각한다.
- C4는 가용성(쓰기 정지)과 RPO의 교환이라 재현·측정되더라도 배포 구성 결정은 사용자에게 근거와 선택지로 묻는다. app 코드가 그 결정에 기대는 부분만 이 실험에서 정한다.
- 시간 값(H5 지연 비율)은 측정 잠금 아래에서만 재고, 결론은 건수와 불변식으로 낸다.
