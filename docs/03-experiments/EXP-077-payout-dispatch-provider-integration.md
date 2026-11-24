# EXP-077: 지급 워커가 PostgreSQL과 PayPal 응답 유실 아래에서도 한 번만 보내고 원장에 반영하는가

- 질문: 정산 원장의 지급을 운영자가 승인하면 워커가 PayPal Payouts로 보내고 결과를 원장에 반영한다. 이 전체 경로가, 생성 응답을 잃은 뒤 재시도하는 경우에도 PayPal에 지급을 한 번만 만들고 원장을 정합적으로 마무리하는가?
- 상태: 통합 테스트로 확인. 가짜 PayPal을 쓴 결정적 테스트이며 실제 Sandbox 호출이 아니다. 실제 Sandbox 호출 결과는 [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md)에 있다.
- 날짜: 2026-11-25
- 관련: Issue #486, [EXP-073](EXP-073-paypal-sandbox-payouts-response-shapes.md), [ADR-0052](../02-decisions/adr/ADR-0052-settlement-fee-reserve-payout.md)

## 왜 했는가

EXP-073은 Java 클라이언트와 실제 Sandbox 사이의 응답 해석 결함 하나를 고쳤다. 그러나 클라이언트 다음 단계(워커가 응답을 분류하고, 상태 전이와 원장 분개를 PostgreSQL에 남기는 경로)는 코드를 읽어 판단했을 뿐이다. 조사해 보니 워커를 provider와 DB와 함께 돌리는 테스트가 저장소에 없었다. 워커 테스트는 provider를 mock으로, 원장 테스트는 워커 없이 서비스를 직접 호출한다.

## 방법

- 테스트 `PayoutDispatchProviderIntegrationTest`(PostgreSQL 18.6 컨테이너, Spring 컨텍스트, 실제 `PayoutDispatchWorker`와 `PayPalPayoutsRestClient`, 지급 플래그 켬, 폴링 0.2초).
- 시드: 판매자, 검증된 수취 계정, `ACTIVE` 지급 프로필(미국, USD), 10,000원 결제 분개. 그다음 `payouts.request`와 `PayoutDispatchApprovalService.approve`를 호출한다. 환율은 기존 ECOS 가짜(1,332.4원/USD)를 쓴다.
- PayPal 가짜는 EXP-073에서 실제 Sandbox로 관측한 모양과 순서를 따른다: 생성 응답에는 `items`가 없고 `sender_batch_header`가 중첩된다. 조회는 배치 `PENDING`과 항목 `PENDING`, 이어서 배치 `PROCESSING`과 항목 `SUCCESS`, 이후 배치 `SUCCESS` 순이다. 같은 `sender_batch_id`는 400으로 거부하고 원래 배치는 `details[0].link`에 담는다.
- 응답 유실은 가짜가 배치를 만든 뒤 연결을 끊는 방식으로 넣는다(`DISCONNECT_AFTER_REQUEST`). 클라이언트에는 응답이 닿지 않는다.

## 결과

| 시나리오 | 관찰 | 단언 |
| --- | --- | --- |
| 정상 | 지급이 `CONFIRMED`로 끝남 | 디스패치 `COMPLETED`, 항목 상태 `SUCCESS`, 수수료 2센트 반영, PayPal 배치 1건, 지급 가능 금액 0 |
| 생성 응답 유실 | 첫 응답을 잃은 뒤 같은 `sender_batch_id`로 재시도, 중복 거부에서 원래 배치를 찾아 조회를 이어 감 | 생성 요청 2회, **PayPal 배치 1건**, 중복 거부 1회, 저장된 배치 ID가 원래 배치와 같음, `CONFIRMED`, 지급 가능 금액 0 |
| 배치 `SUCCESS`, 항목 `UNCLAIMED` | 배치가 `SUCCESS`가 돼도 지급은 `UNCLAIMED`로 남음 | `CONFIRMED`가 아님, 디스패치 `COMPLETED`가 아님 |

각 테스트는 3.5~7.7초에 끝난다. 시간은 워커의 재시도 간격(`2^(시도-1)`초에 지터)이 지배한다.

### 수정 전 클라이언트로 되돌린 결과

EXP-073에서 고친 클라이언트를 수정 전(`d0790ca2`)으로 되돌려 같은 테스트를 돌렸다.

- 응답 유실 시나리오가 1.3초 만에 실패했다. 지급은 `FAILED`, 디스패치는 `FAILED/PROVIDER_REJECTED`였다. PayPal에는 배치가 이미 있다.
- 같은 시점의 **지급 가능 금액은 10,000원**이었다. 실패한 지급이 예약을 풀어 판매자의 잔액이 도로 열렸다는 뜻이다.
- 정상 시나리오와 `UNCLAIMED` 시나리오는 수정 전에도 통과했다. 이 둘은 현재 동작을 고정하는 테스트이며 이번 결함을 잡지 못한다.

## 이 결과가 증명하는 것과 아닌 것

- 증명한 것: 이 구성(가짜 PayPal, 실제 워커, 실제 PostgreSQL)에서 생성 응답을 한 번 잃어도 PayPal 배치는 한 번만 만들어지고 지급이 확정된다. 수정 전에는 같은 상황에서 지급이 실패로 기록되고 판매자의 잔액이 열린다. 배치 `SUCCESS`만으로는 확정하지 않는다.
- 증명하지 못한 것:
  - 가짜 PayPal은 EXP-073에서 관측한 응답 모양을 따라 만든 것이다. 실제 PayPal이 모든 경우에 같은 모양을 쓴다는 증거가 아니다. 가짜의 `UNCLAIMED` 항목에는 수수료 필드를 넣지 않았는데, 실제 응답에 수수료가 오는지는 기록하지 않았다.
  - 잔액이 도로 열린 뒤 같은 판매자가 다시 지급을 요청해 PayPal에 두 번째 지급이 만들어지는 것(이중 지급)은 실행하지 않았다. 잔액이 열렸다는 것까지만 확인했다.
  - `UNCLAIMED` 시나리오의 잔액 단언은 `음수가 아님`뿐이라 약하다. 미수령 지급이 잔액을 어떻게 예약하는지는 이 테스트가 고정하지 않는다.
  - 응답 유실은 첫 생성 호출 한 번에 한정했다. 조회 응답 유실, 워커 프로세스 종료, 30일 보호 기간 이후의 `NEEDS_REVIEW` 격상, `BLOCKED`·`RETURNED`·`FAILED` 항목은 다루지 않았다.
  - 운영자 승인 API(HTTP, 인증)는 거치지 않고 서비스를 직접 호출했다.

## 후속 제안(적용하지 않음)

- 조회 응답 유실과 워커 종료를 같은 가짜에서 주입한다.
- 실패 지급이 잔액을 도로 여는 조건이 의도인지 확인한다. 이번에는 PayPal에 배치가 있는데 실패로 기록한 것이 원인이었고 수정됐다. 반대로 정말로 보내지 않았다고 확정된 실패에서만 열리는지는 별도로 검증해야 한다.
