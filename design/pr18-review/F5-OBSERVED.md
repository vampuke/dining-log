# F5 observed launch acknowledgement candidate

Worktree: `/tmp/dining-pr18-f5-launch`, branch `fix/pr18-f5-launch`, starting commit `37ac1eb71848cd6c31a79e3d2118d2d2d36b24c0`.

## Actual RED read before production edits

Actions run `42360683696` tested `02d5ff9d26231b1d06eb0f069c4a61e93343fe2e`. The 109 extracted failures in `/tmp/dining-release-20261107/verified-full-stacks-42360683696.json` match the full device reports: 51 on API 35 and 58 on API 26. Filtering by `Pr18StartupTest` and the actual `f5` method name yields 24 entries, four style/dark combinations per method per API.

| Actual method in `Pr18StartupTest` | API 35 | API 26 |
| --- | --- | --- |
| `f5DismissRotateAndRecreateRetainsTheLaunchAcknowledgement` | 4 assertions: popup expected 0, actual 1; test line 100 | Same 4 assertions |
| `f5DismissActualSystemFontChangeRetainsTheLaunchAcknowledgementOnLogin` | 4 assertions: popup expected 0, actual 1; test line 114 | 4 earlier `IllegalArgumentException: Required value was null` failures in `Pr18Evidence.save:344`, called by `awaitPopup:43` at test line 108 |
| `f5InitiallyOptedOutLaunchCanEnableCheckingWithoutAnInterruptingStartupPopup` | 4 assertions: popup expected 0, actual 1; test line 145 | Same 4 assertions |

The popup assertions originate at `assertNoPopup:64`. These are 20 observed popup assertions and four API 26 screenshot failures, not 24 demonstrated popup assertions. In particular, API 26 real-font behavior remains unmeasured by this run.

Reports for each API are under `/tmp/dining-release-20261107/ci-evidence-42360683696/android-auth-api-{35,26}/reports/androidTests/connected/debug/top.alanvampuke.dininglog.product.Pr18StartupTest.html`. Their `outputs/p4-screenshots/p4-screenshots/pr18-measurements-api{35,26}.txt` confirms popup count 1 with only one version request for the assertion failures; the API 35 font cases record actual resource font scale 2.0. Rotation/recreation reuses the same checker. The late-opt-in test starts with a real persisted false preference, issues zero startup requests, enables checking through Settings, then receives the first background response.

`MainActivityContent.kt`, `core/Updates.kt`, and `Pr18StartupTest.kt` are byte-identical in the tested checkpoint and starting commit (Git blobs respectively `d68813779b94b20cf5ad11c32566f242fcaf5a14`, `a483a6b072dee30c0d7b2e4402f91d18ec724c00`, `627b0a5c096d51e4db6f1c302307262e2e1337fc`). The intervening F1/F2/F4 static fixes are already present in this worktree; this candidate does not reopen them.

## Cause and minimal plan

1. `launchUpdateShown` uses plain `remember`, so a new Activity composition loses the acknowledgement and sees the singleton checker's unchanged automatic release again. Save the acknowledgement at its stable composition position with `rememberSaveable`. Save dialog visibility too, so recreation preserves an open dialog as well as a dismissed one. Keep these states outside the authentication/theme branches and do not key them to changing preference or release values.
2. The current effect consumes every nonmanual release while enabled. The checker waits for DataStore correctly, but its initial `AppUpdateState.enabled=true` is only a placeholder, and every later false-to-true preference change also runs an automatic check. A launch initially opted out therefore gets an interrupting popup later. Expose a separate `startupRelease` field in the checker state, populated only by the first real preference's completed automatic attempt. First opt-out, failed/invalid/equal/older results, cancellation, and a request skipped behind a manual check leave it empty permanently for that checker. Later automatic/manual results still update the existing Settings result. Consume the startup offer once, even if the current manual/disabled state suppresses presentation.

The small Core change is necessary because the shared composition receives `ThemePreferences`, not `UpdatePreferences`, and the current update state does not identify the first stored preference or first completed attempt. Inferring completion by observing `checking=true` is unreliable: the pinned Compose `produceState` and StateFlow can conflate intermediate values, including a fast no-update/failure completion before composition starts. Freeze the first eligible release in the producer, atomically with its successful result in the existing StateFlow; there is no second stream whose publication can lag completion. The new field defaults to null and is retained by subsequent state copies. Request locking, cancellation, manual provenance, and release validation stay intact.

The lockfiles pin runtime/runtime-saveable **1.8.2** under BOM `2025.05.01`. The matching Google Maven source jars were read in memory: `RememberSaveable.kt:35–37,84–88` restores through the saved-state registry; `:56–58` says restored values do not validate old inputs; `:184–193` unregisters disposed providers and only resets live values when inputs change. Boolean `mutableStateOf` uses a supported standard policy. `ProduceState.kt:64–66` explicitly permits observers to see only the latest rapid update. A fresh composition with fresh launch owners can prompt again; acknowledgement is not persisted in DataStore or attached to an account.

Only shared composition state and the initial-check result seam will change in production. Add focused Core test cases for delayed stored opt-out, frozen positive/negative startup outcomes, cancellation, and manual contention; keep all existing tests and assertions intact. Installer/F6, BiteComponents/F3, authentication/session/cryptography, backend, versions, dependencies, workflows, release policy and signing are outside this change.

## Verification boundary

Run Python/source guards and `git diff --check` only. The existing `verify_bite_visuals.py` uses `p7_baseline.json` for tags and `p8_protected_hashes.json` for backend/contracts hashes; it does not enforce the historical `p7.protectedHashes` Core entries. No baseline/hash refresh is required for this scope.

The candidate contains two production files, this record, and `android/core/src/test/kotlin/top/alanvampuke/dininglog/core/StartupUpdatesTest.kt`. Its five new tests cover delayed real opt-out, a frozen positive startup offer across subsequent automatic/manual results, failed/invalid/equal/older startup outcomes followed by a newer automatic result, initial-check cancellation, and a manual request owning the lock during preference restoration. They are test source for the parent's CI, not locally executed Kotlin results.

| Checks actually run locally | Result |
| --- | --- |
| `python3 -B -` — saved-state-only source comparison after the first behavior change | Exit 0; only the two Boolean declarations and import changed at that step |
| `python3 -B android/tools/test_pr18_theme_regressions.py` | Exit 0; 7 tests passed; existing F1/F2/F4 fixes retained |
| `python3 -B android/tools/verify_p6_visuals.py` | Exit 0; delegates to BITE, retaining native/vector/tag/contrast and protected-file checks |
| `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'` | Exit 0; 18 tests passed with synthetic release/ADB fixtures |
| `python3 -B -` — scope, production-diff reconstruction, Kotlin delimiters and protected hashes | Exit 0; 711 other tracked files, all 30 existing test files and all 80 enforced protected hashes unchanged; three Kotlin files have balanced delimiters |
| `git diff --check` | Exit 0 |

The first inline delimiter audit exited 1 because its tokenizer misread apostrophes in comments. Correcting the audit to tokenize comments and literals together made it pass; no production change was made between those attempts. Source comparisons passed in both attempts. These audits do not compile Kotlin or exercise Compose saved-state restoration.

No local Gradle/JVM/emulator build or Android GREEN is claimed. The parent must run the candidate in Actions on API 26/35, verify the three F5 paths (including an actual font change), F5 fresh-launch/theme cases, asynchronous preference restoration, and F8 startup/manual positive and negative paths. The existing API 26 screenshot failure must be resolved or measured by its owner without weakening the F5 assertions.
