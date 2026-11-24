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
- Worker는 기본 활성화되며 `initial delay`와 poll 간격은 각각 1초, lease는 60초이다. 테스트는 `atlas.paypal.webhook-retry.worker.enabled=false`와 `PT24H` 간격을 사용해 background claim을 비활성화한다.
- 처리가 lease보다 오래 걸리면 다른 인스턴스가 같은 이벤트를 claim할 수 있다. owner lock, idempotent 처리 및 lease token fencing으로 중복 부작용과 오래된 queue 갱신을 방지한다. lease heartbeat는 제공하지 않는다.
- 영구 amount/status/currency/provider-id mismatch는 재시도하지 않는다. 재시도 가능한 상관관계 결과만 queue에 넣는다.
- queue에는 정규화한 최소 필드만 보존하며 원본 웹훅 콘텐츠를 보존하지 않는다.
- EXHAUSTED 행은 운영자 확인을 위해 유지한다. 자동 정리 정책은 이 결정에서 도입하지 않는다.

## 결과

스케줄러 pool은 기존 세 개 expiry 작업과 독립된 webhook retry 작업을 위해 3개에서 4개로 증가한다. 여러 인스턴스의 조정은 PostgreSQL이 담당하며 JVM 내 스케줄링만으로 분산 lock을 대체하지 않는다. 재시도 경로는 이미 검증된 이벤트만 처리하며 provider API operation을 시작하지 않는다.

## 운영자 EXHAUSTED 조회·재시도 (#279, D-089)

EXHAUSTED는 별도 opt-in HTTP Basic chain으로만 노출한다. chain은 `atlas.payment.webhook-retry.operator.enabled`로 제어하며 `atlas.payment.webhook-retry.operator.username`과 `atlas.payment.webhook-retry.operator.password`를 사용한다. 활성화 시 username이 필요하고 password는 최소 32자여야 한다. 일반 JWT 계정 역할을 확장하지 않는다.

`GET /api/internal/payment/paypal/webhook-retries/exhausted?page={page}&size={size}`는 0부터 시작하는 page와 기본값 20, 최대 100인 size로 읽기 전용 페이지를 반환한다. `page * size` offset은 최대 10,000이며 음수 page 또는 그보다 큰 offset은 400으로 거절해 대규모 OFFSET scan을 막는다. 조회는 EXHAUSTED 행만 대상으로 하며 이벤트 식별·유형, attempt count 및 생성/완료 시각처럼 운영에 필요한 안전한 필드만 선택한다. payload, 리소스/provider 식별자, 오류 문자열, 결제·환불 상태나 금액은 조회하지 않는다.

`POST /api/internal/payment/paypal/webhook-retries/{eventId}/requeue`는 EXHAUSTED 행만 재대기시킨다. `Idempotency-Key`와 비어 있지 않은 operator reason이 필수이며 요청자 principal, 사유, 시각, 키를 불변 audit 행에 기록한다. 이벤트당 수동 재시도는 최대 3회다. 같은 키와 같은 요청은 큐 행 삭제 뒤에도 기존 성공을 재사용하고, 같은 키의 다른 actor/event/reason 요청은 409로 거절한다. PENDING, 누락, 제한 초과는 거절하며 영구 거부 이벤트는 큐에 넣지 않는다. EXHAUSTED 행 잠금·전이·전역 키 audit·횟수 증가는 한 트랜잭션이며 durable audit 기준으로 제한한다. 큐 scheduling 필드만 초기화하고 PaymentAttempt/payment/refund/provider 상태에는 쓰지 않는다. 응답은 202이며 webhook worker만 재처리한다.

운영자 인증은 이벤트의 재처리나 외부 provider 호출 권한을 부여하지 않는다. operator action은 검증된 큐 이벤트만 대상으로 하며 처리 책임은 기존 worker에 남는다.

## 시도별 영속 이력 및 운영자 read model (#281, D-090)

