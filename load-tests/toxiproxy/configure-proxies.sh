#!/bin/bash
# 격리된 로컬 Compose Toxiproxy만 설정한다. 운영이나 GitHub Actions endpoint는 대상이 아니다.
set -euo pipefail
api=${TOXI_API_URL:-http://127.0.0.1:19475}
config_dir=$(cd "$(dirname "$0")" && pwd)
python3 - "$api" "$config_dir" <<'PY'
import json
import sys
import urllib.error
import urllib.request

base, directory = sys.argv[1:]
for filename in ("paypal-proxy.json", "paypal-capture-proxy.json", "postgres-proxy.json", "smtp-proxy.json"):
    expected = json.load(open(f"{directory}/{filename}", encoding="utf-8"))
    url = f"{base.rstrip('/')}/proxies/{expected['name']}"
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            actual = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        request = urllib.request.Request(
            f"{base.rstrip('/')}/proxies",
            data=json.dumps(expected).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=3) as response:
            actual = json.load(response)
    for key in ("name", "upstream", "enabled"):
        if actual.get(key) != expected.get(key):
            raise SystemExit(f"Toxiproxy {expected['name']}의 {key} 설정이 예상과 다르다. 격리된 profile을 재시작하라.")
    expected_port = expected["listen"].rsplit(":", 1)[1]
    actual_host, actual_port = actual["listen"].rsplit(":", 1)
    if actual_port != expected_port or actual_host not in ("0.0.0.0", "[::]"):
        raise SystemExit(f"Toxiproxy {expected['name']} listener가 예상과 다르다. 격리된 profile을 재시작하라.")
    print(f"Toxiproxy proxy 준비 완료: {expected['name']}")
PY
