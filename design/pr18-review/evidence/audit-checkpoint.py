#!/usr/bin/env python3
"""Audit the tests-first snapshot without compiling Kotlin or starting a JVM.

This is a checkpoint audit, not an Android compiler or a future closure gate.
It compares the extracted composition and delegated I/O with PR18's original
source, and checks that all other original repository files are unchanged.
"""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[3]
BASE = '8b8decf7c39ed67858ef99c760e1ca75e273f013'
MASTER = '45c7f65823042deb76171732dcb2a000f8620e65'
APP = 'android/app/src/main/kotlin/top/alanvampuke/dininglog/'
TEST = 'android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/'
MODIFIED = {APP + 'MainActivity.kt', APP + 'InAppUpdates.kt', 'android/tools/verify_bite_visuals.py'}
ADDED = {APP + 'MainActivityContent.kt', APP + 'UpdateIo.kt',
         'android/data/src/debug/kotlin/top/alanvampuke/dininglog/data/Pr18PreferenceFile.kt',
         'android/tools/pr18_visual_roles.py', 'android/tools/test_pr18_theme_regressions.py'} | {
    TEST + name + '.kt' for name in ('Pr18ComponentTest', 'Pr18DialogAcceptanceTest', 'Pr18Evidence',
                                    'Pr18Fixtures', 'Pr18InstallTransactionTest',
                                    'Pr18PreferencesOrchestrationTest', 'Pr18ScreenAcceptanceTest', 'Pr18StartupTest')}


def git(*arguments):
    return subprocess.check_output(['git', *arguments], cwd=ROOT)


def original(path):
    return git('show', BASE + ':' + path).decode()


def current(path):
    return (ROOT / path).read_text()


def replace_once(text, before, after):
    assert text.count(before) == 1, ('unexpected seam fragment', before)
    return text.replace(before, after, 1)


def lines(text):
    return '\n'.join(line.strip() for line in text.splitlines() if line.strip())


def mask_kotlin(text):
    """Mask comments/strings, including interpolation, for a delimiter audit."""
    def block_comment(index):
        depth = 1
        index += 2
        while index < len(text):
            if text.startswith('/*', index):
                depth += 1
                index += 2
            elif text.startswith('*/', index):
                depth -= 1
                index += 2
                if depth == 0:
                    return index
            else:
                index += 1
        raise AssertionError('unclosed block comment')

    def interpolation(index):
        depth = 1
        while index < len(text):
            if text.startswith('//', index):
                end = text.find('\n', index)
                index = len(text) if end < 0 else end
            elif text.startswith('/*', index):
                index = block_comment(index)
            elif text[index] in ('"', "'"):
                index = quoted(index)
            elif text[index] == '{':
                depth += 1
                index += 1
            elif text[index] == '}':
                depth -= 1
                index += 1
                if depth == 0:
                    return index
            else:
                index += 1
        raise AssertionError('unclosed string interpolation')

    def quoted(index):
        raw = text.startswith('"""', index)
        quote = '"""' if raw else text[index]
        index += len(quote)
        while index < len(text):
            if text.startswith(quote, index):
                return index + len(quote)
            if not raw and text[index] == '\\':
                index += 2
            elif quote != "'" and text.startswith('${', index):
                index = interpolation(index + 2)
            else:
                index += 1
        raise AssertionError('unclosed quoted literal')

    masked = list(text)
    index = 0
    while index < len(text):
        start = index
        if text.startswith('//', index):
            end = text.find('\n', index)
            index = len(text) if end < 0 else end
        elif text.startswith('/*', index):
            index = block_comment(index)
        elif text[index] in ('"', "'"):
            index = quoted(index)
        else:
            index += 1
            continue
        for position in range(start, index):
            if masked[position] != '\n':
                masked[position] = ' '
    return ''.join(masked)


def body(text, marker):
    masked = mask_kotlin(text)
    start = masked.index('{', text.index(marker))
    depth = 1
    for end in range(start + 1, len(masked)):
        depth += (masked[end] == '{') - (masked[end] == '}')
        if depth == 0:
            return text[start + 1:end]
    raise AssertionError('unclosed source body: ' + marker)


assert git('branch', '--show-current').decode().strip() == 'fix/pr18-review-0.8.3'
assert git('rev-parse', 'master').decode().strip() == MASTER
subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=ROOT, check=True)
print('PASS branch/master/base; execution HEAD=' + git('rev-parse', 'HEAD').decode().strip())
baseline_files = set(git('ls-tree', '-r', '--name-only', BASE).decode().splitlines())
for path in sorted(baseline_files - MODIFIED):
    assert (ROOT / path).is_file() and (ROOT / path).read_bytes() == git('show', BASE + ':' + path), path
added = set(git('ls-files', '--others', '--exclude-standard').decode().splitlines())
added |= set(git('diff', '--name-only', BASE).decode().splitlines()) - baseline_files
assert all(path in ADDED or path.startswith('design/pr18-review/') for path in added), sorted(added - ADDED)
print(f'PASS {len(baseline_files - MODIFIED)} original files byte-identical; new files restricted to tests/seams/evidence')
legacy_tests = [path for path in baseline_files if '/src/androidTest/' in path or '/src/test/' in path]
print(f'PASS {len(legacy_tests)} legacy Android test files unchanged; dependencies/locks/version/signing/workflows/resources unchanged')
protected = json.loads(current('android/tools/p8_protected_hashes.json'))
for path, expected in protected.items():
    assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == expected, path
assert set(git('ls-files', 'backend', 'contracts').decode().splitlines()) == set(protected)
print(f'PASS {len(protected)} protected backend/contracts hashes; migrations unchanged')

