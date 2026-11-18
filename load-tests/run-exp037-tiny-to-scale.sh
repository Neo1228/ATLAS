#!/usr/bin/env bash
# EXP-037: start from a deliberately tiny node, find the first wall, then resolve it
# one measured step at a time.
#
# The point is not to show a big number. It is to show *which* limit appears first,
# *why* the fix targets that limit, and how far the fix moves the ceiling. Each phase
# reuses the same dataset and the same offered rates so the comparison is honest.
#
# Usage:
#   ATLAS_JWT_SIGNING_SECRET=... bash load-tests/run-exp037-tiny-to-scale.sh tiny
#   ... scale-up      # 2 vCPU app
#   ... pooler        # tiny app + PgBouncer
#   ... scale-out     # 3 app replicas behind nginx
#
# Dataset is built once with `seed`; later phases pass LOAD_SKIP_SEED=1 so a 2M-row
# rebuild never lands inside a measurement window.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

phase="${1:-tiny}"
results="ATLAS-application/build/k6-results"
mkdir -p "$results"

if [[ -z "${ATLAS_JWT_SIGNING_SECRET:-}" ]]; then
    echo "ATLAS_JWT_SIGNING_SECRET is required" >&2
    exit 2
fi

common_env() {
    export LOAD_WORKLOAD="${LOAD_WORKLOAD:-journey}"
    export LOAD_PROBE_MODE="${LOAD_PROBE_MODE:-matrix}"
    export LOAD_RATES="${LOAD_RATES:-5 6;10 12;20 24}"
    export GRAFANA_CAPTURE="${GRAFANA_CAPTURE:-1}"
    export LOAD_KEEP_STACK=1
    export LOAD_SEED_SCOPE="${LOAD_SEED_SCOPE:-2000 1000}"
    export LOAD_SKIP_SEED=1
    export LOAD_KEEP_DATA=1
    export SEEDED_SELLER_IDS="${SEEDED_SELLER_IDS:-}"
    export HOT_SELLER_SHARE="${HOT_SELLER_SHARE:-0}"
}

seed_dataset() {
    echo "=== EXP-037: building dataset (2000 sellers x 1000 products) ==="
    docker compose -f compose.yaml -f compose.capacity-node.yaml \
        --profile observability --profile loadtest up -d postgres
    # Wait for PostgreSQL, then let the application run migrations once.
    docker compose -f compose.yaml -f compose.capacity-node.yaml \
        --profile observability --profile loadtest up -d application
    for _ in $(seq 1 90); do
        if docker compose -f compose.yaml -f compose.capacity-node.yaml exec -T postgres \
                psql -U atlas -d atlas -tAc "select count(*) from product" >/dev/null 2>&1; then
            break
        fi
        sleep 2
    done
    docker compose -f compose.yaml -f compose.capacity-node.yaml exec -T postgres \
        psql -U atlas -d atlas -v sellers=2000 -v products_per_seller=1000 -v batch=200 \
        -f - < load-tests/seed-catalog-scale.sql
}

run_with() {
    local label="$1" files="$2" target="$3" sampled="$4" readiness="$5" services="$6"
    echo "=== EXP-037 phase: ${label} ==="
    LOAD_RUN_PREFIX="exp037${label}" \
    LOAD_COMPOSE_FILES="${files}" \
    LOAD_STACK_SERVICES="${services}" \
    LOAD_TARGET_URL="${target}" \
    LOAD_SAMPLED_CONTAINERS="${sampled}" \
    LOAD_READINESS_URL="${readiness}" \
        bash load-tests/run-exp033-capacity-matrix.sh
}

common_env

case "$phase" in
    seed)
        seed_dataset
        ;;
    tiny)
        run_with "tiny" "compose.yaml compose.capacity-tiny.yaml" \
            "http://application:8080" "atlas-application-1 atlas-postgres-1" \
            "http://127.0.0.1:18080/api/products?limit=1" \
            "application postgres-exporter prometheus grafana grafana-image-renderer"
        ;;
    scale-up)
        # Same tiny DB, more app CPU: isolates the application-side wall from the DB.
        run_with "scaleup" "compose.yaml compose.capacity-node.yaml" \
            "http://application:8080" "atlas-application-1 atlas-postgres-1" \
            "http://127.0.0.1:18080/api/products?limit=1" \
            "application postgres-exporter prometheus grafana grafana-image-renderer"
        ;;
    pooler)
        run_with "pooler" "compose.yaml compose.capacity-tiny.yaml compose.capacity-pooler.yaml" \
            "http://application:8080" "atlas-application-1 atlas-postgres-1 atlas-pgbouncer-1" \
            "http://127.0.0.1:18080/api/products?limit=1" \
            "application postgres-exporter prometheus grafana grafana-image-renderer pgbouncer"
        ;;
    scale-out)
        run_with "scaleout" "compose.yaml compose.capacity-node.yaml compose.capacity-distributed.yaml" \
            "http://app-lb:8080" "atlas-app-a atlas-app-b atlas-app-c atlas-postgres-1" \
            "http://127.0.0.1:18081/api/products?limit=1" \
            "app-a app-b app-c app-lb postgres-exporter prometheus grafana grafana-image-renderer"
        ;;
    *)
        echo "Unknown phase: $phase (seed|tiny|scale-up|pooler|scale-out)" >&2
        exit 2
        ;;
esac
