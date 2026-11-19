# EXP-041: 결제 임계 경로의 외부 환율 의존 비용

**추적 이슈:** [#420](https://github.com/Neo1228/ATLAS/issues/420)

**상태:** 실측 완료 · 로컬, PostgreSQL 18.6 컨테이너 + MockWebServer stub

## 왜 이 실험을 했는가

"핫 로우 경쟁이 병목일 것"이라는 가설을 먼저 세웠다. `inventory_pool` 단일 행에 동시 요청이 몰리니 PostgreSQL row lock 이 직렬화할 것이라고 예상했다.

**측정 결과 가설이 틀렸다.** 단일 행 `UPDATE`는 8 client에서 63,537 tps, 32 client에서 194,219 tps까지 올라갔다(로컬 overlay fs, `synchronous_commit=on`). 핫 로우는 이 시스템의 병목이 아니다. 이 부정적 결과를 먼저 기록한다.

그래서 한 층 위로 올라가 **요청 경로의 외부 의존**을 봤다. 결제는 AUTHORIZATION 시작 시 외부 환율 API(BOK ECOS)를 동기 호출하고, 캐시가 없었다.

## 가설

> 결제 응답시간은 내부 DB 처리보다 **외부 환율 API 지연**이 지배한다. 캐시가 없으므로 결제 N건은 외부 호출 N회를 만든다.

## 방법

- `FxRateCallCostIntegrationTest`: 결제 3건을 실행하고 테스트 stub 의 요청 수를 센다. 같은 금액 5회 변환도 센다.
- `FxRateLatencyProbeTest`: 실제 코드 경로(`FxConversionService.convertKrwToUsdCents`)를 10회 호출해 wall-clock 표본을 남긴다.
- stub 지연을 `FX_STUB_LATENCY_MS` 환경변수로 주입한다. 테스트 JVM 에 `-D` 가 상속되지 않아 환경변수를 썼다.
- 매 측정은 `--rerun-tasks` 로 새 JVM 에서 실행했다(캐시된 결과를 재사용하지 않는다).
- stub 지연 주입이 실제로 관측되는지 별도로 먼저 확인했다: 주입 0/80/450ms → 관측 1/85/457ms.

## 결과 1: 구조 — 결제 1건 = 외부 호출 1회

| 시나리오 | 외부 환율 호출 |
|---|---:|
| 결제 3건 | ≥ 3 |
| 같은 금액 5회 변환 | **5** |

캐시가 없으므로 동일 금액·동일 시점에도 매번 네트워크로 나간다.

## 결과 2: 지연 — 외부 지연이 1:1로 결제에 더해진다

캐시 도입 **전**, 실제 코드 경로 10회 표본의 p50:

| 주입한 외부 지연 | p50 | 기준선 대비 |
|---|---:|---:|
| 0 ms | 2.11 ms | — |
| 80 ms | **91.90 ms** | +89.79 ms |
| 450 ms | **461.43 ms** | +459.32 ms |

내부 DB 처리가 2ms 수준인데, 외부 지연이 그대로 더해진다. 이 경로는 외부 의존이 지배한다.

## 결과 3: 캐시 도입 후 (같은 조건, 같은 명령)

| 주입한 외부 지연 | 캐시 전 p50 | 캐시 후 p50 | 캐시 후 max |
|---|---:|---:|---:|
| 0 ms | 2.11 ms | **0.00 ms** | 39.39 ms |
| 80 ms | 91.90 ms | **0.00 ms** | 121.46 ms |
| 450 ms | 461.43 ms | **0.00 ms** | 498.89 ms |

10회 중 9회가 캐시 적중으로 즉시 반환된다. max 는 첫 호출이 지불하는 실제 외부 지연이다. 즉 **결제 1건당이 아니라 TTL(60초)당 1회**로 외부 호출이 줄어든다.

## 해석

- 외부 의존을 없앨 수는 없다. 환율은 외부 사실이다. 줄일 수 있는 것은 **호출 빈도**다.
- BOK ECOS 731Y001 은 **일 단위 고시**다. 60초 TTL 은 고시 주기보다 훨씬 짧으므로, 캐시가 오래된 환율을 쓰게 만들 위험이 작다.
- 금전 정합성은 캐시에 의존하지 않는다. 결제 금액은 `PaymentAttempt` 에 불변 스냅샷으로 고정되고, 이후 비교는 저장된 값과만 한다. 캐시는 "어떤 고시값을 스냅샷으로 고를지"에만 영향을 준다.

## 한계

- 로컬 단일 호스트 측정이다. 실제 BOK ECOS 지연은 네트워크·해외 구간에 따라 다르며, 이 실험은 **주입한 지연에 대한 민감도**를 보인 것이지 운영 p50 을 주장하지 않는다.
- 외부 API rate limit·429 동작은 측정하지 않았다. 호출 빈도가 1/TTL 로 줄면 위험도 줄지만, 실제 quota 는 별도 확인이 필요하다.
- 캐시는 프로세스 로컬이다. replica 간 값이 최대 TTL 만큼 다를 수 있다. 금전 정합성은 스냅샷 불변성으로 유지되지만, 이 불일치가 운영에서 문제가 되는지는 별도 관측이 필요하다.
- 60초 TTL·10분 max-stale 은 **측정이 아니라 판단**이다. 고시 주기(일 단위)와 결제 실패 허용 시간을 근거로 정했으며, 운영 분포를 보고 조정해야 한다.

## 결정에 미친 영향

- 1차로 TTL 캐시 + 단일 비행을 도입했다. 단일 비행은 측정으로 필요성이 확인됐다: 캐시만 있으면 만료 직후 동시 요청이 N회 외부로 나간다.
- 2차(stale-while-error)도 함께 도입했다. 외부 장애 시 결제 전면 중단보다 몇 분 된 고시값 사용이 낫다는 판단이며, `max-stale` 초과 시에는 결제를 막는다.
- 다중 인스턴스 공유 캐시(Redis)는 **보류**한다. 지금은 replica 수와 외부 호출량을 먼저 측정해야 한다. 이 실험은 단일 프로세스 비용만 다뤘다.

## 추가: 외부 호출 관측은 이미 존재했다 (측정 후 발견)

공유 캐시 도입 여부를 판단하려면 replica 별 외부 호출률이 필요하다. 처음에는 도메인에 카운터를 넣거나 로그 파이프라인을 만드는 방안을 검토했으나, **실측 결과 둘 다 불필요했다.**

### 확인 방법

환율 API 를 1회 호출한 뒤 `MeterRegistry` 를 덤프했다.

```text
http.client.requests{client.name=localhost, error=none, exception=none,
  method=GET, outcome=SUCCESS, status=200,
  uri=/api/StatisticSearch/{key}/json/kr/1/10/731Y001/D/{from}/{to}/0000001/}
```

### 이유

- `micrometer-observation` 이 이미 runtime classpath 에 있다(Spring Boot 의존성).
- `BokEcosExchangeRateProvider` 가 주입받는 `RestClient.Builder` 는 Spring Boot 가 제공한다.
- Boot 는 micrometer-observation 이 있을 때 `RestClient` 에 자동으로 계측을 붙인다.

따라서 외부 호출 수·지연·상태가 이미 `http.client.requests` 로 노출된다. 새 카운터도, 로그 수집기도 필요 없었다.

### 이번에 바꾼 것

`application-observability.yml` 한 항목. 호출 **수**는 기본 카운터로 보이지만 **지연 분포**는 histogram 이 있어야 PromQL 로 계산된다.

```yaml
management.metrics.distribution.percentiles-histogram:
  http.client.requests: true
```

### replica 별 호출률 확인 방법

```promql
sum by (instance) (rate(http_client_requests_seconds_count{uri=~".*StatisticSearch.*"}[5m]))
```

이 값을 BOK ECOS quota 와 비교해 공유 캐시 도입 임계를 정한다.

### 교훈

"가장 작은 변경"을 찾을 때, 새 계측을 설계하기 전에 **이미 존재하는 계측을 먼저 확인**해야 한다. 이 실험에서 그 순서를 지키지 않아 세 번의 구현 시도가 낭비됐다.

## 추가: replica 간 측정 시도와 환경 제약 (미완)

replica 수에 따른 외부 호출 배수를 실측하려 했다. **완료하지 못했고, 그 원인을 남긴다.**

### 구성한 것

현재 코드로 `bootJar` 빌드 → 격리 PostgreSQL(15432) + `load-tests/paypal-stub.py`(18100) + ECOS stub(18099) + app(18090, `observability` profile). V49 마이그레이션 적용 확인. 기존 `load-tests/buyer-checkout.js` 로 signup → seller onboarding → product/SKU → cart → checkout → payment 전 구간 성공.

이 과정에서 outbound 호출이 실제로 관측되는 것을 확인했다:

```text
http_client_requests_seconds_count{client_name="127.0.0.1", status="CLIENT_ERROR",
  uri="/api/StatisticSearch/{key}/json/kr/1/10/731Y001/D/{from}/{to}/0000001/"} 8
```

### 막힌 지점

`PayPalProperties.validateBaseUrl` 은 PayPal base URL 호스트를 `localhost` / `127.0.0.1` / `::1` / sandbox 로만 허용한다. 이 런타임(Colima VM)은 `--network host` 에서도 컨테이너 네트워크 네임스페이스를 분리한다. 따라서:

- 컨테이너의 `127.0.0.1` 은 호스트 stub 이 아니다
- 호스트 bridge 주소(`172.17.0.1` 등)는 검증에서 거부된다

결과적으로 앱이 ECOS stub 에 도달하지 못하고 `ResourceAccessException`(회당 ~8ms 로 빠른 실패)이 발생했다. 검증을 우회하면 측정이 아니라 설정 변경이 되므로 하지 않았다.

### 이미 측정된 것

`FxRateCallCostIntegrationTest` 가 실제 HTTP 경로로 검증한다(통과 중):

| 시나리오 | FX 외부 호출 |
|---|---:|
| 결제 3건 | 1회 |
| 같은 금액 5회 변환 | 1회 |
| 8스레드 동시 콜드 스타트 | 1회 |

단일 인스턴스에서 캐시·단일 비행은 실측으로 확인됐다. **남은 것은 replica 간 배수뿐**이며 이 환경에서는 재현할 수 없다.

### 다음 시도에서 필요한 것

컨테이너 런타임이 `--network host` 를 실제로 공유하거나, stub 을 앱과 **같은 네임스페이스**에 두어야 한다(예: stub 을 앱 컨테이너 안에서 실행, 또는 검증 허용 호스트가 같은 네임스페이스를 가리키게 구성). 또는 운영과 동일한 compose 스택에서 replica 수만 늘려 측정한다.
