# EXP-016: Buyer checkout 판매자 분할 비교

**상태:** benchmark 기록 · JWT enforcement 이전 실행

> [!WARNING]
> 현재 Seller API는 JWT를 요구합니다. 이 k6 setup은 Bearer token을 보내지 않으므로 account/Seller onboarding을 현재 인증 계약에 맞춘 뒤 재실행해야 합니다. 기존 결과는 당시 구현 기준으로 보존.

## 목적과 범위

동일한 두 Cart line이 한 SellerOrder로 합쳐지는 경로(A)와 서로 다른 SellerOrder 두 개로 분리되는 경로(B)를 비교. HTTP request RPS, 완료된 checkout session TPS/latency, JVM과 PostgreSQL 지표를 수집. Q-008의 `2.78 sessions/s`는 합성 arrival input이며 운영 traffic 수요나 capacity로 해석하지 않음.

## 환경·데이터·workload

앱과 k6 모두 선택적 Compose 프로파일로 실행한다. 먼저 앱 JAR를 만들고 서비스를 시작한다:

```sh
./gradlew :application-module:bootJar
docker compose --profile observability up --build -d application postgres-exporter prometheus grafana
```

- App image build context는 `ATLAS-application/Dockerfile`과 `ATLAS-application/build/libs/application-module.jar`.
- API·management port는 host에 publish하지 않음. Compose 내부에서 k6·Prometheus가 `application:8080/8081`에 접근.
- PostgreSQL `5432`, exporter `9187`, Prometheus `9090`, Grafana `3000`은 host loopback에만 publish.
- Host `bootRun`의 API·management listener는 기본 `127.0.0.1` bind.

- k6는 published port 없이 Compose network에서 `http://application:8080`에 직접 요청.
- k6 `setup()`은 timed scenario 전에 같은 API로 synthetic Seller/Product/SKU를 생성.
- Host `bootRun`은 listener를 `127.0.0.1`에 bind하므로 Prometheus container scrape 경로로 사용할 수 없음. Compose에서는 앱 container 안에서만 `0.0.0.0`으로 override.
k6 Compose service는 `loadtest` 프로파일에만 속하므로 `observability` metrics stack의 기본 `up` 실행에서 시작하지 않는다. 일회성 실행은 `--profile observability --profile loadtest run --rm k6 ...`로 두 프로파일을 선택한다.

- Prometheus는 TSDB volume/retention override 없이 실행. Container 제거 시 time series 삭제; 필요한 query 결과는 coordinator가 별도 export.
- Grafana는 persistent volume 없이 dashboard provisioning으로 복원.
- k6 structured summary는 `docs/03-experiments/results/exp-016:/results` bind mount에 `SUMMARY_FILE=/results/<run-id>.json`으로 저장. 이는 테스트 summary이며 raw Prometheus TSDB가 아님.
- Dashboard는 HTTP 처리율/p95, JVM heap/process CPU, PostgreSQL active connections·commit·deadlock을 수집. 이 지표만으로 DB wait 원인을 확정할 수 없음.
- 이번 run은 tracing/OTel/custom instrumentation 없이 수행. Metrics로 원인 분리가 안 되는 경우에만 Tempo/Alloy tracing을 후속 검토.
HTTP p95 dashboard panel이 필요로 하는 bucket series를 내보내기 위해 `application-observability.yml`에서만 `management.metrics.distribution.percentiles-histogram.http.server.requests=true`를 활성화한다. 이는 관측용 histogram이며 SLO나 latency threshold를 정의하지 않는다.
- Prometheus scrape interval은 5초. 120초 pilot에서 24회 scrape로 짧은 신호 변화를 관찰하기 위한 설정.
- Collector overhead는 pilot 자원 사용량에 포함되며 별도 분리 측정하지 않음. 실제 부하는 120초 pilot으로 제한.

## 재현 방법

`load-tests/buyer-checkout.js` 환경 계약:

| 변수 | 필수/기본 | 의미 |
|---|---|---|
| `BASE_URL` | required; command passes `http://application:8080` | Compose-internal app origin |
| `PROFILE` | required | `one-seller` or `two-seller` |
| `RUN_ID` | required, `[A-Za-z0-9_-]{1,32}` | Unique synthetic-data and buyer/key prefix per run |
| `UNIT_PRICE_WON` | required positive integer; commands use synthetic `100` | Equal seeded unit price; not production pricing |
| `DURATION` | default `120s`; command passes explicitly | Calibration or pilot duration |
| `SESSION_RATE_NUMERATOR` | default `278`; command passes explicitly | Constant-arrival-rate numerator |
| `SESSION_RATE_TIME_UNIT` | default `100s`; command passes explicitly | Default input is `278/100s = 2.78` synthetic sessions/s |
| `FIXED_VUS` | required positive integer; no default | Calibration-derived fixed concurrency for both preallocated and max VUs |
| `SUMMARY_FILE` | required by examples | `/results/<run-id>.json` on writable results mount |

