#!/usr/bin/env python3
"""Regenerate the auto-updated README sections and shields endpoint badges.

Reads:
  - JUnit XML test results under **/build/test-results/**/*.xml (test counts)
  - committed regression baselines under <module>/baselines/*.json (benchmark numbers)

Writes:
  - README.md     — the content between the AUTOGEN:tests and AUTOGEN:benchmarks markers
  - .github/badges/tests.json, .github/badges/benchmarks.json — shields.io endpoint badges

Run from the repository root: python3 .github/scripts/update_readme.py
Stdlib only; safe to run repeatedly (idempotent given the same inputs).
"""

import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.getcwd()
README = os.path.join(ROOT, "README.md")
BADGE_DIR = os.path.join(ROOT, ".github", "badges")


# ---- helpers ---------------------------------------------------------------

def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def pct(x):
    return f"{round(x * 100)}%"


def mrr(x):
    return f"{x:.2f}"


def ns_to_ms(ns):
    return f"{ns / 1e6:.1f} ms"


def ns_to_us(ns):
    return f"{ns / 1e3:.1f} µs"


def ns_to_s(ns):
    return f"{ns / 1e9:.2f} s"


def ns_smart(ns):
    if ns >= 1e6:
        return ns_to_ms(ns)
    if ns >= 1e3:
        return ns_to_us(ns)
    return f"{round(ns)} ns"


# ---- test counts -----------------------------------------------------------

def count_tests():
    tests = failures = errors = skipped = suites = 0
    for f in glob.glob("**/build/test-results/**/*.xml", recursive=True):
        try:
            r = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        if r.tag != "testsuite":
            continue
        suites += 1
        tests += int(r.get("tests", 0))
        failures += int(r.get("failures", 0))
        errors += int(r.get("errors", 0))
        skipped += int(r.get("skipped", 0))
    passing = tests - failures - errors - skipped
    return dict(suites=suites, tests=tests, passing=passing,
                failed=failures + errors, skipped=skipped)


# ---- benchmark numbers from baselines --------------------------------------

def benchmark_rows():
    """Return (markdown table rows, badge message) from the committed baselines."""
    cq = load_json("lang-jdt/baselines/completion-quality.json") or {}
    cl = load_json("lang-jdt/baselines/completion-latency.json") or {}
    iq = load_json("index-impl/baselines/index-quality.json") or {}
    ip = load_json("index-impl/baselines/index-perf.json") or {}
    bl = load_json("build-engine/baselines/build-largeproject.json") or {}

    rows = []

    if cq:
        rows.append((
            "Java completion **quality**", "recall / top-1 / MRR",
            f"**{pct(cq['overall.recall'])}** / {pct(cq['overall.top1'])} / {mrr(cq['overall.mrr'])}",
        ))
    if cl:
        rows.append((
            "Java completion **latency** (per keystroke)", "member access / type ref",
            f"**{ns_to_ms(cl['latency.member-access.ns'])}** / {ns_to_ms(cl['latency.type-ref.ns'])}",
        ))
    if iq:
        rows.append((
            "Symbol **index** quality", "recall / top-1 / MRR",
            f"**{pct(iq['overall.recall'])}** / {pct(iq['overall.top1'])} / {mrr(iq['overall.mrr'])}",
        ))
    if ip:
        prefix = next((v for k, v in sorted(ip.items()) if k.startswith("query.prefix.") and k.endswith(".ns")), None)
        fuzzy = next((v for k, v in sorted(ip.items()) if k.startswith("query.fuzzy.") and k.endswith(".ns")), None)
        if prefix is not None and fuzzy is not None:
            rows.append((
                "Symbol **index** query", "prefix / fuzzy",
                f"~{ns_smart(prefix)} / ~{ns_smart(fuzzy)}",
            ))
    if bl:
        modules = bl.get("modules", "?")
        rows.append((
            f"**Build engine** ({modules}-module Java project)", "full build / incremental",
            f"**~{ns_to_s(bl['fullBuild.ns'])}** / {bl['incremental.tasks']} tasks re-run",
        ))

    table = ["| Area | Metric | Result |", "|---|---|---|"]
    for area, metric, result in rows:
        table.append(f"| {area} | {metric} | {result} |")

    # badge: a compact headline
    parts = []
    if cq:
        parts.append(f"recall {pct(cq['overall.recall'])}")
    if bl:
        parts.append(f"build {ns_to_s(bl['fullBuild.ns']).replace(' ', '')}")
    badge_msg = " · ".join(parts) if parts else "see README"
    return "\n".join(table), badge_msg


# ---- README block replacement ----------------------------------------------

def replace_block(text, name, body):
    start = f"<!-- AUTOGEN:{name}:START -->"
    end = f"<!-- AUTOGEN:{name}:END -->"
    pattern = re.compile(re.escape(start) + r".*?" + re.escape(end), re.DOTALL)
    if not pattern.search(text):
        sys.exit(f"AUTOGEN markers for '{name}' not found in README.md")
    return pattern.sub(f"{start}\n{body}\n{end}", text)


def main():
    counts = count_tests()
    bench_table, bench_badge = benchmark_rows()

    with open(README) as f:
        text = f.read()

    if counts["tests"] > 0:
        tests_line = (
            f"**{counts['passing']}** tests passing across **{counts['suites']}** suites · "
            f"{counts['failed']} failing · {counts['skipped']} skipped "
            f"(framework / `CI_CORE_ONLY`)."
        )
        text = replace_block(text, "tests", tests_line)
    else:
        print("No test results found; leaving the tests section unchanged.")

    text = replace_block(text, "benchmarks", bench_table)

    with open(README, "w") as f:
        f.write(text)

    os.makedirs(BADGE_DIR, exist_ok=True)
    if counts["tests"] > 0:
        color = "brightgreen" if counts["failed"] == 0 else "red"
        msg = f"{counts['passing']} passing" + ("" if counts["failed"] == 0 else f", {counts['failed']} failing")
        with open(os.path.join(BADGE_DIR, "tests.json"), "w") as f:
            json.dump({"schemaVersion": 1, "label": "tests", "message": msg, "color": color}, f, indent=2)

    with open(os.path.join(BADGE_DIR, "benchmarks.json"), "w") as f:
        json.dump({"schemaVersion": 1, "label": "benchmarks", "message": bench_badge, "color": "blueviolet"}, f, indent=2)

    print(f"Updated README + badges: {counts['passing']}/{counts['tests']} tests, benchmarks='{bench_badge}'")


if __name__ == "__main__":
    main()
