# PR18 tests-first checkpoint

Stopped for parent-run Android RED. This checkpoint adds regressions, acceptance coverage and narrow production test seams. No numbered behavior fix or version bump is included. Android code has not been compiled or executed on this VPS.

Branch: `fix/pr18-review-0.8.3`. Execution HEAD before this checkpoint commit: `8b8decf7c39ed67858ef99c760e1ca75e273f013`. Immutable master base: `45c7f65823042deb76171732dcb2a000f8620e65`. The checkpoint SHA is the commit containing this document and is returned to the parent separately.

The stop condition comes from `/tmp/dining-release-20261107/fix-pr18.md`: “For Android-only RED execution, prepare a TEST-ONLY checkpoint commit and STOP for parent CI”. No remote write, Gradle, JVM, emulator, build, dependency installation, Jest/Vitest or Docker command was run.

## Executed lightweight evidence

All commands ran from `/tmp/dining-pr18-fix-0.8.3`. Exact output and exit codes are in [execution results](evidence/lightweight/results.json) and the corresponding logs.

| Command | Exit | Observed result |
| --- | ---: | --- |
| `python3 -B android/tools/test_pr18_theme_regressions.py` | 1 | RED: 7 tests, 27 failures; actual F1/F2/F4 regressions plus one prospective F1/F4 guard |
| `python3 -B android/tools/verify_bite_visuals.py` | 1 | RED: 18 additional InkPaper role failures; every retained classic/native/vector/tag/protected-hash assertion runs first |
| `python3 -B android/tools/verify_p6_visuals.py` | 1 | RED: delegates to the extended BITE verifier after its retained checks; same 18 InkPaper role failures |
| `node --test .github/scripts/release-guards.test.mjs` | 0 | 7 passed |
| `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'` | 0 | 18 passed |
| `git diff --check` | 0 | Passed |
| `git diff --cached --check` | 0 | Passed after encoding the unittest receipt; the initial artifact-only whitespace failure is retained in execution results |
| `python3 -B design/pr18-review/evidence/audit-checkpoint.py` | 0 | Source neutrality, original-file allowlist, protected hashes and Kotlin delimiter audit passed; this is not compilation |

Source-bound measurements are retained in [component-roles.json](evidence/lightweight/component-roles.json). The observed dark button ratios are 3.4471 normally and 3.0456 focused/pressed. Placeholders measure 3.8338 light and 4.3891 dark. Unchecked switch thumb/border-to-track ratios are 1.5737 light and 1.6112 dark; adjoining-surface ratios also fail. All three InkPaper rating renderers still use `>=80`, producing zero high-emphasis values in the stored 10..50 range. The classic pairs remain passing controls.

The raw unittest progress output contains trailing spaces. [theme-regressions.json](evidence/lightweight/theme-regressions.json) preserves that exact output in its `output` field; decoding it reproduces the original UTF-8 bytes. The initial full-index whitespace rejection is also preserved as JSON. Source files and assertions were not changed to resolve this artifact-formatting issue.

The additional dark `accent/paper` measurement is 4.2290. It is a prospective acceptance guard for the default RatingEditor frame after F4 makes 40 reachable. The current 80 branch is unreachable, so this measurement is not presented as an observed currently rendered high-score failure.

P6's overall RED is expected at this checkpoint because it invokes the extended verifier. The independent API26/API35 instrumentation jobs in the existing workflow build and run their own connected suite; the parent still needs their real Android failures. A static verifier failure or compilation error does not establish Android behavior RED.

## Android failures expected to establish RED

All methods below are pending actual parent CI execution. Parameterized component/startup cases explicitly run `INK_PAPER` and `QUIET_GRID`, light and dark. Existing tests remain intact.

| Finding | Exact class and method | Expected original behavior failure |
| --- | --- | --- |
| F1 | `Pr18ComponentTest.f1RenderedButtonAndPlaceholderUseAccessibleForegroundsAndPreserveCallbacks` | InkPaper light placeholder and dark enabled button text fail 4.5:1 after recording actual foregrounds and callback deltas |
| F2 | `Pr18ComponentTest.f2ActualSettingsUncheckedSwitchThumbAndBorderContrastWithTrackAndSurface` | Pixels from the real enabled unchecked Settings switch fail 3:1 in both InkPaper palettes |
| F3 | `Pr18ComponentTest.f3InputRuleIsPaintedAboveTheContainerBeforeSupportingTextForEveryFieldState` | The empty InkPaper field has no visible input-bottom rule; supporting cases also require the rule before support/error text |
| F4 | `Pr18ComponentTest.f4ScoreVerdictAndFillEmphasize40And50WithNeutral39AndNull` | At 40 the recorded Score/verdict/fill remain neutral instead of the required accent; 39/40/50/null are covered |
| F5 | `Pr18StartupTest.f5DismissRotateAndRecreateRetainsTheLaunchAcknowledgement` | Dismissed automatic popup reopens after rotation plus new Activity owner; request count remains one |
| F5 | `Pr18StartupTest.f5DismissActualSystemFontChangeRetainsTheLaunchAcknowledgementOnLogin` | Dismissed login startup popup reopens after a real system `font_scale=2.0` Activity recreation |
| F5 | `Pr18StartupTest.f5InitiallyOptedOutLaunchCanEnableCheckingWithoutAnInterruptingStartupPopup` | Late opt-in background result unexpectedly presents a startup popup |
| F6 | `Pr18InstallTransactionTest.f6DelayedAutomaticBDoesNotReplaceReadyAOrPreventInstall` | Automatic B replaces READY A and sweeps its SHA-verified APK; READY must remain installable |
| F6 | `Pr18InstallTransactionTest.f6DelayedAutomaticBDoesNotReplacePermissionTransactionAOnReturn` | B replaces NEED_PERMISSION A; permission return verifies the wrong/missing package and loses A |
| F6 | `Pr18InstallTransactionTest.f6DelayedAutomaticBDoesNotSweepInstallingAOrReceiveAsynchronousFailureFromA` | Held A verification resumes after B sweeps A's APK and marks B FAILED |
| F6 | `Pr18InstallTransactionTest.f6RepeatedInstallTapsOwnOneJobAndOnePermissionRequest` | Three install taps own three jobs/rechecks/permission launches instead of one |
| F6 | `Pr18InstallTransactionTest.f6RepeatedPermissionReturnInRecreatedOwnerOwnsOneJobAndInstallerIntent` | Repeated permission-return/install calls own duplicate jobs and installer invocations |
| F6 | `Pr18InstallTransactionTest.f6ExplicitCancelAndManualBSelectionCannotReceiveStaleAInstallerCompletion` | After explicit cancel and manual selection of B, held A completion changes B's IDLE state to FAILED; isolates asynchronous ownership from automatic-selection protection |

