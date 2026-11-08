#!/usr/bin/env python3
"""Archive and cross-check the existing Actions failures; never run Android."""
import hashlib
import html
import json
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = Path(__file__).resolve().parent / "ci"
SOURCE = Path("/tmp/dining-release-20261107/ci-evidence-42435516388")
PARENT = Path("/tmp/dining-publish-20261108/api-failure-stacks.json")
BASE = "fd9294181b5fef8bb67cb3f2c337711f36e45beb"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def archive(path, relative):
    data = path.read_bytes()
    target = DEST / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return {"source": str(path), "saved": str(target.relative_to(ROOT)),
            "bytes": len(data), "sha256": sha(data)}


def parse_tree(block):
    nodes = {}
    parents = []
    counts = Counter()
    previous = None
    for line in block:
        match = re.search(r"Node #(\d+) at .*?px(?:, Tag: '([^']*)')?", line)
        if match:
            node_id = int(match[1])
            depth = line.index("Node #") // 3
            assert depth <= len(parents)
            parents = parents[:depth]
            parent_id = parents[-1] if parents else None
            nodes[node_id] = {"id": node_id, "tag": match[2], "parent": parent_id,
                              "sibling": counts[parent_id], "path": parents + [node_id],
                              "raw": line, "details": []}
            counts[parent_id] += 1
            parents.append(node_id)
            previous = node_id
        elif previous is not None:
            nodes[previous]["details"].append(line)
    return nodes


def trace(failure, lines, measurement):
    first = failure["stack"].splitlines()[0]
    if "Unreachable touch " not in first:
        assert "Text = '[正在下载 · 50%]'" in failure["stack"]
        return {"category": "exact_text_item_mismatch", "observed_text": "正在下载 · 50%",
                "assertion": "assertTextContains defaults to substring=false in pinned Compose 1.8.2"}
    ids = [int(i) for i in re.findall(r"node-(\d+):", first)]
    geometries = [(n, line) for n, line in enumerate(lines) if " touch-geometry name=" in line
                  and all(f"node-{node_id}:" in line for node_id in ids)]
    assert len(geometries) == 1, (failure["name"], len(geometries))
    geometry_index, geometry = geometries[0]
    name = re.search(r"touch-geometry name=(.*?) owner=", geometry)[1]
    tag = "save" if name == "landscape-ime-save" else name
    observations = []
    for node_id in ids:
        matches = [(n, line) for n, line in enumerate(lines[:geometry_index])
                   if f" overlay tag=node-{node_id}:" in line]
        assert len(matches) == 1, (failure["name"], node_id)
        n, line = matches[0]
        assert re.search(r"owner=(\d+) root=(\d+)", line).groups() == re.search(
            r"owner=(\d+) root=(\d+)", geometry).groups()
        observations.append({"node_id": node_id, "line": n + 1, "raw": line})
    result = {"category": "lower_paint_plane", "measurement_source": measurement,
              "touch_tag": tag, "geometry_line": geometry_index + 1,
              "geometry_raw": geometry, "overlay_observations": observations}
    if failure["class"] == "Pr18StartupTest":
        assert not any(f"Node #{i} at" in line for i in ids for line in lines)
        result.update(order_evidence="baseline_source_ancestry", tree=None, target_id=None,
                      explanation="Startup failure site emits no diagnostic tree. ProductRoot passes BottomTabs to "
                      "ListScreens; ScreenFrame places content before the floating capsule (QuietGridComponents.kt:88-93). "
                      "No observed runtime paint ranks are claimed.")
        return result
    message = first.split(": ", 1)[1]
    failure_lines = [n for n, line in enumerate(lines) if " FAIL case=" in line
                     and line.endswith("message=" + message)]
    assert len(failure_lines) == 1
    for start in range(failure_lines[0] + 1, min(len(lines), failure_lines[0] + 2400)):
        if not ("Printing with useUnmergedTree" in lines[start] and
                (" semantics root=" in lines[start] or " protected-semantics " in lines[start])):
            continue
        end = start + 1
        while end < len(lines) and not lines[end].startswith("style="):
            end += 1
        block = lines[start:end]
        nodes = parse_tree(block)
        if not all(i in nodes for i in ids):
            continue
        targets = [n for n in nodes.values() if n["tag"] == tag]
        if len(targets) != 1:
            continue
        target = targets[0]
        relations = []
        for node_id in ids:
            candidate = nodes[node_id]
            a, b = target["path"], candidate["path"]
            common = 0
            while common < min(len(a), len(b)) and a[common] == b[common]:
                common += 1
            assert 0 < common < min(len(a), len(b))
            target_branch, candidate_branch = nodes[a[common]], nodes[b[common]]
            assert candidate_branch["sibling"] < target_branch["sibling"]
            relations.append({"candidate": candidate, "common_ancestor": a[common - 1],
                              "candidate_branch": candidate_branch["id"], "candidate_index": candidate_branch["sibling"],
                              "target_branch": target_branch["id"], "target_index": target_branch["sibling"],
                              "relation": "lower"})
        result.update(order_evidence="actual_unmerged_diagnostic_tree", target=target,
                      relations=relations, tree_start_line=start + 1, tree_raw="\n".join(block) + "\n",
                      failure_line=failure_lines[0] + 1, failure_raw=lines[failure_lines[0]])
        return result
    raise AssertionError((failure["api"], failure["name"], "diagnostic tree unavailable"))


