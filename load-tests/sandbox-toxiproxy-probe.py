#!/usr/bin/env python3
"""Real Sandbox OAuth over a verified TLS tunnel, not an application load test.

The proxy forwards encrypted traffic; TLS SNI, Host and certificate verification
all use PayPal's actual Sandbox hostname. No orders or captures are created.
"""

import base64
import http.client
import json
import os
import socket
import ssl
import sys
import time
from pathlib import Path

HOST = "api-m.sandbox.paypal.com"
API_PORT = 18474
PROXY_PORT = 18666
OUTPUT = Path("sandbox-toxiproxy-results.json")
REPETITIONS = 3


def control(method, path, payload=None):
    connection = http.client.HTTPConnection("127.0.0.1", API_PORT, timeout=5)
    try:
        body = json.dumps(payload) if payload is not None else None
        connection.request(method, path, body, {"Content-Type": "application/json"})
        response = connection.getresponse()
        response.read()
        if not 200 <= response.status < 300:
            raise RuntimeError("Toxiproxy control request failed")
    finally:
        connection.close()


class SandboxConnection(http.client.HTTPSConnection):
    def connect(self):
        # HTTPSConnection uses self.host for TLS SNI and certificate validation.
        raw = socket.create_connection(("127.0.0.1", PROXY_PORT), self.timeout)
        try:
            self.sock = self._context.wrap_socket(raw, server_hostname=self.host)
        except BaseException:
            raw.close()
            raise


def trial(authorization):
    connection = SandboxConnection(HOST, timeout=12, context=ssl.create_default_context())
    start = time.monotonic()
    status = None
    exception = None
    try:
        connection.request(
            "POST", "/v1/oauth2/token", "grant_type=client_credentials",
            {"Host": HOST, "Authorization": authorization,
             "Content-Type": "application/x-www-form-urlencoded"},
        )
        response = connection.getresponse()
        status = response.status
        # Never retain, parse, or print the OAuth response containing an access token.
        response.close()
    except Exception as error:
        exception = type(error).__name__
    finally:
        connection.close()
    return {"http_status": status, "latency_ms": round((time.monotonic() - start) * 1000, 1),
            "exception_class": exception}


def main():
    client_id = os.environ.get("PAYPAL_CLIENT_ID")
    client_secret = os.environ.get("PAYPAL_CLIENT_SECRET")
    if not client_id or not client_secret:
        print("PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET are required", file=sys.stderr)
        return 1
    credentials = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode("ascii")
    authorization = "Basic " + credentials
    report = {
        "target": HOST,
        "operation": "POST /v1/oauth2/token",
        "repetitions_per_condition": REPETITIONS,
        "limitations": "Direct Python OAuth probe, not the app/JVM or a mixed workload; no order or capture.",
        "trials": [],
    }
    failed = False
    try:
        for attempt in range(30):
            try:
                control("GET", "/version")
                break
            except (OSError, http.client.HTTPException):
                if attempt == 29:
                    raise
                time.sleep(0.5)
        control("POST", "/proxies", {
            "name": "paypal", "listen": "0.0.0.0:8666", "upstream": HOST + ":443",
        })
        conditions = (
            ("baseline", None),
            ("latency_500ms", {"name": "experiment", "type": "latency", "stream": "downstream",
                               "attributes": {"latency": 500, "jitter": 0}}),
            ("reset_peer", {"name": "experiment", "type": "reset_peer", "stream": "downstream",
                            "attributes": {"timeout": 0}}),
            ("recovered", None),
        )
        for label, toxic in conditions:
            try:
                if toxic:
                    control("POST", "/proxies/paypal/toxics", toxic)
                for repetition in range(1, REPETITIONS + 1):
                    result = {"condition": label, "repetition": repetition, **trial(authorization)}
                    report["trials"].append(result)
                    print(json.dumps(result), flush=True)
            finally:
                if toxic:
                    control("DELETE", "/proxies/paypal/toxics/experiment")
        normal = [r for r in report["trials"] if r["condition"] != "reset_peer"]
        resets = [r for r in report["trials"] if r["condition"] == "reset_peer"]
        failed = (any(r["http_status"] != 200 for r in normal)
                  or not any(r["exception_class"] for r in resets))
    except Exception as error:
        report["experiment_error_class"] = type(error).__name__
        print("Experiment error class: " + type(error).__name__, file=sys.stderr)
        failed = True
    finally:
        report["result"] = "failed" if failed else "completed"
        OUTPUT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