All script inputs must be passed to k6 using CLI `-e KEY=value` options after `k6 run` and before the script path; Compose service environment values alone do not populate k6 `__ENV`.

### 1-VU calibration 및 고정 concurrency

- Profile별 60초·1-VU calibration부터 수행. `summaryTrendStats`는 `med`, `p(95)`, `p(99)` 기록; `med`는 p50.
- Script JSON의 `checkoutSessionLatencyMs.p50`, `.p95`, `.p99`에 각각 기록. VU 산정에는 `.p95` 사용.
- 고정 VU 공식: `ceil(2.78 sessions/s × observed p95 session duration in seconds)`. p95가 0.8초면 `ceil(2.78 × 0.8) = 3 VUs`.
- 0.8초는 산식 예시이지 측정값이 아님. p95 표본이 없으면 임의 VU로 진행하지 않고 setup/checkout을 조사하거나 calibration 재실행.
실측 calibration p95: one-seller `61.2ms`, two-seller `39ms`. 산식에 따라 두 프로파일 모두 fixed VU `1`을 사용. 원본: [one-seller](results/exp-016/exp016-cal-a.json), [two-seller](results/exp-016/exp016-cal-b.json).

```sh
mkdir -p docs/03-experiments/results/exp-016
docker compose --profile observability --profile loadtest run --rm k6 run \
  -e BASE_URL=http://application:8080 \
  -e PROFILE=one-seller -e RUN_ID=exp016-cal-a \
  -e UNIT_PRICE_WON=100 -e DURATION=60s \
  -e SESSION_RATE_NUMERATOR=278 -e SESSION_RATE_TIME_UNIT=100s \
  -e FIXED_VUS=1 -e SUMMARY_FILE=/results/exp016-cal-a.json \
  /scripts/buyer-checkout.js

docker compose --profile observability --profile loadtest run --rm k6 run \
  -e BASE_URL=http://application:8080 \
  -e PROFILE=two-seller -e RUN_ID=exp016-cal-b \
  -e UNIT_PRICE_WON=100 -e DURATION=60s \
  -e SESSION_RATE_NUMERATOR=278 -e SESSION_RATE_TIME_UNIT=100s \
  -e FIXED_VUS=1 -e SUMMARY_FILE=/results/exp016-cal-b.json \
  /scripts/buyer-checkout.js
```

Calibration 중 1 VU가 2.78 sessions/s 도착을 모두 수행하지 못할 수 있다. session p95는 완료 세션 표본으로 계산하고 `droppedIterations`를 함께 기록한다. calibration에서 p95를 얻을 표본이 부족하면 해당 프로파일에서 비교 가능한 고정 VU를 도출하지 못한 한계로 기록한다.

각 calibration 결과에서 산출한 양의 정수를 shell 환경 변수 `FIXED_VUS_A`, `FIXED_VUS_B`에 설정한다. `maxVUs`는 `FIXED_VUS`와 항상 같아 고정되며 autoscaling하지 않는다. Pilot 전 애플리케이션이 API 요청을 받을 준비가 되었는지 확인한다.

docker compose --profile observability --profile loadtest run --rm k6 run \
  -e BASE_URL=http://application:8080 \
  -e PROFILE=one-seller -e RUN_ID=exp016-a \
  -e UNIT_PRICE_WON=100 -e DURATION=120s \
  -e SESSION_RATE_NUMERATOR=278 -e SESSION_RATE_TIME_UNIT=100s \
  -e FIXED_VUS=${FIXED_VUS_A} -e SUMMARY_FILE=/results/exp016-a.json \
  /scripts/buyer-checkout.js

docker compose --profile observability --profile loadtest run --rm k6 run \
  -e BASE_URL=http://application:8080 \
  -e PROFILE=two-seller -e RUN_ID=exp016-b \
  -e UNIT_PRICE_WON=100 -e DURATION=120s \
  -e SESSION_RATE_NUMERATOR=278 -e SESSION_RATE_TIME_UNIT=100s \
  -e FIXED_VUS=${FIXED_VUS_B} -e SUMMARY_FILE=/results/exp016-b.json \
  /scripts/buyer-checkout.js

세션 오류와 dropped iterations는 summary에 기록한다. fixed pool이 target arrival rate를 유지하지 못하면 이를 비교의 제한으로 보고하며 처리 용량 결과로 해석하지 않는다. variability/sample/telemetry가 부족할 때만 새 RUN_ID로 각 profile을 3회 × 120초까지 확장하고 사유를 기록한다.