summary = json.loads((SOURCE / "summary.json").read_bytes())
parent = json.loads(PARENT.read_bytes())
assert summary["run_id"] == 42435516388
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
assert head == BASE, "Collect failure evidence before the repair commit"
manifest = [archive(SOURCE / "summary.json", "summary.json"), archive(PARENT, "parent-failure-stacks.json")]
failures = []
for api in (26, 35):
    artifact = next(a for a in summary["artifacts"] if a["name"] == f"android-auth-api-{api}")
    assert (artifact["test_count"], artifact["failed_count"], artifact["skipped_count"]) == (190, 32, 0)
    expected = {(c["class"].rsplit(".", 1)[-1], c["name"]) for c in artifact["cases"] if c["failure"]}
    measurement_path = SOURCE / f"android-auth-api-{api}/outputs/p4-screenshots/p4-screenshots/pr18-measurements-api{api}.txt"
    measurement_bytes = measurement_path.read_bytes()
    measurement = {"source": str(measurement_path), "bytes": len(measurement_bytes), "sha256": sha(measurement_bytes)}
    manifest.append(measurement)  # Raw selected lines/trees are saved below; no PNG/metadata is rewritten.
    lines = measurement_bytes.decode().splitlines()
    actual = set()
    for path in sorted((SOURCE / f"android-auth-api-{api}/reports/androidTests/connected/debug").glob("*.html")):
        entries = []
        for fragment in re.findall(r'<div class="test">(.*?)\n</div>', path.read_text(), re.S):
            name_match = re.search(r'<h3 class="failures">(.*?)</h3>', fragment, re.S)
            if not name_match:
                continue
            name = html.unescape(name_match[1])
            stacks = [html.unescape(s).strip() for s in re.findall(r"<pre>(.*?)</pre>", fragment, re.S)]
            assert len(stacks) == 1
            cls = path.stem.rsplit(".", 1)[-1]
            extracted = [f for f in parent if (f["api"], f["class"], f["name"]) == (api, cls, name)]
            assert len(extracted) == 1
            assert extracted[0]["stack"].strip() == stacks[0]
            assert extracted[0]["source_sha256"] == sha(path.read_bytes())
            row = dict(extracted[0], trace=trace(extracted[0], lines, measurement))
            entries.append(row)
            actual.add((cls, name))
            print(api, cls, name, row["trace"]["category"], row["trace"].get("order_evidence", "pinned_text_matcher"))
        if entries:
            manifest.append(archive(path, f"api-{api}/{path.name}"))
            failures.extend(entries)
    assert actual == expected
assert len(failures) == len(parent) == 64
write_json(DEST / "full-failures.json", failures)
write_json(DEST / "source-manifest.json", manifest)
write_json(DEST / "read-receipt.json", {"command": "python3 -B app-runtime-fix-evidence/collect-ci.py", "execution_head": head,
           "executed_at_utc": datetime.now(timezone.utc).isoformat(), "status": "complete",
           "failure_instances": len(failures), "per_api": {str(api): dict(Counter(f["trace"]["category"] for f in failures if f["api"] == api)) for api in (26, 35)},
           "actual_tree_instances": sum(f["trace"].get("order_evidence") == "actual_unmerged_diagnostic_tree" for f in failures),
           "source_order_only_instances": sum(f["trace"].get("order_evidence") == "baseline_source_ancestry" for f in failures),
           "android_green": "pending_real_Actions"})
print("PASS: all 64 complete HTML stacks match parent extraction and summary; 32 actual trees, 24 source-order traces, 8 text mismatches archived.")
