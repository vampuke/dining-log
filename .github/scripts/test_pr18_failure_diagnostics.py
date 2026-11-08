#!/usr/bin/env python3
"""UNIT FIXTURES ONLY: none of these XML/ADB samples are Android results."""
import importlib.util
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
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


class WorkflowContextFixtures(unittest.TestCase):
    def test_private_paths_are_initialized_before_all_consumers(self):
        workflow = (Path(__file__).resolve().parents[2] /
                    ".github/workflows/pr18-failure-diagnostics.yml").read_text()
        job, steps = workflow.split("    steps:\n", 1)
        self.assertNotRegex(job, r"\$\{\{\s*runner\b",
                            "runner context is unsupported in jobs.<job_id>.env")
        initializer, consumers = steps.split("      - ", 2)[1:]
        self.assertNotIn("        if:", initializer)
        self.assertIn("        run: |\n", initializer,
                      "Initialize private paths before checkout can fail")
        script = "\n".join(line[10:] for line in
                           initializer.split("        run: |\n", 1)[1].splitlines())
        self.assertIn('--live "$PR18_DIAGNOSTIC_LIVE"', consumers)
        self.assertIn('--archive "$PR18_DIAGNOSTIC_ARCHIVE"', consumers)
        upload = consumers.split("          path: |\n", 1)[1].split(
            "          if-no-files-found:", 1)[0]
        with tempfile.TemporaryDirectory(prefix="pr18-workflow-context-fixture-") as temp:
            runner_temp, github_env = Path(temp) / "runner temp", Path(temp) / "github-env"
            for api in (26, 35):
                for group in diagnostics.METHODS:
                    with self.subTest(api=api, group=group):
                        rendered = script.replace("${{ matrix.api }}", str(api)).replace(
                            "${{ matrix.group }}", group)
                        github_env.write_text("")
                        result = subprocess.run(["bash", "-e", "-c", rendered],
                            env={"RUNNER_TEMP": str(runner_temp), "GITHUB_ENV": str(github_env)},
                            capture_output=True, text=True, timeout=5)
                        self.assertEqual(0, result.returncode, result.stderr)
                        published = dict(line.split("=", 1) for line in github_env.read_text().splitlines())
                        archive = runner_temp / f"pr18-failure-final-{api}-{group}/evidence.tar.gz"
                        self.assertEqual(published, {
                            "PR18_DIAGNOSTIC_LIVE": str(runner_temp / f"pr18-failure-live-{api}-{group}"),
                            "PR18_DIAGNOSTIC_ARCHIVE": str(archive)})
                        paths = upload.replace("${{ runner.temp }}", str(runner_temp)).replace(
                            "${{ matrix.api }}", str(api)).replace("${{ matrix.group }}", group)
                        self.assertEqual([line.strip() for line in paths.splitlines()],
                                         [str(archive), str(archive) + ".sha256",
                                          str(archive.parent / "unsealed-attempt.json")])


INTERFACE_KIND = "PURE_SUBPROCESS_INTERFACE_FIXTURE_NOT_ANDROID"


def linux_process(pid):
    # Independent kernel observation, not the controller's cleanup predicate.
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return {"pid": pid, "state": fields[0], "ppid": int(fields[1]),
            "pgid": int(fields[2]), "sid": int(fields[3]), "start_ticks": int(fields[19])}


