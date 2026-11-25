#!/usr/bin/env python3
"""Sample the relay while a fault runs, audit one run against the topic, and summarize many runs.

sample     once per interval: highest event_seq in the log and the relay cursor (until SIGTERM)
audit      wait for the relay to drain, read the whole topic, compare it with the event log table
summarize  read every run's result.json under a results directory and print per-condition tables

Invariants (one violation is a finding, not noise):
  lost == 0                         every (workflow_id, aggregate_version) the run wrote is in the topic
  unexpected == 0                   no topic message that has no row in the event log
  first_delivery_violations == 0    per workflow, the versions in order of first delivery rise strictly
  partition_changes == 0            a workflow's messages all sit in one partition
  cursor_advanced_while_down == 0   the cursor does not move while the broker cannot answer
  drained                           the cursor reaches the log end within the drain timeout
Duplicates, redelivery after a higher version and drain time are reported, not asserted.
"""
import argparse
import collections
import json
import re
import signal
import statistics
import subprocess
import sys
import time
from pathlib import Path

RELAY_GROUP = "kafka-relay"
CURSOR_SQL = (f"(SELECT last_event_seq FROM order_inventory_event_cursor "
              f"WHERE consumer_group = '{RELAY_GROUP}')")
LINE = re.compile(r"^Partition:(\d+)\tOffset:(\d+)\t.*?(\{.*\})\s*$")


