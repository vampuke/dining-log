"""Stdlib dummy controls only; none of these data or commands are native D1 evidence.

E2_TEST_EVIDENCE retains every disposable control directory for an independent rerun.
Run with python3 -B; importing the runner never executes Node or Workers.
"""

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
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
        self.addCleanup(lambda: unrelated.poll() is None and unrelated.terminate())
        self.addCleanup(lambda: None)
        directory = self.root / 'descendant'; directory.mkdir()
        command = [sys.executable, '-B', '-c', "import subprocess,sys; subprocess.Popen([sys.executable,'-B','-c','import time; time.sleep(3)']); print('CONTROL parent exit')"]
        value = e2.run_owned(command, self.root, directory, 1)
        self.assertTrue(value.get('descendants_observed'))
        self.assertTrue(value.get('cleanup_ok'))
        self.assertIsNone(unrelated.poll())
        unrelated.terminate(); unrelated.wait(timeout=2)


class ExitControls(DisposableControls):
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
