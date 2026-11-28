# 분산 환경 준비 상태 점검

**추적 이슈:** [#356](https://github.com/Neo1228/ATLAS/issues/356)

**상태:** 조사 기록 · 즉시 도입하지 않음

## 목적

ATLAS는 단일 모듈러 모놀리스로 시작했습니다. EXP-037/038에서 application replica를 3대로 늘려 측정했고, 그 과정에서 **여러 인스턴스에서만 드러나는 가정**이 몇 곳 있음을 확인했습니다.

이 문서는 그 지점을 **기록만** 합니다. 지금 도입하지 않는 이유는 측정으로 병목이 확인된 것과, 아직 확인되지 않은 것을 구분하기 위해서입니다.

## 결론 요약

| 영역 | 현재 상태 | 다중 인스턴스 영향 | 판단 |
| --- | --- | --- | --- |
| HTTP 세션 | `STATELESS` (JWT) | 없음 | 안전 |
| 만료 sweep | advisory lock으로 1개만 수행 | 없음(중복 조회 제거됨) | 안전 |
| payment recovery | lease + `SKIP LOCKED`, `PROVIDER`/`APPLY` stage | 없음(중복 호출은 provider request-id, 중복 owner 적용은 업무 키로 흡수) | 안전 |
| webhook retry | `SKIP LOCKED` + lease | 없음 | 안전 |
| **owner 단위 직렬화** | `OwnerLockRegistry` = JVM 전역 `synchronized` | **인스턴스 간 무효** | 주의 |
| **admission sweep cursor** | 인메모리 `expiryCursor` | replica마다 다른 위치에서 스캔 | 주의(효율만) |
| **벽시계 직접 사용** | `Instant.now()` 다수 | 노드 간 시계 차이 | 주의 |
| **통화 가정** | KRW↔USD 하드코딩 | 다통화 확장 시 재설계 | 제약 |
| 로컬 파일 저장 | 없음 | 없음 | 안전 |
| 읽기 확장 | primary만 사용 | 읽기 부하가 primary에 집중 | 확장 지점 |

## 1. owner 단위 직렬화는 인스턴스 간에 유효하지 않다

`OrderServiceImpl`과 `PaymentServiceImpl`은 같은 주문·결제에 대한 동시 요청을 `OwnerLockRegistry`로 직렬화합니다.

```java
// OrderServiceImpl.java:63
private static final OwnerLockRegistry ORDER_LOCKS = new OwnerLockRegistry();
// OwnerLockRegistry.java:18
synchronized (entry) { return action.get(); }
```

`static` + `ConcurrentHashMap` + `synchronized` 조합은 **한 JVM 안에서만** 유효합니다. replica 2대가 같은 주문에 동시에 결제를 제출하면 두 스레드가 병렬로 진행합니다.

### 다중 인스턴스에서 실제로 남는 위험

이 락이 무효해도 **DB가 최종 방어선**입니다. 경로별로 확인한 사실은 다음과 같습니다.

| 경로 | 인메모리 락 | DB 락 | 판정 |
| --- | --- | --- | --- |
| 결제 제출 `submitPayment` | 있음 | `lockOrder` = `PESSIMISTIC_WRITE` (L173) | 상태 전이는 안전, provider 중복 호출 가능 |
| 결제 대조 `reconcilePayment` | 있음 | `lockOrder` 호출 있음(L307) | 위와 같음 |
| 주문 취소 `cancel` | 있음 | `lockOrder` 호출 있음(L279) | 위와 같음 |
| 주문 만료 sweep | 없음(advisory lock) | 조건부 `UPDATE` + `PESSIMISTIC_WRITE` | 안전 |

즉 **중복 상태 전이는 DB 락과 조건부 갱신이 막고**, 남는 것은 **중복 provider 호출과 그로 인한 락 대기**입니다. provider 호출은 고정 request-id(`phaseRequestId`)로 멱등이므로 금전 사고로 이어지지는 않습니다.

### 다중 인스턴스에서 달라지는 것

한 인스턴스에서는 두 번째 요청이 인메모리 락에서 대기 후 즉시 반환됩니다. 여러 인스턴스에서는 두 요청 모두 DB까지 내려가 `PESSIMISTIC_WRITE` 경쟁과 재시도를 유발합니다. 부하가 늘면 지연이 늘고, 드물게 `CONFLICT` 응답이 사용자에게 보일 수 있습니다.

**도입 검토 방향(지금 하지 않음):** `pg_advisory_xact_lock`을 owner 키에 걸어 인메모리 락을 대체하거나, 조건부 `UPDATE … WHERE state = expected`로 전이를 명시합니다. 다만 락 대기가 커넥션을 점유하므로 **2대 이상에서 경쟁 빈도를 먼저 측정**해야 합니다. 현재 측정에서 이 경쟁은 드롭의 주원인이 아니었습니다.

## 2. admission sweep cursor는 replica별로 다르다

```java
// AdmissionServiceImpl.java:52
private JdbcQueueRepository.DueTicketCursor expiryCursor;
```

이 cursor는 keyset 페이지네이션 위치를 기억해 다음 tick에서 이어 읽기 위한 것입니다. 인메모리이므로 replica마다 자기 위치에서 시작합니다.

**영향:** 정확성 문제는 아닙니다. 만료 처리는 상태 조건부 갱신이라 중복 실행해도 한 번만 반영됩니다. 다만 **같은 구간을 여러 replica가 중복 스캔**하므로 낭비가 replica 수에 비례합니다. EXP-037에서 replica 3대일 때 sweep 실행이 2분간 358회 관측된 것과 같은 성질입니다.

**도입 검토 방향:** cursor를 DB에 두거나(작은 테이블 1행), cursor 없이도 효율적인 인덱스 조건으로 바꾸는 방법이 있습니다. 트래픽이 낮은 지금은 우선순위가 낮습니다.

## 3. 벽시계를 직접 읽는 곳이 있다

`Clock`을 주입해 쓰는 곳도 많지만, 다음은 `Instant.now()`를 직접 호출합니다.

- `PaymentServiceImpl` 3곳(320, 400, 450)
- `PayPalBalanceReportRunIngestionService`, `PayPalBalanceReportOperatorService`

**다중 노드 영향:** 노드 간 시계가 수 초 어긋나면 `updatedAt`, lease 만료 판정, 리포트 대조 시각이 노드마다 달라집니다. lease 기반 worker는 `clock_timestamp()`(DB 시계)를 쓰므로 안전하지만, 애플리케이션이 만든 timestamp는 노드 시계입니다.

**도입 검토 방향:** 새 코드는 `Clock` 주입을 유지하고, 기존 `Instant.now()` 호출을 `Clock`으로 통일합니다. 이는 다중 인스턴스뿐 아니라 테스트 결정성에도 도움이 됩니다.

## 4. 통화는 KRW와 USD 두 개로 고정되어 있다

```java
// PaymentProvider.java:10
default String providerCurrency() { return "KRW"; }
// PayPalPaymentProvider.java:34
public String providerCurrency() { return "USD"; }
// OrderRefundServiceImpl.java:273
|| !"KRW".equals(payment.currency())
```

결제는 KRW로 기록하고 provider에는 USD로 보내는 구조이며, FX 스냅샷이 그 사이를 잇습니다. 이는 **의도된 설계**이고 현재 요구사항에 맞습니다.

**확장 시 걸림:** 다통화 판매(예: USD 직접 판매, EUR 추가)를 하려면 `currency`가 이미 컬럼으로 있으므로 스키마 변경은 작지만, 환불·정산·리포트가 모두 "KRW 원장 + USD provider" 쌍을 가정합니다. 통화별 원장 분리나 환율 이력 관리가 필요해집니다.

**지금 하지 않는 이유:** 현재 요구사항에 다통화가 없습니다. 근거 없는 선제 일반화는 코드 복잡도만 늘립니다.

## 5. 읽기 확장 지점

현재 애플리케이션은 primary PostgreSQL만 사용합니다. EXP-038에서 replica 3대·80 it/s까지는 primary로 충분했고(PostgreSQL CPU 31%), 160 it/s에서 남은 병목은 연결 수가 아니었습니다.

**확장 검토 시점:** 다음 중 하나가 관측될 때입니다.

- 읽기 라우트 p95가 SLO를 넘으면서 PostgreSQL CPU가 70%를 15분 이상 유지
- 캐시 도입 후에도 목록 조회가 primary 읽기의 대부분을 차지
- replica lag이 문제되지 않는 경로(상품 목록·카테고리)가 병목

read replica 도입 시 **read-after-write 경로는 절대 replica로 보내지 않습니다.** `buyer-journey-capacity.js`가 측정하는 `fd_cart_read_after_write_ok`가 바로 그 성질입니다.

## 6. 그 밖에 확인했고 문제가 없던 것

| 항목 | 확인 결과 |
| --- | --- |
| HTTP 세션 | `SessionCreationPolicy.STATELESS` — sticky session 불필요 |
| payment recovery | `FOR UPDATE SKIP LOCKED` + lease token fencing + `PROVIDER`/`APPLY` stage fencing ([ADR-0046](adr/ADR-0046-order-payment-transactional-outbox.md)) |
| webhook retry | `SKIP LOCKED` + lease, `LIMIT 1` claim |
| 만료 sweep 3종 | advisory try-lock으로 tick당 1개만 수행 |
| 로컬 파일 저장 | `Files.write`/`FileOutputStream` 사용 없음 |
| 정적 mutable 상태 | `static` 컬렉션 없음(위 owner lock 제외) |

## 7. 우선순위 제안

측정 근거와 위험도를 함께 고려한 순서입니다. **어느 것도 지금 바로 도입하지 않습니다.**

| 순위 | 항목 | 근거 | 선행 조건 |
| ---: | --- | --- | --- |
| 1 | `Instant.now()` → `Clock` 통일 | 다중 노드 시계 차이, 테스트 결정성 | 없음(작은 리팩터링) |
| 2 | owner 락의 다중 인스턴스 경쟁 측정 | 실제 경쟁 빈도가 미측정 | 2대 이상에서 동일 주문 동시 결제 부하 |
| 3 | admission cursor 공유 | 중복 스캔 낭비 | sweep 비용이 유의미해질 때 |
| 4 | 읽기 확장(read replica 또는 캐시) | PostgreSQL CPU가 병목이 될 때 | 위 5절의 관측 조건 |
| 5 | 다통화 원장 | 요구사항 발생 시 | 제품 결정 |

## 8. 실제 PayPal Sandbox 계약 검증 결과

로컬 스텁은 우리가 기대하는 응답을 우리가 작성한 것이므로, PayPal이 필드나 상태 이름을 바꿔도 통과합니다. 이를 보완하려고 실제 `api-m.sandbox.paypal.com`을 호출하는 워크플로(`.github/workflows/paypal-sandbox-contract.yml`)를 두고 1회 실행했습니다.

| 확인 항목 | 실제 결과 |
| --- | --- |
| OAuth client_credentials | 토큰 발급 성공(길이 97) |
| 주문 생성 | `order_id=4V745537SE3747156`, `status=CREATED` |
| 주문 조회 | `status=CREATED`, `amount=1.00 USD` |
| `PayPal-Request-Id` 멱등성 | 같은 요청 ID 재전송 시 **동일 order_id 반환** |

즉 ATLAS가 의존하는 요청 형식과 응답 필드(`id`, `status`, `links`, `purchase_units.amount`)가 실제 Sandbox에서 유효함을 확인했습니다.

**검증 중 발견한 워크플로 버그:** 첫 실행에서 토큰 발급과 주문 생성은 성공했지만 응답 검증이 `JSONDecodeError`로 실패했습니다. `printf ... | python3 - <<'PY'` 형태는 heredoc이 stdin을 차지해 파이프 데이터가 전달되지 않습니다. 검증 스크립트를 파일로 쓰고 응답도 파일로 넘기도록 고쳤습니다.

**실측에서 확인한 PayPal 동작:** `payment_source`를 포함해 주문을 만들면 PayPal은 `approve` 대신 **`payer-action`** rel을 반환합니다. ATLAS 클라이언트는 두 rel을 모두 처리하므로 코드는 정상입니다.

**범위 밖:** 승인·캡처·환불은 구매자 승인 상호작용이 필요해 이 워크플로에 넣지 않았습니다. 그 경로는 통합 테스트(MockWebServer)와 로컬 실측이 담당합니다.

**클라이언트 테스트를 넣지 않은 이유:** Sandbox를 직접 호출하는 JUnit 테스트를 세 번 시도했지만, 실패 원인이 계속 제품 코드가 아니라 테스트의 `RestClient` 구성이었습니다. 맨 `RestClient.builder()`는 중첩 JSON의 `links`를 Map으로 바인딩하지 않고, Spring 7에서는 converter 등록 API도 deprecated됐습니다. 실제 앱은 Spring Boot가 구성한 builder를 주입받습니다. 검증 책임을 계층별로 나누는 편이 정직합니다.

| 계층 | 검증 대상 | 도구 |
| --- | --- | --- |
| PayPal API 계약 | OAuth·주문 생성/조회·Request-Id 멱등성 | 이 워크플로의 curl |
| ATLAS 결제 흐름·상태 전이 | 주문→승인→캡처→환불 로직 | MockWebServer 통합 테스트 |
| 결제 경로 지연 | 체크아웃·결제 p95 | EXP-038 로컬 실측 |

## 9. 이 문서의 한계

- 코드 정적 분석과 EXP-037/038 측정에 근거하며, 다중 인스턴스에서 owner 락 경쟁을 직접 측정하지는 않았습니다.
- 다중 인스턴스 경합은 이후 [EXP-118](../03-experiments/EXP-118-multi-instance-assumption-audit.md)에서 실제 bootJar 인스턴스 2~3대와 PostgreSQL 하나로 셌습니다(인증 주체 한도, 다른 인스턴스의 결제 스냅숏, 임시 JWT 키, 만료 sweep 잠금과 transaction pooler, V10 rolling 배포). 그 결과의 결정은 [ADR-0107](adr/ADR-0107-multi-instance-shared-state-guards.md)과 [D-186](../00-governance/decision-log.md)에 있습니다. 위 1절의 owner 락 분류는 이 문서의 코드 읽기 그대로이고, 결제 경로의 판정은 EXP-118 P칸을 따릅니다.
- "안전"으로 분류한 항목도 DB predicate에 의존하므로, 새 상태 전이를 추가할 때 그 predicate를 유지해야 합니다.
- 이 문서는 결정 기록이 아니라 **점검 기록**입니다. 실제 도입은 각 항목의 측정 근거가 생긴 뒤 별도 ADR로 진행합니다.
