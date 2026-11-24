# EXP-065: 같은 workflow에 claim과 cancel이 동시에 들어오면 두 쪽 상태가 어긋나는가

- 질문: 같은 주문 workflow에 claim 1건과 cancel 2건(중복 취소 포함)이 동시에 도착해도 주문 쪽 상태와 location 쪽 예약이 어긋나지 않고, 재고가 한 번만 움직이는가?
- 상태: 탐색 실험. [EXP-064](EXP-064-location-fault-convergence.md)와 같은 메모리 location 모델을 쓴다. 코드 동작은 바꾸지 않았다.
- 날짜: 2026-11-23
- 관련: [ADR-0064](../02-decisions/adr/ADR-0064-order-inventory-just-in-time-lease-claims.md), [EXP-064](EXP-064-location-fault-convergence.md), 테스트 `LocationFaultConvergenceIntegrationTest.concurrentClaimAndCancelOnTheSameWorkflowNeverDiverge`

## 왜 했는가

기존 테스트는 claim 뒤 취소, 중복 취소를 순차로만 확인한다. 둘이 동시에 도착하는 경쟁은 확인한 적이 없다. location은 claim된 재고의 cancel을 `INVALID_STATE`로 거절하므로, claim이 먼저 이기면 coordinator가 restore로 바꿔 재고를 되돌려야 한다. 이 경로가 동시성에서도 맞는지가 질문이다.

## 방법

- 주문 40건을 미리 `RESERVED`로 만든 뒤, workflow마다 `claim` 1건과 `cancel` 2건을 12개 스레드로 한꺼번에 보낸다. 호출 순서는 seed 7로 섞어 고정했다.
- 두 가지로 실행한다: 장애 없음, 장애 폭풍 동시 주입(EXP-064와 같은 확률).
- 확인: 예외가 새지 않을 것, 진행 중으로 남은 workflow 0, 주문과 location의 어긋남 0, 주인 없는 예약 0, 재고 보존, `CANCELLED` workflow는 어느 location에도 예약을 남기지 않을 것, `CLAIMED` workflow는 두 location 모두 `CLAIMED`일 것.

## 결과

| 조건 | 최종 상태 | claim이 먼저 이겨 `CLAIMED` 응답을 받은 workflow | 어긋남 | 주인 없는 예약 | 남은 재고 보유 |
| --- | --- | --- | --- | --- | --- |
| 장애 없음 | `CANCELLED` 40 | 11 (5회 모두 11) | 0 | 0 | 0 |
| 장애 폭풍 | `CANCELLED` 40 | 6~8 (5회) | 0 | 0 | 0 |

5회 반복 모두 통과했다.

## 해석과 한계

- claim이 이긴 뒤에도 cancel이 재고를 되돌려 40건 모두 `CANCELLED`로 수렴했다. 즉 claim 우선 경로의 restore 보상이 이 경쟁에서 동작했다.
- **장애 없는 경우의 11은 타이밍 다양성의 증거가 아니다.** 호출 순서를 seed로 고정했고 조건이 같아 5회 모두 11이 나왔다. 스레드 타이밍이 매번 달랐다고 말할 수 없다. 장애를 섞은 경우의 6~8 변동만 타이밍과 장애의 영향이 섞인 값이다.
- 이 테스트는 cancel이 항상 두 번 들어오므로 끝은 항상 `CANCELLED`이다. cancel이 없는 경로(`CLAIMED`로 끝나는 동시성)와 claim 응답 유실 뒤 재시도는 이 실험이 다루지 않는다. `CLAIMED` 분기의 단언은 이 입력에서는 실행되지 않았다.
- location은 메모리 모델이라 실제 서비스의 행 잠금 동작은 검증하지 않았다. 새 결함은 발견되지 않았고, 결함이 없다는 증거는 아니다.

## 재현

```sh
./gradlew :atlas-application:test --tests 'com.atlas.order.LocationFaultConvergenceIntegrationTest'
```
