#!/usr/bin/env python3
"""Linux Actions-only E1 diagnostic. Performance decisions remain in original tests."""
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

REF = "refs/heads/verify/issue19-capacity-smoke-abba-20261112"
BLOCKS = ("01-A", "02-B", "03-B", "04-A")
SCENARIOS = ("p1-4500", "plan-6000")
STATE = Path("/tmp/android-worker-state")
WORKER_LOG = Path("/tmp/android-worker.log")


def require_smoke_base():
    if os.environ.get("DINING_SMOKE_BASE_URL") != "http://127.0.0.1:8787/api/v1":
        raise RuntimeError("Requires exact local smoke base http://127.0.0.1:8787/api/v1")


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def process(pid, pgid=None):
    path = Path("/proc") / str(pid)
    try:
        fields = (path / "stat").read_text().rpartition(") ")[2].split()
        if pgid is not None and int(fields[2]) != pgid:
            return None
        return dict(pid=pid, state=fields[0], ppid=int(fields[1]), pgid=int(fields[2]),
                    sid=int(fields[3]), start_ticks=int(fields[19]),
                    argv=(path / "cmdline").read_bytes().decode(errors="replace").split("\0")[:-1])
    except (FileNotFoundError, ProcessLookupError):
        return None


def members(pgid):
    rows = []
    for path in Path("/proc").iterdir():
        if path.name.isdigit() and (row := process(int(path.name), pgid)) is not None:
            rows.append(row)
    return sorted(rows, key=lambda row: row["pid"])


def owned(proc, identity):
    rows = members(proc.pid)
    leader = next((row for row in rows if row["pid"] == proc.pid), None)
    if (proc.poll() is not None or leader is None or identity is None or
            any(leader[key] != identity[key] for key in ("pid", "ppid", "pgid", "sid", "start_ticks", "argv")) or
            identity["ppid"] != os.getpid() or identity["pgid"] != proc.pid or
            identity["sid"] != proc.pid or any(row["sid"] != proc.pid for row in rows)):
        raise RuntimeError("Smoke ownership rejected; no group signal sent")
    return rows


