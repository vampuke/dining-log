#!/usr/bin/env python3
"""Bounded PR18 device diagnosis. This is never a release gate or suite substitute."""
import argparse
from collections import Counter
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shlex
import signal
import struct
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET
import zlib

BASE = "be8aaf8d60aa5513227a102d4cdbfcb2377b778d"
PACKAGE = "top.alanvampuke.dininglog.product."
REMOTE = "/sdcard/Android/data/top.alanvampuke.dininglog.debug/files/p4-screenshots"
VARIANTS = ("INK_PAPER-dark=false", "INK_PAPER-dark=true",
            "QUIET_GRID-dark=false", "QUIET_GRID-dark=true")
METHODS = {
    "flow": ("Pr18ScreenAcceptanceTest", "f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent"),
    "landscape": ("Pr18ScreenAcceptanceTest", "f7EveryListAndSearchRemainReachableInBothLandscapeDirectionsAtAllFontBranches"),
    "password": ("Pr18DialogAcceptanceTest", "f7PasswordDialogUsesRealFontAndKeepsInputCallbacksSaveAndSecurity"),
    "conflict": ("Pr18DialogAcceptanceTest", "f7ConflictComparisonChoiceAndSaveRemainScrollableAndExplicitAtAllFonts"),
    "fold": ("Pr16UiTest", "invalidFirstVisitDateIsRevealedAfterFoldingAndSavingAndStaysVisibleWhenFoldedAgain"),
}


def selected_cases(group):
    cls, method = METHODS[group]
    suffixes = ("",) if group == "fold" else tuple(f"[{v}]" for v in VARIANTS)
    # Runner 1.6.2 strips only numeric [0] suffixes. Explicit named variants are
    # required here; a bare method selector would silently miss these cases.
    return [f"{PACKAGE}{cls}#{method}{suffix}" for suffix in suffixes]


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    os.replace(temporary, path)


def event(root, kind, **values):
    with Path(root, "collector.jsonl").open("a") as stream:
        stream.write(json.dumps({"event": kind, "monotonic_ns": time.monotonic_ns(),
                                 "utc_ns": time.time_ns(), **values}, ensure_ascii=False) + "\n")


def source_plan(repo, group, api):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=repo).decode().strip()
    head = git("rev-parse", "HEAD")
    if os.environ.get("GITHUB_SHA") and head != os.environ["GITHUB_SHA"]:
        raise RuntimeError("Checkout HEAD differs from GITHUB_SHA")
    changed = git("diff", "--name-only", BASE, "HEAD").splitlines()
    # Bind ALL non-diagnostic source to the original failing run, including every
    # test, baseline assertion, production file and mandatory full-suite gate.
    allowed = {
        ".github/workflows/pr18-failure-diagnostics.yml",
        ".github/scripts/pr18-failure-diagnostics.py",
        ".github/scripts/test_pr18_failure_diagnostics.py",
        ".github/pr18-failure-diagnostics.md",
    }
    if set(changed) - allowed:
        raise RuntimeError(f"Original failing source/gates changed: {set(changed) - allowed}")
    if git("diff", "--name-only", "HEAD") or git("diff", "--cached", "--name-only"):
        raise RuntimeError("Tracked checkout differs from source commit")
    hashes = {}
    for filename in git("ls-files", "android").splitlines():
        hashes[filename] = digest(Path(repo, filename))
    cls, method = METHODS[group]
    test_path = f"android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/{cls}.kt"
    source = Path(repo, test_path).read_text()
    if f"@Test fun {method}()" not in source:
        raise RuntimeError("Selected original test method is missing")
    if group != "fold":
        import re
        fixture = Path(repo, "android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18Evidence.kt").read_text()
        block = fixture.split("internal fun pr18StyleCases()", 1)[1].split("\n)", 1)[0]
        variants = tuple(f"{style}-dark={dark}" for style, dark in
                         re.findall(r"arrayOf<Any>\(ThemeStyle\.(\w+), (true|false)\)", block))
        if variants != VARIANTS or '@Parameterized.Parameters(name = "{0}-dark={1}")' not in source:
            raise RuntimeError("Declared parameterized case IDs differ from probe contract")
    return {"evidence_kind": "DEVICE_DIAGNOSTIC_PLAN_NOT_RESULTS", "base_sha": BASE,
            "source_sha": head, "api": api, "group": group,
            "expected_case_ids": selected_cases(group),
            "expected_count": len(selected_cases(group)), "android_source_sha256": hashes,
            "source_unchanged_from_real_failure": True,
            "selector": ",".join(selected_cases(group)),
            "full_suite_contract_unchanged": {"classes": 20, "cases": 190,
                                             "original_classes": 17, "original_methods": 93,
                                             "original_case_floor": 174}}


