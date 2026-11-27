# EXP-114: 판매자 정산서 조회 투영, 공개 이벤트 계약, EDA 전환 신호를 측정으로 정한다

- 상태: ✓ 완료(PostgreSQL 18.6, 단일 호스트, 합성 원장 항목 970만 행, 시간 칸마다 깨끗한 run 5회 또는 5쌍, 정확성·유실 주입·변이 확인 포함). 가설과 반증 조건·판정 규칙은 첫 시간 측정 전에 커밋했다(이 브랜치의 첫 커밋 `docs(experiments): EXP-114 가설과 반증 조건을 측정 전에 등록한다`, 작성 11:46, 첫 시간 측정 11:55). 결과: 조회 투영과 덮는 인덱스 모두 넣지 않음(A1은 H4 쓰기 비용 반증), 공개 계약 버전·계약 시험과 전환 신호 지표를 출시.
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

## 결과

### 조건과 실행 상태

- 데이터: 분개 2,111,042건, 항목 9,704,275행(2,056 MB), 배분 707,951행. 적재 후 생성기 검사: 불균형 분개 0, 항목 없는 분개 0. 목표 판매자 항목 수 3,920 / 39,200 / 392,000 / 3,920,000(이하 1k / 10k / 100k / 1m).
- 잠금 구간(`measure-lock.sh`, 태그 `eps`): hold1 11:55~12:16(A0), hold2 13:23~13:47(A1), hold3 13:47~14:22(A2·A3·A4), hold4 14:45~14:58(A0 대 A1 쌍 측정, 14:37~14:44의 첫 시도는 디스크 부족으로 무효), hold5 15:05~15:08(H3 원인 분리, 신호 질의 비용). 각 run의 load1과 다른 라벨 컨테이너는 run 줄마다 적었고 load1 > 12인 run은 `contaminated-load`로 빼고 집계했다.
- **공변량(사전 등록에 없던 것)**: hold2 동안 라벨 없는 다른 프로젝트의 빌드 컨테이너(`buildx_buildkit_*`)가 약 3.4코어를 썼다(`docker stats`, 13:34). load1 규칙(≤ 12)으로는 깨끗했지만 hold1(load1 3~7)과 같은 조건이 아니다. 그래서 A0(hold1)과 A1(hold2)의 시간 비교는 부하가 섞였고, 판정에는 부하와 무관한 버퍼 수(`EXPLAIN (ANALYZE, BUFFERS)`)와 같은 창에서 번갈아 잰 쌍 측정(hold4)만 쓴다.
- **하네스 결함 1건**: 첫 칸(A0 `balance-1k` 5회와 `balance-10k` 1회)은 pgbench 트랜잭션 로그 정리가 컨테이너 안에서 globbing되지 않아 앞 칸 로그가 섞였다(`harness-invalid`로 표시하고 뺐다). 고친 뒤의 run은 run마다 로그를 지운다. 처리량(tps)은 pgbench 자체 출력이라 영향이 없다.
- 시간 칸의 p95는 한 run의 트랜잭션 로그로 계산했다. 1m 판매자 조회처럼 5초 run에 트랜잭션이 몇 건뿐인 칸의 p95는 표본이 작다(n 열 참고).

### A0: 현재 비용 (hold1, 칸마다 깨끗한 run 5회, p50은 run별 p50의 중앙값)

| 칸 | 1k | 10k | 100k | 1m |
|---|---|---|---|---|
| `sellerBalance` p50 ms | 0.826(hold4 쌍 측정의 A0 인덱스 칸) | 14.410 | 240.846 | 628.163 |
| `reserveBalance` p50 ms | 0.440 | 4.948 | 186.867 | 553.739 |
| 환불 분개 트랜잭션(판매자 락 + `reserveBalance` + 분개) p50 ms | 1.448 | 5.780 | 190.751 | 998.908 |
| 30일 기간 합계 p50 ms | 3.925 | 28.799 | 332.401 | 719.564 |
| 전체 이력 기간 합계 p50 ms | 12.307 | 173.656 | 752.755 | 1958.711 |
| 행 첫 페이지(30일) p50 ms | 0.460 | 2.592 | 34.332 | 248.196 |
| 정산서 1회(질의 5개, REPEATABLE READ) p50 ms | 5.349 | 45.619 | 578.879 | 944.730 |
| 정산서 1회 p95 ms | 7.601 | 64.450 | 714.913 | 4255.173 |

버퍼(`EXPLAIN (ANALYZE, BUFFERS)`, 데운 캐시 1회): `sellerBalance` 1,029 / 9,959 / 73,785 / 138,633(1m은 `read=23,532` 포함), 실행 18.4 / 90.3 / 425.6 / 783.7 ms. 10k·100k는 `settlement_entry_seller_account_side_idx` 비트맵 스캔 뒤 힙을 항목당 거의 한 페이지씩 읽었다(39,200행에 9,959 버퍼: 판매자 행이 시간순 삽입으로 표 전체에 흩어져 있다). 1m은 표의 40%라 병렬 순차 스캔이었다.

