#!/usr/bin/env python3
"""UNIT FIXTURES ONLY: none of these XML/ADB samples are Android results."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib
import struct
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "diagnostics", Path(__file__).with_name("pr18-failure-diagnostics.py"))
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


def fixture_report(directory, expected, outcomes=None):
    outcomes = outcomes or {}
    suite = ET.Element("testsuite", tests=str(len(expected)),
                       failures=str(sum(v == "failure" for v in outcomes.values())),
                       errors=str(sum(v == "error" for v in outcomes.values())),
                       skipped=str(sum(v == "skipped" for v in outcomes.values())))
    for case_id in expected:
        cls, name = case_id.split("#", 1)
        case = ET.SubElement(suite, "testcase", classname=cls, name=name,
                             fixture="UNIT_FIXTURE_NOT_ANDROID_EVIDENCE")
        if case_id in outcomes:
            ET.SubElement(case, outcomes[case_id], message="labelled unit fixture")
    Path(directory, "TEST-unit-fixture.xml").write_bytes(ET.tostring(suite))


def png_fixture():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(
            ">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(b"\0\0\0\0")) + chunk(b"IEND", b""))


class FixtureDevice:
    """Explicit fake device; only the collector interface is exercised."""
    def __init__(self):
        self.files = {"frame.png": (png_fixture(), 1), "receipt.txt": (b"one\n", 1)}
        self.pulls = []
        self.mutate_during_pull = None

    def inventory(self):
        return {name: (len(data), stamp) for name, (data, stamp) in self.files.items()}

    def stat(self, name):
        data, stamp = self.files[name]
        return len(data), stamp

    def pull(self, name, destination):
        self.pulls.append(name)
        Path(destination).write_bytes(self.files[name][0])
        if self.mutate_during_pull == name:
            data, stamp = self.files[name]
            self.files[name] = (data + b"two\n", stamp + 1)
            self.mutate_during_pull = None


class DiagnosticInterfaceFixtures(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="pr18-labelled-unit-fixtures-")
        self.root = Path(self.tmp.name)
        self.expected = diagnostics.selected_cases("flow")

    def tearDown(self):
        self.tmp.cleanup()

    def test_all_four_named_variants_are_explicit_selectors(self):
        for group in ("flow", "landscape", "password", "conflict"):
            ids = diagnostics.selected_cases(group)
            self.assertEqual(4, len(ids))
            self.assertEqual(4, len(set(ids)))
            self.assertTrue(all("[" in value for value in ids))
            self.assertTrue(any("QUIET_GRID-dark=true]" in value for value in ids))
        self.assertEqual(1, len(diagnostics.selected_cases("fold")))
        self.assertEqual(17, sum(len(diagnostics.selected_cases(g)) for g in diagnostics.METHODS))

    def inspect(self, outcomes=None, raw_exit=0, expected=None):
        fixture_report(self.root, self.expected if expected is None else expected, outcomes)
        return diagnostics.inspect_results(self.root, self.expected, raw_exit)

    def test_pass_requires_all_exact_cases_and_zero_raw_exit(self):
        result = self.inspect()
        self.assertEqual("COMPLETE_PASS", result["classification"])
        self.assertEqual({"passed": 4, "failed": 0, "errors": 0, "skipped": 0,
                          "missing": 0, "duplicate": 0, "unexpected": 0}, result["counts"])

    def test_real_failures_remain_red_with_full_failure_nodes(self):
        result = self.inspect({self.expected[0]: "failure"}, raw_exit=1)
        self.assertEqual("COMPLETE_FAIL", result["classification"])
        self.assertEqual(1, result["counts"]["failed"])
        self.assertIn("labelled unit fixture", result["cases"][0]["details"][0])
        self.assertEqual(1, result["raw_gradle_exit"])

    def test_error_is_separate_and_remains_red(self):
        result = self.inspect({self.expected[0]: "error"}, raw_exit=1)
        self.assertEqual("COMPLETE_FAIL", result["classification"])
        self.assertEqual(1, result["counts"]["errors"])

    def test_skipped_is_not_a_pass(self):
        result = self.inspect({self.expected[0]: "skipped"})
        self.assertEqual("INVALID_EXECUTION", result["classification"])
        self.assertEqual(1, result["counts"]["skipped"])

    def test_missing_parameter_is_not_red_or_green_execution(self):
        result = self.inspect(expected=self.expected[:-1], raw_exit=124)
        self.assertEqual("INVALID_EXECUTION", result["classification"])
        self.assertEqual(1, result["counts"]["missing"])
        self.assertEqual(124, result["raw_gradle_exit"])

    def test_duplicate_and_unrelated_methods_are_rejected(self):
        extra = "fixture.Unrelated#mustNotRun"
        result = self.inspect(expected=self.expected + [self.expected[0], extra])
        self.assertEqual("INVALID_EXECUTION", result["classification"])
        self.assertEqual(1, result["counts"]["duplicate"])
        self.assertEqual(1, result["counts"]["unexpected"])

    def test_wrong_class_cannot_satisfy_method_name(self):
        altered = [value.replace("Pr18ScreenAcceptanceTest", "FixtureWrongOwner")
                   for value in self.expected]
        result = self.inspect(expected=altered)
        self.assertEqual(4, result["counts"]["missing"])
        self.assertEqual("INVALID_EXECUTION", result["classification"])

    def test_missing_or_malformed_reports_retain_missing_count(self):
        result = diagnostics.inspect_results(self.root, self.expected, None)
        self.assertEqual(4, result["counts"]["missing"])
        self.assertEqual("INVALID_EXECUTION", result["classification"])
        Path(self.root, "bad.xml").write_text("<testsuite")
        result = diagnostics.inspect_results(self.root, self.expected, 1)
        self.assertTrue(result["invalid_reasons"])

    def test_suite_counters_cannot_hide_missing_failures(self):
        self.inspect({self.expected[0]: "failure"}, raw_exit=1)
        p = Path(self.root, "TEST-unit-fixture.xml")
        p.write_text(p.read_text().replace('failures="1"', 'failures="0"'))
        result = diagnostics.inspect_results(self.root, self.expected, 1)
        self.assertEqual("INVALID_EXECUTION", result["classification"])

    def test_nonzero_or_absent_exit_never_becomes_pass(self):
        self.assertEqual("INFRA_FAILURE", self.inspect(raw_exit=7)["classification"])
        self.assertEqual("INVALID_EXECUTION", self.inspect(raw_exit=None)["classification"])

    def test_collector_only_repulls_changed_files_and_survives_uninstall(self):
        device = FixtureDevice()
        collector = diagnostics.Collector(self.root, device)
        collector.sample()
        collector.sample()
        self.assertEqual(["frame.png", "receipt.txt"], device.pulls)
        device.files["receipt.txt"] = (b"one\ntwo\n", 2)
        collector.sample()
        self.assertEqual(["frame.png", "receipt.txt", "receipt.txt"], device.pulls)
        device.files.clear()  # UTP's app-data removal must not delete retained files.
        collector.sample()
        self.assertEqual(b"one\ntwo\n", Path(self.root, "device", "receipt.txt").read_bytes())
        self.assertTrue(Path(self.root, "device", "frame.png").is_file())

    def test_mutating_pull_keeps_previous_complete_receipt(self):
        device = FixtureDevice()
        collector = diagnostics.Collector(self.root, device)
        collector.sample()
        device.files["receipt.txt"] = (b"one\nchanged\n", 2)
        device.mutate_during_pull = "receipt.txt"
        collector.sample()
        self.assertEqual(b"one\n", Path(self.root, "device", "receipt.txt").read_bytes())
        collector.sample()
        self.assertEqual(device.files["receipt.txt"][0], Path(self.root, "device", "receipt.txt").read_bytes())
        self.assertFalse(list(self.root.rglob("*.part")))

    def test_incomplete_png_is_retried_without_publishing_partial_file(self):
        device = FixtureDevice()
        complete = device.files["frame.png"]
        device.files["frame.png"] = (complete[0][:-8], 1)
        collector = diagnostics.Collector(self.root, device)
        collector.sample()
        self.assertFalse(Path(self.root, "device", "frame.png").exists())
        device.files["frame.png"] = complete
        collector.sample()
        self.assertEqual(complete[0], Path(self.root, "device", "frame.png").read_bytes())

    def test_remote_paths_cannot_escape_collector(self):
        device = FixtureDevice()
        device.files["../outside.txt"] = (b"fixture\n", 1)
        diagnostics.Collector(self.root, device).sample()
        self.assertFalse(Path(self.root.parent, "outside.txt").exists())

    def test_collector_budget_does_not_block_command_timeout(self):
        device = FixtureDevice()
        diagnostics.Collector(self.root, device).sample(deadline=0)
        self.assertEqual([], device.pulls)
        rows = [json.loads(line) for line in Path(self.root, "collector.jsonl").read_text().splitlines()]
        self.assertEqual(2, next(row["remaining"] for row in rows
                                 if row["event"] == "collection-budget-ended"))

    def test_native_focus_and_input_receipts_keep_raw_exits_and_timestamps(self):
        class NativeFixture:
            def command(self, *args):
                return diagnostics.subprocess.CompletedProcess(
                    args, 0, b"UNIT_FIXTURE_NOT_ANDROID_EVIDENCE\n", b"")
        diagnostics.native_sample(self.root, NativeFixture(), 5)
        rows = [json.loads(line) for line in Path(self.root, "collector.jsonl").read_text().splitlines()]
        self.assertEqual(["window", "input"], [row["command"] for row in rows])
        self.assertTrue(all(row["event"] == "native" and row["raw_exit"] == 0 for row in rows))
        self.assertTrue(all(row["end_ns"] >= row["start_ns"] for row in rows))

    def test_source_guard_rejects_non_diagnostic_changes(self):
        responses = [b"fixture-head", b"android/feature/src/main/Changed.kt\n"]
        with patch.dict(diagnostics.os.environ, {}, clear=True):
            with patch.object(diagnostics.subprocess, "check_output", side_effect=responses):
                with self.assertRaisesRegex(RuntimeError, "Original failing source/gates changed"):
                    diagnostics.source_plan(self.root, "flow", 26)

    def test_source_guard_rejects_head_not_matching_actions_sha(self):
        with patch.dict(diagnostics.os.environ, {"GITHUB_SHA": "different-fixture-head"}, clear=True):
            with patch.object(diagnostics.subprocess, "check_output", return_value=b"fixture-head"):
                with self.assertRaisesRegex(RuntimeError, "differs from GITHUB_SHA"):
                    diagnostics.source_plan(self.root, "flow", 26)

    def test_seal_refuses_live_writer_or_missing_stop_receipt(self):
        archive = self.root.parent / (self.root.name + ".tar.gz")
        with self.assertRaises(RuntimeError):
            diagnostics.seal(self.root, archive)
        Path(self.root, "COLLECTOR_STOPPED.json").write_text(json.dumps({"writers_stopped": True}))
        with diagnostics.writer_lock(self.root):
            with self.assertRaises(RuntimeError):
                diagnostics.seal(self.root, archive)
        self.assertFalse(archive.exists())

    def test_hard_interruption_retains_missing_counts_without_live_archive(self):
        archive = self.root / "final" / "evidence.tar.gz"
        live = self.root / "live"
        live.mkdir()
        Path(live, "RUN_STARTED.json").write_text('{"fixture": "UNIT_FIXTURE_NOT_ANDROID_EVIDENCE"}')
        with patch("builtins.print"):
            status = diagnostics.finish(self.root, live, archive, "flow")
        self.assertEqual(1, status)
        self.assertFalse(archive.exists())
        result = json.loads(Path(archive.parent, "unsealed-attempt.json").read_text())
        self.assertEqual(4, result["counts"]["missing"])
        self.assertIsNone(result["raw_gradle_exit"])
        self.assertEqual("INVALID_EXECUTION", result["classification"])

    def test_archive_is_immutable_and_bound_to_file_hashes(self):
        import tarfile
        archive = self.root.parent / (self.root.name + ".tar.gz")
        Path(self.root, "COLLECTOR_STOPPED.json").write_text(json.dumps({"writers_stopped": True}))
        Path(self.root, "retained.txt").write_text("labelled unit fixture\n")
        diagnostics.seal(self.root, archive)
        with tarfile.open(archive) as tar:
            self.assertIn("retained.txt", tar.getnames())
            self.assertIn("archive-manifest.json", tar.getnames())
        previous = archive.read_bytes()
        Path(self.root, "retained.txt").write_text("later mutation\n")
        self.assertEqual(previous, archive.read_bytes())
        archive.unlink()
        archive.with_suffix(archive.suffix + ".sha256").unlink()


if __name__ == "__main__":
    unittest.main()