def inspect_results(directory, expected, raw_exit):
    cases, invalid = [], []
    reports = sorted(Path(directory).rglob("*.xml"))
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as error:
            invalid.append(f"Unreadable report {path}: {error}")
            continue
        suites = list(root.iter("testsuite"))
        if not suites:
            invalid.append(f"No testsuite in {path}")
        for suite in suites:
            direct = suite.findall("testcase")
            # Aggregate parent suites are checked through their descendant cases.
            actual = list(suite.iter("testcase"))
            counter_values = {"tests": len(actual),
                              "failures": sum(c.find("failure") is not None for c in actual),
                              "errors": sum(c.find("error") is not None for c in actual),
                              "skipped": sum(c.find("skipped") is not None or c.get("status") in
                                             ("skipped", "notrun") for c in actual)}
            for key, count in counter_values.items():
                try:
                    if suite.get(key) is None or int(suite.get(key)) != count:
                        invalid.append(f"Suite {key} counter disagrees with cases in {path}")
                except ValueError:
                    invalid.append(f"Non-integer suite {key} in {path}")
            if suite.get("disabled", "0") != "0":
                invalid.append(f"Disabled tests in {path}")
            for case in direct:
                details = [ET.tostring(n, encoding="unicode") for n in case
                           if n.tag in ("failure", "error", "skipped")]
                outcome = ("skipped" if case.find("skipped") is not None or
                           case.get("status") in ("skipped", "notrun") else
                           "errors" if case.find("error") is not None else
                           "failed" if case.find("failure") is not None else "passed")
                cases.append({"id": f"{case.get('classname')}#{case.get('name')}",
                              "outcome": outcome, "details": details, "xml": str(path),
                              "xml_sha256": digest(path)})
    observed = Counter(case["id"] for case in cases)
    missing = sorted(set(expected) - set(observed))
    unexpected = sorted(set(observed) - set(expected))
    duplicates = {key: count for key, count in observed.items() if count > 1}
    counts = {key: sum(case["outcome"] == key for case in cases)
              for key in ("passed", "failed", "errors", "skipped")}
    counts.update(missing=len(missing), duplicate=sum(v - 1 for v in duplicates.values()),
                  unexpected=sum(observed[key] for key in unexpected))
    if missing or unexpected or duplicates:
        invalid.append("Missing, duplicate, or unrelated selected case IDs")
    if counts["skipped"]:
        invalid.append("Selected tests skipped/notrun")
    if raw_exit is None:
        invalid.append("Missing raw Gradle exit")
    classification = ("INVALID_EXECUTION" if invalid else
                      "COMPLETE_FAIL" if counts["failed"] or counts["errors"] else
                      "INFRA_FAILURE" if raw_exit != 0 else "COMPLETE_PASS")
    return {"classification": classification, "raw_gradle_exit": raw_exit,
            "expected_case_ids": expected, "expected_count": len(expected),
            "counts": counts, "missing_case_ids": missing, "duplicate_case_ids": duplicates,
            "unexpected_case_ids": unexpected, "invalid_reasons": invalid, "cases": cases,
            "report_files": [{"path": str(p), "sha256": digest(p)} for p in reports]}


