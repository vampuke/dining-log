"""Stdlib dummy controls only; none of these data or commands are native D1 evidence.

E2_TEST_EVIDENCE retains every disposable control directory for an independent rerun.
Run with python3 -B; importing the runner never executes Node or Workers.
"""

import copy
import hashlib
import importlib.util
import json
import os
import select
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


RUNNER = Path(__file__).with_name("capacity-history-e2.py")
spec = importlib.util.spec_from_file_location("capacity_history_e2", RUNNER)
e2 = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = e2
spec.loader.exec_module(e2)

USER = "00000000-0000-4000-8000-000000000001"
SELECTED = {
    "p1-4500": "36b96093-b118-4f24-9243-c225d2902229",
    "plan-6000": "7900e620-0083-4b1b-90b1-18f0665e228b",
}
FIXTURE = {
    "p1-4500": "664bf1e7a42ce7a6c32850a4ba38ef94aafd533eeae2c1b0bef186e972f1bbfc",
    "plan-6000": "5021392ad1d8cec6c9b8a7400cf50b2fe6b0c2e05b2148dfc66e596d52685244",
}
HISTORY = "SELECT * FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 10"
TABLES = ("users", "sessions", "refresh_tokens", "refresh_rotation_results", "recovery_codes",
          "auth_rate_limits", "restaurants", "dishes", "restaurant_search", "visits",
          "change_sets", "revisions", "idempotency_keys", "mutation_guards")
QUERY_IDS = ("updated_page", "rating_page", "aggregate_search_hit", "aggregate_search_miss",
             "restaurant_detail", "restaurant_dishes", "restaurant_visits",
             "detail_children_batch", "entity_revisions_10")


def quantiles(values):
    values = sorted(values)
    return {"min": values[0], "p50": values[14], "p95": values[28], "max": values[29]}


def dummy_original(scenario="p1-4500"):
    """A small invented schema control, never a generated corpus or native sample."""
    selected = SELECTED[scenario]
    revision_count = 11 if scenario == "p1-4500" else 14
    dishes = 5 if scenario == "p1-4500" else 8
    counts = dict.fromkeys(TABLES, 0)
    counts.update(users=1, restaurants=500, dishes=500 * dishes, visits=1500,
                  restaurant_search=500, revisions=13500 if dishes == 5 else 18000,
                  change_sets=13500 if dishes == 5 else 18000)
    deltas = dict.fromkeys(TABLES, 0)
    deltas.update(restaurants=1, dishes=5, visits=3, revisions=10, change_sets=1,
                  restaurant_search=1)
    detail = {"sql": "SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL", "params": [USER, selected]}
    child_dishes = {"sql": "SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC", "params": [USER, selected]}
    visits = {"sql": "SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC", "params": [USER, selected]}
    queries = []
    for name in QUERY_IDS:
        statements = [{"sql": "SELECT id FROM restaurants WHERE user_id=?", "params": [USER]}]
        returned = [20]
        if name == "restaurant_detail": statements, returned = [detail], [1]
        if name == "restaurant_dishes": statements, returned = [child_dishes], [dishes]
        if name == "restaurant_visits": statements, returned = [visits], [3]
        if name == "detail_children_batch": statements, returned = [detail, child_dishes, visits], [1, dishes, 3]
        if name == "aggregate_search_hit": returned = [20]
        if name == "aggregate_search_miss": returned = [0]
        if name == "entity_revisions_10": statements, returned = [{"sql": HISTORY, "params": [USER, "restaurant", selected]}], [10]
        samples = [{"wallMs": 2, "sqlMs": 0.5, "rowsRead": 10, "rowsWritten": 0,
                    "returnedRows": returned[:]} for _ in range(30)]
        queries.append({"id": name, "statements": copy.deepcopy(statements), "samples": samples,
                        "plans": [["CONTROL ONLY plan"] for _ in statements],
                        "wallMs": quantiles([2] * 30), "sqlMs": quantiles([0.5] * 30),
                        "rowsRead": quantiles([10] * 30)})
    base = "/restaurants/" + selected
    paths = ("/restaurants?limit=20", "/restaurants?sort=rating_desc&limit=20",
             "/search?q=%E6%8B%9B%E7%89%8C%E6%B5%8B%E8%AF%95", "/search?q=capacity_missing_51a71c",
             base, base + "/dishes", base + "/visits", base + "/revisions?limit=10")
    return {"schemaVersion": 1, "scenario": {"name": scenario, "restaurants": 500,
            "dishesPerRestaurant": dishes, "visitsPerRestaurant": 3, "restaurantRevisions": revision_count,
            "childRevisions": 2, "objects": 4500 if dishes == 5 else 6000,
            "history": counts["revisions"]}, "repetitions": 30, "p95LimitMs": 100,
            "wallClock": "Date.now (1 ms resolution; Workers performance.now is not used)",
            "seed": {"seed": 0x51a71c, "seedHex": "0x51a71c", "fixedTimeUtc": "2026-10-31T12:00:00.000Z",
                     "fixtureSha256": FIXTURE[scenario]}, "rowCounts": counts,
            "restaurantRevisionRange": {"min": revision_count, "max": revision_count},
            "queries": queries, "actualAPI": [{"path": p, "samples": [2] * 30,
                    "wallMs": quantiles([2] * 30)} for p in paths],
            "write": {"changes": 22, "rowDeltas": deltas, "wallMs": 1, "sqlMs": 0.5,
                      "rowsRead": 1, "rowsWritten": 22},
            "integrity": {"foreignKeyViolations": 0, "quickCheck": "ok"}, "overLimit": []}


def emit(result):
    return b"CAPACITY_RESULT " + json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode() + b"\n"


CONTROL_IDS = [f"00000000-0000-4000-8000-{index:012d}" for index in range(20, 30)]


def dummy_meta():
    return {"duration": 0.5, "size_after": 123, "rows_read": 10, "rows_written": 0,
            "last_row_id": 0, "changed_db": False, "changes": 0}


def dummy_sample(index=1, projection="id"):
    """Representation arithmetic control; the full arm lengths are invented inputs."""
    meta = dummy_meta()
    meta_json = json.dumps(meta, separators=(",", ":"))
    row_bytes = [len(json.dumps({"id": value}, separators=(",", ":")).encode()) for value in CONTROL_IDS]
    if projection == "*": row_bytes = [50000] * 10
    members = [{"key": "success", "key_json_utf8_bytes": 9, "value_json_utf8_bytes": 4},
               {"key": "meta", "key_json_utf8_bytes": 6, "value_json_utf8_bytes": len(meta_json.encode())},
               {"key": "results", "key_json_utf8_bytes": 9, "value_json_utf8_bytes": 2 + 9 + sum(row_bytes)}]
    total = 2 + 2 + sum(x["key_json_utf8_bytes"] + 1 + x["value_json_utf8_bytes"] for x in members)
    return {"index": index, "start_ms": 1000 + 10 * index, "stop_ms": 1002 + 10 * index,
            "wall_ms": 2, "bookkeeping_stop_ms": 1003 + 10 * index, "bookkeeping_ms": 1,
            "d1_reported_duration_ms": 0.5, "meta": meta, "returned_count": 10,
            "ordered_ids": CONTROL_IDS[:], "json_result_utf8_bytes": total,
            "json_result_sha256": hashlib.sha256(b"CONTROL ONLY, not a native D1Result").hexdigest(),
            "json_representation": {"property_order": ["success", "meta", "results"], "members": members,
                                    "row_json_utf8_bytes": row_bytes, "meta_json": meta_json}}


def dummy_attestation(scenario="p1-4500"):
    """Invented safe projections for rejection controls; no DB is created or read."""
    original = dummy_original(scenario)
    selected = SELECTED[scenario]
    revisions = original["scenario"]["restaurantRevisions"]
    fixed = 1793448000000
    reads = []

    def add(label, sql, params, results):
        reads.append({"label": label, "sql": sql, "params": params, "success": True,
                      "results": results, "meta": dummy_meta()})

    add("user", "SELECT id,username,display_name FROM users WHERE id=?", [USER],
        [{"id": USER, "username": "capacity_user", "display_name": "Synthetic capacity account"}])
    add("restaurant", "SELECT id,user_id,version,created_at,updated_at FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL",
        [USER, selected], [{"id": selected, "user_id": USER, "version": revisions,
                           "created_at": fixed + 2000, "updated_at": fixed + 2000 + revisions - 1}])
    for label, table, count, order in (("dishes", "dishes", original["scenario"]["dishesPerRestaurant"], "updated_at"),
                                       ("visits", "visits", 3, "dining_date")):
        rows = [{"id": f"00000000-0000-4000-8000-{100 + i + (50 if label == 'visits' else 0):012d}",
                 "user_id": USER, "restaurant_id": selected, "version": 2,
                 "created_at": fixed + 2020 + i, "updated_at": fixed + 2021 + i} for i in range(count)]
        add(label, f"SELECT id,user_id,restaurant_id,version,created_at,updated_at FROM {table} WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY {order} DESC,id DESC",
            [USER, selected], rows)
    add("selected_revision_count", "SELECT COUNT(*) AS n FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL",
        [USER, "restaurant", selected], [{"n": revisions}])
    post = original["rowCounts"].copy()
    for table, delta in original["write"]["rowDeltas"].items(): post[table] += delta
    post["sessions"] += 1; post["refresh_tokens"] += 1
    for table in TABLES: add("count:" + table, f"SELECT COUNT(*) AS n FROM {table}", [], [{"n": post[table]}])
    appended = "00000000-0000-4000-8000-000000000900"
    change_set = "00000000-0000-4000-8000-000000000901"
    add("appended_restaurant", "SELECT id,user_id,version,created_at,updated_at FROM restaurants WHERE user_id=? AND created_at=?",
        [USER, fixed + 500000], [{"id": appended, "user_id": USER, "version": 2,
                                "created_at": fixed + 500000, "updated_at": fixed + 500001}])
    grouped = []
    for index, entity_type in enumerate(["restaurant"] * 2 + ["dish"] * 5 + ["visit"] * 3):
        grouped.append({"id": f"00000000-0000-4000-8000-{1000 + index:012d}", "user_id": USER,
                        "restaurant_id": appended, "entity_type": entity_type,
                        "entity_id": appended if entity_type == "restaurant" else f"00000000-0000-4000-8000-{1100 + index:012d}",
                        "entity_version_after": index + 1 if entity_type == "restaurant" else 1,
                        "change_set_id": change_set, "created_at": fixed + 500000 + index})
    add("appended_revisions", "SELECT id,user_id,restaurant_id,entity_type,entity_id,entity_version_after,change_set_id,created_at FROM revisions WHERE user_id=? AND restaurant_id=? ORDER BY created_at,id",
        [USER, appended], grouped)
    add("appended_change_set", "SELECT id,user_id,created_at FROM change_sets WHERE user_id=? AND id=?", [USER, change_set],
        [{"id": change_set, "user_id": USER, "created_at": fixed + 500000}])
    columns = ["id", "user_id", "change_set_id", "restaurant_id", "entity_type", "entity_id", "dish_id", "visit_id",
               "action", "entity_version_before", "entity_version_after", "diff_json", "source_revision_id", "search_text",
               "note", "note_search", "metadata_version", "metadata_updated_at", "deleted_at", "created_at"]
    history = []
    for index, value in enumerate(CONTROL_IDS):
        version = revisions - index
        history.append({"id": value, "user_id": USER, "restaurant_id": selected, "entity_type": "restaurant",
                        "entity_id": selected, "change_set_id": f"00000000-0000-4000-8000-{1200 + index:012d}",
                        "entity_version_before": version - 1, "entity_version_after": version, "created_at": fixed + 2000 + version - 1,
                        "diff_json": {"utf16_length": 8000, "utf8_length": 24000, "sha256": "a" * 64},
                        "search_text": {"utf16_length": 7900, "utf8_length": 23700, "sha256": "b" * 64},
                        "review_diff": {"change_count": 1, "field": "review", "before_utf16_length": 3950, "after_utf16_length": 3950},
                        "row_json_utf8_bytes": 50000, "row_sha256": "c" * 64, "column_order": columns[:]})
    add("history_full", HISTORY, [USER, "restaurant", selected], history)
    add("history_id", HISTORY.replace("SELECT *", "SELECT id", 1), [USER, "restaurant", selected], [{"id": v} for v in CONTROL_IDS])
    return {"reads": reads}


def dummy_probe_raw(scenario="p1-4500", projection="id", state="pass"):
    original = emit(dummy_original(scenario))
    payload = original[len(b"CAPACITY_RESULT "):-1]
    task = {"filepath": str(RUNNER.parents[2] / "backend/tests/capacity/capacity_test.ts"),
            "pool": "threads", "leaves": [
                {"id": "control-selected", "name": scenario + ": exact corpus, measured SQL, atomic complete write, p95 <= 100ms", "mode": "run", "state": state},
                {"id": "control-skipped", "name": ("plan-6000" if scenario == "p1-4500" else "p1-4500") + ": exact corpus, measured SQL, atomic complete write, p95 <= 100ms", "mode": "skip", "state": "skip"}]}
    capture = {"emit_count": 1, "unexpected_log_calls": 0, "wrapper_owned": True, "wrapper_restored": True,
               "payload_sha256": hashlib.sha256(payload).hexdigest(), "payload_utf8_bytes": len(payload),
               "raw_emit_sha256": hashlib.sha256(original[:-1]).hexdigest()}
    preflight = {"schemaVersion": 1, "scenario": scenario, "projection": projection, "original_task": task,
                 "capture": capture, "binding": {"saved_db_same": True, "provided_names": ["DB", "TEST_MIGRATIONS"]},
                 "attestation_before": dummy_attestation(scenario)}
    measurement = {"full_sql": HISTORY, "id_sql": HISTORY.replace("SELECT *", "SELECT id", 1),
                   "params": [USER, "restaurant", SELECTED[scenario]],
                   "sql": HISTORY if projection == "*" else HISTORY.replace("SELECT *", "SELECT id", 1)}

    def plans(label):
        return {arm: {"label": label + ":" + arm, "sql": "EXPLAIN QUERY PLAN " + measurement[arm + "_sql"],
                      "params": measurement["params"], "success": True, "meta": dummy_meta(),
                      "results": [{"id": 4, "parent": 0, "notused": 0, "detail": "CONTROL ONLY plan"}]} for arm in ("full", "id")}

    history = {"schemaVersion": 1, "scenario": scenario, "projection": projection, "original_task": task,
               "measurement": measurement, "plans_before": plans("explain_before"), "plans_after": plans("explain_after"),
               "attestation_after": dummy_attestation(scenario), "samples": [dummy_sample(i, projection) for i in range(1, 31)]}
    return original, preflight, history


def probe_log(original, preflight, history):
    state = preflight['original_task']['leaves'][0]['state']
    backend = str(Path(preflight['original_task']['filepath']).parents[2])
    return ((' RUN  v3.2.4 ' + backend + '\n\n').encode() + original +
            b"E2_PREFLIGHT " + json.dumps(preflight, separators=(",", ":")).encode() +
            b"\nE2_HISTORY_RESULT " + json.dumps(history, separators=(",", ":")).encode() + b"\n" +
            framework_summary(state))


def framework_summary(state):
    """Pinned default reporter stdout, synthetic durations; never native evidence."""
    result = 'failed' if state == 'fail' else 'passed'
    return (f'\n Test Files  1 {result} (1)\n'
            f'      Tests  1 {result} | 1 skipped (2)\n'
            '   Start at  15:00:00\n'
            '   Duration  1.23s (transform 10ms, setup 10ms, collect 10ms, tests 1.10s, environment 0ms, prepare 10ms)\n\n').encode()


def framework_stderr(state):
    if state != 'fail': return b''
    return (('\n' + '⎯' * 7 + ' Failed Tests 1 ' + '⎯' * 7 + '\n\n').encode()
            + b' FAIL  tests/capacity/capacity_test.ts > CONTROL selected leaf\n'
            b'AssertionError: CONTROL p95 exceeds 100ms\n'
            b'---------- [1/1] ----------\n\n')


def control_streams(raw):
    task = e2.record(raw, 'E2_PREFLIGHT')[1]['original_task']
    return raw, framework_stderr(next(l['state'] for l in task['leaves'] if l['mode'] == 'run'))


def framework_mutations(stdout, stderr):
    """Complete records remain intact; only final framework evidence changes."""
    prefix = stdout[:stdout.index(b'\n Test Files')]
    summary = stdout[len(prefix):]
    return {
        'missing': (prefix, stderr),
        'truncated': (stdout[:-4], stderr),
        'duplicate': (stdout + summary, stderr),
        'malformed': (stdout.replace(b'   Duration  ', b'   Duration  bogus '), stderr),
        'extra-test': (stdout.replace(b'1 failed | 1 skipped (2)', b'2 failed | 1 skipped (3)'), stderr),
        'extra-file': (stdout.replace(b'1 failed (1)', b'2 failed (2)'), stderr),
        'suite-error': (stdout, stderr + '⎯⎯ Failed Suites 1 ⎯⎯\n'.encode()),
        'unhandled-after-summary': (stdout, stderr + '⎯⎯ Unhandled Errors ⎯⎯\nError: Failed to run tests\n'.encode()),
        'error-count': (stdout.replace(b'   Start at ', b'     Errors  1 error\n   Start at '), stderr),
        'split-stream-summary': (prefix + summary[:summary.index(b'   Duration')], stderr + summary[summary.index(b'   Duration'):]),
        'stderr-summary': (prefix, stderr + summary),
        'terminal-edit': (stdout.replace(b'   Duration', b'\x1b[2K   Duration'), stderr),
        'carriage-return': (stdout.replace(b'   Duration', b'\r   Duration'), stderr),
        'invalid-utf8': (stdout + b'\xff\n', stderr),
        'wrong-version': (stdout.replace(b'v3.2.4', b'v3.2.7'), stderr),
        'missing-failed-test-banner': (stdout, b''),
        'duplicate-failed-test-banner': (stdout, stderr + stderr),
        'extra-failed-test-banner': (stdout, stderr.replace(b'Failed Tests 1', b'Failed Tests 2')),
        'malformed-failed-test-banner': (stdout, stderr + b'Failed Tests bogus\n'),
        'misplaced-failed-test-banner': (stdout.replace(b'\n Test Files', stderr + b'\n Test Files'), b''),
        'post-probe-console': (stdout.replace(b'\n Test Files', b'\nstdout | CONTROL payload\n\n Test Files'), stderr),
        'post-summary-output': (stdout + b'CONTROL later output\n', stderr),
    }


def exit_probe_raw(scenario='p1-4500', state='pass', gate_failed=False):
    original, preflight, history = dummy_probe_raw(scenario, '*', state)
    if gate_failed:
        result = json.loads(original[len(b'CAPACITY_RESULT '):])
        query = result['queries'][0]
        for sample in query['samples']: sample['wallMs'] = 101
        query['wallMs'] = quantiles([s['wallMs'] for s in query['samples']])
        original = emit(result)
        payload = original[len(b'CAPACITY_RESULT '):-1]
        preflight['capture'].update(payload_sha256=hashlib.sha256(payload).hexdigest(),
                                    payload_utf8_bytes=len(payload),
                                    raw_emit_sha256=hashlib.sha256(original[:-1]).hexdigest())
    return probe_log(original, preflight, history)


EXIT_STATES = ((1, 'pass', False, False), (0, 'pass', False, True),
               (1, 'fail', False, True), (1, 'fail', True, True),
               (1, 'pass', True, False), (0, 'fail', False, False),
               (0, 'fail', True, False), (0, 'pass', True, False))


def supportive_control_cohort():
    """Fixed, fully audited dummy cohort; these are never native measurements."""
    processes = []
    for block, projection in (('01-A', '*'), ('02-B', 'id'), ('03-B', 'id'), ('04-A', '*')):
        for scenario in ('p1-4500', 'plan-6000'):
            original, preflight, history = dummy_probe_raw(scenario, projection)
            for sample in history['samples']:
                sample['start_ms'] = 1000 + 20 * sample['index']
                sample['wall_ms'] = 10 if projection == '*' else 2
                sample['stop_ms'] = sample['start_ms'] + sample['wall_ms']
                sample['bookkeeping_stop_ms'] = sample['stop_ms'] + sample['bookkeeping_ms']
            receipt = {'returncode': 0, 'timed_out': False, 'cleanup_ok': True,
                       'spawn_error': None, 'descendants_after_exit': []}
            audited = e2.audit_process(receipt, control_streams(probe_log(original, preflight, history)), scenario, projection, RUNNER.parents[2])
            e2.require(audited['infrastructure_valid'], 'invalid supportive control fixture')
            audited.update(block=block, scenario=scenario)
            processes.append(audited)
    return processes


class EmitControls(unittest.TestCase):
    def test_complete_first_thirty_and_all_original_gates(self):
        result = e2.audit_original(emit(dummy_original()), "p1-4500")
        self.assertIsInstance(result, dict)
        self.assertIn("original_samples", result)
        self.assertEqual(result["original_samples"], 510)
        self.assertEqual(result["gate_evaluations"], 26)
        self.assertFalse(result["performance_failed"])

    def test_missing_duplicate_and_malformed_emit_rejected(self):
        good = emit(dummy_original())
        for raw in (b"no emit\n", good + good, b"CAPACITY_RESULT {broken}\n",
                    b'CAPACITY_RESULT {"schemaVersion":1,"schemaVersion":1}\n'):
            with self.subTest(raw=raw[:35]), self.assertRaises(ValueError):
                e2.audit_original(raw, "p1-4500")

    def test_incomplete_direct_api_and_duplicate_group_rejected(self):
        for mutate in (lambda x: x["queries"][0]["samples"].pop(),
                       lambda x: x["actualAPI"][0]["samples"].pop(0),
                       lambda x: x["queries"].__setitem__(0, x["queries"][1]),
                       lambda x: x["queries"][0]["samples"][0].pop("rowsRead")):
            value = dummy_original(); mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                e2.audit_original(emit(value), "p1-4500")

    def test_wrong_scenario_sql_params_and_corpus_rejected(self):
        mutations = (
            lambda x: x["queries"][-1]["statements"][0].__setitem__("sql", HISTORY + "; DELETE FROM revisions"),
            lambda x: x["queries"][-1]["statements"][0].__setitem__("params", [USER, "restaurant"]),
            lambda x: x["seed"].__setitem__("fixtureSha256", "0" * 64),
            lambda x: x["write"]["rowDeltas"].__setitem__("sessions", 1),
            lambda x: x["rowCounts"].__setitem__("revisions", 1),
            lambda x: x.__setitem__("p95LimitMs", 101),
            lambda x: x.__setitem__("repetitions", 29),
        )
        for mutate in mutations:
            value = dummy_original(); mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                e2.audit_original(emit(value), "p1-4500")
        with self.assertRaises(ValueError): e2.audit_original(emit(dummy_original("plan-6000")), "p1-4500")

    def test_writeful_nonfinite_and_boolean_samples_rejected(self):
        for key, bad in (("rowsWritten", 1), ("wallMs", float("nan")), ("sqlMs", True), ("rowsRead", -1)):
            value = dummy_original(); value["queries"][0]["samples"][0][key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError):
                e2.audit_original(emit(value), "p1-4500")

    def test_nearest_rank_29_and_raw_payload_bytes_are_preserved(self):
        value = dummy_original()
        sample_values = [2] * 28 + [126, 147]
        query = value["queries"][-1]
        for sample, wall in zip(query["samples"], sample_values): sample["wallMs"] = wall
        query["wallMs"] = quantiles(sample_values); value["overLimit"] = ["entity_revisions_10"]
        raw = emit(value)
        audit = e2.audit_original(raw, "p1-4500")
        self.assertTrue(audit["performance_failed"])
        self.assertEqual(next(g for g in audit["gates"] if g["id"] == "entity_revisions_10" and g["metric"] == "wallMs")["p95"], 126)
        self.assertEqual(audit["payload_sha256"], hashlib.sha256(raw[len(b"CAPACITY_RESULT "):-1]).hexdigest())
        self.assertEqual(audit["result"]["queries"][-1]["samples"][0]["wallMs"], 2)

    def test_wrong_reported_quantile_cannot_hide_a_failed_gate(self):
        value = dummy_original(); value["queries"][0]["samples"][28]["wallMs"] = 129
        value["queries"][0]["samples"][29]["wallMs"] = 147
        with self.assertRaises(ValueError): e2.audit_original(emit(value), "p1-4500")


