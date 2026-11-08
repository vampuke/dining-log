"""Exercise APK-only release boundaries without credentials or installed packages."""
from pathlib import Path
import json
import os
import re
import subprocess
import tempfile
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/release.yml'
SCRIPT = ROOT / '.github/scripts/verify-apk-only-release.py'
MODE_GATE = 'Validate explicit production mode before executing candidate scripts'
EVIDENCE_NAME = 'dining-apk-only-source-check.json'
ANNOUNCEMENT_FIELDS = ('APP_VERSION_CODE', 'APP_VERSION_NAME', 'APP_DOWNLOAD_URL',
                       'APP_APK_SHA256', 'APP_RELEASE_NOTES', 'APP_RELEASED_AT')
GIT_ENV = {'PATH': os.environ['PATH'], 'LANG': 'C.UTF-8',
           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
           'GIT_TERMINAL_PROMPT': '0'}
FIXTURE_TOML = '''name = "dining-release-fixture"
main = "src/index.ts"
compatibility_date = "2026-09-25"
workers_dev = false

[limits]
cpu_ms = 10

[[d1_databases]]
binding = "DB"
database_name = "fixture-only"
database_id = "00000000-0000-0000-0000-000000000001"

[vars]
APP_ENV = "production"
PASSWORD_ITERATIONS = "10000"
SESSION_MAX_AGE_SECONDS = "15552000"
APP_VERSION_CODE = "10"
APP_VERSION_NAME = "0.8.2"
APP_DOWNLOAD_URL = "https://example.invalid/releases/v0.8.2/app-release.apk"
APP_APK_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
APP_RELEASE_NOTES = "已发布版本的测试公告。"
APP_RELEASED_AT = "1793857524"

[env.staging.vars]
APP_VERSION_NAME = "do-not-ignore-nested-vars"
'''


def workflow_steps(source):
    """Read this workflow's step properties; reject unfamiliar if expressions."""
    starts = list(re.finditer(r'^      - ', source, re.M))
    result = []
    for index, start in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(source)
        block = source[start.start():end]
        condition = re.search(r'^        if: (.+)$', block, re.M)
        run = re.search(r'^        run: (.+)$', block, re.M)
        command = ''
        if run:
            if run[1] == '|':
                body = re.match(r'(?:          .*\n)+', block[run.end() + 1:])
                if body:
                    command = re.sub(r'^ {10}', '', body[0], flags=re.M)
            else:
                command = run[1]
        result.append({'block': block, 'run': command,
                       'if': condition[1] if condition else None})
    return result


def step_runs(step, mode):
    condition = step['if']
    if condition is None:
        return True  # GitHub's default success() after preceding gates passed.
    match = re.fullmatch(r"env\.PRODUCTION_MODE == '(apk-only|full)'", condition)
    if not match:
        raise AssertionError(f'Unsupported release-mode condition: {condition}')
    # Actions string equality is case insensitive; the trusted mode gate must
    # independently reject uppercase and other malformed dispatch input.
    return mode.casefold() == match[1].casefold()


def production_steps(steps):
    return [step for step in steps
            if 'CLOUDFLARE_API_TOKEN:' in step['block']
            or 'production-d1.mjs' in step['run']
            or 'smoke:production' in step['run']
            or any('wrangler deploy' in line and '--dry-run' not in line
                   for line in step['run'].splitlines())
            or ('wrangler d1' in step['run'] and '--remote' in step['run'])]


def assert_full_only_production(source):
    steps = production_steps(workflow_steps(source))
    expected = (
        'node .github/scripts/production-d1.mjs checkpoint',
        'node .github/scripts/production-d1.mjs pre-migration',
        'node .github/scripts/production-d1.mjs domain',
        'npx --no-install wrangler d1 migrations apply DB --remote',
        'node .github/scripts/production-d1.mjs verify',
        'npx --no-install wrangler deploy',
        'npm run smoke:production',
    )
    if len(steps) != len(expected):
        raise AssertionError('The seven existing production steps must be retained')
    for step, command in zip(steps, expected):
        if command not in step['run']:
            raise AssertionError(f'Existing full-mode command was changed: {command}')
        if step_runs(step, 'apk-only'):
            raise AssertionError(f'APK-only can touch production: {command}')
        if not step_runs(step, 'full'):
            raise AssertionError(f'Full mode lost an existing production gate: {command}')


