#!/usr/bin/env python3
"""Audit one fault-injection run from the databases, and summarize many runs.

audit      compare what the client was told with what the three databases hold
summarize  read every run's result.json under a results directory and print per-condition tables

Invariants (each is a boolean per run; one false is a finding, not noise):
  lost_accepted == 0   every request answered 200/201/202 is RESERVED in the order DB at the end
  counts_equal         order RESERVED workflows == location A reservations == location B
                       reservations == the reserved quantity of each of the run's three SKUs
  no_duplicates        no order_id appears twice in the order workflows or in either location,
                       and every reservation item has quantity 1
  converged            no workflow is left in PENDING, RETRY_PENDING, COMPENSATING,
                       COMPENSATION_REQUIRED or CANCEL_PENDING within the timeout
Extra evidence recorded per run (not invariants): the non-terminal workflows seen right after the load
(lease_until and next_attempt_at relative to the database clock, so a convergence tail can be compared
with the lease), the time to the first 201 after the fault ended, per-target statuses, latencies, and
for runs with --replay (same-key retries) whether every retried request was answered and whether the
replay of every key created nothing new.
Only rows created by this run are counted (idempotency key prefix on the order side, order id
prefix on the location side), so earlier runs in the same database cannot leak in.
"""
import argparse
import collections
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

BASE_INVARIANTS = ("lost_accepted_zero", "counts_equal", "no_duplicates", "converged")
NONTERMINAL = ("PENDING", "RETRY_PENDING", "COMPENSATING", "COMPENSATION_REQUIRED", "CANCEL_PENDING")
ACCEPTED = (200, 201, 202)


def psql(container, user, database, sql, timeout=15):
    """Return rows as lists of strings, or None when the database does not answer."""
    try:
        completed = subprocess.run(
            ["docker", "exec", container, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", user,
             "-d", database, "-At", "-F", "|", "-c", sql],
            capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return None
    if completed.returncode != 0:
        return None
    return [line.split("|") for line in completed.stdout.splitlines() if line]


def retry(function, attempts=40, pause=1.0):
    for _ in range(attempts):
        value = function()
        if value is not None:
            return value
        time.sleep(pause)
    raise SystemExit("database did not answer in time; evidence is in the run directory")


def order_query(args, sql):
    return psql(args.order_container, "atlas", "atlas_order", sql)


def location_query(container, sql):
    return psql(container, "atlas_inventory", "atlas_inventory", sql)


def nonterminal_snapshot(args, states):
    """Non-terminal workflows right now, with lease_until / next_attempt_at as seconds from the DB clock."""
    sql = (f"SELECT count(*), count(*) FILTER (WHERE next_attempt_at <= now()), "
           f"count(*) FILTER (WHERE lease_until IS NOT NULL AND lease_until > now()), "
           f"coalesce(round(EXTRACT(EPOCH FROM (max(lease_until) - now()))::numeric, 2)::text, ''), "
           f"coalesce(round(EXTRACT(EPOCH FROM (max(next_attempt_at) - now()))::numeric, 2)::text, ''), "
           f"coalesce(max(attempt_count), 0), coalesce(string_agg(DISTINCT workflow_state, ','), '') "
           f"FROM order_inventory_reservation_workflow "
           f"WHERE idempotency_key LIKE '{args.run_id}-%' AND workflow_state IN ({states})")
    rows = order_query(args, sql)
    if rows is None or not rows:
        return None
    row = rows[0]
    return {"non_terminal": int(row[0]), "due": int(row[1]), "leased": int(row[2]),
            "lease_remaining_max_s": float(row[3]) if row[3] else None,
            "next_attempt_remaining_max_s": float(row[4]) if row[4] else None,
            "max_attempt_count": int(row[5]), "states": row[6]}


def wait_for_convergence(args):
    """(epoch at which the run first has no non-terminal workflow or None on timeout, tail probe).

    The probe is the first snapshot that still saw non-terminal workflows. It predicts when the run
    converges if the only thing holding it back is the lease (max lease_until) or the retry backoff
    (max next_attempt_at); the audit then compares the prediction with the observed time."""
    states = ",".join(f"'{state}'" for state in NONTERMINAL)
    deadline = time.time() + args.converge_timeout
    probe = None
    while time.time() < deadline:
        taken = time.time()
        snapshot = nonterminal_snapshot(args, states)
        if snapshot is not None and snapshot["non_terminal"] == 0:
            return time.time(), probe
        if snapshot is not None and probe is None:
            probe = {**snapshot, "taken_after_since_s": round(taken - args.since, 2),
                     "taken_epoch": taken}
        time.sleep(0.5)
    return None, probe


def location_view(container, run_id):
    prefix = f"{run_id[:8]}-0000-4000-8000-%"
    reservations = retry(lambda: location_query(
        container,
        f"SELECT status, count(*), count(DISTINCT order_id) FROM inventory_reservation "
        f"WHERE order_id::text LIKE '{prefix}' GROUP BY status ORDER BY status"))
    items = retry(lambda: location_query(
        container,
        f"SELECT i.sku_id, count(*), coalesce(sum(i.quantity), 0), "
        f"count(*) FILTER (WHERE i.quantity <> 1) FROM inventory_reservation_item i "
        f"JOIN inventory_reservation r USING (reservation_id) "
        f"WHERE r.order_id::text LIKE '{prefix}' AND r.status = 'RESERVED' GROUP BY i.sku_id"))
    stock = retry(lambda: location_query(
        container,
        f"SELECT sku_id, initial_capacity, available_quantity, reserved_quantity, claimed_quantity "
        f"FROM inventory_location_stock WHERE sku_id LIKE 'fi-%-{run_id}' ORDER BY sku_id"))
    return {
        "reservations": {row[0]: {"rows": int(row[1]), "distinct_orders": int(row[2])} for row in reservations},
        "items": {row[0]: {"rows": int(row[1]), "quantity": int(row[2]), "quantity_not_one": int(row[3])}
                  for row in items},
        "stock": {row[0]: {"initial": int(row[1]), "available": int(row[2]), "reserved": int(row[3]),
                           "claimed": int(row[4])} for row in stock},
    }


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(fraction * len(ordered)))] if ordered else None


