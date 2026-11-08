#!/usr/bin/env python3
"""Verify the repair's exact scope and immutable CI/source inventories."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = Path(__file__).resolve().parent
BASE = "fd9294181b5fef8bb67cb3f2c337711f36e45beb"
PRODUCT = "android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/"
ALLOWED = {PRODUCT + "Pr18Evidence.kt", PRODUCT + "Pr18DialogAcceptanceTest.kt"}
parser = argparse.ArgumentParser()
parser.add_argument("--receipt", type=Path, default=DEST / "scope-green.json")
args = parser.parse_args()
manifest = json.loads((DEST / "source-manifest-base.json").read_text())
assert manifest["base_sha"] == BASE


def sha(data):
    return hashlib.sha256(data).hexdigest()


unchanged = []
changed = []
for entry in manifest["git_tree"]:
    assert entry["kind"] == "blob"
    path = ROOT / entry["path"]
    data = os.readlink(path).encode() if entry["mode"] == "120000" else path.read_bytes()
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if blob == entry["git_blob"]:
        unchanged.append(entry["path"])
    else:
        assert entry["path"] in ALLOWED, entry["path"]
        changed.append(entry["path"])
assert set(changed) == ALLOWED
diff_paths = set(subprocess.check_output(["git", "diff", "--name-only", BASE], cwd=ROOT, text=True).splitlines())
original_paths = {entry["path"] for entry in manifest["git_tree"]}
assert diff_paths & original_paths == ALLOWED
added = (diff_paths - original_paths) | set(subprocess.check_output(
    ["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines())
assert all(path.startswith("app-runtime-fix-evidence/") for path in added)

before = (DEST / "base/Pr18Evidence.kt").read_text()
after = (ROOT / (PRODUCT + "Pr18Evidence.kt")).read_text()
start, end = "    private fun withOverlays", "    private fun logTouchGeometry"
assert before[:before.index(start)] == after[:after.index(start)]
assert before[before.index(end):] == after[after.index(end):]
old_adapter = before[before.index(start):before.index(end)]
new_adapter = after[after.index(start):after.index(end)]
assert old_adapter[old_adapter.index("        val windows = interactiveWindows()"):] == new_adapter[
    new_adapter.index("        val windows = interactiveWindows()"):]
selector_start, selector_end = '        val candidates =', '        val overlays ='
assert old_adapter[old_adapter.index(selector_start):old_adapter.index(selector_end)] == new_adapter[
    new_adapter.index(selector_start):new_adapter.index(selector_end)]
assert "overlay.touchBoundsInRoot" in new_adapter
assert "paint-order-unknown" in new_adapter

old_assertion = '''evidence.target("update-progress").assertTextContains(if (state.stage == UpdateStage.VERIFYING) "正在校验安装包…"
                            else if (state.percent == null) "百分比待确认" else "50%")'''
new_assertion = '''evidence.target("update-progress").assertTextEquals(if (state.stage == UpdateStage.VERIFYING) "正在校验安装包…"
                            else if (state.percent == null) "正在下载 · 百分比待确认" else "正在下载 · 50%")'''
before_dialog = (DEST / "base/Pr18DialogAcceptanceTest.kt").read_text()
after_dialog = (ROOT / (PRODUCT + "Pr18DialogAcceptanceTest.kt")).read_text()
assert before_dialog.count(old_assertion) == 1
assert before_dialog.replace(old_assertion, new_assertion) == after_dialog

method_count = case_count = 0
for name, suite in manifest["android_test_suites"].items():
    data = (ROOT / suite["path"]).read_bytes()
    methods = re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)", data.decode())
    assert methods == suite["methods"], name
    if suite["path"] not in ALLOWED:
        assert sha(data) == suite["sha256"], name
    method_count += len(methods)
    case_count += suite["actual_ci_cases"]
assert (len(manifest["android_test_suites"]), method_count, case_count) == (20, 109, 190)
assert manifest["original_counts"] == {"classes": 17, "methods": 93, "cases": 174}
summary = json.loads((DEST / "ci/summary.json").read_text())
for artifact in summary["artifacts"]:
    if artifact["name"].startswith("android-auth-api-"):
        assert len(artifact["cases"]) == artifact["test_count"] == 190
        assert sum(bool(c["failure"]) for c in artifact["cases"]) == artifact["failed_count"] == 32
        assert not any(c["skipped"] for c in artifact["cases"])
for entry in json.loads((DEST / "ci/source-manifest.json").read_text()):
    if entry.get("saved"):
        assert sha((ROOT / entry["saved"]).read_bytes()) == entry["sha256"]
for entry in json.loads((DEST / "pinned/source-manifest.json").read_text())["entries"]:
    if entry.get("saved"):
        assert sha((ROOT / entry["saved"]).read_bytes()) == entry["full_sha256"]

red = json.loads((DEST / "lightweight/red/receipt.json").read_text())
green = json.loads((DEST / "lightweight/green/receipt.json").read_text())
assert red["steps"][-1]["name"] == "junit" and red["steps"][-1]["exit_code"] == 1
assert all(step["exit_code"] == 0 for step in red["steps"][:-1])
assert all(step["exit_code"] == 0 for step in green["steps"])
red_log = (DEST / "lightweight/red/junit.log").read_text()
green_log = (DEST / "lightweight/green/junit.log").read_text()
assert "Tests run: 26,  Failures: 11" in red_log
assert "OK (26 tests)" in green_log
for path, expected in red["inputs_sha256"].items():
    if path in ALLOWED:
        assert expected == manifest["repair_inputs_sha256"][path]
    elif not path.endswith("extracted-source.kt.txt"):
        assert green["inputs_sha256"][path] == expected == sha((ROOT / path).read_bytes()), path
for path in ALLOWED:
    assert green["inputs_sha256"][path] == sha((ROOT / path).read_bytes())

receipt = {"command": "python3 -B app-runtime-fix-evidence/verify-scope.py",
           "executed_at_utc": datetime.now(timezone.utc).isoformat(),
           "execution_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
           "status": "pass", "base_sha": BASE, "unchanged_base_files": len(unchanged),
           "modified_base_files": changed, "fixture_methods": 26, "red_failures": 11,
           "current_android_inventory": manifest["current_counts"], "original_android_inventory": manifest["original_counts"],
           "tested_worktree_source_sha256": {path: sha((ROOT / path).read_bytes()) for path in sorted(ALLOWED)},
           "preserved": ["all production files/assets/themes", "all original case/method identities",
                         "action selector", "window/IME ordering", "physical injection/provenance gates",
                         "FLAG_SECURE and PNG/capture/font provenance", "all callback assertions"],
           "android_green": "pending_real_Actions"}
args.receipt.parent.mkdir(parents=True, exist_ok=True)
args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
print(f"PASS {len(unchanged)} baseline files byte-identical; only the adapter and exact progress assertion changed")
print("PASS 20/109/190 and original 17/93/174 identities retained; CI artifacts and pinned sources unchanged")
print("PASS actual 26-case RED (11 failures) -> GREEN; unchanged harness inputs bind both executions to source")
print("Android GREEN pending real Actions")