def completed_reaping(proc, identity, block):
    if proc.poll() != 0 or (block / "cleanup.reaper.exit").read_text() != "0\n":
        raise RuntimeError("Subreaper exit is not normal successful completion")
    expected = {"schemaVersion": 1, "path": "after-TERM-no-children",
                "identity": {key: identity[key] for key in ("pid", "ppid", "pgid", "sid", "start_ticks", "argv")},
                "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    try:
        proof = json.loads((block / "reaper.complete.json").read_text())
        if proof != expected:
            raise ValueError("Mismatched owner/source/path")
    except (OSError, ValueError) as error:
        raise RuntimeError("Subreaper completion proof missing or mismatched") from error


def stop_group(proc, identity, block):
    before = []
    try:
        try:
            before = owned(proc, identity)
        except Exception as error:
            (block / "ownership.failure").write_text(str(error) + "\n")
            (block / "ownership.exit").write_text("1\n")
            raise
        save(block / "cleanup.before.json", before)
        if not (block / "ownership.failure").exists():
            (block / "ownership.exit").write_text("0\n")
        os.killpg(proc.pid, signal.SIGTERM)
        save(block / "cleanup.signal.json", {"pgid": proc.pid, "signal": "TERM", "identity": identity})
        (block / "cleanup.reaper.exit").write_text("NOT_COMPLETED\n")
        code = proc.wait(timeout=20)
        (block / "cleanup.reaper.exit").write_text(str(code) + "\n")
        if code != 0:
            raise RuntimeError("Subreaper exit was abnormal: " + str(code))
        completed_reaping(proc, identity, block)
        survivors = [row for old in before if (row := process(old["pid"])) is not None and row["start_ticks"] == old["start_ticks"]]
        save(block / "cleanup.survivors.json", survivors)
        if members(proc.pid) or survivors:
            raise RuntimeError("Smoke members remain after TERM")
        (block / "cleanup.exit").write_text("0\n")
    except Exception as error:
        (block / "cleanup.exit").write_text("1\n")
        with (block / "cleanup.failure").open("a") as evidence:
            evidence.write(str(error) + "\n")
        raise
    finally:
        save(block / "cleanup.after.json", members(proc.pid))


def smoke_group(block):
    require_smoke_base()
    reaper_source = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    # Keep a verifiable session leader after the existing starter backgrounds Wrangler.
    # Reap adopted grandchildren on TERM so B can prove an empty group, including zombies.
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise RuntimeError("Cannot enable Linux child subreaper")
    stopping = False

    def term(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, term)
    result = subprocess.run(["bash", ".github/scripts/start-android-api.sh"])
    (block / "startup.exit").write_text(str(result.returncode) + "\n")
    while True:
        try:
            while os.waitpid(-1, os.WNOHANG)[0]:
                pass
        except ChildProcessError:
            if stopping:
                identity = process(os.getpid())
                save(block / "reaper.complete.json", {
                    "schemaVersion": 1, "path": "after-TERM-no-children",
                    "identity": {key: identity[key] for key in ("pid", "ppid", "pgid", "sid", "start_ticks", "argv")},
                    "runnerSha256": reaper_source})
                return
        time.sleep(0.05)


def run_logged(argv, log, cwd, guard=None):
    save(log.with_suffix(".command.json"), {"argv": argv, "cwd": str(cwd)})
    # Arguments are positional, never interpolated shell text. Retain the original exit
    # independently of tee; a failed first scenario cannot suppress the second process.
    script = '''set +e
log=$1; shift
(printf '%s\n' "$BASHPID" > "$log.pid"; exec "$@") 2>&1 | tee "$log"
codes=("${PIPESTATUS[@]}")
printf '%s\n' "${codes[0]}" > "$log.exit"
printf '%s\n' "${codes[1]}" > "$log.tee.exit"
test "${codes[0]}" -eq 0 && test "${codes[1]}" -eq 0
'''
    proc = subprocess.Popen(["bash", "-c", script, "e1", str(log), *argv], cwd=cwd)
    valid = True
    while True:
        if guard is not None:
            try:
                rows = guard()
                observation = {"monotonic": time.monotonic(), "members": rows}
            except Exception as error:
                valid = False
                observation = {"monotonic": time.monotonic(), "failure": str(error)}
                log.with_suffix(".liveness.failure").write_text(str(error) + "\n")
            with log.with_suffix(".liveness.during.jsonl").open("a") as evidence:
                evidence.write(json.dumps(observation) + "\n")
        if proc.poll() is not None:
            break
        time.sleep(0.05)
    return proc.returncode == 0 and valid


def workerds(rows):
    return [row for row in rows if row["state"] != "Z" and row["argv"] and "workerd" in row["argv"][0]]


def liveness(proc, identity, treatment, witnesses, block):
    if treatment:
        completed_reaping(proc, identity, block)
    rows = members(proc.pid) if treatment else owned(proc, identity)
    if treatment:
        if rows:
            raise RuntimeError("Smoke group reappeared during B capacity")
    else:
        actual = {(row["pid"], row["start_ticks"]) for row in workerds(rows)}
        if not witnesses or any((row["pid"], row["start_ticks"]) not in actual for row in witnesses):
            raise RuntimeError("Original smoke workerd exited during A capacity")
    return rows


def source(repo):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=repo, text=True).strip()
    tracked = git("ls-files").splitlines()
    return {"head": git("rev-parse", "HEAD"), "backend_tree": git("rev-parse", "HEAD:backend"),
            "testSha256": hashlib.sha256((repo / "backend/tests/capacity/capacity_test.ts").read_bytes()).hexdigest(),
            "lockSha256": hashlib.sha256((repo / "backend/package-lock.json").read_bytes()).hexdigest(),
            "trackedSha256": {name: hashlib.sha256((repo / name).read_bytes()).hexdigest() for name in tracked}}


