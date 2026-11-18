#!/usr/bin/env bash
# EXP-035: single-node limit -> horizontal scaling comparison.
#
# Phase 1 runs the read matrix against one constrained application container.
# Phase 2 keeps the same per-container limits and the same PostgreSQL, adds two
# more replicas behind nginx, and repeats the identical arrival-rate matrix.
# The difference between phases is therefore attributable to replica count only.
#
# Usage:
#   ATLAS_JWT_SIGNING_SECRET=... \\
#   LOAD_SEED_SCOPE="200 500" \\
#   bash load-tests/run-exp035-single-to-distributed.sh
#
# Optional:
#   PHASE=1|2|both        run only one phase (default both)
#   GRAFANA_CAPTURE=1     capture one PNG per dashboard panel per scenario
#   LOAD_PROBE_MODE=smoke use a 5s smoke instead of the 120s matrix
#   LOAD_SEED_SCOPE="N M" sellers and products per seller

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

phase="${PHASE:-both}"
probe_mode="${LOAD_PROBE_MODE:-matrix}"
capture="${GRAFANA_CAPTURE:-0}"
seed_scope="${LOAD_SEED_SCOPE:-200 500}"
results="ATLAS-application/build/k6-results"
mkdir -p "$results"

if [[ -z "${ATLAS_JWT_SIGNING_SECRET:-}" ]]; then
    echo "ATLAS_JWT_SIGNING_SECRET is required (the application refuses to start without it)" >&2
    exit 2
fi

run_phase() {
    local label="$1" compose_files="$2" target_url="$3" sampled="$4" readiness_url="$5"
    echo "=== EXP-035 phase ${label} ==="
    echo "compose:  ${compose_files}"
    echo "target:   ${target_url}"
    echo "sampled:  ${sampled}"

    LOAD_RUN_PREFIX="exp035-${label}" \
    LOAD_COMPOSE_FILES="${compose_files}" \
    LOAD_WORKLOAD=reads \
    LOAD_PROBE_MODE="${probe_mode}" \
    GRAFANA_CAPTURE="${capture}" \
    LOAD_KEEP_STACK=1 \
    LOAD_SEED_SCOPE="${seed_scope}" \
    LOAD_TARGET_URL="${target_url}" \
    LOAD_SAMPLED_CONTAINERS="${sampled}" \
    LOAD_READINESS_URL="${readiness_url}" \
        bash load-tests/run-exp033-capacity-matrix.sh
    return $?
}

status=0
if [[ "$phase" == "1" || "$phase" == "both" ]]; then
    run_phase single "compose.yaml compose.capacity-node.yaml" \
        "http://application:8080" "atlas-application-1 atlas-postgres-1" \
        "http://127.0.0.1:18080/api/products?limit=1" || status=1
fi

if [[ "$phase" == "2" || "$phase" == "both" ]]; then
    # Recreate with the distributed overlay before the second phase so Prometheus
    # scrapes per replica. The load balancer is the single entry point.
    docker compose -f compose.yaml -f compose.capacity-node.yaml -f compose.capacity-distributed.yaml \
        --profile observability --profile loadtest up -d
    LOAD_STACK_SERVICES="app-a app-b app-c app-lb postgres-exporter prometheus" \
    run_phase distributed "compose.yaml compose.capacity-node.yaml compose.capacity-distributed.yaml" \
        "http://app-lb:8080" "atlas-app-a atlas-app-b atlas-app-c atlas-postgres-1" \
        "http://127.0.0.1:18081/api/products?limit=1" || status=1
fi

exit "$status"
