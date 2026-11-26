# 기능 상세와 운영 가이드

[README](../README.md)에서 옮긴 내용이다. 문장은 그대로 두고 상대 링크의 기준 경로만 `docs/`에 맞췄다. README는 처음 읽는 사람이 실행하는 데 필요한 것만 남긴다.

## 기능 상세

Buyer 주문 내역: `GET /api/buyers/{buyerRef}/orders`는 인증된 Buyer의 Campaign·cart 주문 요약을 keyset cursor로 조회. 기본 limit 10, 최대 100; 상세는 `GET /api/orders/{orderId}`.
Buyer 관심 상품 목록은 `GET /api/buyers/{buyerRef}/wishlist`로 페이지 조회하고, `POST`/`DELETE /api/buyers/{buyerRef}/wishlist/products/{productId}`로 멱등 저장·삭제. 본인 계정만 접근하며 장바구니·재고 예약과 분리됩니다.
Verified buyer 상품 리뷰는 배송 완료된 본인 주문 상품만 작성할 수 있습니다: `POST /api/buyers/{buyerRef}/reviews`; 공개 평점·리뷰 페이지는 `GET /api/products/{productId}/reviews`입니다.
반품은 배송 완료 후 구매자가 요청하고 Seller가 수령·검수합니다. 구매자는 `GET /api/buyers/{buyerRef}/orders/{orderId}/returns`로 자신의 주문에 대한 반품 목록과 단계별 상태를 조회합니다(`returnId` 없이). 단건 조회는 `GET /api/buyers/{buyerRef}/orders/{orderId}/returns/{returnId}`입니다. 다른 구매자의 주문은 `NOT_FOUND`로 응답해 존재 여부를 드러내지 않습니다.
계정 session은 `GET /api/auth/sessions`로 bounded 조회하고 `DELETE /api/auth/sessions/{familyId}`로 본인 family를 종료할 수 있습니다. Logout·비밀번호 재설정 뒤 관련 access JWT는 다음 요청부터 즉시 폐기됩니다.
인증 시도는 주체(이메일)·출처(주소) 두 축으로 제한합니다. PBKDF2 검증 1회가 수십 ms의 CPU를 쓰므로, 실패 한도를 넘으면 **비밀번호 검증 전에** 거절하고 응답은 실제 인증 실패와 동일하게 유지합니다. 실패 기록은 인증 transaction과 분리해 rollback되어도 남으며, 정상 로그인은 카운터를 초기화합니다. MFA 코드 시도와 비밀번호 재설정·이메일 인증 메일 발송도 같은 한도를 공유합니다. 설정과 판단 근거는 [ADR-0048](02-decisions/adr/ADR-0048-authentication-attempt-throttling.md)입니다.
Buyer 상품 목록은 `minPriceWon`/`maxPriceWon`으로 SKU 유효 가격 범위를 필터링하며, 기간 판매가가 활성인 동안에는 effective price 기준으로 cursor 페이지 조회합니다.

## 계정 인증·복구

- `POST /api/auth/signup`은 검증 메일 전송 요청을 접수하고 `202` 반환. 이미 사용 중인 이메일에도 같은 HTTP status/body 반환; SMTP delivery timing 평준화는 미지원. 메일 token 검증 후 `POST /api/auth/verify-email`을 호출해야 login 가능.
- `POST /api/auth/email-verification/request`와 `/api/auth/password-reset/request`는 account 존재 여부와 무관하게 같은 accepted 응답 반환. Reset은 `POST /api/auth/password-reset/confirm`에서 수행하며 기존 refresh family와 미소비 MFA login challenge 전체 revoke. Login은 token/challenge 발급 전 account password hash를 row lock 안에서 재검증해 동시 reset에 따른 stale credential 사용 방지.
- Login에서 MFA 미사용 시 `tokens` 반환. MFA 사용 시 5분 유효 challenge만 반환하며, `POST /api/auth/mfa/challenge`의 TOTP 또는 단회 recovery code 확인 뒤 token 발급.
- 선택형 MFA endpoint: 인증된 account에서 `POST /api/auth/mfa/enroll`로 enrollment 시작, `/api/auth/mfa/enable`로 활성화·recovery code 10개 발급, `/api/auth/mfa/disable`로 해제.
- SMTP host·자격 증명과 발신 주소는 [.env.example](../.env.example)의 환경 변수 참조. External `ATLAS_ACCOUNT_MAIL_PUBLIC_BASE_URL`은 HTTPS 필수; HTTP는 loopback 개발 주소만 허용. MFA 설정 전 `ATLAS_ACCOUNT_MFA_ENCRYPTION_KEY`에 `openssl rand -base64 32`로 생성한 32-byte key 설정; 평문 key·메일 token은 로그나 저장소에 기록 금지.
- 사용자용 메일 화면 미구현. Client는 메일의 one-time token을 API 요청 body로 전달; token의 URL·access log 기록 금지.

## PayPal Balance report 대사

