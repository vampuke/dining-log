#!/usr/bin/env python3
"""Pure Python Gate A contracts; these are never Android/native receipts."""
from pathlib import Path
import re
import contextlib
import copy
import hashlib
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
BASE = "be8aaf8d60aa5513227a102d4cdbfcb2377b778d"
TEST_DIR = "android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/"
METHOD = "f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent"
ALLOWED = {
    TEST_DIR + "Pr18ScreenAcceptanceTest.kt",
    TEST_DIR + "Pr18Fixtures.kt",
    TEST_DIR + "Pr18Evidence.kt",
    TEST_DIR + "Pr18UiCausalProbe.kt",
    ".github/scripts/pr18-ui-causal-probe.py",
    ".github/scripts/test_pr18_ui_causal_probe.py",
    ".github/workflows/pr18-ui-causal-probe.yml",
    ".github/pr18-ui-causal-probe.md",
}


def original(path):
    return subprocess.check_output(["git", "show", f"{BASE}:{path}"], cwd=REPO)


def subject():
    spec = importlib.util.spec_from_file_location("gate_a_contract_subject", REPO / ".github/scripts/pr18-ui-causal-probe.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def scalar_fixture_rows():
    """In-memory stop-table fixture ONLY; never files or native evidence."""
    def identity(value):
        return {"id": value, "present": value is not None, "type": "PURE_PYTHON_CONTRACT_FIXTURE_NOT_NATIVE"}
    native = {"attached": True, "shown": True, "focused": True, "matchesActivityRoot": True,
              "matchesActivityToken": True, "imeVisible": False, "imeInsets": [0, 0, 0, 0]}
    old = {key: identity(value) for key, value in {
        "activity": 11, "window": 12, "registry": 13, "provider": 15, "store": 14,
        "internalVm": 16, "productVm": 17}.items()}
    old.update(resourcesFont=1, lifecycle="RESUMED", secure=False, decor=native, productKey="product:pr18-owner:1")
    new = copy.deepcopy(old)
    new.update({key: identity(value) for key, value in {
        "activity": 21, "window": 22, "registry": 23, "provider": 25, "internalVm": 26, "productVm": 27}.items()})
    new["resourcesFont"] = 2
    callbacks = {"accountScope": identity(31), "generation": 1, "detailRequests": [], "writes": 0}
    fresh = copy.deepcopy(callbacks)
    fresh["accountScope"] = identity(41)
    def snapshot(stage, font, owner, entry=None, settings=False, counts=None):
        return {"stage": stage, "requestedFont": font, "owner": copy.deepcopy(owner),
                "navigation": [{"entryId": entry}] if entry else [],
                "screens": [{"tag": "screen:settings"}] if settings else [],
                "roots": [{"native": native}], "callbacks": copy.deepcopy(counts or callbacks), "sameVmAsHelper": True}
    cleared = copy.deepcopy(old)
    cleared.update(productVm=identity(None), internalVm=identity(None))
    rows = [snapshot("before-production-render", 1, old), snapshot("after-production-render", 1, old),
            snapshot("terminal-font100", 1, old, "OLD_SETTINGS_ENTRY", True),
            snapshot("disposed-before-clear", 1, old), snapshot("disposed-after-clear", 1, cleared),
            {"stage": "natural-save-callback", "requestedFont": 2, "outgoingActivity": identity(11),
             "outState": identity(51), "contentsRead": False},
            dict(snapshot("configuration-returned", 2, new), sameActivityAsOutgoing=False)]
    saved = {"stage": "natural-save-complete", "requestedFont": 2, "saveCallbacks": 1,
             "outgoingActivity": identity(11), "outState": identity(51), "productKey": "product:pr18-owner:1",
             "readAfterConfigurationReturnedAndIdle": True, "tabPresent": True,
             "tabType": "java.lang.String", "tab": "settings", "unexpectedTabRedacted": False}
    for name in ("registry", "provider", "product"):
        saved[name + "FieldPresent"] = True
        saved[name + "Field"] = {"type": "android.os.Bundle", "id": 52, "present": True}
    rows += [saved, snapshot("before-production-render", 2, new, counts=fresh)]
    boundary = snapshot("composition-owner-boundary", 2, new, counts=fresh)
    boundary.update({key: True for key in ("localOwnerMatchesActivity", "localRegistryMatchesActivity",
                    "extrasVmOwnerMatchesLocal", "extrasRegistryOwnerMatchesLocal", "sameVmAsActivity", "firstExistingVmObservation")})
    boundary.update(boundaryVm=identity(27), initialRestoredTab="settings", compositionFont=2, compositionView=native)
    rows += [boundary, snapshot("after-production-render", 2, new, "NEW_SETTINGS_ENTRY", True, fresh),
             snapshot("failure-list-RESTAURANT", 2, new, "NEW_SETTINGS_ENTRY", True, fresh)]
    for index, row in enumerate(rows, 1):
        row["sequence"] = index
        row["fixtureScope"] = "PURE_PYTHON_CONTRACT_FIXTURE_NOT_NATIVE"
    return rows


class GateAProducerContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe = subject()
        cls.collector, cls.collector_source = cls.probe.load_collector(REPO)
        cls.base_tree = cls.probe.tree(REPO, BASE)

    def test_exact_original_command_and_approved_collector_binding(self):
        self.assertEqual(self.probe.CASES, self.collector.selected_cases("flow"))
        self.assertEqual(self.probe.native_command(), ["./gradlew", ":app:connectedDebugAndroidTest", "--no-daemon",
                         "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(self.collector.selected_cases("flow"))])
        self.assertEqual(hashlib.sha256(self.collector_source).hexdigest(), self.probe.COLLECTOR_SHA256)
        with self.assertRaisesRegex(RuntimeError, "Original failing source|Tracked checkout"):
            self.collector.source_plan(REPO, "flow", 26)

    def test_manifest_binds_all_be8_files_and_four_original_method_blocks(self):
        plan = self.probe.source_plan(REPO, working_tree=True)
        self.assertEqual(plan["evidence_kind"], "DIAGNOSTIC_MODIFIED_INSTRUMENTATION")
        self.assertEqual(plan["base_sha"], BASE)
        self.assertFalse(plan["source_unchanged_from_real_failure"])
        self.assertEqual(plan["production_diff"], [])
        self.assertEqual(len(plan["unchanged_be8_bindings"]), len(self.base_tree) - 3)
        self.assertEqual(len(plan["original_method_blocks_sha256"]), 4)
        self.assertEqual({row["path"] for row in plan["diagnostic_delta"]}, self.probe.ALLOWED)
        self.assertTrue(plan["android_production_source_sha256"])

    def test_changed_original_assertion_is_rejected(self):
        name = TEST_DIR + "Pr18ScreenAcceptanceTest.kt"
        data = (REPO / name).read_bytes().replace(b'.assertExists()', b'.assertDoesNotExist()', 1)
        with self.assertRaisesRegex(RuntimeError, "Original actions/assertions/source changed"):
            self.probe.check_be8_binding(name, data, "100644", self.base_tree[name], original(name))

    def test_changed_production_source_and_mode_are_rejected(self):
        name = "android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivityContent.kt"
        data = original(name)
        with self.assertRaisesRegex(RuntimeError, "Production/unrelated be8 source changed"):
            self.probe.check_be8_binding(name, data + b"\n// contract fixture mutation\n", "100644", self.base_tree[name])
        with self.assertRaisesRegex(RuntimeError, "Original source mode changed"):
            self.probe.check_be8_binding(name, data, "100755", self.base_tree[name])

    def test_diagnostic_hook_cannot_hide_a_tab_intervention(self):
        name = TEST_DIR + "Pr18ScreenAcceptanceTest.kt"
        data = (REPO / name).read_bytes().replace(b"evidence.uiCausalProbe?.terminal(runtime!!)", b'vm.selectTab("restaurants")')
        with self.assertRaisesRegex(RuntimeError, "Unexpected diagnostic hook/action"):
            self.probe.original_projection(name, data)

    def test_production_composition_call_stays_first_and_unwrapped(self):
        name = TEST_DIR + "Pr18Fixtures.kt"
        source = (REPO / name).read_text()
        boundary = source.split("val content: @androidx.compose.runtime.Composable () -> Unit = {", 1)[1]
        self.assertEqual(boundary.split("MainActivityContent(", 1)[0].strip(), "")
        mutated = source.replace("            MainActivityContent(", "            LocalViewModelStoreOwner.current\n            MainActivityContent(", 1)
        with self.assertRaisesRegex(RuntimeError, "Production composition call position changed"):
            self.probe.original_projection(name, mutated.encode())

    def test_save_consume_handle_injection_and_owner_override_are_rejected(self):
        source = (REPO / (TEST_DIR + "Pr18UiCausalProbe.kt")).read_text()
        for action in ('provider.saveState()', 'consumeRestoredStateForKey("tab")', 'SavedStateHandle()',
                       'vm.selectTab("restaurants")', 'CompositionLocalProvider(owner) {}', 'Bundle()'):
            with self.subTest(action=action), self.assertRaisesRegex(RuntimeError, "state-changing/private/alternate-owner"):
                self.probe.helper_contract(source + "\n" + action)

    def test_callback_cannot_read_a_pre_androidx_save_bundle(self):
        source = (REPO / (TEST_DIR + "Pr18UiCausalProbe.kt")).read_text().replace(
            "naturalState = outState; saveCallbacks++", 'naturalState = outState; saveCallbacks++; outState.get("tab")')
        with self.assertRaisesRegex(RuntimeError, "before ComponentActivity's complete save"):
            self.probe.helper_contract(source)

    def test_native_run_rejects_local_or_wrong_ref_before_any_build(self):
        for environment in ({"GITHUB_ACTIONS": "false"}, {"GITHUB_ACTIONS": "true", "GITHUB_REF": "refs/heads/master"}):
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(sys, "argv", ["probe", "run"]), \
                 contextlib.redirect_stderr(io.StringIO()), mock.patch.object(self.probe, "run_probe") as run:
                with self.assertRaises(SystemExit) as exit_receipt:
                    self.probe.main()
                self.assertEqual(exit_receipt.exception.code, 2)
                run.assert_not_called()

    def test_workflow_runs_one_actual_api26_experiment_and_preserves_partial_evidence(self):
        workflow = (REPO / ".github/workflows/pr18-ui-causal-probe.yml").read_text()
        shells = self.probe.embedded_shells(workflow)
        self.assertEqual(len(shells), 6)
        self.assertEqual(sum(shell["kind"] == "script" for shell in shells), 1)
        self.assertIn("api-level: 26", workflow)
        self.assertNotIn("matrix:", workflow)
        self.assertIn("reviewed_candidate_sha", workflow)
        self.assertIn("setup-attempt.json", workflow)
        self.assertIn("preflight-source-manifest.json", workflow)
        self.assertIn('finish --live "$PR18_GATE_A_LIVE" --archive "$PR18_GATE_A_ARCHIVE"', workflow)
        self.assertEqual(workflow.count("if: always()"), 2)

    def test_full_scalar_chain_is_only_a_labelled_contract_fixture(self):
        self.assertEqual(self.probe.case_chain(scalar_fixture_rows()), "H1_CHAIN_RECORDED_WITH_ORIGINAL_IDENTITY_PATTERN")

    def test_failure_actual_font_contradiction_stops_on_h4(self):
        """Artificial negative scalars; no Android execution or native receipt."""
        rows = scalar_fixture_rows()
        failure = next(row for row in rows if row["stage"] == "failure-list-RESTAURANT")
        failure["owner"]["resourcesFont"] = 1
        self.assertEqual(failure["requestedFont"], 2)
        self.assertEqual(self.probe.case_chain(rows), "H4_NOT_ESTABLISHED")

    def test_failure_non_resumed_lifecycle_stops_on_h4(self):
        """Artificial negative scalars; actual font stays 2, never native proof."""
        rows = scalar_fixture_rows()
        failure = next(row for row in rows if row["stage"] == "failure-list-RESTAURANT")
        failure["owner"]["lifecycle"] = "CREATED"
        self.assertEqual(failure["owner"]["resourcesFont"], 2)
        self.assertEqual(self.probe.case_chain(rows), "H4_NOT_ESTABLISHED")

    def test_failure_owner_must_match_rendered_owner_on_h4(self):
        """Artificial owner contradictions/missing IDs; never native evidence."""
        for name in ("activity", "window", "registry", "provider", "store"):
            for mutation in ("different_failure_id", "both_ids_absent"):
                with self.subTest(owner=name, mutation=mutation):
                    rows = scalar_fixture_rows()
                    rendered = next(row for row in rows if row["stage"] == "after-production-render" and row["requestedFont"] == 2)
                    failure = next(row for row in rows if row["stage"] == "failure-list-RESTAURANT")
                    if mutation == "different_failure_id":
                        failure["owner"][name]["id"] = 999
                    else:
                        for row in (rendered, failure):
                            row["owner"][name].update(id=None, present=False)
                    self.assertEqual(self.probe.case_chain(rows), "H4_NOT_ESTABLISHED")

    def test_missing_natural_bundle_and_early_read_cannot_support_h1(self):
        rows = scalar_fixture_rows()
        saved = next(row for row in rows if row["stage"] == "natural-save-complete")
        saved["outState"]["present"] = False
        self.assertEqual(self.probe.case_chain(rows), "CAUSAL_CHAIN_MISSING_OR_CONTRADICTORY")
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "natural-save-callback")["contentsRead"] = True
        self.assertEqual(self.probe.case_chain(rows), "CAUSAL_CHAIN_MISSING_OR_CONTRADICTORY")

    def test_restaurants_boundary_vm_with_settings_route_falsifies_h1(self):
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "composition-owner-boundary")["initialRestoredTab"] = "restaurants"
        self.assertEqual(self.probe.case_chain(rows), "H1_PROXIMATE_SELECTOR_FALSIFIED")

    def test_wrong_composition_owner_supports_h3_before_any_tab_intervention(self):
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "composition-owner-boundary")["sameVmAsActivity"] = False
        self.assertEqual(self.probe.case_chain(rows), "H3_OWNER_PROVENANCE_DIFFERENT")

    def test_undisposed_roots_or_wrong_font_stop_on_h4(self):
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "disposed-after-clear")["screens"] = [{"tag": "screen:settings"}]
        self.assertEqual(self.probe.case_chain(rows), "H4_DISPOSAL_BOUNDARY_UNCERTAIN")
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "after-production-render" and row["requestedFont"] == 2)["owner"]["resourcesFont"] = 1
        self.assertEqual(self.probe.case_chain(rows), "H4_OWNER_FONT_READINESS_UNCERTAIN")

    def test_changed_vm_or_entry_pattern_does_not_establish_cause(self):
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "after-production-render" and row["requestedFont"] == 2)["owner"]["productVm"]["id"] = 17
        self.assertEqual(self.probe.case_chain(rows), "CAUSAL_CHAIN_MISSING_OR_CONTRADICTORY")
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "terminal-font100")["navigation"][0]["entryId"] = "NEW_SETTINGS_ENTRY"
        self.assertEqual(self.probe.case_chain(rows), "CAUSAL_CHAIN_MISSING_OR_CONTRADICTORY")

    def test_reordered_natural_save_receipt_cannot_claim_causality(self):
        rows = scalar_fixture_rows()
        next(row for row in rows if row["stage"] == "natural-save-complete")["sequence"] = 1
        with self.assertRaisesRegex(ValueError, "do not follow the original causal boundary"):
            self.probe.case_chain(rows)

    def test_source_only_empty_reports_and_unexpected_pass_never_approve_gate_a(self):
        directory = os.environ.get("PR18_GATE_A_TEST_SCRATCH", os.environ.get("RUNNER_TEMP"))
        with tempfile.TemporaryDirectory(prefix="PURE_PYTHON_GATE_A_CONTRACT_", dir=directory) as temporary:
            results = self.collector.inspect_results(Path(temporary) / "no-reports", self.probe.CASES, None)
            self.assertFalse(self.probe.original_red(results))
            gate = self.probe.inspect_gate_a(temporary, results, self.collector)
            self.assertEqual(gate["gate_a_classification"], "INCOMPLETE_NATIVE_PROOF")
            self.assertFalse(gate["approval"])
            self.assertFalse(gate["correction_implemented"])
            self.assertFalse(gate["full_ci_or_app_pass"])
            results["classification"] = "COMPLETE_PASS"
            results["raw_gradle_exit"] = 0
            gate = self.probe.inspect_gate_a(temporary, results, self.collector)
            self.assertEqual(gate["gate_a_classification"], "NONINTERFERENCE_NOT_ESTABLISHED")


