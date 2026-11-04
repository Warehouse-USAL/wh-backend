"""Turns load/results/*.json (k6 --summary-export), stats-*.csv (docker stats) and *.jsonl (streams.py) into summary.md."""
import csv
import glob
import json
import os
import re
import sys
from collections import defaultdict

RES = sys.argv[1] if len(sys.argv) > 1 else "/results"


def mib(text):
    m = re.match(r"([\d.]+)\s*([KMG]i?B)", text)
    if not m:
        return 0.0
    v, unit = float(m.group(1)), m.group(2)
    return v * {"KiB": 1 / 1024, "KB": 1 / 1024, "MiB": 1, "MB": 1, "GiB": 1024, "GB": 1024}[unit]


out = ["# Load test summary", ""]
for path in sorted(glob.glob(f"{RES}/k6-*.json")):
    name = os.path.basename(path)[3:-5]
    data = json.load(open(path))
    m = data.get("metrics", {})
    out += [f"## {name}", ""]
    reqs = m.get("http_reqs", {})
    failed = m.get("http_req_failed", {})
    out.append(f"requests: {int(reqs.get('count', 0))} ({reqs.get('rate', 0):.1f}/s), failed: {failed.get('value', 0) * 100:.2f}%")
    out += ["", "| request | count | avg ms | p50 | p95 | p99 | max |", "|---|---|---|---|---|---|---|"]
    for key, val in sorted(m.items()):
        if key.startswith("http_req_duration{name:"):
            n = key[len("http_req_duration{name:"):-1]
            out.append(f"| {n} | | {val.get('avg', 0):.0f} | {val.get('med', 0):.0f} | {val.get('p(95)', 0):.0f} | {val.get('p(99)', 0):.0f} | {val.get('max', 0):.0f} |")
    d = m.get("http_req_duration")
    if d:
        out.append(f"| ALL | {int(reqs.get('count', 0))} | {d.get('avg', 0):.0f} | {d.get('med', 0):.0f} | {d.get('p(95)', 0):.0f} | {d.get('p(99)', 0):.0f} | {d.get('max', 0):.0f} |")
    for key in sorted(k for k in m if k.startswith(("orders_", "scarce_status_"))):
        if key in m:
            out.append(f"\n{key}: {int(m[key].get('count', 0))}")
    th = [(k, v) for k, v in {**{k: x.get("thresholds", {}) for k, x in m.items()}}.items() if v]
    bad = [f"{k}: {t}" for k, v in th for t, ok in v.items() if ok is True or ok == "true"]  # in the export, true means the threshold failed
    out.append("\nthresholds: " + ("ALL PASSED" if th and not bad else "FAILED: " + "; ".join(bad) if bad else "none"))
    out.append("")

for path in sorted(glob.glob(f"{RES}/streams-*.jsonl")):
    out += [f"## {os.path.basename(path)[8:-6]}", ""]
    for line in open(path):
        line = line.strip()
        if line.startswith("{"):
            out += ["```json", line, "```"]
    out.append("")

stats = defaultdict(lambda: defaultdict(lambda: {"cpu": [], "mem": []}))
for path in sorted(glob.glob(f"{RES}/stats-*.csv")):
    scenario = os.path.basename(path)[6:-4]
    for row in csv.reader(open(path)):
        if len(row) == 3:
            c = re.sub(r"^[^-]+-|-\d+$", "", row[0])
            stats[scenario][c]["cpu"].append(float(row[1].strip("%") or 0))
            stats[scenario][c]["mem"].append(mib(row[2].split("/")[0]))
if stats:
    out += ["## Container CPU and memory per scenario (docker stats, every 2 s)", "",
            "| scenario | container | CPU avg % | CPU max % | mem max MiB |", "|---|---|---|---|---|"]
    for sc, cs in stats.items():
        for c, v in sorted(cs.items()):
            if c in ("backend", "mongodb", "suggestions", "suggestions-redis", "redpanda", "victoriametrics"):
                out.append(f"| {sc} | {c} | {sum(v['cpu']) / len(v['cpu']):.0f} | {max(v['cpu']):.0f} | {max(v['mem']):.0f} |")
open(f"{RES}/summary.md", "w").write("\n".join(out) + "\n")
print("\n".join(out))
