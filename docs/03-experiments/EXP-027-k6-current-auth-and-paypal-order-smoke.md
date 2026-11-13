# EXP-027 — K6 인증·PayPal order smoke 검증

**상태:** checkout-only pilot 측정 완료 · EXP-027 실행에서 PayPal Sandbox order 미호출 · 별도 Sandbox E2E는 Issue #254/PR #262에서 완료

**연결:** [Issue #248](https://github.com/Neo1228/ATLAS/issues/248)

## 목적

- K6 시나리오를 현재 JWT·Buyer/Seller ownership·PayPal API contract에 정합
- 일반 checkout 측정과 외부 PayPal 요청 분리
- 실제 실행 결과로 인증·checkout 경로 검증

## 현재 실행 계약

| 모드 | 검증 범위 | PayPal 요청 |
|---|---|---:|
| `checkout-only` | 인증·cart·checkout 예약 | 0 |
| `paypal-sandbox-order-smoke` | API `AUTHORIZATION_PENDING`·checkout `PAYMENT_PENDING`·approval URL 확인 | 1회 |
| `paypal-sandbox-verify` | manual Buyer approval 뒤 `CAPTURED`·`PAID`·inventory `CLAIMED` 확인 | 0 |
| Coupon redemption | coupon redemption cap 경합 | 0 |

- Order smoke는 Buyer approval/capture 전까지. `PAYPAL_SHOW_APPROVAL_URL=true`로 URL을 local terminal에만 출력; verify mode는 callback 이후 상태 확인
- `paypal-sandbox-order-smoke`: `buyer-checkout.js`·`gate4.js`에서 order 생성 1회
- Capture·settlement 결과를 검증하지 않으므로 결제 완료·정산 성능 근거로 사용 금지
- 관련 코드: [JWT helper](../../load-tests/lib/auth.js) · [Buyer checkout](../../load-tests/buyer-checkout.js) · [Gate4](../../load-tests/gate4.js) · [Coupon](../../load-tests/coupon-redemption.js)

## Local checkout pilot

| 항목 | 값 |
|---|---:|
| K6 profile / mode | `one-seller` / `checkout-only` |
| 목표 도착률 | 1 session/s × 1s |
| 계획 session / SKU 재고 | 1 / 2 |
| 실제 시작 iteration | 2 |
| checkout 완료 | 2 |
| 오류 / dropped iteration | 0 / 0 |
| 측정 HTTP RPS | 4.9856 |
| 완료 checkout / 설정 window | 2 / 1s |
| PayPal 요청 | 0 |

> [!NOTE]
> 1초 correctness smoke 결과. 지속 부하·용량·RPS/TPS 성능 비교 또는 SLO 근거 미해당. `completed checkout / 설정 window`는 이 실행의 단순 비율이며 안정 처리량으로 해석 금지. Issue #254/PR #262의 Sandbox E2E는 별도 실행 근거.

### 관찰 및 수정

1. **Buyer cart ownership**
   - JWT Buyer가 다른 Seller의 SKU를 cart에 담을 때 HTTP 403 발생
   - 원인: `AccountOwnershipInterceptor`가 모든 `{sellerId}` 경로에 Seller ownership을 강제
   - 수정: Seller ownership 검증은 `/api/sellers/` 경로에 한정. Buyer 경로의 `buyerRef` ownership 검증 유지
   - 회귀 테스트: [`AccountOwnershipInterceptorTest`](../../ATLAS-application/src/test/java/com/atlas/application/security/AccountOwnershipInterceptorTest.java)
- `/api/sellers/` 외부에 Seller 소유 API를 추가하면 ownership 검증도 함께 추가

1. **K6 종료 경계 iteration**
   - `1 session/s × 1s`, 재고 1에서 두 번째 iteration이 시작돼 `INSUFFICIENT_STOCK` 발생
   - Synthetic fixture 재고에 1개 guard 추가
   - Guard는 해당 pilot의 false stock exhaustion 방지용. 트래픽 용량 가정으로 해석 금지

1. **Container build 순서**
   - [`ATLAS-application/Dockerfile`](../../ATLAS-application/Dockerfile)은 기존 `application-module.jar` 복사
   - Java 변경 후 `./gradlew :application-module:bootJar` 완료 뒤 Compose image 재생성 필요

## 검증

- K6 2.3.0 `inspect`: 활성 시나리오 8종·모드 구문 및 import 확인
- Compose `loadtest` profile config: 통과
- Full `./gradlew --no-daemon check :application-module:bootJar`: 57 suites · 274 tests · 실패/오류/건너뜀 0
- `PaymentLifecycleIntegrationTest.paypalAuthorizationCaptureAndReplayUseTheLocalProvider`: `AUTHORIZATION_PENDING`·approval URL assertion
- `AccountOwnershipInterceptorTest`: 통과
- Buyer flow·coupon integration tests: 통과
- 실제 1초 checkout pilot: 2/2 완료, 오류 0, dropped iteration 0

## 제한 및 이력

- EXP-027 checkout-only pilot에서는 PayPal Sandbox order 미호출. 별도 Sandbox E2E (2026-11-13): KRW 2,000 → USD 1.50 (BOK 1,332.4 KRW/USD, 기준일 2026-11-13, provider amount 150 cents), Buyer approval·capture 성공, PayPal order `COMPLETED`, UNKNOWN capture 응답의 order GET 복구, K6 verification 1/1 `CAPTURED`·`PAID`·`CLAIMED`, KRW settlement journal 균형 확인 (PSP_CLEARING debit 2,000 / SELLER_PAYABLE credit 2,000). 근거: [Issue #254](https://github.com/Neo1228/ATLAS/issues/254) · [PR #262](https://github.com/Neo1228/ATLAS/pull/262) · [README PayPal Sandbox](../../README.md#paypal-sandbox)
- 별도 E2E는 성공 capture·정산 경로의 근거이며 EXP-027 1초 pilot의 처리량·부하 결과와 무관. Expiry/approval 경합 검증 근거로도 사용 금지.
- PayPal provider result `PENDING`은 API `AUTHORIZATION_PENDING`으로 매핑. Buyer approval 이후 authorization·capture 진행
- 현재 expiry sweep는 `RESERVED`만 조회. `PAYMENT_PENDING` reservation은 provider 결과 확인·reconciliation 전까지 `checkoutExpiresAt` 뒤에도 유지. 단순 TTL로 inventory를 해제하지 않는 안전 우선 계약: [ADR-0029](../02-decisions/adr/ADR-0029-order-payment-recovery-contract.md)
- EXP-027 pilot의 settlement write path 측정 0건. 별도 E2E의 균형 원장은 correctness 근거이며 write throughput 측정 근거 미해당.
- [EXP-022](EXP-022-settlement-app-write-cost.md)는 과거 checkout/capture 결과 보존. 현재 PayPal 흐름과 직접 비교 불가