**근본 원인(측정으로 찾음)**: 30일 기간 합계도 이력에 비례했다. 계획에서 수수료·보류 부분(`period_entries`)이 `settlement_entry`를 병렬 순차 스캔(1m: 3,920,000행 읽음, 741 ms 중 대부분)하고 분개와 해시 조인한 뒤 기간으로 걸렀다. 질의가 기간 조건을 분개(`j.recorded_at`)에만 걸어서 항목 쪽에서 판매자·기간 범위를 쓸 인덱스가 없었다. EXP-080은 분개 쪽 갈래만 보고 이 갈래를 측정하지 않았다.

| 가설 | 결과 | 판정 |
|---|---|---|
| H1 `sellerBalance` p50 1m/1k ≥ 50배 | 쌍 측정 1k p50 0.826 ms 대 1m 628.163 ms: 760배 | ✓ 지지 |
| H2 환불 분개 p50 1m/1k ≥ 10배 | 998.908 / 1.448 = 690배(반증 조건 < 2배) | ✓ 지지 |
| H3 독자 4개가 capture c = 4 처리량을 ≤ 15%만 줄임 | 7,018.0 → 709.8 tps(-89.9%), p50 0.494 → 2.681 ms, p95 1.030 → 35.382 ms(5/5 깨끗). A1·A4 창에서도 3,428.8 → 346.8(-89.9%), 6,746.5 → 670.1(-90.1%) | ✗ 반증(> 30% 감소) |
| H9 `Payload` 필드 이름을 바꿔도 기존 시험 통과 | `eventType` → `type` 변이에서 기존 Kafka 시험 3개 클래스 8건 모두 통과 | ✓ 지지(문제 재현) |

H3 원인: 락 대기가 아니라 CPU 경합이다(hold5, 3회씩). 독자 4개(1m 판매자 정산서)와 capture c = 4를 같이 돌리며 0.5초마다 쓰기 세션의 대기 종류를 20번 표본한 결과 `wait_event_type = Lock`은 0회였고 대부분 대기 없음(CPU에서 실행 중)이었다(합계: CPU 40, IO 10, LWLock 4, Client 3). 독자의 병렬 질의를 끄면(`max_parallel_workers_per_gather = 0`) 쓰기 처리량이 250.2 → 846.5 tps(3.4배), 쓰기 p50 8.864 → 3.042 ms, 독자 처리량도 0.68 → 1.38 tps로 올랐다. 컨테이너 CPU 4개에 독자 4개가 각각 병렬 작업자 2개를 더 띄워 쓰기 세션이 CPU를 기다린 것이다. MVCC라 읽기가 쓰기를 잠그지 않는다는 가정은 맞았지만 같은 CPU를 나눈다는 비용을 빠뜨렸다.

### A1: 덮는 인덱스와 기간 조건 교체 (채택하지 않음, H4 반증)

A1 = `settlement_entry (seller_id, account_code, recorded_at) INCLUDE (entry_side, amount_won)`을 만들고 기존 `(seller_id, account_code, entry_side)`를 지움 + 기간 합계의 수수료·보류를 항목 `recorded_at`으로 자름. 만들기 17.1 s(4 CPU, 항목 970만 행), 크기 715 MB. 결과 동일성: 판매자 4명 × 기간 2개(30일, 전체) 8칸에서 기존 질의와 새 질의의 결과 행이 모두 같았다(`eq_a1.sql`, 8/8 `t`).

버퍼와 실행 시간(데운 캐시 1회, 부하와 무관한 비교는 버퍼):

| 질의 | 1k A0 → A1 | 10k A0 → A1 | 100k A0 → A1 | 1m A0 → A1 |
|---|---|---|---|---|
| `sellerBalance` 버퍼 | 1,029 → 424 | 9,959 → 4,083 | 73,785 → 40,271 | 138,633 → 139,045(둘 다 순차 스캔) |
| `sellerBalance` ms | 18.4 → 1.3 | 90.3 → 7.4 | 425.6 → 86.1 | 783.7 → 836.7 |
| `reserveBalance` 버퍼 | 992 → 130 | 9,596 → 1,220 | 71,888 → 11,971 | 138,633 → 119,969 |
| `reserveBalance` ms | 3.3 → 0.3 | 30.3 → 1.2 | 271.6 → 13.1 | 511.3 → 171.5 |
| 30일 기간 합계 ms | 22.8 → 3.3 | 98.3 → 28.6 | 462.6 → 137.0 | 873.7 → 196.1 |
| 전체 이력 기간 합계 ms | 32.8 → 21.5 | 226.1 → 44.3 | 1,194.5 → 213.1 | 4,577.7 → 651.8 |

