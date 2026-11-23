# ADR-0068: 외부 조사로 약점을 찾아 고도화한 방향

- 상태: Accepted — 외부에서 지적된 약점을 우리 구현의 요구사항으로 받아 보강한다. 외부 주장은 그대로 믿지 않고 우리 코드와 재현 실험으로 확인한 뒤 반영한다.
- 날짜: 2026-11-23
- 관련: [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md), [ADR-0066](ADR-0066-redis-admission-gate.md), [ADR-0067](ADR-0067-kafka-not-adopted.md), [EXP-060](../../03-experiments/EXP-060-hot-sku-admission-gate.md), [EXP-061](../../03-experiments/EXP-061-event-log-fanout-and-replay.md), [실험 점검표](../../03-experiments/experiment-checklist.md)

## 배경

Redis gate와 이벤트 로그를 자체 측정으로 만든 뒤, 약한 곳이 어디인지 외부 1차 자료로 점검했다. 약점으로 지적된 항목은 접는 이유가 아니라 보강할 설계 요구로 취급한다. 점검은 (1) Redis 사전 차감 (2) 이벤트 로그와 Kafka 판단 (3) saga/예약 구조 (4) 부하 측정 방법 네 갈래로 나눠 각각 반증을 찾는 방식으로 했다. 출처의 날짜나 본문을 직접 확인하지 못한 것은 해당 줄에 "미확인"으로 표시한다.

## 1. 이벤트 로그: 손실 가능성을 재현했고 안전한 cursor로 고도화한다

