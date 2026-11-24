# EXP-064: location 요청·응답 유실이 섞여도 주문과 재고 상태가 수렴하는가

- 질문: coordinator가 location을 호출하는 중 요청 유실, 응답 유실, 503이 섞여도 장애가 멈춘 뒤 workflow가 모두 끝나고, 주문이 가진 예약과 location이 가진 예약이 어긋나지 않는가? 장애가 길어지면 workflow가 조용히 사라지지 않고 사람이 볼 수 있는 상태로 남는가?
- 상태: 탐색 실험. 실제 location 서비스가 아니라 계약을 따르는 메모리 모델을 상대로 했다. 코드 동작은 바꾸지 않았고 테스트만 추가했다.
- 날짜: 2026-11-23
- 관련: [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md), [ADR-0064](../02-decisions/adr/ADR-0064-order-inventory-just-in-time-lease-claims.md), [EXP-053](EXP-053-inventory-location-failure-load.md), 테스트 `LocationFaultConvergenceIntegrationTest`

## 왜 했는가

[EXP-053](EXP-053-inventory-location-failure-load.md)은 응답 유실과 JVM 재시작을 각각 1회 확인했다. 장애가 섞였을 때 어긋남이 생기는지, 실패 유형이 겹치는 조합은 확인하지 않았다. 기존 통합 테스트는 장애 하나를 정해 두고 결과를 확인하는 방식이라, 예상하지 못한 조합은 볼 수 없다.

## 환경과 방법

- PostgreSQL 18.6(Testcontainers), 주문 100건, 모든 주문이 location A의 `sku-a` 1개와 B의 `sku-b` 1개를 요구한다. location별 재고는 40이다.
- location은 `MockWebServer` 위의 메모리 모델이다. 멱등 키 재호출, 취소 tombstone(예약 전 취소가 먼저 도착하면 이후 예약을 거절), `claim`·`release`·`restore` 전이, 이미 claim된 재고의 취소 거절(`INVALID_STATE`)을 실제 서비스 구현을 읽고 같은 규칙으로 구현했다. 모델이 실제 서비스와 같은 규칙을 지키는지는 자동으로 검증하지 않았다.
- 장애 폭풍: 요청마다 확률적으로 (a) 적용하지 않고 응답 없이 연결 끊기 8%, (b) 503 5%, (c) 적용한 뒤 응답 없이 연결 끊기 12%를 주입한다. 동시 12개 스레드로 `submit`한 뒤, 같은 방식으로 workflow의 약 50%는 `claim`, 30%는 `cancel`, 20%는 그대로 둔다. 장애를 멈추고 `recoverDue`를 돌려 수렴시킨 뒤 감사한다.
- 감사: (1) 주문이 `RESERVED`·`CLAIMED`라고 믿는데 location이 그렇지 않은 경우, (2) location이 `RESERVED`·`CLAIMED`로 쥔 재고를 가진 주문이 주문 쪽에는 그런 상태가 아닌 경우, (3) `available + 보유 = 초기 재고`가 깨진 경우를 센다.
- 재시도 간격은 테스트를 빠르게 하려고 `base-backoff` 20ms, `max-backoff` 100ms로 줄였다(기본값은 1초, 1분).
- seed 5개(11, 23, 47, 71, 97). 같은 클래스를 5회 반복 실행해 불안정성도 확인했다.

## 결과

| 항목 | 값 |
| --- | --- |
| 폭풍 중 주입된 장애(seed별) | 요청 유실 14~27, 응답 유실 25~32, 503 9~15 |
| 장애 정지 시점에 진행 중이던 workflow | 29~44건(100건 중) |
| 수렴까지 `recoverDue` 라운드 | 모든 seed에서 2 |
| 수렴 시간 | 797~913ms(축소한 backoff 기준) |
| 주문과 location의 어긋남 | 모든 seed에서 0 |
| location이 쥔 주인 없는 예약 | 모든 seed에서 0 |
| `NEEDS_REVIEW`로 남은 workflow | 모든 seed에서 0 |
| 클래스 5회 반복 | 5회 모두 통과 |

지속 장애 시나리오(location B가 계속 503, 주문 30건):

- 30건 모두 `NEEDS_REVIEW`로 식별됐다(10 라운드, 약 2.2초, 축소한 backoff 기준). 사라진 workflow는 없다.
- 그 시점에 location A는 30개 재고를 계속 쥐고 있었고, 주문 쪽은 이를 자기 것으로 알고 있어 어긋남은 없었다.
- B를 복구한 뒤 운영자 해제를 하자 30건이 모두 `FAILED`가 됐고 A의 재고는 40/40으로 돌아왔다.

## 감사가 실제로 결함을 볼 수 있는가 (음성 대조군)

통과만 하는 테스트는 감사가 결함을 볼 수 있는지 알려 주지 않는다. 그래서 "취소가 location에 적용되지 않았는데 성공 응답을 받는" 결함을 일부러 주입했다. 같은 seed 5개에서 감사는 주인 없는 예약 5건을 탐지했다(5회 반복 실행에서 3~8건, 실행마다 다르다. 스레드 순서에 따라 달라지는 값이라 크기에 의미를 두지 않는다).

## 하네스 제약: 요청 유실의 구현

`MockWebServer`(okhttp3 4.12.0)의 `DISCONNECT_AT_START`는 연결을 받는 시점에 한 번만 평가되고 `dispatch()`가 돌려주는 값에서는 무시되어 빈 200 응답이 나간다. 따라서 이 실험에서 "요청 유실"은 요청을 읽되 적용하지 않고 응답 없이 연결을 끊는 정책(`DISCONNECT_AFTER_REQUEST`)으로 구현했다. 위 음성 대조군(적용되지 않은 취소에 성공 응답을 주는 결함)은 감사가 이 상태를 탐지하는지 확인하는 용도다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 계약을 지키는 location을 상대로, 요청 유실·응답 유실·503이 섞인 100건과 같은 조건 5개 seed에서 coordinator가 모든 workflow를 종료시키고 주문과 location의 상태가 어긋나지 않았다. 지속 장애에서는 workflow가 `NEEDS_REVIEW`로 드러났다.
- 증명하지 못한 것:
  - 실제 location 서비스(`InventoryReservationServiceImpl`)와의 연동. 모델과 서비스가 같은 규칙을 지키는지는 코드를 읽어 맞췄을 뿐이다.
  - 네트워크 분리, PostgreSQL 장애·failover, coordinator 프로세스 종료. [EXP-053](EXP-053-inventory-location-failure-load.md)이 JVM 재시작을 1회 다뤘을 뿐이다.
  - 기본 backoff(1초~1분)에서의 수렴 시간. 위 시간은 축소한 값이다.
  - 장애율의 대표성. 8%·5%·12%는 임의로 정한 값이며 실제 장애 분포가 아니다.
  - 실패한 주문 수가 장애 때문인지 재고 부족 때문인지는 분리하지 않았다(`FAILED` 38~45건에는 둘이 섞여 있다. 재고 40에 주문 100건).
- 이 실험은 새로운 결함을 찾지 못했다. 그것이 결함이 없다는 증거는 아니다. 장애 조합을 늘리고 실제 location 서비스를 붙이는 실험이 다음 단계다.

## 재현

```sh
./gradlew :atlas-application:test --tests com.atlas.order.LocationFaultConvergenceIntegrationTest
```

seed별 요약은 `atlas-application/build/fault-convergence/`에 쌓이며 git에서 제외된다. 위 수치는 2026-11-23 실행의 표준 출력에서 옮겼다.