def assert_source_guard_boundary(source):
    steps = workflow_steps(source)
    guards = [step for step in steps if step['run'] == 'python3 -B .github/scripts/verify-apk-only-release.py']
    if len(guards) != 1:
        raise AssertionError('APK-only source guard invocation is missing or duplicated')
    guard = guards[0]
    if not step_runs(guard, 'apk-only') or step_runs(guard, 'full'):
        raise AssertionError('Source guard must run exactly for APK-only mode')
    selection = next(index for index, step in enumerate(steps)
                     if step['run'] == 'node .github/scripts/validate-release.mjs')
    if not selection < steps.index(guard) < min(steps.index(step) for step in production_steps(steps)):
        raise AssertionError('Source guard must follow selection and precede every production step')
    artifacts = [step for step in steps if '          name: release-evidence\n' in step['block']]
    if len(artifacts) != 1 or '${{ runner.temp }}/' + EVIDENCE_NAME not in artifacts[0]['block']:
        raise AssertionError('Source guard evidence must be retained in release-evidence')


class WorkflowBoundaryTest(unittest.TestCase):
    def test_all_production_touches_require_full_mode(self):
        assert_full_only_production(WORKFLOW.read_text())

    def test_removing_any_production_condition_is_detected(self):
        source = WORKFLOW.read_text()
        for step in production_steps(workflow_steps(source)):
            with self.subTest(command=step['run']):
                changed = step['block'].replace("        if: env.PRODUCTION_MODE == 'full'\n", '', 1)
                self.assertNotEqual(changed, step['block'])
                with self.assertRaisesRegex(AssertionError, 'APK-only can touch production'):
                    assert_full_only_production(source.replace(step['block'], changed, 1))

    def test_dispatch_requires_an_explicit_safe_choice_without_env_fallback(self):
        source = WORKFLOW.read_text()
        choice = re.search(r'^      production_mode:\n((?: {8,}.*\n)+)', source, re.M)
        self.assertIsNotNone(choice, 'production_mode dispatch choice is missing')
        for declaration in ('required: true', 'type: choice', 'default: apk-only'):
            self.assertRegex(choice[1], re.compile(r'^        ' + re.escape(declaration) + r'$', re.M), declaration)
        self.assertEqual(re.findall(r'^          - (.+)$', choice[1], re.M), ['apk-only', 'full'])
        self.assertIn('      PRODUCTION_MODE: ${{ inputs.production_mode }}\n', source)

    def test_trusted_mode_gate_rejects_empty_unknown_and_malformed_modes(self):
        steps = workflow_steps(WORKFLOW.read_text())
        gates = [step for step in steps if f'- name: {MODE_GATE}\n' in step['block']]
        self.assertEqual(len(gates), 1, 'A trusted inline production mode gate is required')
        gate = gates[0]
        self.assertIsNone(gate['if'], 'Invalid modes must run the rejecting gate')
        self.assertIn('        shell: bash\n', gate['block'])
        self.assertLess(steps.index(gate), next(index for index, step in enumerate(steps)
                                             if 'node .github/scripts/validate-release.mjs' == step['run']))
        cases = ('apk-only', 'full', None, '', 'unknown', 'FULL', 'APK-ONLY',
                 ' apk-only', 'full ', 'apk-only\n', 'full\napk-only',
                 'apk-only; touch escaped', '${{ inputs.production_mode }}', '{}')
        with tempfile.TemporaryDirectory(prefix='dining-mode-gate-') as directory:
            sentinel = Path(directory) / 'candidate-executed'
            for mode in cases:
                with self.subTest(mode=mode):
                    sentinel.unlink(missing_ok=True)
                    environment = {'PATH': os.environ['PATH'], 'LANG': 'C.UTF-8',
                                   'DINING_MODE_SENTINEL': str(sentinel)}
                    if mode is not None:
                        environment['PRODUCTION_MODE'] = mode
                    result = subprocess.run(['bash', '-c', gate['run'] +
                                             '\nprintf accepted > "$DINING_MODE_SENTINEL"\n'],
                                            env=environment, capture_output=True, text=True, timeout=5)
                    accepted = mode in ('apk-only', 'full')
                    self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
                    self.assertEqual(sentinel.exists(), accepted)

    def test_source_guard_follows_selection_and_precedes_production(self):
        assert_source_guard_boundary(WORKFLOW.read_text())

    def test_removing_the_source_guard_is_detected(self):
        source = WORKFLOW.read_text()
        guard = next(step for step in workflow_steps(source)
                     if step['run'] == 'python3 -B .github/scripts/verify-apk-only-release.py')
        with self.assertRaisesRegex(AssertionError, 'source guard invocation is missing'):
            assert_source_guard_boundary(source.replace(guard['block'], '', 1))

    def test_both_modes_retain_offline_backend_and_signed_apk_health_gates(self):
        steps = workflow_steps(WORKFLOW.read_text())
        commands = ('npm ci', 'node --test ../.github/scripts/release-guards.test.mjs',
                    "python3 -B -m unittest discover -s ../.github/scripts -p 'test_*.py'",
                    'npm run check', 'node tests/contracts.mjs', 'npm test', 'npm run db:verify',
                    'npx --no-install wrangler deploy --dry-run',
                    './gradlew :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --no-daemon',
                    'python3 .github/scripts/verify-release-apk.py',
                    'sha256sum --check app-release.apk.sha256')
        required = []
        for command in commands:
            matches = [step for step in steps if command in step['run']]
            self.assertEqual(len(matches), 1, command)
            required.append(matches[0])
        for api in (26, 35):
            install = [step for step in steps if f'          api-level: {api}\n' in step['block']]
            self.assertEqual(len(install), 1)
            self.assertIn('          script: bash .github/scripts/release-install.sh\n', install[0]['block'])
            required.append(install[0])
        health = [step for step in steps if 'https://dining.alanvampuke.top/api/v1/health' in step['run']]
        self.assertEqual(len(health), 1)
        self.assertIn('test "$status" = 200', health[0]['run'])
        self.assertIn("assert body == {'status': 'ok', 'api_version': 'v1'}", health[0]['run'])
        self.assertNotRegex(health[0]['run'], r'--(?:request|data)|\s-X\s|CLOUDFLARE_API_TOKEN')
        required.append(health[0])
        publish = next(step for step in steps if 'gh release create "$RELEASE_TAG"' in step['run'])
        for step in required:
            self.assertIsNone(step['if'], step['block'].splitlines()[0])
            self.assertLess(steps.index(step), steps.index(publish))
        guard = next(step for step in steps if step['run'] == 'python3 -B .github/scripts/verify-apk-only-release.py')
        self.assertNotIn('CLOUDFLARE_API_TOKEN', guard['block'])
        backend = next(step for step in steps if 'npm run db:verify' in step['run'])
        self.assertLess(steps.index(guard), steps.index(backend))

    def test_ci_discovers_the_new_tests_using_the_existing_pattern(self):
        self.assertIn("python3 -B -m unittest discover -s ../.github/scripts -p 'test_*.py'",
                      (ROOT / '.github/workflows/ci.yml').read_text())

    def test_publish_notes_describe_the_actual_mode_without_a_false_smoke_claim(self):
        steps = workflow_steps(WORKFLOW.read_text())
        publish = [step for step in steps if 'gh release create "$RELEASE_TAG"' in step['run']]
        self.assertEqual(len(publish), 1)
        self.assertIsNone(publish[0]['if'])
        self.assertNotIn('only after production smoke passes', publish[0]['block'])
        prefix = publish[0]['run'].split('node .github/scripts/validate-release.mjs publish')[0]
        self.assertIn('Release mode:', prefix)
        self.assertNotRegex(prefix, r'\b(?:node|gh|npm|npx|curl|gradlew)\b')
        with tempfile.TemporaryDirectory(prefix='dining-release-notes-') as directory:
            notes = Path(directory) / 'dining-release-notes.md'
            for mode in ('apk-only', 'full', 'unknown', ''):
                with self.subTest(mode=mode):
                    notes.write_text('Reviewed public changelog.\n')
                    result = subprocess.run(['bash', '-c', prefix], capture_output=True, text=True,
                                            env={'PATH': os.environ['PATH'], 'RUNNER_TEMP': directory,
                                                 'PRODUCTION_MODE': mode}, timeout=5)
                    accepted = mode in ('apk-only', 'full')
                    self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
                    if accepted:
                        self.assertIn('Release mode: ' + mode, notes.read_text())
                    if mode == 'apk-only':
                        self.assertIn('Production deployment, migrations and CRUD smoke were skipped.', notes.read_text())
                        self.assertNotIn('CRUD smoke gates passed', notes.read_text())
                    elif mode == 'full':
                        self.assertIn('CRUD smoke gates passed', notes.read_text())
                    else:
                        self.assertEqual(notes.read_text(), 'Reviewed public changelog.\n')


class ApkOnlySourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='dining-apk-only-git-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / 'checkout'
        self.root.mkdir()
        self.temp = Path(self.directory.name) / 'runner-temp'
        self.temp.mkdir()
        self.git('init', '--quiet', '--initial-branch=master')
        self.git('config', 'user.name', 'APK-only release fixture')
        self.git('config', 'user.email', 'release-fixture@example.invalid')
        self.git('config', 'core.filemode', 'true')
        self.toml = self.root / 'backend/wrangler.toml'
        self.toml.parent.mkdir()
        self.toml.write_text(FIXTURE_TOML)
        files = {'backend/src/index.ts': 'export default { fetch() { return new Response("fixture"); } };\n',
                 'backend/package.json': '{"name":"fixture-only","dependencies":{}}\n',
                 'backend/package-lock.json': '{"name":"fixture-only","lockfileVersion":3}\n',
                 'backend/migrations/0001_initial.sql': 'CREATE TABLE fixture (id INTEGER PRIMARY KEY);\n'}
        for relative, content in files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        self.previous_commit = self.commit()
        self.fixture_base = self.previous_commit
        self.previous_tag = 'v0.8.2'
        self.fixture_tag_index = 0
        self.git('tag', 'v0.8.2')
        (self.root / 'README.md').write_text('Candidate Android/release changes outside backend.\n')
        self.head = self.commit()

    def git(self, *arguments, input=None):
        return subprocess.check_output(['git', *arguments], cwd=self.root, env=GIT_ENV,
                                       input=input, stderr=subprocess.PIPE, text=True).strip()

    def commit(self):
        self.git('add', '--all')
        self.git('commit', '--quiet', '-m', 'Synthetic release source fixture')
        return self.git('rev-parse', 'HEAD')

    def selection(self):
        return {'commit': self.head, 'tag': 'v0.8.3', 'versionCode': 11, 'versionName': '0.8.3',
                'candidate_ci': 'https://example.invalid/ci/fixture',
                'previous': {'tag': self.previous_tag, 'url': 'https://example.invalid/releases/fixture'}}

    def set_both_sources_toml(self, content):
        """Commit invalid/changed fixtures at a real tag and a separate HEAD."""
        self.git('checkout', '--quiet', '--detach', self.fixture_base)
        self.toml.write_text(content)
        self.previous_commit = self.commit()
        self.fixture_tag_index += 1
        self.previous_tag = f'v0.7.{self.fixture_tag_index}'
        self.git('tag', self.previous_tag)
        (self.root / 'README.md').write_text('Separate candidate with the same backend tree.\n')
        self.head = self.commit()

    def candidate_toml(self, content):
        self.git('checkout', '--quiet', '--detach', self.fixture_base)
        self.previous_tag = 'v0.8.2'
        self.previous_commit = self.fixture_base
        self.toml.write_text(content)
        self.head = self.commit()

    def assert_rejected(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((self.temp / EVIDENCE_NAME).read_text())['status'], 'failed')

    def run_guard(self, selection=None, environment=None, raw_selection=None, write_selection=True):
        self.assertTrue(SCRIPT.is_file(), 'The std-library APK-only source guard is missing')
        selection_text = json.dumps(self.selection() if selection is None else selection)
        selection_path = self.temp / 'dining-release-selection.json'
        if write_selection:
            selection_path.write_text(selection_text if raw_selection is None else raw_selection)
        else:
            selection_path.unlink(missing_ok=True)
        (self.temp / EVIDENCE_NAME).unlink(missing_ok=True)
        env = dict(GIT_ENV, GITHUB_WORKSPACE=str(self.root), RUNNER_TEMP=str(self.temp),
                   RELEASE_COMMIT=self.head, RELEASE_TAG='v0.8.3', PRODUCTION_MODE='apk-only')
        if environment:
            for key, value in environment.items():
                if value is None:
                    env.pop(key, None)
                else:
                    env[key] = value
        return subprocess.run(['python3', '-B', str(SCRIPT)], cwd=self.root, env=env,
                              capture_output=True, text=True, timeout=10)

    def test_unchanged_backend_passes_with_exact_tag_commit_evidence(self):
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        evidence = json.loads((self.temp / EVIDENCE_NAME).read_text())
        self.assertEqual(evidence['status'], 'passed')
        self.assertEqual(evidence['production_mode'], 'apk-only')
        self.assertEqual(evidence['previous']['tag'], 'v0.8.2')
        self.assertEqual(evidence['previous']['commit'], self.previous_commit)
        self.assertEqual(evidence['candidate']['commit'], self.head)
        self.assertEqual(evidence['changed_paths'], [])
        self.assertEqual(evidence['previous']['backend_tree'], self.git('rev-parse', 'v0.8.2:backend'))
        self.assertEqual(evidence['candidate']['backend_tree'], self.git('rev-parse', 'HEAD:backend'))

    def test_only_the_six_existing_announcement_values_may_change(self):
        values = {'APP_VERSION_CODE': '11', 'APP_VERSION_NAME': '0.8.3',
                  'APP_DOWNLOAD_URL': 'https://example.invalid/releases/v0.8.3/app-release.apk',
                  'APP_APK_SHA256': 'b' * 64, 'APP_RELEASE_NOTES': '新公告。\n第二行。',
                  'APP_RELEASED_AT': '1794067200'}
        content = FIXTURE_TOML
        for key, value in values.items():
            content = re.sub(r'^' + key + r' = .*$',
                             lambda _: key + ' = ' + json.dumps(value, ensure_ascii=False),
                             content, count=1, flags=re.M)
        self.assertEqual({key: tomllib.loads(content)['vars'][key] for key in values}, values)
        self.toml.write_text(content)
        self.head = self.commit()
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        evidence = json.loads((self.temp / EVIDENCE_NAME).read_text())
        self.assertEqual(evidence['changed_paths'], ['backend/wrangler.toml'])
        self.assertEqual(evidence['allowed_announcement_fields'], sorted(ANNOUNCEMENT_FIELDS))
        self.assertEqual(evidence['changed_announcement_fields'], sorted(ANNOUNCEMENT_FIELDS))
        self.assertRegex(evidence['non_announcement_config_sha256'], r'^[0-9a-f]{64}$')

    def test_every_announcement_field_is_required_even_if_missing_in_both_sources(self):
        for key in ANNOUNCEMENT_FIELDS:
            with self.subTest(field=key):
                content = re.sub(r'^' + key + r' = .*\n', '', FIXTURE_TOML, count=1, flags=re.M)
                self.assertNotIn(key, tomllib.loads(content)['vars'])
                self.set_both_sources_toml(content)
                result = self.run_guard()
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(json.loads((self.temp / EVIDENCE_NAME).read_text())['status'], 'failed')

    def test_ambiguous_or_non_json_selection_records_are_rejected(self):
        valid = json.dumps(self.selection())
        records = (valid[:-1] + ', "commit": ' + json.dumps(self.head) + '}',
                   valid[:-1] + ', "unexpected": NaN}', valid[:-1], 'null', '[]')
        for raw in records:
            with self.subTest(selection=raw):
                result = self.run_guard(raw_selection=raw)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_toml_is_compared_semantically_instead_of_by_text_slices(self):
        content = FIXTURE_TOML.replace('[vars]', '["vars"]').replace(
            'cpu_ms = 10', 'cpu_ms = 1_0 # same TOML integer').replace(
            'main = "src/index.ts"', 'main="src/index.ts"').replace(
            'APP_VERSION_CODE = "10"\nAPP_VERSION_NAME = "0.8.2"',
            'APP_VERSION_NAME = "0.8.2"\nAPP_VERSION_CODE = "10"')
        content = '# Reordered announcement lines do not affect TOML semantics.\n' + content
        self.assertEqual(tomllib.loads(content), tomllib.loads(FIXTURE_TOML))
        self.candidate_toml(content)
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_non_target_vars_cannot_be_edited_added_or_removed(self):
        variants = (
            FIXTURE_TOML.replace('APP_ENV = "production"', 'APP_ENV = "preview"'),
            FIXTURE_TOML.replace('PASSWORD_ITERATIONS = "10000"', 'PASSWORD_ITERATIONS = "10001"'),
            FIXTURE_TOML.replace('SESSION_MAX_AGE_SECONDS = "15552000"', 'SESSION_MAX_AGE_SECONDS = "15551999"'),
            FIXTURE_TOML.replace('[vars]', '[vars]\nEXTRA_BINDING = "fixture-only"'),
            FIXTURE_TOML.replace('APP_ENV = "production"\n', ''),
        )
        for content in variants:
            with self.subTest(config=content):
                tomllib.loads(content)
                self.candidate_toml(content)
                self.assert_rejected(self.run_guard())

    def test_db_bindings_limits_compatibility_and_nested_vars_cannot_change(self):
        variants = (
            FIXTURE_TOML.replace('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002'),
            FIXTURE_TOML.replace('binding = "DB"', 'binding = "OTHER_DB"'),
            FIXTURE_TOML.replace('database_name = "fixture-only"', 'database_name = "other-fixture"'),
            FIXTURE_TOML.replace('compatibility_date = "2026-09-25"', 'compatibility_date = "2026-09-26"'),
            FIXTURE_TOML.replace('cpu_ms = 10', 'cpu_ms = 11'),
            FIXTURE_TOML.replace('cpu_ms = 10', 'cpu_ms = 10.0'),
            FIXTURE_TOML.replace('workers_dev = false', 'workers_dev = 0'),
            FIXTURE_TOML.replace('workers_dev = false', 'workers_dev = true'),
            FIXTURE_TOML.replace('main = "src/index.ts"', 'main = "src/other.ts"'),
            FIXTURE_TOML.replace('"do-not-ignore-nested-vars"', '"0.8.3"'),
            FIXTURE_TOML + '\n[observability]\nenabled = true\n',
        )
        for content in variants:
            with self.subTest(config=content):
                tomllib.loads(content)
                self.candidate_toml(content)
                self.assert_rejected(self.run_guard())

    def test_module_dependencies_lockfile_and_migration_content_cannot_change(self):
        changes = {
            'backend/src/index.ts': 'export default { fetch() { return new Response("changed"); } };\n',
            'backend/package.json': '{"name":"fixture-only","dependencies":{"fixture-dependency":"1.0.0"}}\n',
            'backend/package-lock.json': '{"name":"fixture-only","lockfileVersion":3,"packages":{"node_modules/fixture-dependency":{"version":"1.0.0"}}}\n',
            'backend/migrations/0001_initial.sql': 'CREATE TABLE fixture (id TEXT PRIMARY KEY);\n',
        }
        for relative, content in changes.items():
            with self.subTest(path=relative):
                self.git('checkout', '--quiet', '--detach', self.fixture_base)
                path = self.root / relative
                self.assertNotEqual(path.read_text(), content)
                path.write_text(content)
                self.head = self.commit()
                self.assert_rejected(self.run_guard())

    def test_files_cannot_be_added_deleted_or_renamed(self):
        for operation in ('new-module', 'new-migration', 'delete', 'rename'):
            with self.subTest(operation=operation):
                self.git('checkout', '--quiet', '--detach', self.fixture_base)
                module = self.root / 'backend/src/index.ts'
                if operation == 'new-module':
                    (module.parent / 'new.ts').write_text('export const fixture = true;\n')
                elif operation == 'new-migration':
                    (self.root / 'backend/migrations/0002_fixture.sql').write_text('SELECT 1;\n')
                elif operation == 'delete':
                    module.unlink()
                else:
                    module.rename(module.with_name('renamed.ts'))
                self.head = self.commit()
                self.assert_rejected(self.run_guard())

    def test_file_modes_including_wrangler_mode_cannot_change(self):
        for relative in ('backend/src/index.ts', 'backend/wrangler.toml'):
            with self.subTest(path=relative):
                self.git('checkout', '--quiet', '--detach', self.fixture_base)
                (self.root / relative).chmod(0o755)
                self.head = self.commit()
                self.assertTrue(self.git('ls-tree', 'HEAD', '--', relative).startswith('100755 blob '))
                self.assert_rejected(self.run_guard())

    def test_symlinks_cannot_replace_regular_backend_files(self):
        for relative in ('backend/src/index.ts', 'backend/wrangler.toml'):
            with self.subTest(path=relative):
                self.git('checkout', '--quiet', '--detach', self.fixture_base)
                path = self.root / relative
                path.unlink()
                path.symlink_to('package.json')
                self.head = self.commit()
                self.assertTrue(self.git('ls-tree', 'HEAD', '--', relative).startswith('120000 blob '))
                self.assert_rejected(self.run_guard())

    def test_an_empty_git_subtree_is_also_a_backend_path_change(self):
        empty = self.git('mktree', input='')
        entries = self.git('ls-tree', f'{self.fixture_base}:backend')
        backend = self.git('mktree', input=entries + f'\n040000 tree {empty}\tempty-directory\n')
        tree = self.git('mktree', input=f'040000 tree {backend}\tbackend\n')
        self.head = self.git('commit-tree', tree, '-p', self.fixture_base, '-m', 'Fixture empty subtree')
        self.git('checkout', '--quiet', '--detach', self.head)
        self.assertIn('backend/empty-directory', self.git('ls-tree', '-r', '-t', 'HEAD', '--', 'backend'))
        self.assert_rejected(self.run_guard())

    def test_previous_and_candidate_toml_must_each_have_all_six_fields(self):
        for key in ANNOUNCEMENT_FIELDS:
            missing = re.sub(r'^' + key + r' = .*\n', '', FIXTURE_TOML, count=1, flags=re.M)
            with self.subTest(field=key, source='previous'):
                self.set_both_sources_toml(missing)
                self.toml.write_text(FIXTURE_TOML)
                self.head = self.commit()
                self.assert_rejected(self.run_guard())
            with self.subTest(field=key, source='candidate'):
                self.candidate_toml(missing)
                self.assert_rejected(self.run_guard())

    def test_announcement_types_and_value_structure_are_checked_in_both_sources(self):
        invalid = (
            ('APP_VERSION_CODE', 10), ('APP_VERSION_CODE', '0'), ('APP_VERSION_CODE', '2147483648'),
            ('APP_VERSION_NAME', ''), ('APP_VERSION_NAME', 'v0.8.2'), ('APP_VERSION_NAME', '0.8.2\n'),
            ('APP_DOWNLOAD_URL', 'http://example.invalid/app.apk'),
            ('APP_DOWNLOAD_URL', 'https://fixture-user:fixture-password@example.invalid/app.apk'),
            ('APP_DOWNLOAD_URL', 'https:///app.apk'), ('APP_DOWNLOAD_URL', 'https://example.invalid:bad/app.apk'),
            ('APP_DOWNLOAD_URL', 'https://example.invalid/\napp.apk'),
            ('APP_APK_SHA256', 'a' * 63), ('APP_APK_SHA256', 'z' * 64),
            ('APP_RELEASE_NOTES', ['wrong-type']), ('APP_RELEASE_NOTES', 'x' * 4001),
            ('APP_RELEASED_AT', '0'), ('APP_RELEASED_AT', '9007199254740992'),
            ('APP_RELEASED_AT', '2026-11-07T00:00:00Z'), ('APP_RELEASED_AT', 1793857524),
        )
        for key, value in invalid:
            with self.subTest(field=key, invalid_type=type(value).__name__):
                content = re.sub(r'^' + key + r' = .*$',
                                 lambda _: key + ' = ' + json.dumps(value),
                                 FIXTURE_TOML, count=1, flags=re.M)
                self.assertEqual(tomllib.loads(content)['vars'][key], value)
                self.set_both_sources_toml(content)
                self.assert_rejected(self.run_guard())

    def test_announcement_notes_length_matches_the_api_utf16_limit(self):
        for units, accepted in ((4000, True), (4002, False)):
            with self.subTest(utf16_units=units):
                notes = '\U0001f600' * (units // 2)
                content = re.sub(r'^APP_RELEASE_NOTES = .*$',
                                 lambda _: 'APP_RELEASE_NOTES = ' + json.dumps(notes, ensure_ascii=False),
                                 FIXTURE_TOML, count=1, flags=re.M)
                self.assertEqual(tomllib.loads(content)['vars']['APP_RELEASE_NOTES'], notes)
                self.set_both_sources_toml(content)
                result = self.run_guard()
                self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)

    def test_missing_vars_and_malformed_toml_fail_even_for_identical_trees(self):
        for content in (FIXTURE_TOML.replace('[vars]', '[announcement_vars]'),
                        FIXTURE_TOML + '\nbroken = ['):
            with self.subTest(content=content):
                self.set_both_sources_toml(content)
                self.assert_rejected(self.run_guard())

    def test_previous_selection_requires_a_well_formed_stable_tag(self):
        previous_values = (None, {}, {'tag': None}, {'tag': ''}, {'tag': 8}, {'tag': 'master'},
                           {'tag': 'v01.8.2'}, {'tag': 'v0.8.2-rc1'}, {'tag': 'v0.8.2\n'},
                           {'tag': 'v0.8.2^{commit}'}, {'tag': 'v0.8.2; touch escaped'})
        for previous in previous_values:
            with self.subTest(previous=previous):
                selection = self.selection()
                selection['previous'] = previous
                self.assert_rejected(self.run_guard(selection=selection))
        selection = self.selection()
        del selection['previous']
        self.assert_rejected(self.run_guard(selection=selection))

    def test_a_valid_previous_tag_without_a_real_local_ref_is_rejected(self):
        selection = self.selection()
        selection['previous']['tag'] = 'v0.8.0'
        self.assert_rejected(self.run_guard(selection=selection))

    def test_a_branch_with_the_tag_name_cannot_replace_a_missing_tag_ref(self):
        self.git('tag', '-d', 'v0.8.2')
        self.git('branch', 'v0.8.2', self.previous_commit)
        self.assert_rejected(self.run_guard())

    def test_a_tag_to_a_blob_is_not_a_published_source_commit(self):
        blob = self.git('rev-parse', f'{self.previous_commit}:backend/wrangler.toml')
        self.git('tag', 'v0.8.1', blob)
        selection = self.selection()
        selection['previous']['tag'] = 'v0.8.1'
        self.assert_rejected(self.run_guard(selection=selection))

    def test_annotated_previous_tags_are_peeled_to_the_actual_commit(self):
        self.git('tag', '-a', 'v0.8.1', self.previous_commit, '-m', 'Synthetic published source tag')
        selection = self.selection()
        selection['previous']['tag'] = 'v0.8.1'
        result = self.run_guard(selection=selection)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((self.temp / EVIDENCE_NAME).read_text())['previous']['commit'],
                         self.previous_commit)

    def test_master_matching_head_cannot_hide_a_change_from_the_published_tag(self):
        module = self.root / 'backend/src/index.ts'
        module.write_text('export const changedAfterPreviousRelease = true;\n')
        self.head = self.commit()
        self.assertEqual(self.git('rev-parse', 'master'), self.head)
        self.assertNotEqual(self.previous_commit, self.head)
        self.assert_rejected(self.run_guard())

    def test_selection_and_environment_commits_must_match_the_actual_full_head(self):
        for commit in (None, '', 'a' * 39, 'g' * 40, self.previous_commit,
                       self.head + '\n', ' ' + self.head):
            with self.subTest(selection_commit=commit):
                selection = self.selection()
                selection['commit'] = commit
                self.assert_rejected(self.run_guard(selection=selection))
            if type(commit) is str or commit is None:
                with self.subTest(environment_commit=commit):
                    self.assert_rejected(self.run_guard(environment={'RELEASE_COMMIT': commit}))
        selection = self.selection()
        del selection['commit']
        self.assert_rejected(self.run_guard(selection=selection))
        result = self.run_guard(environment={'RELEASE_COMMIT': self.head.upper()})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_selection_and_environment_release_tags_cannot_disagree(self):
        for tag in (None, '', 'v0.8.4', 'v0.8.3\n', 'master'):
            with self.subTest(tag=tag):
                selection = self.selection()
                selection['tag'] = tag
                self.assert_rejected(self.run_guard(selection=selection))
                self.assert_rejected(self.run_guard(environment={'RELEASE_TAG': tag}))

    def test_missing_selection_file_fails_closed(self):
        self.assert_rejected(self.run_guard(write_selection=False))

    def test_source_check_cannot_accept_full_empty_or_unknown_modes(self):
        for mode in (None, '', 'full', 'FULL', 'unknown', 'apk-only\n'):
            with self.subTest(mode=mode):
                self.assert_rejected(self.run_guard(environment={'PRODUCTION_MODE': mode}))

    def test_evidence_contains_hashes_and_source_ids_without_binding_or_token_values(self):
        binding_marker = 'NON_SECRET_FIXTURE_BINDING_MUST_NOT_BE_LOGGED'
        token_marker = 'NON_SECRET_FIXTURE_TOKEN_MUST_NOT_BE_LOGGED'
        self.set_both_sources_toml(FIXTURE_TOML.replace('APP_ENV = "production"',
                                                     'APP_ENV = "' + binding_marker + '"'))
        result = self.run_guard(environment={'CLOUDFLARE_API_TOKEN': token_marker})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = result.stdout + result.stderr + (self.temp / EVIDENCE_NAME).read_text()
        for value in (binding_marker, token_marker, 'database_id', '00000000-0000-0000-0000-000000000001'):
            self.assertNotIn(value, output)
        record = json.loads((self.temp / EVIDENCE_NAME).read_text())
        self.assertRegex(record['previous']['wrangler_sha256'], r'^[0-9a-f]{64}$')


if __name__ == '__main__':
    unittest.main()
