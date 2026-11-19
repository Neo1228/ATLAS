#!/usr/bin/env python3
"""Minimal PayPal sandbox stand-in for local load measurements.

Why this exists
---------------
`ATLAS_PAYMENT_PROVIDER=paypal` activates the real PayPal client, but a load test
must never depend on the public sandbox: its latency, rate limits, and availability
would dominate the measurement and make runs non-reproducible. `PayPalProperties`
already allows loopback base URLs for exactly this purpose, so this stub implements
the four calls ATLAS makes and nothing else.

Endpoints
---------
POST /v1/oauth2/token                          -> bearer token
POST /v2/checkout/orders                       -> create order
GET  /v2/checkout/orders/{id}                  -> order status
POST /v2/checkout/orders/{id}/authorize        -> authorization
POST /v2/payments/captures/{id}/refund         -> refund

Deliberate limitations
----------------------
- No signature validation and no webhook delivery: those paths are covered by the
  existing MockWebServer and webhook tests.
- Latency is added only if `PAYPAL_STUB_LATENCY_MS` is set, so the default measures
  ATLAS and not the stub.
- State is in memory; restarting the stub loses orders, which is fine because each
  measurement run starts from a fresh database.

Usage
-----
    python3 load-tests/paypal-stub.py --port 8099
"""

import argparse
import json
import os
import socket
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ORDERS = {}
LATENCY_MS = int(os.environ.get("PAYPAL_STUB_LATENCY_MS", "0"))


def _delay():
    if LATENCY_MS > 0:
        time.sleep(LATENCY_MS / 1000.0)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep load output readable
        if os.environ.get("PAYPAL_STUB_VERBOSE") == "1":
            super().log_message(fmt, *args)

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length == 0:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return {}

    def do_POST(self):
        _delay()
        path = urlparse(self.path).path
        if path == "/v1/oauth2/token":
            self._read_json()
            return self._send(200, {
                "access_token": "stub-access-token",
                "token_type": "Bearer",
                "expires_in": 3600,
            })
        if path == "/v2/checkout/orders":
            body = self._read_json()
            order_id = "STUB-" + uuid.uuid4().hex[:16].upper()
            amount = 0
            currency = "KRW"
            for unit in body.get("purchase_units", []):
                money = unit.get("amount", {})
                amount += int(float(money.get("value", 0) or 0))
                currency = money.get("currency_code", currency)
            ORDERS[order_id] = {"amount": amount, "currency": currency, "status": "CREATED",
                                "capture_id": "CAP-" + uuid.uuid4().hex[:16].upper()}
            return self._send(201, {
                "id": order_id,
                "status": "CREATED",
                "links": [
                    {"rel": "approve", "href": f"http://127.0.0.1/stub/approve/{order_id}"},
                    {"rel": "capture", "href": f"http://127.0.0.1/stub/capture/{order_id}"},
                ],
            })
        if path.startswith("/v2/checkout/orders/") and path.endswith("/authorize"):
            order_id = path.split("/")[4]
            order = ORDERS.get(order_id)
            if order is None:
                return self._send(404, {"name": "RESOURCE_NOT_FOUND"})
            order["status"] = "COMPLETED"
            return self._send(201, {
                "id": order_id,
                "status": "COMPLETED",
                "purchase_units": [{
                    "payments": {"authorizations": [{
                        "id": "AUTH-" + uuid.uuid4().hex[:16].upper(),
                        "status": "CREATED",
                        "amount": {"value": str(order["amount"]), "currency_code": order["currency"]},
                    }]},
                }],
            })
        if path.startswith("/v2/payments/captures/") and path.endswith("/refund"):
            capture_id = path.split("/")[4]
            return self._send(201, {
                "id": "REF-" + uuid.uuid4().hex[:16].upper(),
                "status": "COMPLETED",
                "capture_id": capture_id,
            })
        return self._send(404, {"name": "NOT_IMPLEMENTED", "path": path})

    def do_GET(self):
        _delay()
        path = urlparse(self.path).path
        # BOK ECOS exchange-rate stand-in: the PayPal provider requires a USD/KRW
        # snapshot before it will call PayPal, and the real ECOS API needs a key.
        # Returning a fixed recent rate keeps the payment path measurable offline.
        if path.startswith("/api/StatisticSearch/"):
            today = time.strftime("%Y%m%d", time.gmtime())
            return self._send(200, {
                "StatisticSearch": {
                    "row": [{
                        "TIME": today,
                        "DATA_VALUE": "1350.00",
                        "STAT_CODE": "731Y001",
                        "UNIT_NAME": "1달러",
                    }],
                },
            })
        if path.startswith("/v2/checkout/orders/"):
            order_id = path.split("/")[4]
            order = ORDERS.get(order_id)
            if order is None:
                return self._send(404, {"name": "RESOURCE_NOT_FOUND"})
            return self._send(200, {
                "id": order_id,
                "status": order["status"],
                "purchase_units": [{"amount": {"value": str(order["amount"]),
                                               "currency_code": order["currency"]}}],
            })
        if path == "/health":
            return self._send(200, {"status": "ok", "orders": len(ORDERS)})
        return self._send(404, {"name": "NOT_IMPLEMENTED", "path": path})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    args = parser.parse_args()
    # Bind IPv4 explicitly and IPv6 as well. An IPv6-only listener accepts ::1 but
    # refuses 127.0.0.1, and the app connects to 127.0.0.1, so a v6-only bind looks
    # exactly like a dead provider.
    servers = []
    ThreadingHTTPServer.address_family = socket.AF_INET
    servers.append(ThreadingHTTPServer(("0.0.0.0", args.port), Handler))
    try:
        import threading
        ThreadingHTTPServer6 = type("ThreadingHTTPServer6", (ThreadingHTTPServer,), {})
        ThreadingHTTPServer6.address_family = socket.AF_INET6
        v6 = ThreadingHTTPServer6(("::", args.port), Handler)
        threading.Thread(target=v6.serve_forever, daemon=True).start()
        servers.append(v6)
    except OSError:
        pass  # IPv6 unavailable in this network namespace; IPv4 is enough
    server = servers[0]
    print(f"paypal-stub listening on :{args.port} (latency={LATENCY_MS}ms)")
    server.serve_forever()


if __name__ == "__main__":
    main()