Retained positive controls include startup on login, no automatic APK download, disabled/failed/equal/older startup responses, manual opt-in/opt-out checks, dismissal through style/theme recomposition, fresh launch owners, cancel/retry/manual selection, explicit-unrated semantics, one-tenth steps and bounds, and classic rendering. New overflow and 24dp target negative controls must still detect genuine clipping/undersized layout. Harness errors, failed preconditions or absent runtime execution must be reported separately from behavior RED.

F7/F8 acceptance is prepared, not yet accepted: complete restaurant/search/dish/visit/history/detail/edit/settings/auth flows, update/password/conflict/apply/confirmation/operation dialogs, both styles and themes, 360dp width, both landscape rotations, actual IME geometry, and font branches 1.0/1.3/1.31/2.0. Actual resource/TextLayoutResult font density, palette ARGB, layout coordinates, callback deltas, reachability and screenshots are recorded by the tests. See the [evidence manifest](evidence/manifest.json) and [harvest instructions](evidence/README.md).

## Behavior-preserving seams and source audit

`MainActivity` delegates its existing composition to internal `MainActivityContent`. Ordinary `remember` launch/popup flags, READY auto-install, startup eligibility, auth/session operations, UI copy and manual flows remain unchanged. The automatic provenance argument is ignored. The original one-argument public `open` entry is retained and delegates to an internal overload.

`InAppUpdates` accepts instance-owned transport, installer and scope collaborators through an internal constructor. Its existing Hilt constructor supplies the same HTTP and Android installer operations. Version/URL/redirect validation, SHA promotion/recheck, permissions, orphan cleanup, incomplete busy guard and asynchronous state writes remain in the original paths. The tests control service scheduling and installer timing, while exercising real validation and actual fixture files. No trust exception, mutable global debug hook or exported component was added.

The data-module debug-only `Pr18PreferenceFile` opens independent actual DataStore files using its already locked dependency and internal production factory. The production preference factory is unchanged. Fresh-owner/reopened-file cases simulate new process-owned collaborators; they do not claim an OS process kill or actual Hilt MainActivity injection.

The audit compares the shared composition and delegated update bodies against the original PR18 source, preserving string contents and substituting only receiver/I/O delegation. It verifies 663 original repository files byte-for-byte, except the three listed modifications, including 22 legacy Android test files and all 80 protected backend/contracts files. Backend, contracts, migrations, core/data/feature production code, dependency declarations/locks, versions, resources, manifests, workflows, signing and release guards remain unchanged. All four reviewer reports are copied unchanged under [reviews](reviews/) with [byte hashes](evidence/review-hashes.json); the consolidated findings were checked against their actual source mechanisms.

## Changed source files

Modified existing files:

- `android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt`
- `android/app/src/main/kotlin/top/alanvampuke/dininglog/InAppUpdates.kt`
- `android/tools/verify_bite_visuals.py`

Added source files:

- `android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivityContent.kt`
- `android/app/src/main/kotlin/top/alanvampuke/dininglog/UpdateIo.kt`
- `android/data/src/debug/kotlin/top/alanvampuke/dininglog/data/Pr18PreferenceFile.kt`
- `android/tools/pr18_visual_roles.py`
- `android/tools/test_pr18_theme_regressions.py`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18Evidence.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18Fixtures.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18ComponentTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18StartupTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18InstallTransactionTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18PreferencesOrchestrationTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18ScreenAcceptanceTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18DialogAcceptanceTest.kt`

The remaining added files under `design/pr18-review/` are this checkpoint, the audit, immutable reviewer copies, source-bound measurement/command receipts and the expected Android evidence manifest/README. No Android PNG or runtime measurement has been fabricated or committed.

Next: the parent executes the existing full connected suite on this checkpoint for API26/API35 and supplies actual failing assertions, measurements and retained artifacts. Only after the corresponding real RED may behavior fixes begin. Fix/closure and the separate three-file 0.8.3 version bump remain pending. Existing PR CI run `42351574722` exercised classic rendering and is not InkPaper acceptance.
