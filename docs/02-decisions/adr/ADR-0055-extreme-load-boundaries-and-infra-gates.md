# ADR-0055: 극한 부하 보호를 경계 설정으로 먼저 하고, 인프라는 측정으로만 도입한다

- 상태: Accepted — 실측 + 외부 사례 근거
- 날짜: 2026-11-20
- 관련: [ADR-0053](ADR-0053-shared-infra-adoption-gates.md), [ADR-0054](ADR-0054-flash-sale-contention-removal.md), [EXP-044](../../03-experiments/EXP-044-flash-sale-inventory-contention.md), [EXP-045](../../03-experiments/EXP-045-settlement-balance-index.md), [EXP-046](../../03-experiments/EXP-046-metrics-scrape-full-scan.md)
- 추적: [#434](https://github.com/Neo1228/ATLAS/issues/434)

## 배경

ADR-0053 은 캐시·브로커·외부 락을 "측정된 병목이 없다"는 이유로 보류했다. 그러나 그 판단의 측정들은 **저부하**였다. "대충 맞다"는 가정 위에서 인프라 불필요를 결론지은 것은 방법론적 결함이었다.

이 ADR 은 극한 부하를 가정하고 다시 물었다: **무엇이 먼저 무너지는가?** 그리고 외부 사례(인도·중국 하이퍼스케일)를 근거로 경계를 설정했다.

## 확인된 결함과 근거

| 결함 | 확인 방법 | 근거 |
|---|---|---|
| 예약마다 중복 `FOR UPDATE` | 실측 (EXP-044) | 128 동시에서 tps 460 정체·p50 264ms |
| `sellerBalance` 전체 스캔 | EXPLAIN (EXP-045) | 2,936 버퍼 → 인덱스 4 버퍼 |
| 스크레이프당 16개 `COUNT(*)` | 코드 감사 (EXP-046) | 비인증 경로가 DB 부하를 조절 |
| PayPal 토큰 요청 타임아웃 없음 | 코드 감사 | OAuth 경로가 무한 대기 가능 |
| JPA 경로에 DB 타임아웃 없음 | 코드 감사 | `query-timeout` 은 JdbcTemplate 만 |
| Tomcat 200 : Hikari 10 | 설정 부재 | 20:1 대기 |

## 핵심 통찰: "실패 배출 속도"

가장 중요한 계산은 **대기 상한이 곧 실패 배출 속도**라는 것이다.

```text
배출 가능 rps = Tomcat 스레드 수 / connection-timeout(초)
200 스레드 / 5초 = 40 rps
```

즉 5초 상한에서 40 rps 를 넘는 굶는 요청이 유입되면, Tomcat accept queue(기본 100)가 차고 **503 이 아니라 TCP reset** 이 나간다. 클라이언트는 재시도하고, 이는 증폭된다.

500ms 로 낮추면 같은 200 스레드로 400 rps 까지 정상적으로 503 을 돌려준다. 그리고 스레드를 40 으로 줄이면 40/0.5 = **80 rps** 의 정상적 거절 능력을 유지하면서 스레드 낭비를 없앤다.

## 결정

### 1) 스레드를 커넥션에 맞춘다 (인프라 아님, 설정)

Tomcat `max-threads: 40` (Hikari 10 × 4).

근거: [HikariCP pool sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) — "작은 풀을 포화시키고 스레드를 대기시켜라", 4코어+1디스크면 9~10. [PostgreSQL wiki](https://wiki.postgresql.org/wiki/Number_Of_Database_Connections) — TPS 는 ~50 커넥션에서 정점 후 하락.

**ATLAS 의 Hikari 10 은 이미 올바른 값이었다.** 잘못된 것은 반대편(스레드 200)이었다.

### 2) 커넥션 대기 상한을 500ms 로 (인프라 아님, 설정)

위 "배출 속도" 계산에 근거한다. 정상 DB 작업이 수 ms 이므로 500ms 대기는 이미 과부하 신호다.

### 3) 서버 측 타임아웃 (인프라 아님, 설정)

`statement_timeout=30s`, `idle_in_transaction_session_timeout=60s` 를 JDBC URL options 로 건다. `query-timeout` 은 JPA 경로를 덮지 못하기 때문이다.

### 4) 중복 직렬화 제거 (ADR-0054)

### 5) 전체 스캔 제거 (EXP-045, EXP-046)

## 인프라는 왜 아직 도입하지 않는가

세 후보 모두 **측정된 임계값에 도달하지 않았다.**

| 후보 | 임계값 | 현재 상태 | 판정 |
|---|---|---|---|
| 공유 캐시 (Redis) | ECOS quota 80% 또는 429 | FX 초당 0.033회 | 미도달 |
| 메시지 브로커 | due job 나이 60초 초과 15분 | 복구 큐 정상 | 미도달 |
| 외부 락 | 2대 이상 same-owner 대기가 latency 예산 초과 | DB 방어선 작동 (동시 8건 → attempt 1개) | 미도달 |

**경계 설정으로 40~175% 개선을 먼저 확보했다.** 인프라를 먼저 넣었다면 그 개선의 원인을 잘못 귀속시켰을 것이다.

## 인프라가 필요해질 조건 (구체적으로)

### 재고 버킷 (분할)

Alibaba 分桶 사례: 하나의 SKU 를 N개 버킷으로 나눠 쓰기 경합을 분산.

**ATLAS 의 현재 상한:** 단일 행 조건부 UPDATE 로컬 약 600~900 tps (EXP-044). Alibaba 는 단일 행 MySQL UPDATE 를 **~500 tps** 로 본다.

**도입 조건:** 특정 SKU 의 피크 쓰기가 **지속적으로 500 tps 를 넘고**, 그것이 매출 손실로 이어질 때. 버킷은 다음 문제를 만든다:

- **과판매**: 예약 버퍼를 두고 버퍼 미만이면 즉시 품절
- **단편화**: 버킷 잔량이 최소 주문 단위보다 작으면 그 재고는 팔 수 없다. 버킷 크기 ≥ 최대 주문 수량이어야 하고, 단편 버킷은 DB 로 환원해 소진한다
- **불변식**: `SUM(bucket.available) + SUM(reserved) == initial_capacity - claimed` 를 대조 작업으로 검증해야 한다 (JD 사례)

### Redis 사전 차감

JD 사례: Redis 가 예약 쓰기를 받고 DB 는 회계만. 불변식 `DB.available == Redis.available + Redis.reserved` 를 대조 작업으로 확인.

**도입 조건:** 위 버킷으로도 부족할 때. 이때는 **대조 작업과 보상 체인**이 필수다. JD 도 "단기 일관성을 희생한다"고 명시했다.

### 서킷 브레이커

**주의:** Zalando 사례 — 단순 half-open 서킷 브레이커는 **닫히는 순간 모든 대기 트래픽이 한꺼번에 도착**해 다시 열린다(3초 주기 진동). 1분 평균 CPU 로는 보이지 않았다.

**도입 조건:** PayPal 장애가 빈발하고, 서킷 브레이커를 넣을 때 **점진 복구**(33%/67%/100%)와 사전 스케일을 함께 설계할 때만.

### 재시도 정책

현재 PayPal 호출은 자동 재시도하지 않는다(`PayPalRestClient` 주석). 이는 **AWS 원칙과 일치**한다: 재시도는 한 계층에서만, 멱등 연산만.

**도입 조건:** 재시도를 추가한다면 full jitter(base 50~100ms, cap 2s), 재시도 예산 10%, 총 예산 10초. 여러 계층에 걸쳐 재시도하면 3^5 = 243배 증폭된다.

## 측정하지 못한 것 (정직하게)

- **HTTP 계층 포함 극한 부하를 재지 않았다.** EXP-044 는 서비스 계층만 측정했다. Tomcat 설정 변경의 효과는 아직 측정 전이다.
- **다중 replica 경합**: 단일 노드만 측정했다.
- **`payment_recovery_job` 대량 데이터에서의 스크레이프 지연**: 쿼리 수만 셌다.
- **PostgreSQL 커넥션 정점**: 인용한 ~50 은 출처 값이며 ATLAS 에서 직접 재지 않았다.

## 참고 사례 요약

| 사례 | 관찰 | ATLAS 적용 |
|---|---|---|
| Taobao 大秒 | 단일 SKU 쓰기 1,500 tps, 앞단 600k QPS. 계층적 검증 funnel | 읽기는 낡아도 되고, **쓰기만 강한 일관성** |
| Alibaba 단일 행 UPDATE | ~500 tps 상한 | ATLAS 600~900 tps 는 이미 그 범위 |
| JD 재고 예약 | Redis 쓰기 + DB 회계 + 대조 작업 | 버킷/사전차감 시 필수 |
| Meituan 결제 | 트랜잭션 안 외부 호출 = "위험도 5성" | ATLAS 는 이미 provider 를 밖에서 호출 |
| Mayur (인도) | 2.4초 결제 호출을 트랜잭션에 넣어 풀 고갈, DB CPU 4% | 같은 위험 형태. `idle_in_transaction` 상한이 자동 방어 |
| Flipkart Big Billion Days | 락 경합이 CPU 보다 먼저 스레드 고갈 | bounded gate + fast-fail |
| Zalando | 서킷 브레이커 진동 | 점진 복구 없이 브레이커 도입 금지 |

## 결제 정합성: exactly-once 는 존재하지 않는다

외부 사례 조사에서 가장 일관된 결론이다. Razorpay·PhonePe·Paytm·Alipay(Antom) **모두** 같은 구조를 쓴다.

> **at-least-once 전달 + 멱등 키 + 권위 있는 상태 조회 + T+n 명세 대조**

어느 곳도 exactly-once 를 제공하지 않는다. "provider 는 성공했는데 클라이언트는 실패를 봤다"는 창은 **프로토콜이 아니라 대조 작업으로** 닫힌다.

### ATLAS 의 현재 상태

| 요소 | 상태 |
|---|---|
| provider 호출을 DB transaction 밖에서 | **이미 함** (`performPhase`) |
| 고정 request-id 펜싱 | **이미 함** (`phaseRequestId` 비교) |
| 권위 있는 상태 조회 | **이미 함** (reconcile, `AUTHORIZATION_PENDING`/`UNKNOWN`) |
| T+n 명세 대조 | **부분적** (`PayPalBalanceReport*` 수집은 있음, 로컬 원장과의 diff·보정 경로는 없음) |

즉 ATLAS 는 4개 중 3개를 이미 갖췄다. 빠진 것은 **명세 대조의 diff 와 보정**이다.

### 다음에 만들 것 (우선순위)

1. **로컬 원장 vs provider 명세 diff** — 분류는 셋이다: local-only(우리는 청구했다고 봄, provider 는 아님), provider-only(그들은 청구함, 우리가 놓침), 금액/수수료 불일치. Antom 의 3단 보고서(거래 상세 → 정산 상세 → 정산 요약)가 참조 스키마.
2. **diff 는 보고만이 아니라 보정할 수 있어야 한다** — JD 모델: 경보 → 캐시 무효화 → **수량 정정**. 정정 자체가 멱등·감사 가능한 연산이어야 한다.

### PhonePe 의 규칙 (가장 강한 형태)

> 상태 조회 API 가 진실의 원천이다. 콜백은 편의이며 도착하지 않을 수 있다. **최종 결과를 받기 전에는 주문을 실패로 표시하지 말고, 같은 주문으로 새 결제를 요청하지도 마라.**

폴링 스케줄: 요청 성공 후 ~5초 → 3~5초 간격 → 만료 시점 최종 확인 → 이후 **15분 간격 백그라운드 대조**(영원히 폴링하지 않음).

### GitHub 사례의 교훈

이 조사 전체에서 가장 큰 단일 숫자는 처리량이 아니라 **GitHub 의 웹훅 20만 건 유실**이다. 큐 TTL 이 장애 시간보다 짧아서 잃었다.

> ATLAS 가 post-commit 작업 큐를 추가한다면, TTL 은 정상 지연이 아니라 **감당할 수 있는 최악의 장애 시간**을 기준으로 정해야 한다.
