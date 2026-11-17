#!/usr/bin/env bash
set -uo pipefail

compose() {
    docker compose --profile observability --profile loadtest "$@"
}

mode="${LOAD_PROBE_MODE:-readiness}"
case "$mode" in
    readiness)
        scenarios=('278 1')
        duration='120s'
        session_rate_time_unit='100s'
        ;;
    smoke)
        scenarios=('1 1')
        duration='5s'
        session_rate_time_unit='1s'
        ;;
    matrix)
        scenarios=('278 1' '1112 1' '4448 4')
        duration='120s'
        session_rate_time_unit='100s'
        ;;
    *) echo "Unsupported LOAD_PROBE_MODE=${mode}" >&2; exit 2 ;;
esac

results_dir="ATLAS-application/build/k6-results"
api_url="http://127.0.0.1:18080"
prometheus_url="http://127.0.0.1:9090"
mkdir -p "$results_dir"
failures=0

for spec in "${scenarios[@]}"; do
    read -r rate fixed_vus <<< "$spec"
    run_id="exp033-r${rate}"
    summary="/results/${run_id}.json"
    readiness_file="${results_dir}/${run_id}-startup.txt"
    response_file="${results_dir}/${run_id}-readiness.body"
    curl_error_file="${results_dir}/${run_id}-readiness.curl-error"
    echo "Starting fresh 4-CPU probe mode=${mode} run=${run_id} rate=${rate}/${session_rate_time_unit} fixed_vus=${fixed_vus} duration=${duration}"

    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    if ! compose up -d application postgres-exporter prometheus; then
        echo "Failed to start Compose stack for ${run_id}" >&2
        failures=1
        compose ps --all > "$readiness_file" 2>&1 || true
        compose logs --tail=200 --no-color application >> "$readiness_file" 2>&1 || true
        break
    fi

    readiness_status="transport-error"
    for _ in $(seq 1 60); do
        readiness_status=$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
            "${api_url}/api/products?limit=1" 2>"$curl_error_file") || readiness_status="transport-error"
        if [[ "$readiness_status" == "200" ]]; then
            break
        fi
        sleep 2
    done
    if [[ "$readiness_status" != "200" ]]; then
        {
            printf 'mode=%s\nrun_id=%s\nreadiness_status=%s\n' "$mode" "$run_id" "$readiness_status"
            echo '--- curl error ---'
            cat "$curl_error_file" 2>/dev/null || true
            echo '--- HTTP response body ---'
            cat "$response_file" 2>/dev/null || true
            echo '--- compose state ---'
            compose ps --all 2>&1 || true
            echo '--- application startup logs (last 200 lines) ---'
            compose logs --tail=200 --no-color application 2>&1 || true
        } > "$readiness_file"
        echo "Application readiness failed for ${run_id}; diagnostic artifact saved" >&2
        failures=1
        break
    fi

    prometheus_ready=1
    if ! curl --retry 30 --retry-delay 2 --retry-connrefused --retry-all-errors \
            --fail --silent --show-error --output /dev/null "${prometheus_url}/-/ready"; then
        echo "Prometheus did not become ready for ${run_id}; k6 result will still be collected" >&2
        prometheus_ready=0
        failures=1
    fi

    if [[ "$mode" == "readiness" ]]; then
        {
            printf 'mode=%s\nrun_id=%s\nreadiness_status=%s\nprometheus_ready=%s\n' \
                "$mode" "$run_id" "$readiness_status" "$prometheus_ready"
            compose ps --all 2>&1 || true
            compose logs --tail=80 --no-color application 2>&1 || true
        } > "$readiness_file"
        echo "Readiness succeeded for ${run_id}; no k6 load was generated."
        continue
    fi

    if ! compose run --rm k6 run \
            -e BASE_URL=http://application:8080 \
            -e PROFILE=one-seller \
            -e RUN_ID="${run_id}" \
            -e UNIT_PRICE_WON=100 \
            -e DURATION="${duration}" \
            -e SESSION_RATE_NUMERATOR="${rate}" \
            -e SESSION_RATE_TIME_UNIT="${session_rate_time_unit}" \
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
