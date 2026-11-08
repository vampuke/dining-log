#!/usr/bin/env python3
"""Validate real connected-test XML; distinguish expected assertions from infra failures."""

import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


CLASS = "top.alanvampuke.dininglog.product.Pr18ReviewRegressionTest"
EXPECTED = {
    "f5DelayedFirstPreferenceRestoreKeepsDismissedManualAClosedAndSettingsBAvailable":
        "F5-001 rejected startup B must not reopen dismissed A",
    "f6SynchronousPermissionReturnContinuesOwnedA":
        "F6-001 one initial SHA check and exactly one permission continuation",
    "f6DuplicateSynchronousAndCompletedPermissionReturnsContinueOwnedAOnce":
        "F6-001 one initial SHA check and exactly one permission continuation",
    "f6SynchronousPermissionReturnAfterManualBSelectionRejectsStaleA": None,
}


def inspect_results(root):
    files = sorted(Path(root).rglob("*.xml"))
    cases = []
    invalid = []
    for path in files:
        try:
            report = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            invalid.append(f"Unreadable XML {path}: {error}")
            continue
        for suite in report.iter("testsuite"):
            try:
                if any(int(suite.get(key, "0")) > 0 for key in ("skipped", "disabled")):
                    invalid.append(f"Suite reports skipped/disabled tests: {path}")
                if suite.get("tests") is not None and int(suite.get("tests")) != len(list(suite.iter("testcase"))):
                    invalid.append(f"Suite test count disagrees with actual cases: {path}")
            except ValueError:
                invalid.append(f"Invalid suite counters: {path}")
        for case in report.iter("testcase"):
            failures = [ET.tostring(node, encoding="unicode") for node in case
                        if node.tag in {"failure", "error"}]
            skipped = case.find("skipped") is not None or case.get("status") in {"skipped", "notrun"}
            cases.append({
                "class": case.get("classname"), "name": case.get("name"),
                "outcome": "SKIPPED" if skipped else "FAILED" if failures else "PASSED",
                "failures": failures, "xml": str(path),
            })
    observed = [(case["class"], case["name"]) for case in cases]
    required = {(CLASS, method) for method in EXPECTED}
    if len(observed) != len(EXPECTED) or set(observed) != required:
        invalid.append("Expected each of the four exact regression methods once; missing, duplicate, or unrelated tests are invalid evidence")
    if any(case["outcome"] == "SKIPPED" for case in cases):
        invalid.append("Skipped tests are invalid regression evidence")
    classification = "INVALID_EXECUTION"
    if not invalid:
        if all(case["outcome"] == "PASSED" for case in cases):
            classification = "GREEN"
        elif all(
            (case["outcome"] == "PASSED" if EXPECTED[case["name"]] is None else
             case["outcome"] == "FAILED" and
             any(EXPECTED[case["name"]] in failure and
                 ("java.lang.AssertionError" in failure or "org.junit.ComparisonFailure" in failure)
                 for failure in case["failures"]))
            for case in cases
        ):
            classification = "EXPECTED_RED"
        else:
            classification = "OTHER_FAILURE"
    return {"classification": classification, "valid_execution": not invalid,
            "tests": len(cases), "invalid_reasons": invalid, "cases": cases}


def main():
    result = inspect_results(sys.argv[1])
    destination = Path(sys.argv[2])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    # Expected behavior failures keep the Gradle/emulator step RED; this check
    # never converts that step to success or treats missing/skipped tests as RED.
    return 0 if result["classification"] in {"GREEN", "EXPECTED_RED"} else 1


if __name__ == "__main__":
    sys.exit(main())
