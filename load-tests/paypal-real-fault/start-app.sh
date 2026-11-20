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

base="https://api-m.sandbox.paypal.com:$proxy_port"
exec env -i PATH="$PATH" HOME="$private" \
  ATLAS_SERVER_ADDRESS=127.0.0.1 ATLAS_SERVER_PORT="$app_port" \
  ATLAS_PUBLIC_BASE_URL="http://127.0.0.1:$app_port" \
  ATLAS_DB_URL="jdbc:postgresql://127.0.0.1:$db_port/atlas" ATLAS_DB_USER=atlas \
  ATLAS_DB_PASSWORD="${DB_PASSWORD:-atlas-real-local}" \
  ATLAS_JWT_SIGNING_SECRET="$(cat "$private/jwt")" \
  SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT="$mail_port" \
  ATLAS_PAYMENT_PROVIDER=paypal PAYPAL_BASE_URL="$base" \
  PAYPAL_CLIENT_ID="$client_id" PAYPAL_CLIENT_SECRET="$client_secret" \
  ATLAS_PAYPAL_WEBHOOK_RETRY_ENABLED=false \
  java -Djdk.net.hosts.file="$private/hosts" -jar "$root/ATLAS-application/build/libs/application-module.jar" \
  --management.endpoints.web.exposure.include=health,prometheus,metrics
