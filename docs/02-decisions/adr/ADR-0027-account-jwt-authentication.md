---
id: ADR-0027
title: Buyer·Seller 계정 인증과 JWT session
status: Accepted
owner: Project Owner
date: 2026-11-10
links:
  decisions: [D-082]
  requirements: [FR-018, BR-018, INV-015, Q-007]
  issues: [206]
---
# ADR-0027: Buyer·Seller 계정 인증과 JWT session

- 상태: ✓ 확정; implementation 대기
- 결정일: 2026-11-10
- 결정 ID: [D-082](../../00-governance/decision-log.md)
- 관련 요구사항: [FR-018/BR-018/INV-015/Q-007](../../01-requirements/requirements-index.md)
- 관련 Issue: [Issue #206](https://github.com/Neo1228/ATLAS/issues/206)
- 선행 결정: [ADR-0026 PayPal](ADR-0026-paypal-sandbox-payments.md)

## 배경

현재 API는 local `permitAll`; `buyerRef`와 `sellerId`는 요청 값이며 계정·인증 정보와 연결되지 않음. Seller는 UUID만 보유하고 Buyer account, password, login, role, ownership relation은 미구현. JWT 발급만 더하면 path에 다른 사용자의 ID를 넣는 IDOR가 그대로 남음.

## 결정

- Buyer·Seller를 하나의 Account에 연결하는 first-party identity model. Account는 immutable UUID, normalized unique email, password hash, status를 소유.
- Buyer registration은 account와 Buyer identity를 atomic하게 생성. Account당 Buyer identity 1개. Authenticated account는 Seller onboarding으로 Seller profile을 추가할 수 있으며 account-to-seller relation은 명시적으로 보유; Account당 Seller profile은 여러 개 허용.
- Existing synthetic `buyerRef`·Seller rows는 account에 자동 연결하지 않음. 신규 Buyer/Seller onboarding만 인증 접근 가능. 기존 Cart/Order/Seller data는 owner claim 경로가 없어 인증 사용자에게 공개하지 않으며, local data cleanup은 별도 수동 작업.
- Login은 email/password. Password hashing은 Spring Security `DelegatingPasswordEncoder`의 PBKDF2 v5.8 defaults 사용. 128자 입력 계약과 호환되며 algorithm id를 저장해 향후 encoder migration 지원. Unknown email도 process-generated dummy hash로 PBKDF2 검증을 수행해 계정 존재 여부에 따른 명확한 timing 차이를 완화. Custom crypto 금지. 응답·로그·오류에 password/token 노출 금지; login 실패는 generic error.
- Spring Security OAuth2 Resource Server/Nimbus JWT support 사용. Access token은 서명 검증, 고정 algorithm allow-list, `iss`, `aud`, `sub`, `iat`, `exp` 검증. Initial access lifetime 10분; amount는 운영 SLA가 아닌 보수적인 초기 개발 선택. Private signing key는 환경별 ignored local secret file/env로 주입, Git·docs·Actions 일반 log에 기록 금지.
- Refresh token은 고엔트로피 opaque random value. DB에는 token hash·family/session ID·account ID·expiry·consumed/revoked state만 저장. Refresh token은 7일, family absolute expiry는 30일. Refresh 성공 시 매번 rotate. 이전 token 재사용 시 family revoke. Logout은 현재 family revoke.
- 모든 protected controller operation에서 authenticated account와 Buyer/Seller resource owner 일치 검증. Client가 보낸 `buyerRef`·`sellerId`를 principal 증거로 인정하지 않음.
- Default deny. 공개 route는 login/register/refresh 및 명시적으로 검토된 catalog browse만. PayPal return/cancel callback과 서명 검증 webhook은 필요한 경로만 anonymous로 허용; provider callback은 내부 persisted state와 signature/provider token 검증을 계속 요구.
- API는 refresh token을 refresh/logout request body와 token response body로 전달; URL·query·log 금지. Client는 secure storage 사용. Browser storage integration은 미구현; browser cookie 도입 시 HttpOnly/Secure/SameSite와 CSRF 방어를 별도 구현.
- Core authentication/authorization behavior는 stateless access JWT + stateful refresh session. 별도 IdP/social login/MFA는 추가하지 않음.

## 선택 이유와 대안

- `permitAll` 유지: 다른 사용자 데이터에 대한 임의 ID 접근이 가능해 제외.
- Access JWT만: logout·강제 revoke가 `exp`까지 지연되어 계정 정지·token 탈취 대응에 부적합.
- Long-lived JWT / refresh token rotation 없음: 탈취된 bearer credential replay window가 길어 제외.
- Cookie session만: 단일 monolith에서는 간결하고 즉시 revoke 가능. 현재 요구는 JWT를 명시하므로 access API에는 JWT를 사용하되 refresh session을 DB에 두어 revoke와 replay detection을 확보. 이 설계는 JWT의 완전 무상태성을 선택하지 않음.
- Home-grown JWT, password hashing, Spring Security filter: 검증이 불완전해지기 쉬워 제외. Spring Security Resource Server, Nimbus, `PasswordEncoder`, JDK `SecureRandom` 사용.
- BCrypt 기본 72-byte input 한계가 128자 입력 계약과 충돌. 추가 runtime dependency 없이 Spring Security PBKDF2 encoder를 사용해 입력 잘림·요청 실패 방지. Cost 증가는 실제 local workload 측정 후 검토.
- Buyer persona는 account당 1개, Seller profile은 account당 여러 개 허용. Multi-buyer personas, staff roles, permissions matrix는 후속 범위.

## 흐름

```mermaid
sequenceDiagram
    participant U as Buyer/Seller
    participant A as ATLAS API
    participant DB as PostgreSQL
    U->>A: Register/Login (email, password)
    A->>DB: Verify account and password hash
    A-->>U: Short-lived access JWT + opaque refresh token
    U->>A: Protected request (Bearer JWT)
    A->>A: Validate token and principal/resource ownership
    U->>A: Refresh with current refresh token
    A->>DB: Consume old hash; persist replacement hash
    A-->>U: New access JWT + rotated refresh token
    U->>A: Replay consumed refresh token
    A->>DB: Revoke refresh token family
```

## 검증과 한계

- Registration/login: duplicate normalized email, invalid password, generic unknown-account response, disabled account.
- JWT: wrong signature/algorithm/issuer/audience, expired token, missing claims, key rotation configuration.
- Refresh: single-use rotation, concurrent refresh race, replay-family revoke, expired/revoked session, logout.
- Ownership: Buyer A→Buyer B cart/order/checkout/return/shipment denial; Seller A→Seller B product/inventory/coupon/order/settlement denial.
- Security boundary: anonymous protected endpoint denied; explicitly public catalog/login/callback/webhook routes still obey route-specific validation; authenticated role cannot bypass ownership.
- Testcontainers PostgreSQL for account/session uniqueness, transaction rollback, rotation race, and migration. ArchUnit verifies application/security dependency boundaries.
- Local-only API remains default bind `127.0.0.1`. Auth does not make the app public-ready; rate limiting, email verification/password recovery, MFA, privacy retention, operational key management, and production deployment review remain future work.

## 참고 자료

- [Spring Security OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [OWASP JSON Web Token Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html)
- [Auth0 Token Best Practices](https://auth0.com/docs/secure/tokens/token-best-practices)
- [OpenReplay — Choosing an Authentication Strategy for a New Web App](https://blog.openreplay.com/authentication-strategy-web-app)