def latency_summary(requests):
    values = [r["ended"] - r["sent"] for r in requests]
    lags = [r["sent"] - r["scheduled"] for r in requests]
    return {"max": round(max(values), 2), "p50": round(percentile(values, 0.5), 3),
            "p95": round(percentile(values, 0.95), 3),
            "send_lag_max": round(max(lags), 2)} if values else {}


def retry_summary(requests, replay, state_by_key, created_by_key):
    """What happened when requests were resent with the same idempotency key (--replay runs)."""
    failed = [r for r in requests if r["http"] not in ACCEPTED]
    retries = [a for a in replay if a["phase"] == "retry"]
    everything = [a for a in replay if a["phase"] == "all"]
    by_key = collections.defaultdict(list)
    for attempt in retries:
        by_key[attempt["key"]].append(attempt)
    resolved, existed_201, ones, twos, attempts_to_resolve = 0, 0, 0, 0, []
    for request in failed:
        tries = by_key.get(request["key"], [])
        final = next((a for a in tries if a["http"] in (200, 201)), None)
        if final is None:
            continue
        resolved += 1
        attempts_to_resolve.append(final["attempt"])
        if final["http"] == 201:
            ones += 1
            # a 201 means "created by this request". The row is older than the answering attempt by far
            # (1 s margin for the database-to-host clock difference) -> it existed, and 200 was expected.
            if created_by_key.get(request["key"], final["sent"] + 1) < final["sent"] - 1.0:
                existed_201 += 1
        else:
            twos += 1
    return {"retry_set": len(failed), "resolved": resolved, "unresolved": len(failed) - resolved,
            "resolved_with_201": ones, "resolved_with_200": twos,
            "retry_201_but_existed": existed_201,
            "attempts_to_resolve_max": max(attempts_to_resolve, default=0),
            "retry_attempts": len(retries),
            "all_total": len(everything),
            "all_http": dict(collections.Counter(str(a["http"]) for a in everything)),
            "all_201": sum(1 for a in everything if a["http"] == 201),
            "all_body_status": dict(collections.Counter(str(a["body_status"]) for a in everything)),
            "all_not_reserved_body": sum(1 for a in everything
                                         if a["http"] in (200, 201) and a["body_status"] != "RESERVED")}