def psql(container, user, database, sql, timeout=20):
    """Return rows as lists of strings, or None when the database does not answer."""
    try:
        done = subprocess.run(
            ["docker", "exec", container, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", user,
             "-d", database, "-At", "-F", "|", "-c", sql],
            capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return None
    if done.returncode != 0:
        return None
    return [line.split("|") for line in done.stdout.splitlines() if line.strip()]


def progress(pg, user, database):
    """(highest event_seq, cursor event_seq, events behind the cursor) or None."""
    rows = psql(pg, user, database,
                f"SELECT coalesce(max(event_seq), 0), coalesce({CURSOR_SQL}, 0), "
                f"count(*) FILTER (WHERE event_seq > coalesce({CURSOR_SQL}, 0)) "
                f"FROM order_inventory_event")
    if not rows:
        return None
    return int(rows[0][0]), int(rows[0][1]), int(rows[0][2])


def sample(args):
    stop = []
    signal.signal(signal.SIGTERM, lambda *_: stop.append(1))
    signal.signal(signal.SIGINT, lambda *_: stop.append(1))
    with open(args.out, "w") as handle:
        handle.write("ts,max_seq,cursor_seq,lag\n")
        while not stop:
            now = time.time()
            row = progress(args.pg, args.user, args.database)
            if row:
                handle.write(f"{now:.3f},{row[0]},{row[1]},{row[2]}\n")
                handle.flush()
            time.sleep(max(0.0, args.interval - (time.time() - now)))
    return 0


def topic_messages(kafka, topic, timeout_s=90):
    """Read the whole topic. Returns (messages, expected_count); fails if the counts differ."""
    offsets = subprocess.run(
        ["docker", "exec", kafka, "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server",
         "localhost:9092", "--topic", topic, "--time", "-1"],
        capture_output=True, text=True, timeout=60, check=False)
    expected = sum(int(line.rsplit(":", 1)[1]) for line in offsets.stdout.splitlines()
                   if line.count(":") == 2)
    messages = []
    if expected:
        consumer = subprocess.run(
            ["docker", "exec", kafka, "/opt/kafka/bin/kafka-console-consumer.sh",
             "--bootstrap-server", "localhost:9092", "--topic", topic, "--from-beginning",
             "--max-messages", str(expected), "--timeout-ms", str(timeout_s * 1000),
             "--property", "print.partition=true", "--property", "print.offset=true",
             "--property", "print.key=true", "--property", "print.headers=true",
             "--property", "print.timestamp=false"],
            capture_output=True, text=True, timeout=timeout_s + 60, check=False)
        for line in consumer.stdout.splitlines():
            match = LINE.match(line)
            if match:
                payload = json.loads(match.group(3))
                messages.append((int(match.group(1)), int(match.group(2)), payload))
    return messages, expected


def window_advance(samples, begin, end):
    """Cursor movement between the first sample at/after begin and the last at/before end."""
    inside = [s for s in samples if begin <= s[0] <= end]
    if len(inside) < 2:
        return None
    return inside[-1][2] - inside[0][2]


def read_samples(path):
    rows = []
    for line in Path(path).read_text().splitlines()[1:]:
        parts = line.split(",")
        if len(parts) == 4:
            rows.append((float(parts[0]), int(parts[1]), int(parts[2]), int(parts[3])))
    return rows


def delivery_order(per_key):
    """Per workflow, count first deliveries that arrive below an earlier first delivery, and
    messages that arrive below a version already seen (a re-delivery after a higher version).
    per_key maps a workflow to its versions in partition-offset order."""
    first_violations = 0
    redelivered_after_higher = 0
    for versions in per_key.values():
        seen, highest, last_first = set(), 0, 0
        for version in versions:
            if version < highest:
                redelivered_after_higher += 1
            if version not in seen:
                seen.add(version)
                if version <= last_first:
                    first_violations += 1
                last_first = max(last_first, version)
            highest = max(highest, version)
    return first_violations, redelivered_after_higher


def audit(args):
    plan = json.loads(Path(args.plan).read_text())
    fault = json.loads(Path(args.fault).read_text())
    prefix = plan["key_prefix"]
    pg, user, database = args.pg, args.user, args.database

    run_rows = psql(pg, user, database,
                    "SELECT e.workflow_id, e.aggregate_version, e.event_seq "
                    "FROM order_inventory_event e JOIN order_inventory_reservation_workflow w "
                    f"USING (workflow_id) WHERE w.idempotency_key LIKE '{prefix}%'")
    if run_rows is None:
        raise SystemExit("order database did not answer")
    expected = {(row[0], int(row[1])): int(row[2]) for row in run_rows}
    max_run_seq = max(expected.values(), default=0)
    all_rows = psql(pg, user, database,
                    "SELECT workflow_id, aggregate_version FROM order_inventory_event") or []
    all_db = {(row[0], int(row[1])) for row in all_rows}
    gap_rows = psql(pg, user, database,
                    "SELECT coalesce(max(extract(epoch FROM gap)), 0) FROM ("
                    "SELECT e.occurred_at - lag(e.occurred_at) OVER (ORDER BY e.event_seq) AS gap "
                    "FROM order_inventory_event e JOIN order_inventory_reservation_workflow w "
                    f"USING (workflow_id) WHERE w.idempotency_key LIKE '{prefix}%') g")
    max_gap = float(gap_rows[0][0]) if gap_rows else None

    # wait for the relay to reach the end of the run's events
    producer_end = plan["ended_at"]
    deadline = time.time() + args.drain_timeout
    drained_at = None
    while time.time() < deadline:
        row = progress(pg, user, database)
        if row and row[1] >= max_run_seq and row[2] == 0:
            drained_at = time.time()
            break
        time.sleep(0.5)
    recovery_end = max(fault.get("end", 0), producer_end)
    drain_seconds = round(drained_at - recovery_end, 1) if drained_at else None

    time.sleep(1)  # let in-flight acks settle before the topic is read
    messages, topic_total = topic_messages(args.kafka, args.topic)
    read_complete = len(messages) == topic_total and topic_total > 0

    copies = collections.defaultdict(list)       # (workflow, version) -> [(partition, offset)]
    per_key = collections.defaultdict(list)      # workflow -> [(offset-ordered versions)]
    key_partitions = collections.defaultdict(set)
    unexpected = 0
    id_mismatch = 0
    all_distinct = set()
    for partition, offset, payload in sorted(messages, key=lambda m: (m[0], m[1])):
        workflow, version = payload["workflowId"], payload["aggregateVersion"]
        point = (workflow, version)
        all_distinct.add(point)
        if point not in all_db:
            unexpected += 1
        if payload["eventId"] != f"{workflow}:{version}":
            id_mismatch += 1
        if point in expected:
            copies[point].append((partition, offset))
            per_key[workflow].append(version)
            key_partitions[workflow].add(partition)

    # evidence for a finding: this run's messages in partition/offset order
    with open(Path(args.out).with_name("topic-run.tsv"), "w") as handle:
        handle.write("partition\toffset\teventSeq\tworkflowId\taggregateVersion\n")
        for partition, offset, payload in sorted(messages, key=lambda m: (m[0], m[1])):
            if (payload["workflowId"], payload["aggregateVersion"]) in expected:
                handle.write(f"{partition}\t{offset}\t{payload['eventSeq']}\t"
                             f"{payload['workflowId']}\t{payload['aggregateVersion']}\n")

    lost = sorted(point for point in expected if point not in copies)
    extra = sum(len(c) - 1 for c in copies.values())
    keys_with_dups = len({point[0] for point, c in copies.items() if len(c) > 1})
    max_copies = max((len(c) for c in copies.values()), default=0)
    first_violations, redelivered_after_higher = delivery_order(per_key)
    partition_changes = sum(1 for parts in key_partitions.values() if len(parts) > 1)
    missing_total = len(all_db - all_distinct)

    samples = read_samples(args.samples) if Path(args.samples).exists() else []
    cursor_decreases = sum(1 for a, b in zip(samples, samples[1:]) if b[2] < a[2])
    advanced = None
    if fault.get("down_begin") and fault.get("down_end"):
        advanced = window_advance(samples, fault["down_begin"] + args.settle, fault["down_end"])
    final = progress(pg, user, database)

    result = {
        "condition": fault.get("fault"), "run_id": plan["run_id"],
        "planned_workflows": plan["workflows"], "planned_events": plan["planned_events"],
        "db_events": len(expected), "max_event_gap_s": round(max_gap, 2) if max_gap is not None else None,
        "topic_copies": sum(len(c) for c in copies.values()), "topic_distinct": len(copies),
        "lost": len(lost), "lost_sample": [f"{w}:{v}" for w, v in lost[:5]],
        "unexpected": unexpected, "event_id_mismatch": id_mismatch,
        "duplicates": extra, "keys_with_duplicates": keys_with_dups, "max_copies": max_copies,
        "first_delivery_violations": first_violations,
        "redelivered_after_higher": redelivered_after_higher,
        "partition_changes": partition_changes,
        "topic_total_messages": topic_total, "topic_read_complete": read_complete,
        "lost_total_all_runs": missing_total,
        "cursor_decreases": cursor_decreases, "cursor_advanced_while_down": advanced,
        "drained": drained_at is not None, "drain_seconds_after_recovery": drain_seconds,
        "final_max_seq": final[0] if final else None, "final_cursor": final[1] if final else None,
        "final_lag": final[2] if final else None,
        "peak_lag": max((s[3] for s in samples), default=None),
        "producer_psql_exit": plan["psql_exit"], "producer_max_tick_lateness_s": plan["max_tick_lateness_s"],
        "fault_down_seconds": round(fault["down_end"] - fault["down_begin"], 1)
        if fault.get("down_begin") and fault.get("down_end") else None,
    }
    result["passed"] = bool(
        read_complete and result["lost"] == 0 and unexpected == 0 and first_violations == 0
        and partition_changes == 0 and id_mismatch == 0 and result["drained"]
        and result["db_events"] == result["planned_events"] and cursor_decreases == 0
        and (advanced in (None, 0)) and missing_total == 0)
    Path(args.out).write_text(json.dumps(result, indent=1))
    print(json.dumps({k: result[k] for k in (
        "condition", "run_id", "db_events", "topic_distinct", "lost", "duplicates",
        "first_delivery_violations", "drain_seconds_after_recovery", "passed")}))
    return 0


def spread(values):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    return f"{min(values):g} / {statistics.median(values):g} / {max(values):g}"


def summarize(args):
    by_condition = collections.OrderedDict()
    for path in sorted(Path(args.results).glob("*/run-*/result.json")):
        data = json.loads(path.read_text())
        by_condition.setdefault(path.parent.parent.name, []).append(data)
    print("| condition | n | passed | events produced (per run) | in topic, distinct (per run) | "
          "lost (sum, max) | duplicates min / median / max | max copies | first-delivery violations (sum) "
          "| redelivered after higher min / median / max | cursor moved while down (max) "
          "| drain s after recovery min / median / max |")
    print("|" + " --- |" * 12)
    for condition, runs in by_condition.items():
        passed = sum(1 for r in runs if r["passed"])
        lost = [r["lost"] for r in runs]
        print("| {} | {} | {} | {} | {} | {}, {} | {} | {} | {} | {} | {} | {} |".format(
            condition, len(runs), passed, spread([r["planned_events"] for r in runs]),
            spread([r["topic_distinct"] for r in runs]), sum(lost), max(lost),
            spread([r["duplicates"] for r in runs]), max(r["max_copies"] for r in runs),
            sum(r["first_delivery_violations"] for r in runs),
            spread([r["redelivered_after_higher"] for r in runs]),
            max((r["cursor_advanced_while_down"] or 0) for r in runs),
            spread([r["drain_seconds_after_recovery"] for r in runs])))
    return 0


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("sample", "audit"):
        p = sub.add_parser(name)
        p.add_argument("--pg", required=True)
        p.add_argument("--user", default="atlas")
        p.add_argument("--database", default="atlas_order")
    s = sub.choices["sample"]
    s.add_argument("--out", required=True)
    s.add_argument("--interval", type=float, default=1.0)
    a = sub.choices["audit"]
    a.add_argument("--kafka", required=True)
    a.add_argument("--topic", required=True)
    a.add_argument("--plan", required=True)
    a.add_argument("--fault", required=True)
    a.add_argument("--samples", required=True)
    a.add_argument("--out", required=True)
    a.add_argument("--drain-timeout", type=int, default=90)
    a.add_argument("--settle", type=float, default=2.0,
                   help="seconds after the broker became unreachable before cursor movement counts")
    m = sub.add_parser("summarize")
    m.add_argument("results")
    args = parser.parse_args()
    return {"sample": sample, "audit": audit, "summarize": summarize}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
