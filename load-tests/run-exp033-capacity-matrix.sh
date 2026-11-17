#!/usr/bin/env bash
set -uo pipefail

compose() {
    docker compose --profile observability --profile loadtest "$@"
}

results_dir="ATLAS-application/build/k6-results"
api_url="http://127.0.0.1:18080"
prometheus_url="http://127.0.0.1:9090"
mkdir -p "$results_dir"
failures=0

# Sessions/s: 2.78 baseline, 11.12 (4x), 44.48 (16x). Fixed VUs are
# calibration-derived from EXP-016 one-seller p95 (61.2 ms), with headroom.
for spec in '278 1' '1112 1' '4448 4'; do
    read -r rate fixed_vus <<< "$spec"
    run_id="exp033-r${rate}"
    summary="/results/${run_id}.json"
    echo "Starting fresh 4-CPU workload run=${run_id} sessions_per_second=$(awk "BEGIN { printf \"%.2f\", $rate / 100 }") fixed_vus=${fixed_vus}"

    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    if ! compose up -d application postgres-exporter prometheus; then
        echo "Failed to start Compose stack for ${run_id}" >&2
        failures=1
        continue
    fi
    if ! curl --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
            --fail --silent --show-error --output /dev/null "${api_url}/api/products?limit=1"; then
        echo "Application did not become ready for ${run_id}" >&2
        failures=1
        continue
    fi
    if ! curl --retry 30 --retry-delay 2 --retry-connrefused --retry-all-errors \
            --fail --silent --show-error --output /dev/null "${prometheus_url}/-/ready"; then
        echo "Prometheus did not become ready for ${run_id}" >&2
        failures=1
        continue
    fi

    if ! compose run --rm k6 run \
            -e BASE_URL=http://application:8080 \
            -e PROFILE=one-seller \
            -e RUN_ID="${run_id}" \
            -e UNIT_PRICE_WON=100 \
            -e DURATION=120s \
            -e SESSION_RATE_NUMERATOR="${rate}" \
            -e SESSION_RATE_TIME_UNIT=100s \
            -e FIXED_VUS="${fixed_vus}" \
            -e SUMMARY_FILE="${summary}" \
            /scripts/buyer-checkout.js; then
        echo "k6 run failed or crossed its thresholds for ${run_id}; preserving available reports" >&2
        failures=1
    fi

    # Wait for a Prometheus scrape after the load phase, then retain raw query results.
    sleep 6
    queries=(
        'hikaricp_active|max_over_time(hikaricp_connections_active[3m])'
        'hikaricp_pending|max_over_time(hikaricp_connections_pending[3m])'
        'hikaricp_max|max_over_time(hikaricp_connections_max[3m])'
        'hikaricp_timeouts|increase(hikaricp_connections_timeout_total[3m])'
        'http_request_p95|histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[2m])))'
        'expiry_duration_max|max_over_time(atlas_expiry_sweep_duration_seconds_max[3m])'
        'expiry_runs|increase(atlas_expiry_sweep_runs_total[3m])'
        'expiry_changed|increase(atlas_expiry_sweep_changed_total[3m])'
    )
    for entry in "${queries[@]}"; do
        metric_name="${entry%%|*}"
        query="${entry#*|}"
        if ! curl --fail --silent --show-error --get "${prometheus_url}/api/v1/query" \
                --data-urlencode "query=${query}" \
                --output "${results_dir}/${run_id}-${metric_name}.json"; then
            echo "Prometheus query failed for ${run_id}/${metric_name}" >&2
            failures=1
        fi
    done

done

compose down --volumes --remove-orphans >/dev/null 2>&1 || true
exit "$failures"
