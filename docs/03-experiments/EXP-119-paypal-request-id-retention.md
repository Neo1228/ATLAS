# EXP-119: PayPal-Request-Id 보관 기간보다 늦은 재시도가 돈을 두 번 움직이는지 확인한다

- 상태: ✓ 완료. 가설과 반증 조건은 첫 실행 전에 커밋했다(`3d7ed7de`). 결과: H1 ✓(현행 코드 3/3에서 부분 환불이 두 번 나감), H2 ✓(수정 뒤 3/3에서 추가 POST 0건), H3 ✓(30일 안 재시도는 같은 키로 완료 3/3). 결정은 [ADR-0108](../02-decisions/adr/ADR-0108-paypal-request-id-reuse-limit.md).
- 날짜: 2026-11-28
- 관련: [#735](https://github.com/Neo1228/ATLAS/issues/735), [EXP-118](EXP-118-multi-instance-assumption-audit.md), [ADR-0106](../02-decisions/adr/ADR-0106-payment-recovery-retry-budget-and-distributed-candidates.md), [ADR-0107](../02-decisions/adr/ADR-0107-multi-instance-shared-state-guards.md), [ADR-0108](../02-decisions/adr/ADR-0108-paypal-request-id-reuse-limit.md)

## 문제 한 문장

결제와 환불 재시도는 처음 만든 `PayPal-Request-Id`를 계속 쓰는데, PayPal이 그 키를 잊은 뒤에 같은 요청을 다시 보내면 돈이 한 번 더 움직이는 엔드포인트가 있는지 확인한 적이 없다.

## 가설과 반증 조건 (첫 실행 전에 커밋)

코드 읽기로 고른 위험 경로: 환불 POST(`/v2/payments/captures/{id}/refund`)는 금액을 명시해 보내고, 결과가 `UNKNOWN`이고 PayPal 환불 id가 없으면 같은 키로 다시 보낸다. 다시 보내는 경로(같은 멱등 키로 환불 재요청, 운영자 재큐)에 나이 상한이 없다.

칸 하나: 2개 주문 품목 중 1개 부분 환불. 첫 POST는 stub이 기록한 뒤 응답 없이 끊는다(`UNKNOWN`). 환불 작업 행의 `created_at`을 46일 앞으로 옮기고 stub이 그 키를 잊게 한다(PayPal의 45일 보관 끝을 흉내). 같은 멱등 키로 환불을 다시 요청한다. 센 값: stub이 받은 환불 POST 수와 stub의 환불 누계(첫 환불 금액의 배수). n = 3(시험을 3번 실행).

- **H1(현행 코드).** 다시 요청하면 POST가 한 번 더 나가고 stub의 환불 누계가 첫 금액의 2배가 된다. 반증: 3회 중 한 번이라도 POST가 1건이거나 누계가 1배.
- **H2(수정 코드: 작업 나이가 30일 이상이면 다시 보내지 않고 `UNKNOWN`으로 둔다).** 같은 칸에서 추가 POST 0건, 누계 1배, 결과 `UNKNOWN`. 반증: 3회 중 한 번이라도 POST 2건이거나 누계 2배.
- **H3(회귀).** 30일보다 젊은 `UNKNOWN` 환불은 지금처럼 같은 키로 다시 보내고 완료된다(기존 시험 `lostRefundResponseStaysUnknownAndSameKeyReplayReusesProviderRequestId`). 반증: 수정 뒤 그 시험이 실패.

## 문서 확인 (요약)

엔드포인트별 보관 기간, 만료 뒤 응답, 인용과 URL은 ADR-0108 표가 정본이다. 요약: Orders v2 주문 생성·authorize·capture는 6시간(최대 72시간, PayPal GitHub OpenAPI 2.32. 개발자 사이트 현행 2.36에서는 문장이 빠짐), capture refund는 45일(API requests 페이지), Payments v2 authorization capture·void는 문서에 값이 없다. 만료 뒤 같은 키에 대한 응답은 어느 문서에도 없다. 돈이 두 번 움직일 수 있는 엔드포인트는 금액을 명시한 refund뿐이다(authorize는 주문당 하나 `ORDER_ALREADY_AUTHORIZED`, capture는 `final_capture: true`라 `AUTHORIZATION_ALREADY_CAPTURED`, void는 돈을 움직이지 않음).

코드 경로(같은 키를 다시 보내는 곳과 최대 나이)도 ADR-0108 표에 있다. refund에는 나이 상한 없는 경로가 둘(같은 멱등 키 재요청, 운영자 재큐 뒤 worker) 있었다.

## 방법

- stub: 통합 시험의 PayPal stub(`PayPalIntegrationTestSupport`, MockWebServer). 환불은 `PayPal-Request-Id`마다 한 번만 처리하고 capture별 환불 누계를 넘으면 `REFUND_AMOUNT_EXCEEDED`·`CAPTURE_FULLY_REFUNDED`로 거절한다. 보관 기간 끝은 `forgetRefundRequestIds()`(키 기억만 지우고 누계는 남김)로 흉내 낸다.
- 칸: 시험 `OrderRefundLifecycleIntegrationTest.unknownRefundOlderThanPayPalRequestIdRetentionIsNotResent`. 수량 2 주문(단가 125원)에서 1개 환불(stub 환불 금액 0.09 USD). 첫 POST는 stub이 처리한 뒤 연결을 끊는다. 작업 행 `created_at`을 46일 앞으로 옮기고 키를 잊게 한 뒤 같은 멱등 키로 환불을 다시 요청한다. 센 값: stub 환불 누계, 환불 POST 수(`providerCalls`).
- 실행: `./gradlew :atlas-application:test --tests '<시험>' --rerun`을 3번. 수가 결정적이라 호스트 잠금 없이 돌렸다(시간 값은 쓰지 않는다).

## 결과

| 칸 | 코드 | n | stub 환불 누계(첫 환불 0.09) | 결과 | 판정 |
|---|---|---|---|---|---|
| H1 | 현행(`f00412d3` + 시험) | 3 | 0.18·0.18·0.18 | 3/3 실패: "expected: 0.09 but was: 0.18" | ✓ H1 섬 |
| H2 | 수정 | 3 | 0.09·0.09·0.09, 추가 POST 0, `PayPal refund was not resent` 로그 1건씩 | 3/3 통과 | ✓ H2 섬 |
| H3 | 수정 | 3 | 기존 시험 `lostRefundResponseStaysUnknownAndSameKeyReplayReusesProviderRequestId` | 3/3 통과 | ✓ H3 섬 |

사전 등록과 다르게 간 것: 첫 H1 3회는 시험 fixture를 수량 1 주문으로 잘못 만들어 전액 환불이 됐다. 그때 두 번째 POST는 stub이 `CAPTURE_FULLY_REFUNDED`(422)로 거절해 3/3 모두 누계 1배였다. 사전 등록 칸(부분 환불)과 다르므로 판정에 쓰지 않고 fixture를 수량 2로 고친 뒤 위 3회를 돌렸다. 이 3회는 "전액 환불은 PayPal의 잔액 검사가 두 번째 환불을 막는다"는 문서 내용과 맞는다.

## 변이 확인

| 변이 | 새 시험 결과 |
|---|---|
| 상한 검사 제거(= 현행 코드) | 실패, 0.18 (H1) |
| workflow가 첫 요청 시각 대신 현재 시각을 넘김 | 실패, 0.18 |
| 상한 30일 → 50일 | 실패, 0.18 |

## 주장 표

| 주장 | 증거 | 확신도 |
|---|---|---|
| PayPal이 키를 잊은 뒤 같은 키로 부분 환불을 다시 보내면 돈이 두 번 나갈 수 있다 | stub 재현 3/3. PayPal 문서는 만료 뒤 동작을 말하지 않는다. 실제 PayPal에서는 45일을 기다려야 해 재현하지 않았다 | 중간(stub 모델과 보수적 가정) |
| 수정 뒤 첫 요청에서 30일이 지난 환불은 다시 보내지 않는다 | H2 3/3, 변이 3개 모두 실패 | 높음 |
| 결제 시도 쪽 재전송(authorize·capture·void)은 만료 뒤에도 돈을 두 번 움직이지 않는다 | PayPal 오류 목록 인용(ADR-0108 표)과 `final_capture: true` 코드 읽기. 측정하지 않음 | 중간(문서 근거) |
| EXP-118의 중복 capture 호출 10~26%는 다른 JVM의 두 요청이 같은 `CAPTURE_PENDING`에서 capture를 예약한 결과다 | 코드 읽기(`OwnerLockRegistry`는 JVM 안, `canExecutePhase`가 `CAPTURE_PENDING` 허용)와 1대 100건 대 2대 110~126건 | 중간(이 실험에서 다시 세지 않음) |

## 한계

- stub의 키 망각은 실제 PayPal 동작이 아니라 "만료 뒤 같은 키는 새 요청"이라는 보수적 가정이다.
- 결제 시도 쪽 엔드포인트의 만료 뒤 응답은 문서 인용만 있고 재현하지 않았다. 우선순위(돈이 두 번 움직일 수 있는 경로만 재현)에 따른 범위 밖 결정이다. 재검토 조건은 ADR-0108에 있다.
- 원자료(gradle 로그, 시험 결과 XML)는 커밋하지 않는다. 표의 수가 정본이다.
