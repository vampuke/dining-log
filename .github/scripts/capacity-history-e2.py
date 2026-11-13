"""Actions-only post-suite diagnostic; imported controls never start Workers."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import select
import signal
import stat
import subprocess
import sys
import time

BASE = 'cb933095f8855889e5d76f960923108e0e285496'
BRANCH = 'verify/issue19-capacity-history-payload-20261112'
REF = 'refs/heads/' + BRANCH
BACKEND_TREE = '29ba53b82cfa707e872f3571bebb42aecb6011b1'
ALLOWED = ('.github/scripts/capacity-history-e2-probe.ts', '.github/scripts/capacity-history-e2.py',
           '.github/scripts/test_capacity_history_e2.py', '.github/workflows/capacity-history-e2.yml')
SNAPSHOT_SOURCES = ('backend/tests/capacity/capacity_test.ts', 'backend/vitest.capacity.config.ts',
                    'backend/package.json', 'backend/package-lock.json', 'backend/wrangler.toml', *ALLOWED)
REQUIRED_SCRATCH = ('scratch/config.ts', 'scratch/setup.ts', 'scratch/config-facts.json')
WRANGLER_SHA256 = 'c54fae36ad2d0eac2098cff9396f7405fe852b0ca8a82e261945db1987410a3f'

def validation(fn):
    def checked(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except (KeyError, IndexError, TypeError, AttributeError, OverflowError) as error:
            raise ValueError('incomplete or malformed evidence') from error
    return checked

@validation
def audit_original(raw, scenario):
    payload, result = record(raw, 'CAPACITY_RESULT')
    validate_corpus(result, scenario)
    require([q['id'] for q in result['queries']] == list(QUERY_IDS), 'direct groups/order')
    require(len(result['actualAPI']) == 8 and len({q['path'] for q in result['actualAPI']}) == 8, 'API groups')
    for q in result['queries'] + result['actualAPI']:
        require(len(q['samples']) == 30, 'first thirty required')
    returned = [[20],[20],[20],[0],[1],[result['scenario']['dishesPerRestaurant']],[3],[1,result['scenario']['dishesPerRestaurant'],3],[10]]
    base = '/restaurants/' + SELECTED[scenario]
    paths = ['/restaurants?limit=20','/restaurants?sort=rating_desc&limit=20','/search?q=%E6%8B%9B%E7%89%8C%E6%B5%8B%E8%AF%95','/search?q=capacity_missing_51a71c',base,base+'/dishes',base+'/visits',base+'/revisions?limit=10']
    require([a['path'] for a in result['actualAPI']] == paths, 'original API paths/order')
    for q, counts in zip(result['queries'], returned):
        for sample in q['samples']:
            require(sample['returnedRows'] == counts and all(type(v) is int for v in sample['returnedRows']), 'original returned cardinality')
            for key in ('wallMs', 'sqlMs', 'rowsRead', 'rowsWritten'):
                number(sample[key], integer=key in ('wallMs', 'rowsRead', 'rowsWritten'))
            require(sample['rowsWritten'] == 0, 'original read wrote rows')
            require(set(('wallMs', 'sqlMs', 'rowsRead', 'rowsWritten', 'returnedRows')) <= set(sample), 'sample fields')
    for api in result['actualAPI']:
        for value in api['samples']: number(value, integer=True)
    gates = []
    for query in result['queries']:
        for metric in ('wallMs', 'sqlMs', 'rowsRead'):
            require(query[metric] == quantiles([v[metric] for v in query['samples']]), 'reported quantiles')
        for metric in ('wallMs', 'sqlMs'):
            gates.append({'id': query['id'], 'metric': metric,
                          'p95': sorted(s[metric] for s in query['samples'])[28]})
    for api in result['actualAPI']:
        require(api['wallMs'] == quantiles(api['samples']), 'reported API quantiles')
        gates.append({'id': api['path'], 'metric': 'wallMs', 'p95': sorted(api['samples'])[28]})
    return {'result': result, 'original_samples': sum(len(q['samples']) for q in result['queries'] + result['actualAPI']),
            'gate_evaluations': len(gates), 'gates': gates, 'performance_failed': any(g['p95'] > 100 for g in gates),
            'payload_sha256': hashlib.sha256(payload).hexdigest(), 'payload_utf8_bytes': len(payload),
            'raw_emit_sha256': hashlib.sha256(b'CAPACITY_RESULT ' + payload).hexdigest()}


def require(condition, message):
    if not condition:
        raise ValueError(message)

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result

def parse_json(raw):
    return json.loads(raw, object_pairs_hook=unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite JSON')))

def record(raw, marker):
    token = marker.encode() + b' '
    require(raw.count(token) == 1, 'missing or duplicate ' + marker)
    payload = raw.split(token, 1)[1].splitlines()[0]
    value = parse_json(payload)
    require(isinstance(value, dict), 'record must be an object')
    return payload, value

QUERY_IDS = ('updated_page', 'rating_page', 'aggregate_search_hit', 'aggregate_search_miss',
             'restaurant_detail', 'restaurant_dishes', 'restaurant_visits', 'detail_children_batch', 'entity_revisions_10')
TABLES = ('users', 'sessions', 'refresh_tokens', 'refresh_rotation_results', 'recovery_codes', 'auth_rate_limits',
          'restaurants', 'dishes', 'restaurant_search', 'visits', 'change_sets', 'revisions', 'idempotency_keys', 'mutation_guards')
USER = '00000000-0000-4000-8000-000000000001'
SELECTED = {'p1-4500': '36b96093-b118-4f24-9243-c225d2902229', 'plan-6000': '7900e620-0083-4b1b-90b1-18f0665e228b'}
FIXTURE = {'p1-4500': '664bf1e7a42ce7a6c32850a4ba38ef94aafd533eeae2c1b0bef186e972f1bbfc',
           'plan-6000': '5021392ad1d8cec6c9b8a7400cf50b2fe6b0c2e05b2148dfc66e596d52685244'}
HISTORY = 'SELECT * FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 10'
FIXED = 1793448000000

def deltas():
    values = dict.fromkeys(TABLES, 0)
    values.update(restaurants=1, dishes=5, visits=3, revisions=10, change_sets=1, restaurant_search=1)
    return values

def corpus(scenario):
    require(scenario in SELECTED, 'scenario')
    dishes = 5 if scenario == 'p1-4500' else 8
    counts = dict.fromkeys(TABLES, 0)
    counts.update(users=1, restaurants=500, dishes=500*dishes, visits=1500, restaurant_search=500,
                  revisions=13500 if dishes == 5 else 18000, change_sets=13500 if dishes == 5 else 18000)
    return counts

def validate_corpus(result, scenario):
    counts = corpus(scenario)
    expected = {'name': scenario, 'restaurants': 500, 'dishesPerRestaurant': 5 if scenario == 'p1-4500' else 8,
                'visitsPerRestaurant': 3, 'restaurantRevisions': 11 if scenario == 'p1-4500' else 14,
                'childRevisions': 2, 'objects': 4500 if scenario == 'p1-4500' else 6000, 'history': counts['revisions']}
    require(result['schemaVersion'] == 1 and result['scenario'] == expected, 'original scenario/schema')
    require(result['repetitions'] == 30 and result['p95LimitMs'] == 100, 'original gates changed')
    require(result['seed']['seed'] == 0x51a71c and result['seed']['seedHex'] == '0x51a71c'
            and result['seed']['fixedTimeUtc'] == '2026-10-31T12:00:00.000Z'
            and result['seed']['fixtureSha256'] == FIXTURE[scenario], 'fixture identity')
    for table in TABLES:
        number(result['rowCounts'][table], integer=True)
        number(result['write']['rowDeltas'][table], integer=True)
    for key in ('wallMs','sqlMs','rowsRead','rowsWritten'):
        number(result['write'][key], integer=key != 'sqlMs')
    require(result['wallClock'] == 'Date.now (1 ms resolution; Workers performance.now is not used)'
            and result['integrity'] == {'foreignKeyViolations':0,'quickCheck':'ok'}, 'original clock/integrity')
    require(result['rowCounts'] == counts and result['write']['rowDeltas'] == deltas()
            and result['write']['changes'] == 22, 'corpus/write deltas')
    require(result['restaurantRevisionRange'] == {'min': expected['restaurantRevisions'], 'max': expected['restaurantRevisions']}, 'revision range')
    history = [q for q in result['queries'] if q['id'] == 'entity_revisions_10']
    require(len(history) == 1 and history[0]['statements'] == [{'sql': HISTORY, 'params': [USER, 'restaurant', SELECTED[scenario]]}], 'history SQL/params')

def number(value, integer=False):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, 'invalid finite nonnegative number')
    if integer:
        require(type(value) is int, 'integer required')
    return value

def quantiles(values):
    require(len(values) == 30, 'exactly thirty; no fallback quantile')
    values = sorted(values)
    return {'min': values[0], 'p50': values[14], 'p95': values[28], 'max': values[29]}

@validation
def audit_sample(value, index, ids, lengths, projection):
    require(value['index'] == index and type(value['index']) is int, 'sample ordinal')
    for key in ('start_ms', 'stop_ms', 'wall_ms', 'bookkeeping_stop_ms', 'bookkeeping_ms'):
        number(value[key], integer=True)
    require(value['stop_ms'] >= value['start_ms'] and value['wall_ms'] == value['stop_ms'] - value['start_ms'], 'wall clock')
    require(value['bookkeeping_stop_ms'] >= value['stop_ms'] and value['bookkeeping_ms'] == value['bookkeeping_stop_ms'] - value['stop_ms'], 'bookkeeping clock')
    readonly_meta(value['meta'])
    require(number(value['d1_reported_duration_ms']) == value['meta']['duration'], 'D1 duration mismatch')
    valid_ids(value['ordered_ids'])
    require(value['ordered_ids'] == ids and value['returned_count'] == 10, 'ordered IDs/count')
    representation = value['json_representation']
    members = representation['members']
    require(representation['property_order'] == [m['key'] for m in members]
            and len(members) == 3 and set(representation['property_order']) == {'success', 'meta', 'results'}, 'complete result members/order')
    require(parse_json(representation['meta_json']) == value['meta'], 'serialized meta mismatch')
    row_bytes = representation['row_json_utf8_bytes']
    expected = [utf8_json({'id': ident}) for ident in ids] if projection == 'id' else [lengths[ident] for ident in ids]
    require(projection in ('id', '*') and row_bytes == expected, 'row representation lengths')
    expected_values = {'success': 4, 'meta': len(representation['meta_json'].encode()), 'results': 2 + 9 + sum(row_bytes)}
    for member in members:
        require(member['key_json_utf8_bytes'] == utf8_json(member['key'])
                and member['value_json_utf8_bytes'] == expected_values[member['key']], 'component bytes')
    total = 2 + len(members) - 1 + sum(m['key_json_utf8_bytes'] + 1 + m['value_json_utf8_bytes'] for m in members)
    require(number(value['json_result_utf8_bytes'], integer=True) == total, 'complete JSON byte arithmetic')
    require(re.fullmatch('[0-9a-f]{64}', value['json_result_sha256']), 'result fingerprint')

def valid_ids(values):
    require(isinstance(values, list) and len(values) == 10 and len(set(values)) == 10, 'ten distinct IDs')
    for value in values:
        require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', value), 'revision UUID')

def readonly_meta(meta):
    for key in ('duration', 'size_after', 'rows_read', 'rows_written', 'last_row_id', 'changes'):
        number(meta[key], integer=key != 'duration')
    require(meta['rows_written'] == 0 and meta['changes'] == 0 and meta['changed_db'] is False, 'writeful read')

def utf8_json(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode())

@validation
def audit_probe(raw, original, scenario, projection, root):
    _, preflight = record(raw, 'E2_PREFLIGHT')
    _, history = record(raw, 'E2_HISTORY_RESULT')
    require(b'E2_HISTORY_ERROR ' not in raw, 'native hook error')
    for value in (preflight, history):
        require(value['schemaVersion'] == 1 and value['scenario'] == scenario and value['projection'] == projection, 'probe identity')
    capture = preflight['capture']
    require(capture['emit_count'] == 1 and capture['wrapper_owned'] is True and capture['wrapper_restored'] is True, 'console ownership')
    for key in ('payload_sha256', 'payload_utf8_bytes', 'raw_emit_sha256'):
        require(capture[key] == original[key], 'raw forwarding hash/bytes')
    number(capture['unexpected_log_calls'], integer=True)
    binding = preflight['binding']
    require(binding['saved_db_same'] is True and {'DB', 'TEST_MIGRATIONS'} <= set(binding['provided_names']), 'saved env.DB identity')
    task = preflight['original_task']
    require(task == history['original_task'], 'task drift')
    require(task['filepath'] == str(Path(root) / 'backend/tests/capacity/capacity_test.ts')
            and task['pool'] == 'threads', 'original file/pool')
    require(len(task['leaves']) == 2 and len({l['id'] for l in task['leaves']}) == 2, 'selected leaf count')
    selected = [l for l in task['leaves'] if l['name'].startswith(scenario + ': ')]
    other = [l for l in task['leaves'] if not l['name'].startswith(scenario + ': ')]
    require(len(selected) == len(other) == 1 and selected[0]['mode'] == 'run'
            and selected[0]['state'] in ('pass', 'fail') and other[0]['mode'] == 'skip'
            and other[0]['state'] == 'skip' and other[0]['name'].startswith(('plan-6000' if scenario == 'p1-4500' else 'p1-4500') + ': '), 'finished selected original leaf')
    require(len(history['samples']) == 30, 'first thirty probe samples')
    rows = attest(preflight['attestation_before'], original['result'], scenario)
    attest(history['attestation_after'], original['result'], scenario)
    require([(r['label'],r['sql'],r['params'],r['results']) for r in preflight['attestation_before']['reads']] == [(r['label'],r['sql'],r['params'],r['results']) for r in history['attestation_after']['reads']], 'post-probe corpus/content drift')
    measurement = history['measurement']
    queries = [q for q in original['result']['queries'] if q['id'] == 'entity_revisions_10']
    require(len(queries) == 1, 'single original history query')
    query = queries[0]
    require(type(query['statements']) is list and len(query['statements']) == 1, 'single original history statement')
    plans = query['plans']
    require(type(plans) is list and len(plans) == 1, 'single original history plan')
    original_plan = plans[0]
    require(type(original_plan) is list and original_plan
            and all(type(detail) is str and detail for detail in original_plan), 'nonempty original history plan strings')
    full_sql = query['statements'][0]['sql']
    id_sql = full_sql.replace('SELECT *', 'SELECT id', 1)
    params = query['statements'][0]['params']
    require(measurement == {'full_sql': full_sql, 'id_sql': id_sql, 'params': params,
                            'sql': full_sql if projection == '*' else id_sql}, 'single projection SQL')
    for stage in ('plans_before', 'plans_after'):
        require(set(history[stage]) == {'full','id'}, 'both SQL plans')
        for arm, sql in [('full', full_sql), ('id', id_sql)]:
            plan = history[stage][arm]
            require(plan['sql'] == 'EXPLAIN QUERY PLAN ' + sql and plan['params'] == params and plan['success'] is True, 'EXPLAIN identity')
            readonly_meta(plan['meta'])
            require(len(plan['results']) > 0, 'missing EXPLAIN rows')
            for row in plan['results']:
                require(set(row) == {'id','parent','notused','detail'} and isinstance(row['detail'], str), 'EXPLAIN row shape')
                for key in ('id','parent','notused'): number(row[key], integer=True)
    ids = [r['id'] for r in rows]
    lengths = {r['id']: r['row_json_utf8_bytes'] for r in rows}
    for index, sample in enumerate(history['samples'], 1):
        audit_sample(sample, index, ids, lengths, projection)
        if index > 1:
            require(sample['start_ms'] >= history['samples'][index-2]['bookkeeping_stop_ms'], 'serial probe clock reversal')
    return {'probe_calls': len(history['samples']), 'reference_ids': [r['id'] for r in rows],
            'original_task_state': selected[0]['state'],
            'preflight': preflight, 'history': history, 'original_plan': original_plan}

@validation
def attest(value, original, scenario):
    params = [USER, 'restaurant', SELECTED[scenario]]
    revisions = original['scenario']['restaurantRevisions']
    dish_count = original['scenario']['dishesPerRestaurant']
    reads = value['reads']
    labels = ['user', 'restaurant', 'dishes', 'visits', 'selected_revision_count'] + ['count:' + t for t in TABLES] + [
        'appended_restaurant', 'appended_revisions', 'appended_change_set', 'history_full', 'history_id']
    require([r['label'] for r in reads] == labels, 'attestation read order/completeness')
    for r in reads: readonly_meta(r['meta'])
    by = {r['label']: r for r in reads}
    def check(label, sql, bind):
        r = by[label]
        require(r['sql'] == sql and r['params'] == bind and r['success'] is True, 'attestation SQL/params')
        return r['results']
    user = check('user', 'SELECT id,username,display_name FROM users WHERE id=?', [USER])
    require(user == [{'id': USER, 'username': 'capacity_user', 'display_name': 'Synthetic capacity account'}], 'safe known account')
    row = check('restaurant', 'SELECT id,user_id,version,created_at,updated_at FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL', [USER, SELECTED[scenario]])
    require(row == [{'id': SELECTED[scenario], 'user_id': USER, 'version': revisions,
                     'created_at': FIXED + 2000, 'updated_at': FIXED + 2000 + revisions - 1}], 'selected restaurant identity/version')
    for label, count, order in [('dishes', dish_count, 'updated_at'), ('visits', 3, 'dining_date')]:
        query = next(q for q in original['queries'] if q['id'] == 'restaurant_' + label)['statements']
        sql = f'SELECT * FROM {label} WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY {order} DESC,id DESC'
        require(query == [{'sql': sql, 'params': [USER, SELECTED[scenario]]}], 'original child predicates')
        children = check(label, sql.replace('SELECT *', 'SELECT id,user_id,restaurant_id,version,created_at,updated_at', 1), query[0]['params'])
        require(len(children) == count and len({c['id'] for c in children}) == count, 'selected child IDs/count')
        for c in children:
            require(set(c) == {'id', 'user_id', 'restaurant_id', 'version', 'created_at', 'updated_at'} and
                    c['user_id'] == USER and c['restaurant_id'] == SELECTED[scenario] and c['version'] == 2, 'child identity/version')
            require(re.fullmatch(r'[0-9a-f-]{36}', c['id']) and c['updated_at'] == c['created_at'] + 1, 'child identity/time')
    n = check('selected_revision_count', 'SELECT COUNT(*) AS n FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL', params)
    require(n == [{'n': revisions}], 'selected revision count')
    post = corpus(scenario)
    for t, delta in deltas().items(): post[t] += delta
    post['sessions'] += 1; post['refresh_tokens'] += 1
    for table in TABLES:
        require(check('count:' + table, f'SELECT COUNT(*) AS n FROM {table}', []) == [{'n': post[table]}], 'post-suite count/session/write deltas')
    appended = check('appended_restaurant', 'SELECT id,user_id,version,created_at,updated_at FROM restaurants WHERE user_id=? AND created_at=?', [USER, FIXED + 500000])
    require(len(appended) == 1, 'appended identity')
    a = appended[0]
    require(set(a) == {'id', 'user_id', 'version', 'created_at', 'updated_at'} and a['user_id'] == USER
            and a['version'] == 2 and a['created_at'] == FIXED + 500000 and a['updated_at'] == FIXED + 500001, 'appended version/time')
    group = check('appended_revisions', 'SELECT id,user_id,restaurant_id,entity_type,entity_id,entity_version_after,change_set_id,created_at FROM revisions WHERE user_id=? AND restaurant_id=? ORDER BY created_at,id', [USER, a['id']])
    valid_ids([r['id'] for r in group])
    require(len({r['change_set_id'] for r in group}) == 1, 'one appended change set')
    types = [r['entity_type'] for r in group]
    require(types.count('restaurant') == 2 and types.count('dish') == 5 and types.count('visit') == 3, 'ten grouped revisions')
    for r in group:
        require(set(r) == {'id','user_id','restaurant_id','entity_type','entity_id','entity_version_after','change_set_id','created_at'} and
                r['user_id'] == USER and r['restaurant_id'] == a['id'] and FIXED + 500000 <= r['created_at'] < FIXED + 500200, 'group identity/time')
        require(r['entity_version_after'] in (1,2) if r['entity_type'] == 'restaurant' else r['entity_version_after'] == 1, 'group versions')
    restaurants = [r for r in group if r['entity_type'] == 'restaurant']
    require([r['entity_version_after'] for r in restaurants] == [1,2] and all(r['entity_id'] == a['id'] for r in restaurants), 'restaurant grouped versions')
    cs = check('appended_change_set', 'SELECT id,user_id,created_at FROM change_sets WHERE user_id=? AND id=?', [USER, group[0]['change_set_id']])
    require(cs == [{'id': group[0]['change_set_id'], 'user_id': USER, 'created_at': FIXED + 500000}], 'appended change set')
    full = check('history_full', HISTORY, params)
    id_rows = check('history_id', HISTORY.replace('SELECT *', 'SELECT id', 1), params)
    ids = [r['id'] for r in full]
    valid_ids(ids)
    require(id_rows == [{'id': value} for value in ids], 'ordered full/id identities')
    require([(r['created_at'], r['id']) for r in full] == sorted([(r['created_at'], r['id']) for r in full], reverse=True), 'descending history order')
    for index, r in enumerate(full):
        version = revisions - index
        require(r['user_id'] == USER and r['restaurant_id'] == SELECTED[scenario] and r['entity_type'] == 'restaurant'
                and r['entity_id'] == SELECTED[scenario] and r['entity_version_after'] == version
                and r['entity_version_before'] == version - 1 and r['created_at'] == FIXED + 2000 + version - 1, 'history identity/version/time')
        for key in ('diff_json', 'search_text'):
            text = r[key]
            require(number(text['utf16_length'], integer=True) >= 7800 and number(text['utf8_length'], integer=True) >= text['utf16_length']
                    and re.fullmatch('[0-9a-f]{64}', text['sha256']), 'long history content/fingerprint')
        diff = r['review_diff']
        require(diff['change_count'] == 1 and diff['field'] == 'review' and 3900 <= diff['before_utf16_length'] <= 4000
                and 3900 <= diff['after_utf16_length'] <= 4000, 'original long review changes')
        require(number(r['row_json_utf8_bytes'], integer=True) > 40000 and re.fullmatch('[0-9a-f]{64}', r['row_sha256'])
                and len(r['column_order']) == len(set(r['column_order'])) and {'id','diff_json','search_text','entity_version_after'} <= set(r['column_order']), 'full row content/columns')
    return full

@validation
def guard_identity(**facts):
    require(facts['head'] == facts['candidate'] and re.fullmatch('[0-9a-f]{40}', facts['head']), 'candidate SHA')
    require(facts['branch'] == BRANCH and facts['ref'] == REF and facts['backend_tree'] == BACKEND_TREE
            and facts['descends'] is True and sorted(facts['changes']) == sorted(('A', p) for p in ALLOWED), 'frozen branch/tree/four paths')

@validation
def verify_tracked_bytes(root, expected):
    root = Path(root).resolve()
    manifest = {}
    for name, entry in expected.items():
        path = root / name
        require(not Path(name).is_absolute() and '..' not in Path(name).parts, 'unsafe tracked path')
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root), 'tracked source ownership')
        raw = path.read_bytes()
        mode = '100755' if path.stat().st_mode & 0o111 else '100644'
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        require(mode == entry['mode'] and blob == entry['blob'], 'tracked bytes/modes changed')
        manifest[name] = {'mode': mode, 'blob': blob, 'sha256': hashlib.sha256(raw).hexdigest()}
    return manifest

@validation
def expand_setup(template):
    token = b'__E2_PROJECTION__'
    require(template.count(token) == 1, 'one projection placeholder required')
    variants = {'full': template.replace(token, b'*'), 'id': template.replace(token, b'id')}
    require(variants['full'].replace(b"projection = '*'", b"projection = 'id'", 1) == variants['id'], 'only projection may differ')
    return variants

CONFIG = b"""import original from '../vitest.capacity.config';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { writeFileSync, readFileSync, lstatSync, realpathSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
export default async function(configEnv) {
  // Use the frozen backend file in place: copying it would rebase main/migrations.
  // Wrangler also reads adjacent .dev.vars, so reject supplemental inputs unread.
  const scratch = dirname(fileURLToPath(import.meta.url));
  const backend = fileURLToPath(new URL('../', import.meta.url)).replace(/\/$/, '');
  for (const directory of [backend, scratch]) {
    const info = lstatSync(directory);
    if (!info.isDirectory() || info.isSymbolicLink() || info.uid !== process.getuid() ||
        realpathSync(directory) !== directory ||
        readdirSync(directory).some(name => /^\.(dev\.vars|env)(\.|$)/.test(name))) {
      throw new Error('unsafe capacity config directory');
    }
  }
  if (dirname(scratch) !== backend || !/^\.capacity-e2-scratch-[0-9]+-[0-9]+$/.test(scratch.slice(backend.length + 1)) ||
      readdirSync(scratch).some(name => /^wrangler\.(toml|json|jsonc)$/.test(name))) {
    throw new Error('foreign scratch Wrangler config');
  }
  const wranglerPath = resolve(backend, 'wrangler.toml');
  const wranglerInfo = lstatSync(wranglerPath);
  if (!wranglerInfo.isFile() || wranglerInfo.isSymbolicLink() || wranglerInfo.uid !== process.getuid() ||
      realpathSync(wranglerPath) !== wranglerPath || dirname(wranglerPath) !== backend) {
    throw new Error('tracked Wrangler config ownership/hash drift');
  }
  const wranglerHash = createHash('sha256').update(readFileSync(wranglerPath)).digest('hex');
  if (wranglerHash !== 'c54fae36ad2d0eac2098cff9396f7405fe852b0ca8a82e261945db1987410a3f') {
    throw new Error('tracked Wrangler config ownership/hash drift');
  }
  const resolved = await (typeof original === 'function' ? original(configEnv) : original);
  const workers = resolved.test?.poolOptions?.workers;
  if (resolved.test.setupFiles != null || workers.isolatedStorage !== false ||
      workers.wrangler?.configPath !== './wrangler.toml' ||
      workers.experimental_remoteBindings || workers.miniflare.d1Databases ||
      workers.miniflare.d1Persist || workers.miniflare.persist ||
      JSON.stringify(workers).match(/remoteProxy|experimental_experimental_remote/)) {
    throw new Error('unsafe original capacity config');
  }
  const keys = value => Object.keys(value).sort().join(',');
  if (keys(resolved.test.poolOptions) !== 'workers' || keys(workers) !== 'isolatedStorage,miniflare,wrangler' ||
      keys(workers.wrangler) !== 'configPath' || keys(workers.miniflare) !== 'bindings,compatibilityDate' ||
      keys(workers.miniflare.bindings) !== 'TEST_MIGRATIONS' || workers.miniflare.compatibilityDate !== '2025-06-17') {
    throw new Error('unsafe original non-path pool option');
  }
  const poolBefore = JSON.stringify(resolved.test.poolOptions);
  // The pinned pool resolves this option from the active scratch config directory.
  workers.wrangler.configPath = wranglerPath;
  const { configPath, ...wranglerOther } = workers.wrangler;
  const normalizedPool = { ...resolved.test.poolOptions, workers: {
    ...workers, wrangler: { configPath: './wrangler.toml', ...wranglerOther }
  } };
  if (JSON.stringify(normalizedPool) !== poolBefore) {
    throw new Error('non-path pool option drift');
  }
  const setup = fileURLToPath(new URL('./setup.ts', import.meta.url));
  const result = { ...resolved, test: { ...resolved.test, setupFiles: [setup] } };
  if (result.plugins !== resolved.plugins || result.test.poolOptions !== resolved.test.poolOptions) {
    throw new Error('original plugin/pool identity lost');
  }
  const names = Object.keys(workers.miniflare.bindings).sort();
  const hashes = Object.fromEntries(names.map(k => [k, createHash('sha256').update(JSON.stringify(workers.miniflare.bindings[k])).digest('hex')]));
  writeFileSync(new URL('./config-facts.json', import.meta.url), JSON.stringify({
    inherited_once: true, plugins_same: true, pool_options_same: true,
    pool_options_except_wrangler_path_same: true,
    pool_options_sha256: createHash('sha256').update(poolBefore).digest('hex'),
    wrangler_original_config: './wrangler.toml', wrangler_sha256: wranglerHash,
    test_original: { include: resolved.test.include, testTimeout: resolved.test.testTimeout, hookTimeout: resolved.test.hookTimeout },
    isolatedStorage: workers.isolatedStorage, wrangler_config: workers.wrangler.configPath,
    compatibilityDate: workers.miniflare.compatibilityDate, binding_names: names, binding_hashes: hashes,
    remote_bindings: false, persistent_override: false, setup_path: setup
  }));
  return result;
}
"""

@validation
def materialize_scratch(backend, run, attempt, template, archive):
    backend = Path(backend)
    path = backend / ('.capacity-e2-scratch-' + run + '-' + attempt)
    require(backend.is_dir() and not backend.is_symlink() and backend.absolute() == backend.resolve(), 'unsafe backend root')
    require(re.fullmatch('[0-9]+', run) and re.fullmatch('[0-9]+', attempt), 'unsafe run/attempt')
    require(not path.exists() and not path.is_symlink(), 'scratch already exists')
    path.mkdir(mode=0o700)
    variants = expand_setup(template)
    (path / 'config.ts').write_bytes(CONFIG)
    (path / 'setup.ts').write_bytes(variants['full'])
    return {'path': str(path.absolute()), 'backend': str(backend.absolute()), 'inode': path.stat().st_ino,
            'device': path.stat().st_dev, 'uid': os.getuid(), 'variants': variants}

@validation
def select_projection(owner, projection):
    path = owned_scratch(owner)
    require(projection in ('full','id'), 'unknown projection')
    (path / 'setup.ts').write_bytes(owner['variants'][projection])

@validation
def cleanup_scratch(owner, archive):
    path = owned_scratch(owner)
    archive = Path(archive).absolute()
    require(archive.is_dir() and not archive.is_symlink() and archive.resolve() == archive
            and not archive.resolve().is_relative_to(path.parent)
            and archive.stat().st_uid == owner['uid'], 'unsafe archive')
    for name in REQUIRED_SCRATCH: expect_evidence(archive / Path(name).name)
    errors = []
    for item in sorted(path.iterdir()):
        target = archive / item.name
        try:
            raw = item.read_bytes()
            # Exclusive creation rejects stale files and symlinks without touching them.
            with target.open('xb') as copied: copied.write(raw)
            require(target.is_file() and not target.is_symlink() and target.read_bytes() == raw,
                    'scratch archive copy drift')
            freeze_evidence(target)
            if EXPECTED_EVIDENCE is not None and target.is_relative_to(EVIDENCE_ROOT):
                require(EXPECTED_EVIDENCE.get(str(target.relative_to(EVIDENCE_ROOT))) == hashlib.sha256(raw).hexdigest(),
                        'scratch archive completion hash missing or changed')
        except (ValueError, OSError) as error:
            errors.append(str(error))
        else:
            # Only copied and frozen originals may be discarded on success.
            item.unlink()
    # Failed archival leaves the original bytes and owned directory available.
    require(not errors, 'scratch archive failed: ' + '; '.join(errors))
    path.rmdir()



def owned_scratch(owner):
    path = Path(owner['path'])
    require(path.is_dir() and not path.is_symlink() and path.resolve() == path and
            str(path.parent) == owner['backend'] and path.name.startswith('.capacity-e2-scratch-'), 'scratch path ownership')
    info = path.stat()
    require(info.st_ino == owner['inode'] and info.st_dev == owner['device'] and info.st_uid == owner['uid'] == os.getuid(), 'scratch inode ownership')
    require({'config.ts','setup.ts'} <= {p.name for p in path.iterdir()} <= {'config.ts','setup.ts','config-facts.json'}, 'unexpected scratch file')
    for p in path.iterdir():
        require(not p.is_symlink() and p.is_file() and p.stat().st_uid == owner['uid'], 'unsafe scratch member')
    require((path / 'config.ts').read_bytes() == CONFIG and (path / 'setup.ts').read_bytes() in owner['variants'].values(), 'scratch source drift')
    return path

# Native entry is fail-closed before any local scratch or Node launch.
def main():
    try:
        require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_OS') == 'Linux'
                and sys.platform == 'linux', 'Linux GitHub Actions only')
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--phase', choices=('guard','preflight','abba'), required=True)
        parser.add_argument('--output', type=Path, required=True)
        parser.add_argument('--preflight', type=Path)
        args = parser.parse_args()
        return native(args)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1



# Linux process ownership and log drain; unrelated processes remain untouched.
def process_table():
    result = {}
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit(): continue
        try:
            fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
            result[int(entry.name)] = {'pid': int(entry.name), 'ppid': int(fields[1]), 'pgid': int(fields[2]),
                                      'sid': int(fields[3]), 'start': int(fields[19]), 'state': fields[0]}
        except (OSError, ValueError, IndexError):
            continue
    return result


def run_owned(command, cwd, directory, timeout):
    import ctypes
    require(sys.platform == 'linux' and isinstance(command, list) and command and all(isinstance(x,str) for x in command), 'owned Linux argument vector')
    require(number(timeout) > 0, 'bounded positive timeout')
    require(ctypes.CDLL(None).prctl(36, 1, 0, 0, 0) == 0, 'subreaper unavailable')
    directory = Path(directory)
    baseline = process_table()
    known = {}
    handles = {}
    exited_handles = []
    ownership_errors = []
    unexplained = {}
    events = []
    start = time.monotonic()
    timed_out = False
    spawn_error = None
    process = None
    def alive(fd):
        poller = select.poll(); poller.register(fd, select.POLLIN)
        status = poller.poll(0)
        require(not status or (len(status) == 1 and status[0][0] == fd
                and status[0][1] & select.POLLIN
                and not status[0][1] & ~(select.POLLIN | select.POLLHUP)), 'ambiguous pidfd exit status')
        return not status
    def pin(pid, info, proof):
        fd = None
        try:
            require(hasattr(os, 'P_PIDFD') and hasattr(signal, 'pidfd_send_signal'), 'identity-stable signal/wait unavailable')
            fd = os.pidfd_open(pid, 0)
            # A live pidfd brackets the fresh /proc read: the numeric identity
            # cannot change during that read without the handle becoming dead.
            live = alive(fd)
            current = process_table().get(pid)
            require(current is not None and current['start'] == info['start'], 'generation changed while pinning')
            require((live and alive(fd)) or (proof['kind'] == 'launched' and process.returncode is None
                    and current['ppid'] == os.getpid()), 'generation exited while pinning')
            if proof['kind'] == 'observed-parent':
                if current['ppid'] != proof['pid'] or not alive(handles[proof['pid']]):
                    # Parent exit can race pinning. Only the still-unreaped
                    # private session leader can independently prove this child.
                    proof = {'kind': 'inherited-session', 'pid': process.pid, 'start': known[process.pid]['start']}
            if proof['kind'] == 'inherited-session':
                leader = process_table().get(process.pid)
                require(process.returncode is None and leader is not None and leader['start'] == known[process.pid]['start']
                        and current['sid'] == process.pid, 'session generation lost')
            known[pid] = dict(current, ownership_proof=proof)
            handles[pid] = fd
            return True
        except (OSError, ValueError, AttributeError) as error:
            if fd is not None: os.close(fd)
            ownership_errors.append({'pid': pid, 'error': str(error)})
            return False
    def discover():
        table = process_table()
        changed = True
        while changed:
            changed = False
            for pid, info in table.items():
                if pid in known:
                    if info['start'] != known[pid]['start']:
                        error = {'pid': pid, 'error': 'owned numeric identity reused'}
                        if error not in ownership_errors: ownership_errors.append(error)
                    continue
                proof = None
                if ownership_errors: continue
                if pid == process.pid and info['ppid'] == os.getpid() and info['sid'] == info['pgid'] == pid and pid not in baseline:
                    proof = {'kind': 'launched', 'pid': pid, 'start': info['start']}
                elif info['ppid'] in handles and alive(handles[info['ppid']]) and info['ppid'] in table and table[info['ppid']]['start'] == known[info['ppid']]['start']:
                    proof = {'kind': 'observed-parent', 'pid': info['ppid'], 'start': known[info['ppid']]['start']}
                elif process.pid in known and process.returncode is None and process.pid in table and table[process.pid]['start'] == known[process.pid]['start'] and info['sid'] == process.pid:
                    # Popen has not reaped the pinned session leader, so its
                    # numeric session generation is still reserved. After reap,
                    # only previously pinned descendants retain ownership.
                    proof = {'kind': 'inherited-session', 'pid': process.pid, 'start': known[process.pid]['start']}
                if proof:
                    changed = pin(pid, info, proof) or changed
        for pid, info in table.items():
            if pid in known and info['start'] == known[pid]['start']: continue
            previous = baseline.get(pid)
            if info['ppid'] == os.getpid() and (previous is None or previous['start'] != info['start'] or previous['ppid'] != os.getpid()):
                unexplained.setdefault((pid, info['start']), info)
        return {pid: table[pid] for pid, info in known.items() if pid in table and table[pid]['start'] == info['start']}
    def send(sig):
        live = discover()
        # Never deliver a signal via a numeric PID, even after a fresh snapshot.
        for pid, info in live.items():
            if ownership_errors: break
            if info['state'] == 'Z': continue
            try:
                signal.pidfd_send_signal(handles[pid], sig, None, 0)
                events.append({'pid':pid,'start':info['start'],'signal':sig,'identity':'pidfd'})
            except (OSError, AttributeError) as error:
                ownership_errors.append({'pid':pid,'error':str(error)})
    def reap():
        for pid in known:
            if pid == process.pid: continue
            try: os.waitid(os.P_PIDFD, handles[pid], os.WEXITED | os.WNOHANG)
            except ChildProcessError: pass
            except OSError as error:
                ownership_errors.append({'pid':pid,'error':str(error)})
    with (directory / 'stdout.raw').open('xb') as stdout, (directory / 'stderr.raw').open('xb') as stderr:
        try:
            process = subprocess.Popen(command, cwd=cwd, stdout=stdout, stderr=stderr, start_new_session=True)
        except OSError as error:
            spawn_error = str(error)
        if process is not None:
            discover()  # Retain the launched identity before poll can reap it.
            while process.returncode is None:
                discover()
                if process.pid in handles and not alive(handles[process.pid]):
                    discover()  # Discover the private session before leader reap.
                    process.poll(); break
                if process.pid not in handles and process.poll() is not None: break
                if time.monotonic() - start >= timeout:
                    timed_out = True; send(signal.SIGTERM); break
                time.sleep(0.02)
            if timed_out:
                try: process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    send(signal.SIGKILL)
                    try: process.wait(timeout=2)
                    except subprocess.TimeoutExpired: pass
            code = process.poll()
            remaining = discover()
            leaked = [p for p in remaining if p != process.pid]
            if remaining:
                send(signal.SIGTERM)
                end = time.monotonic() + 0.5
                while discover() and time.monotonic() < end:
                    reap(); time.sleep(0.02)
                if discover(): send(signal.SIGKILL)
                end = time.monotonic() + 2
                while discover() and time.monotonic() < end:
                    reap(); time.sleep(0.02)
            reap()
            remaining = discover()
            # A partial /proc snapshot cannot prove exit. Check every retained
            # generation, including absent descendants, before releasing it.
            for pid, fd in handles.items():
                try:
                    require(not alive(fd), 'retained owned generation still alive')
                    exited_handles.append(fd)
                except (OSError, ValueError) as error:
                    ownership_errors.append({'pid': pid, 'error': str(error)})
            cleanup_ok = not remaining and code is not None and process.pid in known and not unexplained and not ownership_errors
        else:
            code = None; leaked = []; cleanup_ok = True
    # Unproved handles stay pinned until this unsafe runner exits.
    for fd in exited_handles: os.close(fd)
    receipt = {'command': command, 'cwd': str(cwd), 'pid': process.pid if process else None,
               'returncode': code, 'signal': -code if code is not None and code < 0 else None,
               'spawn_error': spawn_error, 'timed_out': timed_out, 'cleanup_ok': cleanup_ok,
               'descendants_observed': [v for k,v in known.items() if process and k != process.pid],
               'unexplained_adopted': list(unexplained.values()),
               'ownership_errors': ownership_errors,
               'descendants_after_exit': leaked, 'cleanup_signals': events,
               'stdout_sha256': hashlib.sha256((directory / 'stdout.raw').read_bytes()).hexdigest(),
               'stderr_sha256': hashlib.sha256((directory / 'stderr.raw').read_bytes()).hexdigest()}
    write_json(directory / 'process.json', receipt)
    return receipt

def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    freeze_evidence(path)

@validation
def audit_framework(stdout, stderr, probe, root):
    """Pinned Vitest 3.2.4 default reporter, non-TTY run with one file.

    Summary counts are emitted by onFinished after pool completion. Suite
    errors have their own stderr banner even if the file/test counts match.
    Strip only SGR colors; terminal editing, invalid UTF8 and partial lines
    fail closed. Never join streams to construct a completion summary.
    """
    facts = {'reporter': 'vitest@3.2.4/default/non-TTY', 'clean': False,
             'stdout_sha256': hashlib.sha256(stdout).hexdigest(),
             'stderr_sha256': hashlib.sha256(stderr).hexdigest(),
             'summary_count': 0, 'summary': None, 'failed_test_counts': [],
             'failed_test_banner_count': 0,
             'additional_error_diagnostics': [], 'errors': []}
    try:
        def normalize(raw):
            text = re.sub(rb'\x1b\[[0-9;]*m', b'', raw).decode('utf-8')
            text = text.replace('\r\n', '\n')
            require(not re.search(r'[\x00-\x08\x0b-\x1f\x7f-\x9f]', text), 'unsupported framework control bytes')
            require(not text or text.endswith('\n'), 'truncated framework log line')
            return text
        out, err = normalize(stdout), normalize(stderr)
        state = probe['original_task_state']
        require(probe['preflight']['capture']['unexpected_log_calls'] == 0,
                'unexpected test logging cannot establish reporter provenance')
        banner = ' RUN  v3.2.4 ' + str(Path(root) / 'backend')
        require(out.splitlines().count(banner) == 1, 'missing or duplicate pinned framework run banner')
        # E2 JSON stays on single lines; strings in the test payload cannot
        # supply reporter lines. All three records must precede completion on stdout.
        ends = []
        for marker in ('CAPACITY_RESULT', 'E2_PREFLIGHT', 'E2_HISTORY_RESULT'):
            matches = list(re.finditer(r'^' + marker + r' [^\n]*\n', out, re.M))
            require(len(matches) == 1 and marker + ' ' not in err, 'framework stdout record provenance')
            ends.append(matches[0].end())
        require(ends == sorted(ends), 'framework record order')
        all_lines = out.splitlines() + err.splitlines()
        facts['failed_test_counts'] = [int(m.group(1)) for line in all_lines
                                      if (m := re.fullmatch(r'⎯+ Failed Tests ([0-9]+) ⎯+', line))]
        facts['failed_test_banner_count'] = sum('Failed Tests' in line for line in all_lines)
        facts['additional_error_diagnostics'] = [line for line in all_lines
                if re.search(r'Failed Suites|Unhandled (?:Errors?|Rejection|Reporter Error)|'
                             r'Vitest caught [0-9]+ unhandled|Error running worker:|'
                             r'^\s*(?:Errors|Type Errors|Source Errors)\s', line)]
        # Reserve every summary label across BOTH streams so duplicate, malformed,
        # misplaced or truncated fragments cannot be ignored beside a valid block.
        labels = ('Test Files', 'Tests', 'Start at', 'Duration')
        for label in labels:
            count = sum(bool(re.match(r'^\s*' + label + r'\s', line)) for line in all_lines)
            if label == 'Test Files': facts['summary_count'] = count
            require(count == 1, 'missing or duplicate framework ' + label)
        result = 'failed' if state == 'fail' else 'passed'
        duration = r'(?:[0-9]+ms|[0-9]+\.[0-9]{2}s)'
        pattern = (r'^ Test Files  1 ' + result + r' \(1\)\n'
                   r'      Tests  1 ' + result + r' \| 1 skipped \(2\)\n'
                   r'   Start at  (?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\n'
                   r'   Duration  ' + duration + r' \(transform ' + duration +
                   r', setup ' + duration + r', collect ' + duration + r', tests ' + duration +
                   r', environment ' + duration + r', prepare ' + duration + r'\)\n\n')
        summaries = list(re.finditer(pattern, out, re.M))
        require(len(summaries) == 1, 'missing or malformed final framework summary/inventory')
        summary = summaries[0]
        facts['summary'] = {'text': summary.group(), 'stdout_start': summary.start(),
                            'files': {result: 1, 'total': 1},
                            'tests': {result: 1, 'skipped': 1, 'total': 2},
                            'unhandled_errors': 0 if not facts['additional_error_diagnostics'] else None}
        require(summary.start() >= max(ends), 'framework summary precedes probe completion')
        require(not out[summary.end():].strip(), 'output after final framework summary')
        require(not re.search(r'^(?:stdout|stderr) \|', out[max(ends):] + err, re.M),
                'unexpected user console output after probe')
        require(not facts['additional_error_diagnostics'], 'additional suite/pool/unhandled framework error')
        require(facts['failed_test_counts'] == ([1] if state == 'fail' else []),
                'failed-test diagnostics contradict selected leaf')
        require(facts['failed_test_banner_count'] == (1 if state == 'fail' else 0)
                and 'Failed Tests' not in out, 'malformed or misplaced failed-test diagnostics')
        facts['clean'] = True
    except (ValueError, UnicodeError) as error:
        facts['errors'].append(str(error))
    return facts

# Control interface: preserve the combined exit and the two raw streams.
def audit_process(receipt, raw, scenario, projection, root):
    stdout, stderr = raw if isinstance(raw, tuple) else (raw, b'')
    raw = stdout + stderr
    value = {'process': receipt, 'original_audit': None, 'probe_audit': None,
             'framework_audit': None, 'audit_errors': []}
    try: value['original_audit'] = audit_original(raw, scenario)
    except ValueError as error: value['audit_errors'].append({'original': str(error)})
    if value['original_audit'] is not None:
        try: value['probe_audit'] = audit_probe(raw, value['original_audit'], scenario, projection, root)
        except ValueError as error: value['audit_errors'].append({'probe': str(error)})
    value['original_failed'] = bool((value['original_audit'] or {}).get('performance_failed')
                                   or (value['probe_audit'] or {}).get('original_task_state') == 'fail')
    if not value['audit_errors']:
        value['framework_audit'] = audit_framework(stdout, stderr, value['probe_audit'], root)
        if not value['framework_audit']['clean']:
            value['audit_errors'].append({'framework': value['framework_audit']['errors']})
        state = value['probe_audit']['original_task_state']
        if value['original_audit']['performance_failed'] and state != 'fail':
            value['audit_errors'].append({'process': 'original task/gates contradiction'})
        if type(receipt['returncode']) is not int or receipt['returncode'] != (1 if state == 'fail' else 0):
            value['audit_errors'].append({'process': 'unexplained or contradictory combined exit'})
    valid = (not value['audit_errors'] and receipt['returncode'] in (0,1) and not receipt['timed_out']
             and receipt['cleanup_ok'] and not receipt['spawn_error'] and not receipt['descendants_after_exit']
             and not receipt.get('unexplained_adopted'))
    value['infrastructure_valid'] = bool(valid)
    value['failed'] = not valid or receipt['returncode'] != 0 or value['original_failed']
    return value

def serial_phase(phase, execute, render):
    require(phase in ('preflight','abba'), 'phase')
    blocks = [('preflight','full')] if phase == 'preflight' else [('01-A','full'),('02-B','id'),('03-B','id'),('04-A','full')]
    value = {'processes': [], 'renderers': [], 'failed': False, 'infrastructure_valid': True}
    for block, arm in blocks:
        results = []
        for scenario in ('p1-4500','plan-6000'):
            audit = execute(block, arm, scenario)
            results.append(audit); value['processes'].append(audit)
            value['failed'] |= audit['failed']
            value['infrastructure_valid'] &= audit['infrastructure_valid']
            if not audit['infrastructure_valid']: break
        renderer = render(block, results)
        value['renderers'].append(renderer)
        value['failed'] |= renderer['failed']
        value['infrastructure_valid'] &= not renderer['failed']
        if not value['infrastructure_valid']: break
    return value

def audit_renderer(receipt, directory):
    directory = Path(directory)
    present = all((directory / name).is_file() and not (directory / name).is_symlink()
                  and (directory / name).stat().st_size > 0 for name in ('measurements.json','RESULTS.md'))
    failed = (receipt['returncode'] != 0 or not receipt['cleanup_ok'] or receipt['timed_out']
              or receipt['spawn_error'] or receipt['descendants_after_exit'] or receipt.get('unexplained_adopted') or not present)
    return {'process': receipt, 'outputs_present': present, 'failed': bool(failed)}

@validation
def resolved_config_guard(facts, backend, manifest):
    require(facts.get('inherited_once') is True and facts.get('plugins_same') is True and facts.get('pool_options_same') is True
            and facts.get('pool_options_except_wrangler_path_same') is True
            and facts.get('isolatedStorage') is False and facts.get('remote_bindings') is False and facts.get('persistent_override') is False
            and facts.get('test_original') == {'include':['tests/capacity/capacity_test.ts'],'testTimeout':600000,'hookTimeout':60000},
            'unsafe resolved config')
    require(facts.get('wrangler_original_config') == './wrangler.toml'
            and facts.get('wrangler_config') == str(Path(backend) / 'wrangler.toml')
            and facts.get('wrangler_sha256') == WRANGLER_SHA256 == manifest['backend/wrangler.toml']['sha256']
            and type(facts.get('pool_options_sha256')) is str
            and re.fullmatch('[0-9a-f]{64}', facts['pool_options_sha256']), 'unsafe resolved config path/hash')

@validation
def preflight_gate(path, identity):
    path = Path(path)
    require(path.is_dir() and not path.is_symlink(), 'missing preflight directory')
    require((path / 'phase.json').is_file(), 'missing preflight infrastructure receipt')
    receipt = parse_json((path / 'phase.json').read_bytes())
    require(receipt['phase'] == 'preflight' and receipt['infrastructure_valid'] is True and len(receipt['processes']) == 2, 'both native preflight scenarios required')
    for key in ('candidate','ref','run','attempt','runtime'):
        require(receipt[key] == identity[key], 'preflight identity/runtime drift')
    require([p['scenario'] for p in receipt['processes']] == ['p1-4500','plan-6000'], 'preflight scenario order')
    require(receipt['smoke_state'] == 'absent', 'preflight liveness state')
    for process in receipt['processes']:
        require(process['infrastructure_valid'] is True, 'invalid native preflight')
        directory = path / 'preflight' / process['scenario']
        streams = []
        for name in ('stdout.raw','stderr.raw'):
            file = directory / name
            require(file.is_file() and not file.is_symlink(), 'missing preflight raw evidence')
            content = file.read_bytes()
            require(hashlib.sha256(content).hexdigest() == process['process'][name.split('.')[0] + '_sha256'], 'preflight raw hash drift')
            streams.append(content)
        audited = audit_process(process['process'], tuple(streams), process['scenario'], '*', Path(__file__).resolve().parents[2])
        require(audited['infrastructure_valid'], 'preflight re-audit failed')
        for key in ('original_audit', 'probe_audit', 'framework_audit', 'original_failed', 'failed', 'audit_errors'):
            require(process.get(key) == audited[key], 'preflight recorded audit drift')
    require(receipt['scratch_cleanup_ok'] is True and receipt['source_guard_after'] is True, 'preflight cleanup/source receipt missing')
    require(receipt['failed'] is any(p['failed'] for p in receipt['processes']), 'preflight failure receipt drift')
    manifest = path / 'artifact-hashes.json'
    require(manifest.is_file() and not manifest.is_symlink(), 'missing finalized preflight hash manifest')
    try:
        hashes = parse_json(manifest.read_bytes())
        verify_artifact_members(path, hashes)
        source = parse_json((path / 'source-before.json').read_bytes())['manifest']
        facts = receipt['resolved_config']
        # Bind the archived resolution to the recorded native cwd and frozen source.
        backend = Path(receipt['processes'][0]['process']['cwd'])
        require(backend.is_absolute() and '..' not in backend.parts, 'unsafe preflight backend path')
        resolved_config_guard(facts, backend, source)
        require(parse_json((path / 'scratch/config-facts.json').read_bytes()) == facts, 'preflight config archive drift')
        for process in receipt['processes']:
            directory = path / 'preflight' / process['scenario']
            require(process['process']['cwd'] == str(backend)
                    and parse_json((directory / 'resolved-config.json').read_bytes()) == facts, 'preflight resolved config drift')
    except OSError as error:
        raise ValueError('missing finalized preflight evidence') from error
    return receipt

# Descriptive inference, separate from all original 100 ms gates.
def describe_effect(processes):
    expected = [(b,s) for b in ('01-A','02-B','03-B','04-A') for s in ('p1-4500','plan-6000')]
    report = {'status': 'incomplete', 'comparisons': [], 'issue19': 'UNRESOLVED',
              'scope': 'post-suite local Workers/D1 only; original window, root cause and production remain unknown',
              'duration_label': 'D1-reported duration includes cursor execution, row conversion/materialization and bookkeeping',
              'bytes_label': 'UTF8 JSON representation, not actual wire/IPC bytes',
              'allocation_limit': 'Bookkeeping allocations can affect later calls; the overhead bound cannot prove absence of later GC interference'}
    if [(p.get('block'),p.get('scenario')) for p in processes] != expected: return report
    if any(not p.get('infrastructure_valid') or not (p.get('framework_audit') or {}).get('clean') for p in processes):
        return report
    by = {(p['block'],p['scenario']): p['probe_audit'] for p in processes}
    for scenario in ('p1-4500','plan-6000'):
        cohort = [by[block,scenario] for block in ('01-A','02-B','03-B','04-A')]
        scenario_plans = [v['history'][stage][arm]['results'] for v in cohort
                          for stage in ('plans_before','plans_after') for arm in ('full','id')]
        scenario_exclusions = []
        if any(plan != scenario_plans[0] for plan in scenario_plans):
            scenario_exclusions.append('scenario-wide SQL plan changed')
        if any(v['original_plan'] != [r['detail'] for r in scenario_plans[0]] for v in cohort):
            scenario_exclusions.append('scenario-wide original emitted plan differs')
        scenario_rows = [sample['meta']['rows_read'] for v in cohort for sample in v['history']['samples']]
        if any(rows != scenario_rows[0] for rows in scenario_rows):
            scenario_exclusions.append('scenario-wide rows_read shifted')
        for a,b in (('01-A','02-B'),('04-A','03-B')):
            full = by[a,scenario]; ident = by[b,scenario]
            fs=full['history']['samples']; bs=ident['history']['samples']
            fm=quantiles([s['wall_ms'] for s in fs]); bm=quantiles([s['wall_ms'] for s in bs])
            small=all(b['json_result_utf8_bytes'] < a['json_result_utf8_bytes'] for a,b in zip(fs,bs))
            difference=fm['p95']-bm['p95']
            ratio=fm['p95']/bm['p95'] if bm['p95'] else None
            support=difference>=5 and (fm['p95']>=1.5*bm['p95']) and small
            exclusions=[]
            d1_full=quantiles([s['d1_reported_duration_ms'] for s in fs])
            d1_id=quantiles([s['d1_reported_duration_ms'] for s in bs])
            bookkeeping_full=quantiles([s['bookkeeping_ms'] for s in fs])
            bookkeeping_id=quantiles([s['bookkeeping_ms'] for s in bs])
            all_plans=[v['history'][stage][arm]['results'] for v in (full,ident) for stage in ('plans_before','plans_after') for arm in ('full','id')]
            if any(plan!=all_plans[0] for plan in all_plans): exclusions.append('SQL plan changed, including possible covering index')
            for v in (full,ident):
                original=v['original_plan']
                if original != [r['detail'] for r in all_plans[0]]: exclusions.append('original emitted plan differs')
            if abs(d1_full['p95']-d1_id['p95'])>1: exclusions.append('D1-reported duration shifted by more than 1 ms')
            if any(a['meta']['rows_read']!=b['meta']['rows_read'] for a,b in zip(fs,bs)): exclusions.append('rows_read shifted')
            if any(a['ordered_ids']!=b['ordered_ids'] for a,b in zip(fs,bs)): exclusions.append('ordered IDs changed')
            if any(v['preflight']['capture']['unexpected_log_calls'] for v in (full,ident)): exclusions.append('unexpected intercepted logging')
            if bookkeeping_full['p95']>max(1,0.25*fm['p95']) or bookkeeping_id['p95']>max(1,0.25*bm['p95']): exclusions.append('bookkeeping p95 exceeds overhead bound')
            if full['preflight']['binding'] != ident['preflight']['binding']: exclusions.append('binding identity facts drifted')
            exclusions.extend(scenario_exclusions)
            report['comparisons'].append({'scenario':scenario,'full_block':a,'id_block':b,'full_wall_ms':fm,'id_wall_ms':bm,
                'absolute_wall_p95_difference_ms':difference,'relative_wall_p95_ratio':ratio,
                'id_smaller_every_call':small,'criterion_support':support,'exclusions':exclusions,
                'full_d1_reported_duration_ms':d1_full,'id_d1_reported_duration_ms':d1_id,
                'd1_p95_difference_ms':d1_full['p95']-d1_id['p95'],
                'full_bookkeeping_ms':bookkeeping_full,'id_bookkeeping_ms':bookkeeping_id,
                'full_json_result_utf8_bytes':quantiles([s['json_result_utf8_bytes'] for s in fs]),
                'id_json_result_utf8_bytes':quantiles([s['json_result_utf8_bytes'] for s in bs])})
    if any(c['exclusions'] for c in report['comparisons']):
        report['status']='confounded'
        return report
    report['status'] = 'result-size-related path support' if all(c['criterion_support'] for c in report['comparisons']) else 'no reproduced effect'
    return report

PINNED = {'@cloudflare/vitest-pool-workers':'0.8.49','vitest':'3.2.4','@vitest/runner':'3.2.7',
          'miniflare':'4.20250617.5','workerd':'1.20250617.0','@cloudflare/workers-types':'4.20250620.0','wrangler':'4.23.0'}
LEDGER = None
SAFETY = None
EVIDENCE_ROOT = None
EXPECTED_EVIDENCE = None

def expect_evidence(path):
    path = Path(path).absolute()
    if EXPECTED_EVIDENCE is not None and path.is_relative_to(EVIDENCE_ROOT):
        EXPECTED_EVIDENCE.setdefault(str(path.relative_to(EVIDENCE_ROOT)), None)

def freeze_evidence(path, digest=None):
    """Record completion bytes once; later filesystem inventories cannot reset them."""
    path = Path(path).absolute()
    expect_evidence(path)
    if EXPECTED_EVIDENCE is None or not path.is_relative_to(EVIDENCE_ROOT): return
    name = str(path.relative_to(EVIDENCE_ROOT))
    if EXPECTED_EVIDENCE[name] is not None: return
    if digest is None and path.is_file() and not path.is_symlink():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
    EXPECTED_EVIDENCE[name] = digest

def owned_command(command, cwd, directory, timeout):
    if SAFETY is not None:
        require(SAFETY['owned_processes_gone'], 'owned command prohibited after unsafe cleanup')
    for name in ('stdout.raw','stderr.raw','process.json'): expect_evidence(Path(directory) / name)
    try:
        receipt = run_owned(command, cwd, directory, timeout)
    except (ValueError, OSError):
        if SAFETY is not None: SAFETY['owned_processes_gone'] = False
        raise
    if SAFETY is not None:
        SAFETY['owned_processes_gone'] &= receipt['cleanup_ok'] and not receipt.get('ownership_errors')
    for name in ('stdout','stderr'):
        freeze_evidence(Path(directory) / (name + '.raw'), receipt[name + '_sha256'])
    freeze_evidence(Path(directory) / 'process.json')
    return receipt

def read_command(command, cwd, expected=(0,)):
    require(LEDGER is not None, 'command evidence ledger unavailable')
    directory = LEDGER / ('%03d' % (len(list(LEDGER.iterdir())) + 1))
    directory.mkdir()
    receipt = owned_command(command, cwd, directory, 30)
    require(receipt['returncode'] in expected and receipt['cleanup_ok'] and not receipt['timed_out']
            and not receipt['spawn_error'] and not receipt['descendants_after_exit'], 'guard command failed: ' + str(command))
    return (directory / 'stdout.raw').read_bytes(), receipt['returncode']

def tree_entries(raw):
    result = {}
    for row in raw.split(b'\0'):
        if not row: continue
        header, path = row.split(b'\t',1)
        mode, kind, blob = header.decode().split()
        require(kind=='blob' and mode in ('100644','100755'), 'unexpected tracked mode/object')
        result[path.decode()] = {'mode':mode,'blob':blob}
    return result

def source_guard(root, candidate, ref, scratch=None):
    def git(*args, expected=(0,)):
        return read_command(['git',*args], root, expected)
    head=git('rev-parse','HEAD')[0].decode().strip()
    branch=git('branch','--show-current')[0].decode().strip()
    backend=git('rev-parse','HEAD:backend')[0].decode().strip()
    descends=git('merge-base','--is-ancestor',BASE,head,expected=(0,1))[1]==0
    changes=[tuple(line.split('\t')) for line in git('diff','--name-status',BASE,head)[0].decode().splitlines()]
    guard_identity(head=head,branch=branch,backend_tree=backend,candidate=candidate,ref=ref,descends=descends,changes=changes)
    expected=tree_entries(git('ls-tree','-rz',head)[0])
    manifest=verify_tracked_bytes(root,expected)
    git('diff','--exit-code','HEAD')
    for line in git('status','--porcelain','--untracked-files=all')[0].decode().splitlines():
        require(scratch is not None and line.startswith('?? '+str(Path(scratch).relative_to(root))+'/'), 'unexpected source status')
    return {'head':head,'branch':branch,'backend_tree':backend,'manifest':manifest}

def runtime(root, dependencies=True):
    version=read_command(['node','--version'],root)[0].decode().strip()
    require(re.fullmatch(r'v22\.[0-9]+\.[0-9]+',version), 'native Node major must be 22')
    import tomllib
    wrangler_bytes=(root/'backend/wrangler.toml').read_bytes()
    wrangler=tomllib.loads(wrangler_bytes.decode())
    databases=wrangler['d1_databases']
    require(len(databases)==1 and databases[0]['binding']=='DB' and not databases[0].get('remote'), 'original local D1 binding guard')
    binding_facts={'names':[d['binding'] for d in databases],
                   'hashes':{d['binding']:hashlib.sha256(json.dumps(d,sort_keys=True).encode()).hexdigest() for d in databases},
                   'wrangler_sha256':hashlib.sha256(wrangler_bytes).hexdigest()}
    value={'node':version,'platform':sys.platform,'python':sys.version.split()[0],'runner_image':os.environ.get('ImageOS'),
           'runner_image_version':os.environ.get('ImageVersion'),'wrangler_binding_facts':binding_facts,'dependencies':{}}
    if dependencies:
        lock=parse_json((root/'backend/package-lock.json').read_bytes())
        for name,version in PINNED.items():
            installed=parse_json((root/'backend/node_modules'/name/'package.json').read_bytes())['version']
            require(installed==version==lock['packages']['node_modules/'+name]['version'], 'dependency pin drift: '+name)
            value['dependencies'][name]=installed
    return value

def no_smoke():
    found=[]
    for pid,info in process_table().items():
        try:
            args=(Path('/proc')/str(pid)/'cmdline').read_bytes().split(b'\0')
        except OSError: continue
        unsafe=any(re.search(rb'(^|/)(workerd|wrangler)([/.]|$)|^smoke:production$|(^|/)start-android-api\.sh$',arg) for arg in args if arg)
        if unsafe: found.append(dict(info,command_sha256=hashlib.sha256(b'\0'.join(args)).hexdigest()))
    require(not found, 'unexpected external wrangler/workerd/smoke process; no unowned process was killed')
    return {'smoke_state':'absent','process_inventory':list(process_table().values()),'unexpected_external_processes':found}

def safe_output(output, root):
    temp=Path(os.environ.get('RUNNER_TEMP',''))
    require(temp.is_absolute() and temp.is_dir(), 'RUNNER_TEMP required')
    output=Path(output).absolute()
    require(output.is_relative_to(temp.resolve()) and not output.is_relative_to(root) and output.resolve()==output,
            'evidence must be outside checkout in RUNNER_TEMP without symlinks')
    require(not output.exists(), 'output already exists; no stale evidence reuse')
    output.mkdir(parents=True,mode=0o700)
    return output

def snapshot_member(name):
    return 'source-snapshots/source__' + name.replace('/', '__')

def artifact_hashes(output):
    # Keep upload-artifact's credential/hidden-file exclusion. Only the fixed
    # tracked source allowlist gets visible snapshot names.
    return {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(output.rglob('*')) if p.is_file() and not p.is_symlink()
            and all(not part.startswith('.') for part in p.relative_to(output).parts)
            and p.relative_to(output) != Path('artifact-hashes.json')}

@validation
def verify_artifact_members(output, hashes, expected=None):
    """Check local or downloaded evidence against all recorded and required members."""
    output = Path(output).absolute()
    require(output.is_dir() and output.resolve() == output and not output.is_symlink(), 'unsafe artifact directory')
    for p in output.rglob('*'):
        require(not p.is_symlink() and (p.is_file() or p.is_dir())
                and all(not part.startswith('.') for part in p.relative_to(output).parts), 'unsafe artifact member')
    require(type(hashes) is dict and all(type(name) is str and type(digest) is str
            and re.fullmatch('[0-9a-f]{64}', digest) for name, digest in hashes.items()), 'invalid artifact hash manifest')
    source = parse_json((output / 'source-before.json').read_bytes())['manifest']
    required = {snapshot_member(name): source[name]['sha256'] for name in SNAPSHOT_SOURCES}
    require(len(required) == len(SNAPSHOT_SOURCES) and required.keys() <= hashes.keys(), 'missing required source snapshot')
    require(all(hashes[name] == digest for name, digest in required.items()), 'source snapshot hash drift')
    if expected is None:
        receipt = parse_json((output / 'evidence-receipt.json').read_bytes())
        expected = receipt['expected']
        require(set(receipt['final_members']) | {'evidence-receipt.json'} <= hashes.keys(), 'missing final evidence receipt/result')
    require(type(expected) is dict and expected.keys() <= hashes.keys(), 'missing completed evidence member')
    # Native finalization has no phase.json yet; its predeclared ledger and
    # archive identify the requirement. Reopening also binds the saved phase,
    # so removing the entire archive and its receipt entries cannot erase it.
    phase_path = output / 'phase.json'
    scratch_required = ((output / 'scratch').exists() or any(name in expected for name in REQUIRED_SCRATCH)
                        or (phase_path.is_file() and parse_json(phase_path.read_bytes())['phase'] != 'guard'))
    if scratch_required:
        require(set(REQUIRED_SCRATCH) <= expected.keys() and set(REQUIRED_SCRATCH) <= hashes.keys(),
                'missing required scratch completion evidence')
    require(all(digest is not None and hashes[name] == digest for name, digest in expected.items()),
            'completed evidence byte/hash drift')
    require(hashes == artifact_hashes(output), 'artifact member/hash completeness')

def native(args):
    global LEDGER, SAFETY, EVIDENCE_ROOT, EXPECTED_EVIDENCE
    root=Path(__file__).resolve().parents[2]
    output=safe_output(args.output,root)
    EVIDENCE_ROOT = output; EXPECTED_EVIDENCE = {}
    for name in ('source-before.json','source-after.json','phase.json'): expect_evidence(output / name)
    if args.phase != 'guard':
        for name in REQUIRED_SCRATCH: expect_evidence(output / name)
    LEDGER=output/'commands'; LEDGER.mkdir()
    candidate=os.environ.get('GITHUB_SHA','')
    ref=os.environ.get('GITHUB_REF','')
    run=os.environ.get('GITHUB_RUN_ID',''); attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
    identity={'candidate':candidate,'ref':ref,'run':run,'attempt':attempt}
    phase={'phase':args.phase,**identity,'processes':[],'infrastructure_valid':False,'failed':True,
           'scratch_cleanup_ok':False,'source_guard_after':False,'artifact_members_ok':False,
           'smoke_state':'absent','owned_processes_gone':True}
    SAFETY = phase
    owner=None
    baseline_config=None
    effect=None
    try:
        initial=source_guard(root,candidate,ref)
        write_json(output/'source-before.json',initial)
        snapshots=output/'source-snapshots'; snapshots.mkdir()
        for name in SNAPSHOT_SOURCES:
            (output/snapshot_member(name)).write_bytes((root/name).read_bytes())
            freeze_evidence(output/snapshot_member(name), initial['manifest'][name]['sha256'])
        phase['runtime']=runtime(root,args.phase!='guard')
        identity['runtime']=phase['runtime']
        if args.phase=='guard':
            phase.update(infrastructure_valid=True,failed=False,source_guard_after=True,scratch_cleanup_ok=True)
        else:
            if args.phase=='abba':
                require(args.preflight is not None, 'ABBA requires native preflight receipt')
                previous=preflight_gate(args.preflight,identity)
                baseline_config=previous['resolved_config']
                phase['preflight_original_failed']=previous['failed']
            archive=output/'scratch'; archive.mkdir()
            template=(root/ALLOWED[0]).read_bytes()
            variants=expand_setup(template)
            for arm,raw in variants.items():
                (archive/(arm+'-setup.ts')).write_bytes(raw); freeze_evidence(archive/(arm+'-setup.ts'))
            write_json(archive/'variants.json',{arm:hashlib.sha256(raw).hexdigest() for arm,raw in variants.items()})
            owner=materialize_scratch(root/'backend',run,attempt,template,archive)
            write_json(output/'scratch-ownership.json',{k:v for k,v in owner.items() if k!='variants'})
            def execute(block,arm,scenario):
                nonlocal baseline_config
                directory=output/block/scenario; directory.mkdir(parents=True)
                try:
                    no_smoke_receipt=no_smoke(); write_json(directory/'liveness-before.json',no_smoke_receipt)
                    before=source_guard(root,candidate,ref,owner['path']); write_json(directory/'source-before.json',before)
                    require(runtime(root)==identity['runtime'], 'runtime drift before process')
                    select_projection(owner,arm)
                    owned_scratch(owner)
                    command=['./node_modules/.bin/vitest','run','--config',str(Path(owner['path']).relative_to(root/'backend')/'config.ts'),'-t',scenario]
                    receipt=owned_command(command,root/'backend',directory,900)
                    require(receipt['cleanup_ok'], 'owned process/log drain incomplete; schedule stopped')
                    streams=tuple((directory/name).read_bytes() for name in ('stdout.raw','stderr.raw'))
                    raw=b''.join(streams)
                    audited=audit_process(receipt,streams,scenario,'*' if arm=='full' else 'id',root)
                    after=source_guard(root,candidate,ref,owner['path']); write_json(directory/'source-after.json',after)
                    require(before==after and runtime(root)==identity['runtime'], 'source/runtime drift after process')
                    write_json(directory/'liveness-after.json',no_smoke())
                    facts_path=Path(owner['path'])/'config-facts.json'
                    require(facts_path.is_file() and not facts_path.is_symlink(), 'missing native resolved config facts')
                    facts=parse_json(facts_path.read_bytes())
                    resolved_config_guard(facts, root/'backend', before['manifest'])
                    if baseline_config is None: baseline_config=facts
                    require(facts==baseline_config, 'resolved binding/config drift')
                    write_json(directory/'resolved-config.json',facts)
                    audited.update(block=block,scenario=scenario,projection=arm)
                    write_json(directory/'audit.json',audited)
                    for marker,name in [('E2_PREFLIGHT','preflight-record.json'),('E2_HISTORY_RESULT','history-record.json')]:
                        try:
                            (directory/name).write_bytes(record(raw,marker)[0]+b'\n'); freeze_evidence(directory/name)
                        except ValueError: pass
                    return audited
                except (ValueError,OSError) as error:
                    failure={'block':block,'scenario':scenario,'projection':arm,'failed':True,'infrastructure_valid':False,'error':str(error)}
                    write_json(directory/'infrastructure-error.json',failure)
                    return failure
            def render(block,values):
                directory=output/block
                if not phase['owned_processes_gone']:
                    result={'failed':True,'skipped_unsafe_cleanup':True,'error':'renderer not launched before owned log drain'}
                    write_json(directory/'renderer-unsafe.json',result)
                    return result
                if any(not value['infrastructure_valid'] for value in values):
                    result={'failed':True,'skipped':True,'reason':'scenario infrastructure invalid',
                            'evidence_status':'INCOMPLETE','scenarios':values}
                    write_json(directory/'renderer-skipped.json',result)
                    return result
                raw=b''
                for scenario in ('p1-4500','plan-6000'):
                    for name in ('stdout.raw','stderr.raw'):
                        path=directory/scenario/name
                        if path.is_file(): raw+=path.read_bytes()
                log=directory/'capacity.log'; log.write_bytes(raw)
                freeze_evidence(log)
                renderer=directory/'renderer'; renderer.mkdir()
                for name in ('measurements.json','RESULTS.md'): expect_evidence(directory/name)
                try:
                    before=source_guard(root,candidate,ref,owner['path'])
                    write_json(renderer/'source-before.json',before)
                except (ValueError,OSError) as error:
                    result={'failed':True,'skipped':True,'reason':'renderer source validation failed',
                            'evidence_status':'INCOMPLETE','error':str(error)}
                    write_json(directory/'renderer-skipped.json',result)
                    return result
                receipt=owned_command(['node','tests/capacity/report.mjs',str(log)],root/'backend',renderer,120)
                for name in ('measurements.json','RESULTS.md'): freeze_evidence(directory/name)
                result=audit_renderer(receipt,directory)
                write_json(renderer/'audit.json',result)
                return result
            outcome=serial_phase(args.phase,execute,render)
            phase.update(outcome)
            # Schedule membership is independent of the surviving filesystem,
            # and raw-byte expectations also come from completion receipts.
            for p in phase['processes']:
                directory = output/p['block']/p['scenario']
                if p['infrastructure_valid']:
                    for name in ('liveness-before.json','source-before.json','source-after.json','liveness-after.json',
                                 'resolved-config.json','audit.json','preflight-record.json','history-record.json','process.json'):
                        expect_evidence(directory/name)
                    for name in ('stdout','stderr'):
                        freeze_evidence(directory/(name+'.raw'), p['process'].get(name+'_sha256'))
            blocks = ('preflight',) if args.phase == 'preflight' else ('01-A','02-B','03-B','04-A')
            for block, renderer in zip(blocks, phase['renderers']):
                if not renderer['failed']:
                    for name in ('capacity.log','measurements.json','RESULTS.md','renderer/stdout.raw',
                                 'renderer/stderr.raw','renderer/process.json','renderer/audit.json'):
                        expect_evidence(output/block/name)
            phase['resolved_config']=baseline_config
            total_expected=2 if args.phase=='preflight' else 8
            phase['infrastructure_valid'] &= len(phase['processes'])==total_expected
            phase['original_samples']=sum(p.get('original_audit',{}).get('original_samples',0) for p in phase['processes'] if p.get('original_audit'))
            phase['original_gate_evaluations']=sum(p.get('original_audit',{}).get('gate_evaluations',0) for p in phase['processes'] if p.get('original_audit'))
            phase['probe_calls']=sum(p.get('probe_audit',{}).get('probe_calls',0) for p in phase['processes'] if p.get('probe_audit'))
            expected=(1020,52,60) if args.phase=='preflight' else (4080,208,240)
            if (phase['original_samples'],phase['original_gate_evaluations'],phase['probe_calls'])!=expected:
                phase.update(infrastructure_valid=False,failed=True)
            if args.phase=='abba' and phase['infrastructure_valid']:
                effect=describe_effect(phase['processes'])
            if phase.get('preflight_original_failed'): phase['failed']=True
    except (ValueError,OSError) as error:
        phase.update(infrastructure_valid=False,failed=True,error=str(error))
    finally:
        if not phase['owned_processes_gone']:
            phase.update(infrastructure_valid=False,failed=True)
        if owner is not None and phase['owned_processes_gone']:
            try:
                cleanup_scratch(owner,output/'scratch'); phase['scratch_cleanup_ok']=True
            except (ValueError,OSError) as error:
                phase.update(infrastructure_valid=False,failed=True,cleanup_error=str(error))
        try:
            after=source_guard(root,candidate,ref)
            write_json(output/'source-after.json',after)
            phase['source_guard_after']=True
        except (ValueError,OSError) as error:
            phase.update(infrastructure_valid=False,failed=True,source_guard_after=False,source_error=str(error))
        # Final conclusions/receipts are written after validation. All preceding
        # members retain their completion hashes in this independent receipt.
        pending = {'phase.json'}
        expected = {name:digest for name,digest in EXPECTED_EVIDENCE.items() if name not in pending}
        write_json(output/'evidence-receipt.json', {'expected':expected,
                   'final_members':['phase.json'] + (['descriptive-effect.json'] if effect is not None else [])})
        hashes=artifact_hashes(output)
        try:
            verify_artifact_members(output,hashes,{**expected,
                                    'evidence-receipt.json':EXPECTED_EVIDENCE['evidence-receipt.json']})
            phase['artifact_members_ok']=True
        except (ValueError,OSError) as error:
            phase.update(infrastructure_valid=False,failed=True,artifact_error=str(error))
        renderer_skipped=any(result.get('skipped') for result in phase.get('renderers', []))
        phase['evidence_status'] = 'COMPLETE' if phase['artifact_members_ok'] and not renderer_skipped else 'INCOMPLETE'
        if effect is not None:
            effect['final_operations']={key:phase[key] is True for key in ('scratch_cleanup_ok','source_guard_after')}
            effect['final_exclusions']=[message for key,message in (
                ('scratch_cleanup_ok','final scratch cleanup failed'),
                ('source_guard_after','final source validation failed')) if not effect['final_operations'][key]]
            effect['artifact_members_ok']=phase['artifact_members_ok']
            effect['evidence_status']=phase['evidence_status']
            if not phase['artifact_members_ok']: effect['final_exclusions'].append('required artifact membership failed')
            effect['final_failure_evidence']={key:phase[key] for key in ('cleanup_error','source_error','artifact_error') if key in phase}
            if effect['final_exclusions']:
                effect['status']='infrastructure-invalid'
                phase.update(infrastructure_valid=False,failed=True)
                for comparison in effect['comparisons']: comparison['exclusions'].extend(effect['final_exclusions'])
            write_json(output/'descriptive-effect.json',effect)
            hashes['descriptive-effect.json']=hashlib.sha256((output/'descriptive-effect.json').read_bytes()).hexdigest()
        write_json(output/'phase.json',phase)
        hashes['phase.json']=hashlib.sha256((output/'phase.json').read_bytes()).hexdigest()
        write_json(output/'artifact-hashes.json',hashes)
    return int(phase['failed'])

if __name__ == '__main__':
    raise SystemExit(main())
