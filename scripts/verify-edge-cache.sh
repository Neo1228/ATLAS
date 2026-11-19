#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:18082}"
RUN_ID="${RUN_ID:-$(date +%s)-$$}"
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
request "cold-public" "/api/products?limit=1&cacheProbe=$RUN_ID" 200 MISS
request "warm-public" "/api/products?limit=1&cacheProbe=$RUN_ID" 200 HIT

# Sale prices have start/end instants, so the public TTL is capped at one second
# and stale-while-revalidate is deliberately disabled. After expiry the origin is
# queried again instead of serving a potentially stale price.
sleep 2
request "expired-public" "/api/products?limit=1&cacheProbe=$RUN_ID" 200 EXPIRED

# Query parameters are part of the cache key.
request "different-query" "/api/products?limit=2&cacheProbe=$RUN_ID" 200 MISS

# Authorization requests bypass shared storage and receive the application's no-store policy.
request "authorization-bypass" "/api/products?limit=1&cacheProbe=$RUN_ID" 401 BYPASS \
  -H 'Authorization: Bearer invalid'

# Errors must never become shared cache hits.
missing="/api/products/00000000-0000-0000-0000-000000000000?cacheProbe=$RUN_ID"
request "public-404-first" "$missing" 404 MISS
request "public-404-repeat" "$missing" 404 MISS

echo "Edge cache verification passed."
