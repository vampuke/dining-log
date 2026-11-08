#!/usr/bin/env python3
"""Gate A diagnostic producer. Modified instrumentation, never a CI/release pass."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import textwrap
import time
import types

BASE = "be8aaf8d60aa5513227a102d4cdbfcb2377b778d"
COLLECTOR_SHA = "e08702a890bd59d1a80b7232da8f28f2bd2533ad"
COLLECTOR_PATH = ".github/scripts/pr18-failure-diagnostics.py"
COLLECTOR_SHA256 = "9233a38598538b93418d5c03af2370f4f2530ec2aa9e02e2f37fc760aed626bd"
KIND = "DIAGNOSTIC_MODIFIED_INSTRUMENTATION"
REF = "refs/heads/verify/pr18-ui-causal-probe-r1-20261108"
TEST_DIR = "android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/"
METHOD = "f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent"
VARIANTS = ("INK_PAPER-dark=false", "INK_PAPER-dark=true", "QUIET_GRID-dark=false", "QUIET_GRID-dark=true")
CASES = [f"top.alanvampuke.dininglog.product.Pr18ScreenAcceptanceTest#{METHOD}[{v}]" for v in VARIANTS]
MODIFIED = {TEST_DIR + name for name in ("Pr18ScreenAcceptanceTest.kt", "Pr18Fixtures.kt", "Pr18Evidence.kt")}
ADDED = {TEST_DIR + "Pr18UiCausalProbe.kt", ".github/scripts/pr18-ui-causal-probe.py",
         ".github/scripts/test_pr18_ui_causal_probe.py", ".github/workflows/pr18-ui-causal-probe.yml",
         ".github/pr18-ui-causal-probe.md"}
ALLOWED = MODIFIED | ADDED
HOOKS = {
    "Pr18ScreenAcceptanceTest.kt": (
        "evidence.uiCausalProbe?.terminal(runtime!!)", "evidence.uiCausalProbe?.disposedBeforeClear()",
        "evidence.uiCausalProbe?.afterClear()", "evidence.uiCausalProbe?.configurationComplete()",
        "evidence.uiCausalProbe?.beforeRender(next)", "evidence.uiCausalProbe?.afterRender(next, vm)"),
    "Pr18Evidence.kt": (
        "internal val uiCausalProbeCase get() = evidenceCase\ninternal var uiCausalProbe: Pr18UiCausalProbe? = null",
        "uiCausalProbe?.failure(name)"),
    "Pr18Fixtures.kt": (
        "import androidx.compose.runtime.SideEffect\nimport androidx.compose.ui.platform.LocalDensity\n"
        "import androidx.compose.ui.platform.LocalSavedStateRegistryOwner\nimport androidx.compose.ui.platform.LocalView\n"
        "import androidx.lifecycle.HasDefaultViewModelProviderFactory\nimport androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner",
        "val probe = evidence.uiCausalProbe\nif (probe != null) {\n"
        "    val owner = LocalViewModelStoreOwner.current\n    val registryOwner = LocalSavedStateRegistryOwner.current\n"
        "    val view = LocalView.current\n    val native = LocalDensity.current\n"
        "    val extras = (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras\n"
        "    SideEffect { probe.compositionBoundary(this@Pr18Runtime, owner, registryOwner, extras,\n"
        "        view, native.fontScale, native.density) }\n}"),
}
PREPARE = "    @Before fun prepare() { evidence = Pr18Evidence(compose, style, dark); evidence.configure(1f) }"
RESTORE = "    @After fun restore() { evidence.close(); runtime?.close() }"
DIAG_PREPARE = PREPARE[:-2] + "; evidence.uiCausalProbe = Pr18UiCausalProbe.openIfSelected(evidence) }"
DIAG_RESTORE = "    @After fun restore() { try { evidence.close(); runtime?.close() } finally { evidence.uiCausalProbe?.close(); evidence.uiCausalProbe = null } }"
BLOCK = re.compile(r"(?ms)^([ \t]*)// PR18_GATE_A_BEGIN\n(.*?)^\1// PR18_GATE_A_END\n")


def git(repo, *args):
    return subprocess.check_output(["git", *args], cwd=repo)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def tree(repo, ref):
    entries = {}
    for row in git(repo, "ls-tree", "-rz", ref).split(b"\0"):
        if row:
            metadata, name = row.split(b"\t", 1)
            mode, kind, oid = metadata.decode().split()
            if kind != "blob":
                raise RuntimeError("Non-file source binding is outside this diagnostic contract")
            entries[name.decode()] = (mode, oid)
    return entries


def original_projection(name, data):
    source = data.decode()
    blocks = [textwrap.dedent(match[1]).rstrip("\n") for match in BLOCK.findall(source)]
    if tuple(blocks) != HOOKS[Path(name).name]:
        raise RuntimeError(f"Unexpected diagnostic hook/action in {name}")
    source = BLOCK.sub("", source)
    if name.endswith("Pr18Fixtures.kt"):
        boundary = data.decode().split("val content: @androidx.compose.runtime.Composable () -> Unit = {", 1)[1]
        if boundary.split("MainActivityContent(", 1)[0].strip():
            raise RuntimeError("Production composition call position changed")
    if name.endswith("Pr18ScreenAcceptanceTest.kt"):
        for diagnostic, original in ((DIAG_PREPARE, PREPARE), (DIAG_RESTORE, RESTORE)):
            if source.count(diagnostic) != 1:
                raise RuntimeError("Missing/extraneous opt-in or finally cleanup")
            source = source.replace(diagnostic, original)
    return source.encode()


def helper_contract(source):
    forbidden = (r"\b(?:createSavedStateHandle|consumeRestoredStateForKey|SavedStateHandle|ViewModelProvider|"
                 r"selectTab|performClick|injectInputEvent|Bundle|Thread\.sleep)\s*\(|\.saveState\s*\(|\.clear\s*\(|"
                 r"\b(?:setContent|CompositionLocalProvider)\s*[({]|getDeclared|java\.lang\.reflect|"
                 r"(?:putString|putBundle|registerSavedStateProvider|unregisterSavedStateProvider)\s*\(")
    if re.search(forbidden, source):
        raise RuntimeError("Observer contains a state-changing/private/alternate-owner interface")
    for literal in ("Application.ActivityLifecycleCallbacks", "onActivitySaveInstanceState",
                    "androidx.lifecycle.BundlableSavedStateRegistry.key",
                    "androidx.lifecycle.internal.SavedStateHandlesProvider",
                    "androidx.lifecycle.internal.SavedStateHandlesVM",
                    "getSavedStateProvider(PROVIDER_KEY)", "store.get(HANDLES_KEY)",
                    "initialRestoredTab\" to vm?.restoredTab", "naturalState = outState",
                    "unregisterActivityLifecycleCallbacks(this)", "finally", KIND):
        if literal not in source:
            raise RuntimeError(f"Missing passive contract: {literal}")
    callback = source.split("override fun onActivitySaveInstanceState", 1)[1].split("private fun lifecycle", 1)[0]
    if ".get(" in callback or "containsKey" in callback:
        raise RuntimeError("Natural Bundle was read before ComponentActivity's complete save")


def check_be8_binding(name, data, mode, base_entry, original=None):
    oid = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if mode != base_entry[0]:
        raise RuntimeError(f"Original source mode changed: {name}")
    if name in MODIFIED:
        if original is None or original_projection(name, data) != original:
            raise RuntimeError(f"Original actions/assertions/source changed: {name}")
        return sha256(original)
    if (mode, oid) != base_entry:
        raise RuntimeError(f"Production/unrelated be8 source changed: {name}")
    return None


def source_plan(repo, working_tree=False):
    repo = Path(repo).resolve()
    head = git(repo, "rev-parse", "HEAD").decode().strip()
    status = git(repo, "status", "--porcelain", "--untracked-files=all").decode()
    if not working_tree and status:
        raise RuntimeError("Probe requires a clean committed checkout")
    if os.environ.get("GITHUB_SHA") and head != os.environ["GITHUB_SHA"]:
        raise RuntimeError("Checkout HEAD differs from GITHUB_SHA")
    base_tree, head_tree = tree(repo, BASE), tree(repo, "HEAD")
    tracked = set(git(repo, "ls-files", "-z").decode().split("\0")) - {""}
    untracked = set(git(repo, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")) - {""}
    paths = tracked | untracked
    if base_tree.keys() - paths or paths - base_tree.keys() - ADDED or not ADDED <= paths:
        raise RuntimeError("Missing source/scaffold or unauthorized added/deleted path")
    hashes, unchanged, production, delta, projections = {}, {}, {}, [], {}
    for name in sorted(paths):
        path = repo / name
        if not path.is_file() or path.is_symlink():
            raise RuntimeError(f"Non-regular diagnostic source {name}")
        data = path.read_bytes()
        oid = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        mode = "100755" if path.stat().st_mode & 0o111 else "100644"
        hashes[name] = sha256(data)
        if not working_tree and head_tree.get(name) != (mode, oid):
            raise RuntimeError(f"Checkout bytes/mode differ from candidate: {name}")
        if name in base_tree:
            original = git(repo, "show", f"{BASE}:{name}") if name in MODIFIED else None
            projection = check_be8_binding(name, data, mode, base_tree[name], original)
            if name in MODIFIED:
                projections[name] = projection
            else:
                unchanged[name] = {"be8_blob": oid, "sha256": hashes[name]}
                if "/src/" in name and any(part in name for part in ("/src/main/", "/src/debug/", "/src/release/")):
                    production[name] = hashes[name]
        if name not in base_tree or (mode, oid) != base_tree[name]:
            if name not in ALLOWED:
                raise RuntimeError(f"Unauthorized source delta: {name}")
            delta.append({"path": name, "scope": "TEST_ONLY_PASSIVE_DIAGNOSTIC",
                          "base_sha256": projections.get(name), "candidate_sha256": hashes[name]})
    helper_contract((repo / (TEST_DIR + "Pr18UiCausalProbe.kt")).read_text())
    name = TEST_DIR + "Pr18ScreenAcceptanceTest.kt"
    before, after = git(repo, "show", f"{BASE}:{name}"), (repo / name).read_bytes()
    starts = list(re.finditer(rb"(?m)^    @Test fun (\w+)\(\) \{", before))
    methods = {}
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(before)
        block = before[match.start():end]
        if after.count(block) != 1:
            raise RuntimeError("An original f7 method block changed or duplicated")
        methods[match.group(1).decode()] = sha256(block)
    return {"evidence_kind": KIND, "base_sha": BASE, "probe_candidate_sha": None if status else head,
            "checkout_head_sha": head, "candidate_state": "WORKING_TREE_NOT_COMMITTED" if status else "CLEAN_COMMITTED",
            "candidate_tree": git(repo, "rev-parse", "HEAD^{tree}").decode().strip() if not status else None,
            "source_unchanged_from_real_failure": False, "production_diff": [],
            "allowed_diagnostic_paths": sorted(ALLOWED), "diagnostic_delta": delta,
            "source_sha256": hashes, "unchanged_be8_bindings": unchanged,
            "android_production_source_sha256": production, "original_source_projection_sha256": projections,
            "original_method_blocks_sha256": methods, "expected_case_ids": CASES, "expected_count": 4, "api": 26,
            "selector": ",".join(CASES), "collector_commit": COLLECTOR_SHA, "collector_sha256": COLLECTOR_SHA256,
            "baseline_run": 42466949156,
            "baseline_xml_sha256": "ae6c6a8b34ad2e3dab835d85acebd4900e5bc68b3ddeb01a307ec6c436fcd722",
            "actions_context": {"run_id": os.environ.get("GITHUB_RUN_ID"), "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
                                "ref": os.environ.get("GITHUB_REF"), "sha": os.environ.get("GITHUB_SHA")},
            "scope": "GATE_A_ONLY_NO_CORRECTION_APPROVAL_OR_RELEASE"}


def load_collector(repo):
    source = git(repo, "show", f"{COLLECTOR_SHA}:{COLLECTOR_PATH}")
    if sha256(source) != COLLECTOR_SHA256:
        raise RuntimeError("Approved collector source binding differs")
    module = types.ModuleType("pr18_e087_collection_interfaces")
    module.__file__ = f"git:{COLLECTOR_SHA}:{COLLECTOR_PATH}"
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    if module.selected_cases("flow") != CASES:
        raise RuntimeError("Exact original native selectors differ from approved collector")
    return module, source


def native_command():
    return ["./gradlew", ":app:connectedDebugAndroidTest", "--no-daemon",
            "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(CASES)]


def embedded_shells(workflow):
    """Extract this workflow's run/script scalars for bash -n, without running them."""
    lines, shells = workflow.splitlines(), []
    for index, line in enumerate(lines):
        match = re.match(r"^(\s+)(run|script):\s*(.*)$", line)
        if not match:
            continue
        indent, kind, value = match.groups()
        if value in ("|", ">-"):
            body = []
            for following in lines[index + 1:]:
                if following.strip() and len(following) - len(following.lstrip()) <= len(indent):
                    break
                body.append(following)
            value = textwrap.dedent("\n".join(body)).rstrip() + "\n"
            if match.group(3) == ">-":
                value = " ".join(value.splitlines()) + "\n"
        shells.append({"kind": kind, "line": index + 1, "source": value})
    return shells


