# ADR-0066: 소진된 SKU의 주문을 Redis admission gate에서 싸게 거절한다

- 상태: Accepted(보강 중) — 옵트인(`atlas.order-inventory-reservation.admission-gate.enabled`, 기본 꺼짐). [ADR-0068](ADR-0068-external-research-direction-review.md)의 외부 조사로 반환 경로 부재·근사 카운터 문제와 더 단순한 대안(품절 음성 캐시)이 확인돼, 반환 경로(`release`/`confirm`)와 pending 반영으로 보강했다. 같은 세션 짝 비교([EXP-062](../../03-experiments/EXP-062-admission-gate-paired-comparison.md))에서 1,000/s는 잡음 안에서 같았고(처리량 비 Redis/precheck 중앙값 1.007, 범위 0.986~1.066, 팔당 n=6) 2,000/s는 보강 전 빌드의 Redis 팔이 precheck보다 불안정했다(팔당 n=3). 그래서 **기본 gate 후보는 품절 음성 캐시 precheck**로, Redis gate는 location 읽기를 더 줄여야 하는 규모를 위한 옵션으로 둔다. 보강 jar로 다시 한 짝 비교([EXP-063](../../03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md))에서는 2,000/s 처리량이 구분되지 않았다(중앙값 Redis 1,964/s, precheck 1,974/s, 팔당 n=4).
- 날짜: 2026-11-23
- 이름 변경(2026-11-24): 아래 본문의 `StockAdmissionService`, `RedisStockAdmissionService`, `PrecheckStockAdmissionService`는 각각 `StockAdmissionControl`, `RedisStockAdmissionControl`, `PrecheckStockAdmissionControl`로 바뀌었다. 동작과 속성·metric 이름은 그대로다. 근거는 [ADR-0069](ADR-0069-layered-naming-and-runtime-configuration.md)의 추상과 구체의 경계.
- 관련 결정: [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)(공유 인프라 도입 조건), [ADR-0060](ADR-0060-order-inventory-reservation-workflow.md)(예약 workflow), [ADR-0046](ADR-0046-order-payment-transactional-outbox.md)
- 근거 실험: [EXP-060](../../03-experiments/EXP-060-hot-sku-admission-gate.md), [EXP-062](../../03-experiments/EXP-062-admission-gate-paired-comparison.md), [EXP-063](../../03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md)

## 문제

재고가 100인 SKU에 수천 명이 동시에 주문하면 대부분은 반드시 거절된다. 현재 구현은 거절될 주문도 승인될 주문과 똑같이 order DB에 workflow 행을 만들고(주문당 DB 트랜잭션 약 5.6~5.9개) location을 호출한 뒤에야 거절한다. 같은 입력(재고 100, 도착률당 20초 측정, 단일 호스트)에서 PostgreSQL만으로는 약 1,000 주문/초부터 completed/s가 383.0~525.0(n=3)으로 포화했고 드롭 반복이 7,912~10,239건이었다([EXP-060](../../03-experiments/EXP-060-hot-sku-admission-gate.md)). 필요한 일은 "어차피 실패할 요청이 durable 쓰기를 만들지 않게" 하는 것이다.

## 도입 조건에 대한 판단

[ADR-0053](ADR-0053-shared-infra-adoption-gates.md)은 Redis를 "측정된 읽기 핫 경로가 없다"는 이유로 보류했고 임계값을 환율 quota 기준으로 적었다. 그 판단은 환율 캐시에 대한 것이었다. 이번 문제는 읽기 캐시가 아니라 **거절 요청이 쓰기 부하를 만드는 경로**이고, 위 실측이 새 근거다. 환율 캐시 보류 결정은 바꾸지 않는다.

## 결정

1. order 모듈에 `StockAdmissionService` 인터페이스를 두고 coordinator `submit`이 workflow 생성 전에 호출한다. 구현이 없으면(기본) 기존 경로 그대로다.
2. 구현은 application 모듈의 Redis gate다. SKU별 "허용 가능 수량"을 Redis에 두고 Lua 한 번으로 확인·차감한다. 여러 SKU는 모두 가능할 때만 차감한다. 같은 요청 key의 재시도는 처음 판정을 그대로 돌려주고 수량을 다시 차감하지 않는다.
3. **Redis는 정본이 아니다.** 수량은 location의 실제 판매 가능 수량으로 채우고 짧은 TTL(기본 5초)마다 다시 채운다. 최종 판정은 항상 location의 조건부 UPDATE이므로 gate가 틀리거나 비어도 초과 예약은 생기지 않는다.
4. Redis 오류·시간 초과는 **거절이 아니라 통과**(fail-open)로 처리한다. 연속 실패가 요청마다 timeout을 기다리지 않도록 최소 circuit breaker(기본 2초)를 둔다.
5. 새 의존성은 `lettuce-core` 하나다. Spring Data Redis 자동구성을 쓰지 않는다(Redis가 없는 환경에서 actuator health가 영향을 받지 않게 하려는 목적).

