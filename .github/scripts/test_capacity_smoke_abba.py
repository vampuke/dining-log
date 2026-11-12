"""Linux control-flow simulation only; no real capacity data or dependencies."""
import importlib.util
import inspect
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

RUNNER = Path(__file__).with_name("capacity-smoke-abba.py").resolve()
EVIDENCE = Path(os.environ.get("E1_TEST_EVIDENCE") or tempfile.mkdtemp(prefix="e1-unittest-evidence-"))
EVIDENCE.mkdir(parents=True, exist_ok=True)


def simulated_result(scenario):
    """Original measurement shape with invented, equal samples; NEVER native evidence."""
    selected = "00000000-0000-0000-0000-000000000002"
    user = "00000000-0000-0000-0000-000000000001"
    large = scenario == "plan-6000"
    scenario_data = dict(name=scenario, restaurants=500, dishesPerRestaurant=8 if large else 5,
                         visitsPerRestaurant=3, restaurantRevisions=14 if large else 11,
                         childRevisions=2, objects=6000 if large else 4500, history=18000 if large else 13500)
    quantiles = dict(min=1, p50=1, p95=1, max=1)
    query_ids = ("updated_page", "rating_page", "aggregate_search_hit", "aggregate_search_miss",
                 "restaurant_detail", "restaurant_dishes", "restaurant_visits", "detail_children_batch", "entity_revisions_10")
    queries = []
    for query_id in query_ids:
        count = 3 if query_id == "detail_children_batch" else 1
        queries.append(dict(id=query_id,
            statements=[dict(sql="SIMULATED SQL: " + query_id, params=[user, selected]) for _ in range(count)],
            plans=[["SIMULATED PLAN"] for _ in range(count)],
            samples=[dict(wallMs=1, sqlMs=1, rowsRead=1, rowsWritten=0, returnedRows=[1] * count) for _ in range(30)],
            wallMs=dict(quantiles), sqlMs=dict(quantiles), rowsRead=dict(quantiles)))
    paths = ("/restaurants?limit=20", "/restaurants?sort=rating_desc&limit=20",
             "/search?q=%E6%8B%9B%E7%89%8C%E6%B5%8B%E8%AF%95", "/search?q=capacity_missing_51a71c",
             "/restaurants/" + selected, "/restaurants/" + selected + "/dishes",
             "/restaurants/" + selected + "/visits", "/restaurants/" + selected + "/revisions?limit=10")
    return dict(schemaVersion=1, scenario=scenario_data, repetitions=30, p95LimitMs=100,
                wallClock="Date.now (1 ms resolution; Workers performance.now is not used)",
                seed=dict(fixtureSha256=("b" if large else "a") * 64, simulation_only=True),
                runtime=dict(sqlite_version="SIMULATED"), rowCounts={}, restaurantRevisionRange={},
                storage=dict(simulation_only=True), write=dict(simulation_only=True),
                integrity=dict(foreignKeyViolations=0, quickCheck="ok"), overLimit=[],
                queries=queries, actualAPI=[dict(path=path, samples=[1] * 30, wallMs=dict(quantiles)) for path in paths],
                simulation_only=True)