class AdbDevice:
    def __init__(self, serial):
        self.prefix = ["adb", "-s", serial]

    def command(self, *args, timeout=10):
        return subprocess.run(self.prefix + list(args), capture_output=True, timeout=timeout)

    def inventory(self):
        command = (f"if [ -d {shlex.quote(REMOTE)} ]; then find {shlex.quote(REMOTE)} -type f | "
                   "while IFS= read -r file; do stat -c '%s %Y %n' \"$file\"; done; else exit 44; fi")
        result = self.command("shell", command)
        if result.returncode:
            raise RuntimeError(f"inventory raw_exit={result.returncode}: {result.stderr.decode(errors='replace')}")
        rows = {}
        for line in result.stdout.decode().splitlines():
            size, stamp, name = line.split(" ", 2)
            if not name.startswith(REMOTE + "/"):
                raise RuntimeError("Unexpected remote evidence path")
            rows[name[len(REMOTE) + 1:]] = (int(size), int(stamp))
        return rows

    def stat(self, name):
        result = self.command("shell", f"stat -c '%s %Y' {shlex.quote(REMOTE + '/' + name)}")
        if result.returncode:
            raise RuntimeError(f"stat raw_exit={result.returncode}: {result.stderr.decode(errors='replace')}")
        return tuple(map(int, result.stdout.split()))

    def pull(self, name, destination):
        result = self.command("pull", REMOTE + "/" + name, str(destination), timeout=15)
        if result.returncode:
            raise RuntimeError(f"pull raw_exit={result.returncode}: {result.stderr.decode(errors='replace')}")


def complete_png(path):
    # A stable size alone is insufficient while a Bitmap writer is paused.
    with Path(path).open("rb") as stream:
        if stream.read(8) != b"\x89PNG\r\n\x1a\n":
            return False
        while True:
            header = stream.read(8)
            if len(header) != 8:
                return False
            size, kind = struct.unpack(">I4s", header)
            if size > 64 * 1024 * 1024:
                return False
            data, crc = stream.read(size), stream.read(4)
            if len(data) != size or len(crc) != 4 or struct.unpack(">I", crc)[0] != zlib.crc32(kind + data):
                return False
            if kind == b"IEND":
                return size == 0 and not stream.read(1)


class Collector:
    def __init__(self, root, device):
        self.root, self.device = Path(root), device
        self.root.mkdir(parents=True, exist_ok=True)
        self.copied = {}

    def sample(self, deadline=None):
        started = time.monotonic_ns()
        try:
            inventory = self.device.inventory()
        except InterruptedError:
            raise
        except Exception as error:
            event(self.root, "inventory-unavailable", error=str(error))
            return
        event(self.root, "inventory", files=len(inventory), bytes=sum(v[0] for v in inventory.values()))
        for name, metadata in sorted(inventory.items()):
            if deadline is not None and time.monotonic() >= deadline:
                event(self.root, "collection-budget-ended", remaining=sum(
                    self.copied.get(key) != value for key, value in inventory.items()))
                break
            relative = PurePosixPath(name)
            if relative.is_absolute() or ".." in relative.parts or not relative.parts:
                event(self.root, "rejected-path", name=name)
                continue
            if self.copied.get(name) == metadata:
                continue
            destination = self.root / "device" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            temporary = destination.with_name(destination.name + ".part")
            before = time.monotonic_ns()
            try:
                self.device.pull(name, temporary)
                after = self.device.stat(name)
                if after != metadata or temporary.stat().st_size != metadata[0]:
                    raise RuntimeError("Remote file changed/vanished during pull")
                if relative.suffix == ".png" and not complete_png(temporary):
                    raise RuntimeError("PNG has no complete validated IEND/CRC")
                sha = digest(temporary)
                os.replace(temporary, destination)
                self.copied[name] = metadata
                event(self.root, "retained", path=name, remote_size=metadata[0], remote_mtime=metadata[1],
                      sha256=sha, elapsed_ns=time.monotonic_ns() - before)
            except InterruptedError:
                raise
            except Exception as error:
                event(self.root, "pull-incomplete", path=name, error=str(error))
            finally:
                temporary.unlink(missing_ok=True)
        # Missing remote files after UTP uninstall do not remove local evidence.
        event(self.root, "sample-end", elapsed_ns=time.monotonic_ns() - started)


