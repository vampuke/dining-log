"""Linux control-flow simulation only; no real capacity data or dependencies."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

RUNNER = Path(__file__).with_name("capacity-smoke-abba.py").resolve()
EVIDENCE = Path(os.environ.get("E1_TEST_EVIDENCE") or tempfile.mkdtemp(prefix="e1-unittest-evidence-"))
EVIDENCE.mkdir(parents=True, exist_ok=True)


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
        self.put("backend/node_modules/.bin/vitest", """#!/usr/bin/env python3
import json, os, signal, sys, time
from pathlib import Path
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
 payload='CAPACITY_RESULT '+json.dumps({'scenario':{'name':sys.argv[-1]},'seed':{'fixtureSha256':('a' if sys.argv[-1]=='p1-4500' else 'b')*64},'simulation_only':True})
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
        result = {'scenario': {'name': scenario}, 'seed': {'fixtureSha256': ('a' if scenario=='p1-4500' else 'b')*64}, 'simulation_only': True}
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