A1 계획: 1k~100k의 잔액·보류는 `Index Only Scan using settlement_entry_seller_account_recorded_idx`(힙 접근 없음). 1m 잔액은 여전히 순차 스캔(판매자가 표의 40%라 계획기가 맞게 고른 것). 30일 합계의 항목 갈래는 모든 N에서 Index Only Scan이 됐고, 1m에 남은 순차 스캔은 `settlement_seller_allocation` 갈래(이 판매자가 배분의 25%)다.

pgbench(hold2, 부하 공변량 있음, 깨끗한 run 5회): `reserveBalance` p50 100k 186.867 → 6.217 ms, 1m 553.739 → 62.787 ms. 환불 분개 p50 100k 190.751 → 6.794 ms, 1m 998.908 → 65.578 ms. 30일 합계 p50 100k 332.401 → 33.513 ms, 1m 719.564 → 278.205 ms. 전체 이력 합계 p50 1m 1958.711 → 688.614 ms. 정산서 1회 p50 10k 45.619 → 11.425 ms(3/5 깨끗), 100k 578.879 → 114.059 ms.

같은 창 쌍 측정(hold4, A1 → A0 → A1 … 인덱스를 번갈아 바꾸며 칸마다 5쌍):

| 칸 | A0 인덱스 tps / p50 / p95 | A1 인덱스 tps / p50 / p95 | 변화(p50) |
|---|---|---|---|
| 잔액 1k | 1,087.0 / 0.826 / 1.333 | 2,254.7 / 0.414 / 0.609 | -50% |
| capture c = 1 | 2,663.5 / 0.324 / 0.642 | 1,836.7 / 0.411 / 1.115 | **+27%**(처리량 -31%) |
| capture c = 4 | 6,824.4 / 0.509 / 1.140 | 5,086.7 / 0.594 / 1.662 | +17%(처리량 -25%) |
| 환불 분개 100k | 4.5 / 207.646 / 296.979 | 132.9 / 7.142 / 9.920 | -97% |
| 정산서 1회 100k | 1.5 / 666.155 / 1,029.872 | 8.3 / 112.864 / 156.338 | -83% |
| 정산서 1회 1m | 0.7 / 1,523.364 / 1,560.983 | 0.9 / 903.778 / 1,507.324 | -41% |

n: 칸마다 A0 5쌍, A1 5쌍이 깨끗(load1 5.8~7.3)했다. 첫 hold4 시도는 호스트 디스크가 가득 차(Colima VM 148 GB 100%) 인덱스 교체가 실패해 54 run을 `invalid`로 버렸고, 쓰이지 않는 익명 볼륨 정리(Main 승인, 102 GB 회수) 뒤 다시 쟀다. 인덱스 크기: 이전 `(seller_id, account_code, entry_side)` 68 MB, 새 인덱스 715 MB(만든 직후). 이전 인덱스는 키 중복이 많아 B-tree 중복 제거를 받지만 새 인덱스는 `recorded_at`이 키에 있어 받지 못한다. 쓰기마다 이 큰 인덱스에 항목 4개를 넣는 것이 쓰기 비용 증가의 유력한 원인이다(WAL·페이지 분할을 따로 재지는 않았다).

| 가설 | 결과 | 판정 |
|---|---|---|
| H4 A1이 N ≥ 10만 `sellerBalance` p50을 ≥ 2배 줄이고 capture c = 1 p50 증가 ≤ 10% | 잔액 p50 100k 240.846 → 38.073 ms(hold2, 6.3배), 1k 0.826 → 0.414 ms(쌍). 그러나 capture c = 1 p50 0.324 → 0.411 ms(+27%, 쌍) | ✗ 반증(쓰기 p50 증가 > 15%) |

### A2: 같은 트랜잭션 투영 (hold3, A1 인덱스 위)

재구축(빈 투영을 원장 전체에서 채움) 18.2 s, 투영 2,602,618행(378 MB). 잔액 질의 버퍼 43~58, 실행 0.6~0.7 ms(N과 무관). pgbench 잔액 p50 1k 0.255, 10k 0.323, 100k 0.428, 1m 0.431 ms. 환불 분개 p50 0.41~0.45 ms(N과 무관).

쓰기(같은 hold3 창의 A4 칸이 기준: A4는 materialized view만 더해 쓰기 경로가 A1과 같다): capture c = 1 p50 0.811 ms 대 0.447 ms(+81%), tps 1,009.0 대 1,614.6. 같은 판매자 c = 8 capture **2,450.1 대 7,345.0 tps(-66.6%)**, p50 2.138 대 0.818 ms, p95 9.602 대 2.109 ms.

