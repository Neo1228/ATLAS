# EXP-050: PostgreSQL 연결 장애가 상품 조회에 미치는 영향

**상태:** 로컬 실험 · PostgreSQL 구간 측정 완료 · PayPal 결제 구간 미측정

## 문제와 가설

외부 의존성이 느려지거나 끊겼을 때, 그 의존성을 사용하는 요청이 커넥션 풀에서 얼마나 오래 대기하고 어떻게 실패하는지 확인한다. 가설은 PostgreSQL 응답 지연이 JDBC 커넥션 점유 시간을 늘려 풀 대기로 이어지고, 연결이 끊기면 요청 실패가 발생한다는 것이다.

이 실험은 상품 조회만 다룬다. 결제 요청이나 PayPal 장애를 검증하지 않으며, ADR-0055의 Tomcat 설정이 전체 서비스에 적절하다는 증거가 아니다.

## 구성과 방법

- macOS arm64 로컬 호스트, JDK 21.0.13.1, PostgreSQL 18.6 컨테이너, Toxiproxy 2.12.0 컨테이너, k6 2.4.0.
- 실제 ATLAS 애플리케이션의 JDBC 연결이 Toxiproxy를 통해 격리된 PostgreSQL로 간다. `ATLAS_PAYMENT_PROVIDER=disabled`이므로 결제 성능 실험이 아니다.
- `load-tests/toxiproxy-catalog-probe.js`와 같은 요청 설정으로 `/api/products?limit=1`에 초당 20회, 10초간 개방형 도착률로 요청했다. 최대 VU 40, 클라이언트 타임아웃 5초. 각 조건 3회, 회당 200~201개 요청이다.
- 기준선에는 toxic이 없다. 지연은 PostgreSQL 프록시의 downstream에 latency 250ms, jitter 0ms, toxicity 1.0을 적용한다. 단절은 downstream `reset_peer`, timeout 0ms, toxicity 1.0이다.
- 원시 k6 summary JSON은 로컬 `/tmp/atlas-rdd-{baseline,latency,reset}-{1,2,3}.json`에 보관했다. 저장소에는 포함하지 않는다.

## 결과

| 조건 | 반복 | 실패율 | p95 | p99 | dropped iterations |
|---|---:|---:|---:|---:|---:|
| 기준선 | 1 | 0% | 5.8ms | 7.2ms | 0 |
| 기준선 | 2 | 0% | 6.6ms | 10.4ms | 0 |
| 기준선 | 3 | 0% | 5.3ms | 7.6ms | 0 |
| 지연 | 1 | 2.49% | 992.8ms | 1001.9ms | 0 |
| 지연 | 2 | 1.49% | 990.9ms | 1002.6ms | 0 |
| 지연 | 3 | 3.48% | 996.9ms | 1003.0ms | 0 |
| reset | 1 | 100% | 507.4ms | 515.4ms | 0 |
| reset | 2 | 100% | 506.9ms | 507.2ms | 0 |
| reset | 3 | 100% | 507.0ms | 509.0ms | 0 |

독립적인 지연 주입 1회에서는 HTTP 200을 받았고, reset 주입 1회에서는 HTTP 500을 받았다. 위 반복 측정의 실패율은 k6 `http_req_failed`이며 상태 코드별 분포는 수집하지 않았다. reset toxic 제거 후 별도 확인 요청은 HTTP 200이었고, 프록시에 toxic이 남아 있지 않았다.

## 스레드 덤프

250ms 지연 주입 및 동일 부하가 진행 중일 때 스레드 덤프 1개를 수집했다. 이름이 `http-nio`로 시작하는 20개 스레드 블록을 패턴 분류한 결과, 8개에서 PostgreSQL socket/query 프레임, 10개에서 Hikari 커넥션 획득 프레임을 관찰했다. 이는 해당 순간의 스냅샷이며 전체 기간의 평균이나 요청별 인과관계는 아니다. 원본은 로컬 `/tmp/atlas-rdd-latency-threads.txt`에 있다.

## 실제 PayPal Sandbox 전송 구간 확인

별도 Toxiproxy가 실제 `api-m.sandbox.paypal.com:443`으로 TCP를 전달하도록 구성했다. `openssl s_client`에서 SNI와 호스트명을 `api-m.sandbox.paypal.com`으로 지정해 TLS 인증서 검증이 성공했다. 인증서 검증을 끄거나 stub을 사용하지 않았다.

Python TLS handshake를 두 차례씩 측정한 결과, toxic이 없을 때 26ms와 23ms였고 downstream 500ms latency 주입 중에는 526ms와 527ms였다. toxic 제거 후 39ms에 다시 연결됐다. 이는 실제 Sandbox까지의 **전송 구간과 장애 주입이 연결됨**을 확인한 것이며, 결제 API나 인증 흐름에 대한 측정이 아니다.

