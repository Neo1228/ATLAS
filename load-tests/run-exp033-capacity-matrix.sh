#!/usr/bin/env bash
set -uo pipefail

# LOAD_COMPOSE_FILES lets a run use the constrained node profile and/or the
# distributed profile without editing this script, e.g.
#   LOAD_COMPOSE_FILES="compose.yaml compose.capacity-node.yaml"
compose() {
    # shellcheck disable=SC2086
    docker compose ${LOAD_COMPOSE_FILES:+-f ${LOAD_COMPOSE_FILES// / -f }} \
        --profile observability --profile loadtest "$@"
}

# Fixed VUs use observed session p99 × arrival rate × 1.5 headroom from EXP-033 run 44155788679.
# LOAD_WORKLOAD=reads drives the seeded catalog read path instead of checkout sessions.
workload="${LOAD_WORKLOAD:-checkout}"
mode="${LOAD_PROBE_MODE:-readiness}"
read -r seed_sellers seed_products_per_seller <<< "${LOAD_SEED_SCOPE:-200 500}"
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
        if [[ -n "${LOAD_RATES:-}" ]]; then
            # "5 4;20 16" style override so a single arrival rate can be re-run in
            # isolation instead of always paying for the whole matrix.
            IFS=';' read -r -a scenarios <<< "$LOAD_RATES"
            session_rate_time_unit='1s'
        elif [[ "$workload" == "fulldomain" ]]; then
            # One iteration walks 9 steps; payment and fulfilment only run for the
            # minority of sessions that convert, so VUs need headroom for the tail.
            scenarios=('20 24' '40 48')
            session_rate_time_unit='1s'
        elif [[ "$workload" == "journey" ]]; then
            # One journey iteration is ~7 requests (4 reads, 1 resumed cart read, and
            # occasionally a cart write + read-after-write + checkout).
            scenarios=('5 6' '10 12' '20 24')
            session_rate_time_unit='1s'
        elif [[ "$workload" == "reads" ]]; then
            # One read iteration is six GETs (first page, deep page, price filter,
            # needle search, broad search, detail) => 30/60/120 HTTP req/s offered.
            scenarios=('5 4' '10 8' '20 16')
            session_rate_time_unit='1s'
        else
            scenarios=('278 1' '1112 2' '4448 6')
            session_rate_time_unit='100s'
        fi
        duration='120s'
        ;;
    *)
        echo "Unsupported LOAD_PROBE_MODE=${mode}" >&2
        exit 2
        ;;
esac

case "$workload" in
    checkout)
        workload_script='buyer-checkout.js'
        workload_k6_args=(-e PROFILE=one-seller -e PAYMENT_MODE=checkout-only -e UNIT_PRICE_WON=100)
        ;;
    reads)
        workload_script='catalog-read-capacity.js'
        workload_k6_args=()
        ;;
    fulldomain)
        # Whole slice: browse -> cart -> checkout -> payment -> shipment -> statement.
        workload_script='full-domain-capacity.js'
        workload_k6_args=(-e SEEDED_SELLER_IDS="${SEEDED_SELLER_IDS:-}")
        if [[ -n "${HOT_SELLER_SHARE:-}" ]]; then
            workload_k6_args+=(-e HOT_SELLER_SHARE="${HOT_SELLER_SHARE}")
        fi
        ;;
    journey)
        # Mixed read/write buyer journey: reads dominate, but cart writes and the
        # read-after-write check exercise the paths a pure read loop never touches.
        workload_script='buyer-journey-capacity.js'
        workload_k6_args=(-e SEEDED_SELLER_IDS="${SEEDED_SELLER_IDS:-}")
        if [[ -n "${HOT_SELLER_SHARE:-}" ]]; then
            workload_k6_args+=(-e HOT_SELLER_SHARE="${HOT_SELLER_SHARE}")
        fi
        ;;
    *)
        echo "Unsupported LOAD_WORKLOAD=${workload}" >&2
        exit 2
        ;;
esac

results_dir="atlas-application/build/k6-results"
api_url="http://127.0.0.1:18080"
prometheus_url="http://127.0.0.1:9090"
grafana_url="http://127.0.0.1:3000"
mkdir -p "$results_dir"
failures=0

