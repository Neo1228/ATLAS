# ADR-0039: 만료 sweep의 batch·시간 상한

- 상태: Proposed
- 날짜: 2026-11-17
- 추적: [Issue #295](https://github.com/Neo1228/ATLAS/issues/295)

## 맥락

`OrderServiceImpl.expireDueOrders`와 `CheckoutPaymentServiceImpl.expireDueCheckouts`는 `@Scheduled(fixedDelay = PT1S)`로 매 초 실행됩니다. 두 job 모두 **상한 없는** 후보 조회로 시작합니다.

- `OrderRepository.findExpiredOrderIds`: `order by checkoutExpiresAt, orderId`만 있고 `LIMIT`이 없습니다.
- `OrderRepository.findExpiredCheckoutIds`: `select distinct checkoutId … order by checkoutId`이며 `LIMIT`이 없습니다.

이후 후보마다 개별 transaction을 열어 row를 잠그고, 상태 전이·재고 해제·쿠폰 해제를 수행합니다. 작업량이 backlog에 비례하므로, 만료 시각이 몰리는 구간(플래시 세일 종료, 여러 구매자의 동시 이탈)에서는 한 tick이 공유 scheduler thread를 오래 점유합니다.

ADR-0031은 이미 이 구조의 비용을 측정했습니다: 만료 주문 300건 sweep 중앙값 ≈ 5.225 s, 같은 조건의 admission ticket 300건 ≈ 20.1 ms. 즉 주문 만료 1건당 약 17 ms가 들며, 1,000건 backlog는 단일 thread에서 약 17초입니다. `fixedDelay`는 이전 실행이 끝난 뒤 다음 실행을 예약하므로, backlog가 tick 처리량보다 크면 만료가 영구히 지연됩니다.

만료되지 않은 주문은 재고 reservation을 계속 점유합니다. 따라서 이 지연은 구매자에게 "재고가 있는데 품절"로 보이는 직접적인 고객 불편으로 이어집니다.

## 결정

두 sweep 모두 **후보 조회에 상한**을 두고, 한 tick의 작업량을 batch 크기와 시간 예산으로 제한합니다.

- `ORDER BY … LIMIT :batchSize`로 후보를 조회합니다. batch 기본값은 100, 허용 범위는 1–1,000입니다.
- 후보를 처리하는 loop는 경과 시간이 시간 예산(기본 2초)을 넘으면 즉시 중단합니다. 남은 후보는 다음 tick에서 이어서 처리합니다.
- 두 상한은 `ExpirySweepPolicy` 값 객체로 표현하고, 설정 키 `atlas.orders.expiry-sweep-batch-size`·`atlas.orders.expiry-sweep-time-budget`으로 노출합니다.
- 기존 poll 간격, 후보별 개별 transaction, 잠금 순서, 상태 전이 규칙은 바꾸지 않습니다. 이 변경은 **작업량 상한**만 추가합니다.

batch와 시간 예산을 **둘 다** 두는 이유는 서로 다른 실패 모드를 막기 때문입니다. batch만 두면 후보 1건 처리가 비정상적으로 느릴 때(잠금 대기) 한 tick이 여전히 길어지고, 시간 예산만 두면 빠른 환경에서 지나치게 많은 후보를 한 tick에 처리해 다른 scheduler job의 지연을 만들 수 있습니다.

## 대안과 절충

- **Set-based 단일 UPDATE:** 후보 전체를 한 SQL로 만료 처리하면 왕복이 줄지만, 후보별로 재고 해제·쿠폰 해제·admission slot 반환·settlement 같은 다른 모듈 호출이 필요합니다. 금전·재고 부수 효과를 한 statement로 옮기는 것은 별도 설계가 필요하므로 채택하지 않았습니다.
- **별도 scheduler pool 추가:** thread를 늘리면 한 tick의 길이는 그대로이고, 만료 지연만 다른 job으로 전가됩니다. ADR-0031의 제한된 pool 원칙과 충돌합니다.
- **poll 간격 단축:** 처리량이 아니라 빈도를 늘리므로 backlog가 있을 때 오히려 경합을 키웁니다.
- **전용 작업 큐 도입:** 결제 복구(ADR-0038)와 같은 durable queue로 옮길 수 있지만, 만료는 `checkout_expires_at`이라는 DB 시각 기준의 단순 조건이라 별도 queue의 이점이 작습니다. backlog 관측 결과 필요해지면 재검토합니다.

선택한 방식의 비용은 backlog가 클 때 만료가 여러 tick에 걸쳐 진행된다는 점입니다. 대신 한 tick의 최대 지연이 bounded되므로 다른 만료 job과 API thread가 굶지 않습니다.

## 결과와 한계

- 한 tick의 후보 수는 `batchSize` 이하, 소요 시간은 시간 예산 근처에서 잘립니다. backlog는 tick마다 감소합니다.
- 처리량(초당 만료 건수)은 후보 1건당 transaction 비용에 의해 결정되며, 이 ADR은 그 비용을 줄이지 않습니다. backlog가 지속적으로 유입되면 batch를 키우거나 만료 처리를 별도 경로로 옮기는 후속 작업이 필요합니다.
- 시간 예산은 `System.nanoTime()` 기준이며, 후보 loop 진입 전에만 검사합니다. 후보 1건 처리 시간이 예산보다 길면 그 tick은 예산을 초과할 수 있습니다.
- 측정은 EXP-079의 로컬 합성 데이터 결과이며 운영 부하 근거가 아닙니다.

## 검증

- `ExpirySweepPolicy` 단위 테스트: batch·시간 예산 허용 범위와 기본값.
- `CheckoutBehaviorTest`: sweep이 설정된 batch 크기를 후보 조회에 전달하는지 검증.
- `ExpiredOrderSweepIntegrationTest`: PostgreSQL에서 만료 후보 60건을 만들고 batch 25로 조회했을 때 정확히 25건만 반환되는지 검증.
- EXP-079: 만료 60,000행 + 미만료 60,000행에서 후보 조회가 상한 없음 8.842 ms/613 buffers, `LIMIT 25` 0.055 ms/3 buffers.
