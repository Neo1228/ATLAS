#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
k6_image="grafana/k6:2.3.0@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8"

: "${ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN:?Export the coordinator service token}"
: "${INVENTORY_LOCATION_SERVICE_TOKEN:?Export the inventory service token}"

if (( ${#ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN} < 32
      || ${#INVENTORY_LOCATION_SERVICE_TOKEN} < 32 )); then
  echo 'Coordinator and inventory service tokens must each contain at least 32 characters' >&2
  exit 2
fi

COORDINATOR_URL="http://host.docker.internal:${ATLAS_SERVER_PORT:-8082}/api/internal/order-inventory/reservations"
RUN_ID="${RUN_ID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
RATE="${RATE:-1000}"
DURATION="${DURATION:-30s}"
LOCATIONS="${LOCATIONS:-2}"
MAX_VUS="${MAX_VUS:-1000}"
TRANSPORT_RETRIES="${TRANSPORT_RETRIES:-0}"

if [[ ! "$RUN_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo 'RUN_ID must be a unique UUID' >&2
  exit 2
fi

export COORDINATOR_URL RUN_ID RATE DURATION LOCATIONS MAX_VUS TRANSPORT_RETRIES
export ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN INVENTORY_LOCATION_SERVICE_TOKEN
if [[ ! "$RATE" =~ ^[0-9]+$ ]] || (( RATE < 1 )); then
  echo 'RATE must be a positive integer' >&2
  exit 2
fi
if [[ ! "$TRANSPORT_RETRIES" =~ ^[0-2]$ ]]; then
  echo 'TRANSPORT_RETRIES must be 0, 1, or 2' >&2
  exit 2
fi
if [[ "$DURATION" =~ ^([0-9]+)(s|m)$ ]]; then
  duration_value="${BASH_REMATCH[1]}"
  duration_unit="${BASH_REMATCH[2]}"
  duration_multiplier=1
  if [[ "$duration_unit" == 'm' ]]; then duration_multiplier=60; fi
  duration_seconds=$((duration_value * duration_multiplier))
else
  echo 'DURATION must be an integer duration such as 30s or 1m' >&2
  exit 2
fi
expected_orders=$((RATE * duration_seconds))

output_dir="$repo_root/.local/distributed-fulfillment/k6"
if [[ "$output_dir" != /* ]]; then output_dir="$repo_root/$output_dir"; fi
mkdir -p "$output_dir"
summary_file="$output_dir/coordinator-${RUN_ID}-summary.json"

k6_docker_args=(--rm)
if [[ -n "${K6_CPUS:-}" ]]; then k6_docker_args+=(--cpus "$K6_CPUS"); fi
if [[ -n "${K6_MEMORY:-}" ]]; then k6_docker_args+=(--memory "$K6_MEMORY"); fi
printf 'k6_container_cpus=%s k6_container_memory=%s\n' \
  "${K6_CPUS:-unlimited}" "${K6_MEMORY:-unlimited}"
printf 'idempotent_transport_retries=%s\n' "$TRANSPORT_RETRIES"
set +e
docker run "${k6_docker_args[@]}" \
  --add-host host.docker.internal:host-gateway \
  -e COORDINATOR_URL \
  -e ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN \
  -e INVENTORY_LOCATION_SERVICE_TOKEN \
  -e RUN_ID \
  -e RATE \
  -e DURATION \
  -e LOCATIONS \
  -e MAX_VUS \
  -e TRANSPORT_RETRIES \
  -v "$repo_root/load-tests/distributed-coordinator.js:/scenario.js:ro" \
  -v "$output_dir:/results" \
  "$k6_image" run --summary-export "/results/coordinator-${RUN_ID}-summary.json" /scenario.js
k6_status=$?
set -e

printf 'run_id=%s\nsummary=%s\nk6_exit_code=%s\n' "$RUN_ID" "$summary_file" "$k6_status"
runner_status=$k6_status
actual_iterations=0
dropped_iterations=0
if [[ -f "$summary_file" ]]; then
  actual_iterations="$(jq -r '.metrics.iterations.count // 0' "$summary_file")"
  dropped_iterations="$(jq -r '.metrics.dropped_iterations.count // 0' "$summary_file")"
  status_counts="$(jq -c '[.metrics | to_entries[] | select(.key | startswith("coordinator_http_status_")) | select((.value.count // 0) > 0) | {metric: .key, count: (.value.count // 0)}]' "$summary_file")"
  printf 'http_status_counts=%s\n' "$status_counts"
else
  runner_status=1
  printf 'k6_summary=missing\n'
fi
if [[ "$dropped_iterations" != 0 ]]; then runner_status=1; fi

if [[ -n "${DB_CONTAINER:-}" ]]; then
  db_user=atlas
  db_name=atlas_order
  settle_timeout=60
  pending_count=''
  for _ in $(seq 1 "$settle_timeout"); do
    pending_count="$(docker exec "$DB_CONTAINER" psql -X -U "$db_user" -d "$db_name" -Atc \
      "SELECT count(*) FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '${RUN_ID}-%' AND workflow_state IN ('PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED', 'CANCEL_PENDING')")"
    [[ "$pending_count" == 0 ]] && break
    sleep 1
  done
  settled_counts="$(docker exec "$DB_CONTAINER" psql -X -U "$db_user" -d "$db_name" -Atc \
    "SELECT count(*), count(*) FILTER (WHERE workflow_state = 'RESERVED'), count(*) FILTER (WHERE workflow_state IN ('PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED', 'CANCEL_PENDING')) FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '${RUN_ID}-%'")"
  IFS='|' read -r workflow_count reserved_count pending_count <<< "$settled_counts"
  states="$(docker exec "$DB_CONTAINER" psql -X -U "$db_user" -d "$db_name" -Atc \
    "SELECT workflow_state || ':' || count(*) FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '${RUN_ID}-%' GROUP BY workflow_state ORDER BY workflow_state")"
  printf 'target_workflows=%s actual_iterations=%s dropped_iterations=%s actual_workflows=%s reserved=%s nonterminal=%s\n' \
    "$expected_orders" "$actual_iterations" "$dropped_iterations" "${workflow_count:-0}" \
    "${reserved_count:-0}" "${pending_count:-unknown}"
  printf '%s\n' "$states"
  if [[ "$pending_count" != 0 || "$workflow_count" != "$actual_iterations" \
      || "$reserved_count" != "$workflow_count" ]]; then
    runner_status=1
  fi
else
  printf 'settlement_check=SKIPPED (set DB_CONTAINER to verify workflow convergence)\n'
fi

exit "$runner_status"