class SampleControls(unittest.TestCase):
    def audit(self, value, projection="id"):
        return e2.audit_sample(value, 1, CONTROL_IDS, dict.fromkeys(CONTROL_IDS, 50000), projection)

    def test_complete_id_and_full_representation_arithmetic(self):
        self.audit(dummy_sample())
        self.audit(dummy_sample(projection="*"), "*")

    def test_order_and_duplicate_ids_cannot_pass_set_equality(self):
        for ids in (CONTROL_IDS[::-1], CONTROL_IDS[:-1] + [CONTROL_IDS[0]], CONTROL_IDS[:-1]):
            value = dummy_sample(); value["ordered_ids"] = ids
            with self.subTest(ids=ids), self.assertRaises(ValueError): self.audit(value)

    def test_incomplete_index_count_and_metadata_rejected(self):
        for mutate in (lambda x: x.pop("start_ms"), lambda x: x.__setitem__("index", 2),
                       lambda x: x.__setitem__("returned_count", 9), lambda x: x["meta"].pop("size_after")):
            value = dummy_sample(); mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError): self.audit(value)

    def test_every_read_write_indicator_is_required_and_zero(self):
        for key, value in (("rows_written", 1), ("changes", 1), ("changed_db", True),
                           ("duration", float("inf")), ("rows_read", -1), ("changes", False)):
            sample = dummy_sample(); sample["meta"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.audit(sample)

    def test_clock_and_bookkeeping_reversals_or_inconsistent_wall_rejected(self):
        for key, value in (("start_ms", 1013), ("wall_ms", 3), ("bookkeeping_stop_ms", 1011),
                           ("bookkeeping_ms", 0), ("stop_ms", True), ("d1_reported_duration_ms", 2)):
            sample = dummy_sample(); sample[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.audit(sample)

    def test_byte_metric_requires_complete_exact_json_components(self):
        for mutate in (lambda x: x.__setitem__("json_result_utf8_bytes", x["json_result_utf8_bytes"] + 1),
                       lambda x: x["json_representation"]["row_json_utf8_bytes"].pop(),
                       lambda x: x["json_representation"]["members"][1].__setitem__("value_json_utf8_bytes", 1),
                       lambda x: x["json_representation"].__setitem__("meta_json", "{}"),
                       lambda x: x["json_representation"]["property_order"].reverse()):
            value = dummy_sample(); mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError): self.audit(value)

    def test_full_rows_match_attested_lengths_and_id_rows_have_only_id_bytes(self):
        for projection in ("id", "*"):
            value = dummy_sample(projection=projection)
            value["json_representation"]["row_json_utf8_bytes"][0] += 1
            with self.subTest(projection=projection), self.assertRaises(ValueError): self.audit(value, projection)


class ProbeControls(unittest.TestCase):
    def audit(self, parts, scenario="p1-4500", projection="id"):
        original, preflight, history = parts
        return e2.audit_probe(probe_log(*parts), e2.audit_original(original, scenario), scenario, projection, RUNNER.parents[2])

    def test_complete_post_suite_attestation_and_first_thirty(self):
        value = self.audit(dummy_probe_raw())
        self.assertIsInstance(value, dict)
        self.assertIn("probe_calls", value)
        self.assertEqual(value["probe_calls"], 30)
        self.assertEqual(value["reference_ids"], CONTROL_IDS)
        self.assertEqual(value["original_task_state"], "pass")
        self.assertEqual(self.audit(dummy_probe_raw("plan-6000", "*"), "plan-6000", "*")["probe_calls"], 30)

    def test_missing_duplicate_malformed_probe_emit_and_partial_samples_rejected(self):
        parts = dummy_probe_raw(); raw = probe_log(*parts); audit = e2.audit_original(parts[0], "p1-4500")
        for broken in (parts[0], raw + b"E2_PREFLIGHT {}\n", raw.replace(b"E2_HISTORY_RESULT ", b"E2_HISTORY_RESULT broken "),
                       raw + b"E2_HISTORY_ERROR {}\n"):
            with self.subTest(raw=broken[-40:]), self.assertRaises(ValueError):
                e2.audit_probe(broken, audit, "p1-4500", "id", RUNNER.parents[2])
        parts[2]["samples"].pop(0)
        with self.assertRaises(ValueError): self.audit(parts)

    def test_receiver_capture_binding_and_finished_leaf_are_prerequisites(self):
        for mutate in (lambda p: p[1]["capture"].__setitem__("payload_sha256", "0" * 64),
                       lambda p: p[1]["capture"].__setitem__("wrapper_owned", False),
                       lambda p: p[1]["capture"].__setitem__("wrapper_restored", False),
                       lambda p: p[1]["binding"].__setitem__("saved_db_same", False),
                       lambda p: p[1]["original_task"]["leaves"][0].__setitem__("state", "run"),
                       lambda p: p[1]["original_task"].__setitem__("pool", "forks")):
            parts = dummy_probe_raw(); mutate(parts)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError): self.audit(parts)

    def test_count_session_write_group_and_safe_identity_fail_closed(self):
        for label, field, bad in (("count:sessions", "n", 0), ("count:revisions", "n", 1),
                                  ("user", "username", "other"), ("restaurant", "version", 2),
                                  ("appended_restaurant", "updated_at", 0), ("appended_revisions", "entity_version_after", 7)):
            parts = dummy_probe_raw()
            next(r for r in parts[1]["attestation_before"]["reads"] if r["label"] == label)["results"][0][field] = bad
            with self.subTest(label=label), self.assertRaises(ValueError): self.audit(parts)
        parts = dummy_probe_raw()
        next(r for r in parts[1]["attestation_before"]["reads"] if r["label"] == "user")["results"][0]["password_hash"] = "CONTROL ONLY must never be archived"
        with self.assertRaises(ValueError): self.audit(parts)

    def test_history_order_versions_content_and_post_probe_drift_rejected(self):
        for mutate in (lambda rows: rows.reverse(), lambda rows: rows[0].__setitem__("entity_version_after", 1),
                       lambda rows: rows[0]["diff_json"].__setitem__("utf16_length", 12)):
            parts = dummy_probe_raw()
            mutate(next(r for r in parts[1]["attestation_before"]["reads"] if r["label"] == "history_full")["results"])
            with self.subTest(mutate=mutate), self.assertRaises(ValueError): self.audit(parts)
        parts = dummy_probe_raw()
        next(r for r in parts[2]["attestation_after"]["reads"] if r["label"] == "history_full")["results"][0]["row_sha256"] = "d" * 64
        with self.assertRaises(ValueError): self.audit(parts)

    def test_writeful_attestation_or_explain_and_ordered_id_projection_rejected(self):
        parts = dummy_probe_raw(); parts[1]["attestation_before"]["reads"][0]["meta"]["changes"] = 1
        with self.assertRaises(ValueError): self.audit(parts)
        parts = dummy_probe_raw(); parts[2]["plans_after"]["id"]["meta"]["changed_db"] = True
        with self.assertRaises(ValueError): self.audit(parts)
        parts = dummy_probe_raw()
        next(r for r in parts[1]["attestation_before"]["reads"] if r["label"] == "history_id")["results"].reverse()
        with self.assertRaises(ValueError): self.audit(parts)

    def test_original_failed_leaf_is_retained_without_invented_exit(self):
        value = self.audit(dummy_probe_raw(state="fail"))
        self.assertEqual(value["original_task_state"], "fail")
        self.assertNotIn("original_exit", value)


class DisposableControls(unittest.TestCase):
    def setUp(self):
        configured = os.environ.get("E2_TEST_EVIDENCE")
        if configured:
            evidence = Path(configured).absolute()
            evidence.mkdir(parents=True, exist_ok=True)
            self.root = Path(tempfile.mkdtemp(prefix=self.__class__.__name__ + "-", dir=evidence))
        else:
            self.temporary = tempfile.TemporaryDirectory(prefix="e2-control-")
            self.addCleanup(self.temporary.cleanup)
            self.root = Path(self.temporary.name)
        (self.root / "CONTROL_ONLY.txt").write_text("Stdlib dummy control. No native Workers/D1 or performance evidence.\n")