- 사실: PostgreSQL 시퀀스 번호는 INSERT 시점에 할당되고 행은 COMMIT 시점에 보인다. `event_seq > cursor`로 읽는 consumer는 늦게 커밋된 낮은 번호를 영영 건너뛴다. [Dudycz 2022-10-28](https://event-driven.io/en/ordering_in_postgres_outbox/), [Sequin 2024-07](https://blog.sequinstream.com/postgres-sequences-can-commit-out-of-order), [PostgreSQL 시퀀스 문서](https://www.postgresql.org/docs/current/functions-sequence.html).
- 직접 재현: `load-tests/event-log-gap-check.sh`에서 3,200건 중 현행 방식은 1,013건을 놓쳤고 xmin 안전 방식(`tx_id < pg_snapshot_xmin(pg_current_snapshot())`)은 0건이었다. EXP-061는 lag만 재서 이를 보지 못했다([정정](../../03-experiments/EXP-061-event-log-fanout-and-replay.md)).
- 결정: 로그에 `tx_id xid8`를 두고 xmin 상한 아래만 읽는 cursor로 바꾸고(V75), 같은 로그를 Kafka로 내보내는 relay를 옵트인 기능으로 구현한다(ADR-0067). 외부 consumer는 Kafka, DB 안 consumer는 안전 cursor를 쓴다. 알려진 비용(장기·idle-in-transaction 트랜잭션이 xmin을 붙잡아 모든 consumer를 멈춤, [PgQ](https://github.com/pgq/pgq))은 stall gauge와 `idle_in_transaction_session_timeout`으로 다룬다.
- 논란 지점: logical replication slot은 라이브 스트림용이며 7일 재생을 slot으로 해결할 수 없다(slot이 WAL을 계속 붙잡는다, [PostgreSQL 문서](https://www.postgresql.org/docs/current/logicaldecoding-explanation.html)).
- 보존은 시간 단위 파티션의 `DETACH CONCURRENTLY` 후 `DROP`이다([문서](https://www.postgresql.org/docs/current/ddl-partitioning.html)).

## 2. Redis admission gate: 지적된 결함을 보강한다

- 반증 근거: Shopify는 재고 예약에서 Redis를 쓰다 두 시스템(Redis와 MySQL ledger)이 원자적이지 않아 oversell/undersell이 모두 가능해 MySQL(SKIP LOCKED)로 되돌렸다고 밝혔다([2026-05-12](https://shopify.engineering/scaling-inventory-reservations)). 우리 gate는 정본이 아니라 "효율용 필터"라 이 비판이 그대로 적용되지는 않지만, Redis를 도입할 이유가 약해진다.
- 코드 결함(코드로 확인, 영향 크기는 미측정):
  1. 승인 때 차감한 수량을 예약 실패·취소·만료 때 돌려주는 경로가 없다. 5초 TTL 재시드만 치유한다. 그 사이는 과잉 거절(stale-low)이 생기고, 근사값에 기댄 거절이 DB 확정 소진과 같은 409로 나간다.
  2. 재시드가 `availableStock`을 읽는데 "승인됐지만 아직 DB에 없는 건"이 빠져 있어 주기적으로 과다 승인(stale-high)이 생긴다. DB가 거절하므로 안전하지만 낭비다.
  3. 재고가 큰 SKU에서는 거절이 없어 보호도 없다. 재고 카운팅과 용량 기반 shedding이 섞여 있다([Amazon Builders' Library](https://aws.amazon.com/builders-library/using-load-shedding-to-avoid-overload), [Stripe](https://stripe.com/blog/rate-limiters)).
- Redis 복제·AOF는 확인된 쓰기도 잃을 수 있다([복제 문서](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)). 효율 필터로서는 허용되지만 정확성 장치로 쓰면 안 된다([Kleppmann](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)).
- 더 단순한 후보: 품절 음성 캐시. location이 부족을 알린 SKU만 짧은 TTL로 기억하면 거절 비용(workflow 행, DB 트랜잭션, location 호출)이 모두 사라진다. 음성 캐시 선례: [RFC 2308](https://www.rfc-editor.org/rfc/rfc2308), [RFC 5861](https://www.ietf.org/rfc/rfc5861).
- 조치(구현 완료): ① 반환 경로 — 예약 실패·취소 때 `release`, 예약 확정 때 `confirm`(멱등 Lua, 확정된 건은 돌려주지 않음). ② 재시드가 "승인됐지만 예약 전" 수량을 빼도록 pending 계정 추가. ③ gate 거절 메시지를 DB 확정 소진과 구분. ④ Redis 없는 변형(`AvailabilityPrecheckAdmissionGate`)에 품절 음성 캐시(기본 1초) 추가. 두 방식의 같은 세션 짝 반복 비교 결과로 기본값을 고른다.

## 3. saga/예약 구조: 구조는 맞고 운영 공백을 메운다

- 맞는 것: 오케스트레이터 + 내구 workflow 행, 멱등키와 파라미터 불일치 거절, TTL 예약, cancel-before-reserve tombstone([Seata fence](https://seata.apache.org/blog/seata-tcc-fence), [AWS 멱등 API](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)), 외부 호출은 트랜잭션 밖([Brandur 2017-10-27](https://brandur.org/idempotency-keys)), claim을 capture 앞에 두는 pivot 배치([Microsoft Saga 문서](https://learn.microsoft.com/en-us/azure/architecture/patterns/saga)), 같은 행 CAS lease fencing, SKIP LOCKED.
- 공백(우선순위순):
  1. `NEEDS_REVIEW`가 관측되지 않는다. 특히 원격 재고가 CLAIMED로 남으면 TTL이 구하지 못해 영구 차감된다. 결제 쪽에는 운영자 API가 있는데 예약 쪽에는 없다. **이번에 gauge 2개를 추가했다**(`atlas.order.inventory.needs_review`, `..needs_review_claimed`). 운영자 조회·재시도·강제 해제 API는 아직 없다.
  2. cancel이 `INVALID_STATE`로 거절되면 "이미 claim됨"으로 추정해 restore하는 것은 오류 코드로 상태를 추론하는 구조다. location에 상태 의존형 멱등 `release` API를 두는 쪽이 낫다. 단, restore는 결제 쪽 "capture 확정 실패"가 기록된 뒤에만 호출돼야 하며 그 코드는 이번에 읽지 않았다(미확인).
  3. tombstone 보존·정리 정책이 없다(location 모듈에 삭제 쿼리가 없음).
  4. 주문당 DB 트랜잭션 5.8은 일부만 본질적이다. 외부 호출 수 + 1이 하한에 가깝고, 읽기 트랜잭션이 섞여 있을 가능성이 크다(추정). 읽기·쓰기를 분리해 재측정하기 전에는 상태 수 줄이기 리팩터링을 하지 않는다.
- 방향: 범용 saga 프레임워크는 만들지 않고 현재의 DB lease queue를 더 단단하게 한다. lease 큐가 5개를 넘거나 장기 타이머·사람 승인·이력 UI 요구가 겹치면 workflow 엔진을 평가한다(엔진도 상태 전이마다 DB에 쓰므로 처리량 이득은 아니다, [Temporal](https://temporal.io/blog/scaling-temporal-the-basics)). Redis에는 재고 정본을 두지 않고 효율용 필터로만 쓴다(Shopify 사례).

## 4. 측정 방법: 주장 등급을 정하고 설계를 보강한다

외부 방법론([Tene](https://videog.infoq.com/downloads/pdfdownloads/presentations/QConSF2015-GilTene-HowNOTtomeasureLatency.pdf), [k6 dropped_iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/), [Gregg](https://www.brendangregg.com/activebenchmarking.html), [Kalibera & Jones](https://www.cs.kent.ac.uk/pubs/2012/3233/content.pdf), [Mytkowicz](https://eecs481.org/readings/producing-wrong-data.pdf))에 비추면:

- 방어 가능: 병목의 질적 식별, 거절 주문이 durable 쓰기를 만든다는 것, 자릿수 비율.
- 참고 수준: 절대 처리량, p99, 퍼센트 개선(EXP-053의 +25.5%/+7.1%/+9.0%는 같은 조건 반복 변동 약 27%보다 작다), 서로 다른 세션의 기준선과 gate 비교, n=1~2.
- 결함: 호스트 vCPU 한도 합이 VM vCPU를 넘을 수 있고(EXP-060 9 vs 8), k6 CPU를 80% 아래로 보장하지 않았으며, 20초·10초 워밍업은 JIT·autovacuum 효과를 못 덮는다. 재고 100·SKU 1개 시나리오는 운영형 다수 SKU 분포(Zipf)를 말해 주지 못한다.
- 조치: 점검표([experiment-checklist.md](../../03-experiments/experiment-checklist.md))를 기준으로 삼고, 비교는 같은 세션 교차 실행(ABBA)과 A/A 대조, n≥3 중앙값·범위로만 주장한다. 기존 EXP의 수치는 정성적 결론 근거로만 읽는다.

## 후속 작업(우선순위)

1. 짝 반복 측정 결과로 ADR-0066 확정 또는 하향(진행 중).
2. EXP-061를 xmin 안전 cursor와 무손실 단언(16 writer, 롤백·지연 커밋, consumer 재시작·replay)으로 재검증.
3. Redis gate를 유지한다면 반환 경로(멱등 INCRBY)나 pending 반영을 추가하고 gate 거절을 "재시도 가능"으로 구분.
4. 예약 `NEEDS_REVIEW` 운영자 API(구현 완료: 조회와 `release` 재실행, README 참조). location의 상태 의존형 `release` API와 tombstone 정리는 남았다.
5. 읽기/쓰기 트랜잭션 분리 계측 후 5.8 재해석.

## 한계

- 출처는 대부분 벤더·엔지니어링 블로그이고 일부 게시일을 확인하지 못했다(Queue-it 문서, AWS Builders' Library 두 글, Helland 본문 접근 불가). Coupang/Taobao의 Redis 사전 차감에 대한 1차 자료는 찾지 못했다.
- 결제 쪽 restore 호출 전 기록 코드와 Uber 재고 예약 자료는 읽지 못했다.
- Kafka 이전 임계값(소비자 5~10개, 수천 건/s 등)은 조사자의 제안이며 출처가 있는 값이 아니다.