def original_red(results):
    return (results["classification"] == "COMPLETE_FAIL" and results["raw_gradle_exit"] == 1 and
            results["counts"] == {"passed": 0, "failed": 4, "errors": 0, "skipped": 0,
                                  "missing": 0, "duplicate": 0, "unexpected": 0} and
            len(results["cases"]) == 4 and Counter(case["id"] for case in results["cases"]) == Counter(CASES) and
            all("java.lang.AssertionError" in "".join(case["details"]) and
                "screen:restaurant:list" in "".join(case["details"]) and
                "Pr18ScreenAcceptanceTest.list" in "".join(case["details"]) and
                METHOD in "".join(case["details"]) for case in results["cases"]))


def case_chain(rows):
    """Apply PLAN's stop table to observed scalars. Does not grant approval."""
    def at(stage, font=None):
        matches = [row for row in rows if row["stage"] == stage and (font is None or row["requestedFont"] == font)]
        if len(matches) != 1:
            raise ValueError(f"Missing/duplicate {stage} at font {font}")
        return matches[0]
    def ident(row, name):
        return row["owner"][name]["id"]
    def screen(row):
        return [node["tag"] for node in row["screens"]]
    def entry(row):
        entries = row["navigation"]
        if len(entries) != 1 or entries[0]["entryId"] == "missing-entry":
            raise ValueError("Missing/ambiguous navigation entry")
        return entries[0]["entryId"]
    terminal, before, cleared = at("terminal-font100", 1), at("disposed-before-clear", 1), at("disposed-after-clear", 1)
    incoming, rendered = at("configuration-returned", 2), at("after-production-render", 2)
    boundaries = [row for row in rows if row["stage"] == "composition-owner-boundary" and
                  row["requestedFont"] == 2 and row["firstExistingVmObservation"]]
    if len(boundaries) != 1:
        raise ValueError("Missing/duplicate initial existing-VM owner-boundary observation")
    boundary = boundaries[0]
    for row in (at("before-production-render", 1), at("after-production-render", 1),
                at("before-production-render", 2), rendered):
        if row["owner"]["resourcesFont"] != row["requestedFont"] or row["owner"]["lifecycle"] != "RESUMED":
            return "H4_OWNER_FONT_READINESS_UNCERTAIN"
    if any(row["navigation"] or row["screens"] for row in (before, cleared)):
        return "H4_DISPOSAL_BOUNDARY_UNCERTAIN"
    if not all(boundary[key] for key in ("localOwnerMatchesActivity", "localRegistryMatchesActivity",
               "extrasVmOwnerMatchesLocal", "extrasRegistryOwnerMatchesLocal", "sameVmAsActivity")):
        return "H3_OWNER_PROVENANCE_DIFFERENT"
    if boundary["initialRestoredTab"] == "restaurants" and screen(rendered) == ["screen:settings"]:
        return "H1_PROXIMATE_SELECTOR_FALSIFIED"
    saved, callback = at("natural-save-complete", 2), at("natural-save-callback", 2)
    causal_order = (terminal, before, cleared, callback, incoming, saved,
                    at("before-production-render", 2), boundary, rendered, at("failure-list-RESTAURANT", 2))
    if any(a["sequence"] >= b["sequence"] for a, b in zip(causal_order, causal_order[1:])):
        raise ValueError("Natural-save/owner/failure observations do not follow the original causal boundary")
    absent = all(not cleared["owner"][name]["present"] for name in ("productVm", "internalVm"))
    retained = all(ident(terminal, name) is not None and ident(terminal, name) == ident(before, name) == ident(cleared, name)
                   for name in ("activity", "window", "registry", "provider", "store"))
    outgoing_members = all(terminal["owner"][name]["present"] and before["owner"][name]["present"]
                           for name in ("productVm", "internalVm"))
    natural = (saved["saveCallbacks"] == 1 and saved["outgoingActivity"]["id"] == ident(terminal, "activity") and
               callback["outgoingActivity"]["id"] == saved["outgoingActivity"]["id"] and not callback["contentsRead"] and
               callback["outState"]["id"] == saved["outState"]["id"] and
               saved["outState"]["present"] and saved["readAfterConfigurationReturnedAndIdle"] and
               all(saved[name + "FieldPresent"] and saved[name + "Field"]["type"] == "android.os.Bundle"
                   for name in ("registry", "provider", "product")) and saved["tabPresent"] and
               saved["tabType"] == "java.lang.String" and saved["tab"] == "settings" and not saved["unexpectedTabRedacted"] and
               saved["productKey"] == terminal["owner"]["productKey"] == rendered["owner"]["productKey"])
    fresh = (all(ident(incoming, name) is not None and ident(incoming, name) != ident(terminal, name)
                 for name in ("activity", "registry", "provider")) and not incoming["sameActivityAsOutgoing"] and
             ident(rendered, "productVm") != ident(terminal, "productVm") and rendered["sameVmAsHelper"] and
             boundary["boundaryVm"]["id"] == ident(rendered, "productVm") and
             terminal["callbacks"]["accountScope"]["id"] != rendered["callbacks"]["accountScope"]["id"] and
             terminal["callbacks"]["generation"] == rendered["callbacks"]["generation"] == 1)
    failure = at("failure-list-RESTAURANT", 2)
    fresh = fresh and failure["sameVmAsHelper"] and ident(failure, "productVm") == ident(rendered, "productVm")
    composition_view = boundary["compositionView"]
    native_ready = (all(composition_view[key] for key in ("attached", "shown", "focused", "matchesActivityRoot", "matchesActivityToken")) and
                   all(row["roots"] and all(root["native"]["matchesActivityRoot"] and root["native"]["matchesActivityToken"]
                        for root in row["roots"]) for row in (rendered, failure)) and
                   all(row["owner"]["decor"]["attached"] and row["owner"]["decor"]["focused"] and
                       row["owner"]["decor"]["matchesActivityToken"] and not row["owner"]["secure"] and
                       row["owner"]["decor"]["imeVisible"] is False and
                       row["owner"]["decor"]["imeInsets"] == [0, 0, 0, 0]
                       for row in (rendered, failure)))
    callbacks_empty = all(not row["callbacks"]["detailRequests"] and row["callbacks"]["writes"] == 0
                          for row in (boundary, rendered, failure))
    if not native_ready:
        return "H4_NATIVE_OWNER_IME_UNCERTAIN"
    if (absent and retained and outgoing_members and natural and fresh and callbacks_empty and
            boundary["initialRestoredTab"] == "settings" and boundary["compositionFont"] == 2 and
            screen(terminal) == screen(rendered) == screen(failure) == ["screen:settings"] and
            entry(terminal) != entry(rendered) == entry(failure)):
        return "H1_CHAIN_RECORDED_WITH_ORIGINAL_IDENTITY_PATTERN"
    return "CAUSAL_CHAIN_MISSING_OR_CONTRADICTORY"