class GateASourceContracts(unittest.TestCase):
    def test_passive_native_probe_contract_before_scaffolding(self):
        path = REPO / (TEST_DIR + "Pr18UiCausalProbe.kt")
        self.assertTrue(path.is_file(), "Gate A lacks its passive natural-save/owner-boundary observer")
        source = path.read_text()
        for required in (
            "Application.ActivityLifecycleCallbacks", "onActivitySaveInstanceState",
            "androidx.lifecycle.BundlableSavedStateRegistry.key",
            "androidx.lifecycle.internal.SavedStateHandlesProvider",
            "androidx.lifecycle.internal.SavedStateHandlesVM", "getSavedStateProvider",
            "restoredTab", "unregisterActivityLifecycleCallbacks", "finally",
            "DIAGNOSTIC_MODIFIED_INSTRUMENTATION", "PR18_GATE_A",
        ):
            self.assertIn(required, source)
        forbidden = r"\b(?:createSavedStateHandle|consumeRestoredStateForKey|SavedStateHandle|ViewModelProvider|selectTab|performClick|injectInputEvent|Thread\.sleep)\s*\(|\.saveState\s*\(|\.clear\s*\(|\b(?:setContent|CompositionLocalProvider)\s*[({]"
        self.assertIsNone(re.search(forbidden, source), "Passive observer contains a state-changing/alternate-owner action")

    def test_every_original_f7_method_body_remains_byte_identical(self):
        path = TEST_DIR + "Pr18ScreenAcceptanceTest.kt"
        before = original(path)
        after = (REPO / path).read_bytes()
        start = b"    @Test fun " + METHOD.encode() + b"() {"
        self.assertEqual(before[before.index(start):], after[after.index(start):])
        self.assertIn(b"for (font in listOf(1f, 2f))", after)

    def test_no_production_or_unrelated_source_delta_is_allowed(self):
        changed = set(subprocess.check_output(
            ["git", "diff", "--name-only", BASE], cwd=REPO).decode().splitlines())
        added = set(subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard"], cwd=REPO).decode().splitlines())
        self.assertFalse((changed | added) - ALLOWED)


if __name__ == "__main__":
    unittest.main(verbosity=2)