정확성(빈 DB, V1~V7 + A2 + A3, 무작위 흐름 c = 4 2,000 분개 중 1,998 커밋): 판매자·계정 80 키에서 A2 잔액 = 원장 합산(불일치 0), 일 단위 대사 불일치 0. **교착 발견**: 무작위 흐름의 2건(0.1%)이 실패했고, c = 8 · 300건 × 8에서 `deadlock failures` 10건(0.417%)이었다. 같은 DB에서 투영 트리거만 지우고 같은 부하를 다시 돌리면 0건이었다. 판매자 두 명의 checkout 항목을 서로 다른 순서로 넣는 두 트랜잭션이 투영 행을 반대 순서로 잠근다(운영 코드는 배분을 `orderId` 순서로 넣으므로 판매자 순서가 고정되지 않는다). 유실 주입: 투영 행 1개 삭제는 대사가 1키, 트리거를 건너뛴 균형 분개(복제 역할) 1건은 대사가 2키(그 분개의 계정 2개)를 지목했다(H7).

| 가설 | 결과 | 판정 |
|---|---|---|
| H5 A2가 1m 잔액 p50 ≤ 5 ms이고 같은 판매자 c = 8 처리량을 ≥ 30% 줄임 | 잔액 p50 0.431 ms, 같은 판매자 처리량 -66.6%, 교착 0.417%(c = 8) | ✓ 지지 → 판정 규칙상 기각 |

### A3: 원장 cursor 기반 비동기 투영 + 꼬리 합산 (hold3, A1 인덱스 위)

꼬리 조회 인덱스 `(seller_id, entry_id) INCLUDE (account_code, entry_side, amount_won)` 796 MB, 만들기 37.9 s. 재구축(적재 1일 전까지 접기) 17.2 s, 투영 2,599,224행(421 MB). 1m 판매자의 꼬리(마지막 1일) 5,087항목. worker 1회(capture c = 4 10초 뒤 약 2만 투영 행 upsert) 0.83~1.36 s(5회).

잔액(투영 + 꼬리) p50 1k 1.007, 10k 1.340, 100k 1.394, 1m 1.886 ms. 환불 분개 p50 1.8~2.5 ms. 쓰기: capture c = 1 p50 0.694 ms(A4 기준 0.447, +55%: 꼬리 인덱스 하나 더), 같은 판매자 c = 8 7,641.8 tps(A4 7,345.0, 차이 없음).

정확성: 무작위 흐름 뒤 투영 + 꼬리 = 원장 합산(80키 불일치 0), cursor 아래 일 단위 대사 불일치 0. 늦은 커밋(활성 트랜잭션을 연 채 worker 실행, 4초 뒤 커밋): 불일치 0(유실 없음). **음성 대조**: 활성 트랜잭션을 보지 않는 단순 cursor(`now() - 1초`)로 같은 상황을 만들면 2키가 어긋났다(늦게 커밋된 항목이 cursor 아래로 들어가 영영 접히지 않음). 투영 행 삭제와 트리거 건너뛴 삽입은 대사가 지목했다.

| 가설 | 결과 | 판정 |
|---|---|---|
| H6 A3 투영 + 꼬리 = 합산, 1m 잔액 p50 ≤ 10 ms, capture p50 변화 A1 대비 ±10%, 늦은 커밋 유실 0 | 불일치 0, 1m p50 1.886 ms, 늦은 커밋 유실 0, capture p50 +55%(꼬리 인덱스) | ✗ 부분 반증(쓰기 비용 조건) |
| H7 대사가 (a)(c)를 지목 | A2: (a) 1키, (c) 2키 지목. A3: (a) 1키 지목(음성 대조에서 남은 2키에 더해), (c)는 해당 없음(A3는 트리거가 없어 그 항목이 꼬리로 정상 반영됨) | ✓ 지지 |

### A4: materialized view (hold3)

만들기 11.6 s + 고유 인덱스 1.8 s. `REFRESH MATERIALIZED VIEW CONCURRENTLY` 22.8 / 21.5 / 16.2 s(3회). 잔액 p50 0.37~0.51 ms. 새로 고침 사이 최신성은 새로 고침 주기 + 16~23 s만큼 늦다.

| 가설 | 결과 | 판정 |
|---|---|---|
| H8 새로 고침 ≥ 10 s | 16.2~22.8 s | ✓ 지지 → 판정 규칙상 기각 |

### 공개 이벤트 계약 (3.4)

H9 재현 뒤 `schemaVersion`(1)을 payload에 넣고 계약 시험(`ReservationEventPayloadContractTest`, 2건: 필드 집합·JSON 형식·key·중복 제거 헤더·ISO-8601 마이크로초, 오류 코드 없음은 명시 null)을 더했다. 참조 소비자(시험 전용)는 모르는 버전에서 멈춘다. Kafka relay 동작(전송 순서, 장애 중복)은 다시 재지 않았다(EXP-089·091·100이 다룸).

### 전환 신호 지표 (7절)