def capture_receipt(root, relative, case_id, shared):
    """Validate the exact be8 capture writers' outputs; never render/create pixels."""
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError("Unsafe capture path")
    image = Path(root) / "device" / relative
    metadata_path = image.with_suffix(".capture.txt")
    if not all(path.is_file() and path.stat().st_size for path in (image, metadata_path)) or not shared.complete_png(image):
        raise ValueError(f"Missing/incomplete original PNG/metadata: {relative}")
    metadata = metadata_path.read_text()
    if not metadata.startswith("pr18-capture-v1\n") or metadata.count("\nbindingSha256=") != 1:
        raise ValueError(f"Incomplete original metadata: {relative}")
    commitment, binding = metadata.removeprefix("pr18-capture-v1\n").split("\nbindingSha256=")
    encoded = shared.digest(image)
    if (binding != sha256(commitment.encode()) + "\n" or "encodedSha256=" + encoded not in commitment or
            "status=NORMAL_PNG" not in commitment or f"caseId={case_id}," not in commitment or
            "api=26," not in commitment or "secureBefore=false" not in commitment or "secureAfter=false" not in commitment):
        raise ValueError(f"Capture bytes/complete provenance disagree: {relative}")
    pixels = re.search(r"pixels=Pr18DecodedPixels\(width=(\d+), height=(\d+), pixelSha256=[0-9a-f]{64}\)", commitment)
    with image.open("rb") as stream:
        header = stream.read(24)
    if not pixels or header[12:16] != b"IHDR" or tuple(map(int, pixels.groups())) != struct.unpack(">II", header[16:24]):
        raise ValueError(f"Actual PNG/native decoded geometry disagree: {relative}")
    method = re.search(r", method=(\w+), owner=", commitment)
    if method is None or method.group(1) not in ("ACTIVITY_IMAGE", "WINDOW_PIXEL_COPY"):
        raise ValueError(f"Unexpected original capture adapter: {relative}")
    window_text = None
    if method.group(1) == "WINDOW_PIXEL_COPY":
        window_text = image.with_suffix(".txt")
        if not window_text.is_file() or not window_text.stat().st_size:
            raise ValueError(f"Missing original own-window text: {relative}")
        last = window_text.read_text().splitlines()[-1]
        if "callback=0" not in last or not last.endswith("outcome=normal_png"):
            raise ValueError(f"Incomplete/failed original own-window receipt: {relative}")
    return {"png": relative, "sha256": encoded, "metadata_sha256": shared.digest(metadata_path),
            "method": method.group(1), "window_text_sha256": shared.digest(window_text) if window_text else None}


