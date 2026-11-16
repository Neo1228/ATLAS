# ADR-0032: PayPal 웹훅 상관관계 재시도 큐

- 날짜: 2026-11-16
- 상태: 승인
- 이슈: [#277](https://github.com/Neo1228/ATLAS/issues/277)

## 배경

PayPal 웹훅이 로컬 결제 시도와 상관관계를 만들 수 있기 전에 도착할 수 있다. 재시도 가능한 응답은 안전하지만 제공자 전달 일정에 의존하며 복구 기회를 놓칠 수 있다. 원본 웹훅을 저장할 필요는 없으며 서명이나 관련 없는 제공자 데이터를 보관할 위험이 있다. 여러 애플리케이션 인스턴스가 동일한 재시도 작업을 동시에 소유하지 않아야 한다.

## 결정

서명 검증 후 명시적으로 재시도 가능한 상관관계 결과만 `paypal_webhook_retry`에 기록한다. 행에는 나중에 `PaymentWebhookEvent`를 재구성하는 데 필요한 이벤트 식별자와 정규화 필드만 둔다. 원본 body, header, signature, credential은 저장하지 않는다. 성공 응답 전에 insert가 commit되어야 한다. 저장 오류는 서버 오류로 전파되어 제공자가 재전송할 수 있게 한다.

스케줄러는 PostgreSQL `FOR UPDATE SKIP LOCKED` CTE와 60초 lease로 due row 한 건을 claim한다. 고유 lease token은 만료된 worker가 완료/재예약을 덮어쓰지 못하게 한다. 기존 payment event idempotency와 owner lock을 유지한다. worker는 이미 검증된 이벤트에 대해 DB 상관관계 및 도메인 처리만 재실행하며 provider operation은 수행하지 않는다.

## 상태 머신

```text
검증된 웹훅
  ├─ accepted / duplicate ───────────────> 일반 처리; 재시도 행 없음
  ├─ 영구 mismatch ──────────────────────> terminal 거부; 재시도 행 없음
  └─ 재시도 가능한 상관관계 mismatch ───> PENDING 영속화 후 성공 응답

PENDING (attempt_count 0)
  └─ due + lease claim ──────────────────> PENDING (attempt_count + 1)
       ├─ 성공 / duplicate / 영구 mismatch -> 재시도 행 삭제
       ├─ 여전히 재시도 가능 / 일시 오류 -> backoff 예약 및 lease 해제
       └─ 8번째 시도도 해결/실패되지 않음 -> EXHAUSTED; 진단 정보와 완료 시각 보존

EXHAUSTED는 terminal이며 claim 대상에서 제외한다. 운영자는 event id, attempt_count,
last_error, finished_at을 확인할 수 있다. lease가 만료된 미완료 PENDING 행은 다시 claim 가능하다.
```

## 제한 및 예외

- worker 최대 시도 횟수는 8회이며, 최초 웹훅 전달은 worker 시도 횟수에 포함하지 않는다.
- batch 크기는 1이다. 느린 이벤트 처리 중 뒤의 이벤트 lease가 만료되는 상황을 방지한다.
- 재시도 간격은 attempt 1부터 1, 2, 4, 8, 16, 32, 64초이며 최대 300초로 제한한다.
- 기본 poll 간격은 1초, lease는 60초이다.
- 처리가 lease보다 오래 걸리면 다른 인스턴스가 같은 이벤트를 claim할 수 있다. owner lock, idempotent 처리 및 lease token fencing으로 중복 부작용과 오래된 queue 갱신을 방지한다. lease heartbeat는 제공하지 않는다.
- 영구 amount/status/currency/provider-id mismatch는 재시도하지 않는다. 재시도 가능한 상관관계 결과만 queue에 넣는다.
- queue에는 정규화한 최소 필드만 보존하며 원본 웹훅 콘텐츠를 보존하지 않는다.
- EXHAUSTED 행은 운영자 확인을 위해 유지한다. 자동 정리 정책은 이 결정에서 도입하지 않는다.

## 결과

스케줄러 pool은 기존 세 개 expiry 작업과 독립된 webhook retry 작업을 위해 3개에서 4개로 증가한다. 여러 인스턴스의 조정은 PostgreSQL이 담당하며 JVM 내 스케줄링만으로 분산 lock을 대체하지 않는다. 재시도 경로는 이미 검증된 이벤트만 처리하며 provider API operation을 시작하지 않는다.
