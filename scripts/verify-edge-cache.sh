#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:18082}"
RUN_ID="${RUN_ID:-$(date +%s)-$$}"
# Product names in the seed contain "atlas". Vary case (the API normalizes it) to
# create a unique but semantically equivalent allowed query key per run.
QUERY_VARIANT="$(python3 - "$RUN_ID" <<'PY_QUERY'
import random, sys
random.seed(sys.argv[1])
word = "atlas"
print("".join(ch.upper() if random.randrange(2) else ch for ch in word))
PY_QUERY
)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

request() {
  local name="$1" path="$2" expected_status="$3" expected_cache="$4"
  shift 4
  local headers="$TMP_DIR/${name}.headers" body="$TMP_DIR/${name}.body"
  local status
  status="$(curl --silent --show-error --max-time 15 -D "$headers" -o "$body" -w '%{http_code}' "$@" "$BASE_URL$path")"
  local cache
  cache="$(awk 'BEGIN{IGNORECASE=1} /^X-Cache-Status:/ {gsub("\r", "", $2); print $2}' "$headers" | tail -1)"
  local control
  control="$(awk 'BEGIN{IGNORECASE=1} /^Cache-Control:/ {$1=""; sub(/^ /, ""); gsub("\r", ""); print}' "$headers" | tail -1)"
  if [[ "$status" != "$expected_status" ]]; then
    echo "FAIL $name: expected status $expected_status, got $status" >&2
    return 1
  fi
  if [[ "$cache" != "$expected_cache" ]]; then
    echo "FAIL $name: expected X-Cache-Status $expected_cache, got ${cache:-<missing>}" >&2
    return 1
  fi
  echo "PASS $name: status=$status cache=$cache bytes=$(wc -c < "$body" | tr -d ' ') cache-control=${control:-<none>}"
}

# A unique query key guarantees the first request is cold.
public_path="/api/products?limit=100&query=$QUERY_VARIANT"
request "cold-public" "$public_path" 200 MISS
request "warm-public" "$public_path" 200 HIT

# Sale prices have start/end instants, so the public TTL is capped at one second
# and stale-while-revalidate is deliberately disabled. After expiry the origin is
# queried again instead of serving a potentially stale price.
sleep 2
request "expired-public" "$public_path" 200 EXPIRED

# Query parameters are part of the cache key.
request "different-query" "/api/products?limit=99&query=$QUERY_VARIANT" 200 MISS

# Host is part of the cache key so multiple domains cannot share variants.
host_path="$public_path"
request "host-a-cold" "$host_path" 200 MISS -H 'Host: a.example.test'
request "host-a-warm" "$host_path" 200 HIT -H 'Host: a.example.test'
request "host-b-isolated" "$host_path" 200 MISS -H 'Host: b.example.test'

# Compressed and identity representations are separate cache variants.
encoding_path="$public_path"
request "gzip-cold" "$encoding_path" 200 MISS -H 'Accept-Encoding: gzip'
if ! grep -qi '^Content-Encoding: gzip' "$TMP_DIR/gzip-cold.headers"; then
  echo "FAIL gzip-cold: expected Content-Encoding: gzip" >&2
  exit 1
fi
request "identity-cold" "$encoding_path" 200 MISS -H 'Accept-Encoding: identity'
if grep -qi '^Content-Encoding: gzip' "$TMP_DIR/identity-cold.headers"; then
  echo "FAIL identity-cold: got a gzip body for identity request" >&2
  exit 1
fi
request "gzip-warm" "$encoding_path" 200 HIT -H 'Accept-Encoding: gzip'

# Cookie-bearing requests bypass the shared cache (future personalization guard).
request "cookie-bypass" "$host_path" 200 BYPASS -H 'Cookie: future-session=opaque'

# Unknown query parameters are not admitted, preventing cache-key pollution/churn.
unknown_path="$public_path&unrecognizedProbe=$RUN_ID"
request "unknown-query-first" "$unknown_path" 200 MISS
request "unknown-query-repeat" "$unknown_path" 200 MISS

# Authorization requests bypass shared storage and receive the application's no-store policy.
request "authorization-bypass" "$public_path" 401 BYPASS \
  -H 'Authorization: Bearer invalid'

# Errors must never become shared cache hits.
missing="/api/products/00000000-0000-0000-0000-000000000000?cacheProbe=$RUN_ID"
request "public-404-first" "$missing" 404 MISS
request "public-404-repeat" "$missing" 404 MISS

echo "Edge cache verification passed."