def inspect_gate_a(root, results, shared):
    root = Path(root)
    errors, cases, variants = [], [], []
    log_path = root / "logcat.txt"
    lines = log_path.read_text(errors="replace").splitlines() if log_path.is_file() else []
    paths = sorted((root / "device").rglob("pr18-gateA-case*-api26.jsonl"))
    for path in paths:
        try:
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            if not rows:
                raise ValueError("Empty passive receipt")
            first = rows[0]
            variant = f"{first['style']}-dark={str(first['dark']).lower()}"
            variants.append(variant)
            if any(row["caseId"] != first["caseId"] or row["style"] != first["style"] or row["dark"] != first["dark"] or
                   row["prefix"] != "PR18_GATE_A" or row["evidenceKind"] != KIND or row["api"] != 26 for row in rows):
                raise ValueError("Case/label/API receipt identity changed")
            if [row["sequence"] for row in rows] != list(range(1, len(rows) + 1)) or any(
                    a["elapsedRealtimeNanos"] > b["elapsedRealtimeNanos"] for a, b in zip(rows, rows[1:])):
                raise ValueError("Receipt sequence/time is incomplete or nonmonotonic")
            if any(row["stage"] == "observation-error" for row in rows):
                raise ValueError("Passive observation error")
            closed = rows[-1]
            if closed["stage"] != "probe-closed" or any(closed[key] for key in (
                    "callbacksRegistered", "retainedActivity", "retainedBundle", "retainedRuntime")):
                raise ValueError("Missing callback/reference cleanup receipt")
            relevant = [line for line in lines if "DiningP4" in line and
                        f"style={first['style']} dark={str(first['dark']).lower()} " in line]
            font100 = [line for line in relevant if "actualFont=1.0 " in line]
            font200 = [line for line in relevant if "actualFont=2.0 " in line]
            callback_pattern = r" (?:record-open |save kind=|nested-history-time |history-card |apply writeCallbackDelta=)"
            callbacks = [line for line in font100 if re.search(callback_pattern, line)]
            touches = [line for line in font100 if " physical-touch-result " in line]
            if len(callbacks) != 10 or len(touches) != 28 or not all("downAccepted=true upAccepted=true" in line for line in touches):
                raise ValueError("Original font100 callback/physical-delivery pattern differs")
            if any(" physical-touch-result " in line for line in font200):
                raise ValueError("Original font200 failure-before-first-touch pattern differs")
            captures = [re.search(r" file=(\S+\.png)", line).group(1) for line in relevant
                        if " capture-provenance " in line and f" case={first['caseId']} " in line]
            if len(captures) != 17 or len(set(captures)) != 17:
                raise ValueError("Missing/duplicate original per-case capture requests")
            retained_captures = [capture_receipt(root, relative, first["caseId"], shared) for relative in captures]
            cases.append({"variant": variant, "case_id": first["caseId"], "receipt": str(path.relative_to(root)),
                          "classification": case_chain(rows), "original_callback_receipts": len(callbacks),
                          "original_accepted_touch_pairs": len(touches), "complete_original_captures": len(captures),
                          "captures": retained_captures})
        except (KeyError, ValueError, TypeError, OSError, AttributeError) as error:
            errors.append({"path": str(path.relative_to(root)), "error": str(error)})
    if Counter(variants) != Counter(VARIANTS):
        errors.append({"error": "Missing/duplicate/unexpected passive case receipts"})
    red = original_red(results)
    classification = ("INCOMPLETE_NATIVE_PROOF" if results["classification"] == "INVALID_EXECUTION" else
                      "NONINTERFERENCE_NOT_ESTABLISHED" if not red else "INCOMPLETE_NATIVE_PROOF" if errors else
                      "H1_CHAIN_AND_ORIGINAL_RED_RECORDED" if all(case["classification"] ==
                       "H1_CHAIN_RECORDED_WITH_ORIGINAL_IDENTITY_PATTERN" for case in cases) else "STOP_TABLE_REQUIRES_MORE_NATIVE_EVIDENCE")
    return {"evidence_kind": KIND, "gate_a_classification": classification, "original_behavioral_red_reproduced": red,
            "cases": cases, "proof_gaps": errors, "correction_implemented": False,
            "approval": False, "full_ci_or_app_pass": False,
            "next_requirement": "Independent review of native receipts; correction remains deferred unless PLAN's causal gate is established"}


