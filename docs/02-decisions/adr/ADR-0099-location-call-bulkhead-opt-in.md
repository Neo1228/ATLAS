# ADR-0099: 위치 서비스 호출의 요청 스레드 동시 상한을 옵트인으로 둔다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-27
- 관련 결정: [ADR-0098](ADR-0098-paypal-call-bulkhead-opt-in.md)(PayPal 호출 상한), [ADR-0055](ADR-0055-extreme-load-boundaries-and-infra-gates.md)(breaker는 점진 복구 없이 넣지 않는다), [ADR-0084](ADR-0084-checkout-ttl-authorize-slo-and-load-contract.md)(부하 계약)
- 근거: [EXP-109](../../03-experiments/EXP-109-resilience-reapproach.md), 이슈 [#706](https://github.com/Neo1228/ATLAS/issues/706)

## 문제

요청 스레드는 위치 서비스(`HttpLocationClient`, 연결 250 ms, 읽기 2 s)를 동기로 기다린다. 위치 서비스가 느려지면 묶이는 스레드 수는 호출 도착률 × 지연이고 Tomcat 스레드 40개를 채우면 위치 서비스를 쓰지 않는 요청까지 밀린다. EXP-108은 40/s에서만 이를 측정했고 20/s는 재지 않았다.

## 결정

위치 서비스 호출에 요청 스레드 동시 호출 상한을 옵트인으로 둔다. 기본값은 상한 없음(이전 동작)이다.

- 설정: `atlas.order-inventory-reservation.bulkhead.max-concurrent-calls`(환경 변수 `ATLAS_ORDER_INVENTORY_RESERVATION_BULKHEAD_MAX_CONCURRENT_CALLS`), 기본 0. 켤 때는 평상시 도착률 × 평균 지연의 1.5배 이상이고 Tomcat 최대 스레드(40)보다 작은 값(권장 20)을 둔다.
- 구현: `HttpLocationClient`와 같은 패키지의 `LocationCallBulkhead`(약 35줄)가 `Semaphore.tryAcquire()`로 요청 스레드 호출만 센다. `HttpLocationClient`의 모든 HTTP 호출(조회, 예약, 취소, claim, release, restore, restock)이 이를 지난다. 요청 스레드 판별은 `RequestContextHolder.getRequestAttributes() != null`이다(ADR-0098과 같다). 복구 worker(`LocationReservationServiceImpl.recoverDue`, `LocationRestockServiceImpl`)는 세지도 거절하지도 않는다.
- 거절: 상한을 넘으면 기다리지 않고 위치 서비스에 아무것도 보내기 전에 `LocationClientException(RETRYABLE_UNKNOWN, "INVENTORY_BUSY")`를 던진다. 호출 쪽이 읽기 시간 초과를 받았을 때와 같은 경로(같은 멱등 키로 다시 시도, 복구 worker가 수렴)로 처리되므로 새 상태가 생기지 않는다.
- 관측: Micrometer 계수기 `atlas.inventory.location.bulkhead.rejected`(`HttpLocationClient`가 등록한다. 위치 호출 코드는 이미 application 모듈이라 지표 의존이 새로 생기지 않는다).
- 공유하지 않는 이유: `PayPalCallBulkhead`(결제 모듈, 다른 예외 타입)와 `LocationCallBulkhead`(application 모듈)는 같은 약 20줄 패턴의 두 번째 사본이다. 모듈 경계상 결제 모듈이 위치 예외를 모르고 application이 결제 provider 패키지의 package-private 클래스를 쓸 수 없어, 공유하려면 새 모듈 간 의존이 필요하다. 세 번째 사본이 생기면 공유 위치를 정한다(패키지 구조 규칙).

## 근거 (EXP-109, 위치 서비스 칸, 가짜 위치 서비스 시간 5 s라 읽기 상한 2 s가 h, 단일 호스트)

| 측정 | 값 |
|---|---|
| 위치 호출 10/s(λ·h = 20) | D = 0(3/3), 위치 POST p50 2.01 s |
| 위치 호출 20/s(λ·h = 40) | D = 0(3/3), 위치 POST p50 2.09 s |
| 위치 호출 40/s(λ·h = 80) | D = 1.0(3/3), 위치 POST p50 5.6 s, 카탈로그 p95 약 7 s |
| 위치 호출 30/s(λ·h = 60) | D = 0.990~0.993(3/3), 위치 POST p50 4.3 s |
| 상한 20, 40/s | D = 0(3/3), 가짜 서버가 받은 호출 210건(상한 없음은 410건), 위치 POST p50 8~9 ms |
| 상한 20, 30/s | D = 0(3/3), 가짜 서버가 받은 호출 210건, 위치 POST p50 9 ms |
| 상한 20, 20/s | D = 0(3/3) |
| 상한 20, 장애 없음 40/s | D = 0(3/3), 거절 계수기 0·0·0, 가짜 서버가 받은 호출 4,574~4,666건(상한 없음 4,579~4,678건). 같은 상한을 장애 40/s에 켰을 때 계수기는 607·608·608로 거절이 실제로 센다 |

세션당 위치 호출은 최소 3건(가용 조회, 예약, claim)이라 ADR-0084의 지속 10 세션/s는 위치 호출 30/s이다. 위치가 읽기 상한(2 s)까지 응답하지 않으면 λ·h = 60으로 포화 문턱(40)을 넘고, 30/s 칸에서 D ≈ 0.99로 확인했다.

## 대안과 버린 이유

| 대안 | 버린 이유 |
|---|---|
| 아무것도 하지 않는다 | 40/s에서 무관한 GET이 전부 저하됐고(D = 1.0, 3/3) 계약 도착률의 산술이 문턱을 넘는다 |
| 위치 호출에 breaker를 둔다 | PayPal 칸에서 완료한 호출 기반 breaker는 첫 열림이 6~10 s로 포화 뒤였고 점진 복구도 나아지지 않았다(EXP-109). 위치 읽기 상한은 2 s라 판단이 더 빠르지만 같은 구조적 한계가 있다 |
| 적응형 한도(AIMD) | PayPal 칸에서 정적 상한과 D가 같고 거절·UNKNOWN이 더 많았다(EXP-109) |
| 읽기 상한(2 s)을 더 줄인다 | 위치 서비스는 정상 지연이 짧아 이미 2 s다. 더 줄이면 정상 호출을 끊는다. 이번에 측정하지 않았다 |
| 기본값을 켠다 | 운영 위치 서비스 지연을 측정할 수 없다. 기본 동작을 바꾸지 않는다 |
| `PayPalCallBulkhead`와 한 클래스로 공유 | 위 "공유하지 않는 이유" |

## 결과와 한계

- 재검토 조건(하나라도 측정으로 확인되면): ① 운영 지표에서 위치 호출이 요청 스레드를 점유하는 시간이 읽기 상한(2 s) 근처이고 `tomcat.threads.busy`가 35 이상으로 10 s 넘게 유지된다 → 상한을 켠다(권장 20). ② 상한을 켠 뒤 `atlas.inventory.location.bulkhead.rejected`가 평상시 위치 호출의 0.5%를 넘는다 → 상한을 올린다. ③ 위치 호출 도착률이 20/s를 넘고 평균 지연이 1 s를 넘는다 → 상한을 켠다(30/s에서 포화를 측정했다). ④ `PayPalCallBulkhead`와 같은 패턴의 세 번째 사본이 필요해진다 → 공유 위치를 정한다.
- 한계: 가짜 위치 서버(고정 서비스 시간)만 썼고 운영 위치 서비스는 측정할 수 없다. 위치 상한 칸은 n = 3이다. 거절 계수기는 장애 없는 40/s(0)와 장애 40/s(607~608)에서 수집했고 30/s 칸에서는 수집하지 않았다.
- 사용자 승인: 이 ADR은 에이전트가 근거를 정리한 제안이며 사용자 승인은 확인되지 않았다.

## 실패 경로와 되돌리기

기본값 0이면 `Semaphore`를 만들지 않고 호출을 그대로 위임한다(이전 동작). 켠 뒤 문제가 있으면 설정을 0으로 되돌린다. 코드를 되돌리려면 이 PR을 되돌린다(스키마·API 변경 없음).