def validate_measurements(result):
    # Completeness only. Original tests own quantiles and every performance gate.
    if (any(type(result.get(key)) is not int or result[key] != expected
            for key, expected in (("schemaVersion", 1), ("repetitions", 30), ("p95LimitMs", 100))) or
            result.get("wallClock") != "Date.now (1 ms resolution; Workers performance.now is not used)"):
        raise RuntimeError("Missing original measurement schema/repetitions/100ms identity")
    query_ids = ("updated_page", "rating_page", "aggregate_search_hit", "aggregate_search_miss",
                 "restaurant_detail", "restaurant_dishes", "restaurant_visits", "detail_children_batch", "entity_revisions_10")
    queries = result.get("queries")
    if (not isinstance(queries, list) or len(queries) != len(query_ids) or
            {query["id"] for query in queries} != set(query_ids)):
        raise RuntimeError("Missing/extra/duplicate SQL measurement records")

    def number(value):
        return type(value) in (int, float) and math.isfinite(value)

    for query in queries:
        count = 3 if query["id"] == "detail_children_batch" else 1
        statements = query.get("statements")
        if (not isinstance(statements, list) or len(statements) != count or
                any(not isinstance(statement.get("sql"), str) or not statement["sql"] or
                    not isinstance(statement.get("params"), list) for statement in statements)):
            raise RuntimeError("Incomplete original SQL statement records")
        samples = query.get("samples")
        if not isinstance(samples, list) or len(samples) != 30:
            raise RuntimeError("SQL measurement requires exactly 30 complete samples")
        for sample in samples:
            if (not isinstance(sample, dict) or
                    set(sample) != {"wallMs", "sqlMs", "rowsRead", "rowsWritten", "returnedRows"} or
                    any(not number(sample[key]) for key in ("wallMs", "sqlMs", "rowsRead", "rowsWritten")) or
                    not isinstance(sample["returnedRows"], list) or len(sample["returnedRows"]) != count or
                    any(type(value) is not int or value < 0 for value in sample["returnedRows"])):
                raise RuntimeError("Incomplete original SQL sample")
    detail = next(query for query in queries if query["id"] == "restaurant_detail")
    params = detail["statements"][0]["params"]
    if (len(params) != 2 or not isinstance(params[1], str) or
            not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", params[1])):
        raise RuntimeError("Missing original SQL/API restaurant identity")
    base = "/restaurants/" + params[1]
    paths = {"/restaurants?limit=20", "/restaurants?sort=rating_desc&limit=20",
             "/search?q=%E6%8B%9B%E7%89%8C%E6%B5%8B%E8%AF%95", "/search?q=capacity_missing_51a71c",
             base, base + "/dishes", base + "/visits", base + "/revisions?limit=10"}
    api = result.get("actualAPI")
    if not isinstance(api, list) or len(api) != 8 or {record["path"] for record in api} != paths:
        raise RuntimeError("Missing/extra/duplicate API measurement records")
    for record in api:
        samples = record.get("samples")
        if not isinstance(samples, list) or len(samples) != 30 or any(not number(value) for value in samples):
            raise RuntimeError("API measurement requires exactly 30 complete samples")


def main():
    require_smoke_base()
    repo = Path.cwd().resolve()
    output = Path(os.environ["E1_OUTPUT"]).resolve()
    if os.environ.get("GITHUB_ACTIONS") != "true" or sys.platform != "linux" or output.is_relative_to(repo):
        raise RuntimeError("Requires Linux Actions and evidence outside source")
    output.mkdir(parents=True, exist_ok=False)
    save(output / "predeclared.json", {"blocks": BLOCKS, "scenarios": SCENARIOS,
                                     "classification": "DIAGNOSTIC_SMOKE_LIVENESS_ABBA_NOT_PRODUCTION_PROOF"})
    before = source(repo)
    save(output / "source.before.json", before)
    save(output / "runtime.json", {"node": subprocess.check_output(["node", "--version"], text=True).strip(),
                                  "runnerOs": os.environ.get("RUNNER_OS"), "runnerImage": os.environ.get("ImageVersion"),
                                  "ref": os.environ.get("GITHUB_REF"), "nodeOptions": os.environ.get("NODE_OPTIONS")})
    if (os.environ.get("GITHUB_REF") != REF or before["head"] != os.environ.get("GITHUB_SHA") or
            before["backend_tree"] != os.environ.get("E1_EXPECTED_BACKEND_TREE")):
        raise RuntimeError("HEAD/ref/backend tree mismatch")
    subprocess.run(["git", "diff", "--exit-code", "HEAD"], cwd=repo, check=True, stdout=subprocess.DEVNULL)
    if STATE.exists() or STATE.is_symlink() or WORKER_LOG.exists() or WORKER_LOG.is_symlink():
        raise RuntimeError("Refuse preexisting unowned smoke state/log")
    fixture_names = [name for name in before["trackedSha256"] if name.startswith(".github/fixtures/")]
    fixtures = {name: ((repo / name).read_bytes(), (repo / name).stat().st_mode) for name in fixture_names}
    baseline_fixtures = None
    failed = False
    safe = True
    for name in BLOCKS:
        block = output / name
        block.mkdir()
        proc = None
        identity = None
        state_inode = None
        phase = "startup"
        if not safe:
            (block / "infrastructure.failure").write_text("NOT_RUN: prior unsafe cleanup\n")
            continue
        try:
            STATE.mkdir(mode=0o700)
            state_inode = STATE.stat().st_ino
            save(block / "source.before.json", source(repo))
            save(block / "startup.command.json", {"argv": ["bash", ".github/scripts/start-android-api.sh"], "cwd": str(repo)})
            with (block / "startup.log").open("w") as log:
                proc = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--smoke-group", str(block)],
                                        cwd=repo, start_new_session=True, stdout=log, stderr=subprocess.STDOUT)
            identity = next((row for row in members(proc.pid) if row["pid"] == proc.pid), None)
            deadline = time.monotonic() + 120
            while not (block / "startup.exit").exists():
                if proc.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Starter failed to return its exit")
                if identity is None:
                    identity = next((row for row in members(proc.pid) if row["pid"] == proc.pid), None)
                time.sleep(0.05)
            save(block / "smoke.identity.json", identity)
            phase = "ownership"
            save(block / "smoke.started.json", owned(proc, identity))
            (block / "ownership.exit").write_text("0\n")
            phase = "startup"
            if (block / "startup.exit").read_text().strip() != "0":
                raise RuntimeError("Startup failed; capacity NOT_RUN")
            phase = "fixtures"
            devvars = repo / ".github/fixtures/.dev.vars"
            if devvars.exists():
                shutil.copyfile(devvars, block / "public-ephemeral.dev.vars")
            # The starter may rewrite PUBLIC ephemeral fixtures; restore exact tracked bytes.
            for fixture, (data, mode) in fixtures.items():
                (repo / fixture).write_bytes(data)
                (repo / fixture).chmod(mode)
            phase = "smoke"
            if not run_logged(["npm", "run", "smoke:production"], block / "smoke.log", repo / "backend"):
                raise RuntimeError("Smoke failed; capacity NOT_RUN")
            phase = "ownership"
            witnesses = workerds(owned(proc, identity))
            save(block / "smoke.workerd-identities.json", witnesses)
            if not witnesses:
                raise RuntimeError("No live owned smoke workerd after prerequisites")
            if name.endswith("B"):
                phase = "cleanup"
                stop_group(proc, identity, block)
            phase = "liveness"
            save(block / "source.capacity-start.json", source(repo))
            for scenario in SCENARIOS:
                rows = liveness(proc, identity, name.endswith("B"), witnesses, block)
                save(block / (scenario + ".liveness.json"), rows)
                if source(repo) != before:
                    raise RuntimeError("Source changed before capacity")
                failed |= not run_logged(["./node_modules/.bin/vitest", "run", "--config", "vitest.capacity.config.ts", "-t", scenario],
                                         block / (scenario + ".log"), repo / "backend",
                                         guard=lambda: liveness(proc, identity, name.endswith("B"), witnesses, block))
            rows = liveness(proc, identity, name.endswith("B"), witnesses, block)
            save(block / "smoke.measurements-end.json", rows)
            combined = b"".join((block / (scenario + ".log")).read_bytes() for scenario in SCENARIOS)
            (block / "capacity.log").write_bytes(combined)
            phase = "renderer"
            failed |= not run_logged(["node", "tests/capacity/report.mjs", str(block / "capacity.log")], block / "renderer.log", repo / "backend")
            phase = "evidence"
            results = [json.loads(match[1]) for line in combined.decode(errors="replace").splitlines()
                       if (match := re.search(r"CAPACITY_RESULT (\{.*\})", line))]
            if [result["scenario"]["name"] for result in results] != list(SCENARIOS):
                raise RuntimeError("Missing/duplicate scenario evidence")
            for result in results:
                validate_measurements(result)
            hashes = {result["scenario"]["name"]: result["seed"]["fixtureSha256"] for result in results}
            if any(not re.fullmatch(r"[0-9a-f]{64}", value) for value in hashes.values()):
                raise RuntimeError("Missing fixture hashes")
            save(block / "fixture-hashes.json", hashes)
            if baseline_fixtures is not None and hashes != baseline_fixtures:
                raise RuntimeError("Fixture differs between blocks")
            baseline_fixtures = hashes
            for report in ("measurements.json", "RESULTS.md"):
                if not (block / report).is_file() or not (block / report).stat().st_size:
                    raise RuntimeError("Missing rendered evidence: " + report)
        except (Exception, KeyboardInterrupt) as error:
            failed = True
            with (block / (phase + ".failure")).open("a") as evidence:
                evidence.write(str(error) + "\n")
            if phase == "ownership":
                (block / "ownership.exit").write_text("1\n")
            (block / "infrastructure.failure").write_text(str(error) + "\n")
        finally:
            try:
                if proc is not None and not (block / "cleanup.exit").exists():
                    stop_group(proc, identity, block)
                elif proc is not None and (block / "cleanup.exit").read_text().strip() != "0":
                    raise RuntimeError("Prior unsafe group cleanup; no repeated signal")
                if state_inode is not None:
                    if STATE.is_symlink() or STATE.stat().st_ino != state_inode:
                        raise RuntimeError("Smoke state ownership changed")
                    shutil.rmtree(STATE)
                if WORKER_LOG.exists():
                    shutil.copyfile(WORKER_LOG, block / "worker.log")
                    WORKER_LOG.unlink()
            except Exception as error:
                failed = True
                safe = False
                (block / "cleanup.exit").write_text("1\n")
                with (block / "cleanup.failure").open("a") as evidence:
                    evidence.write(str(error) + "\n")
            devvars = repo / ".github/fixtures/.dev.vars"
            if devvars.exists() and not (block / "public-ephemeral.dev.vars").exists():
                shutil.copyfile(devvars, block / "public-ephemeral.dev.vars")
            for fixture, (data, mode) in fixtures.items():
                (repo / fixture).write_bytes(data)
                (repo / fixture).chmod(mode)
            after = source(repo)
            save(block / "source.after.json", after)
            failed |= after != before
    save(output / "source.after.json", source(repo))
    subprocess.run(["git", "diff", "--exit-code", "HEAD"], cwd=repo, check=True, stdout=subprocess.DEVNULL)
    failed |= any(not (output / name / "fixture-hashes.json").exists() for name in BLOCKS)
    (output / "runner.exit").write_text("1\n" if failed else "0\n")
    return int(failed)


if __name__ == "__main__":
    require_smoke_base()
    if len(sys.argv) == 3 and sys.argv[1] == "--smoke-group":
        smoke_group(Path(sys.argv[2]))
    else:
        try:
            sys.exit(main())
        except Exception as error:
            output = Path(os.environ.get("E1_OUTPUT", "/tmp/e1-missing-output"))
            if output.is_dir():
                (output / "runner.failure").write_text(str(error) + "\n")
                (output / "runner.exit").write_text("1\n")
            raise
