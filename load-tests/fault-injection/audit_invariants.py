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
    return {"max": round(max(values), 2), "p50": round(percentile(values, 0.5), 3),
            "p95": round(percentile(values, 0.95), 3)} if values else {}


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
            first = tries[0]["sent"]
            # the workflow existed before the first retry if its row is older than that retry
            if created_by_key.get(request["key"], first + 1) < first - 0.05 and state_by_key.get(request["key"]):
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
          "p95 request latency |")
    print("| --- | --- | --- | --- | --- |")
    for condition, runs in by_condition.items():
        print(f"| {condition} | "
              f"{spread([r.get('recovery', {}).get('first_201_after_fault_end_s') for r in runs])} | "
              f"{spread([r.get('recovery', {}).get('last_failure_after_fault_end_s') for r in runs])} | "
              f"{spread([r.get('latency_s', {}).get('max') for r in runs])} | "
              f"{spread([r.get('latency_s', {}).get('p95') for r in runs])} |")
    probes = {c: [r for r in runs if r.get("tail_probe")] for c, runs in by_condition.items()}
    if any(probes.values()):
        print()
        print("convergence tail: the first look after the load that still saw non-terminal workflows "
              "(lease_until and next_attempt_at as seconds from the database clock), and how well "
              "'converged when the longest lease expired' predicts the observed time")
        print()
        print("| condition | runs with a tail probe / n | non-terminal at first look | due (next_attempt_at<=now) | "
              "leased | max lease remaining s | max next_attempt remaining s | observed converge s | "
              "observed minus lease prediction s |")
        print("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for condition, runs in by_condition.items():
            found = probes[condition]
            if not found:
                continue
            print(f"| {condition} | {len(found)}/{len(runs)} | "
                  f"{spread([r['tail_probe']['non_terminal'] for r in found])} | "
                  f"{spread([r['tail_probe']['due'] for r in found])} | "
                  f"{spread([r['tail_probe']['leased'] for r in found])} | "
                  f"{spread([r['tail_probe'].get('lease_remaining_max_s') for r in found])} | "
                  f"{spread([r['tail_probe'].get('next_attempt_remaining_max_s') for r in found])} | "
                  f"{spread([r['tail_probe'].get('observed_converge_s') for r in found])} | "
                  f"{spread([r['tail_probe'].get('observed_minus_lease_prediction_s') for r in found])} |")
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
    args = parser.parse_args()
    sys.exit(args.handler(args))


if __name__ == "__main__":
    main()