### Seed·workload·reset

- k6 `setup()`은 timed workload 전에 Seller/Product/SKU를 API로 생성. Setup 오류는 run 시작 전에 중단.
- Script는 JWT enforcement 이전 작성되어 현재 보호된 Seller API에 token을 보내지 않음. 재사용 전 현재 auth contract에 맞게 account onboarding을 갱신해야 함.
- Profile A는 Seller 1명·SKU 2개, B는 Seller 2명·각 SKU 1개. Product/SKU ID는 RUN_ID/profile/line 조합, Seller/Product UUID는 API 발급.
- Setup 요청은 `phase=setup` tag로 측정 counter에서 제외.

- 두 SKU 가격은 동일한 `UNIT_PRICE_WON`; 세션당 각 SKU quantity 1.
- SKU별 `initialStock`은 arrival rate와 duration으로 계산하며 추가 headroom은 없음: `ceil((SESSION_RATE_NUMERATOR / SESSION_RATE_TIME_UNIT 초) × DURATION 초)`.
- 기본 120초 pilot: `ceil((278 / 100) × 120) = 334`개/SKU. 60초 calibration: 167개/SKU.
- 각 session은 SKU별 1개씩 소비. Checkout 총액은 `2 × UNIT_PRICE_WON`.

Timed workload는 4 HTTP request: Cart line 추가 2회, `POST /api/buyers/{buyerRef}/cart/checkout`, 응답의 checkoutId로 `POST /api/checkouts/{checkoutId}/payment`. 각 iteration은 고유 buyerRef/idempotency key 사용. A는 child order 1개, B는 2개 생성 확인. 당시 deterministic payment test adapter의 `paymentState=CAPTURED`만 완료로 계산.

Seeded rows는 DB에 지속되고 API 삭제 endpoint가 없다. 각 controlled run 사이에는 해당 EXP-016 Compose project만 제거하고 `--volumes`로 PostgreSQL state를 초기화한 뒤 migrations를 다시 적용한다. 매 run은 새 RUN_ID와 동일한 fresh schema/seed policy를 사용한다.
### 초기 sweep 폐기 근거

- 초기 exploratory sweep은 같은 DB를 초기화하지 않고 이어 실행해 transaction row 수가 누적됨. Seller별 결과 차이와 table-growth 효과를 분리할 수 없어 비교 결과에서 제외.
- 초기 k6 summary는 `results/exp-016/exploratory/`에 보관. 최종 표는 profile/run마다 fresh DB를 적용한 결과만 사용.

## 결과

- **HTTP RPS:** timed request 직후 증가하는 k6 `measured_http_requests` counter rate; setup 호출은 제외한다. 정상 세션당 4 요청이며 조기 오류가 있으면 요청 수가 줄 수 있다.
- **완료 세션 TPS:** cart 설정, checkout, payment capture/검증 모두 성공한 `completed_checkout_sessions` rate. 요청 RPS와 별개다.
- **지연:** 완료된 `checkout_session_duration` p50/p95/p99 (ms).
- **오류/드롭:** `checkout_session_errors`, `droppedIterations` count.
- **표본:** 시작 세션 `checkout_session_samples`, 완료 `completed_checkout_sessions` sample count. Seed IDs/stock, pilot JSON stdout을 함께 보존한다.

### Controlled 결과 — fresh DB, local synthetic workload

세 run마다 profile별 DB를 초기화. 각 run은 120초, `2.78 sessions/s`, fixed `1 VU`, line 2개·동일 KRW total, SKU당 initial stock 334. Stock은 `ceil(2.78 × 120)`으로 산정.

| Profile | Sessions | HTTP RPS median [min–max] | Checkout TPS median [min–max] | 오류/dropped |
|---|---:|---:|---:|---:|
| one-seller | 3 runs / 1,002 | 11.113 [11.113–11.118] | 2.778 [2.778–2.779] | 0 / 0 |
| two-seller | 3 runs / 1,002 | 11.115 [11.114–11.118] | 2.779 [2.778–2.780] | 0 / 0 |

| Profile | 세션 p50 median [range] ms | 세션 p95 median [range] ms | 세션 p99 median [range] ms |
|---|---:|---:|---:|
| one-seller | 30.5 [28–31] | 47 [46–48] | 59.67 [59.35–68.72] |
| two-seller | 30 [30–32] | 50 [48–54.7] | 59.68 [59.01–68.68] |