@contextmanager
def writer_lock(root):
    Path(root).mkdir(parents=True, exist_ok=True)
    with Path(root, ".writer.lock").open("a") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Refusing to seal while diagnostic writer is live")
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


def process_identity(pid):
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return {"pid": pid, "state": fields[0], "ppid": int(fields[1]),
            "pgid": int(fields[2]), "sid": int(fields[3]), "start_ticks": int(fields[19])}


def start_process(*args, **kwargs):
    process = subprocess.Popen(*args, **kwargs, start_new_session=True)
    try:
        identity = process_identity(process.pid)
        if identity["ppid"] != os.getpid() or identity["pgid"] != process.pid or identity["sid"] != process.pid:
            raise RuntimeError("New child is not the controller's owned session/group leader")
        process.owned_identity = identity
    except Exception as error:
        # Keep the handle for a failure receipt even if /proc cannot be checked.
        process.ownership_error = str(error)
    return process


def process_exit(process):
    if process.returncode is not None:
        return process.returncode
    # Keep the exited leader unreaped as the group's birth-identity anchor.
    # Popen.poll()/wait() would discard that anchor before descendants stop.
    result = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    if result is None:
        return None
    return result.si_status if result.si_code == os.CLD_EXITED else -result.si_status


def owned_members(process):
    identity = process.owned_identity
    anchor = process_identity(process.pid)
    if any(anchor[key] != identity[key] for key in ("pid", "pgid", "sid", "start_ticks")):
        raise RuntimeError("Owned leader birth identity changed; refusing group signals")
    members = []
    # A failed scan is not an empty group. Z/X cannot write; other states can.
    with os.scandir("/proc") as entries:
        for entry in entries:
            if not entry.name.isdecimal():
                continue
            try:
                member = process_identity(int(entry.name))
            except (FileNotFoundError, ProcessLookupError):
                continue  # Kernel confirmed this process disappeared during inquiry.
            if member["pgid"] == identity["pgid"]:
                if member["sid"] != identity["sid"] or member["start_ticks"] < identity["start_ticks"]:
                    raise RuntimeError("Process group has an unverified member")
                members.append(member)
    return members


