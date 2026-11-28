# EXP-119: PayPal-Request-Id 보관 기간보다 늦은 재시도가 돈을 두 번 움직이는지 확인한다

- 상태: 진행 중. 가설과 반증 조건은 첫 실행 전에 커밋했다.
- 날짜: 2026-11-28
- 관련: [#735](https://github.com/Neo1228/ATLAS/issues/735), [EXP-118](EXP-118-multi-instance-assumption-audit.md), [ADR-0106](../02-decisions/adr/ADR-0106-payment-recovery-retry-budget-and-distributed-candidates.md), [ADR-0107](../02-decisions/adr/ADR-0107-multi-instance-shared-state-guards.md)

## 문제 한 문장

결제와 환불 재시도는 처음 만든 `PayPal-Request-Id`를 계속 쓰는데, PayPal이 그 키를 잊은 뒤에 같은 요청을 다시 보내면 돈이 한 번 더 움직이는 엔드포인트가 있는지 확인한 적이 없다.

## 가설과 반증 조건 (첫 실행 전에 커밋)

코드 읽기로 고른 위험 경로: 환불 POST(`/v2/payments/captures/{id}/refund`)는 금액을 명시해 보내고, 결과가 `UNKNOWN`이고 PayPal 환불 id가 없으면 같은 키로 다시 보낸다. 다시 보내는 경로(같은 멱등 키로 환불 재요청, 운영자 재큐)에 나이 상한이 없다.

칸 하나: 2개 주문 품목 중 1개 부분 환불. 첫 POST는 stub이 기록한 뒤 응답 없이 끊는다(`UNKNOWN`). 환불 작업 행의 `created_at`을 46일 앞으로 옮기고 stub이 그 키를 잊게 한다(PayPal의 45일 보관 끝을 흉내). 같은 멱등 키로 환불을 다시 요청한다. 센 값: stub이 받은 환불 POST 수와 stub의 환불 누계(첫 환불 금액의 배수). n = 3(시험을 3번 실행).

- **H1(현행 코드).** 다시 요청하면 POST가 한 번 더 나가고 stub의 환불 누계가 첫 금액의 2배가 된다. 반증: 3회 중 한 번이라도 POST가 1건이거나 누계가 1배.
- **H2(수정 코드: 작업 나이가 30일 이상이면 다시 보내지 않고 `UNKNOWN`으로 둔다).** 같은 칸에서 추가 POST 0건, 누계 1배, 결과 `UNKNOWN`. 반증: 3회 중 한 번이라도 POST 2건이거나 누계 2배.
- **H3(회귀).** 30일보다 젊은 `UNKNOWN` 환불은 지금처럼 같은 키로 다시 보내고 완료된다(기존 시험 `lostRefundResponseStaysUnknownAndSameKeyReplayReusesProviderRequestId`). 반증: 수정 뒤 그 시험이 실패.