별도 reset 확인에서는 downstream `reset_peer` 주입 중 TLS handshake 두 번이 각각 38ms와 22ms에 `SSLEOFError`로 실패했고, toxic 제거 후 TLS 1.3 연결이 31ms에 성공했다. 이것도 인증되지 않은 handshake 시험이며, 결제 결과나 복구를 검증하지 않는다.

JDK 21 `HttpClient`로 실제 Sandbox의 `/v1/oauth2/token`에도 별도 POST를 보냈다. JVM의 임시 hosts 파일에서 Sandbox 도메인을 `127.0.0.1`로 해석하고, 포트 18443의 Toxiproxy가 실제 Sandbox 443으로 전달했다. TLS 호스트명 검증은 유지했다. 인증정보는 보내지 않았으므로 기준선의 **401은 예상한 인증 거부**다.

| 조건 | 1회 | 2회 |
|---|---|---|
| 기준선 | 401, 628ms | 401, 811ms |
| downstream 500ms 지연 | 401, 1372ms | 401, 1812ms |
| downstream reset | `SSLHandshakeException`, 88ms | `SSLHandshakeException`, 88ms |
| toxic 제거 후 | 401, 802ms | 401, 830ms |

각 요청은 새 Java 프로세스에서 실행해 컴파일과 클라이언트 초기화의 영향이 있으며, 두 표본만으로 지연 분포를 판단할 수 없다. 실제 ATLAS의 Spring OAuth 클라이언트, 인증 성공, 주문 생성, 결제 결과와 복구는 검증하지 않았다.

### GitHub Actions에서 인증된 Sandbox OAuth 장애 주입

