#!/usr/bin/env python3
"""Compare reviewed APK-only source with selection.previous.tag, entirely offline."""
from datetime import date, datetime, time
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from urllib.parse import urlsplit


TAG = re.compile(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')
COMMIT = re.compile(r'[0-9a-fA-F]{40}')
EVIDENCE_NAME = 'dining-apk-only-source-check.json'
ANNOUNCEMENT_FIELDS = ('APP_VERSION_CODE', 'APP_VERSION_NAME', 'APP_DOWNLOAD_URL',
                       'APP_APK_SHA256', 'APP_RELEASE_NOTES', 'APP_RELEASED_AT')
WRANGLER = 'backend/wrangler.toml'


class SourceCheckError(Exception):
    """Messages contain only fixed diagnostics, never configuration values."""


def require(condition, message):
    if not condition:
        raise SourceCheckError(message)


def unique_json_fields(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Release selection contains duplicate JSON fields')
        result[key] = value
    return result


def git(root, *arguments):
    result = subprocess.run(['git', '--no-replace-objects', '-C', str(root), *arguments],
                            capture_output=True, check=False)
    require(result.returncode == 0, 'Required Git source or tag cannot be resolved')
    return result.stdout


def backend_tree(root, commit):
    entries = {}
    for row in git(root, 'ls-tree', '-r', '-t', '-z', '--full-tree', commit, '--', 'backend').split(b'\0'):
        if row:
            metadata, path = row.split(b'\t', 1)
            entries[path.decode('utf-8')] = tuple(metadata.decode('ascii').split())
    require('backend' in entries and entries['backend'][:2] == ('040000', 'tree'),
            'Both sources must contain the complete backend tree')
    return entries


def positive_decimal(value, maximum):
    return (re.fullmatch(r'[1-9][0-9]*', value) is not None
            and len(value) <= len(str(maximum)) and int(value) <= maximum)


def announcement_config(blob):
    config = tomllib.loads(blob.decode('utf-8'))
    values = config.get('vars')
    require(type(values) is dict, 'Both TOML sources must contain a vars table')
    require(all(type(values.get(key)) is str for key in ANNOUNCEMENT_FIELDS),
            'Both TOML sources must contain all six announcement fields as strings')
    require(positive_decimal(values['APP_VERSION_CODE'], 2147483647), 'Invalid APP_VERSION_CODE structure')
    version = values['APP_VERSION_NAME']
    require(len(version) <= 80 and TAG.fullmatch('v' + version) is not None,
            'Invalid APP_VERSION_NAME structure')
    download = values['APP_DOWNLOAD_URL']
    require(not any(ord(character) <= 32 or ord(character) == 127 for character in download),
            'Invalid APP_DOWNLOAD_URL structure')
    url = urlsplit(download)
    require(url.scheme == 'https' and bool(url.hostname) and url.username is None and url.password is None,
            'APP_DOWNLOAD_URL must be HTTPS without credentials')
    require(url.port is None or 0 < url.port <= 65535, 'Invalid APP_DOWNLOAD_URL port')
    require(re.fullmatch(r'[0-9a-fA-F]{64}', values['APP_APK_SHA256']) is not None,
            'Invalid APP_APK_SHA256 structure')
    require(len(values['APP_RELEASE_NOTES'].encode('utf-16-le')) <= 8000,
            'Invalid APP_RELEASE_NOTES structure')
    require(positive_decimal(values['APP_RELEASED_AT'], 9007199254740991),
            'APP_RELEASED_AT must be a positive safe Unix timestamp')
    # Remove exactly these existing six top-level vars, after validating both
    # sources. Nested vars, bindings, IDs and every other setting remain intact.
    config['vars'] = {key: value for key, value in values.items() if key not in ANNOUNCEMENT_FIELDS}
    return config, values


def semantic_config_bytes(value):
    """Canonical TOML semantics with types, so true/1/1.0 cannot compare equal."""
    def typed(item):
        kind = type(item)
        if kind is dict:
            return ['table', [[key, typed(child)] for key, child in sorted(item.items())]]
        if kind is list:
            return ['array', [typed(child) for child in item]]
        if kind in (date, datetime, time):
            return [kind.__name__, item.isoformat()]
        if kind is float:
            return ['float', item.hex()]
        if kind in (str, bool, int):
            return [kind.__name__, item]
        raise SourceCheckError('Unsupported TOML value type')
    return json.dumps(typed(value), separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def check_source(root, selection):
    require(os.environ.get('PRODUCTION_MODE') == 'apk-only', 'Source check requires explicit apk-only mode')
    release_commit = os.environ.get('RELEASE_COMMIT', '')
    release_tag = os.environ.get('RELEASE_TAG', '')
    require(COMMIT.fullmatch(release_commit) is not None, 'RELEASE_COMMIT must be a full commit SHA')
    require(TAG.fullmatch(release_tag) is not None, 'RELEASE_TAG must be a stable version tag')
    require(type(selection) is dict, 'Release selection must be an object')
    candidate = selection.get('commit')
    require(type(candidate) is str and COMMIT.fullmatch(candidate) is not None,
            'Selection candidate must be a full commit SHA')
    head = git(root, 'rev-parse', '--verify', 'HEAD^{commit}').decode('ascii').strip()
    require(candidate == head == release_commit.lower(), 'Selection, HEAD and RELEASE_COMMIT must match')
    require(selection.get('tag') == release_tag, 'Selection tag and RELEASE_TAG must match')
    previous = selection.get('previous')
    require(type(previous) is dict, 'A previous published release is required for APK-only')
    previous_tag = previous.get('tag')
    require(type(previous_tag) is str and TAG.fullmatch(previous_tag) is not None,
            'Previous published tag must be a stable version tag')
    # Resolve only the exact published tag ref. Never fetch, guess, or use master.
    previous_commit = git(root, 'rev-parse', '--verify',
                          f'refs/tags/{previous_tag}^{{commit}}').decode('ascii').strip()
    old_tree = backend_tree(root, previous_commit)
    new_tree = backend_tree(root, head)
    require(old_tree.keys() == new_tree.keys(), 'Backend paths were added or removed')
    require(WRANGLER in old_tree and old_tree[WRANGLER][:2] in
            (('100644', 'blob'), ('100755', 'blob')), 'Both sources must contain a regular wrangler.toml')
    for path, old_entry in old_tree.items():
        new_entry = new_tree[path]
        if path in ('backend', WRANGLER):
            require(old_entry[:2] == new_entry[:2], 'Backend tree or wrangler.toml mode/type changed')
        else:
            require(old_entry == new_entry, 'Backend source changed outside wrangler.toml')
    old_blob = git(root, 'cat-file', 'blob', old_tree[WRANGLER][2])
    new_blob = git(root, 'cat-file', 'blob', new_tree[WRANGLER][2])
    old_config, old_values = announcement_config(old_blob)
    new_config, new_values = announcement_config(new_blob)
    old_semantics = semantic_config_bytes(old_config)
    new_semantics = semantic_config_bytes(new_config)
    require(old_semantics == new_semantics, 'TOML changed outside the six announcement fields')
    return {'schema': 1, 'status': 'passed', 'production_mode': 'apk-only',
            'previous': {'tag': previous_tag, 'commit': previous_commit,
                         'backend_tree': old_tree['backend'][2], 'wrangler_blob': old_tree[WRANGLER][2],
                         'wrangler_sha256': hashlib.sha256(old_blob).hexdigest()},
            'candidate': {'tag': release_tag, 'commit': head, 'backend_tree': new_tree['backend'][2],
                          'wrangler_blob': new_tree[WRANGLER][2],
                          'wrangler_sha256': hashlib.sha256(new_blob).hexdigest()},
            'backend_entries': len(new_tree),
            'changed_paths': sorted(path for path in old_tree if path != 'backend' and old_tree[path] != new_tree[path]),
            'allowed_announcement_fields': sorted(ANNOUNCEMENT_FIELDS),
            'changed_announcement_fields': sorted(key for key in ANNOUNCEMENT_FIELDS if old_values[key] != new_values[key]),
            'non_announcement_config_sha256': hashlib.sha256(old_semantics).hexdigest()}


def main():
    temp_value = os.environ.get('RUNNER_TEMP')
    root_value = os.environ.get('GITHUB_WORKSPACE')
    if not temp_value or not root_value:
        print('APK-only source check requires runner and workspace directories', file=sys.stderr)
        return 1
    output = Path(temp_value) / EVIDENCE_NAME
    try:
        output.unlink(missing_ok=True)
        root = Path(root_value).resolve()
        require(Path(git(root, 'rev-parse', '--show-toplevel').decode().strip()).resolve() == root,
                'GITHUB_WORKSPACE must be the Git repository root')
        selection = json.loads((Path(temp_value) / 'dining-release-selection.json').read_text(encoding='utf-8'),
                               object_pairs_hook=unique_json_fields,
                               parse_constant=lambda _: require(False, 'Release selection must be valid JSON'))
        record = check_source(root, selection)
    except SourceCheckError as error:
        record = {'schema': 1, 'status': 'failed', 'production_mode': 'apk-only', 'reason': str(error)}
    except (OSError, ValueError, UnicodeError):
        record = {'schema': 1, 'status': 'failed', 'production_mode': 'apk-only',
                  'reason': 'Release selection or source is missing or malformed'}
    try:
        output.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    except OSError:
        print('APK-only source evidence cannot be written', file=sys.stderr)
        return 1
    print(json.dumps(record))
    return 0 if record['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
