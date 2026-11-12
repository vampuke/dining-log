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
            "pool": "@cloudflare/vitest-pool-workers", "leaves": [
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
    return original + b"E2_PREFLIGHT " + json.dumps(preflight, separators=(",", ":")).encode() + b"\nE2_HISTORY_RESULT " + json.dumps(history, separators=(",", ":")).encode() + b"\n"


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
            audited = e2.audit_process(receipt, probe_log(original, preflight, history), scenario, projection, RUNNER.parents[2])
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
        command = [sys.executable, '-B', '-c', "import time; print('CONTROL before timeout',flush=True); time.sleep(2)"]
        value = e2.run_owned(command, self.root, directory, 0.1)
        self.assertTrue(value.get('timed_out'))
        self.assertNotEqual(value.get('returncode'), 0)
        self.assertTrue(value.get('cleanup_ok'))
        self.assertIn(b'CONTROL before timeout', (directory / 'stdout.raw').read_bytes())

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
                audited = e2.audit_process(receipt, raw, 'p1-4500', '*', RUNNER.parents[2])
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
        failed = e2.audit_process(dict(good, returncode=1), probe_log(*dummy_probe_raw(state='fail')), 'p1-4500', 'id', RUNNER.parents[2])
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


class PreflightControls(DisposableControls):
    def complete_preflight(self, path, code=0, state='pass', gate_failed=False):
        identity = {'candidate': '1'*40, 'ref': e2.REF, 'run': '12', 'attempt': '1',
                    'runtime': {'node': 'v22.CONTROL'}}
        processes = []
        for scenario in ('p1-4500', 'plan-6000'):
            raw = exit_probe_raw(scenario, state if scenario == 'p1-4500' else 'pass',
                                 gate_failed if scenario == 'p1-4500' else False)
            directory = path / 'preflight' / scenario; directory.mkdir(parents=True)
            (directory / 'stdout.raw').write_bytes(raw); (directory / 'stderr.raw').write_bytes(b'')
            receipt = {'returncode': code if scenario == 'p1-4500' else 0, 'timed_out': False,
                       'cleanup_ok': True, 'spawn_error': None, 'descendants_after_exit': [],
                       'stdout_sha256': hashlib.sha256(raw).hexdigest(),
                       'stderr_sha256': hashlib.sha256(b'').hexdigest()}
            audited = e2.audit_process(receipt, raw, scenario, '*', RUNNER.parents[2])
            # Reopening must reject a stale, previously permissive acceptance
            # using the raw records, even when the saved gate still says true.
            audited.update(scenario=scenario, infrastructure_valid=True)
            processes.append(audited)
        phase = {'phase': 'preflight', **identity, 'processes': processes, 'control_only': True,
                 'infrastructure_valid': True, 'failed': any(p['failed'] for p in processes),
                 'scratch_cleanup_ok': True, 'source_guard_after': True, 'smoke_state': 'absent'}
        (path / 'phase.json').write_text(json.dumps(phase))
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


class InferenceControls(unittest.TestCase):
    def controls(self):
        values=[]
        for block, arm in [('01-A','*'),('02-B','id'),('03-B','id'),('04-A','*')]:
            for scenario in ('p1-4500','plan-6000'):
                parts=dummy_probe_raw(scenario,arm)
                probe=e2.audit_probe(probe_log(*parts),e2.audit_original(parts[0],scenario),scenario,arm,RUNNER.parents[2])
                values.append({'block':block,'scenario':scenario,'probe_audit':probe})
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
                'backend/package.json', 'backend/package-lock.json', *e2.ALLOWED)

    def finalized_control(self, cleanup_failed=False, source_failed=False, artifact_mutation=None):
        processes = supportive_control_cohort()
        expected = e2.describe_effect(processes)
        self.assertEqual(expected['status'], 'result-size-related path support', 'cohort must support before final failures')
        output = self.root / 'CONTROL-finalized-phase'
        outcome = {'processes': processes, 'renderers': [{'failed': False} for _ in range(4)],
                   'failed': False, 'infrastructure_valid': True}
        args = mock.Mock(phase='abba', output=str(output), preflight=str(self.root / 'CONTROL-preflight'))
        owner = {'path': str(self.root / 'CONTROL-owned-scratch'), 'variants': {}}
        source = {'control_only': True, 'manifest': {
            name: {'sha256': hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()}
            for name in self.snapshot_sources()}}
        guards = [source, ValueError('CONTROL final source validation failure') if source_failed else source]
        def cleanup(*_):
            if artifact_mutation is not None: artifact_mutation(output)
            if cleanup_failed: raise ValueError('CONTROL final scratch cleanup failure')
        def collected(*_):
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
        with mock.patch.dict(os.environ, {'RUNNER_TEMP': str(self.root)}), \
             mock.patch.object(e2, 'LEDGER', None), \
             mock.patch.object(e2, 'source_guard', side_effect=guards), \
             mock.patch.object(e2, 'runtime', return_value={'control_only': True}), \
             mock.patch.object(e2, 'preflight_gate', return_value={'resolved_config': {'control_only': True}, 'failed': False}), \
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

    def controlled_phase(self, failure_at=None, mutation=None, snapshot_gap=None):
        """Run the real serial/finalization paths with only pure Python children.

        Guard/runtime and renderer command interfaces substitute bounded Python
        commands. The unsafe-command seam records a fake surviving generation.
        """
        output = self.root / 'phase'
        checkout = self.root / 'fixture-checkout'
        for name in e2.SNAPSHOT_SOURCES:
            target = checkout / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((RUNNER.parents[2] / name).read_bytes())
        scratch = checkout / 'backend/test-owned-scratch'; scratch.mkdir()
        facts = dict(inherited_once=True, plugins_same=True, pool_options_same=True,
                     isolatedStorage=False, remote_bindings=False, persistent_override=False,
                     test_original=dict(include=['tests/capacity/capacity_test.ts'], testTimeout=600000, hookTimeout=60000))
        (scratch / 'config-facts.json').write_text(json.dumps(facts))
        owner = {'path': str(scratch), 'variants': {}}
        source = {'manifest': {name: {'sha256': hashlib.sha256((RUNNER.parents[2] / name).read_bytes()).hexdigest()}
                               for name in e2.SNAPSHOT_SOURCES}}
        trace = {'attempts': [], 'scratch_deletions': [], 'fake_owned_remains': False,
                 'guards': 0, 'runtimes': 0, 'arm': 'full', 'original_raw_hashes': {}}
        original_owned = e2.run_owned
        def guard(*_, **__):
            trace['guards'] += 1
            e2.read_command(['CONTROL-guard', str(trace['guards'])], self.root)
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
                original, preflight, history = dummy_probe_raw(scenario, projection)
                preflight['original_task']['filepath'] = str(checkout / 'backend/tests/capacity/capacity_test.ts')
                history['original_task']['filepath'] = preflight['original_task']['filepath']
                for sample in history['samples']:
                    sample.update(start_ms=1000 + 20 * sample['index'], wall_ms=10 if projection == '*' else 2)
                    sample['stop_ms'] = sample['start_ms'] + sample['wall_ms']
                    sample['bookkeeping_stop_ms'] = sample['stop_ms'] + sample['bookkeeping_ms']
                raw = probe_log(original, preflight, history)
                if snapshot_gap is not None and 'snapshot_gap' not in trace:
                    receipt, trace['snapshot_gap'] = self.omitted_descendant(raw, directory, snapshot_gap, original_owned)
                    trace['original_raw_hashes'][str(directory.relative_to(output) / 'stdout.raw')] = receipt['stdout_sha256']
                    return receipt
                inp = self.root / ('input-%03d.raw' % len(trace['attempts'])); inp.write_bytes(raw)
                actual = [sys.executable, '-B', '-c',
                          'import pathlib,sys; sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes())', str(inp)]
            elif kind == 'renderer':
                actual = [sys.executable, '-B', '-c',
                          "import pathlib,sys; p=pathlib.Path(sys.argv[1]); (p/'measurements.json').write_text('{}'); (p/'RESULTS.md').write_text('CONTROL renderer output'); print('CONTROL renderer completed')",
                          str(directory.parent)]
            else:
                actual = [sys.executable, '-B', '-c', "print('CONTROL guard/runtime')"]
            receipt = original_owned(actual, self.root, directory, 3)
            if kind == 'vitest':
                trace['original_raw_hashes'][str(directory.relative_to(output) / 'stdout.raw')] = receipt['stdout_sha256']
            return receipt
        def cleanup(*_):
            if mutation: mutation(output)
            trace['scratch_deletions'].append(str(scratch))
            for path in scratch.iterdir(): path.unlink()
            scratch.rmdir()
        args = mock.Mock(phase='abba', output=str(output), preflight=str(self.root / 'preflight'))
        with mock.patch.dict(os.environ, {'RUNNER_TEMP': str(self.root)}), \
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
                  'scratch_retained': (self.root / 'fixture-checkout/backend/test-owned-scratch').is_dir()
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


if __name__ == "__main__":
    unittest.main()