for spec in "${scenarios[@]}"; do
    read -r rate fixed_vus <<< "$spec"
    # LOAD_RUN_PREFIX keeps phases apart: without it a second phase silently
    # overwrites the first phase's summaries, which is not recoverable afterwards.
    run_id="${LOAD_RUN_PREFIX:-exp033}-${workload}-r${rate}"
    summary="/results/${run_id}.json"
    readiness_file="${results_dir}/${run_id}-startup.txt"
    response_file="${results_dir}/${run_id}-readiness.body"
    curl_error_file="${results_dir}/${run_id}-readiness.curl-error"
    echo "Starting fresh 4-CPU probe mode=${mode} run=${run_id} rate=${rate}/${session_rate_time_unit} fixed_vus=${fixed_vus} duration=${duration}"

    # A large dataset is rebuilt once and reused across rates. Removing volumes here
    # would wipe it before every scenario, which silently produced an empty-catalog
    # measurement, so LOAD_KEEP_DATA=1 downgrades this to a plain `down`.
    if [[ "${LOAD_KEEP_DATA:-0}" == "1" ]]; then
        compose down --remove-orphans >/dev/null 2>&1 || true
    else
        compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
    # Distributed runs must not start the base single-node service: it would add
    # an unmeasured instance and take the published port from app-a.
    read -r -a stack_services <<< "${LOAD_STACK_SERVICES:-application postgres-exporter prometheus}"
    if [[ "${GRAFANA_CAPTURE:-0}" == "1" ]]; then
        stack_services+=(grafana-image-renderer grafana)
    fi
    if ! compose up -d "${stack_services[@]}"; then
        echo "Failed to start Compose stack for ${run_id}" >&2
        failures=1
        compose ps --all > "$readiness_file" 2>&1 || true
        compose logs --tail=200 --no-color application >> "$readiness_file" 2>&1 || true
        break
    fi

    readiness_status="transport-error"
    for _ in $(seq 1 60); do
        readiness_status=$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
            "${LOAD_READINESS_URL:-${api_url}/api/products?limit=1}" 2>"$curl_error_file") || readiness_status="transport-error"
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

    if [[ "${GRAFANA_CAPTURE:-0}" == "1" ]]; then
        if ! curl --retry 30 --retry-delay 2 --retry-connrefused --retry-all-errors \
                --fail --silent --show-error --output /dev/null "${grafana_url}/api/health"; then
            echo "Grafana did not become ready for ${run_id}" >&2
            failures=1
        fi
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

    # LOAD_SKIP_SEED=1 is for datasets that are too large to rebuild per scenario
    # (e.g. the 2M-row EXP-037 catalog). The guard below still fails the run if the
    # catalog is smaller than requested, so a skipped seed cannot go unnoticed.
    if [[ "$workload" == "reads" && "${LOAD_SKIP_SEED:-0}" != "1" ]]; then
        # Seed once per freshly created stack. Running this inside the per-rate loop
        # put ~700k inserts inside the first rate's metric window, which made that
        # row's buffer counters incomparable with the later rows.
        if ! compose exec -T postgres psql -U atlas -d atlas \
                -v sellers="$seed_sellers" -v products_per_seller="$seed_products_per_seller" \
                -f - < load-tests/seed-catalog-read.sql > "${results_dir}/${run_id}-seed.log" 2>&1; then
            echo "Catalog seed failed for ${run_id}; see ${run_id}-seed.log" >&2
            failures=1
        fi
    fi

    # Sample container CPU/memory beside the load so a latency result can be tied to
    # an actual resource ceiling. 5s cadence, no new service, stops with the run.
    resource_file="${results_dir}/${run_id}-container-resources.csv"
    printf 'timestamp,container,cpu_percent,mem_usage,mem_percent\n' > "$resource_file"
    (
        # An unavailable container must be visible as an explicit row, not as a
        # silently missing sample: an empty CSV previously looked like "no load".
        while sleep 5; do
            for container in ${LOAD_SAMPLED_CONTAINERS:-atlas-application-1 atlas-postgres-1}; do
                if ! sample=$(docker stats --no-stream --format \
                        "{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}" "$container" 2>&1); then
                    printf '%s,%s,UNAVAILABLE,NA,NA\n' \
                        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$container" >> "$resource_file"
                    continue
                fi
                printf '%s\n' "$sample" \
                    | awk -v ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)" -F, \
                        'NF==4 {print ts "," $1 "," $2 "," $3 "," $4}' >> "$resource_file"
            done
        done
    ) &
    sampler_pid=$!

    if [[ "$workload" == "reads" ]]; then
        product_rows=$(compose exec -T postgres psql -U atlas -d atlas -tAc \
            "SELECT count(*) FROM product" 2>/dev/null | tr -d '[:space:]')
        expected_min=$(( seed_sellers * seed_products_per_seller ))
        if [[ "${LOAD_SKIP_SEED:-0}" == "1" ]]; then
            # Reused dataset: trust the actual row count, not the requested scope.
            printf 'reused_dataset product_rows=%s\n' "${product_rows:-0}" \
                >> "${results_dir}/${run_id}-seed.log"
        elif [[ "${product_rows:-0}" -lt "$expected_min" ]]; then
            echo "Read workload needs a seeded catalog: found ${product_rows:-0} products (want ${expected_min}) for ${run_id}" >&2
            failures=1
        fi
    fi

    # A 200 response with zero items still looks healthy but measures nothing.
    # This exact failure produced a meaningless "fast" result against an empty clone.
    listing_items=$(curl --silent --show-error \
        "${LOAD_READINESS_URL:-${api_url}/api/products?limit=1}" 2>/dev/null \
        | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items") or []))' 2>/dev/null || echo 0)
    if [[ "${listing_items:-0}" -lt 1 ]]; then
        echo "Catalog listing returned no items for ${run_id}; measurement would be meaningless" >&2
        failures=1
    fi
    printf 'listing_items=%s\n' "${listing_items:-0}" >> "${results_dir}/${run_id}-seed.log"

    window_start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if ! compose run --rm k6 run \
            -e BASE_URL="${LOAD_TARGET_URL:-http://application:8080}" \
            -e RUN_ID="${run_id}" \
            -e DURATION="${duration}" \
            -e SESSION_RATE_NUMERATOR="${rate}" \
            -e SESSION_RATE_TIME_UNIT="${session_rate_time_unit}" \
            -e FIXED_VUS="${fixed_vus}" \
            -e SUMMARY_FILE="${summary}" \
            ${workload_k6_args[@]+"${workload_k6_args[@]}"} \
            "/scripts/${workload_script}"; then
        echo "k6 run failed or crossed its thresholds for ${run_id}; preserving available reports" >&2
        failures=1
    fi

    window_end="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
    printf 'window_start=%s\nwindow_end=%s\nworkload=%s\noffered_rate=%s/%s\nfixed_vus=%s\n' \
        "$window_start" "$window_end" "$workload" "$rate" "$session_rate_time_unit" "$fixed_vus" \
        > "${results_dir}/${run_id}-window.txt"

    if [[ "${GRAFANA_CAPTURE:-0}" == "1" ]]; then
        # rate()/histogram_quantile() need at least two scrapes (5s each) to return a
        # sample, so let the last scrape land and widen the window by a baseline
        # minute. Without this a short run renders "No data" and proves nothing.
        sleep 12
        capture_from="$(date -u -d "$window_start - 60 seconds" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
            || date -u -v-60S -j -f '%Y-%m-%dT%H:%M:%SZ' "$window_start" +%Y-%m-%dT%H:%M:%SZ)"
        capture_to="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'capture_from=%s\ncapture_to=%s\n' "$capture_from" "$capture_to" \
            >> "${results_dir}/${run_id}-window.txt"
        # One PNG per panel with the load window, plus a manifest of sizes and hashes
        # so a capture can be tied to this run.
        if ! node scripts/capture-grafana-panels.mjs \
                --run-id "$run_id" --from "$capture_from" --to "$capture_to" \
                --out "${results_dir}/grafana-${run_id}"; then
            echo "Grafana panel capture failed for ${run_id}" >&2
            failures=1
        fi
    fi

    if [[ ! -s "${results_dir}/${run_id}.json" ]]; then
        echo "k6 summary file is missing or empty for ${run_id}" >&2
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
        'application_up|up{job="atlas-application"}'
        # datname="atlas" excludes the template1/postgres databases, which otherwise
        # inflate the connection and block counters by roughly 4x on a fresh stack.
        'postgres_connections|sum(pg_stat_database_numbackends{datname="atlas"})'
        'postgres_max_connections|max(pg_settings_max_connections)'
        'postgres_transactions_committed|increase(pg_stat_database_xact_commit{datname="atlas"}[2m])'
        'postgres_connections_active|sum(pg_stat_activity_count{datname="atlas",state="active"})'
        'postgres_buffers_clean|increase(pg_stat_bgwriter_buffers_clean_total[2m])'
        'postgres_transactions_rolled_back|increase(pg_stat_database_xact_rollback{datname="atlas"}[2m])'
        'postgres_blocks_read|increase(pg_stat_database_blks_read{datname="atlas"}[2m])'
        'postgres_blocks_hit|increase(pg_stat_database_blks_hit{datname="atlas"}[2m])'
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
    curl --fail --silent --show-error --get "${prometheus_url}/api/v1/targets" \
        --data-urlencode 'state=active' \
        --output "${results_dir}/${run_id}-prometheus-targets.json" || failures=1
    if ! jq -e '.data.result | any(.value[1] == "1")' \
            "${results_dir}/${run_id}-application_up.json" >/dev/null; then
        echo "Prometheus did not report the application target as up for ${run_id}" >&2
        failures=1
    fi

done

if [[ "${LOAD_KEEP_STACK:-0}" != "1" ]]; then
    if [[ "${LOAD_KEEP_DATA:-0}" == "1" ]]; then
        compose down --remove-orphans >/dev/null 2>&1 || true
    else
        compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
fi
exit "$failures"
