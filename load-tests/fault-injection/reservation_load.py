#!/usr/bin/env python3
"""Seed stock and send a constant-rate reservation load, recording every answer.

Subcommands:
  seed  create the run's three SKUs (two at location A, one at location B)
  load  send RATE requests per second for DURATION seconds and write one JSON line per request
        (--url takes one URL or a comma-separated list; request i goes to url i % len, so two
        application instances get a client-side round robin)
  replay  after a run, resend requests with the SAME idempotency key: phase "retry" repeats every
        request that did not get 200/201/202 until it gets 200/201 (or the deadline), phase "all"
        then sends every key once more. One JSON line per attempt.
  race  send the same Idempotency-Key twice at the same moment (EXP-095 (c)); variants:
        c1 same key, one request to each of two application instances, released together by a barrier
        c2 same key, both to the same instance, released together
        c3 retry during processing: the second request (same key, OTHER instance) follows --gap seconds after the first
        neg NEGATIVE CONTROL: the two requests carry different keys and different orders (every pair must make two workflows)
        seq sequential control: the second request starts after the first answered (the overlap must be about zero)

Every request is one order with one SKU at location A (two items) and one at location B,
the same shape EXP-070 used. The client does not retry: a request that gets no answer is
recorded as status 0, and the audit decides from the databases whether it still took effect.
Tokens come from the environment (INVENTORY_LOCATION_SERVICE_TOKEN,
ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN), never from arguments.
Standard library only, so the host needs nothing but python3.
"""
import argparse
import concurrent.futures
import http.client
import itertools
import json
import os
import threading
import time
import urllib.parse

ACCEPTED = (200, 201, 202)
LOCATION_A = "00000000-0000-0000-0000-000000000001"
LOCATION_B = "00000000-0000-0000-0000-000000000002"
SELLER_A = "00000000-0000-0000-0000-000000000011"
SELLER_B = "00000000-0000-0000-0000-000000000012"


def sku_names(run_id):
    return {"a1": f"fi-a1-{run_id}", "a2": f"fi-a2-{run_id}", "b1": f"fi-b1-{run_id}"}


def order_id(run_id, sequence):
    return f"{run_id[:8]}-0000-4000-8000-{sequence:012d}"


def request_body(run_id, sequence):
    skus = sku_names(run_id)
    return {
        "orderId": order_id(run_id, sequence),
        "allocations": [
            {"fulfillmentLocationId": LOCATION_A, "items": [
                {"sellerId": SELLER_A, "skuId": skus["a1"], "quantity": 1},
                {"sellerId": SELLER_A, "skuId": skus["a2"], "quantity": 1}]},
            {"fulfillmentLocationId": LOCATION_B, "items": [
                {"sellerId": SELLER_B, "skuId": skus["b1"], "quantity": 1}]},
        ],
    }