def stop_process(process):
    receipt = {"writers_stopped": process is None, "identity": None,
               "signals": [], "members": [], "errors": [],
               "raw_exit": process.returncode if process is not None else None,
               "exit_before_stop": None, "exit_before_signal": None, "interrupted_signals": []}

    def inquiry_error(error):
        if isinstance(error, InterruptedError):
            receipt["interrupted_signals"].append(error.args[0])
        else:
            receipt["errors"].append(str(error))

    if process is None:
        return receipt
    identity = getattr(process, "owned_identity", None)
    receipt["identity"] = identity
    if identity is None:
        receipt["errors"].append(getattr(process, "ownership_error", "Missing owned birth identity"))
        return receipt
    started = time.monotonic()
    try:
        receipt["exit_before_stop"] = process_exit(process)
        if receipt["exit_before_stop"] is not None:
            receipt["raw_exit"] = receipt["exit_before_stop"]
        stopped = False
        for signum in (signal.SIGTERM, signal.SIGKILL):
            try:
                receipt["members"] = owned_members(process)
                stopped = all(m["state"] in ("Z", "X") for m in receipt["members"])
            except (OSError, RuntimeError, ValueError, IndexError) as error:
                inquiry_error(error)
            if stopped:
                break
            # Recheck the unreaped leader immediately before signalling. An
            # absent/reused leader never authorizes signalling a numeric group.
            anchor = process_identity(process.pid)
            if any(anchor[key] != identity[key] for key in ("pid", "pgid", "sid", "start_ticks")):
                raise RuntimeError("Lost owned group identity before signal")
            if not receipt["signals"]:
                receipt["exit_before_signal"] = process_exit(process)
            os.killpg(identity["pgid"], signum)
            receipt["signals"].append(int(signum))
            deadline = time.monotonic() + 5
            while True:
                try:
                    receipt["members"] = owned_members(process)
                except (OSError, RuntimeError, ValueError, IndexError) as error:
                    inquiry_error(error)
                    break
                stopped = all(m["state"] in ("Z", "X") for m in receipt["members"])
                if stopped or time.monotonic() >= deadline:
                    break
                time.sleep(.05)
            if stopped:
                break
        if not stopped:
            receipt["errors"].append("Owned live members could not be verified stopped within TERM/KILL bounds")
        # Reap only now: the leader's death alone never proves group quiescence.
        receipt["raw_exit"] = process.wait(timeout=5)
        receipt["writers_stopped"] = stopped and not receipt["errors"]
    except (OSError, RuntimeError, ValueError, IndexError, subprocess.TimeoutExpired) as error:
        inquiry_error(error)
    receipt["elapsed_ns"] = int((time.monotonic() - started) * 1e9)
    return receipt


