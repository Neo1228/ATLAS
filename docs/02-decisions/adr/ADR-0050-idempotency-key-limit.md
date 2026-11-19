# ADR-0050: checkout Idempotency-Key 상한을 221자로 맞춘다

- 상태: Accepted — 구현 추적: [#418](https://github.com/Neo1228/ATLAS/issues/418)
- 날짜: 2026-11-20
- 관련 결정: [ADR-0015](ADR-0015-initial-payment-progression.md)

## 문제

같은 checkout 리소스인데 진입점마다 Idempotency-Key 상한이 달랐다.

| 동작 | 상한 | 위치 |
|---|---:|---|
| checkout 생성 | 256 | `CartCheckoutServiceImpl.validate` |
| checkout reconcile | 221 | `CheckoutPaymentServiceImpl.reconcile` |
| checkout cancel | 221 | `CheckoutPaymentServiceImpl.cancel` |

222~256자 키로 checkout 생성을 성공시킨 뒤, 같은 키로 결제 상태 확인이나 취소를 호출하면 `INVALID_REQUEST`로 거절된다. 생성이 성공했으므로 클라이언트는 그 키가 유효하다고 학습하는데, 복구 경로만 막힌다.

## 결정

checkout 생성 상한을 **221자**로 낮춘다. 세 진입점이 `CheckoutIdempotencyKeys.MAX_LENGTH` 하나를 공유한다.

## 왜 256으로 올리지 않는가

221자는 **ADR-0015가 정한 값**이며 임의의 숫자가 아니다. 내부 suffix(`:void`, `:cancel-reconcile` 등)를 붙여도 `VARCHAR(255)` 컬럼에 들어가도록 34자를 남긴 값이다.

- 256으로 통일하면 suffix 여유가 사라져 컬럼 폭과 충돌한다.
- reconcile·cancel·payment 세 경로를 함께 256으로 올려야 하고, 컬럼 폭도 넓혀야 한다.
- 낮추는 방향은 이미 세 경로가 합의한 값에 하나를 맞추는 일이다.

## 처음 세운 가설이 틀렸던 기록

최초에는 "환불은 128자라 129~256자 키로 만든 주문은 환불 불가"라고 진단했다. 재검증 결과 **틀렸다**:

- 환불 키는 호출자가 새로 공급하며 checkout 키를 재사용하지 않는다.
- 128자 초과 환불 키는 환불 진입점에서 먼저 거절되므로 저장 컬럼에 도달하지 않는다.
- 221자 결제 키는 DB에 저장되지 않는다. `stableRequestId`가 `attemptId + ":" + operation`의 38자 UUID 해시를 만든다.

따라서 "DB에서 잘리거나 오류"라는 서술도 틀렸다. 실제 결함은 위 표의 비대칭 하나였고, 이 ADR은 그 하나만 다룬다. 이 정정 기록을 남기는 이유는, 잘못된 진단으로 221자를 128자로 좁히면 **ADR-0015의 계약을 근거 없이 축소**하기 때문이다.

## 실패 경로

| 상황 | 동작 |
| --- | --- |
| 221자 이하 | 생성·reconcile·cancel 모두 통과 |
| 222자 이상 | 생성 시점에 400 `INVALID_REQUEST` |
| 기존 222~256자 키로 만들어진 checkout | 이 변경 후에도 reconcile 은 221자 제한으로 막힌다 → 배포 전 확인 필요 |

## 검증

- `CheckoutBehaviorTest.checkoutCreationRejectsKeysThatReconcileAndCancelWouldRefuse`: 수정 전 실패(결함 재현), 수정 후 통과.
- `CheckoutBehaviorTest.checkoutCreationAcceptsTheSharedMaximumKey`: 221자 경계 통과, 222자 거절.

## 한계

- 로컬 개발 DB에서 128자 초과 checkout 키는 0건이었다. 운영 DB는 확인하지 못했다.
- 기존 장기 키 데이터가 있다면 그 checkout 의 복구 경로를 어떻게 열지 이 ADR은 결정하지 않는다. 배포 전 확인 후 별도 판단이 필요하다.