def call(base_url, method, path, headers, body=None, timeout=15):
    parsed = urllib.parse.urlparse(base_url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    try:
        connection.request(method, path, body=json.dumps(body) if body is not None else None,
                           headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def seed(args):
    capacity = args.capacity
    headers = {"X-Atlas-Service-Token": os.environ["INVENTORY_LOCATION_SERVICE_TOKEN"], "Content-Type": "application/json"}
    skus = sku_names(args.run_id)
    targets = [(args.location_a_url, LOCATION_A, SELLER_A, skus["a1"]),
               (args.location_a_url, LOCATION_A, SELLER_A, skus["a2"]),
               (args.location_b_url, LOCATION_B, SELLER_B, skus["b1"])]
    for base_url, location, seller, sku in targets:
        path = f"/internal/v1/fulfillment-locations/{location}/stock/{seller}/{sku}"
        status, _ = call(base_url, "PUT", path, headers, {"sellerId": seller, "initialCapacity": capacity})
        if status != 200:
            raise SystemExit(f"stock seed failed for {sku}: HTTP {status}")


class Sender:
    """One keep-alive connection per worker thread; reconnects after any error."""

    def __init__(self, url, headers, timeout):
        parsed = urllib.parse.urlparse(url)
        self.host, self.port, self.path = parsed.hostname, parsed.port, parsed.path
        self.headers, self.timeout = headers, timeout
        self.connection = None

    def post(self, body, idempotency_key):
        if self.connection is None:
            self.connection = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        try:
            self.connection.request("POST", self.path, body=body,
                                    headers={**self.headers, "Idempotency-Key": idempotency_key})
            response = self.connection.getresponse()
            return response.status, response.read(), None
        except Exception as error:  # any transport failure is an unknown outcome
            self.close()
            return 0, b"", type(error).__name__

    def close(self):
        if self.connection is not None:
            self.connection.close()
            self.connection = None


def load(args):
    total = int(args.rate * args.duration)
    headers = {"X-Atlas-Service-Token": os.environ["ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN"], "Content-Type": "application/json"}
    start_at = args.start_at if args.start_at else time.time() + 1.0
    counter = itertools.count()
    counter_lock = threading.Lock()
    records = [None] * total

    urls = args.url.split(",")

    def worker():
        senders = [Sender(url, headers, args.timeout) for url in urls]
        while True:
            with counter_lock:
                index = next(counter)
            if index >= total:
                for sender in senders:
                    sender.close()
                return
            scheduled = start_at + index / args.rate
            delay = scheduled - time.time()
            if delay > 0:
                time.sleep(delay)
            key = f"{args.run_id}-{index}"
            sent = time.time()
            target = index % len(urls)
            status, raw, error = senders[target].post(json.dumps(request_body(args.run_id, index)), key)
            ended = time.time()
            body_status = None
            if raw:
                try:
                    body_status = json.loads(raw).get("status")
                except ValueError:
                    body_status = None
            records[index] = {"i": index, "key": key, "scheduled": scheduled, "sent": sent,
                              "ended": ended, "http": status, "body_status": body_status,
                              "error": error, "target": target}

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(args.threads)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    with open(args.out, "w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record) + "\n")
    print(json.dumps({"total": total, "start_at": start_at, "end_at": max(r["ended"] for r in records)}))


def replay(args):
    originals = [json.loads(line) for line in open(args.requests, encoding="utf-8").read().splitlines()]
    headers = {"X-Atlas-Service-Token": os.environ["ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN"], "Content-Type": "application/json"}
    urls = args.url.split(",")
    attempts = []
    attempts_lock = threading.Lock()

    def send(sender, record, phase, attempt):
        sent = time.time()
        status, raw, error = sender.post(json.dumps(request_body(args.run_id, record["i"])), record["key"])
        ended = time.time()
        body_status, replayed = None, None
        if raw:
            try:
                parsed = json.loads(raw)
                body_status, replayed = parsed.get("status"), parsed.get("replayed")
            except ValueError:
                pass
        with attempts_lock:
            attempts.append({"phase": phase, "i": record["i"], "key": record["key"], "attempt": attempt,
                             "sent": sent, "ended": ended, "http": status, "body_status": body_status,
                             "replayed": replayed, "error": error, "original_http": record["http"]})
        return status

    def run_phase(phase, selected, final_statuses, deadline):
        queue = list(selected)
        queue_lock = threading.Lock()

        def worker(worker_index):
            senders = [Sender(url, headers, args.timeout) for url in urls]
            while True:
                with queue_lock:
                    if not queue:
                        break
                    record = queue.pop()
                attempt = 0
                while True:
                    attempt += 1
                    target = (record["i"] + 1 + attempt) % len(urls)
                    if send(senders[target], record, phase, attempt) in final_statuses or time.time() >= deadline:
                        break
                    time.sleep(args.interval)
            for sender in senders:
                sender.close()

        threads = [threading.Thread(target=worker, args=(n,), daemon=True) for n in range(args.threads)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

    failed = [r for r in originals if r["http"] not in ACCEPTED]
    run_phase("retry", failed, (200, 201), time.time() + args.deadline)
    run_phase("all", originals, ACCEPTED, 0)  # deadline 0: exactly one attempt per key
    with open(args.out, "w", encoding="utf-8") as handle:
        for attempt in attempts:
            handle.write(json.dumps(attempt) + "\n")
    print(json.dumps({"retry_set": len(failed), "attempts": len(attempts),
                      "end_at": max((a["ended"] for a in attempts), default=time.time())}))


def race_send(url, headers, key, body, timeout):
    sender = Sender(url, headers, timeout)
    begin = time.time()
    status, raw, error = sender.post(json.dumps(body), key)
    end = time.time()
    sender.close()
    parsed = {}
    if raw:
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = {}
    return {"sent": begin, "ended": end, "http": status, "body_status": parsed.get("status"), "error": error,
            "replayed": parsed.get("replayed"), "workflow_id": parsed.get("workflowId")}


def race_pair(args, urls, headers, pair, records, lock):
    variant = args.variant
    if variant == "neg":
        keys = [f"{args.run_id}-{pair}a", f"{args.run_id}-{pair}b"]
        indices = [2 * pair, 2 * pair + 1]
    else:
        keys = [f"{args.run_id}-{pair}"] * 2
        indices = [pair, pair]
    bodies = [request_body(args.run_id, index) for index in indices]
    first = pair % len(urls)
    targets = {"c1": [first, 1 - first], "c2": [first, first], "c3": [first, 1 - first],
               "neg": [first, 1 - first], "seq": [first, 1 - first]}[variant]
    results = [None, None]
    barrier = threading.Barrier(2)

    def worker(role):
        if variant in ("c1", "c2", "neg"):
            barrier.wait()
        elif variant == "c3" and role == 1:
            time.sleep(args.gap)
        results[role] = race_send(urls[targets[role]], headers, keys[role], bodies[role], args.timeout)

    if variant == "seq":
        for role in (0, 1):
            worker(role)
    else:
        threads = [threading.Thread(target=worker, args=(role,)) for role in (0, 1)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
    with lock:
        for role in (0, 1):
            records.append({"i": 2 * pair + role if variant == "neg" else None, "pair": pair, "role": role, "variant": variant,
                            "key": keys[role], "target": targets[role], "scheduled": results[role]["sent"], **results[role]})


def race(args):
    urls = args.url.split(",")
    headers = {"X-Atlas-Service-Token": os.environ["ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN"], "Content-Type": "application/json"}
    records, lock = [], threading.Lock()
    began = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        list(pool.map(lambda pair: race_pair(args, urls, headers, pair, records, lock), range(args.pairs)))
    finished = time.time()
    ordered = sorted(records, key=lambda r: (r["pair"], r["role"]))
    for record in ordered:
        if record["i"] is None:
            record["i"] = record["pair"]
    with open(args.out, "w", encoding="utf-8") as handle:
        for record in ordered:
            handle.write(json.dumps(record) + "\n")
    print(json.dumps({"pairs": args.pairs, "begin": began, "end_at": finished, "requests": len(ordered)}))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    seed_parser = sub.add_parser("seed")
    seed_parser.add_argument("--run-id", required=True)
    seed_parser.add_argument("--capacity", type=int, required=True)
    seed_parser.add_argument("--location-a-url", required=True)
    seed_parser.add_argument("--location-b-url", required=True)
    seed_parser.set_defaults(handler=seed)
    load_parser = sub.add_parser("load")
    load_parser.add_argument("--run-id", required=True)
    load_parser.add_argument("--url", required=True)
    load_parser.add_argument("--rate", type=float, required=True)
    load_parser.add_argument("--duration", type=float, required=True)
    load_parser.add_argument("--threads", type=int, default=64)
    load_parser.add_argument("--timeout", type=float, default=15.0)
    load_parser.add_argument("--start-at", type=float, default=0.0)
    load_parser.add_argument("--out", required=True)
    load_parser.set_defaults(handler=load)
    replay_parser = sub.add_parser("replay")
    replay_parser.add_argument("--run-id", required=True)
    replay_parser.add_argument("--url", required=True)
    replay_parser.add_argument("--requests", required=True)
    replay_parser.add_argument("--threads", type=int, default=64)
    replay_parser.add_argument("--timeout", type=float, default=15.0)
    replay_parser.add_argument("--interval", type=float, default=1.0)
    replay_parser.add_argument("--deadline", type=float, default=60.0)
    replay_parser.add_argument("--out", required=True)
    replay_parser.set_defaults(handler=replay)
    race_parser = sub.add_parser("race")
    race_parser.add_argument("--run-id", required=True)
    race_parser.add_argument("--url", required=True, help="two application reservation URLs, comma separated")
    race_parser.add_argument("--variant", choices=["c1", "c2", "c3", "neg", "seq"], required=True)
    race_parser.add_argument("--pairs", type=int, default=800)
    race_parser.add_argument("--concurrency", type=int, default=24)
    race_parser.add_argument("--gap", type=float, default=0.3)
    race_parser.add_argument("--timeout", type=float, default=15.0)
    race_parser.add_argument("--out", required=True)
    race_parser.set_defaults(handler=race)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