def native_sample(root, device, number, deadline=None):
    for name, args in {
        "window": ("shell", "dumpsys window windows"),
        "input": ("shell", "dumpsys input"),
    }.items():
        if deadline is not None and time.monotonic() >= deadline:
            break
        start = time.monotonic_ns()
        try:
            result = device.command(*args)
            path = Path(root, "native", f"{number:05d}-{name}.txt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(result.stdout + result.stderr)
            event(root, "native", command=name, sequence=number, start_ns=start,
                  end_ns=time.monotonic_ns(), raw_exit=result.returncode, path=str(path))
        except InterruptedError:
            raise
        except Exception as error:
            event(root, "native-unavailable", command=name, error=str(error))


def run_probe(repo, root, group, api, deadline_seconds):
    root = Path(root)
    gradle = logcat = None
    raw_exit, run_exit, termination = None, 1, None
    device = collector = None
    logcat_lifecycle = {"started": False, "healthy": False, "premature_exit": None,
                        "checks": [], "owned_teardown_requested": False}

    def check_logcat(phase):
        nonlocal termination, run_exit
        if logcat is None:
            return
        status = process_exit(logcat)
        check = {"phase": phase, "raw_exit": status, "utc_ns": time.time_ns(),
                 "monotonic_ns": time.monotonic_ns()}
        logcat_lifecycle["checks"].append(check)
        event(root, "logcat-lifecycle", **check)
        if status is not None:
            logcat_lifecycle["premature_exit"] = status
            termination = termination or "LIVE_LOGCAT_EXITED"
            if run_exit == 0:
                run_exit = 1

    def interrupted(signum, frame):
        raise InterruptedError(signum)
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, interrupted)
    with writer_lock(root):
        if any(p.name != ".writer.lock" for p in root.iterdir()):
            raise RuntimeError("Diagnostic live directory must be new; stale reports are forbidden")
        write_json(root / "RUN_STARTED.json", {"utc_ns": time.time_ns()})
        try:
            plan = source_plan(repo, group, api)
            write_json(root / "plan.json", plan)
            devices = subprocess.check_output(["adb", "devices"]).decode()
            serials = [line.split()[0] for line in devices.splitlines() if line.endswith("\tdevice")]
            if len(serials) != 1:
                raise RuntimeError("Exactly one real diagnostic emulator must be connected")
            device = AdbDevice(serials[0])
            actual_api = device.command("shell", "getprop ro.build.version.sdk")
            if actual_api.returncode or actual_api.stdout.decode().strip() != str(api):
                raise RuntimeError("Connected device API does not match selected shard")
            for name, args in {
                "adb-version": ("version",), "adb-help": ("help",),
                "device-properties": ("shell", "getprop"),
                "font-initial": ("shell", "settings get system font_scale"),
                "ime-initial": ("shell", "settings get secure default_input_method"),
                "toybox-help": ("shell", "stat --help; find --help"),
            }.items():
                result = device.command(*args)
                (root / f"{name}.txt").write_bytes(result.stdout + result.stderr)
                event(root, "tooling", command=name, raw_exit=result.returncode)
            for command in ("settings put secure show_ime_with_hard_keyboard 1",):
                result = device.command("shell", command)
                if result.returncode:
                    raise RuntimeError("Could not configure original full-suite IME precondition")
            device.command("logcat", "-c")
            with (root / "logcat.txt").open("wb") as log, (root / "gradle.log").open("wb") as gradle_log:
                logcat = start_process(device.prefix + ["logcat", "-v", "threadtime"],
                                       stdout=log, stderr=subprocess.STDOUT)
                logcat_lifecycle["started"] = True
                command = ["./gradlew", ":app:connectedDebugAndroidTest", "--no-daemon",
                           "-Pandroid.testInstrumentationRunnerArguments.class=" + plan["selector"]]
                write_json(root / "command.json", {"cwd": str(Path(repo, "android")), "argv": command})
                gradle = start_process(command, cwd=Path(repo, "android"), stdout=gradle_log,
                                       stderr=subprocess.STDOUT)
                collector = Collector(root, device)
                deadline = time.monotonic() + deadline_seconds
                sequence = 0
                while process_exit(gradle) is None:
                    check_logcat("periodic")
                    if logcat_lifecycle["premature_exit"] is not None:
                        break
                    if time.monotonic() >= deadline:
                        termination = "BOUNDED_TIMEOUT"
                        run_exit = 124
                        break
                    collector.sample(deadline)
                    native_sample(root, device, sequence, deadline)
                    sequence += 1
                    time.sleep(min(5, max(0, deadline - time.monotonic())))
                if termination is None:
                    raw_exit = process_exit(gradle)
                    run_exit = raw_exit if raw_exit >= 0 else 128 - raw_exit
                check_logcat("test-completed")
        except InterruptedError as error:
            termination, run_exit = f"SIGNAL_{error.args[0]}", 128 + error.args[0]
        except Exception as error:
            termination = "HARNESS_ERROR"
            event(root, "harness-error", type=type(error).__name__, error=str(error))
        finally:
            cleanup = {"gradle": stop_process(gradle)}
            if gradle is not None and raw_exit is None:
                raw_exit = cleanup["gradle"]["raw_exit"]
            try:
                if collector is not None:
                    collector.sample(time.monotonic() + 20)
                if device is not None:
                    native_sample(root, device, 99999, time.monotonic() + 20)
            except Exception as error:
                if isinstance(error, InterruptedError):
                    termination = termination or f"SIGNAL_{error.args[0]}"
                    if run_exit == 0:
                        run_exit = 128 + error.args[0]
                else:
                    termination = termination or "HARNESS_ERROR"
                    if run_exit == 0:
                        run_exit = 1
                event(root, "final-collection-error", type=type(error).__name__, error=str(error))
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
                event(root, "logcat-status-error", error=str(error))
            cleanup["logcat"] = stop_process(logcat)
            interrupted_signals = [s for row in cleanup.values() for s in row["interrupted_signals"]]
            if interrupted_signals and termination is None:
                termination, run_exit = f"SIGNAL_{interrupted_signals[0]}", 128 + interrupted_signals[0]
            logcat_stop = cleanup["logcat"]
            # The final status check is also made immediately before the first
            # owned signal, closing the collection-to-teardown check gap.
            for key in ("exit_before_stop", "exit_before_signal"):
                if logcat_stop[key] is not None:
                    logcat_lifecycle["premature_exit"] = logcat_stop[key]
            logcat_lifecycle["owned_teardown_requested"] = bool(logcat_stop["signals"])
            logcat_lifecycle["healthy"] = (logcat_lifecycle["started"] and
                logcat_lifecycle["premature_exit"] is None and bool(logcat_stop["signals"]) and
                logcat_stop["writers_stopped"] and logcat_stop["raw_exit"] is not None)
            if logcat is not None and not logcat_lifecycle["healthy"]:
                termination = termination or ("LIVE_LOGCAT_EXITED" if
                    logcat_lifecycle["premature_exit"] is not None else "LOGCAT_STATUS_UNVERIFIED")
                if run_exit == 0:
                    run_exit = 1
            writers_stopped = all(row["writers_stopped"] for row in cleanup.values())
            if not writers_stopped:
                termination = termination or "OWNED_CLEANUP_FAILED"
                if run_exit == 0:
                    run_exit = 1
            write_json(root / "cleanup.json", cleanup)
            write_json(root / "exit.json", {"raw_gradle_exit": raw_exit,
                                           "raw_logcat_exit": logcat_stop["raw_exit"],
                                           "logcat_lifecycle": logcat_lifecycle,
                                           "run_exit": run_exit, "termination": termination})
            write_json(root / "COLLECTOR_STOPPED.json", {"writers_stopped": writers_stopped,
                                                         "utc_ns": time.time_ns()})
    return run_exit


