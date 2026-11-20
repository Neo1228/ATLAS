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

## 실제 결제 경로 장애 주입 하네스

`load-tests/paypal-real-fault/`는 실제 ATLAS jar를 Toxiproxy 뒤의 실제 `api-m.sandbox.paypal.com`(TLS 검증 유지)에 연결하고, 상품 조회와 결제 요청을 k6로 섞어 보낸다. 명시적 opt-in, loopback 전용 DB·포트, checkout 소유자·금액(1만 원·500센트 이하)·`SYNTHETIC_FIXTURE` FX 스냅샷 사전 검증을 통과해야 실행된다. 자격 증명은 환경 변수로만 받고 출력하지 않는다.

**미실행.** 로컬에 실제 Sandbox 자격 증명이 없고, capture는 Sandbox 구매자 계정의 브라우저 승인이 필요하다. 승인을 자동화하거나 가장하지 않았다. 문법 확인(`bash -n`, `k6 inspect`)만 수행했다.

## 해석과 한계

- 이 설정에서 JDBC 응답 지연은 조회 지연과 일부 실패를 동반했고, 덤프에서 DB 소켓 대기와 커넥션 획득 대기가 동시에 관찰됐다. 이 결과만으로 특정 쿼리나 설정값이 실패의 단독 원인이라고 단정할 수 없다.
- 장애 주입 전후의 Tomcat 200과 40 설정을 비교하지 않았다. 처리율, 실패 상태 코드별 분포, 커넥션 active/pending 시계열도 수집하지 않았다.
- 각 조건은 순서대로 3회 실행했으며 별도 예열 구간과 조건별 무작위 배치를 두지 않았다. 호스트의 다른 프로세스와 캐시 영향도 배제하지 못했다.
- 로컬 PayPal Sandbox 자격 증명을 찾지 못했다. 과거 `/tmp/atlas-app.env`의 PayPal·ECOS 값은 테스트용 placeholder였으므로 실제 자격 증명으로 취급하지 않았다. 사용자 요청에 따라 PayPal stub으로 대체하지 않았다.
- PayPal 지연·단절, 결제와 조회의 혼합 부하, 장애 격리 후보의 전후 비교는 미완료다. 따라서 이 결과로 벌크헤드 도입이나 ADR-0055 설정값을 확정하지 않는다.