| 신호 | 결정 | 근거 |
|---|---|---|
| 큐 표 `n_dead_tup`·크기·마지막 VACUUM(T1) | 옵트인 게이지로 구현 | 통계 뷰만 읽음. 질의 비용 데운 실행 1회 19.6 ms(계획 8.9 ms, 표 4개의 `pg_total_relation_size` 포함) |
| 청구 지연(T1·T2) | 타이머로 구현(기본 켜짐, DB 질의 추가 없음) | 기존 청구 호출을 감쌀 뿐 |
| relay lag·stalled(T4) | 이미 있음(`relay.lag_events`·`stalled_seconds`), 임계만 문서화 | ADR-0067 |
| 판매자 락 대기(T6) | 옵트인 게이지(락 종류별)로 구현. 용도별 분리는 하지 않음 | advisory 락 7곳이 같은 함수라 DB에서 용도 구분 불가. 질의 비용 0.3 ms(계획 0.5 ms) |
| 원장 삽입 지연(T5) | 지표를 새로 만들지 않음 | settlement 모듈에 Micrometer 의존이 없고 새로 들이지 않는다. 쓰기 라우트는 `http.server.requests`가 이미 있다. 이번 측정에서 원장 삽입 비용의 주원인은 삽입이 아니라 락 안의 이력 합산(H2)이었고 A1이 그것을 줄인다 |
| 핫 SKU 경합(T7) | 락 대기 게이지의 `transactionid`·`tuple` 종류로 간접 관찰, SKU별 지표는 만들지 않음 | SKU 라벨은 고카디널리티. 핫 SKU 측정은 EXP-042·055·062가 다룸 |

### 시험과 변이 확인

출시하는 변경(계약, 신호 지표)의 대상 시험: 변경 후 `ModuleBoundaryTest` 7, `DatabaseSignalMetricsIntegrationTest` 2, `PayPalWebhookRetryWorkerMetricsTest` 6, `PaymentRecoveryWorkerTest` 10, `KafkaReservationEventPublisherTest` 2, `ReservationEventPayloadContractTest` 2, `PayoutDispatchWorkerTest` 19, `ReservationEventConsumerKafkaIntegrationTest` 5, `ReservationEventKafkaIntegrationTest` 1건 모두 통과. 변경 전 코드(`origin/main` + 새 계약 시험만)에서 계약 시험은 2건 중 1건(필드 집합) 실패했다. 오류 코드가 없을 때 null을 싣는 시험은 변경 전에도 통과한다(현재 동작을 고정하는 시험이다). 신호 게이지 시험은 변경 전에는 대상 클래스가 없어 컴파일되지 않으므로 아래 변이로 확인했다.

| 변이 | 실패한 시험 |
|---|---|
| M3 락 대기 게이지를 대기 종료 뒤 0으로 되돌리지 않음 | `DatabaseSignalMetricsIntegrationTest` 2건 중 1건 |
| M4 payload `schemaVersion`을 0으로 | `ReservationEventPayloadContractTest` 2건 중 1건 |
| M5 실패한 청구를 재지 않음 | `PaymentRecoveryWorkerTest` 10건 중 1건 |

A1 후보 코드(덮는 인덱스 마이그레이션, 기간 조건 교체, 원장 점검 `ENTRY_RECORDED_AT_MISMATCH`, 그 시험)도 만들어 변경 전 실패·변경 후 통과·변이 M1(인덱스 없음)·M2(하한 `>`)·M6(점검 무력화)를 확인했지만, H4 반증으로 이 PR에서 뺐다(삭제한 파일의 내용 참고).

## 사전 등록 규칙 평가

- 투영을 기본 경로에 넣는 조건(A0 잔액 또는 환불 분개 p95 > 50 ms, N = 10만): A0 잔액 p95 270.654 ms, 환불 분개 p95 232.065 ms라 **조건 성립**. 그래서 판정 규칙대로 투영 후보를 비교했고, 같은 원인을 A1이 투영 없이 해결하는지를 먼저 봤다. A1은 환불 분개 p95를 296.979 → 9.920 ms(쌍 측정, 100k)로 내려 조건을 풀었지만 H4(쓰기 비용)가 반증됐다.
- A2: 같은 판매자 처리량 -66.6%(≥ 30%) → 규칙대로 기각.
- A4: 새로 고침 16~23 s(≥ 10 s)이고 돈을 정하는 경로에 못 쓴다 → 규칙대로 기각.
- A1: H4 반증(capture p50 +27% > 15%) → 규칙대로 채택하지 않는다. 읽기 이득(락 안 200 ms)과 쓰기 비용(0.09 ms, 처리량 -31%)의 교환은 사용자 결정으로 #719에 남긴다.
- 공개 이벤트: H9 성립 → 계약 시험 추가, Kafka는 켜지 않음.

## 해석

