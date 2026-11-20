# EXP-048: 오류 응답 상세 문구 정책과 반품 환불 재시도

**추적 이슈:** [#437](https://github.com/Neo1228/ATLAS/issues/437)

**상태:** 코드 감사 + 회귀 테스트

## 두 가지 발견

DB·성능 작업을 마치고 제품·API 표면을 점검하는 과정에서 두 가지를 찾았다. 하나는 **내가 앞서 잘못 고친 것**이고, 하나는 **고치지 않기로 한 것**이다.

## 1. 반품 환불은 실패하면 영구히 재시도 불가 (수정함)

### 문제

`OrderRefundServiceImpl` 은 반품 환불의 idempotency key 를 **파생값**으로 만든다.

```java
return new OrderRefundRequest(row.sellerId(), row.orderId(), "return-" + returnId, passed);
```

그리고 provider 호출이 실패하면 상태를 `FAILED` 로 기록한다.

```java
} catch (PaymentException exception) {
    refunds.updatePaymentStatus(reserved.refundId(), OrderRefundPaymentStatus.FAILED);
    throw exception;
}
```

payment 계층은 terminal `FAILED` 를 즉시 반환한다(`executeRefundOperation`).

```java
if (state == COMPLETED || state == FAILED) { markRefundForApplication(...); return; }
```

**결과:** 판매자가 같은 검수를 다시 호출해도 같은 키가 조회되어 terminal `FAILED` 가 반환되고, 실제 송금은 다시 시도되지 않는다. 구매자가 받아야 할 돈이 영구히 막힌다.

### 내가 앞서 남긴 잘못된 주석

PR #438 에서 나는 이렇게 적었다.

> "여기서 충돌로 남겨 반품을 RECEIVED 에 두면, **새 idempotency key 로 다시 시도할 수 있다.**"

**틀렸다.** 키는 파생값이라 호출자가 고를 수 없다. 나는 재시도 가능성을 확인하지 않고 주석을 썼다. 감사 에이전트가 이 모순을 잡았다.

### 수정

1. **재시도 가능한 실패는 영구화하지 않는다.** `PAYMENT_PROVIDER_DISABLED`/`UNAVAILABLE` 은 상태를 바꾸지 않는다. provider 가 잠깐 꺼져 있던 것만으로 돈이 막히면 안 된다.
2. **영구 실패는 새 회차 키를 만든다.** `countByKeyPrefix(baseKey + "-r")` 로 기존 회차를 세고 `"return-<id>-r<n>"` 을 만든다.

### 접두사 충돌 버그 (구현 중 발견)

처음 구현에서 `countByKeyPrefix(baseKey)` 를 썼다. `LIKE 'return-<uuid>%'` 는 **다른 반품의 키도 매칭**할 수 있어 회차가 틀어졌고, 실제로 기존 반품 테스트 21개가 깨졌다.

`"-r"` 구분자까지 포함해 세도록 고쳤다. 회귀 테스트 `returnRefundRetryKeysDoNotCollideWithOtherReturns` 가 이를 고정한다.

## 2. 오류 상세 문구는 계속 숨긴다 (고치지 않음)

### 발견

`GlobalExceptionHandler` 는 도메인 예외 메시지를 전부 버린다.

```java
private static ResponseEntity<ProblemDetail> response(
        HttpStatusCode status, String code, String ignoredInternalDetail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, koreanDetail(status, code));
```

클라이언트는 `"현재 상태에서는 요청을 처리할 수 없습니다."` 만 받는다. 무엇을 고쳐야 하는지 알 수 없다.

### 왜 고치지 않았나

도메인 메시지를 노출하도록 바꿨더니 **기존 테스트가 실패했다.**

```java
assertThat(invalid.getBody().getDetail()).isEqualTo("요청 정보를 확인해 주세요.");
```

이 테스트는 `"invalid ticket request"` 같은 내부 메시지가 **숨겨짐을 명시적으로 검증**한다. 이는 의도된 보안 계약이다. 내부 구현·SQL·클래스명이 클라이언트로 새지 않게 하는 설계다.

**내 변경은 이 계약을 깨뜨렸다.** 메시지를 4xx 에서만 노출하고 내부 흔적을 필터링하는 절충안을 시도했지만, 근본적으로 "어떤 메시지가 안전한가"를 자동 판정하는 것은 신뢰할 수 없다.

따라서 **철회했다.** 대신 이 결정을 기록한다.

### 남은 문제 (해결하지 않음)

운영자는 여전히 `CONFLICT` 하나로 여러 원인을 구분할 수 없다. 개선 방향은 **코드 세분화**다 — 메시지를 여는 대신 `REFUND_PROVIDER_FAILED`, `PAYMENT_ALREADY_SETTLED` 처럼 클라이언트가 분기할 수 있는 코드를 늘리는 것. 이는 API 계약 변경이므로 별도 결정이 필요하다.

## 검증

- `OrderRefundLifecycleIntegrationTest`: 22 tests, 0 failures
- 접두사 충돌 회귀 테스트 포함
- 전체 check 통과

## 한계

- provider 실패를 **인위적으로 재현하는 통합 시나리오는 만들지 않았다.** stub provider 동작에 의존해 불안정하기 때문이다. 대신 키 계산 규칙을 단위 수준에서 고정했다. 실제 provider 실패 경로의 end-to-end 검증은 별도 과제다.
- 오류 코드 세분화는 하지 않았다. 위에 적은 이유로 계약 결정이 선행돼야 한다.