로컬에서는 실제 자격 증명을 찾지 못했지만, 기존 GitHub Actions Sandbox 계약 검증의 secret이 사용 가능함을 확인했다. 실험 브랜치의 동일 워크플로에서 그 secret을 runner에만 주입하고, Toxiproxy 뒤의 **실제** PayPal Sandbox OAuth 엔드포인트를 호출했다. 토큰과 응답 본문은 저장·출력하지 않았다. [실행 기록](https://github.com/Neo1228/ATLAS/actions/runs/44685731552)의 artifact에는 상태 코드·시간·예외 클래스만 담았다.

| 조건 | 반복 결과 |
|---|---|
| 기준선 | HTTP 200: 178.2ms, 591.7ms, 239.6ms |
| downstream 500ms 지연 | HTTP 200: 1237.7ms, 1561.4ms, 1117.2ms |
| downstream `reset_peer` | `SSLEOFError`: 18.7ms, 57.4ms, 6.4ms |

이 결과는 인증된 실제 Sandbox OAuth 호출과 TCP 장애 주입을 확인한다. 단, Python HTTPS 클라이언트의 순차 호출 3회이며 ATLAS 애플리케이션·결제 요청·동시 부하를 사용하지 않았다. 요청은 토큰 발급만 수행했고 주문 생성이나 capture는 하지 않았다. 첫 실행은 reset 제거 후 복구 호출을 포함하지 않아 복구를 주장하지 않는다. 결제 동작을 검증하지 않는 우회 경로였으므로 이 브랜치에서 OAuth 전용 워크플로 모드와 probe 스크립트는 제거했다.

## 취소 요청 뒤 capture 경쟁 정정

취소 경로를 다시 점검하면서, `AUTHORIZED` 상태에서 구매자가 취소를 요청한 뒤 복구·승인 콜백이 같은 attempt의 **새 capture를 시작할 수 있는** 경쟁을 확인했다. 기존에는 checkout 상태(`CANCEL_PENDING`)만 보고 결제 모듈의 phase 예약은 이를 알지 못했다.

- V58: `payment_attempt.capture_prohibited` 추가. 배포 시점에 이미 `CANCEL_PENDING`/`CANCELLED`인 checkout의 attempt에도 소급 적용한다.
- 취소 트랜잭션은 checkout 잠금 → attempt 잠금 순서로 펜스를 같은 트랜잭션에 기록한다(`Propagation.MANDATORY`). 취소가 롤백되면 펜스도 함께 롤백된다.
- `reservePhase`는 attempt 행 잠금 아래에서 `CAPTURE + AUTHORIZED + 펜스`인 경우에만 새 provider 호출을 거부한다. 이미 시작된 `CAPTURE_PENDING`/`UNKNOWN` capture는 provider 결과를 계속 복구하고, 그 결과가 `CAPTURED`이면 숨기지 않고 반영한다.

검증: `PaymentCaptureCancellationFenceTest`(단위 2건), `CheckoutCaptureFencePostgresIntegrationTest`(PostgreSQL 18.6 Testcontainers, 롤백·행 잠금 2건), `CheckoutCancelWithoutAttemptIntegrationTest`, `CheckoutBehaviorTest`, 마이그레이션·`ModuleBoundaryTest` 통과. 실제 PayPal에서의 경쟁 재현은 하지 않았다.

## 실제 결제 경로 장애 주입

### 구성

- `load-tests/paypal-real-fault/start-app.sh`: 실제 ATLAS jar를 실험 전용 PostgreSQL 18.6·Mailpit 컨테이너와 연결해 실행한다. JVM hosts 파일로 `api-m.sandbox.paypal.com`을 loopback으로 고정하고, 같은 호스트명의 Toxiproxy 포트를 거쳐 **실제** Sandbox로 보낸다. TLS SNI·인증서 검증은 실제 호스트명 그대로 유지한다. PayPal 자격 증명은 git 제외 파일 `.env.local`에서만 읽는다.
- `load-tests/paypal-real-fault/real-flow.mjs`: 실제 API로 판매자·상품·구매자·장바구니·checkout을 만든다. 결제 제출(실제 PayPal 주문 생성) 후 **Sandbox 구매자 계정으로 headless Chromium(Playwright)이 실제 PayPal 승인 화면에서 승인**한다. 이어서 return URL로 승인 반영·capture를 호출한다. 장애는 주문 생성 요청과 구매자 승인 후 첫 PayPal 단계인 `GET /v2/checkout/orders/{id}` 조회에 주입하고, 상품 조회 10 rps를 동시에 보낸다. 이 측정은 capture POST 자체에 장애를 주입한 것이 아니다.
- 진실 원천 비교: 실험 종료 후 Toxiproxy를 거치지 않는 직접 연결로 PayPal 주문을 조회한다. capture 수·금액을 ATLAS 상태·정산 journal과 비교한다(capture ≤ 1, `PAID` ⇔ capture 1건, capture 금액 = FX 스냅샷 금액, `PAID` ⇔ journal 1건).
- 한계로 남는 합성 요소: BOK ECOS 키가 없어 **FX 스냅샷만** `SYNTHETIC_FIXTURE`(1,400 KRW/USD)로 DB에 넣었다. 1,000원 상품은 $0.71로 실제 Sandbox에 청구된다. 나머지 PayPal 호출과 승인은 stub 없이 실제다.
- 승인 화면 순서(이메일 → 비밀번호 → 검토 → 승인 버튼)는 실제 Sandbox에서 먼저 확인했다. 승인마다 새 브라우저 컨텍스트를 쓴다. 같은 컨텍스트를 재사용하면 앞선 로그인 세션이 남아 화면 순서가 바뀐다.

### 측정 중 발견한 결함

장애 없는 기준선에서도 return 응답이 `UNKNOWN`(5.4s, 6.0s)이었고 `PAID`는 복구 worker를 거쳐 최대 25초 뒤에 됐다. 같은 authorization에 부분 capture 두 번을 보내 확인했다. `Prefer` 헤더가 없으면 capture 응답에는 `id, links, status`만 있고 `amount`가 없다. ATLAS의 금액 검증이 이 응답을 실패로 판정했다. `Prefer: return=representation`으로 고쳤고, 이후 return 응답은 `CAPTURED`(4.5s, 4.8s), `PAID` 수렴은 0.1s 이내였다. 테스트 stub은 항상 전체 응답을 줘서 이 결함을 재현하지 못했다. stub이 PayPal 기본 응답을 따르도록 바꾸자 헤더를 뺀 코드에서 95건 중 22건이 실패했다([#444](https://github.com/Neo1228/ATLAS/issues/444)).

### 결과 (2026-11-20, 조건별 checkout 3건, 상품 조회 10 rps 동시)

- Toxiproxy toxic은 HTTP 요청 경로를 볼 수 없는 TLS tunnel 전체에 적용된다. 따라서 승인 후 toxic은 return URL 처리의 첫 PayPal 요청인 주문 GET에 걸린다. GET이 성공한 뒤 authorization·capture에 개별 장애를 거는 실험은 수행하지 않았다. "수렴"은 return 응답 이후 복구 worker만으로 checkout이 종결 상태가 되기까지 걸린 시간이다(수동 reconcile 없음). PayPal capture 수는 Toxiproxy를 거치지 않는 직접 연결로 조회했다.

| 조건 | 결제 제출 응답 | 승인 반영(return) 응답 | 최종 상태 | 수렴 시간 | PayPal capture | 상품 조회 p95 / 실패 |
|---|---|---|---|---|---|---|
| 기준선 | 200 `AUTHORIZATION_PENDING`, 1.2~1.4s | 200 `CAPTURED`, 5.0~5.5s | 3/3 `PAID` | 0.1s 이내 | 각 1건 | 19ms / 0 |
| 주문 생성 3s 지연 | 200 `UNKNOWN`, 2.06s | 200 `CAPTURED`, 4.3~4.6s | 3/3 `PAID` | 0.1s 이내 | 각 1건 | 14ms / 0 |
| 주문 생성 응답 유실 | 200 `UNKNOWN`, 2.05s | 200 `CAPTURED`, 4.4~5.2s | 3/3 `PAID` | 0.1s 이내 | 각 1건 | 12ms / 0 |
| 승인 후 주문 GET 3s 지연 (수정 전) | 200, 1.0~1.2s | **500 `INTERNAL_ERROR`**, 2.04s | 3/3 `PAYMENT_PENDING` 고착 | 180s 내 미수렴 | 0건 | 17ms / 0 |
| 승인 후 주문 GET 3s 지연 (수정 후) | 200 | 200 `UNKNOWN`, 2.04s | 3/3 `PAID` | 0.6~36.9s | 각 1건 | 21ms / 0 |
| 승인 후 주문 GET 응답 유실 (수정 후) | 200 | 200 `UNKNOWN`, 2.07s | 3/3 `PAID` | 2.5~32.7s | 각 1건 | 143ms / 0 |
| 승인 후 주문 GET 연결 reset (수정 후) | 200 | 200 `UNKNOWN`, 0.2~0.3s | 3/3 `PAID` | 0.1~43.5s | 각 1건 | 52ms / 0 |

- 주문 생성 단계 장애에서는 제출 응답이 2초(연결 시간 초과 상한)에 `UNKNOWN`으로 끝났다. 이후 reconcile이 기존 PayPal 주문과 승인 URL을 되찾아 중복 주문 없이 결제됐다.
- 수정 전 승인 후 주문 GET 장애: `PayPalRestClient.getOrder`가 연결 시간 초과를 provider 장애로 감싸지 않아 500이 났다. 결제 시도는 `AUTHORIZATION_PENDING`, 복구 작업은 `WAITING_FOR_BUYER`로 남았다. 구매자가 이미 승인했는데도 승인 만료까지 다시 승인을 기다리는 상태다([#444](https://github.com/Neo1228/ATLAS/issues/444)).
- 수정 후 주문 GET 장애는 provider 오류로 처리되어 200 `UNKNOWN`으로 돌아오고, toxin을 제거한 뒤 복구 worker가 authorization과 capture를 수행했다. 측정 9건 모두 불변식 4개(capture ≤ 1, `PAID` ⇔ capture 1건, capture 금액 = FX 스냅샷, `PAID` ⇔ journal 1건)를 만족했다. 이 결과를 capture POST 자체의 장애 복구 검증으로 해석하지 않는다.
- 수렴 시간 편차는 복구 worker의 첫 재시도 지연과 backoff에서 온다(첫 건이 30s대인 패턴). 표본이 조건당 3건이라 분포를 판단할 수 없다.
- 응답 유실 조건에서 상품 조회 p95가 143ms로 커졌다. 단일 실행이라 원인(실험 프로세스 CPU, 연결 점유 등)을 분리하지 못했다.

## 해석과 한계

- 이 설정에서 JDBC 응답 지연은 조회 지연과 일부 실패를 동반했고, 덤프에서 DB 소켓 대기와 커넥션 획득 대기가 동시에 관찰됐다. 이 결과만으로 특정 쿼리나 설정값이 실패의 단독 원인이라고 단정할 수 없다.
- 장애 주입 전후의 Tomcat 200과 40 설정을 비교하지 않았다. 처리율, 실패 상태 코드별 분포, 커넥션 active/pending 시계열도 수집하지 않았다.
- 각 조건은 순서대로 3회 실행했으며 별도 예열 구간과 조건별 무작위 배치를 두지 않았다. 호스트의 다른 프로세스와 캐시 영향도 배제하지 못했다.
- 로컬 PayPal Sandbox 자격 증명을 찾지 못했다. 과거 `/tmp/atlas-app.env`의 PayPal·ECOS 값은 테스트용 placeholder였으므로 실제 자격 증명으로 취급하지 않았다. 사용자 요청에 따라 PayPal stub으로 대체하지 않았다.
- PayPal 지연·단절, 결제와 조회의 혼합 부하, 장애 격리 후보의 전후 비교는 미완료다. 따라서 이 결과로 벌크헤드 도입이나 ADR-0055 설정값을 확정하지 않는다.
