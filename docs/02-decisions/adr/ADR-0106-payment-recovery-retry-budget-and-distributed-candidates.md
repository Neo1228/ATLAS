# ADR-0106: 결제 복구는 창이 끝내고 버려진 claim은 따로 세며, 분산 배포 후보 다섯은 재검토 조건과 함께 보류한다

- 상태: Accepted(사용자 지시 "증거가 탄탄한 업그레이드만 적극 채택"에 따른 에이전트 결정. 사용자 승인 여부는 확인하지 못함. C4의 배포 구성은 사용자 결정으로 남김)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0038](ADR-0038-durable-payment-recovery-workflow.md)(복구 워크플로), [ADR-0047](ADR-0047-abandoned-approval-deadline.md), [ADR-0067](ADR-0067-event-log-kafka-relay.md), [ADR-0072](ADR-0072-infrastructure-inventory-and-assumptions.md), [ADR-0077](ADR-0077-keep-reorder-idempotency-records.md)
- 근거: [EXP-117](../../03-experiments/EXP-117-distributed-upgrade-candidates-reproduction.md), 이슈 [#729](https://github.com/Neo1228/ATLAS/issues/729)

## 문제

전제는 여러 app 인스턴스, rolling 배포와 강제 종료, DB failover와 replica, app·location·PayPal 사이 지연과 분할이다. 남은 후보 여섯(F1·F2·F3·G1·C4·E3) 중 무엇이 현재 코드에서 실제로 재현되는지 가려야 했다.

F1은 재현됐다. 결제 복구 기본값(시도 8회, backoff 1초부터 두 배)에서 PayPal 503이 이어지면 job은 8번째 claim에서 `NEEDS_REVIEW`가 됐고, 그때까지 기록된 backoff 합은 가상 시각 83.1~116.1초였다(5회. 지터 때문에 이론 범위는 63.5~127초). ADR-0038이 적은 "15분 상한·24시간 창"에 닿지 못했다. 그래서 2분 남짓 넘는 PayPal 장애나 분할은 그 동안의 미확정 결제를 모두 운영자 수작업으로 보낸다. 같은 계수는 처리 중 강제 종료로 버려진 claim도 세고 있어서, 한도를 그냥 올리면 claim할 때마다 프로세스를 죽이는 job도 그만큼 오래 인스턴스를 죽인다(H2).

## 결정

1. 결과를 기록한 재시도는 창(`max-age`, 24시간)이 끝낸다. `max-attempts` 기본값을 202로 올린다. 202는 창과 backoff 상한에서 나온다: 지연은 지수값의 절반 이상이라 10번째 시도까지 최솟값 합 511.5초, 이후 15분 상한의 절반 450초씩이고, 511.5 + (M−11) × 450 ≥ 86,400을 만족하는 가장 작은 M이 202다. 이제 시도 한도는 창보다 먼저 닿지 않는 안전 상한이고, `PaymentRecoveryWorkerTest`가 이 관계를 main 기본값으로 확인한다. 시작할 때 이 관계가 깨진 구성이면 경고를 남긴다.
2. 결과를 쓰지 못하고 lease가 만료된 claim은 V12의 `abandoned_claim_count`로 따로 세고 `max-abandoned-claims`(기본 8)에서 `ABANDONED_CLAIMS`로 멈춘다. 만료된 lease가 남은 채 다시 claim되면 1을 더하고, sweep은 지금 만료된 lease까지 센다. 운영자 requeue는 두 계수를 0으로 돌린다.
3. F2·F3·G1·C4·E3은 채택하지 않는다. 후보마다 근거와 재검토 조건은 아래 표에 둔다.

## 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 현행 유지(8회) | H1 재현: 가상 83.1~116.1초(5회)에 수작업 전환. ADR-0038이 적은 창과 상한이 쓰이지 않는다 |
| `max-attempts`만 202로 | H2: 버려진 claim도 같은 계수라 독이 든 job이 인스턴스를 202번까지 죽인다. 변이 확인에서 이 구성은 H3(b) 시험이 실패한다 |
| 한도를 PayPal authorization 만료(3일 honor)에 맞춤(F1 원안) | 창 24시간이 honor 72시간보다 짧아 창이 먼저 끝난다. 창이 실제로 쓰이게 된 지금 만료 연동이 더할 보호가 없다. `authorization_expires_at`은 이미 검토 목록에 보인다(V3) |
| 시도 한도 없이 창만 | 창 설정 오류(예: 매우 긴 값) 때 상한이 사라진다. 202는 기본 구성에서 판정에 끼지 않으면서 상한으로 남는다 |

## 결과

- 시험 `PaymentRecoveryRetryBudgetIntegrationTest`(PostgreSQL, 실제 worker, PayPal stub 503, 운영 기본값을 main `application.yml`에서 읽음): 6시간 장애 뒤 회복하면 자동으로 진행, 3일 장애는 창에서 `RECOVERY_WINDOW_EXPIRED`(2회: claim 133·140회, 가상 86,475·86,526초), 버려진 claim 8회에서 `ABANDONED_CLAIMS`, 기록된 재시도는 버려진 claim으로 세지 않음.
- 버려진 claim은 "lease 60초 안에 결과를 쓰지 못한 claim"이다. 강제 종료뿐 아니라 60초 넘는 GC 정지·DB 분할(인스턴스 여럿)에서도 생긴다. 살아 있는 느린 worker가 나중에 결과를 써도 한 번만 세고, 그 결과는 lease 토큰에 막혀 다시 잡은 인스턴스의 기록을 덮지 않는다(시험으로 확인). 같은 job에 그런 정지가 8번 겹치면 독이 든 job이 아니어도 멈추는 것이 이 한도의 비용이다.
- 장애가 길면 job마다 PayPal 조회가 7.5~15분에 한 번 이어진다(1,000건이면 초당 약 1~2회).

## 롤백

- 값만: `ATLAS_PAYMENT_RECOVERY_MAX_ATTEMPTS=8`이면 예전 동작(약 83~116초에 수작업). 버려진 claim 한도는 그대로 남는다.
- 코드: 이 변경을 되돌려도 V12 열은 기본값 0이라 예전 코드와 함께 둘 수 있다. 열을 지우려면 새 마이그레이션으로 지운다(적용한 마이그레이션은 고치지 않는다).

## 재검토 조건

- `ABANDONED_CLAIMS`가 한 주에 1건이라도 나오면 그 job의 종료 원인부터 본다. 정상 배포에서 나오면 한도 8과 lease 60초를 다시 잰다.
- PayPal 장애 중 복구 조회가 PayPal 429를 받으면 15분 상한을 다시 정한다.
