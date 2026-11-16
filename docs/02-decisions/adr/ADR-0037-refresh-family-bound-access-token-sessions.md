# ADR-0037: Refresh family 기반 access token 폐기와 session 관리

- 상태: 채택
- 결정일: 2026-11-17
- 관련 이슈: [#291](https://github.com/Neo1228/ATLAS/issues/291)
- 관련 결정: D-082, D-086, D-095, ADR-0027

## 맥락

ADR-0027은 refresh token family를 DB에서 회전·폐기하지만 access JWT는 서명·iss·aud·sub·iat·exp만 검증한다. Logout은 현재 refresh family를 폐기하고 password reset은 모든 family를 폐기해도 이미 발급된 JWT는 exp까지 유효할 수 있다. 10분은 사용자 로그아웃·계정 탈취 대응에 남는 권한 창이다.

세션 family는 이미 account, created_at, expires_at, revoked_at을 보유하므로 이 상태를 access token 검증과 구매자 session 관리에 재사용할 수 있다.

## 결정

1. **Family-bound access JWT**: access token은 서명된 sid claim에 refresh family UUID를 갖는다. Login/MFA login은 새 family를 만들고, refresh rotation은 동일 family ID를 재사용한다. JWT validator는 sub/sid의 UUID 형식, signature, issuer, audience, iat/exp와 family/account 상태를 검증한다.
2. **요청 시 authoritative DB check**: 매 protected request마다 indexed family primary key + account 상태 lookup으로 family가 존재하고 계정에 속하며 revoked_at이 null, expires_at이 미래, 계정이 ACTIVE/email verified인지 확인한다. 판정은 DB 장애나 malformed claim에서 fail-closed한다. Cache 기반 폐기 지연은 추가하지 않는다.
3. **구매자 session inventory**: GET /api/auth/sessions는 현재 계정의 유효한 family만 created_at DESC, family_id DESC로 기본 20/최대 100, offset 최대 10,000 페이지로 반환하고 현재 sid를 표시한다. Refresh secret/hash와 user-agent/IP fingerprint는 반환·저장하지 않는다.
4. **계정 범위 targeted revoke**: DELETE /api/auth/sessions/{familyId}는 (account_id, family_id) 조건으로만 family와 refresh rows를 폐기한다. 다른 계정·없는 family도 동일한 idempotent 204를 반환해 존재 여부를 노출하지 않는다. 본인 current family 폐기도 허용한다.
5. **기존 lifecycle semantics**: logout은 해당 refresh family만 폐기; 비밀번호 reset/관리상 all-family revoke는 모든 family를 폐기한다. 다음 HTTP 요청부터 해당 family JWT는 401이고 sibling family는 계속 유효하다. Refresh token reuse는 기존 정책대로 family 전체를 폐기하고 해당 family access JWT도 무효화한다.
6. **세션 목록 index**: V37 partial covering index (account_id, created_at DESC, family_id DESC) INCLUDE (expires_at) WHERE revoked_at IS NULL은 bounded active session list의 순서와 filter를 지원한다. Access validation은 family PK lookup을 사용한다.

## 절충과 장애 동작

- 완전 stateless JWT보다 매 protected request에 DB read를 추가한다. 즉시 multi-instance revocation consistency를 위해 latency와 DB QPS를 지불하며 cache invalidation complexity를 피한다.
- 인증 DB lookup이 실패하면 access token을 승인하지 않는다. Service endpoints는 DB 장애 동안 인증 요청이 실패할 수 있으므로 DB pool health·latency와 validation error rate를 관측해야 한다.
- JWT validation이 통과한 뒤 시작된 in-flight request는 진행할 수 있다. Revocation은 subsequent request에 즉시 적용되며 이미 수행 중인 handler를 취소하지 않는다.
- Session response에 device label이 없어 사용자는 createdAt/expiry로만 sessions를 구별한다. User-agent/IP를 기록하면 privacy·spoofing·retention 정책이 필요하므로 제외했다.

## 검증 근거와 한계

- PostgreSQL integration test는 2,501 family/account seed에서 active-family check와 session-list plan을 확인한다. Local JDK 21 / PostgreSQL 18.6 Testcontainers, warm-up 25회 후 200 access-validation sample은 p50 0.349 ms, p95 0.487 ms였다. Session listing EXPLAIN은 ix_refresh_family_active_account_created Index Only Scan을 사용했고 21 heap fetches를 보였다.
- 수치는 local container 측정이며 production hardware/connection pool/traffic SLA가 아니다. Protected-request DB QPS는 요청률에 비례한다; production-like load test와 pool capacity 측정은 후속 운영 검증이다.
- Logout의 sid/refresh-family 불일치 거부, targeted sibling-session revoke의 동시 idempotency, foreign account isolation, password-reset all-family revoke, refresh replay, disabled/missing/malformed session claims 및 paging을 HTTP/JWT/PostgreSQL 통합 검증한다.

## 데이터 흐름

~~~mermaid
sequenceDiagram
    participant C as Client
    participant G as JWT Resource Server
    participant A as AccountService
    participant D as PostgreSQL
    C->>G: Protected request with access JWT(sid)
    G->>G: Verify signature, issuer, audience, exp and UUID claims
    G->>A: Validate(accountId, familyId)
    A->>D: Indexed active-family + account-status lookup
    D-->>A: Active / revoked / expired / disabled
    A-->>G: Valid or fail closed
    G-->>C: Continue or 401
    C->>G: DELETE /api/auth/sessions/{familyId}
    G->>A: Revoke only caller-owned family
    A->>D: UPDATE with account_id + family_id predicate
~~~

## 후속 작업

Production-like load/connection pool capacity를 측정하고 401 vs infrastructure 503 error contract/metrics를 별도 운영 이슈로 확정한다. Device name, notification, signing-key rotation 및 OIDC는 포함하지 않는다.
