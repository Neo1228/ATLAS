#!/bin/bash
# 로컬 전용 실제 PayPal Sandbox 실험. GitHub Actions나 Live 자격 증명을 사용하지 않는다.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/../.." && pwd)
harness="$root/load-tests/paypal-real-fault"
compose_file="$root/load-tests/compose.toxiproxy.yaml"
private=$(mktemp -d "${TMPDIR:-/tmp}/atlas-paypal-fault.XXXXXXXX")
chmod 700 "$private"
project="atlas-paypal-fault-$$-$RANDOM"
app_pid=''
cleanup_done=0
export ATLAS_EXP_TAG=${ATLAS_EXP_TAG:-t2}
compose=(docker compose --project-name "$project" -f "$compose_file")
if [[ -n ${COMPOSE_OVERRIDE_FILE:-} ]]; then compose+=(-f "$COMPOSE_OVERRIDE_FILE"); fi

fail() { printf '%s\n' "$1" >&2; exit 1; }

cleanup() {
    local original_status=$?
    trap - EXIT
    trap '' INT TERM
    (( cleanup_done )) && return "$original_status"
    cleanup_done=1
    local cleanup_failed=0 remaining=''
    if [[ -n $app_pid ]]; then
        kill -TERM "$app_pid" 2>/dev/null || :
        for _ in {1..20}; do
            kill -0 "$app_pid" 2>/dev/null || break
            sleep 0.25
        done
        if kill -0 "$app_pid" 2>/dev/null; then
            kill -KILL "$app_pid" 2>/dev/null || :
        fi
        wait "$app_pid" 2>/dev/null || :
    fi
    if ! "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1; then
        cleanup_failed=1
    fi
    remaining=$("${compose[@]}" ps -aq 2>/dev/null) || cleanup_failed=1
    [[ -z $remaining ]] || cleanup_failed=1
    rm -rf "$private" || cleanup_failed=1
    if (( cleanup_failed )); then
        printf '%s\n' '격리 테스트 자원 정리에 실패했다.' >&2
        (( original_status != 0 )) || original_status=1
    fi
    return "$original_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
[[ ${REAL_SANDBOX_OPT_IN:-} == YES ]] || fail '실제 Sandbox 사용 확인을 위해 REAL_SANDBOX_OPT_IN=YES 를 지정해야 한다.'
[[ -r "$root/.env.local" ]] || fail 'PayPal Sandbox 자격 증명이 든 git 제외 .env.local 이 필요하다.'
for command in docker java npm node openssl python3 nc curl; do
    command -v "$command" >/dev/null || fail "필수 실행 파일이 없다: $command"
done
if ! grep -q '^PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com$' "$root/.env.local"; then
    fail '.env.local 은 실제 Sandbox URL 이어야 한다.'
fi
export REPEAT=${REPEAT:-3}
[[ $REPEAT =~ ^[1-5]$ ]] || fail 'REPEAT 는 1-5 사이여야 한다.'
export RECOVERY_WAIT_MS=${RECOVERY_WAIT_MS:-150000}
[[ $RECOVERY_WAIT_MS =~ ^[0-9]{4,6}$ ]] || fail 'RECOVERY_WAIT_MS 는 4-6자리 정수여야 한다.'
if (( 10#$RECOVERY_WAIT_MS < 1000 || 10#$RECOVERY_WAIT_MS > 300000 )); then
    fail 'RECOVERY_WAIT_MS 는 1000-300000 ms 사이여야 한다.'
fi


export ATLAS_FAULT_APP_PORT=${ATLAS_FAULT_APP_PORT:-19980}
export ATLAS_FAULT_TOXI_API_PORT=${ATLAS_FAULT_TOXI_API_PORT:-19475}
export ATLAS_FAULT_PAYPAL_PROXY_PORT=${ATLAS_FAULT_PAYPAL_PROXY_PORT:-19443}
export ATLAS_FAULT_CAPTURE_PROXY_PORT=${ATLAS_FAULT_CAPTURE_PROXY_PORT:-19444}
export ATLAS_FAULT_PG_PROXY_PORT=${ATLAS_FAULT_PG_PROXY_PORT:-19432}
export ATLAS_FAULT_SMTP_PROXY_PORT=${ATLAS_FAULT_SMTP_PROXY_PORT:-19026}
export ATLAS_FAULT_MAIL_API_PORT=${ATLAS_FAULT_MAIL_API_PORT:-19025}
export ATLAS_FAULT_DB_PASSWORD=${ATLAS_FAULT_DB_PASSWORD:-atlas-real-local}
export ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED=${ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED:-true}
if [[ ${PAYOUT_E2E:-} == YES ]]; then
    export ATLAS_PAYPAL_PAYOUTS_ENABLED=true ATLAS_PAYPAL_PAYOUT_WORKER_ENABLED=true
    export ATLAS_PAYPAL_PAYOUT_WORKER_INITIAL_DELAY=PT1S ATLAS_PAYPAL_PAYOUT_WORKER_POLL_INTERVAL=PT1S
    export ATLAS_SETTLEMENT_PAYOUT_OPERATOR_ENABLED=true ATLAS_SETTLEMENT_PAYOUT_OPERATOR_IDENTITY=account
    export ATLAS_PAYPAL_PAYOUT_SUPPORTED_COUNTRIES=US ATLAS_PAYMENT_EXCHANGE_RATE_PROVIDER=frankfurter
    export UNIT_PRICE_WON=${UNIT_PRICE_WON:-10000}
fi
[[ $ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED == true || $ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED == false ]] \
    || fail 'ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED は true または false 여야 한다.'
export PRIVATE_DIR="$private"
if [[ ${PAYOUT_E2E:-} == YES ]]; then
    export ATLAS_PAYPAL_PAYOUT_RECIPIENT_ENCRYPTION_KEY="$(openssl rand -base64 32)"
    export ATLAS_ACCOUNT_MFA_ENCRYPTION_KEY="$(openssl rand -base64 32)"
fi
export APP_PORT="$ATLAS_FAULT_APP_PORT"
export DB_PORT="$ATLAS_FAULT_PG_PROXY_PORT"
export DB_PASSWORD="$ATLAS_FAULT_DB_PASSWORD"
export TOXI_PROXY_PORT="$ATLAS_FAULT_PAYPAL_PROXY_PORT"
export MAIL_SMTP_PORT="$ATLAS_FAULT_SMTP_PROXY_PORT"
export MAIL_API_URL="http://127.0.0.1:$ATLAS_FAULT_MAIL_API_PORT"
export TOXI_API_URL="http://127.0.0.1:$ATLAS_FAULT_TOXI_API_PORT"
export BASE_URL="http://127.0.0.1:$APP_PORT"
export ATLAS_PAYPAL_CAPTURE_BASE_URL="https://api-m.sandbox.paypal.com:$ATLAS_FAULT_CAPTURE_PROXY_PORT"

for port in "$APP_PORT" "$ATLAS_FAULT_TOXI_API_PORT" "$ATLAS_FAULT_PAYPAL_PROXY_PORT" \
        "$ATLAS_FAULT_CAPTURE_PROXY_PORT" "$ATLAS_FAULT_PG_PROXY_PORT" \
        "$ATLAS_FAULT_SMTP_PROXY_PORT" "$ATLAS_FAULT_MAIL_API_PORT"; do
    if nc -z 127.0.0.1 "$port" 2>/dev/null; then fail "포트 $port 를 다른 프로세스가 사용 중이다."; fi
done

# 임시 JWT·비밀번호와 JVM hosts 설정은 권한 600/700 파일로만 저장하고 종료 시 제거한다.
printf '127.0.0.1 api-m.sandbox.paypal.com\n' > "$private/hosts"
# jdk.net.hosts.file은 이 파일에 없는 호스트의 이름 풀이를 모두 막는다. 지급 환율 공급자(Frankfurter)는
# 실제 외부 호스트라 실행 머신의 DNS로 한 번 풀어 같은 파일에 적는다(없으면 환율 조회가 503이 된다).
if [[ ${PAYOUT_E2E:-} == YES ]]; then
    frankfurter_ip=$(python3 -c 'import socket; print(socket.gethostbyname("api.frankfurter.dev"))') \
        || fail 'api.frankfurter.dev 를 풀지 못했다.'
    printf '%s api.frankfurter.dev\n' "$frankfurter_ip" >> "$private/hosts"
fi
openssl rand -base64 48 | tr -d '\n' > "$private/jwt"
printf 'Local-%s\n' "$(openssl rand -hex 16)" > "$private/acctpw"

if [[ ! -d $harness/node_modules/playwright ]]; then
    (cd "$harness" && npm ci --no-audit --no-fund)
fi
if ! (cd "$harness" && node --input-type=module -e \
        'import { chromium } from "playwright"; import { existsSync } from "node:fs"; process.exit(existsSync(chromium.executablePath()) ? 0 : 1)'); then
    (cd "$harness" && npx playwright install chromium)
fi

gradle_args=(--no-daemon)
if [[ -n ${GRADLE_INIT_SCRIPT:-} ]]; then gradle_args+=(--init-script "$GRADLE_INIT_SCRIPT"); fi
if ! (cd "$root" && ./gradlew "${gradle_args[@]}" :atlas-application:bootJar) > "$private/build.log" 2>&1; then
    tail -40 "$private/build.log" >&2
    fail 'ATLAS atlas-application:bootJar 가 실패했다.'
fi

"${compose[@]}" up -d --wait
for _ in $(seq 1 30); do
    if curl --noproxy '*' -fsS "$TOXI_API_URL/version" >/dev/null 2>&1; then break; fi
    sleep 1
done
curl --noproxy '*' -fsS "$TOXI_API_URL/version" >/dev/null || fail 'Toxiproxy API 가 시작되지 않았다.'
bash "$root/load-tests/toxiproxy/configure-proxies.sh"
for _ in $(seq 1 30); do
    if curl --noproxy '*' -fsS "$MAIL_API_URL/api/v1/search?query=to%3Aprobe%40example.test&limit=1" >/dev/null 2>&1; then break; fi
    sleep 1
done
curl --noproxy '*' -fsS "$MAIL_API_URL/api/v1/search?query=to%3Aprobe%40example.test&limit=1" >/dev/null \
    || fail 'Mailpit API 가 시작되지 않았다.'
PG_CONTAINER=$("${compose[@]}" ps -q postgres)
export PG_CONTAINER
[[ -n $PG_CONTAINER ]] || fail '격리된 PostgreSQL 컨테이너를 찾지 못했다.'

bash "$harness/start-app.sh" > "$private/application.log" 2>&1 &
app_pid=$!
for _ in $(seq 1 120); do
    if curl --noproxy '*' -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then break; fi
    if ! kill -0 "$app_pid" 2>/dev/null; then
        tail -40 "$private/application.log" >&2
        fail 'ATLAS 시작 실패. 비공개 임시 로그는 프로세스 종료 시 제거한다.'
    fi
    sleep 1
done
curl --noproxy '*' -fsS "$BASE_URL/actuator/health" >/dev/null \
    || fail 'ATLAS 가 120초 안에 healthy 상태가 되지 않았다.'

export RESULTS_DIR=${RESULTS_DIR:-$root/load-tests/paypal-real-fault/results}
mkdir -p "$RESULTS_DIR"
export REAL_SANDBOX_OPT_IN=YES

node "$harness/real-flow.mjs"
cleanup
printf '비밀값을 포함하지 않는 실험 결과: %s\n' "$RESULTS_DIR"