def audit(args):
    requests = [json.loads(line) for line in Path(args.requests).read_text(encoding="utf-8").splitlines()]
    converge_epoch, probe = wait_for_convergence(args)
    converge_seconds = None if converge_epoch is None else round(max(0.0, converge_epoch - args.since), 2)
    if probe is not None and converge_epoch is not None:
        taken = probe.pop("taken_epoch")
        for name, key in (("lease", "lease_remaining_max_s"), ("backoff", "next_attempt_remaining_max_s")):
            if probe[key] is not None:
                probe[f"predicted_converge_by_{name}_s"] = round(taken + max(probe[key], 0) - args.since, 2)
        probe["observed_converge_s"] = converge_seconds
        if "predicted_converge_by_lease_s" in probe:
            probe["observed_minus_lease_prediction_s"] = round(
                converge_seconds - probe["predicted_converge_by_lease_s"], 2)
    elif probe is not None:
        probe.pop("taken_epoch")
    window = {}
    recovery = {}
    if args.fault_file and Path(args.fault_file).exists():
        fault = json.loads(Path(args.fault_file).read_text(encoding="utf-8"))
        inside = [r for r in requests if fault["begin"] <= r["sent"] <= fault["end"]]
        window = {"begin": fault["begin"], "end": fault["end"], "length_s": round(fault["end"] - fault["begin"], 2),
                  "requests_started_inside": len(inside),
                  "http_status_inside": dict(collections.Counter(str(r["http"]) for r in inside))}
        if converge_epoch is not None:
            window["converge_after_fault_end_s"] = round(converge_epoch - fault["end"], 2)
        after = [r for r in requests if r["http"] == 201 and r["ended"] > fault["end"]]
        late_failures = [r for r in requests if r["http"] not in ACCEPTED and r["ended"] > fault["end"]]
        recovery = {"first_201_after_fault_end_s": round(min(r["ended"] for r in after) - fault["end"], 2)
                    if after else None,
                    "last_failure_after_fault_end_s": round(max(r["ended"] for r in late_failures) - fault["end"], 2)
                    if late_failures else None}

    local_before = time.time()
    clock = retry(lambda: order_query(args, "SELECT EXTRACT(EPOCH FROM now())"))
    clock_skew = round(time.time() - float(clock[0][0]), 3) - round((time.time() - local_before) / 2, 3)
    workflow_rows = retry(lambda: order_query(
        args, f"SELECT idempotency_key, workflow_state, order_id, EXTRACT(EPOCH FROM created_at) "
              f"FROM order_inventory_reservation_workflow WHERE idempotency_key LIKE '{args.run_id}-%'"))
    state_by_key = {row[0]: row[1] for row in workflow_rows}
    created_by_key = {row[0]: float(row[3]) + clock_skew for row in workflow_rows}  # host clock
    order_ids = [row[2] for row in workflow_rows]
    states = collections.Counter(state_by_key.values())

    http_counts = collections.Counter(str(r["http"]) for r in requests)
    replay = ([json.loads(line) for line in Path(args.replay).read_text(encoding="utf-8").splitlines()]
              if args.replay and Path(args.replay).exists() else None)
    answered_by_retry = {}
    if replay:
        for attempt in replay:
            if attempt["http"] in ACCEPTED:
                answered_by_retry.setdefault(attempt["key"], attempt)
    accepted = [r for r in requests if r["http"] in ACCEPTED or r["key"] in answered_by_retry]
    lost = [r["key"] for r in accepted if state_by_key.get(r["key"]) != "RESERVED"]
    lost_201 = [r["key"] for r in accepted
                if r["http"] in (200, 201) and state_by_key.get(r["key"]) != "RESERVED"]
    unknown_but_reserved = [r["key"] for r in requests
                            if r["http"] not in ACCEPTED and state_by_key.get(r["key"]) == "RESERVED"]
    unknown_total = [r["key"] for r in requests if r["http"] not in ACCEPTED]
    answered_not_in_db = [r["key"] for r in accepted if r["key"] not in state_by_key]

    location_a = location_view(args.location_a_container, args.run_id)
    location_b = location_view(args.location_b_container, args.run_id)
    skus = {"a1": f"fi-a1-{args.run_id}", "a2": f"fi-a2-{args.run_id}", "b1": f"fi-b1-{args.run_id}"}

    def reserved_rows(view):
        return view["reservations"].get("RESERVED", {}).get("rows", 0)

    def stock_reserved(view, sku):
        return view["stock"].get(sku, {}).get("reserved", -1)

    def item_rows(view, sku):
        return view["items"].get(sku, {}).get("rows", 0)

    order_reserved = states.get("RESERVED", 0)
    equal_values = {
        "order_reserved": order_reserved,
        "location_a_reservations": reserved_rows(location_a),
        "location_b_reservations": reserved_rows(location_b),
        "stock_reserved_a1": stock_reserved(location_a, skus["a1"]),
        "stock_reserved_a2": stock_reserved(location_a, skus["a2"]),
        "stock_reserved_b1": stock_reserved(location_b, skus["b1"]),
        "items_a1": item_rows(location_a, skus["a1"]),
        "items_a2": item_rows(location_a, skus["a2"]),
        "items_b1": item_rows(location_b, skus["b1"]),
    }
    duplicates = {
        "order_workflows": len(order_ids) - len(set(order_ids)),
        "location_a": sum(v["rows"] - v["distinct_orders"] for v in location_a["reservations"].values()),
        "location_b": sum(v["rows"] - v["distinct_orders"] for v in location_b["reservations"].values()),
        "items_quantity_not_one": sum(v["quantity_not_one"] for view in (location_a, location_b)
                                      for v in view["items"].values()),
    }
    result = {
        "run_id": args.run_id,
        "requests_sent": len(requests),
        "http_status": dict(http_counts),
        "body_status": dict(collections.Counter(str(r["body_status"]) for r in requests if r["http"] in ACCEPTED)),
        "order_workflow_states": dict(states),
        "equal_values": equal_values,
        "duplicates": duplicates,
        "location_a": location_a,
        "location_b": location_b,
        "lost_accepted": len(lost),
        "lost_accepted_http_200_201": len(lost_201),
        "accepted_missing_in_order_db": len(answered_not_in_db),
        "unknown_outcome_requests": len(unknown_total),
        "unknown_outcome_but_reserved": len(unknown_but_reserved),
        "converge_seconds": converge_seconds,
        "tail_probe": probe,
        "recovery": recovery,
        "http_status_by_target": {str(t): dict(collections.Counter(
            str(r["http"]) for r in requests if r.get("target", 0) == t))
            for t in sorted({r.get("target", 0) for r in requests})},
        "latency_s": latency_summary(requests),
        "clock_skew_s": clock_skew,
        "fault_window": window,
        "invariants": {
            "lost_accepted_zero": len(lost) == 0,
            "counts_equal": len(set(equal_values.values())) == 1,
            "no_duplicates": all(value == 0 for value in duplicates.values()),
            "converged": converge_seconds is not None,
        },
        "evidence": {"lost_keys": lost[:20]},
    }
    if replay is not None:
        result["retry"] = retry_summary(requests, replay, state_by_key, created_by_key)
        result["invariants"]["retry_resolved"] = result["retry"]["unresolved"] == 0
        result["invariants"]["replay_idempotent"] = (result["retry"]["all_201"] == 0
                                                     and result["retry"]["retry_201_but_existed"] == 0
                                                     and result["retry"]["all_not_reserved_body"] == 0)
    result["pass_all"] = all(result["invariants"].values())
    Path(args.out).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"run_id": args.run_id, "pass_all": result["pass_all"], **result["invariants"],
                      "lost": len(lost), "order_reserved": order_reserved}))
    return 0 if result["pass_all"] else 1


def spread(values):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    return f"{min(values):g} / {statistics.median(values):g} / {max(values):g}"


def retry_pass(runs):
    retried = [r for r in runs if "retry_resolved" in r["invariants"]]
    if not retried:
        return "-"
    return f"{sum(1 for r in retried if r['invariants']['retry_resolved'] and r['invariants']['replay_idempotent'])}/{len(retried)}"