- PayPal merchant portal에서 내려받은 Balance Reconciliation report CSV의 단일 파일 업로드 지원. 공식 [Balance report 문서](https://developer.paypal.com/reports/balance)와 [공개 CSV sample](https://www.paypalobjects.com/ppdevdocs/Sample-Balance%20Report-CCOP.csv) 기준의 header-driven parsing.
- `POST /api/internal/settlement/paypal/balance-report/reconciliation`의 multipart `report` 필드로 업로드. 응답에 report SHA-256, 행별 `MATCHED`·`UNMATCHED`·`DUPLICATE`·`MISMATCH`, PayPal `Transfer ID` 참조 포함. Gross amount와 report에 포함된 fee/net amount도 행 결과에 보존.
- Account-sensitive 결과 보호를 위해 기본 비활성화. 활성화 시 `ATLAS_SETTLEMENT_REPORT_OPERATOR_ENABLED=true`, 전용 `ATLAS_SETTLEMENT_REPORT_OPERATOR_USERNAME`, 최소 32-character `ATLAS_SETTLEMENT_REPORT_OPERATOR_PASSWORD` 설정 필요. Stateless HTTP Basic 인증 사용; 외부 TLS ingress, `Authorization` header log redaction, 전용 secret 저장소와 rotation 정책 필요. Enabled가 true가 아니면 operator API 미등록.
- 단일 파일 최대 10 MiB, detail 행 최대 10,000개. 분할 생성된 report의 일부 파일만으로는 완전 대사가 불가능하므로 `Total files > 1` 입력 거절.
- `Sale`은 report `Record ID == provider_capture_id`, `Refund`는 `Record ID == provider_refund_id` 및 `Related Record ID == refund.capture_id == provider_capture_id` exact equality로만 대조. Capture/refund provider 상태가 confirmed이고 provider currency와 signed gross amount까지 일치할 때만 `MATCHED`; top-level Order ID·fuzzy·amount-only matching 금지. 누락·미확정은 `UNMATCHED`, report/provider facts 충돌 또는 ambiguous ID는 `MISMATCH`, report 내부 동일 ID 반복은 `DUPLICATE`. `Related Record ID`와 `Original Record ID`는 응답에 보존하며 internal attempt/refund ID나 journal 값을 응답하지 않음. `providerIdMappingStatus=EXACT_PROVIDER_ID_WITH_AMOUNT_CURRENCY_VALIDATION`.
- 공식 자료는 각 ID 필드 의미를 설명하지만 보편적인 Balance report `Record ID`와 Orders/Payments API ID crosswalk은 보장하지 않음. Exact equality는 행별 candidate check이며 실제 merchant entitlement·report version·ID 행의 검증은 Live gate LG-1이 맡으며, LG-1이 닫히기 전에는 불확실한 행이 `UNMATCHED`로 남는다. 운영 report를 쓰기 전에 실제 merchant report 한 개를 dry-run으로 확인한다(Live gate LG-1, [ADR-0095](02-decisions/adr/ADR-0095-balance-report-id-crosswalk-evidence-and-live-gate.md)). ID 쪽은 실제 Sandbox 거래 기록으로 교차 검증했다. 미확정 row는 계속 `UNMATCHED`.
- `Transfer ID`는 PayPal 문서상 bank statement line과 대조할 식별자. 은행 거래 내역을 별도로 받지 않는 본 endpoint는 은행 입금 수령을 증명하지 않으며 payout 결과에 `NOT_VERIFIED_FROM_PAYPAL_REPORT_ALONE` 표시.
- 원본 report 저장이나 자동 SFTP 수집 미지원. 결과 응답만으로 감사 기록을 만들 수 있으나 보존 시 report hash와 결과를 접근 통제된 외부 감사 저장소에 보관.

## 선택 구성 실행

### PayPal Payouts Sandbox (opt-in)

PayPal Payouts 자동 송금은 구매자 결제와 별도 경로이며 기본 비활성화. Payouts 권한이 있는 Sandbox 앱, Seller가 연결한 verified account, operator가 검토한 수취 국가(기본 allowlist `US`), USD 지급 스냅샷과 audit reason이 필요하다. API는 임의 receiver email을 받거나 반환하지 않는다.

활성화 전 PayPal Sandbox Business 계정의 Payouts 권한과 recipient profile 적격성을 확인한다. JWT/MFA와 다른 전용 32-byte recipient encryption key를 생성한다. 아래 값을 `.env.local`에 설정하고 현재 shell에만 로드한다.

```sh
ATLAS_PAYPAL_PAYOUTS_ENABLED=true
ATLAS_PAYPAL_PAYOUT_RECIPIENT_ENCRYPTION_KEY=<base64-32-byte-key>
ATLAS_SETTLEMENT_PAYOUT_OPERATOR_ENABLED=true
ATLAS_PAYPAL_PAYOUT_WORKER_ENABLED=false
```

운영자는 사람마다 따로 가진 account다([ADR-0092](02-decisions/adr/ADR-0092-payout-operator-person-identity-and-maker-checker.md)). 일반 signup으로 account를 만들고 이메일을 검증한 뒤 **TOTP MFA를 등록**한다. 첫 운영자와 이후 운영자는 DB 권한자가 `payout_operator_account`에 행을 넣어 부여한다. 이 표에 행이 하나도 없으면 운영자 경로는 모두 403이다. 앱은 부여 API를 일부러 제공하지 않는다(권한을 줄 수 있는 경로가 앱에 생기면 그 경로가 새 관문이 된다).

```sql
-- 부여. granted_by는 부여한 DB 사용자가 자동으로 남는다.
INSERT INTO payout_operator_account (account_id) VALUES ('<operator-account-id>');
-- 해제. 행을 지우지 않고 revoked_at만 채운다. 이미 발급된 access token(10분)에도 다음 요청부터 바로 적용된다.
UPDATE payout_operator_account SET revoked_at = now() WHERE account_id = '<operator-account-id>';
```

운영자 호출은 `Authorization: Bearer <access token>`이고 감사 행의 actor는 account id다. 프로필을 검증한 사람은 그 지급을 승인할 수 없고(403 `SEPARATION_OF_DUTIES`), 수동 지급에서 승인한 사람은 송금을 기록할 수 없으며 송금을 기록한 사람은 확인할 수 없다. 운영자 자신의 account가 수취 account인 프로필은 그 운영자가 검증할 수 없다. 소규모 팀이면 운영자를 최소 두 명 부여해야 지급이 진행된다.

운영자 access token은 **MFA challenge(TOTP 또는 recovery code)를 마친 세션에서 발급된 것만** 받아 준다([ADR-0094](02-decisions/adr/ADR-0094-payout-mfa-session-and-resolution-controls.md)). 비밀번호만으로 만든 세션과 MFA를 다시 등록하기 전에 만든 세션은 403이다. V6를 적용한 직후에는 모든 운영자 세션이 한 번 거절되므로 다시 로그인해 MFA를 거쳐야 한다. 재큐(`requeue`), provider 검토 해소(`resolve-provider-review`), 수동 지급의 송금 없음 확정(`manual-no-transfer`)은 같은 지급의 승인자(또는 송금 기록자)와 다른 운영자만 할 수 있고(403 `SEPARATION_OF_DUTIES`), 취소와 송금 전 실패 기록은 단독으로 할 수 있다.

**되돌리기(rollback):** `ATLAS_SETTLEMENT_PAYOUT_OPERATOR_IDENTITY=shared-credential`로 재기동하면 기존 HTTP Basic 단일 자격 증명 모드로 돌아간다. 이때 `ATLAS_SETTLEMENT_PAYOUT_OPERATOR_USERNAME`(비어 있지 않게)과 32자 이상 `ATLAS_SETTLEMENT_PAYOUT_OPERATOR_PASSWORD`가 필요하고, 감사 actor는 사람을 구분하지 못하며 승인자 분리 규칙은 적용되지 않는다(기동 로그에 WARN). 긴급 우회가 아니라 되돌리기 수단이다.

먼저 worker를 끈 채 profile을 만들고 operator 검토를 통과시킨 다음, 지급 요청·operator 승인을 수행해 immutable outbox intent가 생성되는지 확인한다. Profile·국가·암호화 key·Sandbox entitlement 확인 후에만 `ATLAS_PAYPAL_PAYOUT_WORKER_ENABLED=true`로 전환한다. 적격하지 않은 profile은 자동 전송하지 않으며, 별도 manual route는 외부 증빙과 operator audit을 요구한다. Live credential이나 실계좌를 사용하지 않는다.

### 독립 inventory-location 실행

분리된 inventory runtime은 기존 `atlas-application`과 독립 실행된다. 필요 도구는 JDK 25와 Docker Compose이며, Compose가 `build/libs`의 JAR을 bind mount하므로 아래 `bootJar` 단계를 먼저 실행해야 한다. location A/B는 PostgreSQL을 각각 사용한다. coordinator PostgreSQL과 loopback-only Mailpit SMTP는 추적되는 [`load-tests/compose-order-coordinator.yaml`](../load-tests/compose-order-coordinator.yaml)에서 함께 제공한다. Compose 정의는 저장소에 두고, service token 같은 비밀값을 담는 `inventory.env`만 git-ignore된 `.local`에 둔다. 기존 root `postgres` volume은 사용하지 않으며 internal API와 모든 host port는 loopback에만 bind한다.

```sh
mkdir -p .local/distributed-fulfillment
if [ ! -f .env.local ]; then cp .env.example .env.local; fi
chmod 600 .env.local
set -a
. ./.env.local
set +a
if [ -z "${ATLAS_JWT_SIGNING_SECRET:-}" ]; then
  export ATLAS_JWT_SIGNING_SECRET="$(openssl rand -base64 32)"
fi
export INVENTORY_LOCATION_SERVICE_TOKEN="$(openssl rand -hex 32)"
export ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN="$(openssl rand -hex 32)"
export ATLAS_SERVER_PORT=8082
location_a=00000000-0000-0000-0000-000000000001
location_b=00000000-0000-0000-0000-000000000002
{
  printf 'INVENTORY_LOCATION_SERVICE_TOKEN=%s\n' "$INVENTORY_LOCATION_SERVICE_TOKEN"
  printf 'ATLAS_ORDER_INVENTORY_RESERVATION_ENABLED=true\n'
  printf 'ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN=%s\n' "$ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN"
  printf 'ATLAS_ORDER_INVENTORY_RESERVATION_LOCATION_ENDPOINTS="%s=http://127.0.0.1:8090;%s=http://127.0.0.1:8091"\n' \
    "$location_a" "$location_b"
  printf 'ATLAS_DB_URL=jdbc:postgresql://127.0.0.1:5435/atlas_order\nATLAS_DB_USER=atlas\n'
  printf 'ATLAS_DB_PASSWORD=atlas-order-local-only\nATLAS_ORDER_DB_PASSWORD=atlas-order-local-only\n'
  printf 'SPRING_MAIL_HOST=127.0.0.1\nSPRING_MAIL_PORT=1025\n'
  printf 'ATLAS_SERVER_PORT=%s\nATLAS_JWT_SIGNING_SECRET=%s\n' \
    "$ATLAS_SERVER_PORT" "$ATLAS_JWT_SIGNING_SECRET"
} > .local/distributed-fulfillment/inventory.env
chmod 600 .local/distributed-fulfillment/inventory.env
./gradlew --no-daemon :atlas-inventory-location:bootJar :atlas-application:bootJar
# JAR는 bind mount이므로 다시 빌드한 JAR을 적용하려면 container를 재생성한다(`--force-recreate`). DB volume은 유지된다.
docker compose --env-file .local/distributed-fulfillment/inventory.env \
  -p atlas-inventory -f load-tests/compose-inventory-location.yaml -f load-tests/compose-order-coordinator.yaml up -d --wait --force-recreate
```

분리된 inventory runtime을 사용하는 order-side coordinator API는 선택형이다. Internal service token은 `.local` env에만 보관한다. 기본 feature flag가 꺼져 있으면 Buyer cart checkout은 monolith inventory path를 사용하며, flag를 켜면 아래와 같이 remote reservation workflow에 연결된다. Internal coordinator route는 remote reservation·location별 상태·부분 실패 보상·재조정을 별도로 검증하는 경계다.

```sh
set -a
. ./.local/distributed-fulfillment/inventory.env
set +a
java -jar atlas-application/build/libs/atlas-application.jar
```

`POST /api/internal/order-inventory/reservations`는 `Idempotency-Key`, order UUID와 location별 Seller·SKU allocation을 받는다. `RESERVED`는 모든 원격 예약 응답과 잔여 TTL 확인이 끝났다는 뜻이며, 다음 업무 단계는 각 location의 `expiresAt` 전에 시작해야 한다. `RETRY_PENDING`/`COMPENSATION_REQUIRED`는 `202` 상태 조회 대상으로 응답한다. `GET /api/internal/order-inventory/reservations/{workflowId}`로 복구 상태를 확인한다. `POST /api/internal/order-inventory/reservations/{workflowId}/cancel`은 모든 location을 해제하면 `200`(`CANCELLED`), 결과가 불확실하면 `202`(`CANCEL_PENDING`, recovery worker가 재시도), 자동 해제가 불가능하면 `409`(`NEEDS_REVIEW`)로 응답한다.

같은 flag가 켜지면 Buyer cart checkout도 이 workflow를 사용한다. checkout은 `RESERVATION_PENDING`으로 저장된 뒤 원격 예약이 모두 확인되면 `RESERVED`가 된다. 결제는 PayPal 승인까지만 진행한 뒤 모든 원격 예약을 claim하고 나서 매입한다. claim이 만료·취소로 거절되면 매입하지 않고 승인을 void하며, claim 결과가 불확실하면 승인 상태로 두고 payment recovery가 다시 확인한다. 세부 근거는 [ADR-0060](02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md).

```sh
curl -X POST http://127.0.0.1:8082/api/internal/order-inventory/reservations \
  -H "X-Atlas-Service-Token: $ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN" \
  -H "Idempotency-Key: order-reservation-00000000-0000-0000-0000-000000000101" \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"00000000-0000-0000-0000-000000000101","allocations":[{"fulfillmentLocationId":"00000000-0000-0000-0000-000000000001","items":[{"sellerId":"00000000-0000-0000-0000-000000000011","skuId":"SKU-A","quantity":1}]},{"fulfillmentLocationId":"00000000-0000-0000-0000-000000000002","items":[{"sellerId":"00000000-0000-0000-0000-000000000012","skuId":"SKU-B","quantity":1}]}]}'
```

저장소에 추적되는 [`load-tests/distributed-coordinator.js`](../load-tests/distributed-coordinator.js) profile은 기본값이 `RATE=1000`(order/s), `DURATION=30s`, `LOCATIONS=2`, `MAX_VUS=1000`이다. order당 SKU line 3개를 location 2개에 나누므로 초당 최대 2,000건의 remote reservation call을 만든다. 두 service token은 각각 32자 이상이어야 한다. K6는 unique `RUN_ID`로 seller-scoped stock을 seed하고 HTTP status별 응답, p95/p99, reserved/pending/rejected 수, stock conservation을 기록한다. `DB_CONTAINER`를 설정하면 실제 iteration 수와 DB workflow 수를 대조하고, backlog가 모두 `RESERVED`로 수렴할 때까지 기다린다. Dropped iteration은 별도로 보고하며 `K6_CPUS`·`K6_MEMORY`로 부하 생성기 자원을 제한할 수 있다. 합성 profile이며 production capacity나 SLO가 아니다. 측정 결과는 [EXP-059](03-experiments/EXP-059-order-reservation-load-and-idempotent-replay.md)에 있고, JSON은 `.local/distributed-fulfillment/k6/`에만 남는다.

`TRANSPORT_RETRIES=1`을 별도 실행에 지정하면 HTTP status 0 또는 5xx 뒤에 같은 요청 본문과 `Idempotency-Key`로 최대 한 번 재시도한다. 최대 2회까지 설정할 수 있고 기본값은 0이며, 기본 capacity profile 결과와 분리해 해석한다. 재시도 횟수·회복 여부·status별 응답을 요약에 기록해 응답 유실 후에도 workflow가 단일 건으로 수렴하는지 확인한다.

```sh
set -a
. ./.local/distributed-fulfillment/inventory.env
set +a
DB_CONTAINER=atlas-inventory-order-coordinator-postgres-1 RATE=250 DURATION=30s ./load-tests/run-distributed-coordinator.sh
DB_CONTAINER=atlas-inventory-order-coordinator-postgres-1 RATE=1000 DURATION=30s ./load-tests/run-distributed-coordinator.sh
DB_CONTAINER=atlas-inventory-order-coordinator-postgres-1 RATE=1000 DURATION=30s TRANSPORT_RETRIES=1 ./load-tests/run-distributed-coordinator.sh
```

Location A/B API는 각각 `http://127.0.0.1:8090`·`http://127.0.0.1:8091`이다. K6 scenario·runner와 Compose 정의는 추적되는 `load-tests/`에 두고, 실행 JSON과 service token만 git-ignore된 `.local/distributed-fulfillment/`에 보관한다. Inventory 경계와 수치는 [ADR-0059](02-decisions/adr/ADR-0059-independent-inventory-location-service.md)·[EXP-053](03-experiments/EXP-053-inventory-location-failure-load.md), coordinator/lease 정책은 [ADR-0060](02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md)·[ADR-0064](02-decisions/adr/ADR-0064-order-inventory-just-in-time-lease-claims.md) 참조.

느린 SQL 감지는 Java `DataSource` decorator로 선택 활성화.

- `ATLAS_DB_SLOW_QUERY_THRESHOLD_MS`: 측정 근거가 있는 양의 임계값(ms). 미설정 시 감지기 비활성화.
- 기록 항목: HTTP 요청의 `requestId`, datasource bean 이름, 경과 시간, 성공 여부, statement 수, SHA-256 statement fingerprint. SQL text·literal·bind value 기록 0건.
- 감지기 활성·비활성 상태는 startup INFO 로그로 기록.
- 세부 조건·한계: [EXP-025](03-experiments/EXP-025-slow-query-detection.md)

기본 API 주소: `http://127.0.0.1:8080`. `.env.local`은 Spring 자동 로드 대상이 아니므로 위 예시처럼 shell 환경 변수로 전달. Gradle project path는 [`settings.gradle`](../settings.gradle)에 지정되며 `:atlas-application`의 대상은 `atlas-application/`. 운영 규칙은 [저장소 작업 지침](../AGENTS.md) 참고.

### 부하 측정의 PayPal·ECOS stub

부하 측정에서 `load-tests/` 의 PayPal·ECOS stub 을 쓸 때는 stub 과 애플리케이션이 **같은 네트워크 네임스페이스**에 있어야 한다. 가장 확실한 방법은 **JVM·stub·k6 를 모두 호스트에서** 실행하는 것이다(`k6` 는 `brew install k6`). 그러면 검증이 허용하는 `127.0.0.1` 이 실제 stub 을 가리킨다. PayPal base URL 검증이 `localhost`/`127.0.0.1`/`::1`/sandbox 만 허용하므로, 컨테이너 안의 `127.0.0.1` 이 stub 을 가리켜야 한다. `--network host` 가 호스트 네임스페이스를 공유하지 않는 런타임(예: Colima VM)에서는 컨테이너가 호스트 stub 에 도달하지 못해 연결이 거부된다.

## 부하·성능 검증

- k6 시나리오: [`load-tests/`](../load-tests/)
- 기본 동작: 실제 API로 checkout 예약·도메인 상태 검증, 외부 payment provider 호출 0건
- PayPal Sandbox: [buyer-checkout](../load-tests/buyer-checkout.js)의 `paypal-sandbox-order-smoke`는 order 1회 생성, `paypal-sandbox-verify`는 수동 Buyer approval 뒤 `CAPTURED`·`PAID` 확인. Gate4 smoke도 1회만 허용; 기본 부하의 PSP 호출 0건
- Coupon redemption: coupon cap 경합 검증만 수행. 결제 부하와 분리
- 요약은 기본 stdout 출력. 파일 저장 시 `SUMMARY_FILE`을 지정하고 `atlas-application/build/k6-results/` 사용
- 테스트 계정 비밀번호·PayPal credential은 무시 파일 `.env.local`에 보관. 코드·명령 인자·결과 파일에 기록 금지
- Buyer checkout 진단: `K6_DEBUG_ERRORS=true`는 stage·HTTP status·error code만 출력. Response body·token 미출력

`ATLAS_PAYMENT_PROVIDER` 기본값은 `disabled`. Compose의 OAuth client ID/secret 기본값도 `disabled`이며, PayPal API 호출 비활성. 실행마다 고유한 `RUN_ID` 사용.

K6 Compose app 준비 (처음 실행·Java 변경 후):

```sh
./gradlew --no-daemon :atlas-application:bootJar
docker compose --env-file .env.local --profile loadtest up -d --build application
```

`Started AtlasApplication` 로그 확인 후 K6 시나리오 실행.

로컬 5초 checkout pilot:

```sh
docker compose --env-file .env.local --profile loadtest run --rm \
  -e RUN_ID=local-pilot-001 \
  -e PROFILE=one-seller \
  -e UNIT_PRICE_WON=1000 \
  -e SESSION_RATE_NUMERATOR=1 \
  -e SESSION_RATE_TIME_UNIT=1s \
  -e DURATION=5s \
  -e FIXED_VUS=1 \
  -e PAYMENT_MODE=checkout-only \
  k6 run /scripts/buyer-checkout.js
```

### PayPal Sandbox

사전 조건:

- Business app credentials, BOK ECOS API key (기본 provider 사용 시) 또는 무가입 Frankfurter provider 선택, Sandbox Buyer 계정.
- `.env.local`: `ATLAS_PAYMENT_PROVIDER=paypal`, Sandbox `PAYPAL_BASE_URL`, `ATLAS_PUBLIC_BASE_URL=http://127.0.0.1:18080`, `ATLAS_PAYMENT_RETURN_PORT=18080`.
- 승인 callback 준비: `docker compose --env-file .env.local up -d --build --wait application` (host `18080` → container `8080`).
- `bootRun` 사용 시 `ATLAS_PUBLIC_BASE_URL`을 실제로 열어 둔 app port와 일치.

회원가입/API key가 없는 FX source가 필요하면 `.env.local`에 `ATLAS_EXCHANGE_RATE_PROVIDER=frankfurter`를 명시한다. ECB 일별 USD/KRW 기준환율을 사용하며 source·기준일을 payment snapshot에 저장한다. 7일 초과·유효하지 않은 값은 거부한다. 실시간 PayPal 환율이나 은행 체결률이 아니므로 local/Sandbox용이며 기본 provider는 BOK ECOS다([ADR-0065](02-decisions/adr/ADR-0065-frankfurter-sandbox-fx-provider.md)).

`paypal-sandbox-order-smoke`는 order를 1회 생성. approval URL은 `PAYPAL_SHOW_APPROVAL_URL=true`일 때만 출력되며, 한 번만 사용하는 민감 링크. 로컬 외부 로그·PR에 복사 금지.

1. 출력된 approval URL을 브라우저에서 열고 Sandbox Buyer로 승인. PayPal return은 loopback `127.0.0.1:18080`의 ATLAS app으로 돌아오며, 실제 Sandbox authorization/capture 진행
2. 출력된 checkout ID를 사용해 capture 결과 확인

PayPal Sandbox order 생성:

```sh
docker compose --env-file .env.local --profile loadtest run --rm \
  -e RUN_ID=paypal-smoke-001 \
  -e PROFILE=one-seller \
  -e UNIT_PRICE_WON=1000 \
  -e FIXED_VUS=1 \
  -e PAYMENT_MODE=paypal-sandbox-order-smoke \
  -e PAYPAL_SHOW_APPROVAL_URL=true \
  k6 run /scripts/buyer-checkout.js
```

Capture·inventory·settlement 확인:

```sh
docker compose --env-file .env.local --profile loadtest run --rm \
  -e RUN_ID=paypal-smoke-001 \
  -e PROFILE=one-seller \
  -e UNIT_PRICE_WON=1000 \
  -e FIXED_VUS=1 \
  -e PAYMENT_MODE=paypal-sandbox-verify \
  -e PAYPAL_CHECKOUT_ID=CHECKOUT_ID_FROM_OUTPUT \
  k6 run /scripts/buyer-checkout.js
```

#### 검증 기록 (2026-11-13)

- KRW 2,000 → PayPal USD 1.50 (BOK 기준환율 1,332.4 KRW/USD, 기준일 2026-11-13).
- Sandbox Buyer 승인·capture 완료. Unknown capture는 PayPal order GET으로 복구; K6 결과 `CAPTURED` · `PAID` · `CLAIMED`.
- Settlement journal: `PSP_CLEARING` debit / `SELLER_PAYABLE` credit, 각각 KRW 2,000. [Issue #254](https://github.com/Neo1228/ATLAS/issues/254).

## 결제·환불 복구 운영

`PaymentAttempt`와 `PaymentRefundOperation`은 주문/checkout/refund 업무 변경과 **같은 transaction**에서 PostgreSQL `payment_recovery_job`에 기록됩니다. job은 `PROVIDER`(PayPal 호출/대조) → `APPLY`(주문·재고·fulfillment·정산 반영) 두 단계로 진행하며, owner 적용과 job 완료가 한 transaction이라 중간 종료 시 둘 다 rollback되어 재전달됩니다. lease 기반 worker가 두 단계를 stage별로 claim해 복구합니다. 외부 message broker를 추가하지 않으며, at-least-once 재실행은 저장된 provider request ID와 상태 검증에 의존합니다. 구매자 승인 redirect 대기 상태는 자동 재요청하지 않고 결제·재고는 provider 결과가 확정될 때까지 해제하지 않습니다. 자세한 상태·장애·절충은 [ADR-0038](02-decisions/adr/ADR-0038-durable-payment-recovery-workflow.md)을 참고하세요.

- Worker 기본 활성화: `ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED=true`. Initial delay 30초, poll 1초, batch 최대 10건(각 작업은 개별 claim), lease 60초, 최대 자동 시도 8회/24시간. `ATLAS_PAYMENT_RECOVERY_*` 환경 변수로 조정할 수 있습니다.
- 승인 화면 이탈 대비: 승인 대기에 들어가면 `payment_attempt.approval_expires_at`이 기록됩니다(`ATLAS_PAYMENT_APPROVAL_TTL`, 기본 `PT30M`). deadline이 지난 `WAITING_FOR_BUYER` job은 worker가 bounded sweep으로 재개하고, **provider에 실제 상태를 먼저 조회**합니다. 아직 승인 전이면 결제를 닫아 재고 예약과 캠페인 slot을 정리하고, 이미 capture가 확정됐으면 그대로 정상 결제로 수렴합니다. 자세한 판단 근거는 [ADR-0047](02-decisions/adr/ADR-0047-abandoned-approval-deadline.md)입니다.
- `NEEDS_REVIEW`는 `/api/internal/payment/recovery/needs-review?limit=20&offset=0`에서 조회합니다. `/api/internal/payment/recovery/{recoveryJobId}/requeue`는 Idempotency-Key와 검토 사유가 필요하며 task당 최대 3회입니다.
- Operator API는 기본 비활성화(`ATLAS_PAYMENT_RECOVERY_OPERATOR_ENABLED=false`)입니다. 활성화 시 별도 Basic-auth 사용자와 32자 이상의 password가 필요하며, 인증정보는 secret manager/environment에서만 공급합니다.
- PayPal 호출 동시 상한(옵트인): `ATLAS_PAYPAL_BULKHEAD_MAX_CONCURRENT_CALLS`(기본 `0` = 상한 없음). 켜면 서블릿 요청 스레드가 PayPal 응답을 기다리며 동시에 묶일 수 있는 호출 수를 제한하고, 넘는 호출은 기다리지 않고 PayPal에 보내기 전에 거절한다(결제 상태는 `UNKNOWN`, 응답 503, 같은 요청으로 재시도). 복구 worker의 호출은 세지도 거절하지도 않는다. Tomcat 최대 스레드(40)보다 작은 값(20~30)을 쓴다. 거절 수는 Micrometer 계수기 `atlas.payment.paypal.bulkhead.rejected`. 근거와 재검토 조건: [ADR-0098](02-decisions/adr/ADR-0098-paypal-call-bulkhead-opt-in.md), [EXP-108](03-experiments/EXP-108-dependency-slowness-thread-saturation.md).
- Prometheus metric prefix: `atlas.payment.recovery.*` (status별 backlog, stage별 `work_stage`+`status` backlog와 `oldest_due_age_seconds`, operation별 claim/retry/failure/completion, `operation_type`+`work_stage` 처리 duration). `work_stage=APPLY`의 pending count/age가 provider 결과 확정 후 owner 반영까지의 지연을 나타냅니다. `UNKNOWN` 또는 `NEEDS_REVIEW`를 성공/실패로 수동 간주하지 않습니다. 승인 대기 재개는 `atlas.payment.recovery.approval.resumed`로 관측합니다.
- 자세한 단계·crash window·검증은 [ADR-0046](02-decisions/adr/ADR-0046-order-payment-transactional-outbox.md)에 정리했습니다.

## 주문 재고 recovery 운영

분산 inventory feature가 활성화된 환경에서는 예약 workflow, checkout 확정, 환불 재입고를 세 worker가 각각 bounded poll한다. 세 worker는 poll 간격(`atlas.order-inventory-reservation.recovery.poll-interval`, 기본 `PT1S`)과 batch 크기(`recovery.batch-size`, 기본 100)를 공유하며 `ATLAS_ORDER_INVENTORY_RESERVATION_RECOVERY_POLL_INTERVAL`, `ATLAS_ORDER_INVENTORY_RESERVATION_RECOVERY_BATCH_SIZE`로 조정한다. 이 값을 worker별로 따로 바꾼 실험이 없어 개별 설정은 두지 않았다. 기능은 기본 비활성화(`ATLAS_ORDER_INVENTORY_RESERVATION_ENABLED=false`)이고, worker만 끄려면 `recovery.worker-enabled=false`(기본 `true`)로 설정한다.

> 이 섹션의 설정 키는 Spring 규칙대로 환경 변수로 덮어쓸 수 있다. `.`과 `-`를 `_`로 바꾸고 대문자로 쓴다. 예를 들어 `atlas.order-inventory-reservation.recovery.lease-duration`은 `ATLAS_ORDER_INVENTORY_RESERVATION_RECOVERY_LEASE_DURATION`이 된다. 그래서 `application.yml`에는 비밀값과 배포마다 달라지는 주소만 `${ENV}`로 두고, 나머지는 기본값을 그대로 적었다.

- `atlas.order.inventory.recovery.polls{stage,result}`와 `atlas.order.inventory.recovery.duration{stage}`는 stage별 poll 성공·실패와 처리 시간을 나타낸다.
- `atlas.order.inventory.recovery.oldest_due_age_seconds{stage}`는 reservation/restock의 due 대기 시간을, `atlas.order.inventory.recovery.oldest_pending_age_seconds{stage="checkout-finalization",state}`는 remote reservation checkout의 대기 연령을 나타낸다. 취소 대기는 재시도마다 갱신되는 `updated_at` 대신 최초 `CANCEL_PENDING` 요청 시각을 사용한다.
- V73 index는 예약 pending bounded poll의 `(state, created_at, checkout_id)` 조회에 쓰인다. V74는 `CANCEL_PENDING` 최초 진입 시각을 `cancellation_requested_at`에 한 번 저장해 재시도로 age가 초기화되지 않게 하고, 기존 행은 `updated_at`으로 근사 backfill한다. `buyer_checkout_cancel_pending_age_idx`는 취소 age query용 sparse partial index다.
- [EXP-057](03-experiments/EXP-057-checkout-recovery-index.md): 합성 checkout 100,000건(`RESERVATION_PENDING` 100, `CANCEL_PENDING` 50,000, `PAID` 49,900)에서 5회 median 기준으로 예약 조회는 4.174 ms에서 0.049 ms로(78.0~85.2배), 취소 age 조회는 20.163 ms에서 19.114 ms로(5.2~12.5% 감소) 바뀌었다. 같은 호스트에서 두 번 실행한 로컬 관측이며 운영 처리량 보장이나 worker 설정 기준이 아니다.

### 예약 NEEDS_REVIEW 운영

보상 재시도를 모두 쓴 예약 workflow(`recovery.max-attempts`, 기본 8회)는 `NEEDS_REVIEW`가 된다. 원격 재고가 `CLAIMED`로 남은 건은 TTL이 풀어 주지 않으므로 아래 gauge로 먼저 본다.

| 지표 | 의미 |
|---|---|
| `atlas.order.inventory.needs_review{stage="reservation"}` | 사람이 봐야 하는 workflow 수 |
| `atlas.order.inventory.needs_review_claimed{stage="reservation"}` | 그중 원격 재고가 `CLAIMED`로 남은 수(자동으로 풀리지 않음) |

내부 API(`X-Atlas-Service-Token` header 필요, 기존 `/api/internal/order-inventory/reservations` 아래):

- `GET /needs-review?limit=50`: 오래된 순으로 조회한다. `limit` 기본값은 50이다.
- `POST /{workflowId}/release`: 보상을 다시 실행해 원격 예약을 해제한다(`CLAIMED`는 복원). 시도 횟수를 0으로 되돌리고, `NEEDS_REVIEW`가 아니면 현재 상태를 그대로 돌려줘 반복 호출해도 안전하다. 원격 재고를 풀기 때문에 주문을 계속 진행하려는 경우에는 쓰지 않는다.

## 핫 SKU admission gate (선택)

한 SKU에 요청이 몰리면 품절 요청도 order DB에 reservation workflow 행을 만들어, 승인될 수 없는 요청이 DB 쓰기와 CPU를 먼저 소모한다. 로컬 격리 측정(재고 100인 SKU 1개, 주문 1건 = SKU 1줄, 측정 20초, 단일 호스트)에서 PostgreSQL만 쓰는 기준선은 500/s까지 dropped 0으로 요청률을 따라갔다. 1,000/s에서는 completed/s가 383.0~525.0(n=3)으로 꺾였고 거절 주문당 order DB transaction은 약 5.6~5.9개였다. 이를 줄이려고 workflow를 만들기 전에 요청을 앞단에서 거절하는 선택 기능을 두었다. 측정 요약은 아래 표에, 조건과 해석 한계는 [EXP-060](03-experiments/EXP-060-hot-sku-admission-gate.md)·[EXP-062](03-experiments/EXP-062-admission-gate-paired-comparison.md)·[EXP-063](03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md)에, 결정 근거는 [ADR-0066](02-decisions/adr/ADR-0066-redis-admission-gate.md)에 있다. gate는 거절 비용을 앞으로 옮길 뿐이며 재고 정합성의 정본은 계속 inventory-location의 조건부 UPDATE다. 기본값은 모두 비활성화다.

환경 변수 이름은 `atlas.order-inventory-reservation.admission-gate.*` 속성에서 `.`과 `-`를 `_`로 바꾸고 대문자로 쓴 것이다.

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_ENABLED` | `false` | Redis gate 활성화(`RedisStockAdmissionControl`) |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_REDIS_URI` | 없음(활성화 시 필수) | Redis 접속 URI. 인증 정보는 secret manager나 environment에서만 공급한다 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_COMMAND_TIMEOUT` | `PT0.2S` | Redis 명령 timeout |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_STOCK_TTL` | `PT5S` | SKU별 재고 카운터 key TTL. 만료되면 location의 실제 판매 가능 수량으로 다시 채운다 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_DECISION_TTL` | `PT10M` | 요청별 판정 key TTL. 같은 admission key의 재시도는 다시 차감하지 않는다 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_BREAKER_COOLDOWN` | `PT2S` | Redis 오류 뒤 gate를 건너뛰는 시간 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_PRECHECK_ENABLED` | `false` | Redis 없는 대안 활성화(`PrecheckStockAdmissionControl`) |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_PRECHECK_SOLD_OUT_TTL` | `PT1S` | precheck가 품절로 판정한 key를 기억하는 시간. `PT0S`이면 캐시를 끈다 |

- Redis 없는 대안은 workflow 행을 만들기 전에 location의 판매 가능 수량을 읽어, 요청 수량보다 적으면 거절한다. 품절로 판정한 key는 `PRECHECK_SOLD_OUT_TTL` 동안 기억하므로 그 시간에는 location을 다시 읽지 않는다. 차감하지 않는 읽기라 동시 요청은 같은 수량을 보고 통과할 수 있으며, 그 요청은 뒤의 정본 경로에서 거절된다. 이 대안은 별도 metric을 내지 않는다.
- 두 gate는 **동시에 켜지 않고** 한 가지만 선택한다. [ADR-0066](02-decisions/adr/ADR-0066-redis-admission-gate.md)은 기본 gate 후보를 precheck로, Redis gate를 location 읽기를 더 줄여야 하는 규모를 위한 옵션으로 둔다.
- **fail-open**: Redis 호출이 실패하거나 재고를 확인하지 못하면 거절하지 않고 정본 경로로 통과시킨다. 실패하면 `BREAKER_COOLDOWN` 동안 Redis를 호출하지 않고 통과시켜 요청마다 timeout을 기다리지 않는다. Redis 연결은 첫 사용 때 만들어, 기동 시점의 Redis 장애가 애플리케이션 기동을 막지 않는다. precheck 대안도 읽기에 실패하면 통과시킨다.
- Metric `atlas.order.admission.gate{result}`의 `result`는 `admitted`, `rejected`, `fail_open`이다. `fail_open`이 늘면 gate가 우회되고 있다는 신호다.

측정 요약(재고 100인 SKU 1개, 주문 1건 = SKU 1줄, location 1개, 단일 호스트, n = 구성당 반복 횟수):

| 구성 | 도착률 | n | completed/s | dropped | 근거 |
|---|---|---|---|---|---|
| gate 끈 기준선 | 1,000/s | 3 | 383.0~525.0 | 7,912~10,239 | EXP-060, 측정 20초 |
| gate 끈 기준선 | 2,000/s | 3 | 456.0~1,041.9 | 17,351~28,498 | EXP-060, 측정 20초 |
| Redis gate | 1,000/s | 2 | 991.3~992.2 | 140~152 | EXP-060, 측정 20초 |
| Redis gate | 2,000/s | 2 | 1,938.1~1,959.2 | 779~1,096 | EXP-060, 측정 20초 |
| Redis 중지, breaker 없음 | 1,000/s | 1 | 355 | 7,299 | EXP-060, 오버셀 없음 |
| Redis 중지, breaker 적용 | 1,000/s | 2 | 707~730 | 3,472~3,774 | EXP-060, 오버셀 없음 |
| precheck | 1,000/s | 6 | 중앙값 988(937~996) | 중앙값 314(95~1,826) | EXP-062, 측정 30초 |
| Redis gate | 1,000/s | 6 | 중앙값 996(982~999) | 중앙값 60(3~448) | EXP-062, 측정 30초 |
| precheck | 2,000/s | 3 | 중앙값 1,964(1,956~1,974) | 중앙값 1,023(727~1,274) | EXP-062, 측정 30초 |
| Redis gate | 2,000/s | 3 | 중앙값 1,683(1,501~1,944) | 중앙값 9,384(1,550~14,864) | EXP-062, 측정 30초, `fail_open` 2회 동반 |
| precheck(보강 jar) | 2,000/s | 4 | 중앙값 1,974(1,965~1,982) | 중앙값 746(497~998) | EXP-063, 측정 30초 |
| Redis gate(보강 jar) | 2,000/s | 4 | 중앙값 1,964(1,931~1,979) | 중앙값 998(510~1,989) | EXP-063, 측정 30초, `fail_open` 0회 |

- 기준선과 gate 실행은 세션이 달라 퍼센트 개선이나 절대 처리량을 근거로 쓰지 않는다. 기준선의 1,000/s 반복 범위 자체가 383.0~525.0이다. 방어 가능한 결론은 "거절 주문이 durable 쓰기를 만든다"와 "그 쓰기를 없애면 포화 지점이 크게 밀린다"는 자릿수 수준이다.
- 같은 세션 교차 비교(EXP-062·063)에서 precheck와 Redis gate의 처리량은 1,000/s와 보강 jar의 2,000/s에서 run 간 잡음(A/A 대조 4.2%) 안이었다. EXP-062의 Redis 팔 2회(1,501/s, 1,683/s)는 `fail_open`을 동반했으나, 보강 jar에서는 13회 모두 `fail_open` 0회라 재현하지 못했다(원인은 확정하지 못했다).
- EXP-062·063은 Apple M3 Pro, Colima VM 8 vCPU에서 컨테이너 CPU 한도 합이 vCPU를 넘는 과할당 조건이다. 모든 구성요소가 한 호스트에 있다.

재현에는 Docker, JDK 25, `python3`가 필요하고 두 JAR을 먼저 만들어야 한다(compose가 `build/libs`의 JAR을 bind mount한다). 스크립트는 격리된 임시 container/network만 쓰며 종료 시 정리한다. 결과는 git-ignore된 `.local/` 아래에만 쓰므로 저장소에서 원본 결과를 내려받을 수는 없다.

```sh
./gradlew --no-daemon :atlas-inventory-location:bootJar :atlas-application:bootJar
# gate를 끈 기준선. 도착률마다 새 compose 프로젝트를 만든다.
load-tests/run-hot-sku-coordinator.sh
# 도착률과 재고를 지정한다.
RATES="500 1000" STOCK=100 load-tests/run-hot-sku-coordinator.sh
# Redis 없는 대안(GATE=precheck)과 Redis gate(GATE=redis). 아래는 EXP-062·063의 2,000/s 조건이다(EXP-063은 K6_CPUS=2를 추가로 지정했다).
GATE=precheck RATES=2000 DURATION=30s LOCATION_CPUS=2 load-tests/run-hot-sku-coordinator.sh
GATE=redis RATES=2000 DURATION=30s LOCATION_CPUS=2 load-tests/run-hot-sku-coordinator.sh
# Redis 장애 주입. GATE=redis에서만 허용되며 측정 시작 <초> 뒤 Redis container를 중지한다.
GATE=redis REDIS_KILL_AFTER=<초> load-tests/run-hot-sku-coordinator.sh
```

| 변수 | 기본값 | 의미 |
|---|---|---|
| `RATES` | `100 300 500 1000 2000` | 도착률(주문/초) 목록. 공백으로 구분하며 값마다 새 compose 스택을 만든다 |
| `DURATION` | `20s` | 측정 구간. `<초>s` 형식만 받는다 |
| `STOCK` | `100` | 시드할 SKU 재고 |
| `GATE` | `off` | `off`, `redis`, `precheck` 중 하나 |
| `REDIS_KILL_AFTER` | 없음 | 측정 시작 뒤 Redis를 중지할 시점(정수 초). `GATE=redis`에서만 허용한다 |
| `K6_CPUS` | `1` | k6 container CPU 한도 |
| `LOCATION_CPUS` | `1` | inventory-location container CPU 한도 |
| `APP_POOL_SIZE` | `10` | 애플리케이션 Hikari 최대 pool 크기(`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`) |
| `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_COMMAND_TIMEOUT`, `ATLAS_ORDER_INVENTORY_RESERVATION_ADMISSION_GATE_BREAKER_COOLDOWN` | 설정하지 않으면 애플리케이션 기본값(`PT0.2S`, `PT2S`) | 환경에 설정하면 Redis gate에 그대로 전달한다 |
| `OUT` | `.local/hot-sku-coordinator/<timestamp>` | 결과 디렉터리 |

위 결과는 모두 **단일 호스트 탐색적 측정**이며 운영 처리량·SLO·용량 보장이 아니다. Redis 지연·장애 격리는 같은 호스트 안 container 사이의 조건에서만 관찰했다.

## 이벤트 로그와 Kafka relay (선택)

예약 workflow의 상태 전이는 V75 trigger가 `order_inventory_event`에 남긴다. 외부 consumer가 필요할 때만 relay를 켠다([ADR-0067](02-decisions/adr/ADR-0067-event-log-kafka-relay.md)). 환경 변수 이름은 `atlas.order-inventory-reservation.event-relay.*` 속성에서 위와 같은 규칙으로 만든다.

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_ENABLED` | `false` | relay 활성화 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka 주소 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_TOPIC` | `atlas.order-inventory.events` | Kafka 토픽 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_BATCH_SIZE` | `100` | 한 번에 보내는 이벤트 수. 전송이 실패하면 같은 묶음을 다시 보내므로 broker가 멈춘 구간의 중복 상한이다([ADR-0082](02-decisions/adr/ADR-0082-relay-batch-size-and-producer-log.md)) |
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_POLL_INTERVAL` | `PT0.2S` | relay poll 간격 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_SEND_TIMEOUT` | `PT10S` | 한 묶음 전송의 한도(1초 이상 5분 이하). 넘기면 cursor를 옮기지 않고 같은 묶음을 다시 보낸다 |
| `ATLAS_ORDER_INVENTORY_RESERVATION_EVENT_RELAY_LEASE_DURATION` | `PT30S` | relay 단일 실행 lease. `SEND_TIMEOUT`의 두 배보다 5초 이상 길어야 한다(묶음이 덩어리로 나뉘어 순서대로 전송되므로 최악 소요가 `SEND_TIMEOUT`의 두 배다, [ADR-0086](02-decisions/adr/ADR-0086-relay-chunked-send-for-first-delivery-order.md)) |

DB 안 consumer는 `tx_id < pg_snapshot_xmin(pg_current_snapshot())` 아래만 읽는 cursor를 쓴다. 장기 트랜잭션이 consumer를 멈추게 하는지는 `atlas.order.inventory.event.relay.stalled_seconds`로 보고, relay 진행은 `atlas.order.inventory.event.relay.lag_events`·`published`·`failed`로 본다. 정확성은 `load-tests/event-log-gap-check.sh`로 확인한다. 쓰기 8개가 400건씩 총 3,200건을 쓴 1회 실행에서 현행 방식(`event_seq > cursor`)은 1,013건을 놓쳤고 xmin 안전 방식은 0건을 놓쳤다([EXP-061](03-experiments/EXP-061-event-log-fanout-and-replay.md)).

측정은 아래 스크립트로 재현한다. 결과는 git-ignore된 `.local/` 아래에만 쓴다.

```sh
# 이벤트 로그 fan-out·replay. 기본값은 RATE=500, DURATION=60(초 단위 정수), CONSUMERS=3, BATCH=500, POLL_MS=200이다.
load-tests/run-event-log-fanout.sh
# EXP-061의 10,000/s·consumer 12개 조건
RATE=10000 CONSUMERS=12 BATCH=5000 POLL_MS=100 DURATION=30 load-tests/run-event-log-fanout.sh
```

이벤트 로그 fan-out 측정은 [EXP-061](03-experiments/EXP-061-event-log-fanout-and-replay.md), 그 결과로 Kafka를 기본 경로에 도입하지 않은 판단은 [ADR-0067](02-decisions/adr/ADR-0067-event-log-kafka-relay.md)에 있다.

## 분산 이행 단계별 근거

주문·재고·결제를 분산 구성으로 옮기는 8단계(4단계의 hot SKU gate는 4a로 따로 적었다) 각각에 어떤 근거가 있는지와 남은 공백을 적는다. 상태는 `검증됨`(근거 문서와 테스트·실험이 있음), `부분`(일부만 근거가 있음), `미구현`이다. 실험은 모두 로컬 단일 호스트의 탐색적 측정이라 운영 보장이 아니며, 수치는 각 문서에 적힌 조건(workload, n, 환경)과 함께 읽는다.

테스트 baseline은 `./gradlew clean check --no-build-cache --rerun-tasks --no-daemon` 기준 746건이다(skipped 0, failures 0, errors 0, 10분 40초, [ADR-0069](02-decisions/adr/ADR-0069-layered-naming-and-runtime-configuration.md)). 모듈별 건수는 application 507, order 48, payment 38, catalog 37, account 29, settlement 26, inventory-location 20, admission 19, campaign 10, cart 7, inventory 5이며 합계가 746이다. 이 PR에서 신규·변경한 테스트 클래스 19개의 합계는 84건이다. 근거 열의 클래스 옆 숫자는 그 baseline의 클래스별 실행 건수다.

> 위 건수는 ADR-0069 시점의 값이다. 이후 campaign·cart 모듈이 admission·order로 합쳐지고([ADR-0071](02-decisions/adr/ADR-0071-merge-small-campaign-cart-modules.md)) 테스트가 늘어, main `d0790ca2`에서 같은 명령으로 측정한 전체는 955건(skipped 0, failures 0, errors 0, 588초)이었다. 모듈별 건수와 근거 열의 클래스별 건수는 746건 시점의 값이며 다시 세지 않았다.

| 단계 | 상태 | 근거 | 측정값과 조건 | 한계 |
|---|---|---|---|---|
| 1. 단일 모놀리스 기준선 | 검증됨 | [ADR-0058](02-decisions/adr/ADR-0058-distributed-fulfillment-stage-one-baseline.md), `CartCheckoutService`/`CheckoutPaymentService`/`OrderFulfillmentService`, 테스트 6종(`LocationRoutedCheckoutIntegrationTest` 5, `InventoryConcurrencyIntegrationTest` 3, `OrderShipmentIntegrationTest` 5, `OrderRefundLifecycleIntegrationTest` 24, `BuyerFlowIntegrationTest` 50, `PaymentWorkflowCrashWindowIntegrationTest` 3) | 6개 클래스 실행 90건 통과. 성능 수치는 없다. ADR-0058은 2026-11-20 병합 전 83건 통과와 정적 `@Test` 집계 96개를 적었고, 90건과의 차이는 확인하지 못했다 | 기준선은 단일 PostgreSQL 안의 동작 확인이며 분산 장애는 다루지 않는다 |
| 2. 재고 잠금 전략 선택 | 검증됨 | [EXP-055](03-experiments/EXP-055-stock-lock-strategy-comparison.md), `atlas-inventory-location/src/test/java/com/atlas/inventorylocation/InventoryReservationIntegrationTest.java`(13건) | 재고 2,000에 32 스레드가 100회씩(3,200회) 경쟁, 방식별 n=3. 소요 시간은 조건부 UPDATE 690~1,838 ms, 비관적 잠금 7,166~12,447 ms, 낙관적 잠금 4,283~6,109 ms이고 초과 판매는 0이다 | 로컬 합성 workload 비교이며 연결 풀이 없다. 측정 harness를 저장소에 두지 않아 재현할 수 없고 운영 처리량 보장이 아니다 |
| 3. 독립 재고 위치 서비스 | 검증됨 | [ADR-0059](02-decisions/adr/ADR-0059-independent-inventory-location-service.md), [EXP-053](03-experiments/EXP-053-inventory-location-failure-load.md), `InventoryLocationLayerBoundaryTest` 6, `InventoryReservationExpiryWorkerTest` 1 | 1,000/s × 30초, 구성당 1회. location 1개는 24,649건 완료(약 776/s)·dropped 5,352, location 2개·DB 2개는 30,001건 완료(약 1,000/s)·dropped 0 | 같은 호스트의 container와 Toxiproxy 지연·중단 주입만 확인했고 실제 네트워크 분리·다중 호스트는 측정하지 않았다. 두 구성 모두 1회라 방향성 실험이다 |
| 4. 주문 재고 예약 workflow와 복구 | 검증됨 | [ADR-0060](02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md), [ADR-0062](02-decisions/adr/ADR-0062-order-recovery-backlog-observability.md), [ADR-0063](02-decisions/adr/ADR-0063-order-inventory-write-batching.md), [ADR-0064](02-decisions/adr/ADR-0064-order-inventory-just-in-time-lease-claims.md), [EXP-056](03-experiments/EXP-056-order-inventory-recovery-capacity.md), [EXP-057](03-experiments/EXP-057-checkout-recovery-index.md), [EXP-058](03-experiments/EXP-058-order-reservation-submit-capacity.md), [EXP-059](03-experiments/EXP-059-order-reservation-load-and-idempotent-replay.md), `LocationReservationServiceIntegrationTest` 15, `RemoteReservationCheckoutIntegrationTest` 15, `CheckoutCancelWithoutAttemptIntegrationTest` 3, `CheckoutCaptureFencePostgresIntegrationTest` 4, `SellerSkuGlobalFifoMigrationIntegrationTest` 5, [EXP-064](03-experiments/EXP-064-location-fault-convergence.md), [EXP-065](03-experiments/EXP-065-claim-cancel-race.md), [EXP-066](03-experiments/EXP-066-db-outage-convergence.md) | EXP-056: backlog 1,000건을 poller 32개·batch 50으로 1.290초에 처리(775.00 workflow/s). EXP-058: Testcontainers probe 동시 호출 32에서 528.99 workflow/s(p95 95.49 ms). EXP-059: 250/s × 30초·location 2개에서 iteration 7,329건·dropped 172·p95 1,026.77 ms. 셋 모두 구성당 1회 | 단일 호스트 측정이며 worker 설정값의 운영 기준이 아니다. EXP-058은 service 메서드를 직접 호출하는 probe라 HTTP·inventory DB가 빠져 EXP-059 값과 비교할 수 없다. 합성 1,000 order/s 목표는 EXP-056에서 77.5%, EXP-058에서 52.9%였고 EXP-059의 1,000/s 실행은 dropped가 14,806~16,637건이었다 |
| 4a. 핫 SKU admission gate (선택) | 부분 | [ADR-0066](02-decisions/adr/ADR-0066-redis-admission-gate.md), [EXP-060](03-experiments/EXP-060-hot-sku-admission-gate.md), [EXP-062](03-experiments/EXP-062-admission-gate-paired-comparison.md), [EXP-063](03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md), `RedisStockAdmissionControlTest` 3, `PrecheckStockAdmissionControlTest` 2 | 재고 100인 SKU 1개, 1,000/s. gate 끈 기준선 completed/s 383.0~525.0(n=3, 20초), Redis gate 991.3~992.2(n=2, 20초). 같은 세션 교차 비교(30초)에서 precheck 중앙값 988(n=6)과 Redis gate 996(n=6)은 잡음 안에서 같다 | 기준선과 gate는 세션이 달라 퍼센트 개선을 근거로 쓰지 않는다. 다수 SKU 분포, 실제 네트워크 지연, Redis 복구 뒤 breaker 동작은 측정하지 않았다 |
| 5. 독립 결제 서비스 | 부분 | `atlas-application/src/test/java/com/atlas/payment/PaymentLifecycleIntegrationTest.java` 22건(provider 결과 유실 후 같은 request ID로 복구), `atlas-application/src/test/java/com/atlas/payment/PaymentRecoveryIntegrationTest.java` 6건(동시 worker claim과 lease fence), `PaymentWorkflowCrashWindowIntegrationTest` 3건, [ADR-0038](02-decisions/adr/ADR-0038-durable-payment-recovery-workflow.md), [ADR-0075](02-decisions/adr/ADR-0075-keep-payment-in-process.md), [EXP-073](03-experiments/EXP-073-paypal-sandbox-payouts-response-shapes.md), [EXP-077](03-experiments/EXP-077-payout-dispatch-provider-integration.md), [EXP-081](03-experiments/EXP-081-paypal-double-vs-sandbox.md) | 위 3개 클래스 31건 통과. 성능 수치는 측정하지 않았다 | PayPal unknown-result 복구와 capture 전 claim은 테스트됐지만 **독립 결제 서비스는 만들지 않았다**. 결제는 여전히 같은 애플리케이션·DB 안에 있다 |
| 6. 이벤트 로그와 broker | 검증됨(옵트인 relay) | [ADR-0067](02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [EXP-061](03-experiments/EXP-061-event-log-fanout-and-replay.md), `ReservationEventKafkaIntegrationTest` 1, `ReservationEventReadIntegrationTest` 1, `ReservationEventTriggerIntegrationTest` 1, [EXP-067](03-experiments/EXP-067-event-consumer-contract.md), [EXP-072](03-experiments/EXP-072-kafka-outage-relay.md), [EXP-078](03-experiments/EXP-078-relay-lease-length.md) | 합성 이벤트, 구성당 1회. 500/s·consumer 3개에서 lag 최대 98건, 10,000/s·consumer 12개(batch 5,000·poll 100 ms)에서 최대 1,229건. cursor 손실 재현은 3,200건 중 현행 방식 1,013건 누락, xmin 안전 방식 0건 | 현재 측정 workload는 PostgreSQL 로그만으로 충분했지만 독립 consumer 경로 검증을 위해 Kafka relay를 옵트인으로 구현했다. 기본 실행에는 Kafka가 필요하지 않고 외부 consumer 요구가 없으면 relay를 켤 이유도 없다. 앱 경로의 trigger 오버헤드와 실제 payload 크기는 측정하지 않았다(조건과 한계는 ADR-0067) |
| 7. 부분 이행·부분 환불 | 부분 | [ADR-0061](02-decisions/adr/ADR-0061-return-restock-source-shipment.md), [EXP-054](03-experiments/EXP-054-location-split-allocation.md), `SplitAllocationCheckoutIntegrationTest` 2, `LocationAllocationPolicyTest` 2, `LocationRestockServiceImplTest` 2, `LocationReservationServiceIntegrationTest` 분할 주문 반품 3건 | location 3곳, SKU A 70·B 65, 주문 70건(결정적 입력 1회). 단일 location만 허용하면 30건, 분할을 허용하면 65건 수락(분할 주문 35건), 초과 판매 0 | 위치 분할 할당과 반품 재입고의 원 shipment 추적까지만 근거가 있다. location별 부분 취소·부분 환불은 근거가 없고 배송비·출고 용량은 입력에 없다 |
| 8. 장애 주입 | 부분 | [EXP-053](03-experiments/EXP-053-inventory-location-failure-load.md), [EXP-059](03-experiments/EXP-059-order-reservation-load-and-idempotent-replay.md), [EXP-060](03-experiments/EXP-060-hot-sku-admission-gate.md), [EXP-063](03-experiments/EXP-063-redis-gate-failopen-and-paired-rerun.md), [EXP-070](03-experiments/EXP-070-real-stack-fault-injection.md), [EXP-071](03-experiments/EXP-071-production-like-fault-injection.md), [EXP-072](03-experiments/EXP-072-kafka-outage-relay.md) | 응답 유실 replay 1회(20 workflow/s × 5초): proxy가 응답을 폐기한 101건을 같은 key로 재전송했고 workflow 101건이 모두 `RESERVED`였다(EXP-059). JVM 강제 종료 뒤 `RESERVED` 수렴은 10초, 1초 해상도, 1회(EXP-053). Redis 중지(1,000/s)에서 breaker가 없으면 355/s(n=1), 있으면 707~730/s(n=2), 오버셀 없음(EXP-060) | Toxiproxy 지연·중단과 Redis 중지를 단일 호스트에서 탐색적으로 주입했다. 정기 자동 회귀나 다중 호스트 카오스 시험은 없고 Redis 복구 뒤 breaker가 닫히는 동작은 측정하지 못했다 |

## 만료 sweep 운영

`RESERVED` 주문과 buyer checkout의 만료 처리는 1초 주기 scheduler가 담당합니다. 한 tick은 후보를 최대 `ATLAS_ORDERS_EXPIRY_SWEEP_BATCH_SIZE`(기본 100)건까지만 조회하고, 경과 시간이 `ATLAS_ORDERS_EXPIRY_SWEEP_TIME_BUDGET`(기본 `PT2S`)을 넘으면 남은 후보를 다음 tick으로 넘깁니다. 후보별 개별 transaction·잠금 순서·상태 전이는 기존과 동일합니다.

- 만료가 지연되면 해당 주문의 재고 reservation이 계속 점유되어 다른 구매자에게 품절로 보일 수 있으므로, backlog가 지속되면 batch를 키우거나 [ADR-0039](02-decisions/adr/ADR-0039-bounded-expiry-sweep.md)의 후속 검토를 따릅니다.
- 상한 적용 전후 후보 조회 비용은 [EXP-079](03-experiments/EXP-079-expiry-sweep-bound.md)에 기록했습니다(로컬 합성 데이터, 운영 SLA 아님).

### Admission queue 만료

Admission `WAITING`/`CHECKOUT_ACTIVE` ticket은 1초 poll에서 `(expires_at, ticket_id)` keyset으로 due rows를 이어 읽습니다. `ATLAS_ADMISSION_EXPIRY_SWEEP_BATCH_SIZE`(기본 100, 후보 window 2×)와 `ATLAS_ADMISSION_EXPIRY_SWEEP_TIME_BUDGET`(기본 `PT2S`, 정수초만 허용)으로 batch/work를 제한합니다. Candidate query timeout 후 성공한 page는 deadline이 지나도 첫 pair를 처리/skip해 cursor progress를 보장하며, 이때 pair transaction의 정수초 timeout 때문에 tick은 budget보다 최대 약 1초 더 걸릴 수 있습니다. Query timeout/error는 cursor를 보존해 다음 tick에 재시도하고 WARN은 분당 1회로 제한합니다. Counter가 다른 instance에 잠겨 있으면 `SKIP LOCKED`로 기다리지 않고 cursor를 전진시킵니다. V41 composite index가 first/cursor read를 정렬된 index scan으로 지원합니다. 세부 한계와 측정은 [ADR-0041](02-decisions/adr/ADR-0041-bounded-admission-expiry-sweep.md), [EXP-029](03-experiments/EXP-029-admission-expiry-candidates.md)에 기록했습니다.

## `pg_wal` 볼륨이 가득 찰 때

order DB의 `pg_wal`이 별도 볼륨이고 그 볼륨이 가득 차면 PostgreSQL은 WAL 세그먼트(16MB) 경계를 넘는 쓰기에서 `PANIC: could not write to file "pg_wal/...": No space left on device`로 서버를 종료합니다([EXP-095](03-experiments/EXP-095-fault-injection-deep-cells.md), 로컬 128MiB ext4 볼륨, 경계를 넘은 10/10). 증상은 application의 order DB 연결 실패(5xx)와 DB 로그의 `PANIC`·`No space left`입니다. 복구는 공간 확보뿐입니다: `pg_wal`의 채움 원인을 지우거나 볼륨을 늘리고, 컨테이너가 종료돼 있으면 한 번 시작합니다(`redo`가 끝나고 1~2초 안에 연결을 받았고 `pg_resetwal`은 필요 없었습니다). 재시작 정책(`restart: unless-stopped`)이 있으면 채움이 이어지는 동안 DB가 재시작을 되풀이하며(30초에 8번) 공간을 확보하면 사람 없이 6~9초 안에 복구됩니다. 두 경우 모두 응답받은 예약 유실과 중복 예약은 0이었습니다. 알람은 `pg_wal` 볼륨 사용률(가득 차기 전)과 DB 컨테이너 재시작 횟수에 거는 것이 좋습니다. 이것은 인프라 책임이며 application 설정으로 막을 수 없습니다.