def run_probe(repo, root, deadline_seconds):
    # Separate diagnostic-scope orchestration. The frozen producer/source_plan
    # is never called with changed tests or replaced/monkey-patched. Its approved
    # collection, native sampling, owned cleanup and sealing interfaces are reused.
    shared, collector_source = load_collector(repo)
    root = Path(root)
    gradle = logcat = None
    raw_exit, run_exit, termination = None, 1, None
    device = collector = None
    started = time.monotonic_ns()
    logcat_lifecycle = {"started": False, "healthy": False, "premature_exit": None,
                        "checks": [], "owned_teardown_requested": False}

    def check_logcat(phase):
        nonlocal termination, run_exit
        if logcat is None:
            return
        status = shared.process_exit(logcat)
        check = {"phase": phase, "raw_exit": status, "utc_ns": time.time_ns(), "monotonic_ns": time.monotonic_ns()}
        logcat_lifecycle["checks"].append(check)
        shared.event(root, "logcat-lifecycle", **check)
        if status is not None:
            logcat_lifecycle["premature_exit"] = status
            termination = termination or "LIVE_LOGCAT_EXITED"
            if run_exit == 0:
                run_exit = 1

    def interrupted(signum, frame):
        raise InterruptedError(signum)
    old_handlers = {signum: signal.signal(signum, interrupted) for signum in (signal.SIGINT, signal.SIGTERM)}
    with shared.writer_lock(root):
        if any(path.name != ".writer.lock" for path in root.iterdir()):
            raise RuntimeError("Diagnostic live directory must be new; stale reports are forbidden")
        shared.write_json(root / "RUN_STARTED.json", {"utc_ns": time.time_ns(), "evidence_kind": KIND})
        try:
            plan = source_plan(repo)
            shared.write_json(root / "plan.json", plan)
            (root / "approved-e087-collector.py").write_bytes(collector_source)
            devices = subprocess.check_output(["adb", "devices"]).decode()
            serials = [line.split()[0] for line in devices.splitlines() if line.endswith("\tdevice")]
            if len(serials) != 1:
                raise RuntimeError("Exactly one actual API26 diagnostic device must be connected")
            device = shared.AdbDevice(serials[0])
            actual_api = device.command("shell", "getprop ro.build.version.sdk")
            if actual_api.returncode or actual_api.stdout.decode().strip() != "26":
                raise RuntimeError("Connected device is not actual API26")
            for name, args in {
                "adb-version": ("version",), "adb-help": ("help",), "device-properties": ("shell", "getprop"),
                "font-initial": ("shell", "settings get system font_scale"),
                "ime-initial": ("shell", "settings get secure default_input_method"),
                "toybox-help": ("shell", "stat --help; find --help"),
            }.items():
                result = device.command(*args)
                (root / f"{name}.txt").write_bytes(result.stdout + result.stderr)
                shared.event(root, "tooling", command=name, raw_exit=result.returncode)
            result = device.command("shell", "settings put secure show_ime_with_hard_keyboard 1")
            if result.returncode:
                raise RuntimeError("Could not configure original full-suite IME precondition")
            device.command("logcat", "-c")
            with (root / "logcat.txt").open("wb") as log, (root / "gradle.log").open("wb") as gradle_log:
                logcat = shared.start_process(device.prefix + ["logcat", "-v", "threadtime"], stdout=log, stderr=subprocess.STDOUT)
                logcat_lifecycle["started"] = True
                command = native_command()
                shared.write_json(root / "command.json", {"cwd": str(Path(repo, "android")), "argv": command,
                                                         "evidence_kind": KIND, "probe_candidate_sha": plan["probe_candidate_sha"]})
                gradle = shared.start_process(command, cwd=Path(repo, "android"), stdout=gradle_log, stderr=subprocess.STDOUT)
                collector = shared.Collector(root, device)
                deadline, sequence = time.monotonic() + deadline_seconds, 0
                while shared.process_exit(gradle) is None:
                    check_logcat("periodic")
                    if logcat_lifecycle["premature_exit"] is not None:
                        break
                    if time.monotonic() >= deadline:
                        termination, run_exit = "BOUNDED_TIMEOUT", 124
                        break
                    collector.sample(deadline)
                    shared.native_sample(root, device, sequence, deadline)
                    sequence += 1
                    time.sleep(min(5, max(0, deadline - time.monotonic())))
                if termination is None:
                    raw_exit = shared.process_exit(gradle)
                    run_exit = raw_exit if raw_exit >= 0 else 128 - raw_exit
                check_logcat("test-completed")
        except InterruptedError as error:
            termination, run_exit = f"SIGNAL_{error.args[0]}", 128 + error.args[0]
        except Exception as error:
            termination = "HARNESS_ERROR"
            shared.event(root, "harness-error", type=type(error).__name__, error=str(error))
        finally:
            cleanup = {"gradle": shared.stop_process(gradle)}
            if gradle is not None and raw_exit is None:
                raw_exit = cleanup["gradle"]["raw_exit"]
            try:
                if collector is not None:
                    collector.sample(time.monotonic() + 20)
                if device is not None:
                    shared.native_sample(root, device, 99999, time.monotonic() + 20)
            except Exception as error:
                if isinstance(error, InterruptedError):
                    termination = termination or f"SIGNAL_{error.args[0]}"
                    if run_exit == 0:
                        run_exit = 128 + error.args[0]
                else:
                    termination = termination or "HARNESS_ERROR"
                    if run_exit == 0:
                        run_exit = 1
                shared.event(root, "final-collection-error", type=type(error).__name__, error=str(error))
            try:
                check_logcat("before-owned-teardown")
            except Exception as error:
                if isinstance(error, InterruptedError):
                    termination = termination or f"SIGNAL_{error.args[0]}"
                    if run_exit == 0:
                        run_exit = 128 + error.args[0]
                else:
                    termination = termination or "LOGCAT_STATUS_UNVERIFIED"
                    if run_exit == 0:
                        run_exit = 1
                shared.event(root, "logcat-status-error", error=str(error))
            cleanup["logcat"] = shared.stop_process(logcat)
            interrupted_signals = [signum for row in cleanup.values() for signum in row["interrupted_signals"]]
            if interrupted_signals and termination is None:
                termination, run_exit = f"SIGNAL_{interrupted_signals[0]}", 128 + interrupted_signals[0]
            logcat_stop = cleanup["logcat"]
            for key in ("exit_before_stop", "exit_before_signal"):
                if logcat_stop[key] is not None:
                    logcat_lifecycle["premature_exit"] = logcat_stop[key]
            controlled_exit = shared.controlled_stop_proven(logcat_stop)
            if logcat_stop["raw_exit"] is not None and logcat_stop["raw_exit"] >= 0:
                logcat_lifecycle["premature_exit"] = logcat_stop["raw_exit"]
            logcat_lifecycle["owned_teardown_requested"] = bool(logcat_stop["signals"])
            logcat_lifecycle["healthy"] = (logcat_lifecycle["started"] and logcat_lifecycle["premature_exit"] is None and
                                            controlled_exit and logcat_stop["writers_stopped"])
            if logcat is not None and not logcat_lifecycle["healthy"]:
                termination = termination or ("LIVE_LOGCAT_EXITED" if logcat_lifecycle["premature_exit"] is not None
                                               else "LOGCAT_STATUS_UNVERIFIED")
                if run_exit == 0:
                    run_exit = 1
            writers_stopped = all(row["writers_stopped"] for row in cleanup.values())
            if not writers_stopped:
                termination = termination or "OWNED_CLEANUP_FAILED"
                if run_exit == 0:
                    run_exit = 1
            shared.write_json(root / "cleanup.json", cleanup)
            shared.write_json(root / "exit.json", {"raw_gradle_exit": raw_exit, "raw_logcat_exit": logcat_stop["raw_exit"],
                              "logcat_lifecycle": logcat_lifecycle, "run_exit": run_exit, "termination": termination,
                              "elapsed_ns": time.monotonic_ns() - started, "evidence_kind": KIND})
            shared.write_json(root / "COLLECTOR_STOPPED.json", {"writers_stopped": writers_stopped, "utc_ns": time.time_ns()})
            for signum, handler in old_handlers.items():
                signal.signal(signum, handler)
    return run_exit


