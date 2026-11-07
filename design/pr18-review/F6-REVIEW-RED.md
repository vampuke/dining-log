# F6 review RED and owned permission continuation

Base: `061a01fe1ea2a31905566e4a7b558ae1396632f0`, clean
`fix/pr18-review-regressions` checkout in `/tmp/dining-pr18-review-regressions`.
Executed tests-only probe: [Actions run 42373972592](https://github.com/vampuke/dining-log/actions/runs/42373972592),
with that exact `SOURCE_COMMIT`.

## Actual Android evidence

Downloaded artifacts are under
`/tmp/dining-release-20261107/probe-red-42373972592/files`.
`outputs/pr18-regression-probe/results.json` reports `valid_execution=true`,
four exact tests, no skipped cases, and `OTHER_FAILURE`. The combined
classification includes a fixture error; the two F6 assertion failures remain
valid behavioral RED individually.

The complete connected XML is
`outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml`
(SHA-256 `8cd73a5280f8e49273777c9909fb74024965242e9e65587ff33fa2d63fc28232`).
Inspected its full failure stacks, all four per-test logs, UTP/test-results and
Gradle logs, and a complete scan of the 9,611-line final logcat. The complete
log-scan paths, line counts and byte hashes are recorded outside the repository
in `/tmp/dining-release-20261107/f6-f5-complete-log-scan.json`.
Actual device diagnostics are in
`outputs/pr18-regression-probe/device-evidence/p4-screenshots/pr18-measurements-api35.txt`.

| Exact method in `Pr18ReviewRegressionTest` | Actual result |
| --- | --- |
| `f6SynchronousPermissionReturnContinuesOwnedA` | Failed at `synchronousReturn:216`: expected two SHA verifications, actual one. |
| `f6DuplicateSynchronousAndCompletedPermissionReturnsContinueOwnedAOnce` | Same assertion and counts, with repeated synchronous and completed-job returns. |
| `f6SynchronousPermissionReturnAfterManualBSelectionRejectsStaleA` | Passed. B remains IDLE/unverified; only A's initial SHA verification occurs; neither package reaches the installer. |
| `f5DelayedFirstPreferenceRestoreKeepsDismissedManualAClosedAndSettingsBAvailable` | Fixture error, not F5 behavior RED: `CalledFromWrongThreadException` in `MainActivity.applySystemBarTheme:58`, called by the shared composition's `SideEffect` on `DefaultDispatcher-worker-3`. |

Both failing F6 cases reached the real manager with selected A, 31/31 fixture
bytes, a valid SHA-verified APK, one service start, one transport connection and
one permission request. The callback recorded `stage=NEED_PERMISSION` and
`ownerActive=1`, proving synchronous reentry before the requesting job completed.
After all observed install jobs finished, A remained NEED_PERMISSION, with one
started/completed verification and no installer call. The expected initial
verification plus one permission-return verification did not occur. The stale
selection control retains its original passing assertions.

F5's diagnostic also records a `ComposeNotIdleException` after the recomposer
failed. Its checker made two requests, but the intended popup assertion was not
reached successfully. Neither the timeout nor the window-thread exception
authorizes a new F5 production fix.

## Cause and minimal candidate

`install(afterPermission=true)` consumed `permissionGeneration` before checking
whether `installJob` had completed. A synchronous callback therefore erased its
owned return and then hit the active-job guard. A duplicate could not recover
the erased return.

The candidate retains the permission token while the requesting job is active.
One private generation marker deduplicates completion registration. The job's
completion callback reacquires the ownership lock, clears that marker and calls
the existing permission-return entry only when both the current generation and
outstanding token still belong to the same request. The normal entry then
consumes the token and performs the existing SHA recheck and installer checks.
Further returns have no token and cannot create another continuation.

Selection, cancellation and download retry clear the queued marker before
canceling the old job. Old completion callbacks cannot claim a new selection.
The existing stale-request rejection, ownership checks, lazy job publication,
normal repeated-install guard and synchronous permission-launch failure path
remain in place.

Production changes are confined to `InAppUpdates.kt`'s private continuation
marker, invalidation and permission-return entry. The complete download body
and the install body after owner selection are byte-identical to the base:
release/URL/redirect validation, SHA promotion/recheck, permission checks,
installer adapter and service code retain their existing behavior. F5
production, F7, versions, backend, skills, manifests, dependencies and workflows
are unchanged. Regression assertions are unchanged.

## Local validation and remaining work

All executed checks below passed without an Android build or device run:

- `python3 -B android/tools/test_pr18_theme_regressions.py`: seven static tests.
- `python3 -B android/tools/verify_bite_visuals.py` and
  `python3 -B android/tools/verify_p6_visuals.py`: source/palette/protected-file guards.
- Inline Python source guards: retained token until completion, duplicate and
  generation gates, invalidation ordering, byte-identical download/install body,
  and unchanged Android regression tests for the F6 candidate.
- Inline probe-validator unit tests: 17 cases covering GREEN, expected RED,
  the actual mixed-error artifact, unrelated failures, stale-control failure,
  missing/duplicate/unrelated/skipped cases, bad counters and malformed/absent XML.
  The validator itself is unchanged.
- `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'`: 18 existing
  APK metadata/release-install tests.
- `/tmp/dining-p5-state/actionlint` 1.7.12 against all four existing workflows.
- `git diff --check`.

This is an F6 candidate backed by actual RED, not an Android GREEN claim.
The separate F5 fixture scheduling correction must allow the parent to observe
its intended popup assertion before any further F5 production edit. The parent
will push the exact new probe head and rerun the four methods. No remote writes
or local Android build were performed here.