- 문제는 "조회가 쓰기와 같은 DB에 있다"가 아니라 "조회가 필요 없는 행과 힙 페이지를 읽는다"였다. 30일 합계가 이력 전체를 읽은 것은 질의 조건의 결함이었고, 잔액은 흩어진 힙을 읽은 것이 비용의 대부분이었다(버퍼가 항목 수와 거의 같음). 덮는 인덱스는 같은 원장 위에서 이를 해결하지만(진실 원천 하나) 중복 제거를 못 받는 큰 인덱스라 모든 항목 삽입이 비싸졌다. 읽기를 줄이는 모든 안이 쓰기 쪽에 비용을 옮겼고(A1 인덱스, A2 핫 행, A3 꼬리 인덱스), 비용이 가장 작은 것은 측정한 범위에서 A1이었다.
- 투영은 조회를 상수로 만들지만 각자 비용이 측정으로 드러났다. 같은 트랜잭션 투영(A2)은 원장에 없던 핫 행과 교착을 만들었다. 이는 EDA 검토 6절의 Modern Treasury(핫 계정 잔액 행 경합으로 동기·비동기 분기)와 같은 구조이고, 현재 ATLAS 원장에 잔액 행이 없다는 것이 오히려 장점이었다. 비동기 투영(A3)은 정확하게 만들 수 있었지만(활성 트랜잭션을 보는 cursor, 음성 대조에서 단순 cursor는 유실) worker·cursor·대사·꼬리 인덱스를 운영해야 한다.
- H3(독자가 쓰기를 거의 줄이지 않음)는 크게 빗나갔다. 원인은 락이 아니라 같은 CPU를 쓰는 병렬 조회였다. 판매자 조회가 늘면 쓰기 경로가 느려지는 첫 원인은 이것이고, 이 경우의 처방은 투영보다 조회 비용 자체를 줄이는 것(A1)이거나 조회 전용 연결의 병렬도 제한·읽기 replica다. 이 PR은 처방을 정하지 않고 신호(락 대기 게이지가 0인데 쓰기 지연이 오르면 CPU 경합)를 운영 가이드에 적는다.
- 공개 계약: 지금은 소비자 0개라 계약 시험의 가치는 "바꾸는 사람이 깨진 것을 아는 것"이다. 버전 필드는 첫 외부 소비자가 생기기 전에 넣어야 비용이 0이다.

## 결정 (ADR-0103, D-182)

- 조회 투영(A2·A3·A4)과 덮는 인덱스(A1)를 넣지 않는다. 현행(A0)을 유지하고 운영 가이드의 판매자 이력 조회 신호를 재검토 조건으로 둔다. A1의 읽기·쓰기 교환은 #719에서 사용자에게 묻는다.
- 공개 payload에 `schemaVersion`과 계약 시험. relay는 옵트인 그대로.
- 청구 지연 타이머(기본 켜짐), DB 통계 게이지(옵트인). 임계값은 운영 가이드 "EDA 전환 신호" 표.

## 한계

- 단일 호스트, 합성 분포(판매자 Zipf s = 1.1, 730일 균등, 종류 비율 고정). 운영 분포의 판매자 편중·계절성은 다르다.
- hold2의 잠금 밖 부하(빌드 컨테이너 약 3.4코어) 때문에 A0 대 A1의 시간 비교 일부는 부하가 섞였다. 판정에는 버퍼 수와 hold4 쌍 측정을 썼다.
- A2·A3·A4는 A1 인덱스가 있는 상태에서 쟀다(채택 후보 위에서의 추가 비용). A0 인덱스 위의 투영 비용은 재지 않았다.
- 데운 캐시. 1m 판매자 잔액은 `read` 버퍼가 섞였고 콜드 캐시 지연은 재지 않았다.
- A2 교착은 pgbench 무작위 흐름(판매자 20명)에서 재현했고 실제 서비스 흐름에서는 재지 않았다. 운영 코드가 배분을 `orderId` 순서로 넣는다는 것은 코드 읽기다.
- 신호 임계값은 측정이 아니라 설정값(autovacuum 기본 scale factor, `lock_timeout`, `idle_in_transaction_session_timeout`, 폴링 간격)과 외부 사례에서 끌어온 `✓ 결정(가정)`이다.
- A1의 쓰기 비용은 쌍 측정 5쌍(c = 1, c = 4)뿐이고 원인(인덱스 크기, 중복 제거 없음)은 크기 비교에서 추정했다. WAL 바이트와 페이지 분할은 재지 않았다. INCLUDE 없는 변형과 계정별 부분 인덱스는 재지 않았다(재검토 조건에 넣음).
- 이 PR은 마이그레이션을 더하지 않는다(배정받은 V10은 쓰지 않음).

## 주장 표

