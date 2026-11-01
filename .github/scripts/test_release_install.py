"""Exercise the real release-install gate with a synthetic adb, without an SDK."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('release-install.sh').resolve()
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os
from pathlib import Path
import sys
args = sys.argv[1:]
with Path(os.environ['DINING_FAKE_ADB_EVENTS']).open('a') as events:
    events.write(json.dumps(args) + '\n')
scenario = set(os.environ.get('DINING_FAKE_ADB_SCENARIO', '').split(','))
if args == ['shell', 'getprop', 'ro.build.version.sdk']:
    print(os.environ['DINING_FAKE_ADB_API'])
elif args == ['logcat', '-c']:
    print("failed to clear the 'main' log", file=sys.stderr)
    sys.exit(1)
elif args == ['logcat', '-d', '-s', 'AndroidRuntime:E']:
    if 'log-failure' in scenario:
        sys.exit(1)
    if 'fatal' in scenario or 'missing-pid' in scenario:
        print('E/AndroidRuntime: FATAL EXCEPTION: main')
elif args and args[0] == 'install':
    assert Path(args[-1]).is_file()
    if 'install-failure' in scenario or ('reinstall-failure' in scenario and '-r' in args):
        print('Failure [INSTALL_FAILED_TEST_FIXTURE]', file=sys.stderr)
        sys.exit(1)
    print('Success')
elif args == ['shell', 'am', 'start', '-W', '-n',
              'top.alanvampuke.dininglog/top.alanvampuke.dininglog.MainActivity']:
    if 'start-failure' in scenario:
        sys.exit(1)
    print('Status: timeout' if 'bad-status' in scenario else 'Status: ok')
elif args == ['shell', 'pidof', 'top.alanvampuke.dininglog']:
    if 'missing-pid' not in scenario:
        print('12345')
else:
    print('Unexpected adb command in release gate fixture', file=sys.stderr)
    sys.exit(9)
'''


class ReleaseInstallTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='dining-release-install-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.temp = self.root / 'runner-temp'
        self.temp.mkdir()
        self.apk = self.root / 'android/app/build/outputs/apk/release/app-release.apk'
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b'Synthetic gate fixture; not an installable APK')
        self.events = self.root / 'adb-events.jsonl'
        adb = self.bin / 'adb'
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        # Keep the real script's three-second stabilization step; synthetic
        # devices have no clock or process to stabilize, so only stub sleep.
        sleep = self.bin / 'sleep'
        sleep.write_text('#!/usr/bin/env python3\nimport sys\nassert sys.argv[1:] == ["3"]\n')
        sleep.chmod(0o700)

    def run_gate(self, scenario='', api=26, previous=False, github_actions='true'):
        self.events.unlink(missing_ok=True)
        for output in self.temp.iterdir():
            output.unlink()
        if previous:
            (self.temp / 'dining-previous-release.apk').write_bytes(b'Prior signed APK fixture')
        environment = dict(os.environ, GITHUB_ACTIONS=github_actions,
                           GITHUB_WORKSPACE=str(self.root), RUNNER_TEMP=str(self.temp),
                           DINING_FAKE_ADB_EVENTS=str(self.events),
                           DINING_FAKE_ADB_API=str(api), DINING_FAKE_ADB_SCENARIO=scenario)
        environment['PATH'] = str(self.bin) + os.pathsep + environment['PATH']
        result = subprocess.run(['bash', str(SCRIPT)], env=environment, text=True,
                                capture_output=True, timeout=10)
        calls = [json.loads(line) for line in self.events.read_text().splitlines()] if self.events.exists() else []
        return result, calls

    def assert_failed(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('signed APK installed, launched and reinstalled', result.stdout)

    def test_api26_installs_without_permission_to_clear_logs(self):
        result, calls = self.run_gate(api=26)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('signed APK installed, launched and reinstalled on API 26', result.stdout)
        self.assertNotIn(['logcat', '-c'], calls)
        self.assertEqual([call for call in calls if call[0] == 'install'],
                         [['install', str(self.apk)], ['install', '-r', str(self.apk)]])
        self.assertTrue((self.temp / 'dining-release-launch-api-26.txt').is_file())
        self.assertTrue((self.temp / 'dining-release-runtime-api-26.txt').is_file())

    def test_api35_installs_and_launches(self):
        result, calls = self.run_gate(api=35)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('on API 35', result.stdout)
        self.assertIn(['shell', 'pidof', 'top.alanvampuke.dininglog'], calls)
        self.assertIn(['logcat', '-d', '-s', 'AndroidRuntime:E'], calls)

    def test_previous_release_is_installed_before_upgrade_and_reinstall(self):
        result, calls = self.run_gate(previous=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual([call for call in calls if call[0] == 'install'],
                         [['install', str(self.temp / 'dining-previous-release.apk')],
                          ['install', '-r', str(self.apk)], ['install', '-r', str(self.apk)]])

    def test_failed_install_stops_before_start_and_preserves_diagnostics(self):
        result, calls = self.run_gate('install-failure')
        self.assert_failed(result)
        self.assertFalse(any(call[:3] == ['shell', 'am', 'start'] for call in calls))
        self.assertEqual(len([call for call in calls if call[0] == 'install']), 1)
        self.assertTrue((self.temp / 'dining-release-runtime-api-26.txt').is_file())

    def test_start_failure_and_wrong_launch_status_both_stop(self):
        for scenario in ['start-failure', 'bad-status']:
            with self.subTest(scenario=scenario):
                result, calls = self.run_gate(scenario)
                self.assert_failed(result)
                self.assertEqual(len([call for call in calls if call[0] == 'install']), 1)
                self.assertTrue((self.temp / 'dining-release-runtime-api-26.txt').is_file())

    def test_missing_process_stops_and_records_crash_before_log_gate(self):
        result, calls = self.run_gate('missing-pid')
        self.assert_failed(result)
        self.assertEqual(len([call for call in calls if call[0] == 'install']), 1)
        self.assertIn('FATAL EXCEPTION', (self.temp / 'dining-release-runtime-api-26.txt').read_text())

    def test_fatal_exception_stops_before_reinstall(self):
        result, calls = self.run_gate('fatal')
        self.assert_failed(result)
        self.assertEqual(len([call for call in calls if call[0] == 'install']), 1)
        self.assertIn('FATAL EXCEPTION', result.stdout)

    def test_runtime_read_failure_cannot_pass(self):
        result, calls = self.run_gate('log-failure')
        self.assert_failed(result)
        self.assertEqual(len([call for call in calls if call[0] == 'install']), 1)

    def test_failed_diagnostic_collection_preserves_install_failure(self):
        result, _ = self.run_gate('install-failure,log-failure')
        self.assert_failed(result)
        self.assertIn('Runtime diagnostics unavailable', result.stderr)

    def test_reinstall_failure_cannot_pass(self):
        result, calls = self.run_gate('reinstall-failure')
        self.assert_failed(result)
        self.assertEqual(len([call for call in calls if call[0] == 'install']), 2)

    def test_unsupported_sdk_and_non_actions_environment_are_rejected(self):
        result, calls = self.run_gate(api=27)
        self.assert_failed(result)
        self.assertEqual(calls, [['shell', 'getprop', 'ro.build.version.sdk']])
        result, calls = self.run_gate(github_actions='false')
        self.assert_failed(result)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