main = body(original(APP + 'MainActivity.kt'), 'setContent {')
for before, after in (
    ('this@MainActivity', 'activity'), ('::installPermissionLaunch', 'requestInstallPermission'),
    ('applySystemBarTheme(window,', 'applySystemBarTheme(activity.window,'),
    ('startActivity(appUpdateIntent(it))', 'activity.startActivity(appUpdateIntent(it))'),
    ('getSystemService(CLIPBOARD_SERVICE)', 'activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)'),
):
    main = main.replace(before, after)
shared = body(current(APP + 'MainActivityContent.kt'), 'internal fun MainActivityContent(')
shared = replace_once(shared, 'inAppUpdates.open(release, automatic = true)', 'inAppUpdates.open(release)')
assert lines(main) == lines(shared), 'shared composition changed behavior'
entry = body(current(APP + 'MainActivity.kt'), 'setContent {')
assert lines(entry) == lines('MainActivityContent(this, store, account, gateway, refresh, repository,\n'
                            'security, preferences, updates, inAppUpdates, ::installPermissionLaunch)')
print('PASS original MainActivity composition equals extraction after receiver/ignored-provenance substitutions')

old = original(APP + 'InAppUpdates.kt')
new = current(APP + 'InAppUpdates.kt')
old_body = old[old.index('    private val mutableState'):]
new_body = new[new.index('    private val mutableState'):]
old_body = replace_once(old_body, '    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n', '')
new_body = replace_once(new_body, 'UpdateConnection?', 'HttpURLConnection?')
new_body = replace_once(new_body, '    fun open(release: AppRelease) = open(release, automatic = false)\n', '')
new_body = replace_once(new_body,
    '    // Provenance is explicit for regression coverage. Selection behavior is\n'
    '    // deliberately unchanged in the tests-first checkpoint.\n'
    '    @Suppress("UNUSED_PARAMETER")\n', '')
new_body = replace_once(new_body, 'internal fun open(release: AppRelease, automatic: Boolean)', 'fun open(release: AppRelease)')
http_start = old.index('                val request = URL(address).openConnection() as HttpURLConnection')
http_end = old.index('                val code = request.responseCode', http_start)
original_http = old[http_start:http_end]
new_body = replace_once(new_body, '                val request = transport.open(address)\n                connection = request\n', original_http)
new_body = replace_once(new_body, 'installPlatform.verify(apk(release), release.sha256)', 'verifyApkSha256(apk(release), release.sha256)')
new_body = replace_once(new_body, 'installPlatform.canRequestPackageInstalls(activity)', 'activity.packageManager.canRequestPackageInstalls()')
installer_start = old.index('                val uri = FileProvider.getUriForFile')
installer_end = old.index('                mutableState.value = state.value.copy(message', installer_start)
original_installer = old[installer_start:installer_end]
new_body = replace_once(new_body, '                installPlatform.startInstaller(activity, apk(release))\n', original_installer)
assert lines(old_body) == lines(new_body), 'InAppUpdates changed behavior beyond adapter delegation'
constructor = new[new.index('    @Inject constructor('):new.index('    private val mutableState')]
assert lines(constructor) == lines('''@Inject constructor(@ApplicationContext context: Context, versions: AppVersionGateway) : this(
context, versions, HttpUpdateTransport, AndroidUpdateInstallPlatform(context),
CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
)''')
adapter = current(APP + 'UpdateIo.kt')
delegated_http = adapter[adapter.index('        val request = URL(address).openConnection() as HttpURLConnection'):
                         adapter.index('        return object : UpdateConnection')]
assert lines(delegated_http) == lines(original_http.replace('                connection = request\n', '').replace('; ', '\n'))
for getter in ('override val responseCode: Int get() = request.responseCode',
               'override val contentLengthLong: Long get() = request.contentLengthLong',
               'override val inputStream: InputStream get() = request.inputStream',
               'override fun getHeaderField(name: String): String? = request.getHeaderField(name)',
               'override fun disconnect() = request.disconnect()',
               'override suspend fun verify(file: File, expected: String) = verifyApkSha256(file, expected)',
               'override fun canRequestPackageInstalls(activity: Activity) = activity.packageManager.canRequestPackageInstalls()'):
    assert getter in adapter, getter
assert lines(body(adapter, 'override fun startInstaller(activity: Activity, file: File)')) == \
    lines(original_installer.replace('apk(release)', 'file'))
print('PASS original InAppUpdates methods and production HTTP/installer operations retained; automatic argument ignored')

legacy_verifier = original('android/tools/verify_bite_visuals.py')
extra = '# Run the additional InkPaper consumer gate after every retained classic/native/\n' \
        '# vector/tag/protected-file assertion, so a RED accent cannot mask those guards.\n' \
        'from pr18_visual_roles import check_ink_paper\n' \
        "measurements['ink_paper'] = check_ink_paper()\n"
assert replace_once(current('android/tools/verify_bite_visuals.py'), extra, '') == legacy_verifier
print('PASS every original contrast/native/vector/tag/protected-hash verifier assertion retained')
new_kotlin = sorted(path for path in ADDED if path.endswith('.kt'))
for path in new_kotlin + [APP + 'MainActivity.kt', APP + 'InAppUpdates.kt']:
    stack = []
    for character in mask_kotlin(current(path)):
        if character in '({[':
            stack.append(character)
        elif character in ')}]':
            assert stack and stack.pop() == {')': '(', '}': '{', ']': '['}[character], path
    assert not stack, (path, stack)
print(f'PASS delimiter audit of {len(new_kotlin) + 2} Kotlin files; Android compilation/execution PENDING parent CI')
