#!/bin/bash
# Real sandbox only. Prerequisites: a built runnable ATLAS jar, running local
# PostgreSQL with ATLAS schema/data and a running LOCAL Toxiproxy API. The
# Toxiproxy process must resolve api-m.sandbox.paypal.com normally and have
# outbound TLS access; its API and proxy listeners must not be publicly exposed.
#
# Supply PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET in the environment (e.g.
# GitHub Actions secrets), not shell command-line arguments. Example from root:
# REAL_SANDBOX_OPT_IN=YES APP_JAR="$PWD/ATLAS-application/build/libs/ATLAS-application.jar" \
# BUYERS_FILE="$PWD/private/buyers.json" SELLER_ID_FILE="$PWD/private/seller-id" \
# JWT_SECRET_FILE="$PWD/private/jwt-secret" DB_PASSWORD_FILE="$PWD/private/db-password" \
# DB_URL='jdbc:postgresql://127.0.0.1:5432/atlas' DB_USER=atlas \
# bash load-tests/paypal-real-fault/run.sh
# Optional: DURATION_SECONDS=30 PAYMENT_RATE=1 CATALOG_RATE=5, APP_PORT=18080,
# TOXI_API_PORT=8474, TOXI_PROXY_PORT=18443, LATENCY_MS=0 (max 30000).
# Input format and k6 inspect syntax-only command are documented in workload.js.
# JWT signing secret/issuer/audience must match the pre-provisioned JWTs; set
# JWT_ISSUER and JWT_AUDIENCE if they differ from the defaults below. The DB
# must have eligible buyer checkouts for the seller/SKU and sufficient stock.
# Without BOK credentials, a precreated NOT_STARTED authorization attempt can
# contain a consistent immutable FX snapshot explicitly labelled as a test
# fixture; PayPal HTTP still runs for real. This creates real sandbox orders;
# do not approve them. An already approved order or another actor's approval
# can trigger application recovery/capture; use isolated, unapproved checkouts.
# No TLS verification is disabled: JVM hostname stays api-m.sandbox.paypal.com,
# hosts-file resolution points only that name to loopback, while Toxiproxy
# forwards encrypted TLS to the actual Sandbox. Never set a local proxy target
# or alter CA/trust settings to impersonate PayPal. No provider bodies or
# credentials are printed. Application logs are private and deleted on exit.
set -euo pipefail
umask 077
fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ ${REAL_SANDBOX_OPT_IN:-} == YES ]] || fail 'Explicit REAL_SANDBOX_OPT_IN=YES is required'
for cmd in java k6 curl nc python3 psql; do command -v "$cmd" >/dev/null || fail "Missing prerequisite: $cmd"; done
for var in APP_JAR BUYERS_FILE SELLER_ID_FILE PAYPAL_CLIENT_ID PAYPAL_CLIENT_SECRET JWT_SECRET_FILE DB_PASSWORD_FILE DB_URL DB_USER; do
  [[ -n ${!var:-} ]] || fail "Missing $var"