class Control(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="e1-owned-test-"))
        self.repo = self.root / "repo"
        self.repo.mkdir()
        for name in ["backend/node_modules/.bin", "backend/tests/capacity", ".github/scripts", ".github/fixtures", "bin"]:
            (self.repo / name).mkdir(parents=True)
        for name in ["package-lock.json", "package.json", "vitest.capacity.config.ts", "tests/capacity/capacity_test.ts", "tests/capacity/report.mjs"]:
            (self.repo / "backend" / name).write_text("{}\n")
        (self.repo / ".github/fixtures/android-worker.toml").write_bytes(b"exact public fixture\n")
        self.put(".github/scripts/start-android-api.sh", "#!/bin/bash\nbash -c 'exec -a owned-dummy-workerd sleep 300' &\nexit ${FAKE_START_EXIT:-0}\n")
        self.put("bin/npm", "#!/bin/bash\nexit ${FAKE_SMOKE_EXIT:-0}\n")
        self.put("bin/simulated_result.py", inspect.getsource(simulated_result))
        self.put("backend/node_modules/.bin/vitest", """#!/usr/bin/env python3
import json, os, signal, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'bin'))
from simulated_result import simulated_result
p=Path(os.environ['CALLS'])
with p.open('a') as f:f.write(sys.argv[-1]+'\\n')
block=sorted(Path(os.environ['E1_OUTPUT']).glob('*-*'))[-1]
identity=json.loads((block/'smoke.identity.json').read_text())
rows=[]
for path in Path('/proc').iterdir():
 if not path.name.isdigit():continue
 try:
  fields=(path/'stat').read_text().rpartition(') ')[2].split()
  if int(fields[2])==identity['pgid']:
   rows.append({'pid':int(path.name),'state':fields[0],'argv':(path/'cmdline').read_bytes().decode(errors='replace')})
 except (FileNotFoundError,ProcessLookupError):pass
with Path(os.environ['OBSERVATIONS']).open('a') as f:
 f.write(json.dumps({'block':block.name,'scenario':sys.argv[-1],'pid':os.getpid(),'members':rows,'cleanup_done':(block/'cleanup.exit').exists() and (block/'cleanup.after.json').read_text().strip()=='[]'})+'\\n')
if os.environ.get('FAKE_ROTATE_WORKER')=='1' and block.name.endswith('A') and sys.argv[-1]=='p1-4500':
 (block/'replace.request').touch()
 deadline=time.monotonic()+3
 while not (block/'replacement.ready').exists():
  if time.monotonic()>deadline:raise SystemExit(99)
  time.sleep(.01)
if os.environ.get('FAKE_OMIT')!=sys.argv[-1]:
 if os.environ.get('FAKE_RAW')=='1':
  sys.stdout.buffer.write(b'RAW\\xff\\x00\\r\\n');sys.stdout.buffer.flush()
 result=simulated_result(sys.argv[-1])
 defect=os.environ.get('FAKE_MEASUREMENT_DEFECT','')
 records=result['queries' if defect.startswith('sql-') else 'actualAPI']
 if defect.endswith('-missing'):records.pop()
 if defect.endswith('-duplicate'):records[-1]=records[0]
 if defect.endswith('-extra'):records.append(dict(records[0], **({'id':'SIMULATED_EXTRA'} if defect.startswith('sql-') else {'path':'/SIMULATED_EXTRA'})))
 if defect.endswith('-partial'):records[0]['samples'].pop()
 if defect=='sql-field-missing':records[0]['samples'][0].pop('sqlMs')
 if defect=='api-null':records[0]['samples'][0]=None
 payload='CAPACITY_RESULT '+json.dumps(result)
 print(payload)
 if os.environ.get('FAKE_DUPLICATE')==sys.argv[-1]:print(payload)
raise SystemExit(int(os.environ.get('FAKE_FIRST_EXIT','0')) if sys.argv[-1]=='p1-4500' else int(os.environ.get('FAKE_SECOND_EXIT','0')))
""")
        self.put("bin/node", """#!/usr/bin/env python3
import os, sys
from pathlib import Path
if sys.argv[-1]=='--version':
 print('v22.0.0-owned-simulation');raise SystemExit(0)
p=Path(sys.argv[-1]).parent
(p/'renderer.input').write_bytes(Path(sys.argv[-1]).read_bytes())
(p/'measurements.json').write_text('{"simulation_only":true}\\n')
(p/'RESULTS.md').write_text('Simulation only\\n')
raise SystemExit(int(os.environ.get('FAKE_REPORT_EXIT','0')))
""")
        for args in [["git", "init", "-q", "-b", "verify/issue19-capacity-smoke-abba-20261112"], ["git", "add", "."], ["git", "-c", "user.name=E1 Test", "-c", "user.email=e1@example.invalid", "commit", "-qm", "owned fake seams"]]:
            subprocess.run(args, cwd=self.repo, check=True, capture_output=True)
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.repo, text=True).strip()
        self.calls = self.root / "calls"
        self.output = self.root / "artifacts"
        self.env = dict(os.environ, GITHUB_ACTIONS="true", GITHUB_SHA=head,
                        GITHUB_REF="refs/heads/verify/issue19-capacity-smoke-abba-20261112",
                        E1_OUTPUT=str(self.output), CALLS=str(self.calls),
                        DINING_SMOKE_BASE_URL="http://127.0.0.1:8787/api/v1",
                        OBSERVATIONS=str(self.root/'observations.jsonl'), DUMMY_READY=str(self.root/'dummy.ready'),
                        PYTHONDONTWRITEBYTECODE="1",
                        PATH=str(self.repo / "bin") + os.pathsep + os.environ["PATH"])
        self.env["E1_EXPECTED_BACKEND_TREE"] = subprocess.check_output(["git", "rev-parse", "HEAD:backend"], cwd=self.repo, text=True).strip()

    def put(self, name, text):
        p = self.repo / name
        p.write_text(text)
        p.chmod(0o755)

    def tearDown(self):
        dest = EVIDENCE / self.id().split('.')[-1]
        if dest.exists():
            raise RuntimeError("Refuse to overwrite test evidence: " + str(dest))
        shutil.copytree(self.root, dest, ignore=shutil.ignore_patterns(".git", "node_modules"))
        shutil.rmtree(self.root)

    def run_runner(self, **env):
        command = Path(os.environ.get("E1_TEST_RUNNER", str(RUNNER)))
        subprocess.run(["git", "add", "."], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "-c", "user.name=E1 Test", "-c", "user.email=e1@example.invalid", "commit", "-qm", "seal dummy scenario", "--allow-empty"], cwd=self.repo, check=True, capture_output=True)
        self.env["GITHUB_SHA"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.repo, text=True).strip()
        self.env["E1_EXPECTED_BACKEND_TREE"] = subprocess.check_output(["git", "rev-parse", "HEAD:backend"], cwd=self.repo, text=True).strip()
        launcher = self.root / "launch.py"
        launcher.write_text("import importlib.util, sys\nfrom pathlib import Path\ns=importlib.util.spec_from_file_location('e1',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)\nm.STATE=Path(sys.argv[2]);m.WORKER_LOG=Path(sys.argv[3])\nsys.exit(m.main())\n")
        result = subprocess.run([sys.executable, "-B", str(launcher), str(command), str(self.root/'state'), str(self.root/'worker.log')], cwd=self.repo,
                                env=dict(self.env, **env), capture_output=True, timeout=45)
        (self.root / "runner.log").write_bytes(result.stdout + result.stderr)
        (self.root / "runner.exit").write_text(str(result.returncode) + "\n")
        return result

    def test_install_pipeline_retains_both_statuses_with_inherited_errexit(self):
        lines = RUNNER.parents[1].joinpath("workflows/capacity-smoke-abba.yml").read_text().splitlines()
        pipeline = next(i for i, line in enumerate(lines) if 'npm ci 2>&1 | tee' in line)
        start = next(i for i in range(pipeline, -1, -1) if lines[i].strip() == "run: |")
        indent = len(lines[start]) - len(lines[start].lstrip())
        end = next((i for i in range(start + 1, len(lines))
                    if lines[i].strip() and len(lines[i]) - len(lines[i].lstrip()) <= indent), len(lines))
        script = "\n".join(line[indent + 2:] for line in lines[start + 1:end]) + "\n"
        self.put("bin/npm", "#!/bin/bash\nprintf 'SIMULATED npm install\\n'\nexit ${FAKE_INSTALL_EXIT:-0}\n")
        self.put("bin/tee", '#!/bin/bash\ncat > "$1"\nexit ${FAKE_TEE_EXIT:-0}\n')
        for npm_exit, tee_exit in ((17, 0), (0, 23), (17, 23), (0, 0)):
            with self.subTest(npm=npm_exit, tee=tee_exit):
                block = self.root / ("install-%s-%s" % (npm_exit, tee_exit))
                block.mkdir()
                (block / "snippet.sh").write_text(script)
                argv = ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", script]
                (block / "command.json").write_text(json.dumps(argv))
                result = subprocess.run(argv, cwd=self.repo, env=dict(self.env, E1_OUTPUT=str(block),
                                        FAKE_INSTALL_EXIT=str(npm_exit), FAKE_TEE_EXIT=str(tee_exit)), capture_output=True)
                (block / "stdout.log").write_bytes(result.stdout)
                (block / "stderr.log").write_bytes(result.stderr)
                (block / "step.exit").write_text(str(result.returncode) + "\n")
                self.assertEqual(result.returncode, int(bool(npm_exit or tee_exit)))
                self.assertEqual((block / "npm-ci.exit").read_text(), str(npm_exit) + "\n")
                self.assertEqual((block / "npm-ci.tee.exit").read_text(), str(tee_exit) + "\n")

    def test_exact_local_smoke_base_rejected_before_any_effect(self):
        runner = self.runner_module()
        for value in (None, "", "https://dining.alanvampuke.top/api/v1", "http://localhost:8787/api/v1",
                      "http://127.0.0.1:8787/api/v1/", "http://127.0.0.1:8788/api/v1"):
            env = dict(self.env)
            env.pop("DINING_SMOKE_BASE_URL", None)
            if value is not None:
                env["DINING_SMOKE_BASE_URL"] = value
            with self.subTest(base=value), mock.patch.dict(os.environ, env, clear=True), \
                    mock.patch.object(Path, "mkdir") as mkdir, mock.patch.object(runner, "save") as save, \
                    mock.patch.object(runner, "source", side_effect=AssertionError("source attempted")) as source, \
                    mock.patch.object(runner.subprocess, "Popen") as popen, \
                    mock.patch.object(runner.subprocess, "run") as run, \
                    mock.patch.object(runner.subprocess, "check_output") as check:
                with self.assertRaisesRegex(RuntimeError, "Requires exact local smoke base"):
                    runner.main()
                for effect in (mkdir, save, source, popen, run, check):
                    effect.assert_not_called()

    def test_first_failure_still_runs_eight(self):
        result = self.run_runner(FAKE_FIRST_EXIT="7")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(self.calls.read_text().splitlines(), ["p1-4500", "plan-6000"] * 4)

    def test_A_B_liveness_precedes_each_independent_capacity_process(self):
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        observations = [json.loads(line) for line in (self.root/'observations.jsonl').read_text().splitlines()]
        self.assertEqual([row['block'] for row in observations], ['01-A']*2+['02-B']*2+['03-B']*2+['04-A']*2)
        self.assertEqual(len({row['pid'] for row in observations}), 8)
        for row in observations:
            block = self.output / row['block']
            self.assertTrue((block/(row['scenario']+'.liveness.json')).is_file())
            if row['block'].endswith('A'):
                self.assertTrue(any('owned-dummy-workerd' in member['argv'] and member['state']!='Z' for member in row['members']))
                self.assertFalse(row['cleanup_done'])
            else:
                self.assertEqual(row['members'], [])
                self.assertTrue(row['cleanup_done'])
            self.assertEqual(json.loads((block/'cleanup.after.json').read_text()), [])

    def test_A_rejects_smoke_worker_replaced_during_capacity(self):
        self.put('.github/scripts/start-android-api.sh', '#!/bin/bash\nrm -f "$DUMMY_READY"\npython3 .github/scripts/rotate-worker.py &\nwhile ! test -f "$DUMMY_READY"; do sleep .01; done\n')
        self.put('.github/scripts/rotate-worker.py', """import os, subprocess, time
from pathlib import Path
block=sorted(Path(os.environ['E1_OUTPUT']).glob('*-*'))[-1]
worker=subprocess.Popen(['bash','-c','exec -a owned-dummy-workerd sleep 300'])
Path(os.environ['DUMMY_READY']).touch()
while not (block/'replace.request').exists():time.sleep(.01)
worker.terminate();worker.wait()
time.sleep(.15)
subprocess.Popen(['bash','-c','exec -a owned-dummy-workerd sleep 300'])
(block/'replacement.ready').touch()
""")
        result = self.run_runner(FAKE_ROTATE_WORKER='1')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertTrue((self.output/'01-A/p1-4500.liveness.failure').is_file())

    def runner_module(self):
        spec = importlib.util.spec_from_file_location('owned_e1', RUNNER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def dummy_leader(self):
        proc = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        def finish():
            if proc.poll() is None:
                proc.terminate()
            proc.wait(timeout=3)
        self.addCleanup(finish)
        return proc

    def test_unknown_group_is_never_signalled_and_rejection_is_retained(self):
        runner = self.runner_module()
        proc = self.dummy_leader()
        identity = next(row for row in runner.members(proc.pid) if row['pid']==proc.pid)
        identity['start_ticks'] += 1
        with mock.patch.object(runner.os, 'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError, 'ownership rejected'):
                runner.stop_group(proc, identity, self.root)
            kill.assert_not_called()
        self.assertIsNone(proc.poll())
        self.assertTrue((self.root/'ownership.failure').is_file())
        self.assertEqual((self.root/'cleanup.exit').read_text(), '1\n')

    def test_abnormal_reaper_exit_is_retained_and_rejected(self):
        runner = self.runner_module()
        for code in (-signal.SIGTERM, 7):
            with self.subTest(exit=code):
                block = self.root / ("abnormal-" + str(code))
                block.mkdir()
                proc = self.dummy_leader()
                identity = runner.process(proc.pid)
                original_wait = proc.wait
                def exited(timeout):
                    original_wait(timeout=timeout)
                    return code
                with mock.patch.object(proc, "wait", side_effect=exited):
                    with self.assertRaisesRegex(RuntimeError, "Subreaper exit"):
                        runner.stop_group(proc, identity, block)
                self.assertEqual((block / "cleanup.reaper.exit").read_text(), str(code) + "\n")
                self.assertEqual((block / "cleanup.exit").read_text(), "1\n")
                self.assertTrue((block / "cleanup.after.json").is_file())

    def test_zero_exit_without_owned_source_completion_proof_is_rejected(self):
        runner = self.runner_module()
        for defect in ("missing", "identity", "source", "path"):
            with self.subTest(proof=defect):
                block = self.root / defect
                block.mkdir()
                proc = self.dummy_leader()
                identity = runner.process(proc.pid)
                proof = {"schemaVersion": 1, "path": "after-TERM-no-children",
                         "identity": {key: identity[key] for key in ("pid", "ppid", "pgid", "sid", "start_ticks", "argv")},
                         "runnerSha256": __import__("hashlib").sha256(RUNNER.read_bytes()).hexdigest()}
                if defect == "identity":
                    proof["identity"]["start_ticks"] += 1
                elif defect == "source":
                    proof["runnerSha256"] = "0" * 64
                elif defect == "path":
                    proof["path"] = "before-TERM"
                if defect != "missing":
                    (block / "reaper.complete.json").write_text(json.dumps(proof))
                original_wait, original_poll = proc.wait, proc.poll
                def exited(timeout):
                    original_wait(timeout=timeout)
                    return 0  # Explicit fake successful exit; never native evidence.
                def polled():
                    return None if original_poll() is None else 0
                with mock.patch.object(proc, "wait", side_effect=exited), \
                        mock.patch.object(proc, "poll", side_effect=polled):
                    with self.assertRaisesRegex(RuntimeError, "Subreaper completion proof"):
                        runner.stop_group(proc, identity, block)
                self.assertEqual((block / "cleanup.exit").read_text(), "1\n")

    def controlled_reaper(self, starter):
        self.put(".github/scripts/start-android-api.sh", starter)
        runner = self.runner_module()
        with (self.root / "reaper.log").open("wb") as log:
            proc = subprocess.Popen([sys.executable, str(RUNNER), "--smoke-group", str(self.root)],
                                    cwd=self.repo, env=dict(self.env, ESCAPE_READY=str(self.root / "escape.ready")),
                                    start_new_session=True, stdout=log, stderr=subprocess.STDOUT)
        def finish():
            if proc.poll() is None:
                runner.owned(proc, runner.process(proc.pid))
                os.killpg(proc.pid, signal.SIGTERM)
            proc.wait(timeout=3)
        self.addCleanup(finish)
        deadline = time.monotonic() + 3
        while not (self.root / "startup.exit").exists():
            if proc.poll() is not None or time.monotonic() > deadline:
                self.fail("Harmless reaper did not start")
            time.sleep(.01)
        return runner, proc, runner.process(proc.pid)

    def test_normal_reaping_proof_allows_empty_B(self):
        runner, proc, identity = self.controlled_reaper("#!/bin/bash\nsleep .2 &\nexit 0\n")
        runner.stop_group(proc, identity, self.root)
        self.assertEqual((self.root / "cleanup.reaper.exit").read_text(), "0\n")
        self.assertEqual(runner.liveness(proc, identity, True, [], self.root), [])
        proof = json.loads((self.root / "reaper.complete.json").read_text())
        self.assertEqual(proof["path"], "after-TERM-no-children")

    def test_alive_escaped_descendant_blocks_completion_without_unknown_signal(self):
        starter = "#!/bin/bash\npython -c 'import os,time; from pathlib import Path; os.setsid(); Path(os.environ[\"ESCAPE_READY\"]).touch(); time.sleep(.6)' &\nuntil test -f \"$ESCAPE_READY\"; do sleep .01; done\nexit 0\n"
        runner, proc, identity = self.controlled_reaper(starter)
        original_wait = proc.wait
        with mock.patch.object(proc, "wait", side_effect=lambda timeout: original_wait(timeout=.05)), \
                mock.patch.object(runner.os, "killpg", wraps=os.killpg) as kill:
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.stop_group(proc, identity, self.root)
            kill.assert_called_once_with(proc.pid, signal.SIGTERM)
        self.assertFalse((self.root / "reaper.complete.json").exists())
        self.assertEqual((self.root / "cleanup.exit").read_text(), "1\n")
        with self.assertRaises(RuntimeError):
            runner.liveness(proc, identity, True, [], self.root)
        original_wait(timeout=3)  # Finite escaped dummy exits naturally; no signal to its group.

    def test_TERM_timeout_retains_final_members_and_cleanup_failure(self):
        runner = self.runner_module()
        proc = self.dummy_leader()
        identity = next(row for row in runner.members(proc.pid) if row['pid']==proc.pid)
        with mock.patch.object(proc, 'wait', side_effect=subprocess.TimeoutExpired('harmless-dummy', 20)):
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.stop_group(proc, identity, self.root)
        self.assertTrue((self.root/'cleanup.after.json').is_file())
        self.assertTrue((self.root/'cleanup.failure').is_file())
        self.assertEqual((self.root/'cleanup.exit').read_text(), '1\n')
        self.assertEqual(json.loads((self.root/'cleanup.signal.json').read_text())['pgid'], proc.pid)

    def expected_log(self, scenario, raw=False):
        result = simulated_result(scenario)
        return (b'RAW\xff\x00\r\n' if raw else b'') + b'CAPACITY_RESULT ' + json.dumps(result).encode() + b'\n'

    def test_raw_binary_logs_and_CAPACITY_RESULT_bytes_are_unchanged(self):
        result = self.run_runner(FAKE_RAW='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in ['01-A', '02-B', '03-B', '04-A']:
            block = self.output / name
            expected = b''
            for scenario in ['p1-4500', 'plan-6000']:
                data = self.expected_log(scenario, raw=True)
                self.assertEqual((block/(scenario+'.log')).read_bytes(), data)
                expected += data
            self.assertEqual((block/'capacity.log').read_bytes(), expected)
            self.assertEqual((block/'renderer.input').read_bytes(), expected)

    def test_original_capacity_exits_and_renderer_exit_are_independent(self):
        result = self.run_runner(FAKE_FIRST_EXIT='7', FAKE_SECOND_EXIT='9', FAKE_REPORT_EXIT='11')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(self.calls.read_text().splitlines(), ['p1-4500', 'plan-6000']*4)
        pids = []
        for name in ['01-A', '02-B', '03-B', '04-A']:
            block = self.output / name
            for scenario, code in [('p1-4500', 7), ('plan-6000', 9)]:
                self.assertEqual((block/(scenario+'.log.exit')).read_text(), str(code)+'\n')
                self.assertEqual((block/(scenario+'.log.tee.exit')).read_text(), '0\n')
                self.assertEqual((block/(scenario+'.log')).read_bytes(), self.expected_log(scenario))
                pids.append((block/(scenario+'.log.pid')).read_text())
            self.assertEqual((block/'renderer.log.exit').read_text(), '11\n')
            self.assertEqual((block/'renderer.input').read_bytes(), (block/'capacity.log').read_bytes())
        self.assertEqual(len(set(pids)), 8)

    def reject_measurement_case(self, defect):
        self.output = self.root / defect
        self.calls = self.root / (defect + ".calls")
        self.env.update(E1_OUTPUT=str(self.output), CALLS=str(self.calls))
        result = self.run_runner(FAKE_MEASUREMENT_DEFECT=defect)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(self.calls.read_text().splitlines()), 8)
        for name in ("01-A", "02-B", "03-B", "04-A"):
            block = self.output / name
            self.assertTrue((block / "evidence.failure").is_file())
            self.assertEqual((block / "capacity.log").read_bytes(),
                             b"".join((block / (scenario + ".log")).read_bytes() for scenario in ("p1-4500", "plan-6000")))

    def test_missing_SQL_or_API_measurement_rejected_after_eight_calls(self):
        for defect in ("sql-missing", "api-missing"):
            with self.subTest(defect=defect):
                self.reject_measurement_case(defect)

    def test_duplicate_or_extra_SQL_or_API_measurement_rejected_after_eight_calls(self):
        for defect in ("sql-duplicate", "api-duplicate", "sql-extra", "api-extra"):
            with self.subTest(defect=defect):
                self.reject_measurement_case(defect)

    def test_partial_SQL_or_API_samples_rejected_after_eight_calls(self):
        for defect in ("sql-partial", "api-partial", "sql-field-missing", "api-null"):
            with self.subTest(defect=defect):
                self.reject_measurement_case(defect)

    def test_missing_result_fails_closed_after_all_eight_calls(self):
        result = self.run_runner(FAKE_OMIT='p1-4500')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(self.calls.read_text().splitlines()), 8)
        for name in ['01-A', '02-B', '03-B', '04-A']:
            self.assertIn('Missing/duplicate', (self.output/name/'infrastructure.failure').read_text())

    def test_duplicate_result_fails_closed_after_all_eight_calls(self):
        result = self.run_runner(FAKE_DUPLICATE='plan-6000')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(self.calls.read_text().splitlines()), 8)

    def test_startup_failure_is_separate_and_has_no_capacity_successes(self):
        result = self.run_runner(FAKE_START_EXIT='23')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(self.calls.exists())
        for name in ['01-A', '02-B', '03-B', '04-A']:
            block = self.output / name
            self.assertEqual((block/'startup.exit').read_text(), '23\n')
            self.assertTrue((block/'startup.failure').is_file())
            self.assertFalse((block/'smoke.log.exit').exists())
            self.assertFalse((block/'p1-4500.log.exit').exists())
            self.assertEqual((block/'cleanup.exit').read_text(), '0\n')

    def test_smoke_failure_is_separate_and_has_no_capacity_successes(self):
        result = self.run_runner(FAKE_SMOKE_EXIT='19')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(self.calls.exists())
        for name in ['01-A', '02-B', '03-B', '04-A']:
            block = self.output / name
            self.assertEqual((block/'startup.exit').read_text(), '0\n')
            self.assertEqual((block/'smoke.log.exit').read_text(), '19\n')
            self.assertTrue((block/'smoke.failure').is_file())
            self.assertFalse((block/'p1-4500.log.exit').exists())
            self.assertEqual((block/'cleanup.exit').read_text(), '0\n')

    def test_generated_public_fixture_is_retained_then_tracked_bytes_restored(self):
        fixture = self.repo/'.github/fixtures/.dev.vars'
        fixture.write_bytes(b'original public fixture\n')
        starter = self.repo/'.github/scripts/start-android-api.sh'
        self.put(str(starter.relative_to(self.repo)), starter.read_text().replace('exit ${FAKE_START_EXIT:-0}\n', '')+"printf 'generated public fixture\\n' > .github/fixtures/.dev.vars\n")
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(fixture.read_bytes(), b'original public fixture\n')
        for name in ['01-A', '02-B', '03-B', '04-A']:
            block = self.output / name
            self.assertEqual((block/'public-ephemeral.dev.vars').read_bytes(), b'generated public fixture\n')
            self.assertEqual(json.loads((block/'source.before.json').read_text()), json.loads((block/'source.after.json').read_text()))


if __name__ == "__main__":
    unittest.main()