def summarize(args):
    by_condition = collections.defaultdict(list)
    for result_file in sorted(Path(args.results).glob("*/run-*/result.json")):
        condition = result_file.parent.parent.name
        run = json.loads(result_file.read_text(encoding="utf-8"))
        meta_file = result_file.parent / "meta.json"
        run["meta"] = json.loads(meta_file.read_text(encoding="utf-8")) if meta_file.exists() else {}
        by_condition[condition].append(run)
    statuses = sorted({s for runs in by_condition.values() for run in runs for s in run["http_status"]},
                      key=int)
    print("| condition | n | lost=0 | counts equal | no duplicates | converged | all four | "
          "retry invariants (only retry runs) |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for condition, runs in by_condition.items():
        n = len(runs)

        def count(name):
            return sum(1 for run in runs if run["invariants"][name])

        print(f"| {condition} | {n} | {count('lost_accepted_zero')} | {count('counts_equal')} | "
              f"{count('no_duplicates')} | {count('converged')} | "
              f"{sum(1 for r in runs if all(r['invariants'][k] for k in BASE_INVARIANTS))} | "
              f"{retry_pass(runs)} |")
    print()
    print("client-visible status per run, min / median / max over runs (requests per run: "
          f"{next(iter(by_condition.values()))[0]['requests_sent'] if by_condition else '-'})")
    print()
    print("| condition | " + " | ".join(statuses) + " | unknown outcome but RESERVED | converge s |")
    print("| --- | " + " | ".join("---" for _ in statuses) + " | --- | --- |")
    for condition, runs in by_condition.items():
        cells = [spread([run["http_status"].get(status, 0) for run in runs]) for status in statuses]
        print(f"| {condition} | " + " | ".join(cells) + " | "
              f"{spread([run['unknown_outcome_but_reserved'] for run in runs])} | "
              f"{spread([run['converge_seconds'] for run in runs])} |")
    print()
    print("what the fault did (runs where it was observed / n), and recovery timing in seconds, min / median / max")
    print()
    print("| condition | order DB log: not properly shut down | order DB log: redo starts | "
          "app restarted | kernel OOM events | requests started inside fault window | converge after fault end s |")
    print("| --- | --- | --- | --- | --- | --- | --- |")
    for condition, runs in by_condition.items():
        n = len(runs)

        def observed(test):
            return f"{sum(1 for run in runs if test(run))}/{n}"

        print(f"| {condition} | "
              f"{observed(lambda r: r['meta'].get('order_postgres_log', {}).get('not_properly_shut_down', 0) > 0)} | "
              f"{observed(lambda r: r['meta'].get('order_postgres_log', {}).get('redo_starts', 0) > 0)} | "
              f"{observed(lambda r: r['meta'].get('containers', {}).get('app', {}).get('restart_count', 0) > 0)} | "
              f"{observed(lambda r: r['meta'].get('oom_events', 0) > 0)} | "
              f"{spread([r['fault_window'].get('requests_started_inside') for r in runs])} | "
              f"{spread([r['fault_window'].get('converge_after_fault_end_s') for r in runs])} |")
    print()
    print("recovery and latency (seconds; min / median / max over runs; '-' = not applicable)")
    print()
    print("| condition | first 201 after fault end | last failure after fault end | max request latency | "
          "p95 request latency | request sent later than scheduled (max) |")
    print("| --- | --- | --- | --- | --- | --- |")
    for condition, runs in by_condition.items():
        print(f"| {condition} | "
              f"{spread([r.get('recovery', {}).get('first_201_after_fault_end_s') for r in runs])} | "
              f"{spread([r.get('recovery', {}).get('last_failure_after_fault_end_s') for r in runs])} | "
              f"{spread([r.get('latency_s', {}).get('max') for r in runs])} | "
              f"{spread([r.get('latency_s', {}).get('p95') for r in runs])} | "
              f"{spread([r.get('latency_s', {}).get('send_lag_max') for r in runs])} |")
    probes = {c: [r for r in runs if r.get("tail_probe")] for c, runs in by_condition.items()}
    if any(probes.values()):
        print()
        print("convergence tail: the first look after the load that still saw non-terminal workflows. A row "
              "is 'stuck on a lease' when leased more than 3 s earlier (lease length minus remaining time); "
              "only then is 'converged when the longest lease expired' a prediction (a lease the recovery "
              "worker took a moment ago belongs to live work, not to a dead holder)")
        print()
        print("| condition | n | non-terminal at first look | stuck on a lease | converge s, all runs | "
              "max lease remaining s (stuck runs) | observed minus lease prediction s (stuck runs) | "
              "converge s of tail runs that are not lease-stuck |")
        print("| --- | --- | --- | --- | --- | --- | --- | --- |")
        for condition, runs in by_condition.items():
            found = probes[condition]
            if not found:
                continue

            def stuck(run):
                probe = run["tail_probe"]
                lease = float(run["meta"].get("recovery_lease", "PT30S")[2:-1])
                return (probe.get("leased", 0) >= 1 and probe.get("lease_remaining_max_s") is not None
                        and lease - probe["lease_remaining_max_s"] > 3)

            stuck_runs = [r for r in found if stuck(r)]
            others = [r for r in found if not stuck(r)]
            print(f"| {condition} | {len(runs)} | {len(found)}/{len(runs)} | {len(stuck_runs)}/{len(runs)} | "
                  f"{spread([r['converge_seconds'] for r in runs])} | "
                  f"{spread([r['tail_probe'].get('lease_remaining_max_s') for r in stuck_runs])} | "
                  f"{spread([r['tail_probe'].get('observed_minus_lease_prediction_s') for r in stuck_runs])} | "
                  f"{spread([r['converge_seconds'] for r in others])} |")
    retried = {c: [r for r in runs if "retry" in r] for c, runs in by_condition.items()}
    if any(retried.values()):
        print()
        print("same-key retry: requests without 200/201/202 are resent with the same key every second "
              "(up to 60 s), then every key is sent once more (min / median / max over runs)")
        print()
        print("| condition | retried requests | resolved with 200 (replay) | resolved with 201 (created) | "
              "unresolved | 201 although the row existed | replay-all 200 | replay-all 201 | replay-all 202 | "
              "replay-all other | replay-all body not RESERVED |")
        print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for condition, runs in by_condition.items():
            found = retried[condition]
            if not found:
                continue

            def column(name):
                return spread([r["retry"][name] for r in found])

            def all_http(code):
                return spread([r["retry"]["all_http"].get(code, 0) for r in found])

            other = [sum(v for k, v in r["retry"]["all_http"].items() if k not in ("200", "201", "202")) for r in found]
            print(f"| {condition} | {column('retry_set')} | {column('resolved_with_200')} | "
                  f"{column('resolved_with_201')} | {column('unresolved')} | {column('retry_201_but_existed')} | "
                  f"{all_http('200')} | {all_http('201')} | {all_http('202')} | {spread(other)} | "
                  f"{column('all_not_reserved_body')} |")
    return 0


CALL_LINE = re.compile(r"ALOG (\S+) (\d+) (\d+) POST (\S+/reservations) (\d+) (\S+)\s*$")


def parse_calls(text):
    calls = []
    for line in text.splitlines():
        match = CALL_LINE.search(line)
        if match:
            ip, begin, end, _, status, key = match.groups()
            calls.append({"ip": ip, "begin": int(begin) / 1000, "end": int(end) / 1000,
                          "status": int(status), "key": key})
    return calls


def analyse_calls(calls, app_ips, since, window, until=None):
    calls = [c for c in calls if c["begin"] >= since - 0.3 and (until is None or c["begin"] <= until + 2.0)]
    by_key = collections.defaultdict(list)
    for call in calls:
        by_key[call["key"]].append(call)
    xdup = foreign = double_201 = overlap = 0
    sources = collections.Counter(c["ip"] for c in calls)
    max_calls = 0
    for key_calls in by_key.values():
        max_calls = max(max_calls, len(key_calls))
        instances = {c["ip"] for c in key_calls if c["ip"] in app_ips}
        if len(instances) >= 2:
            xdup += 1
        if any(c["ip"] not in app_ips for c in key_calls) and app_ips:
            foreign += 1
        if sum(1 for c in key_calls if c["status"] == 201) >= 2:
            double_201 += 1
        ordered = sorted(key_calls, key=lambda c: c["begin"])
        if any(ordered[i + 1]["begin"] < ordered[i]["end"] for i in range(len(ordered) - 1)):
            overlap += 1
    summary = {
        "calls": len(calls), "keys": len(by_key),
        "dup_calls": len(calls) - len(by_key), "max_calls_per_key": max_calls,
        "xdup_keys": xdup, "foreign_keys": foreign, "double_201": double_201, "overlap_keys": overlap,
        "status": dict(collections.Counter(str(c["status"]) for c in calls)),
        "by_source": dict(sources),
    }
    if window:
        begin, end = window
        inside = [c for c in calls if begin <= c["begin"] <= end]
        summary["in_window_calls"] = len(inside)
        summary["in_window_status"] = dict(collections.Counter(str(c["status"]) for c in inside))
    return summary


CALL_SELFTEST_LINES = """\
location-a-1  | ALOG 10.0.0.2 1000000 1000100 POST /internal/v1/fulfillment-locations/L/reservations 201 k1
location-a-1  | ALOG 10.0.0.3 1000050 1000300 POST /internal/v1/fulfillment-locations/L/reservations 200 k1
location-a-1  | ALOG 10.0.0.2 2000000 2000100 POST /internal/v1/fulfillment-locations/L/reservations 201 k2
location-a-1  | ALOG 10.0.0.2 3000000 3000100 POST /internal/v1/fulfillment-locations/L/reservations 201 k3
location-a-1  | ALOG 10.0.0.2 4000000 4000100 POST /internal/v1/fulfillment-locations/L/reservations 201 k3
location-a-1  | ALOG 10.0.0.9 5000000 5000100 POST /internal/v1/fulfillment-locations/L/reservations 409 k2
location-a-1  | ALOG 10.0.0.2 6000000 6000100 POST /internal/v1/fulfillment-locations/L/reservations/cancel 200 k4
location-a-1  | ALOG 10.0.0.2 7000000 7000100 GET /actuator/health 200 -
"""


def selftest_calls():
    """Synthetic log with a known answer: k1 cross-instance and overlapping, k2 planted from a foreign source,
    k3 created twice (double 201). If a rule above is broken this fails, so a zero from the real logs means something."""
    got = analyse_calls(parse_calls(CALL_SELFTEST_LINES), {"10.0.0.2", "10.0.0.3"}, 0.0, None)
    want = {"calls": 6, "keys": 3, "dup_calls": 3, "xdup_keys": 1, "foreign_keys": 1, "double_201": 1, "overlap_keys": 1}
    bad = {k: (got[k], v) for k, v in want.items() if got[k] != v}
    if bad:
        raise SystemExit(f"self-test FAILED (got, want): {bad}")
    print("self-test ok")


DNS_HEADER = re.compile(r"^(\d+\.\d+) ")
DNS_HEXLINE = re.compile(r"^\s+0x[0-9a-f]+:\s+((?:[0-9a-f]{2,4}\s?)+)")


def tcpdump_packets(text):
    """Yield (epoch, bytes) for every packet in tcpdump -tt -xx text."""
    stamp, data = None, bytearray()
    for line in text.splitlines():
        header = DNS_HEADER.match(line)
        if header:
            if stamp is not None and data:
                yield stamp, bytes(data)
            stamp, data = float(header.group(1)), bytearray()
            continue
        hexline = DNS_HEXLINE.match(line)
        if hexline and stamp is not None:
            data += bytes.fromhex(hexline.group(1).replace(" ", ""))
    if stamp is not None and data:
        yield stamp, bytes(data)


def read_dns_name(message, offset):
    labels = []
    jumped = False
    end = offset
    hops = 0
    while True:
        if offset >= len(message) or hops > 20:
            return None, end
        length = message[offset]
        if length == 0:
            offset += 1
            if not jumped:
                end = offset
            break
        if length & 0xC0 == 0xC0:
            pointer = ((length & 0x3F) << 8) | message[offset + 1]
            if not jumped:
                end = offset + 2
            offset, jumped, hops = pointer, True, hops + 1
            continue
        labels.append(message[offset + 1:offset + 1 + length].decode("ascii", "replace").lower())
        offset += 1 + length
        if not jumped:
            end = offset
    return ".".join(labels), end


def decode_dns(stamp, packet):
    """Return a dict for a DNS message inside an Ethernet/IPv4/UDP frame, or None."""
    if len(packet) < 14 + 20 + 8 + 12 or packet[12:14] != b"\x08\x00":
        return None
    ihl = (packet[14] & 0x0F) * 4
    if packet[14 + 9] != 17:
        return None
    dns = packet[14 + ihl + 8:]
    flags = (dns[2] << 8) | dns[3]
    questions = (dns[4] << 8) | dns[5]
    answers = (dns[6] << 8) | dns[7]
    if questions < 1 or questions > 4:
        return None
    name, position = read_dns_name(dns, 12)
    if name is None or position + 4 > len(dns):
        return None
    qtype = (dns[position] << 8) | dns[position + 1]
    return {"t": stamp, "qr": flags >> 15, "rcode": flags & 0x0F, "name": name, "qtype": qtype, "answers": answers}


def dns_messages(text, name):
    for stamp, packet in tcpdump_packets(text):
        message = decode_dns(stamp, packet)
        if message and message["name"].startswith(name):
            yield message


def last_dns_success(events, before=None):
    times = [e["t"] for e in events if e["qr"] == 1 and e["rcode"] == 0 and e["answers"] > 0 and e["qtype"] == 1
             and (before is None or e["t"] <= before)]
    return max(times) if times else None


def resolver_truth(probe_text, begin):
    """From the separate-container probe lines "<epoch> ok|fail": when the resolver stopped answering after `begin` and when it answered again."""
    rows = [(float(parts[0]), parts[1]) for parts in (line.split() for line in probe_text.splitlines()) if len(parts) == 2 and parts[1] in ("ok", "fail")]
    failed = next((t for t, state in rows if t >= begin and state == "fail"), None)
    back = next((t for t, state in rows if failed is not None and t > failed and state == "ok"), None)
    return failed, back


def summarize_dns(events, fault, probe_text=""):
    """Lookups of order-postgres around a database outage, and the delay the JVM's name cache should cause.

    model_delay_s: the JVM queries only when its cache is empty. The first A query at or after the moment the resolver answers
    again (`resolver_back`) succeeds; the database accepts connections at `ready`; so the cache costs max(0, query - ready)."""
    begin, ready = fault["begin"], fault.get("ready") or fault["end"]
    before = last_dns_success(events, begin)
    answers = [e for e in events if e["qr"] == 1 and e["qtype"] == 1 and e["t"] > begin]
    nxdomain = [e["t"] for e in answers if e["rcode"] == 3]
    queries = [e["t"] for e in events if e["qr"] == 0 and e["qtype"] == 1]
    failed, back = resolver_truth(probe_text, begin)
    after_back = [t for t in queries if back is not None and t >= back - 0.05]
    return {
        "last_success_before_fault": before, "fault_offset_s": round(begin - before, 2) if before else None,
        "queries_after_fault": sum(1 for t in queries if t > begin), "nxdomain_count": len(nxdomain),
        "nxdomain_times_after_begin_s": [round(t - begin, 2) for t in nxdomain][:12],
        "resolver_failed_after_begin_s": round(failed - begin, 2) if failed else None,
        "resolver_back_after_begin_s": round(back - begin, 2) if back else None,
        "ready_after_begin_s": round(ready - begin, 2),
        "first_query_after_resolver_back_s": round(after_back[0] - begin, 2) if after_back else None,
        "model_delay_s": round(max(0.0, after_back[0] - ready), 2) if after_back else None,
    }


def sample_text(name="order-postgres"):
    """tcpdump -tt -xx text for one A query and one NXDOMAIN answer, built here so the decoder is checked against known bytes."""
    labels = b"".join(bytes([len(part)]) + part.encode() for part in name.split(".")) + b"\x00"

    def frame(flags):
        dns = b"\x12\x34" + flags.to_bytes(2, "big") + b"\x00\x01\x00\x00\x00\x00\x00\x00" + labels + b"\x00\x01\x00\x01"
        udp = (40000).to_bytes(2, "big") + (35000).to_bytes(2, "big") + (8 + len(dns)).to_bytes(2, "big") + b"\x00\x00" + dns
        ip = b"\x45\x00" + (20 + len(udp)).to_bytes(2, "big") + b"\x00\x00\x40\x00\x40\x11\x00\x00\x7f\x00\x00\x01\x7f\x00\x00\x0b" + udp
        return b"\x00" * 12 + b"\x08\x00" + ip

    out = []
    for stamp, flags in ((1795590000.123456, 0x0100), (1795590000.125, 0x8183)):
        out.append(f"{stamp:.6f} IP 127.0.0.1.40000 > 127.0.0.11.35000: UDP, length 40")
        data = frame(flags)
        for offset in range(0, len(data), 16):
            chunk = data[offset:offset + 16]
            out.append(f"\t0x{offset:04x}:  " + " ".join(chunk[i:i + 2].hex() for i in range(0, len(chunk), 2)))
    return "\n".join(out) + "\n"


def selftest_dns():
    """A query and an NXDOMAIN answer for order-postgres: decoding must find both and the rcode 3."""
    events = list(dns_messages(sample_text(), "order-postgres"))
    ok = (len(events) == 2 and events[0]["qr"] == 0 and events[1]["qr"] == 1 and events[1]["rcode"] == 3
          and events[1]["qtype"] == 1 and events[1]["answers"] == 0)
    if not ok:
        raise SystemExit(f"self-test FAILED: {events}")
    print("self-test ok")


def calls_command(args):
    ips = json.loads(Path(args.ips).read_text(encoding="utf-8")) if args.ips else {}
    app_ips = {ips[name] for name in ("app", "app2") if name in ips}
    window = None
    if args.fault and Path(args.fault).exists():
        fault = json.loads(Path(args.fault).read_text(encoding="utf-8"))
        window = (fault["begin"], fault["end"])
    result = {}
    for spec in args.log:  # name=path
        name, path = spec.split("=", 1)
        result[name] = analyse_calls(parse_calls(Path(path).read_text(encoding="utf-8", errors="replace")), app_ips,
                                     args.since, window, args.until)
    result["app_ips"] = sorted(app_ips)
    Path(args.out).write_text(json.dumps(result, indent=1), encoding="utf-8")
    return 0


def dns_command(args):
    text = Path(args.log).read_text(encoding="utf-8", errors="replace")
    events = list(dns_messages(text, args.name))
    if args.last_success:  # epoch of the last answered A lookup (used to align the fault with the JVM's cache)
        value = last_dns_success(events, args.before)
        print("" if value is None else f"{value:.6f}")
        return 0
    fault = json.loads(Path(args.fault).read_text(encoding="utf-8"))
    probe = Path(args.probe).read_text(encoding="utf-8") if args.probe and Path(args.probe).exists() else ""
    Path(args.out).write_text(json.dumps(summarize_dns(events, fault, probe), indent=1), encoding="utf-8")
    return 0


def selftest(args):
    selftest_calls()
    selftest_dns()
    return 0


def _json(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _lines(path):
    try:
        return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    except (OSError, ValueError):
        return []


def _first_201(requests, moment):
    ended = [r["ended"] for r in requests if r.get("http") == 201 and r["ended"] >= moment]
    return round(min(ended) - moment, 2) if ended else None


def _load(run_dir):
    try:
        found = re.search(r"load averages?:\s*([\d.]+)", (run_dir / "env-before.txt").read_text(encoding="utf-8"))
        return float(found.group(1)) if found else None
    except OSError:
        return None


def _deep_record(label, run_dir):
    result = _json(run_dir / "result.json", {})
    meta = _json(run_dir / "meta.json", {})
    fault = _json(run_dir / "fault.json", {})
    requests = _lines(run_dir / "requests.jsonl")
    record = {"label": label, "result": result, "pg": meta.get("order_postgres_log") or {}, "load": _load(run_dir),
              "calls": _json(run_dir / "calls.json", {}), "lease": _json(run_dir / "lease-claims.json", {}),
              "containers": meta.get("containers") or {}}
    exposure = run_dir / "exposure-leased.txt"
    record["exposure"] = int(exposure.read_text().split()[0]) if exposure.exists() and exposure.read_text().split() else None
    ready = fault.get("ready")
    if ready:
        record["first_201_after_ready"] = _first_201(requests, ready)
    lsn = _json(run_dir / "wal-lsn.json", {})
    if lsn.get("lsn_before") is not None and lsn.get("lsn_after") is not None:
        record["wal_mb"] = round((lsn["lsn_after"] - lsn["lsn_before"]) / 1048576, 1)
        record["segments"] = lsn["lsn_after"] // 16777216 - lsn["lsn_before"] // 16777216
    record["wal_container_running"] = fault.get("container_running_at_window_end")
    tcpdump = run_dir / "dns.tcpdump.txt"
    if tcpdump.exists() and fault:
        probe = run_dir / "dns-probe.txt"
        record["dns"] = summarize_dns(list(dns_messages(tcpdump.read_text(encoding="utf-8", errors="replace"), "order-postgres")), fault,
                                      probe.read_text(encoding="utf-8") if probe.exists() else "")
        record["delay_after_ready"] = _first_201(requests, ready) if ready else None
    return record


def _spread(values):
    values = [v for v in values if v is not None]
    return "-" if not values else f"{min(values):g} / {statistics.median(values):g} / {max(values):g}"


def deep_command(args):
    groups = collections.defaultdict(list)
    for run_dir in sorted(Path(args.root).glob("rep-*/*/run-*")):
        label = run_dir.parent.name
        if not label.startswith("c-") and not (run_dir / "result.json").exists():
            continue  # still running or failed to start
        if label.startswith("c-"):  # one stack run holds the five same-key variants
            for variant in ("c1", "c2", "c3", "neg", "seq"):
                vdir = run_dir / variant
                if not vdir.exists():
                    continue
                record = _deep_record(f"{label}:{variant}", vdir)
                pairs = collections.defaultdict(list)
                for request in _lines(vdir / "requests.jsonl"):
                    pairs[request["pair"]].append(request)
                record["pairs"] = pairs
                record["calls"] = _json(run_dir / variant / "calls.json", {})
                groups[f"{label}:{variant}"].append(record)
            continue
        groups[label].append(_deep_record(label, run_dir))
    def count(runs, name):
        return sum(1 for r in runs if (r["result"].get("invariants") or {}).get(name))
    print("| condition | n | all four | lost 0 | counts equal | no duplicates | converged | load avg before (min / med / max) |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(groups):
        runs = groups[label]
        print(f"| {label} | {len(runs)} | {sum(1 for r in runs if r['result'].get('pass_all'))} | {count(runs, 'lost_accepted_zero')} | "
              f"{count(runs, 'counts_equal')} | {count(runs, 'no_duplicates')} | {count(runs, 'converged')} | {_spread([r['load'] for r in runs])} |")
    print("\n(a) duplicate remote calls, min / median / max over runs\n")
    print("| condition | n | exposure | takeover claims | A dup_calls | A xdup_keys | A overlap | A double_201 | B dup_calls | B xdup_keys | B double_201 | planted seen (A foreign) | converge s |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(g for g in groups if g.startswith("a-")):
        runs = groups[label]
        def c(side, field):
            return _spread([(r["calls"].get(side) or {}).get(field) for r in runs])
        print(f"| {label} | {len(runs)} | {_spread([r['exposure'] for r in runs])} | {_spread([r['lease'].get('takeover_claims') for r in runs])} | "
              f"{c('a', 'dup_calls')} | {c('a', 'xdup_keys')} | {c('a', 'overlap_keys')} | {c('a', 'double_201')} | {c('b', 'dup_calls')} | {c('b', 'xdup_keys')} | "
              f"{c('b', 'double_201')} | {c('a', 'foreign_keys')} | {_spread([r['result'].get('converge_seconds') for r in runs])} |")
    print("\n(b) pg_wal only\n")
    print("| condition | n | PANIC lines | No-space lines | crash restarts | order DB container exited | WAL MB in window | segments crossed | first 201 after ready s |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(g for g in groups if g.startswith("b-")):
        runs = groups[label]
        def p(field):
            return _spread([r["pg"].get(field) for r in runs])
        print(f"| {label} | {len(runs)} | {p('panic')} | {p('no_space_left')} | {p('crash_restart')} | "
              f"{sum(1 for r in runs if r.get('wal_container_running') is False)}/{len(runs)} | {_spread([r.get('wal_mb') for r in runs])} | "
              f"{_spread([r.get('segments') for r in runs])} | {_spread([r.get('first_201_after_ready') for r in runs])} |")
    print("\n(c) same-key pairs\n")
    print("| variant | stacks | pairs | exactly one 201 and 200/202 | two 201 | overlapping pairs | order / A / B reserved | A xdup keys | A double_201 | all four |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(g for g in groups if g.startswith("c-")):
        runs = groups[label]
        total = sum(len(r["pairs"]) for r in runs)
        good = two = overlap = 0
        for r in runs:
            for pair in r["pairs"].values():
                statuses = [x["http"] for x in pair]
                good += statuses.count(201) == 1 and all(s in (200, 201, 202) for s in statuses)
                two += statuses.count(201) >= 2
                overlap += max(x["sent"] for x in pair) < min(x["ended"] for x in pair)
        reserved = ", ".join("{}/{}/{}".format(*[(r["result"].get("equal_values") or {}).get(k) for k in
                             ("order_reserved", "location_a_reservations", "location_b_reservations")]) for r in runs)
        print(f"| {label.split(':')[1]} | {len(runs)} | {total} | {good} | {two} | {overlap} | {reserved} | "
              f"{_spread([(r['calls'].get('a') or {}).get('xdup_keys') for r in runs])} | {_spread([(r['calls'].get('a') or {}).get('double_201') for r in runs])} | "
              f"{sum(1 for r in runs if r['result'].get('pass_all'))}/{len(runs)} |")
    print("\n(d) DNS: delay after the database accepts connections (each run), against the cache model\n")
    print("| condition | n | offset from last lookup s | NXDOMAIN answers | model delay s (each) | measured first 201 after ready s (each) |")
    print("| --- | --- | --- | --- | --- | --- |")
    for label in sorted(g for g in groups if g.startswith("d-")):
        runs = groups[label]
        def each(key):
            return ", ".join(str(r.get("dns", {}).get(key)) for r in runs)
        print(f"| {label} | {len(runs)} | {_spread([r.get('dns', {}).get('fault_offset_s') for r in runs])} | {_spread([r.get('dns', {}).get('nxdomain_count') for r in runs])} | "
              f"{each('model_delay_s')} | {', '.join(str(r.get('delay_after_ready')) for r in runs)} |")
    print("\n(e) network and pause cells\n")
    print("| condition | n | 201 / 202 / 5xx / 0 per run | max latency s | converge s | B calls begun in window |")
    print("| --- | --- | --- | --- | --- | --- |")
    for label in sorted(g for g in groups if g.startswith("e-")):
        runs = groups[label]
        def split(r):
            status = r["result"].get("http_status") or {}
            five = sum(v for k, v in status.items() if k.startswith("5"))
            return f"{status.get('201', 0)}/{status.get('202', 0)}/{five}/{status.get('0', 0)}"
        print(f"| {label} | {len(runs)} | {'; '.join(split(r) for r in runs)} | {_spread([(r['result'].get('latency_s') or {}).get('max') for r in runs])} | "
              f"{_spread([r['result'].get('converge_seconds') for r in runs])} | {_spread([(r['calls'].get('b') or {}).get('in_window_calls') for r in runs])} |")
    return 0

def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    audit_parser = sub.add_parser("audit")
    audit_parser.add_argument("--run-id", required=True)
    audit_parser.add_argument("--requests", required=True)
    audit_parser.add_argument("--order-container", required=True)
    audit_parser.add_argument("--location-a-container", required=True)
    audit_parser.add_argument("--location-b-container", required=True)
    audit_parser.add_argument("--since", type=float, required=True,
                              help="epoch seconds; convergence time is measured from here")
    audit_parser.add_argument("--converge-timeout", type=float, default=90.0)
    audit_parser.add_argument("--fault-file", default="")
    audit_parser.add_argument("--replay", default="", help="replay.jsonl of a same-key retry run")
    audit_parser.add_argument("--out", required=True)
    audit_parser.set_defaults(handler=audit)
    summarize_parser = sub.add_parser("summarize")
    summarize_parser.add_argument("--results", required=True)
    summarize_parser.set_defaults(handler=summarize)
    calls_parser = sub.add_parser("calls")
    calls_parser.add_argument("--log", action="append", required=True, help="name=path of a location's access log, repeatable")
    calls_parser.add_argument("--ips", help="JSON {app, app2: container IP}")
    calls_parser.add_argument("--since", type=float, default=0.0)
    calls_parser.add_argument("--until", type=float)
    calls_parser.add_argument("--fault")
    calls_parser.add_argument("--out", required=True)
    calls_parser.set_defaults(handler=calls_command)
    dns_parser = sub.add_parser("dns")
    dns_parser.add_argument("--log", required=True, help="tcpdump -tt -xx output of the application's namespace")
    dns_parser.add_argument("--name", default="order-postgres")
    dns_parser.add_argument("--last-success", action="store_true")
    dns_parser.add_argument("--before", type=float)
    dns_parser.add_argument("--fault")
    dns_parser.add_argument("--probe", help="resolver probe lines '<epoch> ok|fail' from a separate container")
    dns_parser.add_argument("--out")
    dns_parser.set_defaults(handler=dns_command)
    deep_parser = sub.add_parser("deep")
    deep_parser.add_argument("--root", required=True)
    deep_parser.set_defaults(handler=deep_command)
    selftest_parser = sub.add_parser("selftest")
    selftest_parser.set_defaults(handler=selftest)
    args = parser.parse_args()
    sys.exit(args.handler(args))


if __name__ == "__main__":
    main()