| 주장 | 증거 | 확신도 |
|---|---|---|
| 판매자 잔액·보류 잔액·환불 분개 비용이 판매자 이력에 비례했다 | A0 표(H1, H2), 버퍼 | 높음(로컬 합성) |
| 30일 정산서 합계가 이력 전체를 읽었다 | A0 계획(`period_entries` 순차 스캔) | 높음 |
| A1(인덱스 + 조건 교체)은 결과를 바꾸지 않는다 | 8/8 동일, 경계 시험, 변이 M2 | 높음 |
| A1은 1k~100k 잔액·보류를 Index Only Scan으로 바꾸고 환불 분개 p50을 약 29배 줄인다 | A1 계획·버퍼, 쌍 측정 | 높음(로컬 합성) |
| 덮는 인덱스(A1)는 쓰기 p50을 약 27% 늘린다 | hold4 쌍 측정(5쌍, 같은 창) | 중간(단일 호스트, 원인은 인덱스 크기로 추정) |
| A2는 같은 판매자 처리량을 줄이고 교착을 만든다 | hold3, corr(트리거 유무 대조) | 높음(로컬) |
| A3 cursor는 늦은 커밋을 잃지 않는다(단순 cursor는 잃는다) | corr 2·2n | 중간(1회 재현) |
| 공개 payload 이름 변경을 기존 시험이 못 잡았고 이제 잡는다 | H9 변이, 계약 시험, 변이 M4 | 높음 |
| 신호 게이지가 실제 PostgreSQL에서 값을 읽고 대기 종료 뒤 0으로 돌아간다 | `DatabaseSignalMetricsIntegrationTest`, 변이 M3 | 높음 |

## 삭제한 파일의 내용

측정 구동 파일은 저장소에 올리지 않았다(실험 산출물 기준). 재현에 필요한 내용을 여기에 옮긴다. 원본은 작업 폴더 밖에만 있다.

### 합성 원장 생성기

```sql
SET session_replication_role = replica;
CREATE TEMP TABLE gen_seller(seller_id uuid PRIMARY KEY, n int NOT NULL);
INSERT INTO gen_seller VALUES
  ('00000000-0000-0000-0000-000000001000', 1000), ('00000000-0000-0000-0000-000000010000', 10000),
  ('00000000-0000-0000-0000-000000100000', 100000), ('00000000-0000-0000-0000-000001000000', 1000000);
WITH z AS (SELECT g, 1.0 / power(g, 1.1) w FROM generate_series(1, 5000) g), s AS (SELECT sum(w) tw FROM z)
INSERT INTO gen_seller SELECT ('10000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
  greatest(1, round(1000000 * w / tw))::int FROM z, s;
CREATE TEMP TABLE gen_journal AS
SELECT s.seller_id, i,
       CASE WHEN i % 100 < 75 THEN 'C' WHEN i % 100 < 93 THEN 'K' WHEN i % 100 < 99 THEN 'R' ELSE 'P' END AS kind,
       now() - random() * interval '730 days' AS recorded_at, (10000 + floor(random() * 190000))::bigint AS gross,
       ('10000000-0000-0000-0000-' || lpad((1 + floor(random() * 5000))::int::text, 12, '0'))::uuid AS other_seller
FROM gen_seller s, generate_series(1, s.n) i;
-- journal_id = uuidv7(recorded_at - now()). 분개를 recorded_at 순서로 넣는다.
-- capture(C): 수수료 3%, 보류 5%, 항목 4개(PSP_CLEARING 차변 = 판매자 + 수수료 + 보류).
-- checkout(K): 이 판매자 gross + 다른 판매자 gross/2 배분, 판매자마다 capture 모양 항목 4개.
-- refund(R): gross/4, PSP_CLEARING 대변 = SELLER_RESERVE 차변(gross/8) + SELLER_PAYABLE 차변(나머지).
-- payout(P): SELLER_PAYABLE 차변 = PSP_CLEARING 대변 = gross.
RESET session_replication_role;
VACUUM (ANALYZE) settlement_journal, settlement_entry, settlement_seller_allocation;
```

검사 질의: 분개별 차변 합 ≠ 대변 합 건수, 항목 없는 분개 건수(둘 다 0이었다). 적재 8분 13초.

### 채택하지 않은 A1 후보 코드(작업 중 만들었고 H4 반증으로 지움)

