# EXP-114: 판매자 정산서 조회 투영, 공개 이벤트 계약, EDA 전환 신호를 측정으로 정한다

- 상태: 사전 등록(측정 전). 가설·반증 조건·판정 규칙을 첫 시간 측정 전에 커밋한다.
- 날짜: 2026-11-27
- 관련: [#719](https://github.com/Neo1228/ATLAS/issues/719), [EXP-045](EXP-045-settlement-balance-index.md), [EXP-080](EXP-080-seller-statement-index.md), [EXP-111](EXP-111-ledger-integrity-durability.md), [EXP-061](EXP-061-event-log-fanout-and-replay.md), [EXP-089](EXP-089-kafka-outage-repeated.md), [EXP-091](EXP-091-kafka-first-delivery-order.md), [EXP-100](EXP-100-kafka-first-delivery-fix-real-stack.md), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [ADR-0100](../02-decisions/adr/ADR-0100-settlement-ledger-guards.md)

## 문제 한 문장

판매자 정산서와 지급 잔액은 요청마다 판매자의 원장 전체 이력을 다시 합산하고(`SettlementJournalRepository.sellerBalance`), 같은 전체 이력 합산이 환불 분개 기록(`reserveBalance`)과 지급 예약(`payable`)에서 판매자 advisory 락을 쥔 채 돌지만, 그 비용이 이력 크기에 따라 얼마나 커지는지는 4,600행(EXP-045)에서만 쟀다. 그래서 조회 투영(EDA 검토 3.2)이 필요한지, 인덱스로 충분한지, 언제 전환해야 하는지 판단할 수치가 없다.

## 코드 읽기 결과 (측정 전, 기준 `origin/main` d2c8395f)

- 정산서 조회 `SettlementServiceImpl.getSellerStatement`는 `REPEATABLE READ` 읽기 전용 트랜잭션에서 질의 5개를 돈다: 기간 합계(`sellerStatementTotals`, 분개 3갈래 + 항목 합계 + 지급), 현재 잔액(`sellerBalance`, 판매자의 모든 항목), 행 한 페이지(`sellerStatementRows`), 지급 대기·확정 합계 2개(`settlement_payout`).
- 기간 질의는 `(seller_id, recorded_at, journal_id)` 범위 스캔이라 비용이 기간 안의 행 수에 비례한다(EXP-080). 기간에 상한이 없어(`from`·`to`만 검사) 전체 이력 기간을 주면 이력 전체를 읽는다.
- `sellerBalance`는 기간과 무관하게 판매자의 모든 `settlement_entry`를 `(seller_id, account_code, entry_side)` 인덱스로 찾아 힙에서 `amount_won`을 읽는다. EXP-045는 이 인덱스로 "Index Only Scan이 가능하다"고 적었지만 `amount_won`이 인덱스에 없어 힙 접근이 남는다(이 실험에서 계획으로 확인한다).
- 같은 전체 이력 합산이 쓰기 경로에도 있다. `recordRefund`는 `lockSellerPayouts`(판매자 advisory 락) 뒤 `insertRefundEntries` 안에서 `reserveBalance`(판매자의 모든 `SELLER_RESERVE` 항목)를 읽고, 지급 생성의 `payable`은 락 안에서 `sellerBalance`를 읽는다. 그래서 이력이 길수록 판매자 락을 쥐는 시간이 길어진다(EDA 검토 T6의 신호와 같은 지점).
- 원장은 V7로 추가 전용이고(UPDATE·DELETE·TRUNCATE 거절), 분개 균형은 지연 제약 트리거가 커밋 때 검사한다. 항목의 `recorded_at`은 모든 쓰기 경로에서 분개와 같은 값이다(capture·checkout·refund·payout 모두 같은 `Instant`를 넘긴다). `entry_id`는 DB의 `uuidv7()` 기본값이다.
- ATLAS 원장에는 계정 잔액 행이 없다(항목 삽입만 하고 잔액은 합산). 그래서 Modern Treasury가 겪은 "핫 계정 잔액 행 경합"(EDA 검토 T5)은 현재 구조에 없고, 같은 트랜잭션 투영(A2)을 넣으면 그 구조가 생긴다.
- 공개 이벤트: `KafkaReservationEventPublisher.Payload`(필드 8개)가 topic 계약이다. 주석은 "필드 이름을 바꾸면 소비자가 깨진다"고 쓰지만 이를 고정하는 시험이 없다. 참조 소비자(`ReservationEventReferenceConsumer`, 시험 전용)는 `workflowId`·`aggregateVersion`과 헤더 `event-id`만 읽는다. 운영 소비자 0개, relay 기본 비활성.
- 전환 신호: relay `lag_events`·`stalled_seconds`, 복구·웹훅·지급 큐의 가장 오래된 항목 나이 게이지는 이미 있다. 큐 표의 죽은 튜플·크기, 청구 질의 지연, 락 대기, 원장 삽입 지연, 핫 SKU 경합은 지표가 없다. advisory 락은 7곳(판매자 지급, 재고 SKU, 위치, 용량 조정, 구매자, 주문 환불, 결제 환불)이 같은 `pg_advisory_xact_lock(hashtextextended(?, 0))` 꼴이라 DB 쪽에서 종류를 가를 수 없다.

## 대안

| 안 | 내용 | 쓰기 경로 변화 | 조회 정확성 |
|---|---|---|---|
| A0 | 현행 유지 | 없음 | 정확 |
| A1 | 인덱스 조정: `settlement_entry (seller_id, account_code, entry_side) INCLUDE (amount_won)`로 잔액 질의를 Index Only Scan으로 | 항목마다 인덱스 항목이 조금 커짐 | 정확 |
| A2 | 같은 트랜잭션 투영: 항목 `AFTER INSERT` 트리거가 `(seller, account, 일)` 행을 UPSERT, 잔액은 투영 합 | 항목마다 UPSERT 1회, 같은 판매자·일 행 경합 | 정확(같은 트랜잭션) |
| A3 | 비동기 투영: 추가 전용 원장 자체를 이벤트 로그로 보고 worker가 안전 cursor(`entry_id`의 시각 ≤ 활성 트랜잭션의 가장 이른 시작) 아래 항목을 `(seller, account, 일)`로 접어 둔다. 잔액 = 투영 합 + cursor 이후 꼬리 합 | 꼬리 조회용 인덱스 1개 | 투영만 읽으면 최대 worker 주기만큼 오래됨, 투영 + 꼬리는 정확 |
| A4 | materialized view `(seller, account, 일)` + `REFRESH CONCURRENTLY` | 없음 | 새로 고침 주기만큼 오래됨 |

A3에 별도 outbox를 두지 않는 이유: 원장이 V7로 추가 전용이라 이미 변하지 않는 사실의 순서열이고, 같은 사실을 outbox에 한 번 더 쓰면 사실이 두 곳이 된다(SSOT). EXP-061·067의 이벤트 로그 cursor가 xmin 안전성을 다룬 방식과 같은 위험(늦게 커밋되는 행)을 cursor 규칙으로 다룬다.

read-after-write 제약: 지급 예약(`payable`)과 환불 분개(`reserveBalance`)는 돈을 정하는 읽기라 오래된 값을 쓸 수 없다(distributed-readiness 5절의 read-after-write 경로). 그래서 A3·A4의 "투영만" 읽기는 이 두 경로에 쓸 수 없고, 쓸 수 있는 것은 정확한 읽기(A0, A1, A2, A3 투영 + 꼬리)뿐이다.

## 측정 방법 (사전 등록)

공통: PostgreSQL 18.6 일회용 컨테이너(라벨 `atlasexp=eps`, `shared_buffers=2GB`, 시간 측정 중 `--cpus 4`), 마이그레이션 V1~V7 적용. 합성 원장: 목표 판매자 4명(분개 1천/1만/10만/100만 건) + 배경 판매자 5,000명(Zipf s = 1.1, 합 100만 건), 최근 730일 균등, 전체를 `recorded_at` 순서로 넣어 판매자 행이 페이지에 흩어지게 한다. 종류 비율 capture 75%, checkout 18%(판매자 2명 배분), refund 6%, payout 1%. 적재 후 `VACUUM (ANALYZE)`와 분개 균형 검사(0건이어야 생성기를 믿는다). 판매자당 1천~10만 건을 현실 범위, 100만 건을 상한 스트레스로 본다.

시간 값은 `measure-lock.sh`를 쥔 구간에서만 재고 run마다 load1과 다른 에이전트의 라벨 컨테이너를 적는다. load1 > 12이거나 다른 라벨 스택이 그 시각에 CPU를 쓰고 있으면 오염으로 표시하고 머리 수치에서 뺀다. 칸마다 n = 5 run. 읽기는 `pgbench -c 1 -T 5`, 쓰기는 `-T 10`, 지연은 트랜잭션별 로그의 p50·p95, 버퍼는 `EXPLAIN (ANALYZE, BUFFERS)` 1회(데운 캐시).

1. 조회 비용(A0, A1): N마다 `sellerBalance`, `reserveBalance`, 30일 기간 합계, 전체 이력 기간 합계, 행 첫 페이지, 정산서 1회 전체(REPEATABLE READ, 질의 5개).
2. 쓰기 비용: capture 분개(분개 1 + 항목 4, V7 트리거 켬) c = 1 배경 판매자 무작위, 같은 판매자 c = 8, 환불 분개(판매자 락 + `reserveBalance` + 분개 1 + 항목 3) N마다 c = 1. 암마다(A0, A1, A2, A3).
3. 읽기가 쓰기에 주는 영향(A0): capture c = 4(배경 판매자)를 정산서 독자 4개(N = 100만 판매자) 있음·없음으로.
4. 투영(A2, A3, A4): 잔액 조회 지연, 재구축 시간(빈 투영을 전체 원장에서 채우는 시간), A4 새로 고침 시간.
5. 정확성: 무작위 흐름(capture·checkout·refund·payout 섞어 1,000 분개, 판매자 20명)을 넣은 뒤 판매자·계정별 투영 잔액 = 원장 합산인지. 유실 주입: (a) 투영 행 1개 삭제, (b) A3에서 cursor 아래로 늦게 커밋되는 트랜잭션(활성 트랜잭션을 연 채 worker 실행 후 커밋), (c) 트리거를 끈 채 항목 1개 삽입(A2). 대사 질의가 (a)(c)를 지목하는지, (b)가 유실 없이 꼬리에 잡히는지.
6. 공개 이벤트 계약: `Payload`의 `eventType`을 다른 이름으로 바꾸는 변이를 넣고 기존 Kafka 관련 시험 3개 클래스가 통과하는지(문제 재현). 계약 시험을 더한 뒤 같은 변이가 실패하는지.
7. 전환 신호 지표: 구현하는 지표마다 PostgreSQL 통합 시험과 변이 확인, 표본 질의의 실행 시간(`EXPLAIN ANALYZE`).

## 가설과 반증 조건 (측정 실행 전 등록)

| ID | 가설 | 반증 조건(수치) |
|---|---|---|
| H1 | A0 `sellerBalance` p50은 판매자 이력에 비례한다: N = 100만에서 N = 1천의 50배 이상 | 100만/1천 p50 비가 10배 미만 |
| H2 | A0 환불 분개 트랜잭션 p50(락 안 `reserveBalance` 포함)이 N = 100만에서 N = 1천의 10배 이상 | 비가 2배 미만 |
| H3 | 정산서 독자 4개(N = 100만)가 capture c = 4 처리량을 15% 이하로만 줄인다(MVCC: 읽기는 쓰기를 막지 않음) | 30% 넘게 줄어듦 |
| H4 | A1은 N ≥ 10만에서 `sellerBalance` p50을 2배 이상 줄이고 capture c = 1 p50을 10% 이하로만 늘린다 | 개선 1.5배 미만 또는 쓰기 p50 15% 넘게 증가 |
| H5 | A2는 N = 100만 잔액 p50 ≤ 5 ms로 만들지만 같은 판매자 c = 8 capture 처리량을 A0보다 30% 이상 줄인다(핫 행) | 같은 판매자 처리량 감소 10% 미만(핫 행 예측 반증) |
| H6 | A3 투영 + 꼬리는 모든 무작위 흐름에서 A0 합산과 같고(불일치 0), N = 100만 잔액 p50 ≤ 10 ms(꼬리 1일 이내), capture 쓰기 p50 변화는 A1 대비 ±10% 이내, 늦은 커밋(b)은 유실되지 않는다 | 불일치 1건 이상 또는 (b) 유실 |
| H7 | 대사 질의는 주입한 투영 손상 (a)(c)를 모두 지목한다 | 하나라도 놓침 |
| H8 | A4 `REFRESH MATERIALIZED VIEW CONCURRENTLY`는 이 원장(항목 약 900만 행)에서 10초 이상 걸린다 | 2초 이하 |
| H9 | 지금 `Payload` 필드 이름(`workflowId`·`aggregateVersion` 외)을 바꿔도 기존 시험이 모두 통과한다 | 기존 시험 하나라도 실패 |

## 판정 규칙 (측정 전 고정)

- 기본 경로에 투영(A2·A3)을 넣는 조건: A0(또는 A1이 채택되면 A1)의 `sellerBalance` p95 또는 환불 분개 p95가 N = 10만에서 50 ms를 넘는다. 넘지 않으면 투영은 넣지 않고 A3를 전환안으로 기록하며 그 임계값을 운영 지표로 둔다.
- A2는 H5의 같은 판매자 처리량 감소가 30% 이상이면 기각한다(T5와 같은 핫 행을 새로 만드는 것).
- A4는 새로 고침이 10초 이상이거나 돈을 정하는 경로에 쓸 수 없으면(오래된 값) 기각한다.
- A1은 H4가 서면 V10으로 채택한다(인덱스 교체, 질의 결과 불변).
- 공개 이벤트: H9가 서면(문제 재현) 계약 시험을 더한다. Kafka는 켜지 않는다.
