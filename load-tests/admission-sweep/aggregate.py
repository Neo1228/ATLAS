#!/usr/bin/env python3
"""EXP-086 jsonl(셀당 1줄)을 (K, TTL) 칸별 표로 묶는다.

사용: aggregate.py <sweep.jsonl> [metric ...]
칸마다 시드 n개의 평균 [최소-최대]를 적는다. 불변식 칸(oversell, queueOrderViolations,
slotLimitExceeded, errors, unresolved)은 시드 전체 합을 적는다.
"""
import json
import statistics
import sys
from collections import defaultdict

DEFAULT_METRICS = [
    "accepted", "sold_out", "lost_queue_expired", "lost_slow_start", "abandoned_before_start",
    "abandoned_after_start", "tickets", "expiredWaitingTickets", "expiredHoldingTickets",
    "slotWastePercent", "slotShare_abandoned_after_start", "slotShare_abandoned_before_start",
    "slotShare_sold_out", "slotShare_slow_start", "waitToSlotAcceptedP50S", "waitToSlotAcceptedP95S", "lastAcceptedVirtualS",
]
INVARIANTS = ["oversell", "queueOrderViolations", "slotLimitExceeded", "errors", "unresolved"]


def load(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                row = json.loads(line)
                total = row["slotSecondsTotal"]
                row["slotWastePercent"] = (
                    round(100.0 * (total - row["slotSecondsProductive"]) / total, 1) if total else 0.0)
                for key in [k for k in row if k.startswith("slotSec_")]:
                    row["slotShare_" + key[len("slotSec_"):]] = round(100.0 * row[key] / total, 1) if total else 0.0
                rows.append(row)
    return rows


def cell(values, integer):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    mean = statistics.mean(values)
    low, high = min(values), max(values)
    if integer:
        return f"{mean:.1f} [{low:.0f}-{high:.0f}]"
    return f"{mean:.1f} [{low:.1f}-{high:.1f}]"


def main():
    path = sys.argv[1]
    metrics = sys.argv[2:] or DEFAULT_METRICS
    rows = load(path)
    groups = defaultdict(list)
    for row in rows:
        groups[(row["k"], row["ttlSeconds"])].append(row)
    ks = sorted({k for k, _ in groups})
    ttls = sorted({t for _, t in groups})
    seeds = sorted({r["seed"] for r in rows})
    print(f"셀 {len(groups)}개, 셀당 시드 n={sorted({len(v) for v in groups.values()})}, 시드 {seeds}, 총 {len(rows)}회")
    sample = rows[0]
    print(f"구매자 {sample['buyers']}명, 재고 {sample['stock']}, 시작 전 포기 {sample.get('abandonBefore')}, 시작 후 포기 {sample.get('abandonAfter')}")
    for metric in metrics:
        integer = not metric.startswith("slotShare_") and metric != "slotWastePercent" and not metric.endswith("S")
        print(f"\n### {metric} (평균 [최소-최대], n=시드)")
        print("| K \\ TTL | " + " | ".join(f"{t}s" for t in ttls) + " |")
        print("|---|" + "---|" * len(ttls))
        for k in ks:
            cells = []
            for t in ttls:
                cells.append(cell([r.get(metric) for r in groups[(k, t)]], integer))
            print(f"| {k} | " + " | ".join(cells) + " |")
    print("\n### 불변식 (시드 전체 합)")
    print("| K | TTL | " + " | ".join(INVARIANTS) + " | accepted<=stock 위반 칸 |")
    print("|---|---|" + "---|" * (len(INVARIANTS) + 1))
    for k in ks:
        for t in ttls:
            cellrows = groups[(k, t)]
            sums = [sum(int(r.get(name, 0)) for r in cellrows) for name in INVARIANTS]
            bad = sum(1 for r in cellrows if not r["acceptedEqualsStockOrFewer"])
            print(f"| {k} | {t}s | " + " | ".join(str(x) for x in sums) + f" | {bad} |")
    total = {name: sum(int(r.get(name, 0)) for r in rows) for name in INVARIANTS}
    print(f"\n전체 합: {total}")


if __name__ == "__main__":
    main()