done
for var in APP_JAR BUYERS_FILE SELLER_ID_FILE JWT_SECRET_FILE DB_PASSWORD_FILE; do
  [[ ${!var} == /* && -f ${!var} && -r ${!var} && -s ${!var} ]] || fail "$var must be an absolute path to a nonempty readable file"
done
# Database URLs may not contain credentials, hosts other than IPv4 loopback,
# query parameters or alternate hosts. Do not point this experiment at prod.
[[ $DB_URL =~ ^jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_-]+$ ]] || fail 'DB_URL must be a simple loopback PostgreSQL JDBC URL without userinfo or parameters'
[[ $DB_USER =~ ^[A-Za-z0-9_-]+$ ]] || fail 'Invalid DB_USER'
number() { local n=$1 value=$2 max=$3; [[ $value =~ ^[0-9]{1,5}$ ]] && (( 10#$value <= max )) || fail "Invalid $n"; }
APP_PORT=${APP_PORT:-18080}; TOXI_API_PORT=${TOXI_API_PORT:-8474}; TOXI_PROXY_PORT=${TOXI_PROXY_PORT:-18443}; LATENCY_MS=${LATENCY_MS:-0}
for var in APP_PORT TOXI_API_PORT TOXI_PROXY_PORT; do number "$var" "${!var}" 65535; (( 10#${!var} > 0 )) || fail "Invalid $var"; done
number LATENCY_MS "$LATENCY_MS" 30000
[[ $APP_PORT != "$TOXI_API_PORT" && $APP_PORT != "$TOXI_PROXY_PORT" && $TOXI_API_PORT != "$TOXI_PROXY_PORT" ]] || fail 'Ports must be distinct'
nc -z 127.0.0.1 "$TOXI_API_PORT" || fail 'Local Toxiproxy API is not listening'
if nc -z 127.0.0.1 "$APP_PORT" 2>/dev/null || nc -z 127.0.0.1 "$TOXI_PROXY_PORT" 2>/dev/null; then fail 'Application or proxy port is already in use'; fi
work=$(mktemp -d "${TMPDIR:-/tmp}/atlas-real-fault.XXXXXXXX")
proxy="atlas-sandbox-$$"
created=0
app_pid=''
cleanup() {
  if [[ -n $app_pid ]]; then kill "$app_pid" 2>/dev/null || :; wait "$app_pid" 2>/dev/null || :; fi
  if (( created )); then curl --noproxy '*' -fsS -X DELETE "http://127.0.0.1:$TOXI_API_PORT/proxies/$proxy" -o /dev/null 2>/dev/null || printf '%s\n' 'Warning: remove the experiment proxy from local Toxiproxy manually.' >&2; fi
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Refuse non-small or unprepared transactions before starting the application.
# This requires exclusive ownership of an isolated local database for the whole
# run; concurrent writers could otherwise invalidate the preflight check.
# The synthetic immutable FX setup fixture (not an ECOS result) must agree
# with the checkout's KRW total; never use this on a production database.
# and USD caps are hardcoded: <= 10,000 won and <= 500 US cents per checkout.
python3 - "$BUYERS_FILE" >"$work/preflight.sql" <<'PY' || fail 'Invalid checkout fixture file'
import base64, json, re, sys, uuid
try:
    rows = json.load(open(sys.argv[1]))
    ids = [r['checkoutId'] for r in rows]
    if not ids or any(not isinstance(x, str) or not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', x) for x in ids):
        raise ValueError()
    if len(set(x.lower() for x in ids)) != len(ids):
        raise ValueError()
    # Decode only the subject for ownership preflight; the app verifies JWT
    # signature, expiry and session. Never print the JWT or decoded claims.
    subjects = [str(uuid.UUID(json.loads(base64.urlsafe_b64decode(
        r['token'].split('.')[1] + '==='))['sub'])) for r in rows]
    values = ','.join("('%s'::uuid,'%s'::uuid)" % pair for pair in zip(ids, subjects))
    print("""WITH ids(id, account_id) AS (VALUES %s)
SELECT CASE WHEN count(*) = %d AND count(*) = count(a.checkout_id)
 AND bool_and(c.state = 'RESERVED' AND c.total_won BETWEEN 1 AND 10000
 AND EXISTS (SELECT 1 FROM buyer_account b WHERE b.account_id = ids.account_id AND b.buyer_id::text = c.buyer_ref)
 AND a.total_won = c.total_won AND a.state = 'NOT_STARTED'
 AND a.payment_phase = 'AUTHORIZATION' AND a.provider_order_id IS NULL
 AND a.provider_currency = 'USD' AND a.provider_amount_minor BETWEEN 1 AND 500
 AND a.fx_rate_won_per_usd > 0 AND a.fx_rate_source = 'SYNTHETIC_FIXTURE' AND a.fx_rate_as_of IS NOT NULL
 AND a.provider_amount_minor = round(c.total_won::numeric / a.fx_rate_won_per_usd * 100))
 THEN 1 ELSE 0 END
FROM ids LEFT JOIN buyer_checkout c ON c.checkout_id = ids.id
LEFT JOIN payment_attempt a ON a.checkout_id = ids.id;""" % (values, len(ids)))
except Exception:
    sys.exit(1)
PY
[[ $DB_URL =~ ^jdbc:postgresql://127\.0\.0\.1:([0-9]{1,5})/([A-Za-z0-9_-]+)$ ]] || fail 'Invalid database URL'
db_port=${BASH_REMATCH[1]}; db_name=${BASH_REMATCH[2]}
result=$(export PGHOST=127.0.0.1 PGPORT="$db_port" PGDATABASE="$db_name" PGUSER="$DB_USER" PGPASSWORD="$(cat "$DB_PASSWORD_FILE")" PGSSLMODE=disable; psql -XAtq -v ON_ERROR_STOP=1 -f "$work/preflight.sql" 2>/dev/null) || fail 'Local database preflight failed'
[[ $result == 1 ]] || fail 'Checkout fixture violates preparation, FX or transaction amount limits'
printf '127.0.0.1 localhost api-m.sandbox.paypal.com\n::1 localhost\n' > "$work/hosts"
# Confirm the same Java executable and hosts-file option used for the app
# actually resolve the Sandbox hostname exclusively to IPv4 loopback.
cat >"$work/ResolveCheck.java" <<'JAVA'
import java.net.InetAddress;
class ResolveCheck {
  public static void main(String[] args) throws Exception {
    InetAddress[] addresses = InetAddress.getAllByName("api-m.sandbox.paypal.com");
    if (addresses.length == 0) throw new IllegalStateException();
    for (InetAddress address : addresses)
      if (!"127.0.0.1".equals(address.getHostAddress())) throw new IllegalStateException();
  }
}
JAVA
env -i PATH="$PATH" HOME="$work" java -Djdk.net.hosts.file="$work/hosts" "$work/ResolveCheck.java" > /dev/null 2>&1 || fail 'JVM cannot enforce Sandbox loopback DNS'
# API returns the proxy representation; discard it rather than logging bodies.
curl --noproxy '*' -fsS -X POST -H 'Content-Type: application/json' \
  --data "{\"name\":\"$proxy\",\"listen\":\"127.0.0.1:$TOXI_PROXY_PORT\",\"upstream\":\"api-m.sandbox.paypal.com:443\"}" \
  "http://127.0.0.1:$TOXI_API_PORT/proxies" -o /dev/null || fail 'Could not create local proxy'
created=1
# Verify the server's actual proxy definition, not just the POST payload.
# A private local Toxiproxy is trusted: it must resolve the upstream through
# real DNS and must not be reconfigured concurrently by another operator.
curl --noproxy '*' -fsS "http://127.0.0.1:$TOXI_API_PORT/proxies/$proxy" -o "$work/proxy.json" || fail 'Cannot inspect local proxy'
python3 - "$work/proxy.json" "$TOXI_PROXY_PORT" <<'PY' || fail 'Proxy topology does not match Sandbox-only policy'
import json, sys
try:
    p = json.load(open(sys.argv[1]))
    assert p['enabled'] is True
    assert p['listen'] == '127.0.0.1:' + sys.argv[2]
    assert p['upstream'] == 'api-m.sandbox.paypal.com:443'
except Exception:
    sys.exit(1)
PY
if (( 10#$LATENCY_MS > 0 )); then
  curl --noproxy '*' -fsS -X POST -H 'Content-Type: application/json' \
    --data "{\"name\":\"sandbox-latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$((10#$LATENCY_MS)),\"jitter\":0}}" \
    "http://127.0.0.1:$TOXI_API_PORT/proxies/$proxy/toxics" -o /dev/null || fail 'Could not configure latency'
fi
# Remove inherited exports before selectively exporting the app configuration:
# no Spring/JVM overrides, HTTP proxies, or trust-store overrides can leak in.
# Secret values go directly from environment/files to the JVM environment.
(
  while IFS='=' read -r name rest; do export -n "$name" 2>/dev/null || :; done < <(env)
  export PATH HOME="$work"
  export ATLAS_SERVER_ADDRESS=127.0.0.1 ATLAS_SERVER_PORT="$APP_PORT"
  export ATLAS_DB_URL="$DB_URL" ATLAS_DB_USER="$DB_USER" ATLAS_DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"
  export ATLAS_JWT_SIGNING_SECRET="$(cat "$JWT_SECRET_FILE")"
  export ATLAS_JWT_ISSUER="${JWT_ISSUER:-http://127.0.0.1:8080}" ATLAS_JWT_AUDIENCE="${JWT_AUDIENCE:-atlas-api}"
  export ATLAS_PAYMENT_PROVIDER=paypal ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED=false ATLAS_PAYPAL_WEBHOOK_RETRY_ENABLED=false
  export PAYPAL_BASE_URL="https://api-m.sandbox.paypal.com:$TOXI_PROXY_PORT"
  # Highest-priority environment property source pins BOTH the PayPal REST
  # client and OAuth token endpoint. No inherited config, JVM options, custom
  # trust store, HTTP proxy, or command-line Spring overrides are passed.
  export SPRING_APPLICATION_JSON="{\"atlas\":{\"payment\":{\"paypal\":{\"base-url\":\"https://api-m.sandbox.paypal.com:$TOXI_PROXY_PORT\"}}},\"spring\":{\"security\":{\"oauth2\":{\"client\":{\"provider\":{\"paypal\":{\"token-uri\":\"https://api-m.sandbox.paypal.com:$TOXI_PROXY_PORT/v1/oauth2/token\"}}}}}}}"
  export PAYPAL_CLIENT_ID PAYPAL_CLIENT_SECRET
  exec java -Djdk.net.hosts.file="$work/hosts" -jar "$APP_JAR"
) >"$work/application.log" 2>&1 &
app_pid=$!
for ((i=0; i<120; i++)); do
  kill -0 "$app_pid" 2>/dev/null || fail 'Application exited during startup (check local configuration; private logs removed on exit)'
  if nc -z 127.0.0.1 "$APP_PORT" 2>/dev/null; then break; fi
  sleep 1
done
nc -z 127.0.0.1 "$APP_PORT" 2>/dev/null || fail 'Application did not listen within 120 seconds'
# k6 receives only workload inputs and tuning, not PayPal or database secrets.
env -i PATH="$PATH" HOME="$work" REAL_SANDBOX_OPT_IN=YES BASE_URL="http://127.0.0.1:$APP_PORT" \
  BUYERS_FILE="$BUYERS_FILE" SELLER_ID_FILE="$SELLER_ID_FILE" \
  DURATION_SECONDS="${DURATION_SECONDS:-30}" PAYMENT_RATE="${PAYMENT_RATE:-1}" CATALOG_RATE="${CATALOG_RATE:-5}" \
  k6 run "$(dirname "$0")/workload.js"