def seal(root, archive):
    root, archive = Path(root), Path(archive)
    with writer_lock(root):
        marker = root / "COLLECTOR_STOPPED.json"
        if not marker.exists() or not json.loads(marker.read_text()).get("writers_stopped"):
            raise RuntimeError("No stopped-writer receipt; final archive cannot race collector/logcat/Gradle")
        if archive.exists():
            raise RuntimeError("Sealed archive already exists")
        files = [p for p in sorted(root.rglob("*")) if p.is_file() and
                 p.name not in (".writer.lock", "archive-manifest.json") and p.suffix != ".part"]
        manifest = [{"path": str(p.relative_to(root)), "bytes": p.stat().st_size,
                     "sha256": digest(p)} for p in files]
        write_json(root / "archive-manifest.json", {"files": manifest, "writers_stopped": True})
        archive.parent.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_name(archive.name + ".part")
        with tarfile.open(temporary, "w:gz") as tar:
            for path in files + [root / "archive-manifest.json"]:
                tar.add(path, arcname=str(path.relative_to(root)), recursive=False)
        os.replace(temporary, archive)
        archive.with_suffix(archive.suffix + ".sha256").write_text(digest(archive) + "\n")


def finish(repo, root, archive, group):
    import shutil
    root = Path(root)
    # Only after run_probe has stopped ALL writers. Copy real reports/APKs before
    # sealing; upload only the resulting immutable archive and its digest.
    with writer_lock(root):
        marker = root / "COLLECTOR_STOPPED.json"
        if not marker.exists() or not json.loads(marker.read_text()).get("writers_stopped"):
            if marker.exists() or (root / "RUN_STARTED.json").exists():
                # Never archive a possibly live logcat/Gradle child after a hard
                # runner interruption. Retain an immutable guard receipt only.
                exit_path = root / "exit.json"
                exits = json.loads(exit_path.read_text()) if exit_path.exists() else {}
                result = inspect_results(root / "no-completed-reports", selected_cases(group),
                                         exits.get("raw_gradle_exit"))
                cleanup_path = root / "cleanup.json"
                result.update(evidence_kind="UNSEALED_INTERRUPTED_ATTEMPT",
                              exits=exits,
                              stopped_marker=json.loads(marker.read_text()) if marker.exists() else None,
                              cleanup=json.loads(cleanup_path.read_text()) if cleanup_path.exists() else {},
                              limitation="No stopped-writer receipt; live evidence was not archived")
                write_json(Path(archive).parent / "unsealed-attempt.json", result)
                print(json.dumps({"classification": result["classification"], "counts": result["counts"],
                                  "limitation": result["limitation"]}))
                return 1
            # Setup/boot can fail before the run step. Preserve missing counts
            # and absent raw exits without inventing any device execution.
            write_json(marker, {"writers_stopped": True, "run_never_started": True})
        build = Path(repo, "android/app/build")
        for relative in ("outputs/androidTest-results/connected", "reports/androidTests/connected",
                         "outputs/apk/debug", "outputs/apk/androidTest/debug"):
            source = build / relative
            if source.exists():
                shutil.copytree(source, root / "build" / relative, dirs_exist_ok=True)
        plan_path = root / "plan.json"
        plan = json.loads(plan_path.read_text()) if plan_path.exists() else None
        exit_path = root / "exit.json"
        exits = json.loads(exit_path.read_text()) if exit_path.exists() else {}
        result = inspect_results(root / "build/outputs/androidTest-results/connected",
                                 selected_cases(group), exits.get("raw_gradle_exit"))
        result.update(evidence_kind="DEVICE_DIAGNOSTIC_ATTEMPT", plan=plan, exits=exits)
        apks = [{"path": str(p.relative_to(root)), "bytes": p.stat().st_size, "sha256": digest(p)}
                for p in sorted((root / "build/outputs/apk").rglob("*.apk"))]
        result["apks"] = apks
        if plan is None or plan["expected_case_ids"] != selected_cases(group):
            result["classification"] = "INVALID_EXECUTION"
            result["invalid_reasons"].append("Missing/mismatched source plan")
        elif any(not Path(repo, name).is_file() or digest(Path(repo, name)) != sha
                 for name, sha in plan["android_source_sha256"].items()):
            result["classification"] = "INVALID_EXECUTION"
            result["invalid_reasons"].append("Android source changed since bound pre-build plan")
        if len(apks) != 2 or exits.get("termination") is not None:
            result["classification"] = "INVALID_EXECUTION"
            result["invalid_reasons"].append("Missing exact app/test APK pair or interrupted harness")
        if exits.get("raw_logcat_exit") is None or exits.get("logcat_lifecycle", {}).get("healthy") is not True:
            result["classification"] = "INVALID_EXECUTION"
            result["invalid_reasons"].append("Missing/unhealthy continuous logcat lifecycle")
        write_json(root / "results.json", result)
    seal(root, archive)
    print(json.dumps({"classification": result["classification"], "counts": result["counts"],
                      "raw_gradle_exit": result["raw_gradle_exit"], "archive": str(archive)}))
    return 0 if result["classification"] == "COMPLETE_PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "run", "finish"))
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--group", choices=METHODS, required=True)
    parser.add_argument("--api", type=int, choices=(26, 35), required=True)
    parser.add_argument("--live", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=1500)
    args = parser.parse_args()
    if args.action == "plan":
        print(json.dumps(source_plan(args.repo, args.group, args.api), indent=2))
        return 0
    if os.environ.get("GITHUB_ACTIONS") != "true":
        parser.error("Real builds/tests run only on Actions; local unit fixtures use interfaces")
    if args.live is None or (args.action == "finish" and args.archive is None):
        parser.error("Live output/archive paths required")
    if not 1 <= args.timeout_seconds <= 1500:
        parser.error("Diagnostic command must be bounded to at most 25 minutes")
    if args.action == "run":
        return run_probe(args.repo, args.live, args.group, args.api, args.timeout_seconds)
    return finish(args.repo, args.live, args.archive, args.group)


if __name__ == "__main__":
    raise SystemExit(main())