def finish(repo, root, archive):
    shared, _ = load_collector(repo)
    root, archive = Path(root), Path(archive)
    with shared.writer_lock(root):
        marker = root / "COLLECTOR_STOPPED.json"
        exits = json.loads((root / "exit.json").read_text()) if (root / "exit.json").is_file() else {}
        if not marker.exists() or not json.loads(marker.read_text()).get("writers_stopped"):
            if marker.exists() or (root / "RUN_STARTED.json").exists():
                shared.write_json(archive.parent / "unsealed-attempt.json", {
                    "evidence_kind": KIND, "classification": "INVALID_EXECUTION", "exits": exits,
                    "expected_case_ids": CASES, "missing_case_ids": CASES,
                    "limitation": "No stopped-writer receipt; potentially live evidence was not archived"})
                return 1
            shared.write_json(marker, {"writers_stopped": True, "run_never_started": True})
        for relative in ("outputs/androidTest-results/connected", "reports/androidTests/connected",
                         "outputs/apk/debug", "outputs/apk/androidTest/debug"):
            source = Path(repo, "android/app/build", relative)
            if source.exists():
                shutil.copytree(source, root / "build" / relative, dirs_exist_ok=True)
        plan = json.loads((root / "plan.json").read_text()) if (root / "plan.json").is_file() else None
        result = shared.inspect_results(root / "build/outputs/androidTest-results/connected", CASES, exits.get("raw_gradle_exit"))
        result.update(evidence_kind=KIND, plan=plan, exits=exits)
        try:
            post = source_plan(repo)
            shared.write_json(root / "post-source-validation.json", post)
            if plan is None or post != plan:
                raise RuntimeError("Missing/changed pre-build candidate source manifest")
        except (RuntimeError, OSError, subprocess.SubprocessError) as error:
            result["invalid_reasons"].append(f"Source binding: {error}")
            shared.write_json(root / "source-validation-error.json", {"error": str(error), "evidence_kind": KIND})
        apks = [{"path": str(path.relative_to(root)), "bytes": path.stat().st_size, "sha256": shared.digest(path)}
                for path in sorted((root / "build/outputs/apk").rglob("*.apk"))]
        result["apks"] = apks
        if len(apks) != 2 or exits.get("termination") is not None:
            result["invalid_reasons"].append("Missing exact app/test APK pair or interrupted harness")
        if exits.get("raw_logcat_exit") is None or exits.get("logcat_lifecycle", {}).get("healthy") is not True:
            result["invalid_reasons"].append("Missing/unhealthy continuous logcat lifecycle")
        if result["invalid_reasons"]:
            result["classification"] = "INVALID_EXECUTION"
        gate = inspect_gate_a(root, result, shared)
        shared.write_json(root / "gate-a.json", gate)
        shared.write_json(root / "results.json", result)
    shared.seal(root, archive)
    print(json.dumps({"evidence_kind": KIND, "classification": result["classification"], "counts": result["counts"],
                      "gate_a_classification": gate["gate_a_classification"], "archive": str(archive), "approval": False}))
    # Preserve raw test outcomes. Exit 1 is either the original failure or an
    # unfulfilled passive contract (including an unexpected pass), never forced RED.
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "run", "finish"))
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path)
    parser.add_argument("--working-tree", action="store_true", help="Local source checks only; not a committed/native binding")
    parser.add_argument("--live", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=1500)
    args = parser.parse_args()
    if args.action == "plan":
        value = source_plan(args.repo, args.working_tree)
        if args.output:
            shared, _ = load_collector(args.repo)
            shared.write_json(args.output, value)
        print(json.dumps({key: value[key] for key in ("evidence_kind", "base_sha", "probe_candidate_sha", "candidate_state",
              "diagnostic_delta", "production_diff", "original_method_blocks_sha256", "expected_case_ids")}, indent=2))
        return 0
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REF") != REF or args.working_tree:
        parser.error("Native actions require the reviewed diagnostic Actions ref and a committed candidate")
    if args.live is None or (args.action == "finish" and args.archive is None) or not 1 <= args.timeout_seconds <= 1500:
        parser.error("New live/archive paths and a deadline of at most 1500 seconds are required")
    if args.action == "run":
        return run_probe(args.repo, args.live, args.timeout_seconds)
    return finish(args.repo, args.live, args.archive)


if __name__ == "__main__":
    raise SystemExit(main())