- 마이그레이션(배정 번호 V10으로 만들었던 것, 이 PR은 마이그레이션을 더하지 않음): `CREATE INDEX IF NOT EXISTS settlement_entry_seller_account_recorded_idx ON settlement_entry (seller_id, account_code, recorded_at) INCLUDE (entry_side, amount_won); DROP INDEX IF EXISTS settlement_entry_seller_account_side_idx;`
- `SettlementJournalRepository.sellerStatementTotals`의 `period_entries`: `FROM settlement_entry e JOIN settlement_journal j USING (journal_id) WHERE e.seller_id = ? AND j.recorded_at >= ? AND j.recorded_at < ?`를 `FROM settlement_entry e WHERE e.seller_id = ? AND e.account_code IN ('PLATFORM_FEE', 'SELLER_RESERVE') AND e.recorded_at >= ? AND e.recorded_at < ?`로(항목과 분개의 `recorded_at`이 같다는 가정, 모든 쓰기 경로가 같은 `Instant`를 넘김).
- 그 가정을 지키는 원장 점검: `SELECT 'ENTRY_RECORDED_AT_MISMATCH' AS check_name, count(*) AS violations FROM settlement_entry e JOIN settlement_journal j USING (journal_id) WHERE e.recorded_at <> j.recorded_at;`
- 시험 `SellerLedgerReadIndexIntegrationTest`(3건): 인덱스 정의, 잔액·기간 질의 계획의 인덱스 사용, `[from, to)` 경계(분개·항목을 from, to - 1µs, to, from - 1µs에 넣고 수수료·보류 합이 행 질의와 같은 경계인지).
- 다시 쓰려면 위 문장으로 다시 만든다(작업 중 커밋은 PR 전에 합쳐 이력에 남지 않는다). 마이그레이션 번호는 그때 비어 있는 번호를 받는다.

### 측정 구동

- 컨테이너: `postgres:18.6`, `--cpus 2 --memory 6g --shm-size 1g`, `-c shared_buffers=2GB -c max_wal_size=8GB -c checkpoint_timeout=30min -c track_io_timing=on`, 잠금 구간 동안 `docker update --cpus 4`. V1~V7 적용.
- 칸 하나: `pgbench -n -M prepared -c <c> -j <c> -T <초> -D seller=<uuid> -D from=<시각> -D to=<시각> -f <스크립트> --log`. 결과 열: 암, 칸, run, c, tps, p50 ms, p95 ms(트랜잭션 로그의 지연 열 정렬), 트랜잭션 수, load1(전/후), 다른 라벨 컨테이너, 상태(load1 > 12면 `contaminated-load`).
- 읽기 스크립트는 `SettlementJournalRepository`의 질의 문장을 그대로(매개변수만 pgbench 변수로) 썼다. 정산서 1회 = `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` + 기간 합계 + 잔액 + 행 21개 + 지급 합계 2개 + `COMMIT`.
- 쓰기 스크립트: capture = 분개 1(배경 판매자 무작위) + 항목 4(V7 트리거 켬). 같은 판매자 = 1m 판매자 고정. 환불 = `pg_advisory_xact_lock(hashtextextended('settlement-payout:' || seller, 0))` + 보류 잔액 합산 + 분개 1 + 항목 3. 쓰기 칸 사이에 적재 시각 이후 행을 지우고 `VACUUM`(원장 크기 고정, 복제 역할로 V7 트리거 우회).
- 독자 영향: 정산서 스크립트를 1m 판매자로 c = 4, 14초 백그라운드 실행 2초 뒤 capture c = 4 10초.
- A2 DDL: `settlement_seller_day_balance(seller_id, account_code, business_day, credit_won, debit_won, entry_count, PK 앞 세 열)` + 항목 `AFTER INSERT ... WHEN (NEW.seller_id IS NOT NULL)` 트리거의 `INSERT ... ON CONFLICT DO UPDATE` 누적.
- A3 DDL: 같은 모양의 `settlement_seller_rollup`, 단일 행 cursor `settlement_rollup_cursor(entry_id uuid)`, 꼬리 인덱스 `settlement_entry (seller_id, entry_id) INCLUDE (account_code, entry_side, amount_won)`. worker 1회 = `next := uuidv7 하한(least(활성 client 트랜잭션의 min(xact_start), now()) - 1초)`, `entry_id ∈ [prev, next)` 항목을 판매자·계정·일로 접어 upsert, cursor = next(한 트랜잭션). 잔액 = 투영 합 + `entry_id >= cursor` 꼬리 합.
- A4 DDL: 같은 집계의 materialized view + 고유 인덱스, `REFRESH MATERIALIZED VIEW CONCURRENTLY`.
- 정확성: 빈 DB에 V1~V7 + A2 + A3(checkout·payout 분개의 외래 키 대상 표를 만들지 않으려고 두 외래 키만 뗌), 무작위 흐름(판매자 20명, capture 60 / checkout 20 / refund 15 / payout 5%)을 c = 4로 2,000건 넣는 동안 worker를 50 ms 간격으로 돌림. 비교 질의: 판매자·계정별 원장 합산 대 A2 합 대 A3(투영 + 꼬리) 합, 일 단위 대사(대변·차변·건수).
- 쌍 측정(hold4): 같은 잠금 구간에서 인덱스를 A1 → A0 → A1로 바꾸며(만들기 + 지우기 + `VACUUM ANALYZE`) 칸 5개(잔액 1k, capture c = 1, capture c = 4, 환불 100k, 정산서 100k·1m)를 5쌍.
