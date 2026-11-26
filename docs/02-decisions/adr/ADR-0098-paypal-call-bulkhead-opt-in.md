# ADR-0098: PayPal 호출의 요청 스레드 동시 상한(bulkhead)을 옵트인으로 둔다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-26
- 관련 결정: [ADR-0055](ADR-0055-extreme-load-boundaries-and-infra-gates.md)(서킷 브레이커는 점진 복구 없이 넣지 않는다), [ADR-0075](ADR-0075-keep-payment-in-process.md), [ADR-0030](ADR-0030-paypal-currency-boundary.md)(UNKNOWN 복구)
- 근거: [EXP-108](../../03-experiments/EXP-108-dependency-slowness-thread-saturation.md), 이슈 [#696](https://github.com/Neo1228/ATLAS/issues/696)

## 문제

요청 스레드는 PayPal과 위치 서비스를 동기로 기다린다. PayPal이 느리면 묶이는 스레드 수는 느린 호출 도착률 × 지연이라(Little의 법칙) Tomcat 스레드 40개를 채울 수 있고, 채우면 PayPal을 쓰지 않는 요청까지 밀린다. 저장소의 breaker는 `RedisStockAdmissionControl`의 최소 2초 breaker 하나뿐이고 외부 의존성의 동시 호출 상한은 없다. 어느 보호 장치가 증거로 정당화되는지(breaker, bulkhead, read timeout 단축)와 새 dependency(Resilience4j)가 필요한지를 정해야 한다.

## 결정

PayPal 호출에만 **요청 스레드 동시 호출 상한**을 옵트인으로 둔다. 기본값은 상한 없음(이전 동작)이다.

- 설정: `atlas.payment.paypal.bulkhead.max-concurrent-calls`(환경 변수 `ATLAS_PAYPAL_BULKHEAD_MAX_CONCURRENT_CALLS`), 기본 0(상한 없음). 켤 때는 Tomcat 최대 스레드(40)보다 작은 값(20~30)을 둔다. 20과 30 모두 측정했다(EXP-108).
- 구현: 결제 모듈이 소유하는 `PayPalCallBulkhead`(`provider.paypal`, 약 50줄)가 `Semaphore.tryAcquire()`로 요청 스레드 호출만 센다. `PayPalRestClient`의 모든 HTTP 호출(주문 생성·조회, authorize, capture, void, refund 조회·실행, webhook 검증)이 이를 거친다.
- 요청 스레드 판별: `RequestContextHolder.getRequestAttributes() != null`(서블릿 요청을 처리하는 동안만 값이 있다). 복구 worker 같은 스케줄러 스레드는 세지도 거절하지도 않는다. 상한이 worker를 막으면 UNKNOWN 복구가 멈추기 때문이다.
- 거절: 상한을 넘으면 **기다리지 않고, 아무것도 보내기 전에** `PayPalBusyException`을 던진다. 이는 `PayPalUnavailableException`의 하위 형식이라 기존 처리(`PAYMENT_PROVIDER_UNAVAILABLE`, 결제 시도는 `UNKNOWN`, 구매자 응답 503)를 그대로 지난다. 호출 전에 거절하므로 PayPal에는 아무것도 도달하지 않았고(확정 미전송), `UNKNOWN`의 같은 `PayPal-Request-Id` 재시도가 중복 capture를 만들지 않는다(PostgreSQL 시험 `rejectedCaptureSendsNothingAndTheRetryCapturesExactlyOnce`).
- 관측: Micrometer 계수기 `atlas.payment.paypal.bulkhead.rejected`.
- 직접 구현한 이유: 필요한 것은 세마포어 하나와 계수기 하나다. Resilience4j `Bulkhead`는 같은 일을 하지만 새 dependency(그리고 Spring Boot 4.1.2와의 호환 조합 확인)와 설정 체계를 들이고, 요청 스레드만 세는 판별은 어차피 직접 써야 한다. breaker를 도입하지 않으므로 Resilience4j가 주는 나머지(상태 기계, 슬라이딩 창)는 쓰지 않는다. micrometer-core는 이미 애플리케이션 classpath에 있고 결제 모듈이 직접 선언만 한다(새 외부 dependency 없음).
- 도입하지 않는 것: breaker(점진 복구 포함), read timeout 변경, 위치 서비스 상한. 이유는 아래 표와 EXP-108이다.

## 근거 (EXP-108, 단일 호스트, 칸당 n=3)

| 측정 | 값 |
|---|---|
| 지연 10초, 느린 호출 4/s(λ·h = 40) | 묶인 스레드 평균 39.5~39.7(`jcmd`), busy 40, 카탈로그 D = 0(3/3) |
| 지연 10초, 8/s(λ·h = 80) | busy 40, 정상 상태 구간 카탈로그 요청 188~189건이 모두 저하(D = 1.0, 3/3), 카탈로그 p95 14.4초 |
| 위치 서비스 읽기 상한 2초, 40/s(λ·h = 80) | busy 40, D = 0.985~1.0(3/3) |
| 상한 20(8/s × 10초) | busy 20~21, D = 0(3/3), 카탈로그 p95 7~8 ms, PayPal 호출 137~138건만 도달 |
| 상한 30 | busy 30~32, D = 0(3/3) |
| 읽기 상한 3초(같은 입력) | busy 25~26, D = 0(3/3), 느린 호출 321건이 모두 PayPal에 도달해 3초에 끊김 |
| 느린 호출 비율 breaker(단순) | 첫 열림까지 11.1초, 그 전에 busy 40, 장애 구간 D = 0.22(3/3) |
| 느린 호출 비율 breaker(점진 복구) | 첫 열림까지 11.1초, 그 전에 busy 40, 장애 구간 D = 0.22(3/3), 장애 시작 뒤 40초 안에 열림 3회 |

## 대안과 버린 이유

| 대안 | 버린 이유 |
|---|---|
| 느린 호출 비율 breaker(Resilience4j식 `slowCallDurationThreshold`·`slowCallRateThreshold`) | 호출이 끝나야 창에 들어가므로 첫 판단이 읽기 상한(10초)보다 빠를 수 없다. 8/s에서는 40개가 5초에 차므로 열리기 전에 이미 포화했다(busy 40, 6/6 반복). 카탈로그 저하는 장애 구간의 약 22%로 bulkhead(0%)보다 컸다 |
| 점진 복구 breaker(ADR-0055의 33/67/100%) | 위와 같은 이유로 첫 열림이 늦고, 장애 구간 안에서 3번 열렸다. 점진 단계가 느린 호출을 일부 통과시켜 busy 최댓값은 단순 breaker와 같았다(40). 복잡도(상태 기계, 단계 타이머)만 늘었다 |
| 읽기 상한을 3초로 낮춘다 | 같은 입력(8/s)에서는 포화를 막았지만(λ·h = 24) 문턱이 14/s(40/3)로 옮겨 갈 뿐이다. PayPal이 3~10초에 응답하는 정상 호출도 전부 끊어 `UNKNOWN`과 복구 작업을 늘리고, capture가 PayPal에서 이미 commit된 뒤 응답을 못 받는 경우를 늘린다(EXP-050) |
| Resilience4j 도입 | 위 "직접 구현한 이유". 새 dependency와 호환 확인이 필요한데 필요한 기능은 세마포어 하나다 |
| 기본값을 켠다(예: 30) | 평상시 PayPal 호출이 몇 개 동시에 도는지(실제 PayPal 지연)를 측정하지 못했다. 실제 Sandbox·운영 지연 분포를 몰라 기본 동작을 바꾸지 않는다 |
| 위치 서비스에도 같은 상한을 둔다 | 같은 결함이 측정됐지만(40/s에서 D ≈ 1.0) 한 요청이 위치별·SKU별로 순차 호출하고 호출 종류(조회·예약·claim)마다 의미가 달라 거절 경로와 상태 영향을 따로 검증해야 한다. 두 번째 사본이 생기는 시점에 비용을 비교한다(패키지 구조 규칙). 재검토 조건에 넣었다 |
| 아무것도 하지 않는다 | **기각.** 8/s × 10초에서 PayPal을 쓰지 않는 요청 전부가 저하되는 것이 3/3 반복에서 측정됐다 |

## 결과와 한계

- 재검토 조건(하나라도 측정으로 확인되면): ① 운영 지표에서 `tomcat.threads.busy`가 35 이상으로 10초 넘게 유지되는데 PayPal 호출 p95가 3초 이상이다 → 상한을 켠다(권장 20~30). ② 상한을 켠 뒤 `atlas.payment.paypal.bulkhead.rejected`가 평상시 PayPal 호출의 0.5%를 넘는다 → 상한을 올린다. ③ 위치 서비스 호출이 요청 스레드를 점유하는 시간이 읽기 상한(2초) 근처이고 도착률이 20/s를 넘는다 → 위치 호출에 같은 상한을 둔다(두 번째 사본이므로 공유 위치를 이때 정한다). ④ PayPal 지연이 3초를 넘는 상태가 장기 지속되는 운영 데이터가 생긴다 → breaker를 다시 비교한다(점진 복구가 필수, ADR-0055).
- 한계: 가짜 PayPal은 용량 제한이 없어 breaker가 막으려는 되돌아온 폭주 진동은 만들어지지 않는다. 웹훅 검증 경로를 대표로 썼고 구매자 `POST /payment` 경로를 부하로 지나지 않았다(같은 `PayPalRestClient`를 쓴다). 호스트가 공유되어 시간 값은 맥락이다. 위치 서비스는 40/s 한 점만 측정했다(20/s는 범위 밖: 예산).
- 사용자 승인: 이 ADR은 에이전트가 근거를 정리한 제안이며 사용자 승인은 확인되지 않았다.

## 실패 경로와 되돌리기

기본값 0이면 코드 경로는 `Semaphore`를 만들지 않고 호출을 그대로 위임한다(이전 동작). 켠 뒤 문제가 있으면 설정을 0으로 되돌린다. 코드를 되돌리려면 이 PR을 되돌린다(스키마·API 변경 없음).