two-seller p95 중앙값은 3ms(약 6.4%) 높았으나 run ranges overlap. p99 중앙값은 사실상 동일; p99는 334 samples/run 수준의 descriptive value. 이 결과는 Seller split의 안정적인 latency 증가를 입증하지 않음. Arrival rate는 test input으로 고정했으므로 처리 용량을 의미하지 않음.

- k6 controlled run JSON: [A1](results/exp-016/exp016-clean-a.json), [A2](results/exp-016/exp016-clean-a-r2.json), [A3](results/exp-016/exp016-clean-a-r3.json); [B1](results/exp-016/exp016-clean-b.json), [B2](results/exp-016/exp016-clean-b-r2.json), [B3](results/exp-016/exp016-clean-b-r3.json).

### 상태 무결성 smoke

성능 비교와 분리한 5초 검증. Profile마다 fresh DB에서 14 sessions를 실행했으며 error/dropped `0`. 이 smoke의 latency는 A/B 비교에 사용하지 않음.

- **one-seller:** 14 checkout / 14 PurchaseOrder / 28 OrderItem. Inventory pool `2/2` 보존, capacity `28 = 28`, 모두 claimed. Payment capture `14/14`; journal balance·gross allocation `14/14`.
- **two-seller:** 14 checkout / 28 PurchaseOrder / 28 OrderItem. Inventory pool `2/2` 보존, capacity `28 = 28`, 모두 claimed. Payment capture `14/14`; journal balance·gross allocation `14/14`.
- **중복·cardinality:** 두 profile 모두 expected order-count mismatch, duplicate checkout/payment attempt/journal `0`. Checkout당 journal `1건`.
- Read-only query: [check-integrity.sql](results/exp-016/check-integrity.sql). 실행: `docker compose cp docs/03-experiments/results/exp-016/check-integrity.sql postgres:/tmp/check-integrity.sql` 후 `docker compose exec -T postgres psql -U atlas -d atlas -v run_id=<RUN_ID> -v profile=<PROFILE> -f /tmp/check-integrity.sql`.
- 증거: [A k6](results/exp-016/exp016-integrity-a.json), [A DB 상태](results/exp-016/exp016-integrity-a-state.json), [B k6](results/exp-016/exp016-integrity-b.json), [B DB 상태](results/exp-016/exp016-integrity-b-state.json).

### Prometheus snapshot

| Metric | one-seller | two-seller | 해석 |
|---|---:|---:|---|
| Checkout HTTP route p95 | 11.01ms | 17.55ms | Prometheus 10m lookback (startup/setup/load/idle 포함); route 진단용, K6 session p95가 primary |
| Payment HTTP route p95 | 11.55ms | 18.03ms | 같은 query window |
| Hikari active connection max | 1 | 1 | 같은 local run profile |
| PostgreSQL deadlock count | 0 | 0 | fresh DB profile |

Process CPU/JVM heap maxima도 [one-seller](results/exp-016/observability-clean-a.json)/[two-seller](results/exp-016/observability-clean-b.json)에 기록. 해당 lookback은 app startup·pilot·idle을 포함하므로 A/B resource 승패 비교에 사용하지 않음. Host settings: 11 logical CPU/18GiB; Docker VM 4 CPU/약 9.7GiB. Compose에 CPU/memory quota는 설정하지 않음.

### 판단과 한계

- Fresh DB에서 profile별 3회 실행, 회당 120초·334 session. 모든 run에서 error/dropped iteration `0`.
- Two-seller p95 median은 one-seller보다 3ms 높았으나 ranges overlap (`46–48ms` vs `48–54.7ms`). p99 median은 사실상 동일 (`59.67ms` vs `59.68ms`); p99는 334 samples/run 수준의 참고값.
- RPS/TPS는 도착률 입력을 따라 측정된 결과이며 load ceiling을 찾지 않음. 처리 용량, 사용자 수, SLO 또는 안정적 성능 우열을 주장하지 않음.
- DB reset이 없던 첫 sweep은 transaction row 누적 영향이 섞여 exploratory 폴더에 보관; controlled 결과 표에서는 제외.
- 추가 성능 변경 전에는 새로운 병목 가설과 workload 근거를 먼저 정함.

## 해석과 한계

- 참고 사례: Meituan trade-system [REF-116](../00-governance/reference-register.md), Shopify inventory reservation [REF-070](../00-governance/reference-register.md). 각각 transaction boundary와 read/DB contention 지표를 참고.
- 사례의 환경·수치·구현을 그대로 가져오지 않음.
- Controlled run은 fresh PostgreSQL schema, synthetic data, deterministic test payment adapter 사용.
- 결과는 단일 JVM/DB의 짧은 workload에 한정. PayPal network latency나 long-run stability 근거로 해석하지 않음.