## 대안과 선택하지 않은 이유

| 대안 | 판단 |
| --- | --- |
| PostgreSQL 안에서 거절 주문의 쓰기를 줄이기(예: 재고 부족 확인 후에만 workflow 생성) | 가장 먼저 시도할 후보였다. 단 location이 별도 서비스·DB라 재고 확인 자체가 원격 호출이므로, 같은 요청마다 location 호출 1회와 앞단 트랜잭션이 남는다. 이 대안은 **구현·측정하지 않았다**. 거절 요청이 location 호출을 만든다는 점은 gate가 제거한다. |
| 앱 프로세스 내 카운터 | replica가 둘 이상이면 각자 100을 센다. 단일 인스턴스 전제에서만 맞는다. |
| hot SKU 재고를 버킷으로 쪼개기(ADR-0054) | 단편화·불변식 대조 작업이 필요하다. gate가 해결하려는 "거절 비용"과 다른 문제를 푼다. |
| Redis를 재고 정본으로 사용 | Redis 장애·유실이 곧 오버셀이 된다. 선택하지 않는다. |

## 검증

- `RedisStockAdmissionServiceTest`(실 Redis, 초기 구현의 2건): 동시 1,000건에서 통과가 시드 수량 100을 넘지 않고 시드 조회는 SKU당 1회라는 것, Redis에 접근할 수 없으면 거절하지 않고 통과시키며 breaker가 열린 뒤에는 요청마다 timeout을 기다리지 않는다는 것.
- `gateRejectionLeavesNoWorkflowRowAndMakesNoRemoteCall`(coordinator 통합): 거절된 요청은 workflow 행도 원격 호출도 만들지 않는다.
- 부하 결과와 장애 주입(Redis 중지)은 [EXP-060](../../03-experiments/EXP-060-hot-sku-admission-gate.md)에 있다. 1,000/s에서 completed/s는 383.0~525.0(기준선 n=3) → 991.3~992.2(Redis gate n=2), 주문당 DB 트랜잭션은 5.75~5.94 → 0.02였고 모든 실행에서 오버셀은 없었다.

## 후속 보강(2026-11-23)

- 반환 경로: 예약 실패·취소 때 `release`, 예약 확정 때 `confirm`을 coordinator가 호출한다(둘 다 멱등 Lua, 확정된 건은 돌려주지 않음). 재시드는 "승인됐지만 예약 전" 수량을 뺀다. `RedisStockAdmissionServiceTest`의 세 번째 테스트가 이 불변식을 확인한다(클래스 합계 3건, `PrecheckStockAdmissionServiceTest` 2건).
- 짝 비교([EXP-062](../../03-experiments/EXP-062-admission-gate-paired-comparison.md))에서 보강 전 빌드의 Redis 팔은 2,000/s의 낮은 두 run(1,501/s, 1,683/s)에서 gate `fail_open` 309회·440회를 동반했다. 보강 jar는 2,000/s 30초 run 13회(진단 3, 가설 6, 짝 비교 4. 가설 중 3회는 command timeout 1초)에서 `fail_open`이 0이어서 재현하지 못했다([EXP-063](../../03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md)). command timeout을 5ms로 낮춘 결함 주입(n=1)에서는 같은 증상이 나왔다(`fail_open` 10,016회, 268/s). timeout 200ms는 유지를 지지하는 자료만 있고 breaker cool-down은 2초 한 점만 측정해 미결이다. 보강 jar 짝 비교(2,000/s, 팔당 n=4)에서 location 호출은 요청당 Redis 0.0042, precheck 0.0133이었다.

## 한계와 재검토 조건

- 측정은 단일 호스트이고 조건당 반복이 적다. EXP-060은 기준선 n=3·Redis gate n=2, EXP-062는 팔당 3~6회, EXP-063은 팔당 4회다. Redis가 같은 호스트에 있고 신뢰구간·부호 검정은 하지 않았다.
- Redis가 죽으면 처리량은 gate가 정상일 때(991.3~992.2/s)로 돌아가지 않는다(1,000/s, 중지 후 완료 속도: breaker 없음 189/s n=1, breaker 적용 595~626/s n=2, [EXP-060](../../03-experiments/EXP-060-hot-sku-admission-gate.md)). 복구 후 breaker가 닫히는 동작은 측정하지 않았다.
- Redis 재시작으로 수량이 사라지면 TTL 이후 location의 실제 수량으로 다시 채워진다. 그 사이의 오거절 가능성은 측정하지 않았다.
- 단일 Redis 노드를 전제한다. cluster에서는 여러 key를 한 스크립트에서 다루므로 hash tag 설계가 필요하다.
- "PostgreSQL 내부 최적화로 충분했는가"에 답하지 못했다. 그 비교가 이 결정의 가장 큰 빈칸이다.
- 운영에서 gate가 fail-open 상태로 지속되는지(`atlas.order.admission.gate{result=fail_open}`)를 관측해 알람 기준을 정해야 한다.