큐 행은 현재 스케줄링 상태이며 성공 시 삭제되고 수동 재시도 시 카운터가 초기화될 수 있으므로 시도 이력의 원본이 아니다. 각 lease 처리 시도를 큐와 독립적으로 영속 보존하고, 큐 삭제나 재등록 이후에도 조회할 수 있어야 한다. 시도 행은 삭제하지 않으며 `CLAIMED`에서 종료 결과로의 조건부 갱신 한 번만 허용한다. 서로 다른 재처리 주기의 시도를 카운터 재사용 때문에 합치지 않는다. 기존 큐 행만으로 과거 시도를 복원하거나 이 기능 도입 전 이력이 완전하다고 주장하지 않는다.

V33은 lease당 한 행을 기록한다. 행은 독립 ID, `event_id`, `event_type`, `attempt_number`, 유일한 내부 상관관계 키 `lease_token`, `outcome`, `claimed_at`, nullable `finished_at`을 가진다. 결과는 `CLAIMED`, `CORRELATION_MISS`, `TRANSIENT_ERROR`, `PROCESSED`, `PERMANENT_REJECT`, `EXHAUSTED`, `LEASE_EXPIRED`로 구분한다. 큐에 대한 FK가 없어 큐 삭제 후에도 이력이 남는다. lease token은 저장 시 상관관계에만 사용하고 운영자 응답에는 포함하지 않는다. 상세 스키마는 [V33 migration](https://github.com/Neo1228/ATLAS/blob/8bfc366bb23647993518591b06c3b9c8f6034adf/ATLAS-application/src/main/resources/db/migration/V33__paypal_webhook_retry_attempt_history.sql)(#529에서 기준 스키마로 통합되기 전 커밋)을 참조한다.

운영자 read model은 기존 [D-089](../../00-governance/decision-log.md#d-089)의 opt-in 설정, 별도 Basic 인증 및 `PAYMENT_WEBHOOK_RETRY_OPERATOR` 권한 경계를 공유한다. `/api/internal/payment/paypal/webhook-retries` 아래에서 요약, PENDING 목록, 이벤트별 이력을 GET으로 제공한다. 조회는 큐 claim·lease 갱신·재시도·감사 쓰기 또는 payment/refund/provider 상태 변경을 하지 않는다. 수동 재시도는 기존 별도 POST와 그 멱등성·감사·횟수 제한을 그대로 따른다.

- 요약은 현재 PENDING/EXHAUSTED 수와 각각 가장 오래된 시각, 만료 lease 수, 최근 24시간의 시도 결과별 수를 제공한다. 24시간 경계와 만료 판정은 서버/DB 시계를 사용하며 만료 lease는 `lease_until IS NOT NULL AND lease_until < CURRENT_TIMESTAMP`이다. 집계는 DB의 grouped SQL projection으로 수행하고 전체 큐나 이력을 애플리케이션 메모리에 적재하지 않는다. 각 조회는 시점별 관측값이며 동시 worker 동작 중 여러 요청 사이의 snapshot 일치를 보장하지 않는다.
- PENDING 목록과 이벤트별 이력은 모두 제한된 페이지 크기와 offset을 검증하는 페이지 조회로 제공한다. offset 상한은 기존 #279와 같은 10,000이다. 이력은 큐 행이 없어도 이벤트 ID로 조회 가능하며 안정적인 정렬을 사용한다. 이력이 없으면 빈 결과로 처리하고 무제한 목록을 반환하지 않는다.
- 응답은 운영에 필요한 명시적 안전 필드만 투영한다. 시도 이력은 결과·시각 등으로 제한하고, 수동 작업 timeline은 명시적 이벤트 분류와 시각만 노출한다. 임의 사유, idempotency key, lease token, 예외 원문, 정규화된 provider ID, webhook payload 및 인증 정보는 노출하지 않는다. 기존 감사 테이블에 사유 등이 보존된다는 사실이 read model에서의 공개를 허용하지 않는다.

이 결정은 새 운영자 조회와 이력의 안전 경계를 정하며 retry 정책이나 결제 확정 규칙을 바꾸지 않는다. 상세 결정은 [D-090](../../00-governance/decision-log.md#d-090), 선행 운영자 작업은 [D-089](../../00-governance/decision-log.md#d-089), 구현 범위는 [#281](https://github.com/Neo1228/ATLAS/issues/281)을 참조한다.