class SourceScratchControls(DisposableControls):
    def test_exact_ref_sha_tree_and_only_four_new_files_are_required(self):
        candidate = "1" * 40
        valid = {"head": candidate, "branch": e2.BRANCH, "backend_tree": e2.BACKEND_TREE,
                 "candidate": candidate, "ref": e2.REF, "descends": True,
                 "changes": [("A", name) for name in e2.ALLOWED]}
        e2.guard_identity(**valid)
        for key, bad in (("head", "2" * 40), ("branch", "master"), ("ref", "refs/heads/master"),
                         ("backend_tree", "0" * 40), ("descends", False),
                         ("changes", [("M", name) for name in e2.ALLOWED]),
                         ("changes", valid["changes"] + [("A", "backend/new-file.ts")])):
            value = valid.copy(); value[key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): e2.guard_identity(**value)

    def test_source_bytes_modes_and_symlink_replacement_reject_without_restore(self):
        source = self.root / "original.txt"; source.write_bytes(b"frozen original\n"); source.chmod(0o644)
        expected = {"original.txt": {"mode": "100644", "blob": hashlib.sha1(b"blob 16\0frozen original\n").hexdigest()}}
        before = e2.verify_tracked_bytes(self.root, expected)
        self.assertIn("original.txt", before)
        self.assertEqual(before["original.txt"]["sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
        source.write_bytes(b"changed\n")
        with self.assertRaises(ValueError): e2.verify_tracked_bytes(self.root, expected)
        self.assertEqual(source.read_bytes(), b"changed\n")
        source.write_bytes(b"frozen original\n"); source.chmod(0o755)
        with self.assertRaises(ValueError): e2.verify_tracked_bytes(self.root, expected)
        target = self.root / "target.txt"; target.write_bytes(b"target kept\n")
        source.unlink(); source.symlink_to(target)
        with self.assertRaises(ValueError): e2.verify_tracked_bytes(self.root, expected)
        self.assertEqual(target.read_bytes(), b"target kept\n")

    def test_variants_change_exactly_one_projection_token(self):
        template = b"// CONTROL ONLY\nconst projection = '__E2_PROJECTION__';\n"
        variants = e2.expand_setup(template)
        self.assertEqual(set(variants), {"full", "id"})
        self.assertEqual(variants["full"], b"// CONTROL ONLY\nconst projection = '*';\n")
        self.assertEqual(variants["id"], b"// CONTROL ONLY\nconst projection = 'id';\n")
        for broken in (b"missing", template + template):
            with self.assertRaises(ValueError): e2.expand_setup(broken)

    def scratch(self):
        backend = self.root / "backend"; backend.mkdir()
        (backend / 'wrangler.toml').write_bytes((RUNNER.parents[2] / 'backend/wrangler.toml').read_bytes())
        archive = self.root / "archive"; archive.mkdir()
        return e2.materialize_scratch(backend, "123", "2", b"const projection = '__E2_PROJECTION__';\n", archive)

    def test_exclusive_owned_scratch_cleanup_preserves_source(self):
        owner = self.scratch(); path = Path(owner["path"])
        e2.select_projection(owner, "id")
        self.assertIn(b"'id'", (path / "setup.ts").read_bytes())
        original = self.root / "backend" / "original.txt"; original.write_text("unchanged")
        e2.cleanup_scratch(owner, self.root / "archive")
        self.assertFalse(path.exists()); self.assertEqual(original.read_text(), "unchanged")
        self.assertTrue((self.root / "archive" / "config.ts").exists())

    def test_preexisting_symlink_unowned_or_extra_scratch_paths_are_never_deleted(self):
        owner = self.scratch(); path = Path(owner["path"])
        with self.assertRaises(ValueError):
            e2.materialize_scratch(path.parent, "123", "2", b"__E2_PROJECTION__", self.root / "archive")
        target = self.root / "target.txt"; target.write_text("kept")
        (path / "setup.ts").unlink(); (path / "setup.ts").symlink_to(target)
        with self.assertRaises(ValueError): e2.cleanup_scratch(owner, self.root / "archive")
        self.assertEqual(target.read_text(), "kept")
        (path / "setup.ts").unlink(); (path / "setup.ts").write_bytes(owner["variants"]["full"])
        (path / "unexpected").write_text("kept")
        with self.assertRaises(ValueError): e2.cleanup_scratch(owner, self.root / "archive")
        self.assertTrue((path / "unexpected").exists())
        (path / "unexpected").unlink()
        changed_owner = owner.copy(); changed_owner["inode"] += 1
        with self.assertRaises(ValueError): e2.cleanup_scratch(changed_owner, self.root / "archive")
        self.assertTrue(path.exists())

    def test_native_cli_is_rejected_locally_before_any_scratch_or_node_launch(self):
        env = os.environ.copy(); env.pop("GITHUB_ACTIONS", None)
        command = [sys.executable, "-B", str(RUNNER), "--phase", "preflight", "--output", str(self.root / "native-forbidden")]
        result = subprocess.run(command, capture_output=True, env=env)
        (self.root / "boundary.stdout").write_bytes(result.stdout); (self.root / "boundary.stderr").write_bytes(result.stderr)
        (self.root / "boundary.exit").write_text(str(result.returncode) + "\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Linux GitHub Actions only", result.stderr)
        self.assertFalse((self.root / "native-forbidden").exists())


class ProcessControls(DisposableControls):
    def cleanup_proven_fixture(self, proof):
        current = e2.process_table().get(proof['pid'])
        if current and current['start'] == proof['start'] and current['state'] != 'Z':
            fd = os.pidfd_open(proof['pid'])
            try:
                poller = select.poll(); poller.register(fd, select.POLLIN)
                current = e2.process_table().get(proof['pid'])
                if not poller.poll(0) and current and current['start'] == proof['start']:
                    signal.pidfd_send_signal(fd, signal.SIGTERM)
            finally: os.close(fd)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            try:
                if os.waitpid(proof['pid'], os.WNOHANG)[0]: return
            except ChildProcessError: return
            time.sleep(.005)
        self.fail('test owner could not reap its bounded fixture')

    def test_unavailable_identity_handle_closes_without_numeric_fallback(self):
        directory = self.root / 'no-pidfd'; directory.mkdir()
        with mock.patch.object(os, 'pidfd_open', side_effect=OSError('CONTROL pidfd unavailable')), \
             mock.patch.object(os, 'kill', side_effect=AssertionError('numeric fallback forbidden')):
            receipt = e2.run_owned([sys.executable, '-B', '-c', 'import time; time.sleep(.05)'],
                                   self.root, directory, 1)
        self.assertFalse(receipt['cleanup_ok'])
        self.assertTrue(receipt['ownership_errors'])
        self.assertFalse(receipt['cleanup_signals'])

    def reuse_race(self, after_reap=False):
        # Deterministic PID reuse is infeasible on the host. This OS seam models
        # exit/reap/replacement at signal delivery; it never signals host PIDs.
        leader, child, foreign_child = 410001, 410002, 410003
        def info(pid, start, ppid, sid=leader):
            return dict(pid=pid, start=start, ppid=ppid, sid=sid, pgid=sid, state='S')
        original = {leader: info(leader, 10, os.getpid()), child: info(child, 11, leader)}
        foreign = {leader: info(leader, 20, os.getpid()),
                   foreign_child: info(foreign_child, 21, leader)} if after_reap else {
                       child: info(child, 20, os.getpid(), child)}
        state = {'tables': 0, 'reused': False, 'foreign_signals': [], 'pinned_signals': []}
        process = mock.Mock(pid=leader, returncode=None)
        def poll():
            if after_reap or state['reused']:
                process.returncode = 0; state['reused'] = True
                return 0
            return None
        process.poll.side_effect = poll
        process.wait.side_effect = lambda **_: poll()
        def table():
            state['tables'] += 1
            if state['tables'] == 1: return {}
            if state['reused']: return foreign
            return {leader: original[leader]} if after_reap else original
        handles = {}
        def pin(pid, flags=0):
            handles[pid + 100] = (pid, table()[pid]['start'])
            return pid + 100
        class Poll:
            def register(self, fd, *_): self.fd = fd
            def poll(self, *_):
                pid, start = handles[self.fd]
                return [(self.fd, select.POLLIN)] if after_reap or state['reused'] or pid not in original else []
        def numeric(pid, sig):
            if not after_reap and pid == leader: return
            state['reused'] = True
            state['foreign_signals'].append({'pid': pid, 'signal': sig})
        def stable(fd, sig, *_):
            if not after_reap and handles[fd][0] == leader: return
            # The proven child exits and is reaped after the discovery snapshot.
            state['reused'] = True
            state['pinned_signals'].append({'handle_generation': handles[fd], 'signal': sig})
            raise ProcessLookupError('controlled original generation exited')
        directory = self.root / 'reuse'; directory.mkdir()
        with mock.patch.object(e2, 'process_table', side_effect=table), \
             mock.patch.object(e2.subprocess, 'Popen', return_value=process), \
             mock.patch.object(os, 'kill', side_effect=numeric), \
             mock.patch.object(os, 'pidfd_open', side_effect=pin), \
             mock.patch.object(signal, 'pidfd_send_signal', side_effect=stable), \
             mock.patch.object(select, 'poll', Poll), \
             mock.patch.object(os, 'close'), mock.patch.object(os, 'waitid', return_value=None):
            receipt = e2.run_owned(['CONTROL-only'], self.root, directory, .0001)
        (self.root / 'reuse-trace.json').write_text(json.dumps(state, indent=2))
        self.assertFalse(state['foreign_signals'], 'no signal may reach a replacement generation')
        self.assertNotIn(foreign_child, [p['pid'] for p in receipt['descendants_observed']],
                         'reaped leader numeric session cannot promote foreign descendants')
        self.assertFalse(receipt['cleanup_ok'], 'lost generation must stop interpretation')

    def test_pid_reuse_between_discovery_and_delivery_never_signals_foreign(self):
        self.reuse_race()

    def test_reaped_leader_session_reuse_never_promotes_foreign(self):
        self.reuse_race(after_reap=True)

    def test_new_unrelated_descendant_actually_adopted_is_never_owned_or_signaled(self):
        # This parent exists before run_owned's baseline. It forks only after the
        # launched command's marker, and records independent fixture ancestry.
        child_code = """
import json,os,signal,sys,time
from pathlib import Path
root=Path(sys.argv[1]); adopter=int(sys.argv[2])
def stopped(sig,frame):
    (root/'fixture-signals.txt').write_text(str(sig)+'\\n')
    sys.exit(0)
signal.signal(signal.SIGTERM,stopped)
deadline=time.monotonic()+5
while os.getppid()!=adopter and time.monotonic()<deadline: time.sleep(.005)
(root/'adopted.json').write_text(json.dumps({'pid':os.getpid(),'ppid':os.getppid()}))
time.sleep(8)
"""
        parent_code = """
import json,os,subprocess,sys,time
from pathlib import Path
root=Path(sys.argv[1])
(root/'parent-ready').write_text(str(os.getpid()))
deadline=time.monotonic()+5
while not (root/'launched').exists() and time.monotonic()<deadline: time.sleep(.005)
assert (root/'launched').exists()
child=subprocess.Popen([sys.executable,'-B','-c',sys.argv[3],str(root),sys.argv[2]])
fields=Path('/proc',str(child.pid),'stat').read_text().rsplit(')',1)[1].split()
assert int(fields[1])==os.getpid()
(root/'fixture-owned.json').write_text(json.dumps({'pid':child.pid,'ppid':os.getpid(),'start':int(fields[19])}))
"""
        unrelated = subprocess.Popen([sys.executable, '-B', '-c', parent_code,
                                      str(self.root), str(os.getpid()), child_code],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def cleanup_fixture():
            # Only the test's Popen parent and its independently recorded child.
            if unrelated.poll() is None: unrelated.terminate()
            unrelated.wait(timeout=2)
            proof = self.root / 'fixture-owned.json'
            if proof.exists():
                owned = json.loads(proof.read_text())
                self.assertEqual(owned['ppid'], unrelated.pid)
                self.cleanup_proven_fixture(owned)
        self.addCleanup(cleanup_fixture)
        deadline = time.monotonic() + 3
        while not (self.root / 'parent-ready').exists() and time.monotonic() < deadline: time.sleep(.005)
        self.assertTrue((self.root / 'parent-ready').exists(), 'fixture parent must predate ownership baseline')
        directory = self.root / 'adoption'; directory.mkdir()
        command = [sys.executable, '-B', '-c', """
import sys,time
from pathlib import Path
root=Path(sys.argv[1]); (root/'launched').write_text('owned command started')
deadline=time.monotonic()+3
while not (root/'adopted.json').exists() and time.monotonic()<deadline: time.sleep(.005)
assert (root/'adopted.json').exists()
""", str(self.root)]
        receipt = e2.run_owned(command, self.root, directory, 4)
        proof = json.loads((self.root / 'fixture-owned.json').read_text())
        adopted = json.loads((self.root / 'adopted.json').read_text())
        self.assertEqual(adopted, {'pid': proof['pid'], 'ppid': os.getpid()}, 'must exercise actual subreaper adoption')
        self.assertEqual(receipt['returncode'], 0, 'launched harmless command must complete')
        self.assertNotIn(proof['pid'], [v['pid'] for v in receipt['cleanup_signals']],
                         'runner must never signal an unrelated adopted descendant')
        self.assertFalse((self.root / 'fixture-signals.txt').exists())
        self.assertNotIn(proof['pid'], [v['pid'] for v in receipt['descendants_observed']])
        self.assertFalse(receipt['cleanup_ok'], 'unexplained adoption must close infrastructure')
        self.assertIn(proof['pid'], [v['pid'] for v in receipt['unexplained_adopted']])
        self.assertEqual(e2.process_table()[proof['pid']]['start'], proof['start'])

    def test_observed_owned_descendant_can_escape_session_and_still_drains(self):
        child_code = """
import json,os,sys,time
from pathlib import Path
root=Path(sys.argv[1]); deadline=time.monotonic()+3
while not (root/'observed').exists() and time.monotonic()<deadline: time.sleep(.005)
assert (root/'observed').exists()
os.setsid(); (root/'escaped').write_text(str(os.getsid(0)))
time.sleep(5)
"""
        command = [sys.executable, '-B', '-c', """
import json,os,subprocess,sys,time
from pathlib import Path
root=Path(sys.argv[1])
child=subprocess.Popen([sys.executable,'-B','-c',sys.argv[2],str(root)])
fields=Path('/proc',str(child.pid),'stat').read_text().rsplit(')',1)[1].split()
parent_fields=Path('/proc',str(os.getpid()),'stat').read_text().rsplit(')',1)[1].split()
(root/'owned-child.json').write_text(json.dumps({'pid':child.pid,'ppid':os.getpid(),'start':int(fields[19]),'parent_start':int(parent_fields[19])}))
deadline=time.monotonic()+3
while not (root/'escaped').exists() and time.monotonic()<deadline: time.sleep(.005)
assert (root/'escaped').exists()
""", str(self.root), child_code]
        original_table = e2.process_table
        def observed_table():
            table = original_table()
            proof_path = self.root / 'owned-child.json'
            if proof_path.exists():
                proof = json.loads(proof_path.read_text())
                child = table.get(proof['pid'])
                if child and child['start'] == proof['start'] and child['ppid'] == proof['ppid']:
                    (self.root / 'observed').write_text('real parent edge observed')
            return table
        def cleanup_fixture():
            proof_path = self.root / 'owned-child.json'
            if proof_path.exists():
                proof = json.loads(proof_path.read_text())
                self.cleanup_proven_fixture(proof)
        self.addCleanup(cleanup_fixture)
        directory = self.root / 'escaped-child'; directory.mkdir()
        with mock.patch.object(e2, 'process_table', observed_table):
            receipt = e2.run_owned(command, self.root, directory, 4)
        proof = json.loads((self.root / 'owned-child.json').read_text())
        self.assertEqual(int((self.root / 'escaped').read_text()), proof['pid'], 'child really changed session')
        descendant = next(v for v in receipt['descendants_observed'] if v['pid'] == proof['pid'])
        self.assertEqual(descendant['ownership_proof'], {'kind': 'observed-parent', 'pid': proof['ppid'], 'start': proof['parent_start']})
        self.assertTrue(receipt['cleanup_ok'])
        self.assertFalse(receipt['unexplained_adopted'])
        self.assertNotIn(proof['pid'], original_table())

    def test_actual_exit_stdout_stderr_and_cleanup_are_retained(self):
        directory = self.root / 'process'; directory.mkdir()
        command = [sys.executable, '-B', '-c', "import sys; print('CONTROL stdout'); print('CONTROL stderr',file=sys.stderr); sys.exit(7)"]
        value = e2.run_owned(command, self.root, directory, 5)
        self.assertEqual(value.get('returncode'), 7)
        self.assertTrue(value.get('cleanup_ok'))
        self.assertEqual((directory / 'stdout.raw').read_bytes(), b'CONTROL stdout\n')
        self.assertEqual((directory / 'stderr.raw').read_bytes(), b'CONTROL stderr\n')
        self.assertEqual(json.loads((directory / 'process.json').read_text())['returncode'], 7)

    def test_timeout_terminates_owned_child_and_preserves_raw_failure(self):
        directory = self.root / 'timeout'; directory.mkdir()
        command = [sys.executable, '-B', '-c', "import signal; print('CONTROL before timeout',flush=True); signal.pause()"]
        real_popen = subprocess.Popen
        owned = []
        def ready_popen(*args, **kwargs):
            started = time.monotonic()
            process = real_popen(*args, **kwargs)
            owned.append(process)
            def cleanup_owned():
                if process.poll() is None: process.kill()
                process.wait(timeout=2)
            self.addCleanup(cleanup_owned)
            stdout = Path(kwargs['stdout'].name)
            deadline = started + 5
            # run_owned starts its clock before Popen; return only after the
            # real child flushed and the unchanged 100 ms deadline elapsed.
            # signal.pause keeps it alive until the runner signals it.
            while time.monotonic() < deadline:
                self.assertIsNone(process.poll(), 'owned child exited before timeout readiness')
                if b'CONTROL before timeout\n' in stdout.read_bytes() and time.monotonic() - started >= 0.1:
                    return process
                time.sleep(.005)
            self.fail('owned child did not flush CONTROL before timeout within 5s')
        with mock.patch.object(e2.subprocess, 'Popen', side_effect=ready_popen):
            value = e2.run_owned(command, self.root, directory, 0.1)
        self.assertTrue(value.get('timed_out'))
        self.assertNotEqual(value.get('returncode'), 0)
        self.assertTrue(value.get('cleanup_ok'))
        self.assertIn(b'CONTROL before timeout', (directory / 'stdout.raw').read_bytes())
        self.assertEqual(len(owned), 1)
        self.assertEqual(value['pid'], owned[0].pid)
        self.assertIn(value['signal'], (signal.SIGTERM, signal.SIGKILL))
        self.assertTrue(value['cleanup_signals'])
        self.assertEqual({event['pid'] for event in value['cleanup_signals']}, {owned[0].pid})
        self.assertTrue(all(event['identity'] == 'pidfd' for event in value['cleanup_signals']))

    def test_owned_descendant_is_drained_and_unowned_process_survives(self):
        unrelated = subprocess.Popen([sys.executable, '-B', '-c', 'import time; time.sleep(4)'])
        def cleanup_unrelated():
            if unrelated.poll() is None: unrelated.terminate()
            unrelated.wait(timeout=2)
        self.addCleanup(cleanup_unrelated)
        directory = self.root / 'descendant'; directory.mkdir()
        command = [sys.executable, '-B', '-c', "import subprocess,sys; subprocess.Popen([sys.executable,'-B','-c','import time; time.sleep(3)']); print('CONTROL parent exit')"]
        value = e2.run_owned(command, self.root, directory, 1)
        self.assertTrue(value.get('descendants_observed'))
        self.assertTrue(value.get('cleanup_ok'))
        self.assertIsNone(unrelated.poll())
        unrelated.terminate(); unrelated.wait(timeout=2)


class ExitControls(DisposableControls):
    def test_framework_completion_mutations_close_selected_failure(self):
        streams = control_streams(exit_probe_raw(state='fail', gate_failed=True))
        receipt = dict(returncode=1, timed_out=False, cleanup_ok=True, spawn_error=None, descendants_after_exit=[])
        clean = e2.audit_process(receipt, streams, 'p1-4500', '*', RUNNER.parents[2])
        self.assertTrue(clean['framework_audit']['clean'])
        self.assertTrue(clean['infrastructure_valid'])
        for name, changed in framework_mutations(*streams).items():
            with self.subTest(name=name):
                audited = e2.audit_process(receipt, changed, 'p1-4500', '*', RUNNER.parents[2])
                self.assertEqual(audited['probe_audit']['probe_calls'], 30)
                self.assertTrue(audited['original_failed'])
                self.assertFalse(audited['framework_audit']['clean'])
                self.assertTrue(audited['framework_audit']['errors'])
                self.assertFalse(audited['infrastructure_valid'])
                self.assertEqual(audited['process']['returncode'], 1)

    def test_clean_framework_pass_and_failure_accept_sgr_and_crlf_without_changing_bytes(self):
        for state in ('pass', 'fail'):
            stdout, stderr = control_streams(exit_probe_raw(state=state, gate_failed=state == 'fail'))
            for color in (False, True):
                with self.subTest(state=state, color=color):
                    colored_stdout = stdout.replace(b' RUN  v3.2.4 ', b'\x1b[36m RUN \x1b[0m v3.2.4 ')
                    colored_stdout = colored_stdout.replace(b' Test Files  ', b'\x1b[2m Test Files \x1b[0m ')
                    colored_stderr = b''.join(b'\x1b[31m' + line + b'\x1b[0m\n' for line in stderr.splitlines())
                    streams = tuple(s.replace(b'\n', b'\r\n') for s in (colored_stdout, colored_stderr)) if color else (stdout, stderr)
                    receipt = dict(returncode=1 if state == 'fail' else 0, timed_out=False,
                                   cleanup_ok=True, spawn_error=None, descendants_after_exit=[])
                    audited = e2.audit_process(receipt, streams, 'p1-4500', '*', RUNNER.parents[2])
                    self.assertTrue(audited['infrastructure_valid'], audited['audit_errors'])
                    facts = audited['framework_audit']
                    self.assertTrue(facts['clean'])
                    self.assertEqual(facts['summary_count'], 1)
                    self.assertEqual(facts['summary']['tests']['total'], 2)
                    self.assertEqual(facts['stdout_sha256'], hashlib.sha256(streams[0]).hexdigest())
                    self.assertEqual(facts['stderr_sha256'], hashlib.sha256(streams[1]).hexdigest())

    def test_payload_summary_strings_and_unexpected_logging_cannot_authorize_completion(self):
        original, preflight, history = dummy_probe_raw(projection='*')
        history['control_payload'] = framework_summary('pass').decode()
        raw = probe_log(original, preflight, history)
        raw = raw[:raw.index(b'\n Test Files')]
        receipt = dict(returncode=0, timed_out=False, cleanup_ok=True, spawn_error=None, descendants_after_exit=[])
        audited = e2.audit_process(receipt, (raw, b''), 'p1-4500', '*', RUNNER.parents[2])
        self.assertFalse(audited['infrastructure_valid'])
        preflight['capture']['unexpected_log_calls'] = 1
        audited = e2.audit_process(receipt, (probe_log(original, preflight, history), b''), 'p1-4500', '*', RUNNER.parents[2])
        self.assertFalse(audited['framework_audit']['clean'])
        self.assertFalse(audited['infrastructure_valid'])

    def test_framework_contamination_prevents_abba_description_and_stops_schedule(self):
        values = supportive_control_cohort()
        values[0]['framework_audit']['clean'] = False
        self.assertEqual(e2.describe_effect(values)['status'], 'incomplete')
        calls = []
        def execute(block, arm, scenario):
            calls.append((block, scenario))
            streams = control_streams(exit_probe_raw(state='fail', gate_failed=True))
            contaminated = framework_mutations(*streams)['unhandled-after-summary']
            return e2.audit_process(dict(returncode=1, timed_out=False, cleanup_ok=True,
                                        spawn_error=None, descendants_after_exit=[]), contaminated,
                                    scenario, '*', RUNNER.parents[2])
        phase = e2.serial_phase('abba', execute, lambda *_: {'failed': False})
        self.assertEqual(len(calls), 1)
        self.assertFalse(phase['infrastructure_valid'])

    def test_selected_performance_fail_plus_later_pool_error_closes_framework_gate(self):
        raw = exit_probe_raw(state='fail', gate_failed=True)
        raw = raw.replace(b'   Start at ', b'     Errors  1 error\n   Start at ')
        stderr = framework_stderr('fail') + ('\n⎯⎯⎯⎯⎯⎯ Unhandled Errors ⎯⎯⎯⎯⎯⎯\n'
                    'Vitest caught 1 unhandled error during the test run.\n'
                    'This might cause false positive tests. Resolve unhandled errors to make sure your tests are not affected.\n'
                    '⎯⎯⎯⎯⎯⎯ Unhandled Error ⎯⎯⎯⎯⎯⎯\n'
                    'Error: Failed to run tests\n').encode()
        receipt = dict(returncode=1, timed_out=False, cleanup_ok=True,
                       spawn_error=None, descendants_after_exit=[])
        audited = e2.audit_process(receipt, raw + stderr, 'p1-4500', '*', RUNNER.parents[2])
        self.assertEqual(audited['probe_audit']['original_task_state'], 'fail')
        self.assertTrue(audited['original_audit']['performance_failed'])
        self.assertEqual(audited['probe_audit']['probe_calls'], 30)
        self.assertEqual(audited['process']['returncode'], 1)
        self.assertTrue(audited['original_failed'])
        self.assertFalse(audited['infrastructure_valid'])

    def test_unexplained_combined_real_exit_closes_collection(self):
        raw = exit_probe_raw()
        source = self.root / 'CONTROL-emits.raw'; source.write_bytes(raw)
        directory = self.root / 'combined-exit'; directory.mkdir()
        receipt = e2.run_owned([sys.executable, '-B', '-c',
                               'import pathlib,sys; sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes()); sys.exit(1)',
                               str(source)], self.root, directory, 5)
        self.assertEqual(receipt['returncode'], 1)
        collected = (directory / 'stdout.raw').read_bytes() + (directory / 'stderr.raw').read_bytes()
        self.assertEqual(collected, raw)
        audited = e2.audit_process(receipt, collected, 'p1-4500', '*', RUNNER.parents[2])
        self.assertEqual(audited['probe_audit']['original_task_state'], 'pass')
        self.assertFalse(audited['original_audit']['performance_failed'])
        self.assertFalse(audited['infrastructure_valid'], 'unexplained combined nonzero must close collection')
        self.assertTrue(audited['failed'])
        self.assertFalse(audited['original_failed'])
        self.assertEqual(audited['process']['returncode'], 1)
        self.assertNotIn('original_exit', audited)

    def test_exit_consistency_collection_truth_table_retains_independent_evidence(self):
        for code, state, gates_failed, valid in EXIT_STATES:
            with self.subTest(code=code, state=state, gates_failed=gates_failed):
                raw = exit_probe_raw(state=state, gate_failed=gates_failed)
                receipt = {'returncode': code, 'timed_out': False, 'cleanup_ok': True,
                           'spawn_error': None, 'descendants_after_exit': []}
                audited = e2.audit_process(receipt, control_streams(raw), 'p1-4500', '*', RUNNER.parents[2])
                self.assertEqual(audited['infrastructure_valid'], valid)
                self.assertEqual(audited['original_failed'], state == 'fail' or gates_failed)
                self.assertEqual(audited['failed'], not valid or code != 0 or state == 'fail' or gates_failed)
                self.assertEqual(audited['process']['returncode'], code)
                self.assertEqual(audited['probe_audit']['original_task_state'], state)
                self.assertEqual(audited['original_audit']['performance_failed'], gates_failed)
                self.assertEqual(audited['original_audit']['gates'], e2.audit_original(raw, 'p1-4500')['gates'])
                self.assertNotIn('original_exit', audited)

    def test_real_nonzero_signal_spawn_timeout_and_missing_probe_propagate(self):
        parts = dummy_probe_raw()
        raw = probe_log(*parts)
        good = {'returncode': 0, 'timed_out': False, 'cleanup_ok': True, 'spawn_error': None, 'descendants_after_exit': []}
        for patch in ({'returncode': 7}, {'returncode': -15}, {'spawn_error': 'CONTROL spawn failed', 'returncode': None},
                      {'timed_out': True}, {'cleanup_ok': False}, {'descendants_after_exit': [123]}):
            value = dict(good, **patch)
            with self.subTest(patch=patch):
                audit = e2.audit_process(value, raw, 'p1-4500', 'id', RUNNER.parents[2])
                self.assertTrue(audit.get('failed'))
                self.assertFalse(audit.get('infrastructure_valid'))
        audit = e2.audit_process(good, parts[0], 'p1-4500', 'id', RUNNER.parents[2])
        self.assertTrue(audit.get('failed')); self.assertFalse(audit.get('infrastructure_valid'))
        failed = e2.audit_process(dict(good, returncode=1), control_streams(probe_log(*dummy_probe_raw(state='fail'))), 'p1-4500', 'id', RUNNER.parents[2])
        self.assertTrue(failed.get('failed')); self.assertTrue(failed.get('infrastructure_valid'))
        self.assertNotIn('original_exit', failed)


class ScheduleControls(unittest.TestCase):
    def test_abba_serial_scenarios_and_renderers_continue_after_performance_failure(self):
        seen = []
        def execute(block, arm, scenario):
            seen.append((block, arm, scenario))
            return {'failed': scenario == 'p1-4500', 'infrastructure_valid': True}
        def render(block, values):
            seen.append(('render', block, len(values)))
            return {'failed': False}
        value = e2.serial_phase('abba', execute, render)
        expected = []
        for block, arm in [('01-A','full'),('02-B','id'),('03-B','id'),('04-A','full')]:
            expected += [(block, arm, 'p1-4500'), (block, arm, 'plan-6000'), ('render',block,2)]
        self.assertEqual(seen, expected)
        self.assertTrue(value.get('failed'))
        self.assertTrue(value.get('infrastructure_valid'))
        self.assertEqual(len(value.get('processes', [])), 8)

    def test_unsafe_infrastructure_stops_and_renderer_failure_is_retained(self):
        seen = []
        def execute(block, arm, scenario):
            seen.append(scenario)
            return {'failed': True, 'infrastructure_valid': False}
        def render(block, values):
            seen.append('renderer attempted')
            return {'failed': True, 'returncode': 9}
        value = e2.serial_phase('abba', execute, render)
        self.assertEqual(seen, ['p1-4500', 'renderer attempted'])
        self.assertTrue(value.get('failed')); self.assertFalse(value.get('infrastructure_valid'))
        self.assertEqual(value.get('renderers', [{}])[0].get('returncode'), 9)

    def test_renderer_missing_evidence_closes_infrastructure_gate(self):
        def execute(block,arm,scenario): return {'failed':False,'infrastructure_valid':True}
        def render(block,values): return {'failed':True}
        value=e2.serial_phase('preflight',execute,render)
        self.assertTrue(value.get('failed'))
        self.assertFalse(value.get('infrastructure_valid'))


class RendererControls(DisposableControls):
    def test_renderer_exit_and_missing_external_outputs_cannot_pass(self):
        good = {'returncode': 0, 'cleanup_ok': True, 'timed_out': False, 'spawn_error': None, 'descendants_after_exit': []}
        value = e2.audit_renderer(good, self.root)
        self.assertTrue(value.get('failed'))
        (self.root / 'measurements.json').write_text('{}')
        (self.root / 'RESULTS.md').write_text('CONTROL ONLY renderer output')
        self.assertFalse(e2.audit_renderer(good, self.root).get('failed'))
        for patch in ({'returncode': 4}, {'returncode': -9}, {'cleanup_ok': False}, {'timed_out': True}):
            self.assertTrue(e2.audit_renderer(dict(good, **patch), self.root).get('failed'))


def finalize_preflight_control(path, phase):
    """Complete stdlib prerequisite evidence; no native commands or measurements."""
    source = {'manifest': {name: {'sha256': hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()}
                           for name in e2.SNAPSHOT_SOURCES}}
    owner = control_scratch_owner(RUNNER.parents[2] / 'backend', phase['run'], phase['attempt'])
    facts = control_config_facts(RUNNER.parents[2] / 'backend', owner)
    e2.write_json(path / 'scratch-ownership.json', {k:v for k,v in owner.items() if k != 'variants'})
    for name in e2.SNAPSHOT_SOURCES:
        target = path / e2.snapshot_member(name); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((RUNNER.parents[2] / name).read_bytes())
    for name in ('source-before.json', 'source-after.json'):
        e2.write_json(path / name, source)
    variants = e2.expand_setup((RUNNER.parents[2] / e2.ALLOWED[0]).read_bytes())
    scratch = path / 'scratch'; scratch.mkdir(exist_ok=True)
    for name, content in [('config.ts', e2.render_config(owner['path_facts'])), ('setup.ts', variants['full']),
                          ('full-setup.ts', variants['full']), ('id-setup.ts', variants['id'])]:
        (scratch / name).write_bytes(content)
    e2.write_json(scratch / 'variants.json', {arm: hashlib.sha256(raw).hexdigest() for arm, raw in variants.items()})
    e2.write_json(scratch / 'config-facts.json', facts)
    for process in phase['processes']:
        process.update(block='preflight', projection='full')
        process['process']['cwd'] = str(RUNNER.parents[2] / 'backend')
        directory = path / 'preflight' / process['scenario']
        for name, value in [('process.json', process['process']), ('audit.json', process),
                            ('preflight-record.json', process['probe_audit']['preflight']),
                            ('history-record.json', process['probe_audit']['history']),
                            ('source-before.json', source), ('source-after.json', source),
                            ('resolved-config.json', facts), ('liveness-before.json', {'smoke_state': 'absent'}),
                            ('liveness-after.json', {'smoke_state': 'absent'})]:
            e2.write_json(directory / name, value)
    directory = path / 'preflight'; renderer = directory / 'renderer'; renderer.mkdir(exist_ok=True)
    raw = b''.join((directory / s / n).read_bytes() for s in ('p1-4500', 'plan-6000') for n in ('stdout.raw', 'stderr.raw'))
    for target, content in [(directory / 'capacity.log', raw), (directory / 'measurements.json', b'{}'),
                            (directory / 'RESULTS.md', b'CONTROL renderer output'),
                            (renderer / 'stdout.raw', b'CONTROL renderer completed'), (renderer / 'stderr.raw', b'')]:
        target.write_bytes(content)
    renderer_process = dict(returncode=0, cleanup_ok=True, timed_out=False, spawn_error=None, descendants_after_exit=[],
                            stdout_sha256=hashlib.sha256((renderer / 'stdout.raw').read_bytes()).hexdigest(),
                            stderr_sha256=hashlib.sha256(b'').hexdigest())
    rendered = e2.audit_renderer(renderer_process, directory)
    e2.write_json(renderer / 'process.json', renderer_process)
    e2.write_json(renderer / 'audit.json', rendered)
    e2.write_json(renderer / 'source-before.json', source)
    phase.update(renderers=[rendered], resolved_config=facts, artifact_members_ok=True, evidence_status='COMPLETE',
                 owned_processes_gone=True, original_samples=1020, original_gate_evaluations=52, probe_calls=60)
    e2.write_json(path / 'phase.json', phase)
    expected = {name: digest for name, digest in e2.artifact_hashes(path).items()
                if name not in ('phase.json', 'evidence-receipt.json')}
    for name, digest in expected.items(): e2.freeze_evidence(path / name, digest)
    e2.write_json(path / 'evidence-receipt.json', {'expected': expected, 'final_members': ['phase.json']})
    e2.write_json(path / 'artifact-hashes.json', e2.artifact_hashes(path))


def control_scratch_owner(backend, run='12', attempt='1'):
    """Invented inode facts for archive-only controls; never a live ownership proof."""
    backend = Path(backend)
    scratch = backend / ('.capacity-e2-scratch-' + run + '-' + attempt)
    variants = e2.expand_setup((RUNNER.parents[2] / e2.ALLOWED[0]).read_bytes())
    entry = lambda path: dict(path=str(path), inode=1, device=1, uid=os.getuid())
    paths = dict(schema_version=1, run=run, attempt=attempt, backend=entry(backend), scratch=entry(scratch),
                 config=entry(scratch / 'config.ts'), setup=entry(scratch / 'setup.ts'), wrangler=entry(backend / 'wrangler.toml'),
                 wrangler_sha256=e2.WRANGLER_SHA256,
                 setup_sha256={arm:hashlib.sha256(raw).hexdigest() for arm, raw in variants.items()})
    return dict(path=str(scratch), backend=str(backend), inode=1, device=1, uid=os.getuid(), variants=variants,
                path_facts=paths, config_sha256=hashlib.sha256(e2.render_config(paths)).hexdigest())


def control_config_facts(backend, owner=None):
    """Dummy resolved facts, retaining the real frozen Wrangler byte identity."""
    owner = owner or control_scratch_owner(backend)
    return dict(path_facts=owner['path_facts'], config_sha256=owner['config_sha256'],
                setup_path=owner['path_facts']['setup']['path'], inherited_once=True, plugins_same=True, pool_options_same=True,
                pool_options_except_wrangler_path_same=True,
                wrangler_original_config='./wrangler.toml', wrangler_config=str(Path(backend) / 'wrangler.toml'),
                wrangler_sha256=WranglerPathControls.WRANGLER_SHA256,
                pool_options_sha256='a' * 64,
                isolatedStorage=False, remote_bindings=False, persistent_override=False,
                test_original=dict(include=['tests/capacity/capacity_test.ts'], testTimeout=600000, hookTimeout=60000))


class PreflightControls(DisposableControls):
    def test_reopening_reaudits_changed_framework_even_when_hashes_are_refreshed(self):
        baseline = control_streams(exit_probe_raw(state='fail', gate_failed=True))
        for name, streams in framework_mutations(*baseline).items():
            with self.subTest(name=name):
                path = self.root / name
                identity, phase = self.complete_preflight(path, 1, 'fail', True)
                self.assertTrue(e2.preflight_gate(path, identity)['processes'][0]['framework_audit']['clean'])
                directory = path / 'preflight/p1-4500'
                for filename, content in zip(('stdout.raw', 'stderr.raw'), streams):
                    (directory / filename).write_bytes(content)
                    phase['processes'][0]['process'][filename.split('.')[0] + '_sha256'] = hashlib.sha256(content).hexdigest()
                # Keep stale accepted framework facts, but refresh every raw/hash
                # binding. Reopening must fail from framework re-audit itself.
                finalize_preflight_control(path, phase)
                with self.assertRaisesRegex(ValueError, 'preflight re-audit failed'):
                    e2.preflight_gate(path, identity)

    def test_reopening_rejects_changed_or_missing_recorded_framework_facts(self):
        for change in ('missing', 'changed'):
            with self.subTest(change=change):
                path = self.root / change
                identity, phase = self.complete_preflight(path, 1, 'fail', True)
                self.assertTrue(e2.preflight_gate(path, identity)['processes'][0]['framework_audit']['clean'])
                if change == 'missing': del phase['processes'][0]['framework_audit']
                else: phase['processes'][0]['framework_audit']['summary']['tests']['total'] = 3
                finalize_preflight_control(path, phase)
                with self.assertRaisesRegex(ValueError, 'preflight recorded audit drift'):
                    e2.preflight_gate(path, identity)

    def complete_preflight(self, path, code=0, state='pass', gate_failed=False):
        identity = {'candidate': '1'*40, 'ref': e2.REF, 'run': '12', 'attempt': '1',
                    'runtime': {'node': 'v22.CONTROL'}}
        processes = []
        for scenario in ('p1-4500', 'plan-6000'):
            raw = exit_probe_raw(scenario, state if scenario == 'p1-4500' else 'pass',
                                 gate_failed if scenario == 'p1-4500' else False)
            directory = path / 'preflight' / scenario; directory.mkdir(parents=True)
            stderr = control_streams(raw)[1]
            (directory / 'stdout.raw').write_bytes(raw); (directory / 'stderr.raw').write_bytes(stderr)
            receipt = {'returncode': code if scenario == 'p1-4500' else 0, 'timed_out': False,
                       'cleanup_ok': True, 'spawn_error': None, 'descendants_after_exit': [],
                       'stdout_sha256': hashlib.sha256(raw).hexdigest(),
                       'stderr_sha256': hashlib.sha256(stderr).hexdigest()}
            audited = e2.audit_process(receipt, control_streams(raw), scenario, '*', RUNNER.parents[2])
            # Reopening must reject a stale, previously permissive acceptance
            # using the raw records, even when the saved gate still says true.
            audited.update(scenario=scenario, infrastructure_valid=True)
            processes.append(audited)
        phase = {'phase': 'preflight', **identity, 'processes': processes, 'control_only': True,
                 'infrastructure_valid': True, 'failed': any(p['failed'] for p in processes),
                 'scratch_cleanup_ok': True, 'source_guard_after': True, 'smoke_state': 'absent'}
        (path / 'phase.json').write_text(json.dumps(phase))
        finalize_preflight_control(path, phase)
        return identity, phase

    def test_exit_consistency_reopening_truth_table_reaudits_raw_records(self):
        for index, (code, state, gates_failed, valid) in enumerate(EXIT_STATES):
            with self.subTest(code=code, state=state, gates_failed=gates_failed):
                path = self.root / str(index)
                identity, phase = self.complete_preflight(path, code, state, gates_failed)
                if valid:
                    reopened = e2.preflight_gate(path, identity)
                    self.assertEqual(reopened['failed'], phase['failed'])
                    self.assertEqual(reopened['processes'][0]['process']['returncode'], code)
                    self.assertEqual(reopened['processes'][0]['probe_audit']['original_task_state'], state)
                    self.assertEqual(reopened['processes'][0]['original_audit']['performance_failed'], gates_failed)
                    self.assertEqual(reopened['processes'][0]['original_failed'], state == 'fail' or gates_failed)
                else:
                    with self.assertRaisesRegex(ValueError, 'preflight re-audit failed'):
                        e2.preflight_gate(path, identity)

    def test_unexplained_combined_exit_closes_reopening(self):
        identity, _ = self.complete_preflight(self.root / 'unexplained', 1, 'pass', False)
        with self.assertRaisesRegex(ValueError, 'preflight re-audit failed'):
            e2.preflight_gate(self.root / 'unexplained', identity)

    def test_saved_preflight_exit_task_gate_and_failure_drift_closes_reopening(self):
        mutations = (
            (lambda x: x['processes'][0]['process'].__setitem__('returncode', 0), 'preflight re-audit failed'),
            (lambda x: x['processes'][0]['probe_audit'].__setitem__('original_task_state', 'pass'), 'preflight recorded audit drift'),
            (lambda x: x['processes'][0]['original_audit']['gates'][0].__setitem__('p95', 200), 'preflight recorded audit drift'),
            (lambda x: x['processes'][0]['original_audit'].__setitem__('performance_failed', True), 'preflight recorded audit drift'),
            (lambda x: x['processes'][0].__setitem__('original_failed', False), 'preflight recorded audit drift'),
            (lambda x: x.__setitem__('failed', False), 'preflight failure receipt drift'),
        )
        for index, (mutate, reason) in enumerate(mutations):
            path = self.root / str(index)
            identity, phase = self.complete_preflight(path, 1, 'fail', False)
            mutate(phase); (path / 'phase.json').write_text(json.dumps(phase))
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, reason):
                e2.preflight_gate(path, identity)

    def test_native_gate_requires_complete_same_run_raw_evidence(self):
        identity = {'candidate':'1'*40,'ref':e2.REF,'run':'12','attempt':'1','runtime':{'node':'v22.CONTROL'}}
        with self.assertRaises(ValueError): e2.preflight_gate(self.root / 'missing', identity)
        receipt = dict(identity, infrastructure_valid=True, phase='preflight', processes=[])
        (self.root / 'phase.json').write_text(json.dumps(receipt))
        with self.assertRaises(ValueError): e2.preflight_gate(self.root, identity)


class FinalizedPreflightControls(DisposableControls):
    def control(self, name='complete', performance_failure=False):
        path = self.root / name
        identity, phase = PreflightControls.complete_preflight(
            self, path, 1 if performance_failure else 0, 'fail' if performance_failure else 'pass', performance_failure)
        self.assertEqual(e2.preflight_gate(path, identity), phase, 'complete prerequisite must reopen')
        return path, identity, phase

    def members(self):
        return ('artifact-hashes.json', 'evidence-receipt.json', 'source-after.json',
                e2.snapshot_member(e2.ALLOWED[1]), 'preflight/renderer/process.json',
                'preflight/renderer/stdout.raw', 'preflight/RESULTS.md', 'preflight/measurements.json',
                'preflight/p1-4500/process.json', 'preflight/p1-4500/source-after.json',
                'preflight/p1-4500/audit.json', 'preflight/p1-4500/history-record.json',
                'scratch/config.ts', 'scratch/setup.ts', 'scratch/config-facts.json', 'phase.json')

    def test_finalized_pair_reopens_and_retains_legitimate_original_failure(self):
        for failed in (False, True):
            path, identity, phase = self.control(str(failed), failed)
            e2.verify_artifact_members(path, e2.parse_json((path / 'artifact-hashes.json').read_bytes()))
            self.assertIs(phase['failed'], failed)
            self.assertEqual(phase['processes'][0]['process']['returncode'], int(failed))
            self.assertIs(phase['processes'][0]['original_audit']['performance_failed'], failed)

    def test_each_missing_finalized_member_closes_reopening(self):
        for index, name in enumerate(self.members()):
            with self.subTest(member=name):
                path, identity, _ = self.control(str(index))
                (path / name).unlink()
                with self.assertRaises(ValueError, msg='missing finalized member: ' + name):
                    e2.preflight_gate(path, identity)

    def test_each_changed_finalized_member_closes_reopening(self):
        for index, name in enumerate(self.members()):
            with self.subTest(member=name):
                path, identity, _ = self.control(str(index))
                file = path / name; file.write_bytes(file.read_bytes() + b'\nCONTROL changed completion bytes')
                with self.assertRaises(ValueError, msg='changed finalized member: ' + name):
                    e2.preflight_gate(path, identity)

    def test_rehashed_omission_cannot_drop_recorded_prerequisite_members(self):
        for index, name in enumerate(self.members()[2:]):
            with self.subTest(member=name):
                path, identity, _ = self.control(str(index))
                (path / name).unlink()
                # Recomputing the upload inventory cannot erase completion history.
                (path / 'artifact-hashes.json').write_text(json.dumps(e2.artifact_hashes(path)))
                with self.assertRaises(ValueError, msg='completed evidence omitted from final inventory: ' + name):
                    e2.preflight_gate(path, identity)

    def test_required_snapshot_cannot_be_omitted_from_both_inventories(self):
        path, identity, _ = self.control()
        name = e2.snapshot_member(e2.ALLOWED[1])
        (path / name).unlink()
        receipt = e2.parse_json((path / 'evidence-receipt.json').read_bytes())
        receipt['expected'].pop(name)
        (path / 'evidence-receipt.json').write_text(json.dumps(receipt))
        (path / 'artifact-hashes.json').write_text(json.dumps(e2.artifact_hashes(path)))
        with self.assertRaises(ValueError): e2.preflight_gate(path, identity)

    def test_receipt_membership_and_hash_manifest_shapes_close_reopening(self):
        mutations = (
            ('evidence-receipt.json', lambda x: x.__setitem__('final_members', [])),
            ('evidence-receipt.json', lambda x: x['expected'].__setitem__('source-after.json', None)),
            ('artifact-hashes.json', lambda x: x.__setitem__('../outside', '1' * 64)),
            ('artifact-hashes.json', lambda x: x.pop('phase.json')),
        )
        for index, (name, mutate) in enumerate(mutations):
            with self.subTest(index=index):
                path, identity, _ = self.control(str(index))
                file = path / name; value = e2.parse_json(file.read_bytes()); mutate(value)
                file.write_text(json.dumps(value))
                with self.assertRaises(ValueError): e2.preflight_gate(path, identity)

    def test_final_manifest_must_be_a_valid_json_object(self):
        for index, raw in enumerate((b'null', b'[]', b'"CONTROL"', b'{', b'{}')):
            with self.subTest(raw=raw):
                path, identity, _ = self.control(str(index))
                (path / 'artifact-hashes.json').write_bytes(raw)
                with self.assertRaises(ValueError): e2.preflight_gate(path, identity)

    def test_unexpected_hidden_symlink_and_visible_members_close_reopening(self):
        for kind in ('hidden', 'symlink', 'visible', 'directory-symlink'):
            with self.subTest(kind=kind):
                path, identity, _ = self.control(kind)
                if kind == 'hidden': (path / '.unexpected').write_bytes(b'CONTROL')
                elif kind == 'visible': (path / 'unexpected').write_bytes(b'CONTROL')
                elif kind == 'symlink': (path / 'linked-source').symlink_to(path / 'source-after.json')
                else:
                    link = self.root / 'linked-directory'; link.symlink_to(path, target_is_directory=True); path = link
                with self.assertRaises(ValueError): e2.preflight_gate(path, identity)

    def test_final_manifest_write_failure_after_valid_phase_never_reopens(self):
        path = self.root / 'native-finalization'
        identity = {'candidate': '1' * 40, 'ref': e2.REF, 'run': '12', 'attempt': '1', 'runtime': {'node': 'v22.CONTROL'}}
        source = {'manifest': {name: {'sha256': hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()}
                               for name in e2.SNAPSHOT_SOURCES}}
        writer = e2.write_json
        def collected(*_):
            with mock.patch.object(e2, 'EXPECTED_EVIDENCE', None):
                _, phase = PreflightControls.complete_preflight(self, path, 1, 'fail', True)
            for name in ('phase.json', 'artifact-hashes.json', 'evidence-receipt.json'): (path / name).unlink()
            for name, digest in e2.artifact_hashes(path).items(): e2.freeze_evidence(path / name, digest)
            return phase
        def failed_writer(target, value):
            if Path(target).name == 'artifact-hashes.json':
                raise OSError('CONTROL final manifest write failed after phase.json')
            return writer(target, value)
        with mock.patch.dict(os.environ, {'RUNNER_TEMP': str(self.root), 'GITHUB_SHA': identity['candidate'],
                                         'GITHUB_REF': identity['ref'], 'GITHUB_RUN_ID': identity['run'], 'GITHUB_RUN_ATTEMPT': identity['attempt']}), \
             mock.patch.object(e2, 'source_guard', return_value=source), \
             mock.patch.object(e2, 'runtime', return_value=identity['runtime']), \
             mock.patch.object(e2, 'materialize_scratch', return_value=control_scratch_owner(RUNNER.parents[2] / 'backend')), \
             mock.patch.object(e2, 'cleanup_scratch'), \
             mock.patch.object(e2, 'serial_phase', side_effect=collected), \
             mock.patch.object(e2, 'run_owned', side_effect=AssertionError('native command forbidden')):
            # Fixture collection may write its own manifest; fail only the real final write.
            def schedule(*args):
                value = collected(*args)
                patcher = mock.patch.object(e2, 'write_json', side_effect=failed_writer)
                patcher.start(); self.addCleanup(patcher.stop)
                return value
            with mock.patch.object(e2, 'serial_phase', side_effect=schedule):
                with self.assertRaisesRegex(OSError, 'CONTROL final manifest write failed'):
                    e2.native(mock.Mock(phase='preflight', output=str(path)))
        self.assertTrue(e2.parse_json((path / 'phase.json').read_bytes())['infrastructure_valid'])
        self.assertFalse((path / 'artifact-hashes.json').exists())
        with self.assertRaises(ValueError): e2.preflight_gate(path, identity)

    def workflow_gate(self, path, identity):
        workflow = (RUNNER.parents[2] / e2.ALLOWED[3]).read_text()
        step = workflow.split('      - name: Read preflight infrastructure gate\n', 1)[1].split('      - name: Native serial ABBA\n', 1)[0]
        code = step.split("          python3 -B - <<'PY'\n", 1)[1].split('          PY\n', 1)[0]
        code = '\n'.join(line[10:] for line in code.splitlines())
        target = self.root / ('workflow-' + path.parents[1].name); target.mkdir()
        script = target / 'gate.py'; script.write_text(code)
        output = target / 'github-output'
        env = dict(os.environ, RUNNER_TEMP=str(path.parents[1]), GITHUB_OUTPUT=str(output),
                   GITHUB_SHA=identity['candidate'], GITHUB_REF=identity['ref'], GITHUB_RUN_ID=identity['run'], GITHUB_RUN_ATTEMPT=identity['attempt'])
        result = subprocess.run([sys.executable, '-B', str(script)], cwd=RUNNER.parents[2], env=env, capture_output=True, timeout=5)
        (target / 'stdout').write_bytes(result.stdout); (target / 'stderr').write_bytes(result.stderr)
        (target / 'exit').write_text(str(result.returncode))
        self.assertEqual(result.returncode, 0, result.stderr)
        return output.read_text().strip()

    def test_workflow_gate_requires_finalized_validator_and_exact_attempt_identity(self):
        for index, change in enumerate(('valid', 'missing-manifest', 'candidate', 'ref', 'run', 'attempt')):
            path = self.root / str(index) / 'capacity-history-e2' / 'preflight'
            identity, _ = PreflightControls.complete_preflight(self, path, 1, 'fail', True)
            if change == 'missing-manifest': (path / 'artifact-hashes.json').unlink()
            elif change != 'valid': identity[change] += '-drift'
            with self.subTest(change=change):
                self.assertEqual(self.workflow_gate(path, identity), 'valid=' + str(change == 'valid').lower())


class OriginalPlanControls(unittest.TestCase):
    def audited_plan(self, plans, missing=False):
        raw, preflight, history = dummy_probe_raw()
        result = json.loads(raw[len(b'CAPACITY_RESULT '):])
        query = result['queries'][-1]
        if missing: query.pop('plans')
        else: query['plans'] = plans
        raw = emit(result)
        original = e2.audit_original(raw, 'p1-4500')
        preflight['capture'].update({key: original[key] for key in
                                     ('payload_sha256', 'payload_utf8_bytes', 'raw_emit_sha256')})
        return e2.audit_probe(probe_log(raw, preflight, history), original, 'p1-4500', 'id', RUNNER.parents[2])

    def test_missing_original_plan_invalidates_probe(self):
        with self.assertRaises(ValueError): self.audited_plan(None, missing=True)

    def test_null_original_plan_invalidates_probe(self):
        for plans in (None, [None]):
            with self.subTest(plans=plans), self.assertRaises(ValueError): self.audited_plan(plans)

    def test_empty_original_plan_invalidates_probe(self):
        for plans in ([], [[]], [['']], [['CONTROL ONLY plan', '']]):
            with self.subTest(plans=plans), self.assertRaises(ValueError): self.audited_plan(plans)

    def test_extra_original_plan_invalidates_probe(self):
        with self.assertRaises(ValueError): self.audited_plan([['CONTROL ONLY plan'], ['extra']])

    def test_non_string_original_plan_invalidates_probe(self):
        for plans in ('CONTROL ONLY plan', ['CONTROL ONLY plan'], [[1]], [[True]], [['CONTROL ONLY plan', None]]):
            with self.subTest(plans=plans), self.assertRaises(ValueError): self.audited_plan(plans)

    def test_exactly_one_selected_history_statement_is_required_by_probe(self):
        raw, preflight, history = dummy_probe_raw()
        statement = dict(sql=HISTORY, params=[USER, 'restaurant', SELECTED['p1-4500']])
        for statements in ([], None, [statement] * 2):
            original = e2.audit_original(raw, 'p1-4500')
            original['result']['queries'][-1]['statements'] = statements
            with self.subTest(statements=statements), self.assertRaises(ValueError):
                e2.audit_probe(probe_log(raw, preflight, history), original, 'p1-4500', 'id', RUNNER.parents[2])

    def test_valid_original_plan_is_returned_and_equality_preserves_support(self):
        plan = ['CONTROL ONLY plan']
        self.assertEqual(self.audited_plan([plan])['original_plan'], plan)
        report = e2.describe_effect(supportive_control_cohort())
        self.assertEqual(report['status'], 'result-size-related path support')
        self.assertTrue(all(not c['exclusions'] for c in report['comparisons']))

    def test_valid_original_plan_mismatch_is_an_exclusion(self):
        plan = ['CONTROL different original plan']
        self.assertEqual(self.audited_plan([plan])['original_plan'], plan)
        values = supportive_control_cohort()
        values[0]['probe_audit']['original_plan'] = plan
        report = e2.describe_effect(values)
        self.assertEqual(report['status'], 'confounded')
        self.assertIn('original emitted plan differs', report['comparisons'][0]['exclusions'])

    def test_effect_comparison_has_no_nullable_original_plan_bypass(self):
        values = supportive_control_cohort()
        values[0]['probe_audit']['original_plan'] = None
        report = e2.describe_effect(values)
        self.assertEqual(report['status'], 'confounded')
        self.assertIn('original emitted plan differs', report['comparisons'][0]['exclusions'])


class InferenceControls(unittest.TestCase):
    def controls(self):
        values=[]
        for block, arm in [('01-A','*'),('02-B','id'),('03-B','id'),('04-A','*')]:
            for scenario in ('p1-4500','plan-6000'):
                parts=dummy_probe_raw(scenario,arm)
                audited=e2.audit_process(dict(returncode=0, timed_out=False, cleanup_ok=True,
                                              spawn_error=None, descendants_after_exit=[]),
                                         control_streams(probe_log(*parts)),scenario,arm,RUNNER.parents[2])
                e2.require(audited['infrastructure_valid'], 'invalid inference control fixture')
                audited.update(block=block,scenario=scenario)
                values.append(audited)
        return values
    def test_smaller_bytes_without_improvement_and_incomplete_blocks_are_not_support(self):
        values=self.controls()
        report=e2.describe_effect(values)
        self.assertEqual(report.get('status'), 'no reproduced effect')
        self.assertEqual(len(report.get('comparisons',[])),4)
        self.assertEqual(e2.describe_effect(values[:-1]).get('status'),'incomplete')

    def test_plan_duration_rows_read_logging_and_bookkeeping_exclude_support(self):
        values=self.controls()
        for p in values:
            if p['block'] in ('01-A','04-A'):
                for s in p['probe_audit']['history']['samples']:
                    s['wall_ms']=10; s['stop_ms']=s['start_ms']+10; s['bookkeeping_stop_ms']=s['stop_ms']+1
        self.assertEqual(e2.describe_effect(values).get('status'),'result-size-related path support')
        mutations=(
            lambda x: x[2]['probe_audit']['history']['plans_before']['id']['results'][0].__setitem__('detail','CONTROL covering index'),
            lambda x: [s.__setitem__('d1_reported_duration_ms',3) for s in x[0]['probe_audit']['history']['samples']],
            lambda x: x[0]['probe_audit']['history']['samples'][0]['meta'].__setitem__('rows_read',11),
            lambda x: x[0]['probe_audit']['preflight']['capture'].__setitem__('unexpected_log_calls',1),
            lambda x: [s.__setitem__('bookkeeping_ms',4) for s in x[0]['probe_audit']['history']['samples']],
        )
        for mutate in mutations:
            v=copy.deepcopy(values); mutate(v)
            with self.subTest(mutate=mutate):
                report=e2.describe_effect(v)
                self.assertEqual(report.get('status'),'confounded')
                self.assertTrue(any(c['exclusions'] for c in report.get('comparisons',[])))


class FinalizationControls(DisposableControls):
    def snapshot_sources(self):
        return ('backend/tests/capacity/capacity_test.ts', 'backend/vitest.capacity.config.ts',
                'backend/package.json', 'backend/package-lock.json', 'backend/wrangler.toml', *e2.ALLOWED)

    def finalized_control(self, cleanup_failed=False, source_failed=False, artifact_mutation=None):
        processes = supportive_control_cohort()
        expected = e2.describe_effect(processes)
        self.assertEqual(expected['status'], 'result-size-related path support', 'cohort must support before final failures')
        output = self.root / 'CONTROL-finalized-phase'
        outcome = {'processes': processes, 'renderers': [{'failed': False} for _ in range(4)],
                   'failed': False, 'infrastructure_valid': True}
        args = mock.Mock(phase='abba', output=str(output), preflight=str(self.root / 'CONTROL-preflight'))
        owner = control_scratch_owner(RUNNER.parents[2] / 'backend')
        facts = control_config_facts(RUNNER.parents[2] / 'backend', owner)
        source = {'control_only': True, 'manifest': {
            name: {'sha256': hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()}
            for name in self.snapshot_sources()}}
        guards = [source, ValueError('CONTROL final source validation failure') if source_failed else source]
        def cleanup(*_):
            if artifact_mutation is not None: artifact_mutation(output)
            if cleanup_failed: raise ValueError('CONTROL final scratch cleanup failure')
        def collected(*_):
            archive = output / 'scratch'
            for name, content in (('config.ts', e2.render_config(owner['path_facts'])), ('setup.ts', owner['variants']['full']),
                                  ('config-facts.json', json.dumps(facts).encode())):
                (archive / name).write_bytes(content); e2.freeze_evidence(archive / name)
            # Final-operation controls substitute the schedule, but must supply
            # its actual required files and frozen mock completion receipts.
            for process in processes:
                directory = output / process['block'] / process['scenario']; directory.mkdir(parents=True)
                probe = process['probe_audit']
                raw = probe_log(emit(process['original_audit']['result']), probe['preflight'], probe['history'])
                for name, content in (('stdout.raw', raw), ('stderr.raw', b'')):
                    (directory / name).write_bytes(content)
                    digest = hashlib.sha256(content).hexdigest()
                    process['process'][name.split('.')[0] + '_sha256'] = digest
                    e2.freeze_evidence(directory / name, digest)
                for name, value in (('process.json', process['process']), ('audit.json', process),
                                    ('preflight-record.json', probe['preflight']), ('history-record.json', probe['history'])):
                    e2.write_json(directory / name, value)
                for name in ('liveness-before.json','liveness-after.json','source-before.json','source-after.json','resolved-config.json'):
                    e2.write_json(directory / name, {'control_only': True})
            for block in ('01-A','02-B','03-B','04-A'):
                directory = output / block; renderer = directory / 'renderer'; renderer.mkdir()
                raw = b''.join((directory / s / n).read_bytes() for s in ('p1-4500','plan-6000') for n in ('stdout.raw','stderr.raw'))
                for path, content in ((directory/'capacity.log',raw), (directory/'measurements.json',b'{}'),
                                      (directory/'RESULTS.md',b'CONTROL results'), (renderer/'stdout.raw',b'CONTROL renderer'),
                                      (renderer/'stderr.raw',b'')):
                    path.write_bytes(content); e2.freeze_evidence(path)
                e2.write_json(renderer/'process.json', {'control_only':True, 'returncode':0})
                e2.write_json(renderer/'audit.json', {'failed':False})
            return outcome
        # Exercise native's finalization code only. All command/DB/runtime and
        # scratch materialization interfaces are replaced; no backend writes.
        with mock.patch.dict(os.environ, {'RUNNER_TEMP': str(self.root), 'GITHUB_RUN_ID': '12', 'GITHUB_RUN_ATTEMPT': '1'}), \
             mock.patch.object(e2, 'LEDGER', None), \
             mock.patch.object(e2, 'source_guard', side_effect=guards), \
             mock.patch.object(e2, 'runtime', return_value={'control_only': True}), \
             mock.patch.object(e2, 'preflight_gate', return_value={'resolved_config': facts, 'failed': False}), \
             mock.patch.object(e2, 'materialize_scratch', return_value=owner), \
             mock.patch.object(e2, 'serial_phase', side_effect=collected), \
             mock.patch.object(e2, 'cleanup_scratch', side_effect=cleanup), \
             mock.patch.object(e2, 'run_owned', side_effect=AssertionError('native command forbidden in finalization control')):
            code = e2.native(args)
        phase = json.loads((output / 'phase.json').read_text())
        report = json.loads((output / 'descriptive-effect.json').read_text())
        hashes = json.loads((output / 'artifact-hashes.json').read_text())
        return code, phase, report, hashes, expected, output

    def test_artifact_delivery_visible_names_and_expected_members_survive_default_upload(self):
        code, phase, report, hashes, _, output = self.finalized_control()
        expected = {'source-snapshots/source__' + name.replace('/', '__'):
                    hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()
                    for name in self.snapshot_sources()}
        self.assertEqual({name: digest for name, digest in hashes.items() if name.startswith('source-snapshots/')},
                         expected, 'every required source snapshot must have a visible name and the frozen source hash')
        eligible = {str(p.relative_to(output)) for p in output.rglob('*') if p.is_file() and not p.is_symlink()
                    and all(not part.startswith('.') for part in p.relative_to(output).parts)}
        self.assertEqual(eligible, set(hashes) | {'artifact-hashes.json'},
                         'default upload hidden-file filtering must omit no required hashed member')
        self.assertEqual(code, 0); self.assertTrue(phase['artifact_members_ok'])
        e2.verify_artifact_members(output, hashes)
        # A downloaded artifact must match every recorded member, including logs
        # and final receipts, rather than accepting whatever happened to arrive.
        for name in ('phase.json', next(iter(expected))):
            path = output / name; original = path.read_bytes()
            path.unlink()
            with self.subTest(name=name, mutation='omitted'), self.assertRaises(ValueError):
                e2.verify_artifact_members(output, hashes)
            path.write_bytes(original + b'CONTROL changed artifact member')
            with self.subTest(name=name, mutation='changed'), self.assertRaises(ValueError):
                e2.verify_artifact_members(output, hashes)
            path.write_bytes(original)
        omitted_hash = dict(hashes); omitted_hash.pop('scratch/variants.json')
        with self.assertRaisesRegex(ValueError, 'missing completed evidence member'):
            e2.verify_artifact_members(output, omitted_hash)
        e2.verify_artifact_members(output, hashes)

    def assert_artifact_failure(self, mutate, message):
        code, phase, report, hashes, expected, output = self.finalized_control(artifact_mutation=mutate)
        self.assertEqual(code, 1, 'incomplete, changed, or unsafe artifact members must close final acceptance')
        self.assertFalse(phase['infrastructure_valid']); self.assertTrue(phase['failed'])
        self.assertFalse(phase['artifact_members_ok']); self.assertIn(message, phase['artifact_error'])
        self.assertEqual(report['status'], 'infrastructure-invalid')
        self.assertIn('required artifact membership failed', report['final_exclusions'])
        for actual, original in zip(report['comparisons'], expected['comparisons']):
            self.assertEqual({k: v for k, v in actual.items() if k != 'exclusions'},
                             {k: v for k, v in original.items() if k != 'exclusions'})
        self.assertEqual(hashes['phase.json'], hashlib.sha256((output / 'phase.json').read_bytes()).hexdigest())
        return hashes

    def source_snapshot(self, output):
        suffix = e2.ALLOWED[1].replace('/', '__')
        return next(p for p in (output / 'source-snapshots').iterdir() if p.name.endswith(suffix))

    def test_artifact_delivery_omitted_required_snapshot_closes_acceptance(self):
        self.assert_artifact_failure(lambda output: self.source_snapshot(output).unlink(),
                                     'missing required source snapshot')

    def test_artifact_delivery_changed_snapshot_closes_expected_source_hash_gate(self):
        def mutate(output):
            path = self.source_snapshot(output)
            path.write_bytes(path.read_bytes() + b'CONTROL changed source snapshot')
        self.assert_artifact_failure(mutate, 'source snapshot hash drift')

    def test_artifact_delivery_unknown_hidden_credential_file_is_rejected_and_unhashed(self):
        hashes = self.assert_artifact_failure(
            lambda output: (output / '.dev.vars').write_text('CONTROL ONLY, no credential values'),
            'unsafe artifact member')
        self.assertNotIn('.dev.vars', hashes)

    def test_artifact_delivery_unowned_symlink_is_rejected_without_reading_target(self):
        target = self.root / 'CONTROL-target'; target.write_text('CONTROL ONLY target stays unchanged')
        hashes = self.assert_artifact_failure(
            lambda output: (output / 'unsafe-link').symlink_to(target), 'unsafe artifact member')
        self.assertNotIn('unsafe-link', hashes)
        self.assertEqual(target.read_text(), 'CONTROL ONLY target stays unchanged')

    def assert_final_failure(self, cleanup_failed=False, source_failed=False):
        code, phase, report, hashes, expected, output = self.finalized_control(cleanup_failed, source_failed)
        self.assertEqual(report['status'], 'infrastructure-invalid', 'final failure must invalidate otherwise supportive interpretation')
        self.assertEqual(code, 1)
        self.assertFalse(phase['infrastructure_valid']); self.assertTrue(phase['failed'])
        self.assertEqual((phase['original_samples'], phase['original_gate_evaluations'], phase['probe_calls']), (4080, 208, 240))
        exclusions = []
        if cleanup_failed:
            self.assertEqual(phase['cleanup_error'], 'CONTROL final scratch cleanup failure')
            exclusions.append('final scratch cleanup failed')
        if source_failed:
            self.assertEqual(phase['source_error'], 'CONTROL final source validation failure')
            exclusions.append('final source validation failed')
            self.assertFalse(phase['artifact_members_ok'], 'missing required final source receipt is incomplete evidence')
            exclusions.append('required artifact membership failed')
        self.assertEqual(report['final_exclusions'], exclusions)
        self.assertEqual(report['final_operations'], {'scratch_cleanup_ok': not cleanup_failed, 'source_guard_after': not source_failed})
        for actual, original in zip(report['comparisons'], expected['comparisons']):
            self.assertEqual({k: v for k, v in actual.items() if k != 'exclusions'},
                             {k: v for k, v in original.items() if k != 'exclusions'}, 'retain every numeric descriptive field')
            self.assertEqual(actual['exclusions'], exclusions)
        self.assertEqual(hashes['descriptive-effect.json'], hashlib.sha256((output / 'descriptive-effect.json').read_bytes()).hexdigest())
        self.assertEqual(hashes['phase.json'], hashlib.sha256((output / 'phase.json').read_bytes()).hexdigest())

    def test_final_failure_scratch_cleanup_invalidates_support_and_retains_numbers(self):
        self.assert_final_failure(cleanup_failed=True)

    def test_final_failure_source_validation_invalidates_support_and_retains_numbers(self):
        self.assert_final_failure(source_failed=True)

    def test_final_failure_both_operations_retains_each_exclusion(self):
        self.assert_final_failure(cleanup_failed=True, source_failed=True)

    def test_successful_final_operations_allow_support_and_hash_delivered_report(self):
        code, phase, report, hashes, expected, output = self.finalized_control()
        self.assertEqual(code, 0)
        self.assertTrue(phase['infrastructure_valid'])
        self.assertEqual(report['status'], expected['status'])
        self.assertEqual(report['comparisons'], expected['comparisons'])
        self.assertEqual(report['final_operations'], {'scratch_cleanup_ok': True, 'source_guard_after': True})
        self.assertEqual(report['final_exclusions'], [])
        self.assertEqual(hashes['descriptive-effect.json'], hashlib.sha256((output / 'descriptive-effect.json').read_bytes()).hexdigest())


class CommandPhaseControls(DisposableControls):
    def omitted_descendant(self, raw, directory, status, run_owned):
        """Pin two generations, then omit the child after leader reap.

        Only OS interfaces are simulated; cleanup and finalization run unchanged.
        The foreign baseline generation is never owned or signalled.
        """
        leader, child, foreign = 420001, 420002, 420003
        handles = {leader: 520001, child: 520002}
        trace = {'status': status, 'pinned': [], 'omitted_snapshots': 0,
                 'polls': [], 'signals': [], 'numeric_signals': [], 'closed': [], 'waits': []}
        def info(pid, start, ppid, sid):
            return dict(pid=pid, start=start, ppid=ppid, sid=sid, pgid=sid, state='S')
        outsider = info(foreign, 30, os.getpid(), foreign)
        process = mock.Mock(pid=leader, returncode=None)
        def table():
            if process.returncode is not None:
                trace['omitted_snapshots'] += 1
                return {foreign: outsider}
            if not trace.get('launched'): return {foreign: outsider}
            return {foreign: outsider, leader: info(leader, 10, os.getpid(), leader),
                    child: info(child, 11, leader, leader)}
        def launch(*_, **kwargs):
            trace['launched'] = True
            kwargs['stdout'].write(raw)
            return process
        def pin(pid, *_):
            trace['pinned'].append(pid)
            return handles[pid]
        class Poll:
            def register(self, fd, *_): self.fd = fd
            def poll(self, *_):
                omitted = process.returncode is not None
                result = []
                if self.fd == handles[leader] and child in trace['pinned']:
                    trace['leader_polls_after_child'] = trace.get('leader_polls_after_child', 0) + 1
                    # Keep the parent alive through the child's pin validation.
                    if trace['leader_polls_after_child'] >= 2:
                        result = [(self.fd, select.POLLIN | (select.POLLHUP if omitted else 0))]
                elif self.fd == handles[child] and omitted:
                    if status == 'uncheckable':
                        trace['polls'].append({'fd': self.fd, 'omitted': True, 'error': 'CONTROL pidfd unreadable'})
                        raise OSError('CONTROL pidfd unreadable')
                    if status == 'ambiguous': result = [(self.fd, select.POLLNVAL)]
                    if status == 'exited': result = [(self.fd, select.POLLIN | select.POLLHUP)]
                trace['polls'].append({'fd': self.fd, 'omitted': omitted, 'events': result})
                return result
        def poll():
            process.returncode = 0
            trace['leader_reaped'] = True
            return 0
        process.poll.side_effect = poll
        process.wait.side_effect = lambda **_: poll()
        def waitid(kind, fd, flags):
            trace['waits'].append({'kind': kind, 'fd': fd, 'flags': flags})
            if fd == handles[child] and status == 'exited':
                trace['adopted_reaped'] = True
                return mock.Mock(si_pid=child)
            return None
        with mock.patch.object(e2, 'process_table', side_effect=table), \
             mock.patch.object(e2.subprocess, 'Popen', side_effect=launch), \
             mock.patch.object(os, 'pidfd_open', side_effect=pin), \
             mock.patch.object(select, 'poll', Poll), \
             mock.patch.object(signal, 'pidfd_send_signal', side_effect=lambda fd, sig, *_: trace['signals'].append([fd, sig])), \
             mock.patch.object(os, 'kill', side_effect=lambda pid, sig: trace['numeric_signals'].append([pid, sig])), \
             mock.patch.object(os, 'close', side_effect=lambda fd: trace['closed'].append(fd)), \
             mock.patch.object(os, 'waitid', side_effect=waitid):
            receipt = run_owned(['CONTROL-omitted-descendant'], self.root, directory, 1)
        trace['receipt'] = receipt
        return receipt, trace

    def controlled_phase(self, failure_at=None, mutation=None, snapshot_gap=None,
                         source_failure_at=None, invalid_audit=False, performance_failure=False):
        """Run the real serial/finalization paths with only pure Python children.

        Guard/runtime and renderer command interfaces substitute bounded Python
        commands. The unsafe-command seam records a fake surviving generation.
        """
        output = self.root / 'phase'
        checkout = self.root / 'fixture-checkout'
        for name in e2.SNAPSHOT_SOURCES:
            target = checkout / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((RUNNER.parents[2] / name).read_bytes())
        owner = control_scratch_owner(checkout / 'backend')
        scratch = Path(owner['path']); scratch.mkdir()
        facts = control_config_facts(checkout / 'backend', owner)
        (scratch / 'config-facts.json').write_text(json.dumps(facts))
        (scratch / 'config.ts').write_bytes(e2.render_config(owner['path_facts']))
        (scratch / 'setup.ts').write_bytes(e2.expand_setup((RUNNER.parents[2] / e2.ALLOWED[0]).read_bytes())['full'])
        source = {'manifest': {name: {'sha256': hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()}
                               for name in e2.SNAPSHOT_SOURCES}}
        trace = {'attempts': [], 'scratch_deletions': [], 'fake_owned_remains': False,
                 'guards': 0, 'runtimes': 0, 'arm': 'full', 'original_raw_hashes': {}}
        original_owned = e2.run_owned
        def guard(*_, **__):
            trace['guards'] += 1
            e2.read_command(['CONTROL-guard', str(trace['guards'])], self.root)
            if trace['guards'] == source_failure_at:
                trace['source_failure'] = 'CONTROL frozen source validation failed after safe drain'
                raise ValueError(trace['source_failure'])
            return source
        def runtime(*_, **__):
            trace['runtimes'] += 1
            e2.read_command(['CONTROL-runtime', str(trace['runtimes'])], self.root)
            return {'control_only': True}
        def command(argv, cwd, directory, timeout):
            directory = Path(directory)
            trace['attempts'].append(argv)
            kind = ('guard' if argv[0] == 'CONTROL-guard' else 'runtime' if argv[0] == 'CONTROL-runtime'
                    else 'vitest' if argv[0].endswith('/vitest') else 'renderer')
            fail = kind == failure_at and (kind not in ('guard', 'runtime') or argv[1] == '2')
            if fail:
                trace['fake_owned_remains'] = True
                (directory / 'stdout.raw').write_bytes(b'CONTROL unsafe cleanup\n')
                (directory / 'stderr.raw').write_bytes(b'CONTROL generation still owned\n')
                receipt = dict(command=argv, returncode=1, cleanup_ok=False, timed_out=False,
                               spawn_error=None, descendants_after_exit=[490001], ownership_errors=[{'error': 'CONTROL unproved drain'}],
                               stdout_sha256=hashlib.sha256((directory / 'stdout.raw').read_bytes()).hexdigest(),
                               stderr_sha256=hashlib.sha256((directory / 'stderr.raw').read_bytes()).hexdigest())
                e2.write_json(directory / 'process.json', receipt)
                return receipt
            if kind == 'vitest':
                scenario = argv[-1]; projection = '*' if trace['arm'] == 'full' else 'id'
                product_failed = performance_failure and scenario == 'p1-4500'
                original, preflight, history = dummy_probe_raw(scenario, projection, 'fail' if product_failed else 'pass')
                if product_failed:
                    value = json.loads(original[len(b'CAPACITY_RESULT '):])
                    query = value['queries'][0]
                    for sample in query['samples']: sample['wallMs'] = 101
                    query['wallMs'] = quantiles([101] * 30)
                    original = emit(value)
                    payload = original[len(b'CAPACITY_RESULT '):-1]
                    preflight['capture'].update(payload_sha256=hashlib.sha256(payload).hexdigest(),
                                                payload_utf8_bytes=len(payload),
                                                raw_emit_sha256=hashlib.sha256(original[:-1]).hexdigest())
                preflight['original_task']['filepath'] = str(checkout / 'backend/tests/capacity/capacity_test.ts')
                history['original_task']['filepath'] = preflight['original_task']['filepath']
                for sample in history['samples']:
                    sample.update(start_ms=1000 + 20 * sample['index'], wall_ms=10 if projection == '*' else 2)
                    sample['stop_ms'] = sample['start_ms'] + sample['wall_ms']
                    sample['bookkeeping_stop_ms'] = sample['stop_ms'] + sample['bookkeeping_ms']
                raw = probe_log(original, preflight, history)
                if invalid_audit: raw = b'CONTROL missing original and probe records\n'
                if snapshot_gap is not None and 'snapshot_gap' not in trace:
                    receipt, trace['snapshot_gap'] = self.omitted_descendant(raw, directory, snapshot_gap, original_owned)
                    trace['original_raw_hashes'][str(directory.relative_to(output) / 'stdout.raw')] = receipt['stdout_sha256']
                    return receipt
                inp = self.root / ('input-%03d.raw' % len(trace['attempts'])); inp.write_bytes(raw)
                actual = [sys.executable, '-B', '-c',
                          'import pathlib,sys; sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes()); sys.stderr.buffer.write(bytes.fromhex(sys.argv[3])); sys.exit(int(sys.argv[2]))',
                          str(inp), '1' if product_failed else '0', framework_stderr('fail' if product_failed else 'pass').hex()]
            elif kind == 'renderer':
                trace.setdefault('renderer_guard_counts', []).append(trace['guards'])
                actual = [sys.executable, '-B', '-c',
                          "import pathlib,sys; p=pathlib.Path(sys.argv[1]); (p/'measurements.json').write_text('{}'); (p/'RESULTS.md').write_text('CONTROL renderer output'); print('CONTROL renderer completed')",
                          str(directory.parent)]
            else:
                actual = [sys.executable, '-B', '-c', "print('CONTROL guard/runtime')"]
            receipt = original_owned(actual, checkout / 'backend', directory, 3)
            if kind == 'vitest':
                trace['original_raw_hashes'][str(directory.relative_to(output) / 'stdout.raw')] = receipt['stdout_sha256']
            return receipt
        def cleanup(*_):
            if mutation: mutation(output)
            trace['scratch_deletions'].append(str(scratch))
            for path in scratch.iterdir():
                target = output / 'scratch' / path.name
                target.write_bytes(path.read_bytes()); e2.freeze_evidence(target)
                path.unlink()
            scratch.rmdir()
        args = mock.Mock(phase='abba', output=str(output), preflight=str(self.root / 'preflight'))
        with mock.patch.dict(os.environ, {'RUNNER_TEMP': str(self.root), 'GITHUB_RUN_ID': '12', 'GITHUB_RUN_ATTEMPT': '1'}), \
             mock.patch.object(e2, '__file__', str(checkout / '.github/scripts/capacity-history-e2.py')), \
             mock.patch.object(e2, 'LEDGER', None), \
             mock.patch.object(e2, 'source_guard', side_effect=guard), \
             mock.patch.object(e2, 'runtime', side_effect=runtime), \
             mock.patch.object(e2, 'preflight_gate', return_value={'resolved_config': facts, 'failed': False}), \
             mock.patch.object(e2, 'materialize_scratch', return_value=owner), \
             mock.patch.object(e2, 'select_projection', side_effect=lambda _, arm: trace.update(arm=arm)), \
             mock.patch.object(e2, 'owned_scratch', return_value=scratch), \
             mock.patch.object(e2, 'no_smoke', return_value={'smoke_state': 'absent'}), \
             mock.patch.object(e2, 'cleanup_scratch', side_effect=cleanup), \
             mock.patch.object(e2, 'run_owned', side_effect=command):
            code = e2.native(args)
        (self.root / 'command-cleanup-trace.json').write_text(json.dumps(trace, indent=2))
        phase = json.loads((output / 'phase.json').read_text())
        return code, phase, output, trace

    def assert_clean_source_failure(self, guard_count, expected_scenarios, reason):
        code, phase, output, trace = self.controlled_phase(source_failure_at=guard_count)
        self.assertIn('source_failure', trace, 'control must fail source validation with drained processes')
        self.assertFalse(trace['fake_owned_remains'])
        self.assertTrue(phase['owned_processes_gone'], 'source failure must not be modeled as unsafe cleanup')
        self.assertFalse(any(argv[0] == 'node' for argv in trace['attempts']),
                         'source-invalid checkout must launch no renderer process')
        self.assertEqual(len(phase['processes']), expected_scenarios)
        self.assertEqual(code, 1); self.assertTrue(phase['failed']); self.assertFalse(phase['infrastructure_valid'])
        self.assertEqual(phase['evidence_status'], 'INCOMPLETE', 'renderer skip leaves final execution evidence incomplete')
        self.assertLess(phase['probe_calls'], 240, 'schedule remains incomplete')
        skipped = json.loads((output / '01-A/renderer-skipped.json').read_text())
        self.assertTrue(skipped['failed']); self.assertTrue(skipped['skipped'])
        self.assertEqual(skipped['reason'], reason)
        self.assertEqual(skipped['evidence_status'], 'INCOMPLETE')
        self.assertEqual(phase['renderers'], [skipped])
        hashes = json.loads((output / 'artifact-hashes.json').read_text())
        expected = json.loads((output / 'evidence-receipt.json').read_text())['expected']
        name = '01-A/renderer-skipped.json'
        self.assertEqual(hashes[name], hashlib.sha256((output / name).read_bytes()).hexdigest())
        self.assertEqual(expected[name], hashes[name], 'skip receipt must be durable evidence')
        for path in (output / 'commands').glob('*/process.json'):
            self.assertTrue(json.loads(path.read_text())['cleanup_ok'], 'guard commands really drained safely')

    def test_clean_post_scenario_source_failure_prohibits_renderer(self):
        self.assert_clean_source_failure(3, 1, 'scenario infrastructure invalid')

    def test_clean_second_scenario_source_failure_prohibits_renderer(self):
        self.assert_clean_source_failure(5, 2, 'scenario infrastructure invalid')

    def test_renderer_prelaunch_source_failure_prohibits_node(self):
        self.assert_clean_source_failure(6, 2, 'renderer source validation failed')

    def test_clean_invalid_audit_prohibits_renderer(self):
        code, phase, output, trace = self.controlled_phase(invalid_audit=True)
        self.assertTrue(phase['owned_processes_gone'])
        self.assertFalse(any(argv[0] == 'node' for argv in trace['attempts']),
                         'any infrastructure-invalid scenario must prohibit renderer execution')
        self.assertEqual(code, 1); self.assertFalse(phase['infrastructure_valid'])
        self.assertEqual(len(phase['processes']), 1)
        self.assertEqual(json.loads((output / '01-A/renderer-skipped.json').read_text())['reason'],
                         'scenario infrastructure invalid')

    def test_real_renderer_path_continues_after_valid_performance_failure(self):
        code, phase, output, trace = self.controlled_phase(performance_failure=True)
        self.assertEqual(code, 1); self.assertTrue(phase['failed']); self.assertTrue(phase['infrastructure_valid'])
        self.assertTrue(phase['owned_processes_gone'])
        self.assertEqual(len(phase['processes']), 8); self.assertEqual(len(phase['renderers']), 4)
        self.assertEqual(trace['renderer_guard_counts'], [6, 11, 16, 21],
                         'each real renderer needs its own immediately preceding source guard')
        for block, renderer in zip(('01-A', '02-B', '03-B', '04-A'), phase['renderers']):
            self.assertFalse(renderer['failed'])
            self.assertTrue((output / block / 'renderer/source-before.json').is_file())
        for process in phase['processes']:
            self.assertTrue(process['infrastructure_valid'])
            self.assertEqual(process['original_failed'], process['scenario'] == 'p1-4500')
            self.assertEqual(process['process']['returncode'], 1 if process['original_failed'] else 0)

    def assert_snapshot_gap_blocks(self, status):
        code, phase, output, trace = self.controlled_phase(snapshot_gap=status)
        gap = trace['snapshot_gap']
        self.assertEqual(gap['pinned'], [420001, 420002], 'fixture must pin leader and live owned child before omission')
        self.assertGreater(gap['omitted_snapshots'], 0, 'fixture must exercise the partial snapshot after leader reap')
        self.assertTrue(gap['leader_reaped'])
        receipt_path = output / '01-A/p1-4500/process.json'
        receipt = json.loads(receipt_path.read_text())
        self.assertEqual(receipt, gap['receipt'])
        self.assertEqual(receipt['descendants_observed'][0]['ownership_proof']['kind'], 'observed-parent')
        hashes = json.loads((output / 'artifact-hashes.json').read_text())
        expected = json.loads((output / 'evidence-receipt.json').read_text())['expected']
        for name in ('process.json', 'stdout.raw', 'stderr.raw'):
            relative = '01-A/p1-4500/' + name
            digest = hashlib.sha256((output / relative).read_bytes()).hexdigest()
            self.assertEqual(hashes[relative], digest)
            self.assertEqual(expected[relative], digest, 'failure evidence must retain completion bytes')
        unsafe_index = next(i for i, argv in enumerate(trace['attempts']) if argv[0].endswith('/vitest'))
        checks = {'cleanup_rejected': receipt['cleanup_ok'] is False,
                  'latch_closed': phase['owned_processes_gone'] is False,
                  'no_later_owned_command': trace['attempts'][unsafe_index + 1:] == [],
                  'no_renderer': not any(argv[0] == 'node' for argv in trace['attempts']),
                  'scratch_retained': (self.root / 'fixture-checkout/backend/.capacity-e2-scratch-12-1').is_dir()
                                      and not trace['scratch_deletions'] and not phase['scratch_cleanup_ok'],
                  'no_foreign_signal': not gap['signals'] and not gap['numeric_signals'],
                  'unproved_handle_retained': 520002 not in gap['closed'],
                  'receipt_fail_closed': phase['failed'] is True and phase['infrastructure_valid'] is False and code == 1}
        (self.root / 'snapshot-gap-checks.json').write_text(json.dumps(checks, indent=2))
        self.assertEqual(checks, dict.fromkeys(checks, True), 'omitted retained pidfd must prohibit cleanup and all later work')
        with mock.patch.object(e2, 'SAFETY', phase), mock.patch.object(e2, 'run_owned') as later:
            with self.assertRaisesRegex(ValueError, 'owned command prohibited after unsafe cleanup'):
                e2.owned_command(['CONTROL-later'], self.root, self.root / 'later', 1)
            later.assert_not_called()
            self.assertFalse(phase['owned_processes_gone'], 'a later command cannot reopen the latch')
        self.assertTrue(receipt['ownership_errors'], 'the failed exit proof must be recorded')

    def test_omitted_live_pinned_descendant_blocks_cleanup_and_later_work(self):
        self.assert_snapshot_gap_blocks('live')

    def test_omitted_uncheckable_pidfd_blocks_cleanup_and_later_work(self):
        self.assert_snapshot_gap_blocks('uncheckable')

    def test_omitted_ambiguous_pidfd_blocks_cleanup_and_later_work(self):
        self.assert_snapshot_gap_blocks('ambiguous')

    def test_omitted_exited_descendant_requires_pidfd_proof_and_adopted_reap(self):
        code, phase, output, trace = self.controlled_phase(snapshot_gap='exited')
        gap = trace['snapshot_gap']
        self.assertEqual(gap['pinned'], [420001, 420002])
        self.assertGreater(gap['omitted_snapshots'], 0)
        self.assertTrue(gap['adopted_reaped'])
        self.assertTrue(any(p['fd'] == 520002 and p['omitted'] and p.get('events') == [[520002, select.POLLIN | select.POLLHUP]]
                            for p in json.loads((self.root / 'command-cleanup-trace.json').read_text())['snapshot_gap']['polls']),
                        'cleanup needs independent retained pidfd exit proof after omission')
        self.assertIn(520002, gap['closed'])
        self.assertTrue(gap['receipt']['cleanup_ok']); self.assertFalse(gap['receipt']['ownership_errors'])
        self.assertFalse(gap['signals']); self.assertFalse(gap['numeric_signals'])
        self.assertEqual(code, 0); self.assertTrue(phase['owned_processes_gone'])
        self.assertTrue(phase['artifact_members_ok']); self.assertTrue(phase['scratch_cleanup_ok'])
        self.assertEqual(len(phase['processes']), 8); self.assertEqual(len(phase['renderers']), 4)

    def assert_unsafe_command(self, kind):
        code, phase, output, trace = self.controlled_phase(failure_at=kind)
        self.assertTrue(trace['fake_owned_remains'])
        self.assertFalse(trace['scratch_deletions'], 'unsafe cleanup prohibits scratch deletion')
        renderer_attempts = [c for c in trace['attempts'] if c[0] == 'node']
        self.assertEqual(len(renderer_attempts), 1 if kind == 'renderer' else 0,
                         'cleanup failure prohibits any later renderer launch')
        unsafe_index = next(i for i, c in enumerate(trace['attempts']) if
                            (kind == 'renderer' and c[0] == 'node') or
                            (kind == 'vitest' and c[0].endswith('/vitest')) or
                            (kind in ('guard', 'runtime') and c == ['CONTROL-' + kind, '2']))
        self.assertEqual(trace['attempts'][unsafe_index + 1:], [],
                         'every command category must stop later launches after unsafe cleanup')
        self.assertFalse(phase['owned_processes_gone'], 'persistent latch cannot be reset by later guard success')
        self.assertFalse(phase['infrastructure_valid']); self.assertTrue(phase['failed']); self.assertEqual(code, 1)

    def test_guard_cleanup_failure_blocks_renderer_and_scratch_deletion(self):
        self.assert_unsafe_command('guard')

    def test_runtime_cleanup_failure_blocks_renderer_and_scratch_deletion(self):
        self.assert_unsafe_command('runtime')

    def test_vitest_cleanup_failure_blocks_renderer_and_scratch_deletion(self):
        self.assert_unsafe_command('vitest')

    def test_renderer_cleanup_failure_blocks_next_command_and_scratch_deletion(self):
        self.assert_unsafe_command('renderer')

    def assert_before_hash_failure(self, relative, change=False):
        def mutate(output):
            path = output / relative
            self.assertTrue(path.is_file(), 'mutation must target already completed real evidence')
            if change: path.write_bytes(path.read_bytes() + b'CONTROL changed before manifest\n')
            else: path.unlink()
        code, phase, output, trace = self.controlled_phase(mutation=mutate)
        self.assertEqual(len(trace['original_raw_hashes']), 8, 'all ABBA Python processes must have completed')
        self.assertEqual((phase['original_samples'], phase['original_gate_evaluations'], phase['probe_calls']),
                         (4080, 208, 240), 'retain the actual audited observations')
        self.assertEqual(code, 1, 'pre-manifest evidence loss/drift must prohibit success')
        self.assertFalse(phase['artifact_members_ok']); self.assertFalse(phase['infrastructure_valid'])
        self.assertEqual(phase['evidence_status'], 'INCOMPLETE')
        report = json.loads((output / 'descriptive-effect.json').read_text())
        self.assertEqual(report['status'], 'infrastructure-invalid')
        self.assertEqual(report['evidence_status'], 'INCOMPLETE')
        self.assertIn('required artifact membership failed', report['final_exclusions'])
        self.assertEqual(len(report['comparisons']), 4)
        self.assertTrue(all(c['criterion_support'] for c in report['comparisons']), 'retain descriptive numbers, revoke conclusion')

    def test_before_final_hash_removed_abba_raw_log_is_incomplete(self):
        self.assert_before_hash_failure('01-A/p1-4500/stdout.raw')

    def test_before_final_hash_changed_abba_raw_bytes_are_incomplete(self):
        self.assert_before_hash_failure('01-A/p1-4500/stdout.raw', change=True)

    def test_before_final_hash_missing_renderer_output_is_incomplete(self):
        self.assert_before_hash_failure('01-A/RESULTS.md')

    def test_before_final_hash_missing_process_receipt_is_incomplete(self):
        self.assert_before_hash_failure('01-A/p1-4500/process.json')

    def test_before_final_hash_missing_result_output_is_incomplete(self):
        self.assert_before_hash_failure('01-A/p1-4500/audit.json')

    def test_completed_evidence_keeps_both_original_emissions_byte_exact(self):
        code, phase, output, trace = self.controlled_phase()
        self.assertEqual(code, 0); self.assertTrue(phase['artifact_members_ok'])
        for block in ('01-A', '02-B', '03-B', '04-A'):
            original = b''
            for scenario in ('p1-4500', 'plan-6000'):
                relative = block + '/' + scenario + '/stdout.raw'
                raw = (output / relative).read_bytes()
                self.assertEqual(hashlib.sha256(raw).hexdigest(), trace['original_raw_hashes'][relative])
                self.assertEqual(raw.count(b'CAPACITY_RESULT '), 1)
                original += raw + (output / block / scenario / 'stderr.raw').read_bytes()
            self.assertEqual((output / block / 'capacity.log').read_bytes(), original)
            self.assertEqual(original.count(b'CAPACITY_RESULT '), 2, 'both raw original emits remain unnormalized')


class SingleSerializationControls(DisposableControls):
    def sample_loop_control(self, projection, drift=False):
        """Execute the checked-in setup helpers and 30-call body with Node-only data.

        Count every stringify/encode while each call is active, including helper
        calls. Reference/oracle construction finishes before the extracted loop.
        """
        source = RUNNER.with_name('capacity-history-e2-probe.ts').read_text()
        helpers = source.split('// Root file afterAll executes', 1)[0]
        helpers = '\n'.join(line for line in helpers.splitlines() if not line.startswith('import '))
        helpers = helpers.replace("'__E2_PROJECTION__'", json.dumps(projection))
        body = source.split("    const reference=before.reads.find", 1)[1].split('    const plansAfter=await plans', 1)[0]
        body = '    const reference=before.reads.find' + body
        directory = self.root / (('full' if projection == '*' else 'id') + ('-drift' if drift else ''))
        directory.mkdir()
        harness = r'''
const crypto = require('node:crypto');
const sha256 = raw => crypto.createHash('sha256').update(raw).digest();
const originalStringify = JSON.stringify;
const NativeEncoder = TextEncoder;
const traces = [], expected = [];
let active, calls = 0, clockCalls = 0;
JSON.stringify = function(value, ...args) {
  if(active) active.serializations.push({kind:value===active.returned ? 'complete' :
    value===active.returned.results ? 'results' : value===active.returned.meta ? 'meta' :
    active.returned.results.includes(value) ? 'row' : 'derived', after_timing:clockCalls===2});
  return originalStringify(value, ...args);
};
globalThis.TextEncoder = class extends NativeEncoder {
  encode(value) {
    if(active) active.encodings.push({complete:value===expected[calls-1].json, after_timing:clockCalls===2});
    return super.encode(value);
  }
};
const fullRows = Array.from({length:10}, (_, i) => ({
  id:'00000000-0000-4000-8000-'+String(i+20).padStart(12,'0'),
  diff_json:originalStringify([{field:'review',before:'"\\,{}[]\n\r\t餐😀é',after:String.fromCharCode(0xd800)}]),
  search_text:'é餐😀"\\,{}[]\n'.repeat(i+1), created_at:1793448000000-i, deleted_at:null
}));
const referenceRows = fullRows.map(row => ({id:row.id, row_json_utf8_bytes:Buffer.byteLength(originalStringify(row))}));
const rows = ARM==='*' ? fullRows : fullRows.map(row=>({id:row.id}));
const orders = [['success','meta','results'],['success','results','meta'],['meta','success','results'],
  ['meta','results','success'],['results','success','meta'],['results','meta','success']];
const returned = Array.from({length:30}, (_, index) => {
  const meta = {duration:0.5,size_after:123+index,rows_read:10,rows_written:0,last_row_id:0,changed_db:false,changes:0};
  const values = {success:true,meta,results:rows};
  const result = Object.fromEntries(orders[index%6].map(key=>[key,values[key]]));
  const json = originalStringify(result), bytes = Buffer.from(json);
  expected.push({json, json_result_utf8_bytes:bytes.byteLength,
    json_result_sha256:crypto.createHash('sha256').update(bytes).digest('hex'),
    json_representation:{property_order:Object.keys(result),meta_json:originalStringify(meta),
      members:Object.keys(result).map(key=>({key,key_json_utf8_bytes:Buffer.byteLength(originalStringify(key)),
        value_json_utf8_bytes:Buffer.byteLength(originalStringify(result[key]))})),
      row_json_utf8_bytes:rows.map(row=>Buffer.byteLength(originalStringify(row)))}});
  return result;
});
if(DRIFT) rows[0].search_text += 'CONTROL changed after reference';
const env = {DB:{prepare() {
  clockCalls=0; calls++;
  active={returned:returned[calls-1],serializations:[],encodings:[]}; traces.push(active);
  return {bind(){return this;},async all(){return active.returned;}};
}}};
Date.now = () => {
  clockCalls++;
  const value=1000+20*calls+[0,2,3][clockCalls-1];
  if(clockCalls===3) active=undefined;
  return value;
};
'''.replace('ARM', json.dumps(projection)).replace('DRIFT', 'true' if drift else 'false')
        setup = "const before={reads:[{label:'history_full',results:referenceRows}]};\nconst context={full_sql:'CONTROL full',id_sql:'CONTROL id',params:[],name:'CONTROL'};\nconst task={}, plansBefore={}; let evidence;\n"
        tail = "\nreturn samples;\n})().then(samples=>finish({samples}),error=>finish({error:String(error)}));\nfunction finish(value) { active=undefined; console.log=savedLog; for(const trace of traces) delete trace.returned; savedLog(originalStringify({...value,traces,expected,reference:referenceRows})); }\n"
        script = directory / 'sample-control.cjs'
        script.write_text(harness + helpers + '\n(async()=>{\n' + setup + body + tail)
        command = ['node', str(script)]
        receipt = subprocess.run(command, capture_output=True, timeout=10)
        (directory / 'stdout').write_bytes(receipt.stdout); (directory / 'stderr').write_bytes(receipt.stderr)
        (directory / 'receipt.json').write_text(json.dumps({'command':command,'exit_code':receipt.returncode,
            'probe_sha256':hashlib.sha256(source.encode()).hexdigest(),'controlled_only':True}, indent=2))
        self.assertEqual(receipt.returncode, 0, receipt.stderr.decode())
        return json.loads(receipt.stdout)

    def test_single_returned_payload_serialization_and_encoding_per_sample(self):
        for projection in ('*', 'id'):
            with self.subTest(projection=projection):
                value = self.sample_loop_control(projection)
                self.assertIsNone(value.get('error'))
                self.assertEqual(len(value['samples']), 30); self.assertEqual(len(value['traces']), 30)
                for index, trace in enumerate(value['traces'], 1):
                    self.assertEqual(trace['serializations'], [{'kind':'complete','after_timing':True}],
                                     f'sample {index}: returned payload must be serialized exactly once after timing')
                    self.assertEqual(trace['encodings'], [{'complete':True,'after_timing':True}],
                                     f'sample {index}: complete representation must be UTF-8 encoded exactly once')

    def test_single_representation_preserves_utf8_components_order_and_sha(self):
        for projection in ('*', 'id'):
            with self.subTest(projection=projection):
                value = self.sample_loop_control(projection)
                self.assertIsNone(value.get('error'))
                ids = [row['id'] for row in value['reference']]
                lengths = {row['id']:row['row_json_utf8_bytes'] for row in value['reference']}
                for index, (sample, expected) in enumerate(zip(value['samples'], value['expected']), 1):
                    for key in ('json_result_utf8_bytes', 'json_result_sha256', 'json_representation'):
                        self.assertEqual(sample[key], expected[key])
                    e2.audit_sample(sample, index, ids, lengths, projection)
                self.assertEqual(len(value['samples']), 30)
                self.assertEqual(len({tuple(s['json_representation']['property_order']) for s in value['samples']}), 6)

    def test_single_representation_rejects_attested_row_length_drift(self):
        value = self.sample_loop_control('*', drift=True)
        self.assertIn('row projection bytes', value.get('error', ''))
        self.assertNotIn('samples', value)
        self.assertEqual(len(value['traces']), 1, 'drift cannot continue to later calls')


class AdditionalControls(DisposableControls):
    def test_actual_spawn_error_and_signal_receipts_are_preserved(self):
        directory=self.root/'spawn'; directory.mkdir()
        value=e2.run_owned([str(self.root/'absent-executable')],self.root,directory,1)
        self.assertIsNone(value['returncode']); self.assertTrue(value['spawn_error']); self.assertTrue(value['cleanup_ok'])
        directory=self.root/'signal'; directory.mkdir()
        value=e2.run_owned([sys.executable,'-B','-c','import os,signal; os.kill(os.getpid(),signal.SIGTERM)'],self.root,directory,1)
        self.assertEqual(value['returncode'],-15); self.assertEqual(value['signal'],15); self.assertTrue(value['cleanup_ok'])
    def test_source_and_output_ownership_never_touch_unowned_paths(self):
        with mock.patch.dict(os.environ, {'RUNNER_TEMP':str(self.root)}):
            for path in (RUNNER.parents[2]/'forbidden-output',self.root):
                with self.assertRaises(ValueError): e2.safe_output(path,RUNNER.parents[2])
            target=self.root/'target'; target.mkdir()
            link=self.root/'link'; link.symlink_to(target,target_is_directory=True)
            with self.assertRaises(ValueError): e2.safe_output(link/'unsafe',RUNNER.parents[2])
            self.assertFalse((target/'unsafe').exists())
    def test_inter_call_clock_reversal_rejected_without_dropping_samples(self):
        parts=dummy_probe_raw()
        sample=parts[2]['samples'][1]
        sample.update(start_ms=0,stop_ms=2,bookkeeping_stop_ms=3)
        with self.assertRaises(ValueError):
            e2.audit_probe(probe_log(*parts),e2.audit_original(parts[0],'p1-4500'),'p1-4500','id',RUNNER.parents[2])


class OriginalShapeControls(unittest.TestCase):
    def test_original_cardinality_api_paths_clock_and_write_metadata_are_required(self):
        mutations=(lambda x:x['queries'][-1]['samples'][0].__setitem__('returnedRows',[9]),
                   lambda x:x['actualAPI'][0].__setitem__('path','/CONTROL wrong API'),
                   lambda x:x.__setitem__('wallClock','CONTROL wrong clock'),
                   lambda x:x['write'].__setitem__('sqlMs',True),
                   lambda x:x['rowCounts'].__setitem__('users',True),
                   lambda x:x['integrity'].__setitem__('quickCheck','CONTROL bad integrity'))
        for mutate in mutations:
            value=dummy_original();mutate(value)
            with self.subTest(mutate=mutate),self.assertRaises(ValueError): e2.audit_original(emit(value),'p1-4500')



class ScenarioWideControls(DisposableControls):
    def drift_cohort(self, scenario, kind):
        values = supportive_control_cohort()
        for value in values:
            if value['scenario'] != scenario: continue
            probe = value['probe_audit']
            second = value['block'] in ('03-B', '04-A')
            for stage in ('plans_before', 'plans_after'):
                for arm in ('full', 'id'):
                    probe['history'][stage][arm]['results'][0]['detail'] = 'CONTROL plan Q' if kind == 'plan' and second else 'CONTROL plan P'
            probe['original_plan'] = [r['detail'] for r in probe['history']['plans_before']['full']['results']]
            for sample in probe['history']['samples']:
                sample['meta']['rows_read'] = 11 if kind == 'rows' and second else 10
        return values

    def assert_scenario_drift(self, kind):
        for scenario in ('p1-4500', 'plan-6000'):
            with self.subTest(scenario=scenario):
                report = e2.describe_effect(self.drift_cohort(scenario, kind))
                (self.root / (scenario + '-' + kind + '-report.json')).write_text(json.dumps(report, indent=2))
                print('CONTROL scenario drift', scenario, kind, 'actual status:', report['status'])
                self.assertTrue(all(c['criterion_support'] for c in report['comparisons']))
                self.assertEqual(report['status'], 'confounded')
                expected = ['scenario-wide SQL plan changed', 'scenario-wide original emitted plan differs'] if kind == 'plan' else ['scenario-wide rows_read shifted']
                for comparison in report['comparisons']:
                    self.assertEqual(comparison['exclusions'], expected if comparison['scenario'] == scenario else [])

    def test_between_pair_plan_drift_excludes_support(self):
        self.assert_scenario_drift('plan')

    def test_between_pair_rows_read_drift_excludes_support(self):
        self.assert_scenario_drift('rows')

    def test_globally_invariant_support_preserves_all_descriptive_measurements(self):
        report = e2.describe_effect(supportive_control_cohort())
        self.assertEqual(report['status'], 'result-size-related path support')
        self.assertEqual(len(report['comparisons']), 4)
        self.assertTrue(all(not c['exclusions'] and c['criterion_support'] for c in report['comparisons']))
        self.assertEqual(report['issue19'], 'UNRESOLVED')

    def test_every_block_stage_arm_and_original_plan_is_scenario_bound(self):
        for scenario in ('p1-4500', 'plan-6000'):
            for block in ('01-A', '02-B', '03-B', '04-A'):
                for stage in ('plans_before', 'plans_after'):
                    for arm in ('full', 'id'):
                        with self.subTest(scenario=scenario, block=block, stage=stage, arm=arm):
                            values = supportive_control_cohort()
                            probe = next(v['probe_audit'] for v in values if (v['scenario'], v['block']) == (scenario, block))
                            probe['history'][stage][arm]['results'][0]['detail'] += ' CONTROL drift'
                            report = e2.describe_effect(values)
                            self.assertEqual(report['status'], 'confounded')
                            self.assertTrue(all('scenario-wide SQL plan changed' in c['exclusions'] for c in report['comparisons'] if c['scenario'] == scenario))
                with self.subTest(scenario=scenario, block=block, original=True):
                    values = supportive_control_cohort()
                    probe = next(v['probe_audit'] for v in values if (v['scenario'], v['block']) == (scenario, block))
                    probe['original_plan'] = ['CONTROL different original plan']
                    report = e2.describe_effect(values)
                    self.assertEqual(report['status'], 'confounded')
                    self.assertTrue(all('scenario-wide original emitted plan differs' in c['exclusions'] for c in report['comparisons'] if c['scenario'] == scenario))

    def test_equal_pair_local_sample_drift_is_still_scenario_confounded(self):
        for scenario in ('p1-4500', 'plan-6000'):
            for index in range(30):
                with self.subTest(scenario=scenario, sample=index + 1):
                    values = self.drift_cohort(scenario, 'none')
                    for value in values:
                        if value['scenario'] == scenario:
                            value['probe_audit']['history']['samples'][index]['meta']['rows_read'] = 11
                    report = e2.describe_effect(values)
                    self.assertEqual(report['status'], 'confounded')
                    self.assertTrue(all(c['exclusions'] == ['scenario-wide rows_read shifted'] for c in report['comparisons'] if c['scenario'] == scenario))


class NativeScratchArchiveControls(DisposableControls):
    members = ('config.ts', 'setup.ts', 'config-facts.json')

    def native_scratch_control(self, mutation=None, missing_facts=False, copy_failure=None, freeze_failure=None, facts_mutation=None,
                               archive_failure=None, final_guard=None):
        """Real materialization/cleanup/completion/finalization/reopening; command seams use dummy bytes only."""
        case = Path(tempfile.mkdtemp(prefix='native-scratch-', dir=self.root))
        checkout = case / 'checkout'
        for name in e2.SNAPSHOT_SOURCES:
            target = checkout / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((RUNNER.parents[2] / name).read_bytes())
        output = case / 'evidence'
        identity = dict(candidate='1' * 40, ref=e2.REF, run='12', attempt='1', runtime={'control_only': True})
        facts = control_config_facts(checkout / 'backend')
        if facts_mutation: facts_mutation(facts)
        source = {'manifest': {name: {'sha256': hashlib.sha256((checkout / name).read_bytes()).hexdigest(),
                                     'blob': hashlib.sha1(b'blob ' + str((checkout / name).stat().st_size).encode() + b'\0' + (checkout / name).read_bytes()).hexdigest()}
                               for name in e2.SNAPSHOT_SOURCES}}
        trace = {'unlinks': [], 'freezes': [], 'cleanup_called': False}
        cleanup = e2.cleanup_scratch
        materialize = e2.materialize_scratch
        freeze = e2.freeze_evidence
        unlink = Path.unlink
        read_bytes = Path.read_bytes
        open_path = Path.open
        def materialized(*args):
            owner = materialize(*args)
            facts.update(path_facts=owner['path_facts'], config_sha256=owner['config_sha256'],
                         setup_path=owner['path_facts']['setup']['path'])
            return owner
        def guard(root, candidate, ref, scratch=None):
            if final_guard and scratch is None and trace['cleanup_called']:
                return final_guard(root, candidate, ref)
            return source
        def command(argv, cwd, directory, timeout):
            directory = Path(directory)
            if argv[0].endswith('/vitest'):
                scenario = argv[-1]
                original, preflight, history = dummy_probe_raw(scenario, '*')
                preflight['original_task']['filepath'] = str(checkout / 'backend/tests/capacity/capacity_test.ts')
                history['original_task']['filepath'] = preflight['original_task']['filepath']
                raw = probe_log(original, preflight, history)
                if not missing_facts:
                    config = Path(cwd) / argv[argv.index('--config') + 1]
                    (config.parent / 'config-facts.json').write_text(json.dumps(facts))
            elif argv[0] == 'node':
                block = directory.parent
                (block / 'measurements.json').write_bytes(b'{}')
                (block / 'RESULTS.md').write_bytes(b'CONTROL renderer output')
                raw = b'CONTROL renderer complete\n'
            else:
                raise AssertionError('unexpected native command: ' + repr(argv))
            (directory / 'stdout.raw').write_bytes(raw)
            (directory / 'stderr.raw').write_bytes(b'')
            receipt = dict(returncode=0, cleanup_ok=True, timed_out=False, spawn_error=None, descendants_after_exit=[],
                           cwd=str(cwd),
                           stdout_sha256=hashlib.sha256(raw).hexdigest(), stderr_sha256=hashlib.sha256(b'').hexdigest())
            e2.write_json(directory / 'process.json', receipt)
            return receipt
        def record_unlink(target, *args, **kwargs):
            if trace['cleanup_called'] and target.parent.name.startswith('.capacity-e2-scratch-') and target.exists():
                name = 'scratch/' + target.name
                trace['unlinks'].append({'name': name, 'ledger': copy.deepcopy(e2.EXPECTED_EVIDENCE),
                                        'original_sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
            return unlink(target, *args, **kwargs)
        def record_freeze(target, digest=None):
            target = Path(target)
            if target.parent == output / 'scratch' and target.name in self.members:
                trace['freezes'].append({'name': target.name, 'exists': target.is_file()})
                if target.name == freeze_failure: raise OSError('CONTROL scratch freeze failed')
            return freeze(target, digest)
        def real_cleanup(owner, archive):
            trace['cleanup_called'] = True
            scratch = Path(owner['path'])
            trace['scratch_path'] = str(scratch)
            trace['original_hex'] = {p.name: p.read_bytes().hex() for p in scratch.iterdir()}
            if copy_failure:
                # Exclusive-copy failure at the actual archive destination; preserve this foreign file.
                (Path(archive) / copy_failure).write_bytes(b'CONTROL foreign archive member')
            read_failed = False
            ownership_read = False
            def fault_read(target):
                nonlocal read_failed, ownership_read
                if archive_failure:
                    stage, member = archive_failure
                    if stage == 'read' and target == scratch / member and not read_failed:
                        # config facts now have a prerequisite ownership read;
                        # this control targets the subsequent archive copy read.
                        if member == 'config-facts.json' and not ownership_read:
                            ownership_read = True
                            return read_bytes(target)
                        read_failed = True
                        raise OSError('CONTROL scratch read failed')
                    if stage == 'verification' and target == Path(archive) / member:
                        return read_bytes(target) + b'CONTROL verification drift'
                return read_bytes(target)
            def fault_open(target, mode='r', *args, **kwargs):
                if archive_failure and archive_failure[0] == 'copy' and target == Path(archive) / archive_failure[1] and mode == 'xb':
                    with open_path(target, mode) as copied: copied.write(b'CONTROL partial archive copy')
                    raise OSError('CONTROL scratch copy failed')
                return open_path(target, mode, *args, **kwargs)
            try:
                with mock.patch.object(Path, 'read_bytes', fault_read), mock.patch.object(Path, 'open', fault_open):
                    return cleanup(owner, archive)
            finally:
                trace['cleanup_ledger'] = copy.deepcopy(e2.EXPECTED_EVIDENCE)
                trace['scratch_exists_after'] = scratch.exists()
                trace['remaining_hex'] = {p.name: p.read_bytes().hex() for p in scratch.iterdir()} if scratch.exists() else {}
                if mutation: mutation(output)
        with mock.patch.dict(os.environ, dict(RUNNER_TEMP=str(case), GITHUB_SHA=identity['candidate'], GITHUB_REF=identity['ref'],
                                               GITHUB_RUN_ID=identity['run'], GITHUB_RUN_ATTEMPT=identity['attempt'])), \
             mock.patch.object(e2, '__file__', str(checkout / '.github/scripts/capacity-history-e2.py')), \
             mock.patch.object(e2, 'source_guard', side_effect=guard), \
             mock.patch.object(e2, 'runtime', return_value=identity['runtime']), \
             mock.patch.object(e2, 'no_smoke', return_value={'smoke_state': 'absent'}), \
             mock.patch.object(e2, 'cleanup_scratch', side_effect=real_cleanup), \
             mock.patch.object(e2, 'materialize_scratch', side_effect=materialized), \
             mock.patch.object(e2, 'freeze_evidence', side_effect=record_freeze), \
             mock.patch.object(Path, 'unlink', record_unlink), \
             mock.patch.object(e2, 'run_owned', side_effect=command):
            code = e2.native(mock.Mock(phase='preflight', output=str(output)))
            phase = e2.parse_json((output / 'phase.json').read_bytes())
            receipt = e2.parse_json((output / 'evidence-receipt.json').read_bytes())
            try:
                e2.preflight_gate(output, identity)
                reopened = True
            except (ValueError, OSError): reopened = False
        trace.update(code=code, phase=phase, receipt=receipt, reopened=reopened)
        (case / 'trace.json').write_text(json.dumps(trace, indent=2))
        print('CONTROL native scratch', mutation.__name__ if mutation else 'clean', 'exit:', code,
              'status:', phase['evidence_status'], 'reopened:', reopened,
              'completion members:', sorted(n for n in receipt['expected'] if n in ('scratch/' + m for m in self.members)))
        return output, trace

    def test_real_cleanup_freezes_each_required_member_before_unlink(self):
        output, trace = self.native_scratch_control()
        self.assertTrue(trace['cleanup_called'])
        self.assertEqual(trace['code'], 0)
        self.assertTrue(trace['reopened'])
        self.assertEqual(trace['phase']['evidence_status'], 'COMPLETE')
        self.assertFalse(trace['scratch_exists_after'])
        for member in self.members:
            name = 'scratch/' + member
            with self.subTest(member=member):
                observed = next(v for v in trace['unlinks'] if v['name'] == name)
                digest = hashlib.sha256((output / name).read_bytes()).hexdigest()
                self.assertEqual(observed['original_sha256'], digest)
                self.assertEqual(observed['ledger'].get(name), digest, 'must freeze copied bytes before unlinking original')
                self.assertEqual(trace['receipt']['expected'].get(name), digest)
        self.assertEqual({f['name'] for f in trace['freezes']}, set(self.members))

    def assert_changed_archive(self, change):
        for member in self.members:
            with self.subTest(member=member, change=change):
                def mutate(output):
                    target = output / 'scratch' / member
                    if change: target.write_bytes(target.read_bytes() + b'CONTROL changed after cleanup\n')
                    else: target.unlink()
                output, trace = self.native_scratch_control(mutation=mutate)
                self.assertEqual(trace['code'], 1)
                self.assertFalse(trace['phase']['artifact_members_ok'])
                self.assertFalse(trace['phase']['infrastructure_valid'])
                self.assertEqual(trace['phase']['evidence_status'], 'INCOMPLETE')
                self.assertFalse(trace['reopened'])
                self.assertIsNotNone(trace['receipt']['expected'].get('scratch/' + member))

    def test_post_cleanup_removal_of_each_member_closes_real_finalization(self):
        self.assert_changed_archive(False)

    def test_post_cleanup_mutation_of_each_member_closes_real_finalization(self):
        self.assert_changed_archive(True)

    def test_unhashed_each_required_member_closes_verifier_and_reopening(self):
        for member in self.members:
            with self.subTest(member=member):
                output, trace = self.native_scratch_control()
                name = 'scratch/' + member
                receipt = trace['receipt']
                del receipt['expected'][name]
                (output / 'evidence-receipt.json').write_text(json.dumps(receipt))
                hashes = e2.artifact_hashes(output)
                (output / 'artifact-hashes.json').write_text(json.dumps(hashes))
                with self.assertRaises(ValueError): e2.verify_artifact_members(output, hashes)
                with self.assertRaises(ValueError): e2.preflight_gate(output, {k:trace['phase'][k] for k in ('candidate','ref','run','attempt','runtime')})

    def test_rehashed_omission_of_each_required_member_closes_verifier(self):
        for member in self.members:
            with self.subTest(member=member):
                output, trace = self.native_scratch_control()
                name = 'scratch/' + member
                (output / name).unlink()
                receipt = trace['receipt']; receipt['expected'].pop(name, None)
                (output / 'evidence-receipt.json').write_text(json.dumps(receipt))
                hashes = e2.artifact_hashes(output)
                (output / 'artifact-hashes.json').write_text(json.dumps(hashes))
                with self.assertRaises(ValueError): e2.verify_artifact_members(output, hashes)

    def test_each_required_member_symlink_replacement_closes_finalization(self):
        for member in self.members:
            with self.subTest(member=member):
                def replace(output):
                    target = output / 'scratch' / member
                    saved = output.parent / ('foreign-' + member); saved.write_bytes(target.read_bytes())
                    target.unlink(); target.symlink_to(saved)
                _, trace = self.native_scratch_control(mutation=replace)
                self.assertEqual(trace['code'], 1)
                self.assertFalse(trace['phase']['artifact_members_ok'])
                self.assertFalse(trace['reopened'])

    def test_missing_config_facts_still_cleans_owned_scratch_and_stays_incomplete(self):
        _, trace = self.native_scratch_control(missing_facts=True)
        self.assertEqual(trace['code'], 1)
        self.assertFalse(trace['scratch_exists_after'])
        self.assertFalse(trace['phase']['artifact_members_ok'])
        self.assertEqual(trace['phase']['evidence_status'], 'INCOMPLETE')
        self.assertIsNone(trace['receipt']['expected'].get('scratch/config-facts.json'))
        self.assertIn('scratch/config-facts.json', trace['receipt']['expected'])

    def test_archive_copy_collision_preserves_foreign_bytes_and_closes_evidence(self):
        for member in self.members:
            with self.subTest(member=member):
                output, trace = self.native_scratch_control(copy_failure=member)
                self.assertEqual((output / 'scratch' / member).read_bytes(), b'CONTROL foreign archive member')
                self.assertEqual(trace['code'], 1)
                self.assertFalse(trace['phase']['artifact_members_ok'])
                self.assertEqual(trace['phase']['evidence_status'], 'INCOMPLETE')
                self.assertFalse(trace['reopened'])

    def test_freeze_failure_cannot_complete_evidence(self):
        for member in self.members:
            with self.subTest(member=member):
                _, trace = self.native_scratch_control(freeze_failure=member)
                self.assertEqual(trace['code'], 1)
                self.assertFalse(trace['phase']['artifact_members_ok'])
                self.assertEqual(trace['phase']['evidence_status'], 'INCOMPLETE')
                self.assertFalse(trace['reopened'])

class ExecutorPoolControls(DisposableControls):
    members = NativeScratchArchiveControls.members

    def test_pinned_threads_file_is_accepted_by_python_audit(self):
        for scenario in ('p1-4500', 'plan-6000'):
            with self.subTest(scenario=scenario):
                raw, preflight, history = dummy_probe_raw(scenario)
                preflight['original_task']['pool'] = history['original_task']['pool'] = 'threads'
                original = e2.audit_original(raw, scenario)
                try:
                    audited = e2.audit_probe(probe_log(raw, preflight, history), original, scenario, 'id', RUNNER.parents[2])
                except ValueError as error:
                    self.fail('pinned actual File.pool=threads rejected: ' + str(error))
                self.assertEqual(audited['probe_calls'], 30)

    def native_file_guard(self, pool, root=True, filepath=None):
        source = RUNNER.with_name('capacity-history-e2-probe.ts').read_text()
        guard = next(line.strip() for line in source.splitlines() if "'original root file/pool'" in line)
        harness = self.root / 'native-file-guard.cjs'
        harness.write_text('const file=' + json.dumps(dict(pool=pool, filepath=filepath or str(RUNNER.parents[2] / 'backend/tests/capacity/capacity_test.ts'))) + ';\n'
                           + ('file.file=file;\n' if root else 'file.file={};\n')
                           + 'function check(v,m) { if(!v) throw new Error(m); }\n'
                           + 'try { ' + guard + '; console.log("accepted"); } catch(e) { console.error(e.message); process.exitCode=1; }\n')
        result = subprocess.run(['node', str(harness)], capture_output=True, text=True, timeout=10)
        print('CONTROL source-bound File.pool', pool, 'exit:', result.returncode, result.stderr.strip())
        return result

    def test_pinned_threads_file_is_accepted_by_native_source_guard(self):
        result = self.native_file_guard('threads')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_wrong_pool_and_unowned_native_root_are_rejected(self):
        for pool in ('@cloudflare/vitest-pool-workers', 'forks', '', None):
            with self.subTest(pool=pool):
                result = self.native_file_guard(pool)
                self.assertEqual(result.returncode, 1)
                self.assertIn('original root file/pool', result.stderr)
                raw, preflight, history = dummy_probe_raw()
                preflight['original_task']['pool'] = history['original_task']['pool'] = pool
                with self.assertRaisesRegex(ValueError, 'original file/pool'):
                    e2.audit_probe(probe_log(raw, preflight, history), e2.audit_original(raw, 'p1-4500'), 'p1-4500', 'id', RUNNER.parents[2])
        for kwargs in ({'root': False}, {'filepath': '/backend/tests/capacity/foreign.ts'}):
            with self.subTest(kwargs=kwargs):
                self.assertEqual(self.native_file_guard('threads', **kwargs).returncode, 1)

    def test_missing_and_drifted_configured_workers_facts_close_native_collection(self):
        for field in ('inherited_once', 'plugins_same', 'pool_options_same', 'isolatedStorage', 'remote_bindings', 'persistent_override', 'test_original'):
            for missing in (True, False):
                with self.subTest(field=field, missing=missing):
                    def mutate(facts):
                        if missing: facts.pop(field)
                        else: facts[field] = not facts[field] if type(facts[field]) is bool else {}
                    _, trace = NativeScratchArchiveControls.native_scratch_control(self, facts_mutation=mutate)
                    self.assertEqual(trace['code'], 1)
                    self.assertFalse(trace['phase']['infrastructure_valid'])
                    self.assertFalse(trace['reopened'])
                    self.assertTrue(all('resolved config' in p['error'] or 'malformed evidence' in p['error'] for p in trace['phase']['processes']))

    def test_resolved_workers_config_keeps_original_objects_and_rejects_missing_or_unsafe_workers(self):
        # The path contract now needs real owned files. Reuse the source-bound
        # body harness; it still imports no adapter and performs no D1 operation.
        control = WranglerPathControls('test_active_scratch_config_resolves_same_tracked_wrangler_bytes')
        control.root = self.root
        result, backend, scratch = control.configured()
        self.assertEqual(result.returncode, 0, result.stderr)
        facts = json.loads((scratch / 'config-facts.json').read_text())
        self.assertEqual(facts['binding_names'], ['TEST_MIGRATIONS'])
        self.assertEqual(facts['binding_hashes']['TEST_MIGRATIONS'], hashlib.sha256(json.dumps(['CONTROL ONLY'], separators=(',', ':')).encode()).hexdigest())
        self.assertTrue(facts['pool_options_same']); self.assertTrue(facts['plugins_same'])
        self.assertFalse(facts['isolatedStorage'])
        self.assertEqual(facts['wrangler_config'], str(backend / 'wrangler.toml'))
        self.assertEqual(facts['setup_path'], str(scratch / 'setup.ts'))
        mutations = ['delete resolved.test.poolOptions.workers;', 'delete resolved.test.poolOptions;',
                     'resolved.test.poolOptions.workers.isolatedStorage=true;',
                     "resolved.test.poolOptions.workers.wrangler.configPath='./foreign.toml';",
                     'resolved.test.poolOptions.workers.experimental_remoteBindings=true;',
                     "resolved.test.poolOptions.workers.miniflare.d1Databases=['foreign'];",
                     "resolved.test.poolOptions.workers.miniflare.d1Persist='foreign';",
                     "resolved.test.poolOptions.workers.miniflare.persist='foreign';",
                     "resolved.test.setupFiles=['foreign'];"]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=mutation):
                control.root = self.root / str(index); control.root.mkdir()
                result, _, scratch = control.configured(mutation=mutation)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((scratch / 'config-facts.json').exists())


class RelocatedConfigControls(DisposableControls):
    def relocated(self, body_mutation=None, path_mutation=None, cwd_mutation=False):
        backend = self.root / 'backend'; backend.mkdir()
        (backend / 'wrangler.toml').write_bytes((RUNNER.parents[2] / 'backend/wrangler.toml').read_bytes())
        archive = self.root / 'archive'; archive.mkdir()
        owner = e2.materialize_scratch(backend, '12', '1', b"const projection = '__E2_PROJECTION__';\n", archive)
        scratch = Path(owner['path'])
        relocated = backend / 'node_modules/.vite-temp'; relocated.mkdir(parents=True)
        # Inert marker: the old wrapper inspects this foreign module parent as
        # its backend and fails the existing supplemental-input guard before
        # calling the original config. The actual backend/scratch have none.
        (relocated.parent / '.env.control').write_bytes(b'')
        module = relocated / 'config.ts.timestamp-control.mjs'
        raw = (scratch / 'config.ts').read_bytes()
        if body_mutation: raw = body_mutation(raw)
        if path_mutation: path_mutation(owner, scratch)
        harness = self.root / 'relocation-control.mjs'
        harness.write_text('''import assert from 'node:assert/strict';
import { SourceTextModule, SyntheticModule } from 'node:vm';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
let calls=0;
const resolved={plugins:[{name:'inert-original-plugin'}],test:{include:['tests/capacity/capacity_test.ts'],testTimeout:600000,hookTimeout:60000,
  poolOptions:{workers:{isolatedStorage:false,wrangler:{configPath:'./wrangler.toml'},
    miniflare:{compatibilityDate:'2025-06-17',bindings:{TEST_MIGRATIONS:['CONTROL ONLY']}}}}}};
const workers=resolved.test.poolOptions.workers, miniflare=workers.miniflare, bindings=miniflare.bindings, wrangler=workers.wrangler;
const before=structuredClone(resolved.test.poolOptions);
const source=new SourceTextModule(Buffer.from(process.argv[1],'hex').toString(),{
  identifier:pathToFileURL(process.argv[2]).href,
  initializeImportMeta(meta,module){meta.url=module.identifier;}
});
await source.link(async specifier=>{
  if(specifier==='../vitest.capacity.config') return new SyntheticModule(['default'],function(){
    this.setExport('default',async()=>{calls++;return resolved;});
  });
  assert.ok(specifier.startsWith('node:'));
  const builtin=await import(specifier), names=Object.keys(builtin);
  return new SyntheticModule(names,function(){for(const name of names)this.setExport(name,builtin[name]);});
});
await source.evaluate();
try {
  const result=await source.namespace.default({});
  assert.equal(calls,1); assert.equal(result.plugins,resolved.plugins);
  assert.equal(result.test.poolOptions,resolved.test.poolOptions);
  assert.equal(result.test.poolOptions.workers,workers); assert.equal(workers.wrangler,wrangler);
  assert.equal(workers.miniflare,miniflare); assert.equal(miniflare.bindings,bindings);
  const normalized=structuredClone(result.test.poolOptions);normalized.workers.wrangler.configPath='./wrangler.toml';
  assert.deepEqual(normalized,before);
  assert.deepEqual(result.test.setupFiles,[process.argv[3]]);
  assert.equal(wrangler.configPath,process.argv[4]);
  console.log(JSON.stringify({module_url:source.identifier,setup_path:result.test.setupFiles[0],wrangler_path:wrangler.configPath,calls}));
} catch(error){console.error('original_calls='+calls);throw error;}
''')
        result = subprocess.run(['node', '--experimental-vm-modules', '--input-type=module', '-e', harness.read_text(), raw.hex(), str(module),
                                 str(scratch / 'setup.ts'), str(backend / 'wrangler.toml')],
                                cwd=self.root if cwd_mutation else backend, capture_output=True, text=True, timeout=10)
        print('CONTROL relocated config:', result.returncode, result.stdout.strip(), result.stderr.strip())
        receipt = os.environ.get('E2_CONFIG_CONTROL_RECEIPT')
        if receipt:
            target = Path(receipt); target.mkdir(parents=True, exist_ok=True)
            (target / 'config.ts').write_bytes(raw)
            (target / 'receipt.json').write_text(json.dumps({
                'control_only': True, 'owner': {k:v for k,v in owner.items() if k != 'variants'},
                'module_url': module.as_uri(), 'generated_sha256': hashlib.sha256(raw).hexdigest(),
                'returncode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}, indent=2))
        return result, owner, scratch

    def test_generated_config_survives_vite_module_relocation(self):
        result, owner, scratch = self.relocated()
        self.assertEqual(result.returncode, 0, result.stderr)
        effective = json.loads(result.stdout)
        self.assertEqual(effective['setup_path'], str(scratch / 'setup.ts'))
        self.assertEqual(effective['wrangler_path'], str(Path(owner['backend']) / 'wrangler.toml'))
        self.assertTrue((scratch / 'config-facts.json').is_file())

    def test_old_module_relative_directories_and_setup_are_rejected(self):
        mutations = {
            'directories': lambda raw: raw.replace(b'const scratch = paths.scratch.path;',
                    b"const scratch = dirname(new URL(import.meta.url).pathname);").replace(
                    b'const backend = paths.backend.path;', b"const backend = new URL('../', import.meta.url).pathname.replace(/\\/$/, '');"),
            'setup': lambda raw: raw.replace(b'const setup = paths.setup.path;',
                    b"const setup = new URL('./setup.ts', import.meta.url).pathname;")
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                case = RelocatedConfigControls(); case.root = self.root / name; case.root.mkdir()
                result, _, scratch = case.relocated(body_mutation=mutate)
                self.assertNotEqual(result.returncode, 0)
                if name == 'directories':
                    self.assertIn('unsafe capacity config directory', result.stderr)
                    self.assertIn('original_calls=0', result.stderr)
                else:
                    facts = e2.parse_json((scratch / 'config-facts.json').read_bytes())
                    with self.assertRaises(ValueError):
                        e2.resolved_config_guard(facts, scratch.parent, {'backend/wrangler.toml': {'sha256': e2.WRANGLER_SHA256}})

    def test_live_path_bytes_symlinks_and_foreign_facts_fail_before_original(self):
        def changed_setup(owner, scratch): (scratch / 'setup.ts').write_bytes(b'changed setup')
        def symlink_setup(owner, scratch):
            target = scratch.parent / 'foreign-setup.ts'; target.write_bytes((scratch / 'setup.ts').read_bytes())
            (scratch / 'setup.ts').unlink(); (scratch / 'setup.ts').symlink_to(target)
        def changed_wrangler(owner, scratch): (scratch.parent / 'wrangler.toml').write_bytes(b'changed Wrangler')
        def stale_facts(owner, scratch): (scratch / 'config-facts.json').write_bytes(b'CONTROL foreign receipt')
        for name, mutate in [('setup-bytes', changed_setup), ('setup-symlink', symlink_setup),
                             ('wrangler-bytes', changed_wrangler), ('stale-facts', stale_facts)]:
            with self.subTest(name=name):
                case = RelocatedConfigControls(); case.root = self.root / name; case.root.mkdir()
                result, _, scratch = case.relocated(path_mutation=mutate)
                self.assertNotEqual(result.returncode, 0)
                if name != 'stale-facts': self.assertIn('original_calls=0', result.stderr)
                else: self.assertEqual((scratch / 'config-facts.json').read_bytes(), b'CONTROL foreign receipt')

    def test_runtime_rejects_wrong_cwd_and_injected_path_tampering(self):
        mutations = {
            'run': lambda raw: raw.replace(b'"run":"12"', b'"run":"13"'),
            'attempt': lambda raw: raw.replace(b'"attempt":"1"', b'"attempt":"2"'),
            'outside-setup': lambda raw: raw.replace(b'/.capacity-e2-scratch-12-1/setup.ts', b'/node_modules/.vite-temp/setup.ts'),
            'wrong-inode': lambda raw: raw.replace(b'"inode":', b'"inode":0,"ignored":'),
            'cwd': None,
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                case = RelocatedConfigControls(); case.root = self.root / name; case.root.mkdir()
                result, _, scratch = case.relocated(body_mutation=mutate, cwd_mutation=name == 'cwd')
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('original_calls=0', result.stderr)
                self.assertFalse((scratch / 'config-facts.json').exists())

    def test_exact_injection_and_json_escaping_use_owned_canonical_paths(self):
        case = RelocatedConfigControls(); case.root = self.root / 'quote"-backslash\\-雪'; case.root.mkdir()
        result, owner, _ = case.relocated()
        self.assertEqual(result.returncode, 0, result.stderr)
        generated = e2.render_config(owner['path_facts'])
        self.assertNotIn(b'__E2_PATH_FACTS__', generated)
        self.assertNotIn(b'import.meta.url', generated)
        for broken in (e2.CONFIG.replace(b'__E2_PATH_FACTS__', b'null'), e2.CONFIG + b'__E2_PATH_FACTS__'):
            with mock.patch.object(e2, 'CONFIG', broken), self.assertRaises(ValueError): e2.render_config(owner['path_facts'])

    def test_reopening_rejects_rehashed_path_facts_and_generated_byte_drift(self):
        mutations = {
            'missing-facts': lambda owner: owner.pop('path_facts'),
            'extra-facts': lambda owner: owner['path_facts'].update(extra='/foreign'),
            'wrong-run': lambda owner: owner['path_facts'].update(run='13'),
            'wrong-attempt': lambda owner: owner['path_facts'].update(attempt='2'),
            'relative': lambda owner: owner['path_facts']['setup'].update(path='./setup.ts'),
            'relocated': lambda owner: owner['path_facts']['setup'].update(path=owner['backend']+'/node_modules/.vite-temp/setup.ts'),
            'outside': lambda owner: owner['path_facts']['wrangler'].update(path='/foreign/wrangler.toml'),
            'traversal': lambda owner: owner['path_facts']['setup'].update(path=owner['path']+'/../setup.ts'),
            'inode': lambda owner: owner['path_facts']['scratch'].update(inode=2),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                path = self.root / name
                identity, _ = PreflightControls.complete_preflight(self, path)
                owner = e2.parse_json((path / 'scratch-ownership.json').read_bytes()); mutate(owner)
                e2.write_json(path / 'scratch-ownership.json', owner)
                self.refresh_receipts(path)
                with self.assertRaises(ValueError): e2.preflight_gate(path, identity)
        for name in ('scratch-ownership.json', 'scratch/config.ts', 'scratch/setup.ts', e2.snapshot_member('backend/wrangler.toml')):
            with self.subTest(member=name):
                path = self.root / name.replace('/', '-')
                identity, _ = PreflightControls.complete_preflight(self, path)
                member = path / name
                if name == 'scratch-ownership.json': member.unlink()
                else: member.write_bytes(member.read_bytes() + b'changed bytes')
                self.refresh_receipts(path)
                with self.assertRaises(ValueError): e2.preflight_gate(path, identity)

    def refresh_receipts(self, path):
        # Refresh both inventories: semantic binding must independently close.
        receipt = e2.parse_json((path / 'evidence-receipt.json').read_bytes())
        inventory = e2.artifact_hashes(path)
        receipt['expected'] = {name:inventory[name] for name in receipt['expected'] if name in inventory}
        e2.write_json(path / 'evidence-receipt.json', receipt)
        e2.write_json(path / 'artifact-hashes.json', e2.artifact_hashes(path))


class WranglerPathControls(DisposableControls):
    members = NativeScratchArchiveControls.members
    WRANGLER_SHA256 = 'c54fae36ad2d0eac2098cff9396f7405fe852b0ca8a82e261945db1987410a3f'
    POOL_SHA256 = '188e21016501691fdcbcbca964cda86a71821114ccd3f36e78d683892b86271e'

    def pinned_resolution(self):
        # Read installed source only; never import or execute the adapter.
        candidates = (RUNNER.parents[2] / 'backend/node_modules', Path('/root/dining_log/backend/node_modules'))
        modules = next(p for p in candidates if (p / '@cloudflare/vitest-pool-workers/package.json').is_file())
        package = json.loads((modules / '@cloudflare/vitest-pool-workers/package.json').read_text())
        lock = json.loads((RUNNER.parents[2] / 'backend/package-lock.json').read_text())
        self.assertEqual(package['version'], '0.8.49')
        self.assertEqual(package['version'], lock['packages']['node_modules/@cloudflare/vitest-pool-workers']['version'])
        source = (modules / '@cloudflare/vitest-pool-workers/dist/pool/index.mjs').read_bytes()
        self.assertEqual(hashlib.sha256(source).hexdigest(), self.POOL_SHA256)
        for rule in (b'return project.config.config ?? project.path;',
                     b'const rootPath = typeof projectPath === "string" ? path2.dirname(projectPath) : "";',
                     b'const configPath = path2.resolve(rootPath, options.wrangler.configPath);'):
            self.assertIn(rule, source)

    def configured(self, mutation='', target_mutation=None, body_mutation=None):
        self.pinned_resolution()
        backend = self.root / 'backend'; backend.mkdir(exist_ok=True)
        target = backend / 'wrangler.toml'
        raw = (RUNNER.parents[2] / 'backend/wrangler.toml').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), self.WRANGLER_SHA256)
        target.write_bytes(raw)
        archive = self.root / 'archive'; archive.mkdir()
        owner = e2.materialize_scratch(backend, '12', '1', b"const projection = '__E2_PROJECTION__';\n", archive)
        scratch = Path(owner['path'])
        if target_mutation: target_mutation(backend, target, scratch)
        body = '\n'.join(line for line in (scratch / 'config.ts').read_text().splitlines() if not line.startswith('import '))
        body = body.replace('export default async function(configEnv)', 'async function configure(configEnv)')
        if body_mutation: body = body_mutation(body)
        harness = scratch / 'config-control.mjs'
        harness.write_text('''import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, basename } from 'node:path';
import { writeFileSync, readFileSync, lstatSync, realpathSync, readdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
let calls=0;
const resolved={plugins:[{name:'original-workers-plugin'}],test:{include:['tests/capacity/capacity_test.ts'],testTimeout:600000,hookTimeout:60000,
  poolOptions:{workers:{isolatedStorage:false,wrangler:{configPath:'./wrangler.toml'},
    miniflare:{compatibilityDate:'2025-06-17',bindings:{TEST_MIGRATIONS:['CONTROL ONLY']}}}}}};
''' + mutation + '''
const before = structuredClone(resolved.test.poolOptions);
const original = async () => { calls++; return resolved; };
''' + body + '''
const result=await configure({});
assert.equal(calls,1); assert.equal(result.plugins,resolved.plugins);
assert.equal(result.test.poolOptions,resolved.test.poolOptions);
assert.equal(result.test.poolOptions.workers,resolved.test.poolOptions.workers);
const effective=result.test.poolOptions.workers.wrangler.configPath;
const normalized=structuredClone(result.test.poolOptions); normalized.workers.wrangler.configPath='./wrangler.toml';
assert.deepEqual(normalized,before,'non-path pool option drift');
const activeConfig=fileURLToPath(new URL('./config.ts',import.meta.url));
const adapterTarget=resolve(dirname(activeConfig),effective);
const originalTarget=resolve(dirname(fileURLToPath(new URL('../vitest.capacity.config.ts',import.meta.url))),'./wrangler.toml');
console.log(JSON.stringify({active_config:activeConfig,inherited_path:'./wrangler.toml',effective_path:effective,
  adapter_target:adapterTarget,original_target:originalTarget,pool_source_sha256:''' + json.dumps(self.POOL_SHA256) + '''}));
const bytes=readFileSync(adapterTarget);
assert.equal(adapterTarget,originalTarget);
assert.equal(createHash('sha256').update(bytes).digest('hex'),''' + json.dumps(self.WRANGLER_SHA256) + ''');
''')
        result = subprocess.run(['node', str(harness)], cwd=backend, capture_output=True, text=True, timeout=10)
        print('CONTROL pinned Wrangler resolution:', result.returncode, result.stdout.strip(), result.stderr.strip())
        return result, backend, scratch

    def test_active_scratch_config_resolves_same_tracked_wrangler_bytes(self):
        result, backend, scratch = self.configured()
        self.assertEqual(result.returncode, 0, result.stderr)
        facts = json.loads((scratch / 'config-facts.json').read_text())
        self.assertEqual(facts['wrangler_config'], str(backend / 'wrangler.toml'))
        self.assertEqual(facts['wrangler_sha256'], self.WRANGLER_SHA256)
        self.assertTrue(facts['pool_options_except_wrangler_path_same'])

    def test_unsafe_wrangler_source_is_rejected_before_config_use(self):
        def symlink(backend, target, scratch):
            outside = self.root / 'foreign.toml'; outside.write_bytes(target.read_bytes())
            target.unlink(); target.symlink_to(outside)
        cases = {
            'missing': lambda b, t, s: t.unlink(),
            'changed': lambda b, t, s: t.write_bytes(t.read_bytes() + b'\n# drift\n'),
            'symlink': symlink,
            'foreign-scratch': lambda b, t, s: (s / 'wrangler.toml').write_bytes(t.read_bytes()),
            'dev-vars': lambda b, t, s: (b / '.dev.vars').write_bytes(b'CONTROL ONLY'),
            'env': lambda b, t, s: (b / '.env').write_bytes(b'CONTROL ONLY'),
        }
        original_root = self.root
        for name, mutate in cases.items():
            with self.subTest(name=name):
                self.root = original_root / name; self.root.mkdir()
                result, _, scratch = self.configured(target_mutation=mutate)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((scratch / 'config-facts.json').exists(), 'unsafe config was accepted before use')
        self.root = original_root

    def test_path_and_non_path_receipt_drift_close_native_and_preflight(self):
        mutations = {
            'missing-hash': lambda f: f.pop('wrangler_sha256'),
            'changed-hash': lambda f: f.update(wrangler_sha256='0' * 64),
            'relative': lambda f: f.update(wrangler_config='./wrangler.toml'),
            'outside': lambda f: f.update(wrangler_config='/foreign/wrangler.toml'),
            'traversal': lambda f: f.update(wrangler_config=f['wrangler_config'].replace('/wrangler.toml', '/../wrangler.toml')),
            'non-path-drift': lambda f: f.update(pool_options_except_wrangler_path_same=False),
            'missing-pool-hash': lambda f: f.pop('pool_options_sha256'),
            'changed-original': lambda f: f.update(wrangler_original_config='./foreign.toml'),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                _, trace = NativeScratchArchiveControls.native_scratch_control(self, facts_mutation=mutate)
                self.assertEqual(trace['code'], 1)
                self.assertFalse(trace['phase']['infrastructure_valid'])
                self.assertFalse(trace['reopened'])

    def test_traversal_overrides_and_any_added_pool_option_are_rejected(self):
        mutations = [
            "resolved.test.poolOptions.workers.wrangler.configPath='../wrangler.toml';",
            "resolved.test.poolOptions.workers.wrangler.configPath='/foreign/wrangler.toml';",
            "resolved.test.poolOptions.workers.wrangler.environment='foreign';",
            "resolved.test.poolOptions.workers.miniflare.d1Persist='/foreign';",
            "resolved.test.poolOptions.workers.miniflare.persist=true;",
            "resolved.test.poolOptions.workers.miniflare.remoteProxyConnectionString='CONTROL';",
            "resolved.test.poolOptions.workers.miniflare.workers=[];",
            "resolved.test.poolOptions.workers.miniflare.compatibilityDate='2026-01-01';",
            "resolved.test.poolOptions.workers.unrecognized='drift';",
            "resolved.test.poolOptions.foreign={};",
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=mutation):
                control = WranglerPathControls('test_active_scratch_config_resolves_same_tracked_wrangler_bytes')
                control.root = self.root / str(index); control.root.mkdir()
                result, _, scratch = control.configured(mutation=mutation)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((scratch / 'config-facts.json').exists())

    def test_non_path_mutation_during_rebase_is_rejected(self):
        result, _, scratch = self.configured(body_mutation=lambda body: body.replace(
            'workers.wrangler.configPath = wranglerPath;',
            "workers.wrangler.configPath = wranglerPath; workers.miniflare.compatibilityDate='drift';"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('non-path pool option drift', result.stderr)
        self.assertFalse((scratch / 'config-facts.json').exists())

    def test_preflight_reopening_rejects_rehashed_path_or_pool_drift(self):
        mutations = (lambda f: f.update(wrangler_config='/foreign/wrangler.toml'),
                     lambda f: f.update(wrangler_sha256='0' * 64),
                     lambda f: f.update(pool_options_except_wrangler_path_same=False),
                     lambda f: f.update(pool_options_sha256='0' * 64))
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                path = self.root / str(index); path.mkdir()
                identity = PreflightControls.complete_preflight(self, path)
                phase = json.loads((path / 'phase.json').read_text())
                mutate(phase['resolved_config'])
                e2.write_json(path / 'phase.json', phase)
                # A refreshed inventory cannot bless semantic receipt drift.
                e2.write_json(path / 'artifact-hashes.json', e2.artifact_hashes(path))
                with self.assertRaises(ValueError): e2.preflight_gate(path, identity)


class ScratchRetentionControls(DisposableControls):
    members = NativeScratchArchiveControls.members

    def retained_scratch_guard(self):
        # Exercise the real source/status guard against a stdlib checkout fixture.
        # Git command output is simulated; no native tool or repository mutation.
        source_guard = e2.source_guard
        def guard(root, candidate, ref):
            tree = b''
            for name in e2.SNAPSHOT_SOURCES:
                raw = (root / name).read_bytes()
                blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
                tree += ('100644 blob ' + blob + '\t' + name).encode() + b'\0'
            status = ''.join('?? ' + str(p.relative_to(root)) + '\n'
                             for p in sorted((root / 'backend').glob('.capacity-e2-scratch-*/*')))
            def command(argv, cwd, expected=(0,)):
                if argv[1:] == ['rev-parse', 'HEAD']: raw = candidate
                elif argv[1:] == ['branch', '--show-current']: raw = e2.BRANCH
                elif argv[1:] == ['rev-parse', 'HEAD:backend']: raw = e2.BACKEND_TREE
                elif argv[1] == 'merge-base': raw = ''
                elif argv[1:3] == ['diff', '--name-status']: raw = ''.join('A\t' + n + '\n' for n in e2.ALLOWED)
                elif argv[1] == 'ls-tree': return tree, 0
                elif argv[1:3] == ['diff', '--exit-code']: raw = ''
                elif argv[1] == 'status': raw = status
                else: raise AssertionError('unexpected source guard command: ' + repr(argv))
                return raw.encode(), 0
            with mock.patch.object(e2, 'read_command', side_effect=command):
                return source_guard(root, candidate, ref)
        return guard

    def assert_retained_failure(self, output, trace, member):
        phase = trace['phase']
        scratch = Path(trace['scratch_path'])
        original = bytes.fromhex(trace['original_hex'][member])
        # This is the regression assertion: the old compensating deletion loses it.
        self.assertTrue(scratch.is_dir(), 'failed archival must retain the owned scratch directory')
        self.assertEqual((scratch / member).read_bytes(), original)
        self.assertEqual(trace['remaining_hex'], {member: original.hex()})
        self.assertTrue(trace['scratch_exists_after'])
        for name in self.members:
            ledger_name = 'scratch/' + name
            if name == member:
                self.assertIn(ledger_name, trace['cleanup_ledger'])
                self.assertIsNone(trace['cleanup_ledger'][ledger_name])
                self.assertIsNone(trace['receipt']['expected'][ledger_name])
                self.assertFalse(any(event['name'] == ledger_name for event in trace['unlinks']))
            else:
                digest = hashlib.sha256(bytes.fromhex(trace['original_hex'][name])).hexdigest()
                self.assertEqual(trace['cleanup_ledger'][ledger_name], digest)
                self.assertEqual(trace['receipt']['expected'][ledger_name], digest)
                self.assertEqual((output / ledger_name).read_bytes(), bytes.fromhex(trace['original_hex'][name]))
        self.assertEqual(trace['code'], 1)
        self.assertFalse(phase['scratch_cleanup_ok'])
        self.assertIn('scratch archive failed', phase['cleanup_error'])
        self.assertFalse(phase['source_guard_after'])
        self.assertEqual(phase['source_error'], 'unexpected source status')
        self.assertFalse(phase['artifact_members_ok'])
        self.assertFalse(phase['infrastructure_valid'])
        self.assertTrue(phase['failed'])
        self.assertEqual(phase['evidence_status'], 'INCOMPLETE')
        self.assertIn('source-after.json', trace['receipt']['expected'])
        self.assertIsNone(trace['receipt']['expected']['source-after.json'])
        self.assertFalse(trace['reopened'])
        identity = {k: phase[k] for k in ('candidate', 'ref', 'run', 'attempt', 'runtime')}
        with self.assertRaises(ValueError): e2.verify_artifact_members(output, e2.artifact_hashes(output))
        with self.assertRaises(ValueError): e2.preflight_gate(output, identity)

    def test_each_exclusive_collision_retains_exact_original_and_foreign_target(self):
        for member in self.members:
            with self.subTest(member=member):
                output, trace = NativeScratchArchiveControls.native_scratch_control(
                    self, copy_failure=member, final_guard=self.retained_scratch_guard())
                self.assertEqual((output / 'scratch' / member).read_bytes(), b'CONTROL foreign archive member')
                self.assert_retained_failure(output, trace, member)

    def test_each_freeze_failure_retains_exact_original_and_unfrozen_copy(self):
        for member in self.members:
            with self.subTest(member=member):
                output, trace = NativeScratchArchiveControls.native_scratch_control(
                    self, freeze_failure=member, final_guard=self.retained_scratch_guard())
                self.assertEqual((output / 'scratch' / member).read_bytes(), bytes.fromhex(trace['original_hex'][member]))
                self.assert_retained_failure(output, trace, member)

    def test_read_partial_copy_and_verification_failures_retain_existing_facts(self):
        member = 'config-facts.json'
        for stage in ('read', 'copy', 'verification'):
            with self.subTest(stage=stage):
                output, trace = NativeScratchArchiveControls.native_scratch_control(
                    self, archive_failure=(stage, member), final_guard=self.retained_scratch_guard())
                target = output / 'scratch' / member
                if stage == 'read': self.assertFalse(target.exists())
                elif stage == 'copy': self.assertEqual(target.read_bytes(), b'CONTROL partial archive copy')
                else: self.assertEqual(target.read_bytes(), bytes.fromhex(trace['original_hex'][member]))
                self.assert_retained_failure(output, trace, member)

class WorkflowEvidenceBoundaryControls(DisposableControls):
    """Execute source-bound Bash with invented stdlib fixtures, never native tools."""

    def workflow(self):
        return (RUNNER.parents[2] / '.github/workflows/capacity-history-e2.yml').read_text()

    def step(self, name):
        return self.workflow().split('      - name: ' + name + '\n', 1)[1].split('\n      - ', 1)[0]

    def value(self, step, key, indent=10):
        prefix = ' ' * indent + key + ': '
        return next(line[len(prefix):] for line in step.splitlines() if line.startswith(prefix))

    def run_text(self, step):
        return '\n'.join(line[10:] for line in step.split('        run: |\n', 1)[1].splitlines()) + '\n'

    def resolve_temp_path(self, expression, temp):
        self.assertEqual(expression.count('${{ runner.temp }}'), 1)
        resolved = expression.replace('${{ runner.temp }}', str(temp))
        self.assertNotIn('$' + '{{', resolved)
        self.assertFalse(any(c in resolved for c in '*?[]\r\n'), 'no wildcard upload/fixture paths')
        return Path(resolved)

    def layout(self, label):
        base = self.root / label
        temp = base / 'runner-temp'; temp.mkdir(parents=True)
        checkout = base / 'checkout'; checkout.mkdir()
        artifact = self.step('Always upload attempted raw evidence')
        controls = self.step('Stdlib dummy controls')
        upload = self.resolve_temp_path(self.value(artifact, 'path'), temp); upload.mkdir()
        disposable = self.resolve_temp_path(self.value(controls, 'E2_TEST_EVIDENCE'), temp)
        return temp, checkout, upload, disposable

    def environment(self, temp, checkout, disposable):
        return dict(os.environ, RUNNER_TEMP=str(temp), GITHUB_WORKSPACE=str(checkout),
                    E2_TEST_EVIDENCE=str(disposable), GITHUB_SHA='1' * 40,
                    GITHUB_REF=e2.REF, GITHUB_RUN_ID='12', GITHUB_RUN_ATTEMPT='1',
                    GITHUB_EVENT_NAME='workflow_dispatch', REVIEWED_SHA='1' * 40)

    def bash(self, step, checkout, env):
        return subprocess.run(['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail',
                               '-c', self.run_text(step)], cwd=checkout, env=env,
                              capture_output=True, timeout=10)

    def direct_upload_members(self, upload):
        self.assertTrue(upload.is_absolute() and upload.is_dir() and not upload.is_symlink()
                        and upload.resolve() == upload, 'canonical nonsymlink upload root')
        members = []
        for directory, directories, files in os.walk(upload, followlinks=False):
            for name in directories + files:
                self.assertFalse(Path(directory, name).is_symlink(), 'no symlink artifact escape')
            directories[:] = sorted(name for name in directories if not name.startswith('.'))
            members.extend(str(Path(directory, name).relative_to(upload))
                           for name in files if not name.startswith('.'))
        return sorted(members)

    def invalid_upload_names(self, members):
        # upload-artifact/@actions/artifact's documented file-path policy, also
        # printed by run 43461925842. Unicode is allowed; the quote rejects our
        # intentional quote/backslash/Unicode Linux filesystem fixture.
        invalid = ('"', ':', '<', '>', '|', '*', '?', '\r', '\n')
        return {name: [char for char in invalid if char in name]
                for name in members if any(char in name for char in invalid)}

    def dummy_unittest(self, checkout, failed=False):
        scripts = checkout / '.github/scripts'; scripts.mkdir(parents=True)
        (scripts / 'test_capacity_history_e2.py').write_text(
            'import os, sys, unittest\nfrom pathlib import Path\n'
            'class CONTROL_ONLY(unittest.TestCase):\n'
            '    def test_capture(self):\n'
            "        Path('controls-invoked').write_bytes(b'CONTROL ONLY invoked')\n"
            "        root = Path(os.environ['E2_TEST_EVIDENCE'])\n"
            '        root.mkdir(parents=True, exist_ok=True)\n'
            '        path = root / ' + repr('quote"-backslash\\-雪') + " / 'backend/wrangler.toml'\n"
            '        path.parent.mkdir(parents=True, exist_ok=True)\n'
            "        path.write_bytes(b'CONTROL ONLY unsafe filesystem fixture')\n"
            "        print('CONTROL ONLY stdout capture')\n"
            "        print('CONTROL ONLY stderr capture', file=sys.stderr)\n"
            f'        self.assertFalse({failed!r})\n')

    def test_workflow_resolved_upload_root_excludes_actual_unsafe_name_fixture(self):
        temp, checkout, upload, disposable = self.layout('name-policy')
        case = RelocatedConfigControls()
        case.root = disposable / 'RelocatedConfigControls-upload-policy'; case.root.mkdir(parents=True)
        # Execute the unchanged real adversarial control, including its Node VM
        # stub and frozen Wrangler bytes, rather than sanitizing a renamed copy.
        case.test_exact_injection_and_json_escaping_use_owned_canonical_paths()
        fixture = case.root / 'quote"-backslash\\-雪/backend/wrangler.toml'
        self.assertEqual(fixture.read_bytes(), (RUNNER.parents[2] / 'backend/wrangler.toml').read_bytes())
        synthetic = ['attempt.json', 'controls/stdout', 'controls/stderr', 'controls/exit',
                     'install.stdout', 'install.stderr', 'install.exit', 'guard/phase.json',
                     'preflight/preflight/p1-4500/stdout.bin', 'preflight/preflight/plan-6000/stderr.bin',
                     'abba/01-A/p1-4500/stdout.bin', 'abba/04-A/plan-6000/stderr.bin']
        synthetic += ['guard/' + e2.snapshot_member(name) for name in e2.SNAPSHOT_SOURCES]
        for name in synthetic:
            path = upload / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'CONTROL ONLY synthetic required artifact member\n')
        members = self.direct_upload_members(upload)
        rejected = self.invalid_upload_names(members)
        print('CONTROL workflow upload-name policy:', json.dumps({
            'upload_root': str(upload.resolve()), 'disposable_root': str(disposable.resolve()),
            'actual_unsafe_fixture': str(fixture), 'fixture_executed': True,
            'direct_members': len(members), 'rejected': rejected}, ensure_ascii=False))
        self.assertEqual(rejected, {}, 'GitHub upload-artifact rejects disposable fixture entries')
        self.assertEqual(disposable, temp / 'capacity-history-e2-controls-disposable')
        for root in (upload, checkout):
            self.assertFalse(disposable.resolve().is_relative_to(root.resolve())
                             or root.resolve().is_relative_to(disposable.resolve()))
        self.assertFalse(fixture.resolve().is_relative_to(upload.resolve()))
        self.assertEqual(set(members), set(synthetic))

    def test_workflow_captures_real_unittest_success_and_failure_outside_disposable_uploads(self):
        step = self.step('Stdlib dummy controls')
        self.assertIn('python3 -B -m unittest discover -s .github/scripts -p test_capacity_history_e2.py -v',
                      self.run_text(step), 'retain the full unittest discovery command')
        for failed in (False, True):
            with self.subTest(failed=failed):
                temp, checkout, upload, disposable = self.layout('capture-' + str(failed))
                self.dummy_unittest(checkout, failed)
                result = self.bash(step, checkout, self.environment(temp, checkout, disposable))
                expected = int(failed)
                self.assertEqual(result.returncode, expected, result.stderr)
                self.assertEqual((upload / 'controls/exit').read_text(), str(expected) + '\n')
                self.assertIn(b'CONTROL ONLY stdout capture', (upload / 'controls/stdout').read_bytes())
                stderr = (upload / 'controls/stderr').read_bytes()
                self.assertIn(b'CONTROL ONLY stderr capture', stderr)
                self.assertIn(b'Ran 1 test', stderr)
                self.assertIn(b'FAILED (failures=1)' if failed else b'\nOK\n', stderr)
                self.assertTrue((disposable / 'quote"-backslash\\-雪/backend/wrangler.toml').is_file())
                self.assertEqual(disposable.resolve(), temp / 'capacity-history-e2-controls-disposable')
                members = self.direct_upload_members(upload)
                self.assertEqual(members, ['controls/exit', 'controls/stderr', 'controls/stdout'])
                self.assertEqual(self.invalid_upload_names(members), {})
                print('CONTROL workflow capture:', json.dumps({
                    'unittest_exit': expected, 'shell_exit': result.returncode,
                    'direct_artifact_members': members, 'external_unsafe_fixture_retained': True}))

    def test_workflow_boundary_rejects_symlinks_aliases_overlap_and_unowned_roots(self):
        cases = ('fixture-upload-symlink', 'fixture-checkout-symlink', 'fixture-foreign-symlink',
                 'fixture-existing-directory', 'fixture-existing-file', 'controls-symlink',
                 'upload-symlink', 'runner-temp-alias', 'checkout-alias', 'checkout-overlap',
                 'outside-runner-temp', 'wildcard-fixture')
        for name in cases:
            with self.subTest(name=name):
                temp, checkout, upload, disposable = self.layout(name)
                self.dummy_unittest(checkout)
                foreign = temp.parent / 'foreign'; foreign.mkdir()
                sentinel = foreign / 'sentinel.txt'; sentinel.write_bytes(b'CONTROL ONLY unread foreign bytes')
                env = self.environment(temp, checkout, disposable)
                if name.startswith('fixture-') and name.endswith('-symlink'):
                    target = {'fixture-upload-symlink': upload, 'fixture-checkout-symlink': checkout,
                              'fixture-foreign-symlink': foreign}[name]
                    disposable.parent.mkdir(parents=True, exist_ok=True)
                    disposable.symlink_to(target, target_is_directory=True)
                elif name == 'fixture-existing-directory':
                    disposable.mkdir(parents=True); (disposable / 'sentinel.txt').write_bytes(sentinel.read_bytes())
                elif name == 'fixture-existing-file':
                    disposable.parent.mkdir(parents=True, exist_ok=True); disposable.write_bytes(sentinel.read_bytes())
                elif name == 'controls-symlink':
                    (upload / 'controls').symlink_to(foreign, target_is_directory=True)
                elif name == 'upload-symlink':
                    upload.rename(temp / 'saved-upload'); upload.symlink_to(foreign, target_is_directory=True)
                elif name == 'runner-temp-alias':
                    alias = temp.parent / 'temp-alias'; alias.symlink_to(temp, target_is_directory=True)
                    env['RUNNER_TEMP'] = str(alias)
                    env['E2_TEST_EVIDENCE'] = str(self.resolve_temp_path(
                        self.value(self.step('Stdlib dummy controls'), 'E2_TEST_EVIDENCE'), alias))
                elif name == 'checkout-alias':
                    alias = temp.parent / 'checkout-alias'; alias.symlink_to(checkout, target_is_directory=True)
                    env['GITHUB_WORKSPACE'] = str(alias)
                elif name == 'checkout-overlap':
                    env['GITHUB_WORKSPACE'] = str(temp)
                elif name == 'outside-runner-temp':
                    env['E2_TEST_EVIDENCE'] = str(foreign / 'disposable')
                elif name == 'wildcard-fixture':
                    env['E2_TEST_EVIDENCE'] = str(temp / 'capacity-history-e2-controls-*')
                before = {str(p.relative_to(foreign)): p.read_bytes() for p in foreign.rglob('*') if p.is_file()}
                result = self.bash(self.step('Stdlib dummy controls'), checkout, env)
                self.assertNotEqual(result.returncode, 0, 'unsafe boundary must fail before unittest')
                self.assertFalse((checkout / 'controls-invoked').exists(), 'no unsafe fixture writes or reads')
                after = {str(p.relative_to(foreign)): p.read_bytes() for p in foreign.rglob('*') if p.is_file()}
                self.assertEqual(after, before, 'foreign data must remain untouched and uncopied')
                if name == 'fixture-existing-file':
                    self.assertEqual(disposable.read_bytes(), sentinel.read_bytes())
                if name == 'fixture-existing-directory':
                    self.assertEqual(sorted(p.name for p in disposable.iterdir()), ['sentinel.txt'])
                print('CONTROL workflow boundary rejected:', name, result.returncode)

    def test_attempt_survives_route_failure_and_missing_native_evidence_keeps_gate_closed(self):
        temp, checkout, upload, disposable = self.layout('early-failure')
        env = self.environment(temp, checkout, disposable)
        env['GITHUB_REF'] = 'refs/heads/CONTROL-invalid-route'
        route = self.bash(self.step('Record attempt and validate exact route'), checkout, env)
        self.assertNotEqual(route.returncode, 0)
        attempt = json.loads((upload / 'attempt.json').read_text())
        self.assertEqual(attempt['GITHUB_REF'], env['GITHUB_REF'])
        self.assertEqual(attempt['GITHUB_SHA'], env['GITHUB_SHA'])
        self.assertEqual(self.direct_upload_members(upload), ['attempt.json'])
        env['GITHUB_OUTPUT'] = str(temp.parent / 'github-output')
        gate = self.bash(self.step('Read preflight infrastructure gate'), RUNNER.parents[2], env)
        self.assertEqual(gate.returncode, 0, gate.stderr)
        self.assertEqual(Path(env['GITHUB_OUTPUT']).read_text(), 'valid=false\n')
        self.assertFalse((upload / 'preflight').exists())
        env['OUTCOMES'] = 'success success success success success success failure skipped success'
        aggregate = self.bash(self.step('Aggregate every required exit including preflight and upload'), checkout, env)
        self.assertNotEqual(aggregate.returncode, 0, 'upload success cannot substitute for missing native evidence')

    def test_workflow_keeps_exact_upload_settings_and_all_nine_required_outcomes(self):
        import re
        artifact = self.step('Always upload attempted raw evidence')
        self.assertEqual(self.value(artifact, 'if', 8), 'always()')
        self.assertEqual(self.value(artifact, 'uses', 8),
                         'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4')
        self.assertEqual(self.value(artifact, 'name'),
                         'DIAGNOSTIC_HISTORY_PROJECTION_E2_NOT_ROOT_CAUSE-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}')
        self.assertEqual(self.value(artifact, 'path'), '${{ runner.temp }}/capacity-history-e2/')
        self.assertEqual(self.value(artifact, 'retention-days'), '14')
        self.assertEqual(self.value(artifact, 'if-no-files-found'), 'error')
        self.assertNotIn('include-hidden-files:', artifact)
        self.assertIn('permissions:\n  contents: read\n', self.workflow())
        self.assertIn('  push:\n    branches: [verify/issue19-capacity-history-payload-20261112]\n',
                      self.workflow())
        self.assertIn('  workflow_dispatch:\n    inputs:\n      reviewed_candidate_sha:', self.workflow())
        self.assertEqual(self.value(self.step('Stdlib dummy controls'), 'continue-on-error', 8), 'true')
        self.assertEqual(self.value(self.step('Native preflight pair'), 'if', 8),
                         "steps.controls.outcome == 'success'")
        self.assertEqual(self.value(self.step('Native serial ABBA'), 'if', 8),
                         "always() && steps.controls.outcome == 'success' && steps.gate.outputs.valid == 'true'")
        aggregate = self.step('Aggregate every required exit including preflight and upload')
        self.assertEqual(self.value(aggregate, 'if', 8), 'always()')
        required = ['route', 'checkout', 'node', 'guard', 'install', 'controls', 'preflight', 'abba', 'artifact']
        self.assertEqual(re.findall(r'\$\{\{ steps\.([a-z]+)\.outcome \}\}', self.value(aggregate, 'OUTCOMES')),
                         required)
        env = dict(os.environ, OUTCOMES=' '.join(['success'] * 9))
        self.assertEqual(self.bash(aggregate, RUNNER.parents[2], env).returncode, 0)
        for index, outcome in enumerate(required):
            for failure in ('failure', 'skipped'):
                with self.subTest(outcome=outcome, failure=failure):
                    values = ['success'] * 9; values[index] = failure
                    env['OUTCOMES'] = ' '.join(values)
                    self.assertNotEqual(self.bash(aggregate, RUNNER.parents[2], env).returncode, 0)


class GeneratedConfigLiteralControls(DisposableControls):
    def test_raw_bytes_config_removes_warning_with_identical_generated_bytes_and_js_regexes(self):
        import ast
        import warnings
        source = RUNNER.read_bytes()
        with warnings.catch_warnings(record=True) as seen:
            warnings.simplefilter('always')
            tree = ast.parse(source)
            compile(source, str(RUNNER), 'exec')
        invalid = [str(item.message) for item in seen if 'invalid escape sequence' in str(item.message)]
        print('CONTROL generated CONFIG invalid-escape warnings:', json.dumps(invalid))
        self.assertEqual(invalid, [], 'generated JS regex literals must not warn during Python compilation')
        config = next(node.value for node in tree.body if isinstance(node, ast.Assign)
                      and any(isinstance(t, ast.Name) and t.id == 'CONFIG' for t in node.targets))
        literal = ast.get_source_segment(source.decode(), config)
        prefix, body = literal.split('"""', 1)
        self.assertIn(prefix, ('rb', 'br'))
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            legacy = ast.literal_eval('b"""' + body)
        self.assertEqual(legacy, e2.CONFIG)
        self.assertEqual(hashlib.sha256(e2.CONFIG).hexdigest(),
                         '02b4bbbd0b3cb220353474ca3bffedb4600ebecb019e92e67b24114500a89a3e',
                         'exact reviewed CONFIG bytes must remain identical')
        owner = SourceScratchControls.scratch(self)
        generated = e2.render_config(owner['path_facts'])
        with mock.patch.object(e2, 'CONFIG', legacy):
            self.assertEqual(e2.render_config(owner['path_facts']), generated)
        self.assertEqual((Path(owner['path']) / 'config.ts').read_bytes(), generated)
        lines = e2.CONFIG.splitlines()
        regexes = [
            next(line.split(b'name => ', 1)[1].split(b'.test(', 1)[0] for line in lines if b'dev\\.vars|env' in line),
            next(line.split(b'|| !', 1)[1].split(b'.test(', 1)[0] for line in lines if b'!/^\\.capacity-e2-scratch-' in line),
            next(line.split(b'name => ', 1)[1].split(b'.test(', 1)[0] for line in lines if b'/^wrangler\\.' in line),
        ]
        cases = [
            [('.dev.vars', True), ('.dev.vars.local', True), ('.env', True), ('.env.control', True),
             ('dev.vars', False), ('.devXvars', False), ('.envy', False)],
            [('.capacity-e2-scratch-12-1', True), ('capacity-e2-scratch-12-1', False),
             ('.capacity-e2-scratch-x-1', False), ('.capacity-e2-scratch-12-1/../other', False)],
            [('wrangler.toml', True), ('wrangler.json', True), ('wrangler.jsonc', True),
             ('wranglerXtoml', False), ('other-wrangler.toml', False)],
        ]
        js = ("import assert from 'node:assert/strict';\nconst regexes = [" +
              ','.join(value.decode() for value in regexes) + '];\nconst cases = ' + json.dumps(cases) + ';\n' +
              'for (let i=0;i<regexes.length;i++) for(const [name,expected] of cases[i])\n' +
              '  assert.equal(regexes[i].test(name), expected, name);\n' +
              "console.log('CONTROL ONLY identical JS regex semantics: 16 cases');\n")
        result = subprocess.run(['node', '--input-type=module', '-e', js],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
