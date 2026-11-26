#!/bin/bash
# 실제 PayPal Sandbox 장애 실험용 ATLAS 실행 스크립트(로컬 전용).
# - PayPal 자격 증명은 저장소 루트의 .env.local(git 제외)에서만 읽고 출력하지 않는다.
# - JVM hosts 파일로 api-m.sandbox.paypal.com 을 127.0.0.1 로 고정하고, 같은 호스트명의
#   Toxiproxy 포트로 보낸다. TLS SNI·인증서 검증은 실제 Sandbox 호스트명 그대로 유지된다.
# - DB·메일은 실험 전용 로컬 컨테이너만 허용한다.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/../.." && pwd)
env_file="$root/.env.local"
private=${PRIVATE_DIR:-/tmp/atlas-real}
app_port=${APP_PORT:-18980}
proxy_port=${TOXI_PROXY_PORT:-18443}
db_port=${DB_PORT:-15442}
mail_port=${MAIL_SMTP_PORT:-11025}

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ -r $env_file ]] || fail '.env.local 이 없다'
[[ -r $private/hosts && -r $private/jwt ]] || fail "$private/hosts, $private/jwt 가 필요하다"
grep -q '^127\.0\.0\.1 api-m\.sandbox\.paypal\.com$' "$private/hosts" || fail 'hosts 파일이 Sandbox loopback 고정이 아니다'

value() { grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2-; }
client_id=$(value PAYPAL_CLIENT_ID); client_secret=$(value PAYPAL_CLIENT_SECRET)
[[ -n $client_id && -n $client_secret ]] || fail '.env.local 에 Sandbox 자격 증명이 없다'
[[ $(value PAYPAL_BASE_URL) == https://api-m.sandbox.paypal.com ]] || fail 'PAYPAL_BASE_URL 은 Sandbox 여야 한다'

[[ $client_id != *$'\n'* && $client_secret != *$'\n'* ]] || fail 'Sandbox 자격 증명은 한 줄이어야 한다.'
base="https://api-m.sandbox.paypal.com:$proxy_port"
# 자격 증명은 stdin으로 자식 shell에 전달하므로 정적 command 문자열에서만 환경변수로 확장한다.
# shellcheck disable=SC2016
exec env -i PATH="$PATH" HOME="$private" \
  ATLAS_SERVER_ADDRESS=127.0.0.1 ATLAS_SERVER_PORT="$app_port" \
  ATLAS_PUBLIC_BASE_URL="http://127.0.0.1:$app_port" \
  ATLAS_DB_URL="jdbc:postgresql://127.0.0.1:$db_port/atlas" ATLAS_DB_USER=atlas \
  ATLAS_DB_PASSWORD="${DB_PASSWORD:-atlas-real-local}" \
  ATLAS_JWT_SIGNING_SECRET="$(cat "$private/jwt")" \
  SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT="$mail_port" \
  ATLAS_PAYMENT_PROVIDER=paypal PAYPAL_BASE_URL="$base" \
  ATLAS_PAYPAL_CAPTURE_BASE_URL="${ATLAS_PAYPAL_CAPTURE_BASE_URL:-}" \
  ATLAS_PAYMENT_EXCHANGE_RATE_PROVIDER="${ATLAS_PAYMENT_EXCHANGE_RATE_PROVIDER:-bok-ecos}" \
  ATLAS_PAYPAL_PAYOUTS_ENABLED="${ATLAS_PAYPAL_PAYOUTS_ENABLED:-false}" \
  ATLAS_PAYPAL_PAYOUT_WORKER_ENABLED="${ATLAS_PAYPAL_PAYOUT_WORKER_ENABLED:-false}" \
  ATLAS_PAYPAL_PAYOUT_WORKER_INITIAL_DELAY="${ATLAS_PAYPAL_PAYOUT_WORKER_INITIAL_DELAY:-PT5S}" \
  ATLAS_PAYPAL_PAYOUT_WORKER_POLL_INTERVAL="${ATLAS_PAYPAL_PAYOUT_WORKER_POLL_INTERVAL:-PT5S}" \
  ATLAS_PAYPAL_PAYOUT_SUPPORTED_COUNTRIES="${ATLAS_PAYPAL_PAYOUT_SUPPORTED_COUNTRIES:-US}" \
  ATLAS_PAYPAL_PAYOUT_RECIPIENT_ENCRYPTION_KEY="${ATLAS_PAYPAL_PAYOUT_RECIPIENT_ENCRYPTION_KEY:-}" \
  ATLAS_SETTLEMENT_PAYOUT_OPERATOR_ENABLED="${ATLAS_SETTLEMENT_PAYOUT_OPERATOR_ENABLED:-false}" \
  ATLAS_SETTLEMENT_PAYOUT_OPERATOR_IDENTITY="${ATLAS_SETTLEMENT_PAYOUT_OPERATOR_IDENTITY:-account}" \
  ATLAS_ACCOUNT_MFA_ENCRYPTION_KEY="${ATLAS_ACCOUNT_MFA_ENCRYPTION_KEY:-}" \
  ATLAS_PAYPAL_LOCAL_PROXY_ENABLED=true \
  ATLAS_PAYPAL_WEBHOOK_RETRY_ENABLED=false \
  ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED="${ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED:-true}" \
  HOSTS_FILE="$private/hosts" \
  APP_EXTRA_JAVA_OPTS="${APP_EXTRA_JAVA_OPTS:-}" \
  APP_JAR="$root/atlas-application/build/libs/atlas-application.jar" \
  /bin/bash -c '
    IFS= read -r PAYPAL_CLIENT_ID || exit 1
    IFS= read -r PAYPAL_CLIENT_SECRET || exit 1
    export PAYPAL_CLIENT_ID PAYPAL_CLIENT_SECRET
    # shellcheck disable=SC2086
    exec java -Djdk.net.hosts.file="$HOSTS_FILE" $APP_EXTRA_JAVA_OPTS -jar "$APP_JAR" \
      --management.endpoints.web.exposure.include=health,prometheus,metrics
  ' <<EOF
$client_id
$client_secret
EOF