class LinuxLifecycleFixtures(unittest.TestCase):
    """Real owned Linux children; every output is an interface fixture, not Android."""
    def setUp(self):
        evidence = os.environ.get("PR18_INTERFACE_EVIDENCE")
        self.root = Path(tempfile.mkdtemp(prefix=self._testMethodName + "-", dir=evidence))
        self.preserve = evidence is not None
        self.children = []
        # Reap orphaned fixture grandchildren ourselves, including zombies.
        self.libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        if self.libc.prctl(37, ctypes.byref(previous), 0, 0, 0) != 0:
            raise RuntimeError("Fixture cannot read Linux subreaper setting")
        self.subreaper = previous.value
        if self.libc.prctl(36, 1, 0, 0, 0) != 0:
            raise RuntimeError("Fixture cannot own/reap its orphaned descendants")

    def tearDown(self):
        import shutil
        for process, identity, child in self.children:
            # Fixture-only identity checks; never signal by process name.
            for pid, birth in ((child["pid"], child["start_ticks"]),
                               (process.pid, identity["start_ticks"])):
                try:
                    current = linux_process(pid)
                    if current["start_ticks"] == birth and current["state"] not in ("Z", "X"):
                        fd = os.pidfd_open(pid)
                        try:
                            signal.pidfd_send_signal(fd, signal.SIGKILL)
                        finally:
                            os.close(fd)
                except (FileNotFoundError, ProcessLookupError):
                    pass
            process.wait(timeout=3)
            if process.stdout:
                process.stdout.close()
            deadline = time.monotonic() + 3
            while True:
                try:
                    pid, _ = os.waitpid(-process.pid, os.WNOHANG)
                    if pid == 0:
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Fixture descendants were not reaped")
                        time.sleep(.01)
                except ChildProcessError:
                    break
        self.libc.prctl(36, self.subreaper, 0, 0, 0)
        if not self.preserve:
            shutil.rmtree(self.root)

    def writer_group(self, leader_exits=False, grandchild=False):
        script = '''
import os, signal, sys, time
from pathlib import Path
writer = os.fork()
if writer == 0:
    if sys.argv[3] == "grandchild" and os.fork() != 0:
        os._exit(0)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    with Path(sys.argv[1]).open("ab", buffering=0) as stream:
        stream.write(b"PURE_SUBPROCESS_INTERFACE_FIXTURE_NOT_ANDROID\\n")
        os.write(1, (str(os.getpid()) + "\\n").encode())
        while True:
            stream.write(b"owned-writer\\n")
            time.sleep(.01)
while not Path(sys.argv[2]).exists():
    time.sleep(.01)
os._exit(0)
'''
        output, exit_trigger = self.root / "writer.txt", self.root / "leader-exit"
        leader = subprocess.Popen([sys.executable, "-B", "-c", script, str(output),
                                   str(exit_trigger), "grandchild" if grandchild else "child"],
                                  stdout=subprocess.PIPE, start_new_session=True)
        identity = linux_process(leader.pid)
        # The fixture supplies birth credentials captured while its owned leader
        # is unreaped. Production must capture these at its own Popen sites.
        leader.owned_identity = identity
        import select
        if not select.select([leader.stdout], [], [], 3)[0]:
            raise RuntimeError("Writer fixture readiness timeout (not assertion RED)")
        child = linux_process(int(leader.stdout.readline()))
        self.children.append((leader, identity, child))
        if leader_exits:
            exit_trigger.touch()
            deadline = time.monotonic() + 3
            while linux_process(leader.pid)["state"] != "Z":
                if time.monotonic() >= deadline:
                    raise RuntimeError("Leader fixture exit timeout (not assertion RED)")
                time.sleep(.01)
        return leader, child, output

    def assert_writer_stopped(self, child, output, returned, elapsed):
        before = output.stat().st_size
        try:
            observed = linux_process(child["pid"])
        except FileNotFoundError:
            observed = None
        deadline = time.monotonic() + .12
        while time.monotonic() < deadline and output.stat().st_size == before:
            time.sleep(.005)
        after = output.stat().st_size
        receipt = {"evidence_kind": INTERFACE_KIND, "child": child, "observed": observed,
                   "returned": returned, "returned_seconds": elapsed,
                   "bytes_at_return": before, "bytes_after_observation": after}
        diagnostics.write_json(self.root / "observation.json", receipt)
        self.assertTrue(observed is None or observed["state"] in ("Z", "X"),
                        f"Owned writer remains live after cleanup: {receipt}")
        self.assertEqual(before, after, "Owned writer continued writing after cleanup returned")

    def test_c1_term_resistant_child_stops_after_leader_exits(self):
        leader, child, output = self.writer_group()
        before = time.monotonic()
        returned = diagnostics.stop_process(leader)
        self.assert_writer_stopped(child, output, returned, time.monotonic() - before)

    def test_c1_exited_parent_and_term_resistant_grandchild(self):
        leader, child, output = self.writer_group(leader_exits=True, grandchild=True)
        before = time.monotonic()
        returned = diagnostics.stop_process(leader)
        self.assert_writer_stopped(child, output, returned, time.monotonic() - before)

    def test_c1_unowned_process_is_never_signalled(self):
        leader, child, output = self.writer_group()
        del leader.owned_identity
        with patch.object(diagnostics.os, "killpg", wraps=os.killpg) as kill:
            returned = diagnostics.stop_process(leader)
        diagnostics.write_json(self.root / "observation.json", {
            "evidence_kind": INTERFACE_KIND, "returned": returned, "signals": kill.call_count})
        self.assertEqual(0, kill.call_count, "Missing birth credentials must not authorize signals")
        self.assertIsInstance(returned, dict)
        self.assertFalse(returned["writers_stopped"])

    def test_c1_failed_process_inquiry_cannot_claim_writers_stopped(self):
        leader, child, output = self.writer_group()
        with patch.object(diagnostics.os, "scandir", side_effect=PermissionError("fixture inquiry failure")):
            returned = diagnostics.stop_process(leader)
        diagnostics.write_json(self.root / "observation.json", {
            "evidence_kind": INTERFACE_KIND, "returned": returned})
        self.assertIsInstance(returned, dict, "Cleanup must retain a verifiable failure receipt")
        self.assertFalse(returned["writers_stopped"])
        self.assertTrue(returned["errors"])

    def test_c1_reaped_leader_refuses_group_signal_and_keeps_known_raw_exit(self):
        leader, child, output = self.writer_group(leader_exits=True)
        self.assertEqual(0, leader.wait(timeout=3))
        with patch.object(diagnostics.os, "killpg", wraps=os.killpg) as kill:
            returned = diagnostics.stop_process(leader)
        diagnostics.write_json(self.root / "observation.json", {
            "evidence_kind": INTERFACE_KIND, "returned": returned, "signals": kill.call_count})
        self.assertFalse(returned["writers_stopped"])
        self.assertEqual(0, kill.call_count)
        self.assertEqual(0, returned["raw_exit"], "Known raw leader exit must survive identity-check failure")

    def test_c1_zombie_only_group_is_complete_without_signals(self):
        process = diagnostics.start_process([sys.executable, "-B", "-c", "import sys; sys.exit(0)"])
        identity = linux_process(process.pid)
        self.children.append((process, identity, identity))
        deadline = time.monotonic() + 3
        while linux_process(process.pid)["state"] != "Z":
            if time.monotonic() >= deadline:
                raise RuntimeError("Zombie fixture exit timeout")
            time.sleep(.01)
        returned = diagnostics.stop_process(process)
        diagnostics.write_json(self.root / "observation.json", {"evidence_kind": INTERFACE_KIND, "returned": returned})
        self.assertTrue(returned["writers_stopped"])
        self.assertEqual([], returned["signals"])
        self.assertEqual(0, returned["raw_exit"])

    def test_c1_cleanup_does_not_signal_unrelated_group(self):
        other = diagnostics.start_process([sys.executable, "-B", "-c", "import time; time.sleep(60)"])
        identity = linux_process(other.pid)
        self.children.append((other, identity, identity))
        leader, child, output = self.writer_group()
        returned = diagnostics.stop_process(leader)
        self.assertTrue(returned["writers_stopped"])
        self.assertEqual(identity["start_ticks"], linux_process(other.pid)["start_ticks"])
        self.assertIsNone(diagnostics.process_exit(other), "Unrelated process group must remain live")

    def wait_fixture_exit(self, role):
        process = self.probe_processes[role]
        deadline = time.monotonic() + 3
        while linux_process(process.pid)["state"] != "Z":
            if time.monotonic() >= deadline:
                raise RuntimeError(f"{role} fixture did not exit (not assertion RED)")
            time.sleep(.01)

    def exit_fixture(self, role):
        (self.root / (role + "-exit")).touch()
        self.wait_fixture_exit(role)

    def probe_fixture(self, hook, logcat_exit=7, gradle_exit=0, stop_override=None,
                      deadline=10, signal_site=None):
        self.probe_processes = {}
        real_popen, real_collector, real_sleep = subprocess.Popen, diagnostics.Collector, time.sleep
        plan = {"evidence_kind": INTERFACE_KIND, "expected_case_ids": diagnostics.selected_cases("flow"),
                "android_source_sha256": {}, "selector": ",".join(diagnostics.selected_cases("flow"))}
        owner = self

        class Device(FixtureDevice):
            prefix = ["PURE_SUBPROCESS_INTERFACE_FIXTURE_NOT_ANDROID-NO-ADB"]
            signalled = False

            def interrupt_once(self, site):
                if signal_site == site and not self.signalled:
                    self.signalled = True
                    os.kill(os.getpid(), signal.SIGINT)

            def inventory(self):
                self.interrupt_once("inventory")
                return super().inventory()

            def pull(self, name, destination):
                self.interrupt_once("pull")
                return super().pull(name, destination)

            def command(self, *args):
                if args == ("shell", "dumpsys window windows"):
                    self.interrupt_once("native")
                output = b"26\n" if args == ("shell", "getprop ro.build.version.sdk") else INTERFACE_KIND.encode()
                return subprocess.CompletedProcess(args, 0, output, b"")

        class ControlledCollector(real_collector):
            samples = 0

            def sample(self, deadline=None):
                super().sample(deadline)
                self.samples += 1
                hook(self.samples)

        def popen(argv, **kwargs):
            if argv[0] == Device.prefix[0]:
                role, code = "logcat", logcat_exit
            elif argv[0] == "./gradlew":
                role, code = "gradle", gradle_exit
            else:
                raise RuntimeError(f"Forbidden fixture subprocess argv: {argv}")
            script = '''
import sys, time
from pathlib import Path
print("PURE_SUBPROCESS_INTERFACE_FIXTURE_NOT_ANDROID", flush=True)
while not Path(sys.argv[1]).exists():
    time.sleep(.005)
sys.exit(int(sys.argv[2]))
'''
            process = real_popen([sys.executable, "-B", "-c", script,
                                  str(owner.root / (role + "-exit")), str(code)],
                                 stdout=kwargs["stdout"], stderr=kwargs["stderr"],
                                 start_new_session=kwargs["start_new_session"])
            identity = linux_process(process.pid)
            owner.children.append((process, identity, identity))
            owner.probe_processes[role] = process
            return process

        live = self.root / "live"
        handlers = {signum: signal.getsignal(signum) for signum in (signal.SIGINT, signal.SIGTERM)}
        from contextlib import ExitStack
        try:
            with ExitStack() as stack:
                stack.enter_context(patch.object(diagnostics, "source_plan", return_value=plan))
                stack.enter_context(patch.object(diagnostics.subprocess, "check_output", return_value=b"fixture\tdevice\n"))
                stack.enter_context(patch.object(diagnostics, "AdbDevice", return_value=Device()))
                stack.enter_context(patch.object(diagnostics, "Collector", ControlledCollector))
                stack.enter_context(patch.object(diagnostics.subprocess, "Popen", side_effect=popen))
                stack.enter_context(patch.object(diagnostics.time, "sleep", side_effect=lambda s: real_sleep(min(s, .01))))
                if stop_override is not None:
                    stack.enter_context(patch.object(diagnostics, "stop_process", side_effect=stop_override))
                status = diagnostics.run_probe(self.root, live, "flow", 26, deadline)
        finally:
            for signum, handler in handlers.items():
                signal.signal(signum, handler)
        exits = json.loads((live / "exit.json").read_text())
        diagnostics.write_json(self.root / "observation.json", {
            "evidence_kind": INTERFACE_KIND, "controller_exit": status, "exits": exits,
            "children": {role: p.returncode for role, p in self.probe_processes.items()}})
        return status, exits, live

    def early_logcat_hook(self, count):
        if count == 1:
            self.exit_fixture("logcat")
            self.exit_fixture("gradle")

    def test_c2_logcat_exit_7_before_gradle_exit_0_is_failure(self):
        status, exits, live = self.probe_fixture(self.early_logcat_hook)
        self.assertNotEqual(0, status, "Lost logcat stream cannot yield run_exit=0")
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertEqual(7, exits["raw_logcat_exit"])
        self.assertEqual("LIVE_LOGCAT_EXITED", exits["termination"])
        self.assertTrue((live / "device/receipt.txt").exists())
        self.assertIn(INTERFACE_KIND, (live / "logcat.txt").read_text())

    def test_c2_clean_early_logcat_exit_is_failure(self):
        status, exits, live = self.probe_fixture(self.early_logcat_hook, logcat_exit=0)
        self.assertNotEqual(0, status, "Even exit0 before owned teardown loses continuous collection")
        self.assertEqual(0, exits["raw_logcat_exit"])
        self.assertEqual("LIVE_LOGCAT_EXITED", exits["termination"])

    def test_c2_logcat_loss_during_final_collection_is_failure(self):
        def hook(count):
            self.exit_fixture("gradle" if count == 1 else "logcat")
        status, exits, live = self.probe_fixture(hook, logcat_exit=0)
        self.assertNotEqual(0, status, "Final collection is still part of continuous logcat lifetime")
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertEqual(0, exits["raw_logcat_exit"])
        self.assertEqual("LIVE_LOGCAT_EXITED", exits["termination"])

    def test_c2_nonzero_gradle_exit_is_preserved_with_logcat_loss(self):
        status, exits, live = self.probe_fixture(self.early_logcat_hook, gradle_exit=9)
        self.assertEqual(9, status)
        self.assertEqual(9, exits["raw_gradle_exit"])
        self.assertEqual("LIVE_LOGCAT_EXITED", exits["termination"])

    def test_c2_finish_rejects_missing_or_unhealthy_logcat_lifecycle(self):
        live = self.root / "live"
        reports = live / "build/outputs/androidTest-results/connected"
        reports.mkdir(parents=True)
        expected = diagnostics.selected_cases("flow")
        fixture_report(reports, expected)
        for directory in ("debug", "androidTest/debug"):
            apk = live / "build/outputs/apk" / directory / "fixture.apk"
            apk.parent.mkdir(parents=True)
            apk.write_text(INTERFACE_KIND)
        diagnostics.write_json(live / "COLLECTOR_STOPPED.json", {"writers_stopped": True})
        diagnostics.write_json(live / "plan.json", {"expected_case_ids": expected, "android_source_sha256": {}})
        for variant, extra in (("missing", {}), ("early-exit", {
                "raw_logcat_exit": 0, "logcat_lifecycle": {"healthy": False, "premature_exit": 0}})):
            with self.subTest(variant=variant):
                diagnostics.write_json(live / "exit.json", {
                    "raw_gradle_exit": 0, "run_exit": 0, "termination": None, **extra})
                archive = self.root / variant / "evidence.tar.gz"
                with patch("builtins.print"):
                    status = diagnostics.finish(self.root, live, archive, "flow")
                result = json.loads((live / "results.json").read_text())
                self.assertNotEqual(0, status, "Exact case/XML pass cannot stand in for collector health")
                self.assertEqual("INVALID_EXECUTION", result["classification"])
                self.assertEqual(4, result["counts"]["passed"])

    def test_c2_normal_owned_logcat_term_is_healthy(self):
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        status, exits, live = self.probe_fixture(hook)
        self.assertEqual(0, status)
        self.assertIsNone(exits["termination"])
        self.assertEqual(-signal.SIGTERM, exits["raw_logcat_exit"])
        self.assertTrue(exits["logcat_lifecycle"]["healthy"])
        self.assertIsNone(exits["logcat_lifecycle"]["premature_exit"])

    def test_c1_failed_cleanup_receipt_blocks_run_and_archive(self):
        real_stop = diagnostics.stop_process
        def stop(process):
            receipt = real_stop(process)
            if process is self.probe_processes.get("gradle"):
                receipt["writers_stopped"] = False
                receipt["errors"].append("Explicit fixture: process inquiry cannot be verified")
            return receipt
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        status, exits, live = self.probe_fixture(hook, stop_override=stop)
        self.assertNotEqual(0, status)
        self.assertEqual("OWNED_CLEANUP_FAILED", exits["termination"])
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertFalse(json.loads((live / "COLLECTOR_STOPPED.json").read_text())["writers_stopped"])
        archive = self.root / "final/evidence.tar.gz"
        with patch("builtins.print"):
            result = diagnostics.finish(self.root, live, archive, "flow")
        self.assertEqual(1, result)
        self.assertFalse(archive.exists())
        attempt = json.loads((archive.parent / "unsealed-attempt.json").read_text())
        self.assertEqual(0, attempt["raw_gradle_exit"])
        self.assertEqual(4, attempt["counts"]["missing"])
        self.assertIn("Explicit fixture: process inquiry cannot be verified",
                      attempt.get("cleanup", {}).get("gradle", {}).get("errors", []))
        self.assertTrue((live / "logcat.txt").exists())

    def test_c2_timeout_and_signal_statuses_remain_nonzero(self):
        for signum, code in ((None, 124), (signal.SIGINT, 130), (signal.SIGTERM, 143)):
            with self.subTest(signum=signum):
                # Separate live roots and process handles for each controlled run.
                original = self.root
                self.root = original / str(code)
                self.root.mkdir()
                try:
                    fired = False
                    def hook(count):
                        nonlocal fired
                        if signum is not None and not fired:
                            fired = True
                            os.kill(os.getpid(), signum)
                    status, exits, live = self.probe_fixture(hook, deadline=.05 if signum is None else 10)
                    self.assertEqual(code, status)
                    self.assertEqual("BOUNDED_TIMEOUT" if signum is None else f"SIGNAL_{signum}", exits["termination"])
                    self.assertEqual(-signal.SIGTERM, exits["raw_gradle_exit"])
                    self.assertTrue(exits["logcat_lifecycle"]["healthy"])
                finally:
                    self.root = original

    def test_c3_sigint_in_collector_or_native_call_cannot_be_swallowed(self):
        for site in ("inventory", "pull", "native"):
            with self.subTest(site=site):
                original = self.root
                self.root = original / site
                self.root.mkdir()
                try:
                    def hook(count):
                        if count == 1:
                            self.exit_fixture("gradle")
                    status, exits, live = self.probe_fixture(hook, signal_site=site)
                    self.assertEqual(130, status, "Collector/native exception handling swallowed SIGINT")
                    self.assertEqual("SIGNAL_2", exits["termination"])
                finally:
                    self.root = original

    def test_c3_sigint_during_owned_cleanup_keeps_signal_exit(self):
        real_stop, real_scandir = diagnostics.stop_process, os.scandir
        fired = False
        def inquiry(path):
            nonlocal fired
            if not fired:
                fired = True
                os.kill(os.getpid(), signal.SIGINT)
            return real_scandir(path)
        def stop(process):
            with patch.object(diagnostics.os, "scandir", side_effect=inquiry):
                return real_stop(process)
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        status, exits, live = self.probe_fixture(hook, stop_override=stop)
        self.assertEqual(130, status, "Owned cleanup must not turn SIGINT into generic exit1")
        self.assertEqual("SIGNAL_2", exits["termination"])

    def finish_probe_fixture(self, live):
        reports = live / "build/outputs/androidTest-results/connected"
        reports.mkdir(parents=True)
        fixture_report(reports, diagnostics.selected_cases("flow"))
        for directory in ("debug", "androidTest/debug"):
            apk = live / "build/outputs/apk" / directory / "fixture.apk"
            apk.parent.mkdir(parents=True)
            apk.write_text(INTERFACE_KIND)
        archive = self.root / "sealed/evidence.tar.gz"
        with patch("builtins.print"):
            status = diagnostics.finish(self.root, live, archive, "flow")
        result = json.loads((live / "results.json").read_text())
        diagnostics.write_json(self.root / "finish-observation.json", {
            "evidence_kind": INTERFACE_KIND, "finish_exit": status,
            "result": result, "archive_exists": archive.exists()})
        self.assertEqual(4, result["counts"]["passed"])
        self.assertEqual(0, result["counts"]["missing"])
        return status, result

    def assert_first_signal_natural_exit_fails(self, code):
        import ast
        # Preempt at the actual first freeze signal, after the last inquiry.
        # An acknowledged frozen child cannot run to natural exit before TERM.
        # Force the equivalent race at the new freeze boundary; killpg stays real.
        tree = ast.parse(Path(diagnostics.__file__).read_text())
        stop = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "freeze_owned")
        signal_line, = [n.lineno for n in ast.walk(stop) if isinstance(n, ast.Call) and
                        isinstance(n.func, ast.Attribute) and n.func.attr == "killpg"]
        timeline = []
        def trace(frame, event, arg):
            if (event == "line" and frame.f_code is diagnostics.freeze_owned.__code__ and
                    frame.f_lineno == signal_line and not timeline and
                    frame.f_locals["process"] is self.probe_processes.get("logcat")):
                self.assertIsNone(frame.f_locals["receipt"]["exit_before_signal"])
                self.exit_fixture("logcat")
                process = self.probe_processes["logcat"]
                waited = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                timeline.append({"action": "natural-exit-before-first-real-freeze-killpg",
                                 "kernel": linux_process(process.pid),
                                 "wait_pid": waited.si_pid, "wait_code": waited.si_code,
                                 "wait_status": waited.si_status, "monotonic_ns": time.monotonic_ns()})
            return trace
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        previous_trace = sys.gettrace()
        try:
            sys.settrace(trace)
            status, exits, live = self.probe_fixture(hook, logcat_exit=code)
        finally:
            sys.settrace(previous_trace)
        finish_status, result = self.finish_probe_fixture(live)
        cleanup = json.loads((live / "cleanup.json").read_text())["logcat"]
        diagnostics.write_json(self.root / "first-signal-observation.json", {
            "evidence_kind": INTERFACE_KIND, "timeline": timeline, "cleanup": cleanup,
            "controller_exit": status, "finish_exit": finish_status,
            "classification": result["classification"]})
        self.assertEqual(1, len(timeline), "Fixture must force the first-signal scheduling window")
        self.assertEqual("Z", timeline[0]["kernel"]["state"])
        self.assertEqual(os.CLD_EXITED, timeline[0]["wait_code"])
        self.assertEqual(code, timeline[0]["wait_status"])
        self.assertEqual(self.probe_processes["logcat"].pid, timeline[0]["wait_pid"])
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertEqual(code, exits["raw_logcat_exit"])
        self.assertIsNone(cleanup["exit_before_stop"])
        self.assertIsNone(cleanup["exit_before_signal"])
        self.assertEqual([], cleanup["signals"], "No fatal signal authorized without live stop proof")
        self.assertFalse(cleanup["controlled_exit"])
        self.assertEqual(code, cleanup["freezes"][0]["leader_exit"])
        self.assertFalse(cleanup["freezes"][0]["confirmed"])
        self.assertTrue(cleanup["writers_stopped"])
        self.assertTrue((live / "device/frame.png").exists())
        self.assertIn(INTERFACE_KIND, (live / "logcat.txt").read_text())
        self.assertNotEqual("COMPLETE_PASS", result["classification"],
                            "First-signal natural exit was misclassified COMPLETE_PASS")
        self.assertNotEqual(0, status)
        self.assertNotEqual(0, finish_status)
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])

    def test_c2_first_signal_window_natural_exit_0_is_failure(self):
        self.assert_first_signal_natural_exit_fails(0)

    def test_c2_first_signal_window_natural_exit_7_is_failure(self):
        self.assert_first_signal_natural_exit_fails(7)

    def probe_signal_fixture(self, mode="term", gradle_exit=0, hook=None, deadline=10):
        real_popen = subprocess.Popen
        def launch(argv, **kwargs):
            # Change only the fixture child's startup, never wait/kill/health.
            self.assertEqual([sys.executable, "-B", "-c"], argv[:3])
            argv = list(argv)
            if Path(kwargs["stdout"].name).name == "logcat.txt":
                if mode == "kill":
                    argv[3] = "import signal\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\n" + argv[3]
                elif mode == "handled-exit0":
                    argv[3] = ("import signal, sys\nsignal.signal(signal.SIGTERM, "
                               "lambda *args: sys.exit(0))\n" + argv[3])
                elif mode in ("self-term", "self-kill"):
                    setup = "import os, signal\n"
                    if mode == "self-kill":
                        setup += "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                    argv[3] = setup + argv[3].replace(
                        "sys.exit(int(sys.argv[2]))",
                        "os.kill(os.getpid(), signal.SIGTERM)" if mode == "self-term" else
                        "os.kill(os.getpid(), signal.SIGKILL)")
            return real_popen(argv, **kwargs)
        def ready_hook(count):
            if count == 1:
                # The child's printed readiness follows signal-handler setup.
                end = time.monotonic() + 3
                while INTERFACE_KIND not in (self.root / "live/logcat.txt").read_text():
                    self.assertLess(time.monotonic(), end, "Fixture child readiness timeout")
                    time.sleep(.005)
            if hook is not None:
                hook(count)
            elif count == 1:
                self.exit_fixture("gradle")
        with patch.object(diagnostics.subprocess, "Popen", side_effect=launch):
            return self.probe_fixture(ready_hook, gradle_exit=gradle_exit, deadline=deadline)

    def assert_signal_teardown_passes(self, mode, signum):
        status, exits, live = self.probe_signal_fixture(mode)
        finish_status, result = self.finish_probe_fixture(live)
        cleanup = json.loads((live / "cleanup.json").read_text())["logcat"]
        self.assertEqual(-signum, self.probe_processes["logcat"].returncode)
        self.assertEqual(-signum, exits["raw_logcat_exit"])
        self.assertIn(signum, cleanup["signals"])
        self.assertTrue(cleanup["controlled_exit"])
        self.assertEqual(len(cleanup["signals"]), len(cleanup["freezes"]))
        for freeze in cleanup["freezes"]:
            self.assertTrue(freeze["confirmed"])
            self.assertEqual(os.CLD_STOPPED, freeze["leader_stop"]["code"])
            self.assertEqual(signal.SIGSTOP, freeze["leader_stop"]["status"])
        self.assertEqual(self.probe_processes["logcat"].owned_identity, cleanup["identity"])
        self.assertTrue(cleanup["writers_stopped"])
        self.assertTrue(exits["logcat_lifecycle"]["healthy"])
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertEqual(0, status)
        self.assertEqual(0, finish_status)
        self.assertEqual("COMPLETE_PASS", result["classification"], "Labelled interface fixture only")

    def test_c2_os_wait_proven_term_teardown_can_finish_pass(self):
        self.assert_signal_teardown_passes("term", signal.SIGTERM)

    def test_c2_os_wait_proven_kill_teardown_can_finish_pass(self):
        self.assert_signal_teardown_passes("kill", signal.SIGKILL)

    def test_c2_term_handler_exit_zero_is_unproven_and_fails(self):
        status, exits, live = self.probe_signal_fixture("handled-exit0")
        finish_status, result = self.finish_probe_fixture(live)
        cleanup = json.loads((live / "cleanup.json").read_text())["logcat"]
        self.assertEqual([signal.SIGTERM], cleanup["signals"])
        self.assertEqual(0, self.probe_processes["logcat"].returncode)
        self.assertEqual(0, exits["raw_logcat_exit"])
        self.assertNotEqual("COMPLETE_PASS", result["classification"],
                            "Signal delivery alone does not prove the child's exit cause")
        self.assertNotEqual(0, status)
        self.assertNotEqual(0, finish_status)
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])

    def test_c1_exited_body_owned_descendants_cleanup_is_bounded(self):
        leader, child, output = self.writer_group(leader_exits=True, grandchild=True)
        before = time.monotonic()
        receipt = diagnostics.stop_process(leader)
        elapsed = time.monotonic() - before
        self.assert_writer_stopped(child, output, receipt, elapsed)
        self.assertTrue(receipt["writers_stopped"])
        self.assertEqual(0, receipt["raw_exit"], "Completed body raw exit must survive descendant teardown")
        self.assertEqual([signal.SIGTERM, signal.SIGKILL], receipt["signals"])
        self.assertLess(elapsed, 20, "TERM/KILL/wait total 15s plus 5s scheduling margin")

    def test_c2_nonzero_body_and_cancellation_cannot_finish_pass(self):
        for code in (9, 124, 130, 143):
            with self.subTest(code=code):
                original = self.root
                self.root = original / str(code)
                self.root.mkdir()
                try:
                    def hook(count):
                        if count == 1 and code in (130, 143):
                            os.kill(os.getpid(), signal.SIGINT if code == 130 else signal.SIGTERM)
                    status, exits, live = self.probe_signal_fixture(
                        gradle_exit=9 if code == 9 else 0,
                        hook=None if code == 9 else hook, deadline=.1 if code == 124 else 10)
                    finish_status, result = self.finish_probe_fixture(live)
                    self.assertEqual(code, status)
                    self.assertEqual(9 if code == 9 else -signal.SIGTERM, exits["raw_gradle_exit"])
                    self.assertNotEqual(0, finish_status)
                    self.assertNotEqual("COMPLETE_PASS", result["classification"])
                finally:
                    self.root = original

    def assert_self_signal_before_freeze_fails(self, second=False):
        real_killpg = os.killpg
        timeline = []
        stops = 0
        uses_freeze = hasattr(diagnostics, "freeze_owned")
        def delivery(pgid, signum):
            nonlocal stops
            process = self.probe_processes.get("logcat")
            if process is not None and pgid == process.pid:
                if signum == signal.SIGSTOP:
                    stops += 1
                target = ((signum == signal.SIGSTOP and stops == (2 if second else 1))
                          if uses_freeze else signum == (signal.SIGKILL if second else signal.SIGTERM))
                if target and not timeline:
                    self.exit_fixture("logcat")
                    waited = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    timeline.append({"kernel": linux_process(process.pid), "wait_pid": waited.si_pid,
                                     "wait_code": waited.si_code, "wait_status": waited.si_status,
                                     "before_requested_signal": int(signum)})
            return real_killpg(pgid, signum)
        with patch.object(diagnostics.os, "killpg", side_effect=delivery):
            status, exits, live = self.probe_signal_fixture("self-kill" if second else "self-term")
        finish_status, result = self.finish_probe_fixture(live)
        cleanup = json.loads((live / "cleanup.json").read_text())["logcat"]
        diagnostics.write_json(self.root / "causal-observation.json", {
            "evidence_kind": INTERFACE_KIND, "timeline": timeline, "cleanup": cleanup,
            "exits": exits, "controller_exit": status, "finish_exit": finish_status,
            "classification": result["classification"]})
        self.assertEqual(1, len(timeline))
        self.assertEqual("Z", timeline[0]["kernel"]["state"])
        self.assertEqual(os.CLD_KILLED, timeline[0]["wait_code"])
        self.assertEqual(signal.SIGKILL if second else signal.SIGTERM, timeline[0]["wait_status"])
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertEqual(-timeline[0]["wait_status"], exits["raw_logcat_exit"])
        self.assertNotEqual("COMPLETE_PASS", result["classification"],
                            "Already self-signalled child was falsely certified as controlled stop")
        self.assertNotEqual(0, status)
        self.assertNotEqual(0, finish_status)
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])

    def test_c2_causal_self_term_before_first_freeze_is_failure(self):
        self.assert_self_signal_before_freeze_fails()

    def test_c2_causal_self_kill_before_second_freeze_is_failure(self):
        self.assert_self_signal_before_freeze_fails(second=True)

    def test_c2_missing_kernel_wait_receipt_cannot_certify_health(self):
        real_stop = diagnostics.stop_process
        def lose_wait(process):
            receipt = real_stop(process)
            if process is self.probe_processes.get("logcat"):
                for freeze in receipt.get("freezes", []):
                    freeze["leader_stop"] = None
            return receipt
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        status, exits, live = self.probe_fixture(hook, stop_override=lose_wait)
        finish_status, result = self.finish_probe_fixture(live)
        self.assertNotEqual(0, status, "Missing kernel stop wait evidence was certified healthy")
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])
        self.assertNotEqual(0, finish_status)
        self.assertEqual("INVALID_EXECUTION", result["classification"])

    def test_c2_absent_raw_stop_exit_cannot_certify_health(self):
        real_stop = diagnostics.stop_process
        def lose_exit(process):
            receipt = real_stop(process)
            if process is self.probe_processes.get("logcat"):
                receipt["raw_exit"] = None
            return receipt
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        status, exits, live = self.probe_fixture(hook, stop_override=lose_exit)
        finish_status, result = self.finish_probe_fixture(live)
        self.assertNotEqual(0, status, "Absent raw exit was certified healthy")
        self.assertIsNone(exits["raw_logcat_exit"])
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])
        self.assertNotEqual(0, finish_status)
        self.assertEqual("INVALID_EXECUTION", result["classification"])

    def test_c2_missing_exit_wait_receipt_cannot_certify_health(self):
        real_stop = diagnostics.stop_process
        def lose_exit_wait(process):
            receipt = real_stop(process)
            if process is self.probe_processes.get("logcat"):
                receipt.pop("exit_wait", None)
            return receipt
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        status, exits, live = self.probe_fixture(hook, stop_override=lose_exit_wait)
        finish_status, result = self.finish_probe_fixture(live)
        self.assertNotEqual(0, status, "Signal number without exit wait evidence was certified healthy")
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])
        self.assertNotEqual(0, finish_status)
        self.assertEqual("INVALID_EXECUTION", result["classification"])

    def assert_frozen_submission_blocks_self_exit(self, second=False):
        real_killpg = os.killpg
        observed = []
        def delivery(pgid, signum):
            process = self.probe_processes.get("logcat")
            if process is not None and pgid == process.pid and signum == (
                    signal.SIGKILL if second else signal.SIGTERM):
                # Release an actual child's self-signal instruction AFTER the
                # last controller inquiry, BEFORE the real fatal signal syscall.
                (self.root / "logcat-exit").touch()
                before = linux_process(process.pid)
                time.sleep(.12)
                waited = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                observed.append({"before": before, "after": linux_process(process.pid),
                                 "exit_wait": None if waited is None else list(waited),
                                 "signal": int(signum)})
            return real_killpg(pgid, signum)
        with patch.object(diagnostics.os, "killpg", side_effect=delivery):
            status, exits, live = self.probe_signal_fixture("self-kill" if second else "self-term")
        finish_status, result = self.finish_probe_fixture(live)
        diagnostics.write_json(self.root / "frozen-window-observation.json", {
            "evidence_kind": INTERFACE_KIND, "observed": observed, "exits": exits,
            "controller_exit": status, "finish_exit": finish_status,
            "classification": result["classification"]})
        self.assertEqual(1, len(observed))
        self.assertEqual("T", observed[0]["before"]["state"],
                         "Child could run after last inquiry and before fatal signal submission")
        self.assertEqual("T", observed[0]["after"]["state"])
        self.assertIsNone(observed[0]["exit_wait"], "Frozen child ran its self-signal instruction")
        self.assertEqual(0, status)
        self.assertEqual(0, finish_status)
        self.assertTrue(exits["logcat_lifecycle"]["healthy"])

    def test_c2_first_frozen_submission_excludes_autonomous_self_term(self):
        self.assert_frozen_submission_blocks_self_exit()

    def test_c2_second_frozen_submission_excludes_autonomous_self_kill(self):
        self.assert_frozen_submission_blocks_self_exit(second=True)

    def assert_freeze_fault_fails(self, fault):
        real_killpg, real_waitid = os.killpg, os.waitid
        def delivery(pgid, signum):
            if fault == "dropped-stop" and signum == signal.SIGSTOP:
                return None  # Delivery return success cannot be a stop proof.
            return real_killpg(pgid, signum)
        def waitid(idtype, pid, options):
            if options & os.WSTOPPED:
                if fault == "wait-denied":
                    raise PermissionError("PURE_SUBPROCESS_INTERFACE_FIXTURE_NOT_ANDROID wait denied")
                if fault == "no-ack":
                    return None
            return real_waitid(idtype, pid, options)
        def hook(count):
            if count == 1:
                self.exit_fixture("gradle")
        with patch.object(diagnostics.os, "killpg", side_effect=delivery), \
                patch.object(diagnostics.os, "waitid", side_effect=waitid):
            status, exits, live = self.probe_fixture(hook)
        finish_status, result = self.finish_probe_fixture(live)
        cleanup = json.loads((live / "cleanup.json").read_text())["logcat"]
        diagnostics.write_json(self.root / "freeze-fault-observation.json", {
            "evidence_kind": INTERFACE_KIND, "fault": fault, "cleanup": cleanup,
            "exits": exits, "controller_exit": status, "finish_exit": finish_status})
        self.assertNotEqual(0, status, "Unacknowledged or failed freeze was certified healthy")
        self.assertFalse(exits["logcat_lifecycle"]["healthy"])
        self.assertEqual(0, exits["raw_gradle_exit"])
        self.assertNotEqual(0, finish_status)
        self.assertEqual("INVALID_EXECUTION", result["classification"])
        self.assertTrue(cleanup["errors"])
        self.assertTrue(cleanup["writers_stopped"], "Safe failure KILL must still stop verified owned writers")

    def test_c2_dropped_stop_does_not_prove_kernel_freeze(self):
        self.assert_freeze_fault_fails("dropped-stop")

    def test_c2_no_kernel_stop_acknowledgement_is_failure(self):
        self.assert_freeze_fault_fails("no-ack")

    def test_c2_kernel_freeze_inquiry_denied_is_failure(self):
        self.assert_freeze_fault_fails("wait-denied")

    def test_c1_every_owned_leader_credential_and_parent_is_required(self):
        for key in ("pid", "ppid", "pgid", "sid", "start_ticks"):
            with self.subTest(key=key):
                process = diagnostics.start_process([sys.executable, "-B", "-c", "import time; time.sleep(60)"])
                identity = linux_process(process.pid)
                self.children.append((process, identity, identity))
                process.owned_identity = dict(process.owned_identity)
                process.owned_identity[key] += 1
                with patch.object(diagnostics.os, "killpg", wraps=os.killpg) as kill:
                    receipt = diagnostics.stop_process(process)
                diagnostics.write_json(self.root / (key + "-ownership.json"), {
                    "evidence_kind": INTERFACE_KIND, "receipt": receipt, "signal_count": kill.call_count})
                self.assertEqual(0, kill.call_count, "Changed owned credentials authorized a numeric group")
                self.assertFalse(receipt["writers_stopped"])
                self.assertIsNone(diagnostics.process_exit(process))

    def test_c2_early_and_final_self_term_and_exit_255_remain_failure(self):
        for phase, mode, code in (("early", "self-term", -15), ("final", "self-term", -15),
                                  ("early", "term", 255)):
            with self.subTest(phase=phase, code=code):
                original = self.root
                self.root = original / (phase + str(code)); self.root.mkdir()
                try:
                    def hook(count):
                        if count == 1:
                            if phase == "early":
                                self.exit_fixture("logcat")
                            self.exit_fixture("gradle")
                        elif phase == "final":
                            self.exit_fixture("logcat")
                    if code == 255:
                        status, exits, live = self.probe_fixture(hook, logcat_exit=255)
                    else:
                        status, exits, live = self.probe_signal_fixture(mode, hook=hook)
                    finish_status, result = self.finish_probe_fixture(live)
                    self.assertNotEqual(0, status)
                    self.assertEqual(0, exits["raw_gradle_exit"])
                    self.assertEqual(code, exits["raw_logcat_exit"])
                    self.assertFalse(exits["logcat_lifecycle"]["healthy"])
                    self.assertNotEqual(0, finish_status)
                    self.assertEqual("INVALID_EXECUTION", result["classification"])
                finally:
                    self.root = original

    def test_c2_real_adb_loopback_stream_has_kernel_proven_normal_stop(self):
        import shutil, socket, threading
        adb = shutil.which("adb")
        if adb is None:
            self.skipTest("Real adb client unavailable; API26/35 normal-stop remains unverified")
        requests, errors, connections = [], [], []
        server = socket.socket()
        server.bind(("127.0.0.1", 0)); server.listen(); server.settimeout(.1)
        done = threading.Event()
        def exact(connection, size):
            data = b""
            while len(data) < size:
                part = connection.recv(size - len(data))
                if not part:
                    raise EOFError()
                data += part
            return data
        def serve(connection):
            try:
                with connection:
                    while not done.is_set():
                        request = exact(connection, int(exact(connection, 4), 16)).decode()
                        requests.append(request)
                        if request.endswith(":features") or request == "host:features":
                            connection.sendall(b"OKAY0000")  # Classic shell protocol, no device claims.
                        elif request == "host:version":
                            connection.sendall(b"OKAY00040029")
                        elif request.startswith("host:tport:"):
                            connection.sendall(b"OKAY" + struct.pack("<Q", 1))
                        elif request.startswith("host:transport:"):
                            connection.sendall(b"OKAY")
                        elif request.startswith("shell:") or request.startswith("shell,v2,"):
                            connection.sendall(b"OKAY")
                            while not done.wait(.01):
                                payload = (INTERFACE_KIND + " loopback-adb-client-stream\n").encode()
                                if request.startswith("shell,v2,"):
                                    payload = struct.pack("<BI", 1, len(payload)) + payload
                                connection.sendall(payload)
                            return
                        else:
                            raise RuntimeError("Unsupported fixture adb request: " + request)
            except (EOFError, ConnectionError, OSError):
                pass  # Controller stopping the real client closes its transport.
            except Exception as error:
                errors.append(str(error))
        def accept():
            while not done.is_set():
                try:
                    connection, _ = server.accept()
                except socket.timeout:
                    continue
                except OSError:
                    return
                connection.settimeout(2)
                thread = threading.Thread(target=serve, args=(connection,), daemon=True)
                connections.append((connection, thread)); thread.start()
        thread = threading.Thread(target=accept, daemon=True); thread.start()
        output = self.root / "real-adb-stream.txt"
        try:
            with output.open("wb") as stream:
                command = [adb, "-H", "127.0.0.1", "-P", str(server.getsockname()[1]),
                           "-s", "PURE_LOOPBACK_TRANSPORT_NOT_DEVICE", "logcat", "-v", "threadtime"]
                process = diagnostics.start_process(command, stdout=stream, stderr=subprocess.STDOUT)
                identity = linux_process(process.pid); self.children.append((process, identity, identity))
                deadline = time.monotonic() + 5
                while output.stat().st_size < len(INTERFACE_KIND) * 3:
                    if diagnostics.process_exit(process) is not None or time.monotonic() >= deadline:
                        raise RuntimeError("ADB fixture setup failed: " + output.read_text())
                    time.sleep(.01)
                self.assertIsNone(diagnostics.process_exit(process), "Device/transport error is not normal stop")
                before = output.stat().st_size; time.sleep(.05)
                self.assertGreater(output.stat().st_size, before, "Real adb client must continuously drain transport")
                receipt = diagnostics.stop_process(process)
            before = output.stat().st_size; time.sleep(.12)
            version_out, version_err = self.root / "adb-version.stdout.txt", self.root / "adb-version.stderr.txt"
            version_command = {"argv": [adb, "version"], "timeout_seconds": 3, "timed_out": False,
                               "stdout": str(version_out), "stderr": str(version_err)}
            diagnostics.write_json(self.root / "adb-version.command.json", version_command)
            with version_out.open("wb") as stdout, version_err.open("wb") as stderr:
                try:
                    version = subprocess.run([adb, "version"], stdout=stdout, stderr=stderr, timeout=3)
                    version_command["exit"] = version.returncode
                except subprocess.TimeoutExpired:
                    version_command.update(timed_out=True, exit=124)
                    raise
                finally:
                    diagnostics.write_json(self.root / "adb-version.command.json", version_command)
            diagnostics.write_json(self.root / "real-adb-observation.json", {
                "evidence_kind": INTERFACE_KIND, "device_evidence": False, "argv": command,
                "adb_realpath": str(Path(adb).resolve()), "adb_sha256": diagnostics.digest(adb),
                "version_stdout": version_out.read_text(), "version_stderr": version_err.read_text(),
                "version_exit": version.returncode, "requests": requests, "fixture_errors": errors,
                "cleanup": receipt, "bytes_at_stop": before, "bytes_after": output.stat().st_size})
            self.assertFalse(errors)
            self.assertIn(INTERFACE_KIND, output.read_text())
            self.assertTrue(any("logcat" in request for request in requests))
            self.assertTrue(receipt.get("freezes"), "Real adb normal-stop lacks kernel freeze evidence")
            self.assertTrue(receipt["controlled_exit"])
            self.assertTrue(receipt["writers_stopped"])
            self.assertEqual(-signal.SIGTERM, receipt["raw_exit"])
            self.assertEqual(before, output.stat().st_size)
        finally:
            done.set(); server.close(); thread.join(timeout=2)
            for connection, worker in connections:
                connection.close(); worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
