# PR18 original failure diagnostic checkpoint

This workflow is a diagnostic feedback loop, never a release gate. No Android
source, fixture, assertion, original test, mandatory workflow, full-suite script,
backend, signing/version policy, or theme implementation changes here.
The failing source is bound to `be8aaf8d60aa5513227a102d4cdbfcb2377b778d`.
The complete acceptance contract remains 20 classes / 109 methods / 190 cases
per API, including the original 17 classes / 93 methods / 174 case floor.
The Python interface tests add no instrumentation cases.

## Review checkpoint and bounded command

After parent/source review, the parent can push the reviewed commit to the
explicit `verify/pr18-failure-diagnostics-20261108` branch. That push registers
and triggers `.github/workflows/pr18-failure-diagnostics.yml` without master,
PR, release, tag, or deploy writes. `workflow_dispatch` is available once the
workflow is registered. This implementation does not push or dispatch it.

Each matrix job runs just one original method, on API26 or API35. Parameterized
methods explicitly retain **all four** declared style/dark variants; methods
retain all their internal font rounds, orientations, controls, and assertions.
No package/tag/notClass filter or screen/focus exemption is used.

| Group | Original class and method | Expected cases per API |
| --- | --- | ---: |
| flow | Pr18ScreenAcceptanceTest.f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent | 4 |
| landscape | Pr18ScreenAcceptanceTest.f7EveryListAndSearchRemainReachableInBothLandscapeDirectionsAtAllFontBranches | 4 |
| password | Pr18DialogAcceptanceTest.f7PasswordDialogUsesRealFontAndKeepsInputCallbacksSaveAndSecurity | 4 |
| conflict | Pr18DialogAcceptanceTest.f7ConflictComparisonChoiceAndSaveRemainScrollableAndExplicitAtAllFonts | 4 |
| fold | Pr16UiTest.invalidFirstVisitDateIsRevealedAfterFoldingAndSavingAndStaysVisibleWhenFoldedAgain | 1 |

That is 17 diagnostic cases per API, 34 across 10 jobs, with at most two jobs
concurrent. Each Gradle command has a 25-minute deadline (an in-flight bounded
ADB operation can delay deadline handling by at most 25 seconds); the job budget is
40 minutes to leave time for setup and evidence sealing. Missing cases after a
timeout remain missing and invalidate execution; the budget is not a pass.

The Actions-only command is:

```sh
python3 -B .github/scripts/pr18-failure-diagnostics.py run \
  --group flow --api 26 --live "$PR18_DIAGNOSTIC_LIVE" --timeout-seconds 1500
```

The helper records and runs the exact `:app:connectedDebugAndroidTest` command
with a comma-separated `class#method[variant]` argument. Local Python checks:

```sh
python3 -B .github/scripts/test_pr18_failure_diagnostics.py
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B .github/scripts/pr18-failure-diagnostics.py plan --group flow --api 26
```

The plan command requires a clean committed source tree. Its inventory/hashes
are source binding, not evidence that Android cases executed.

## Evidence and collector boundary

The live directory is in runner temporary storage. Changed device files are
pulled individually into temporary files. A file is retained by atomic rename
only when its remote size/mtime before and after the pull match its local size.
PNG chunks, CRCs and final IEND are checked before retaining a complete PNG.
An incomplete pull never replaces a previously retained file. Disappearance
after UTP uninstalls app data never deletes the retained copy. The journal
records remote inventory size, each changed pull's duration/hash, failures, and
sample duration so bandwidth/timing claims can be measured.
Collection checks the command deadline between files. The final pull pass also
has a short budget; any remaining files are reported, not counted as retained.

A continuous real logcat stream retains existing per-case UID, measured font,
VM, navigation, geometry, callback, input and security receipts. WindowManager
and native input `dumpsys` samples carry command start/end times and raw exits.
These periodic samples do not promise to capture a millisecond transition or
fill in the unlogged third geometry snapshot. Only original test capture paths
produce images; the collector never captures a protected screen or turns a
protected probe receipt into screenshot success.

Gradle and logcat's raw process exits, timeout/signal/harness state, XML/HTML/UTP outputs,
app and test APKs with SHA256, and the source plan are retained. Result validation
requires the exact selected class/method/variant IDs once each, suite-counter
agreement and no skipped tests. It retains failed/error/skipped/missing counts,
duplicates, unexpected cases, raw exits and full failure XML. Missing reports
cannot become RED execution or GREEN. Failures retain nonzero workflow steps.

**C2 — continuous logcat lifecycle.** Status checks cover the running test, test
completion, final collection and the boundary immediately before the first owned
teardown signal. Early exit, including exit 0, invalidates collection even when
Gradle and every selected XML case pass. `exit.json` retains `raw_logcat_exit`,
timestamped lifecycle checks and the premature exit alongside the raw Gradle
exit and termination reason. A TERM/KILL requested for owned teardown is recorded
separately and does not itself mean premature loss. `finish` requires a healthy
lifecycle receipt as well as the existing exact case/XML/APK/source checks.

The last status inquiry and the first signal have a scheduling window. A child
can exit naturally in that window, remain a zombie, and still allow `killpg`
to succeed. Continuous logcat health therefore requires the owned child's real
wait result to show death by a teardown signal actually sent by the controller,
with the birth identity anchored until group cleanup completes. Signal delivery
or a nonempty signal list alone is insufficient. Natural exit 0/7, a handler
returning exit 0, or an unproven exit fails closed and cannot become
`COMPLETE_PASS`; raw exits and partial evidence remain retained. This proof is
specific to continuous logcat health: an already completed body may retain raw
exit 0 while its owned descendants still require cleanup.

Only after the collector, logcat and Gradle client stop does the final step copy
reports/APKs and seal a SHA256-manifested tar archive. A live writer lock or
missing stopped-writer receipt prevents sealing. Upload reads only that immutable
archive and its digest. Setup failures retain missing counts/absent raw exits. An
interrupted run without a stopped-writer receipt retains only a small immutable
invalid-attempt receipt; it does not archive potentially live evidence. A hard runner kill that prevents shutdown is invalid
diagnostics, not success; the helper cannot guarantee artifact retention after
the runner itself is destroyed.

**C1 — owned group cleanup.** Each new Linux session/group records its leader's
PID, PGID, SID and `/proc` birth ticks. `waitid(WNOWAIT)` retains an exited
leader's identity anchor until cleanup; the leader exiting never proves that
its descendants stopped. Cleanup checks the group's members, allows only
non-writing zombie/dead states, and uses bounded TERM (5 seconds), KILL
(5 seconds) and leader wait (5 seconds). Before any group signal it rechecks
the captured leader identity. No name-based or unrelated-group signal is used.
An unexpectedly reaped/reused leader, failed inquiry or surviving member leaves
`writers_stopped=false` and a nonzero job. `cleanup.json` retains identities,
member states, signals, raw exits, elapsed time and errors. Without verified
cleanup, `finish` copies no build outputs and seals no live evidence; its
`unsealed-attempt.json` retains the exit/lifecycle and cleanup failure receipts.

**C3 — cancellation propagation.** Interface validation additionally reproduced
SIGINT swallowed by inventory, pull and native-command exception handlers, and
SIGINT reduced to exit 1 during cleanup. Interruptions now reach the controller's
signal handling or remain recorded during cleanup: SIGINT/SIGTERM retain
130/143, timeout retains 124. Ordinary unavailable-device/file samples remain
recorded collection errors. Case identities and XML acceptance remain unchanged.

The 22 existing diagnostic test methods/assertions are retained, with 17 added
Linux lifecycle/interface methods. C1/C2/C3 assertion RED then GREEN logs and
fixtures are retained outside the product tree at
`/tmp/dining-publish-20261108/android-diag-cleanup-fix-r1-evidence`, labelled
`PURE_SUBPROCESS_INTERFACE_FIXTURE_NOT_ANDROID`. These checks establish controller
interfaces only. The earlier review's initial logcat fixture setup error and
initial archive-audit error remain excluded from RED evidence. No Android build,
device case, font/window/geometry/focus/callback behavior or upload delivery was
executed here; fresh independent review of the committed candidate is still due.

## Observed facts and remaining hypotheses

Run 42444656454 on the original SHA has seven real failures per API, with only
129/190 API26 and 119/190 API35 cases completed. Both are incomplete FAIL;
neither had completed instrumentation XML/HTML. Build/JVM success is not device
acceptance. The old probe selects only Pr18ReviewRegressionTest and cannot be
RED on these original failure methods.

The original API26 measurement text binds flow case UID
`51f768de-178b-4902-8d21-319b6b71679f` to measured actualFont 2.0. Lines
64320-64326 show the completed font-1 flow on settings, then a different Activity,
window and fixture VM at font 2. The expected and current new VM match and its
list kind is RESTAURANT, while the exposed destination is still screen:settings.
This falsifies the narrow claim that only an old VM object caused that mismatch.
ProductRoot remembers navigation/tab state, and the fixture clears the VM store
before a font recreation. Saved navigation/handle ownership remains a hypothesis;
the receipts do not identify which restore mechanism retained the settings tab.
Production navigation must not be changed to mask a fixture root expectation.

Landscape case receipts show both changing native overlay rectangles and
`paint-order-unknown` candidates. The failed third snapshot is not logged, so
its exact difference is unknown. No-exposed-band failures at measured font 1.31
include sibling row candidates covering the target's X band. This does not prove
an actual touch blocker, nor justify relaxing overlay, focus or geometry guards.
API35 dialog focus assertions have no retained device capture artifact; compare
the working API26 cases against the new native/API35 receipts before changing
focus sequencing. API26 password-save input receipts for case
`eef4c7a0-cbec-469b-bd37-5f045dce9a12` show focused native owners at measured
fonts 1.0, 1.3, 1.31 and 2.0 (lines 14711, 15195, 15679, 16137).
Images describe layout only.

The production fold branch uses a direct `if (firstVisitExpanded)`; the original
test already advances a frame and waits for idle before its failing assertion.
An animation-only explanation is not established. Pr16UiTest is byte identical
between fd9294181b5fef8bb67cb3f2c337711f36e45beb and the failing SHA (SHA256
b4b07ca49c31e40b32aaa0d1d10b8ba1e2712a6542200332845cd801e418db3b).
The working API26 fold receipt changes its observed label from expanded to
collapsed; its focused text field's owner shifts vertically as the IME hides.
The missing API35 device receipt is needed to distinguish click/state behavior.

The full-suite collector unconditionally re-pulls the growing directory after
each pass plus a one-second sleep. Its cancelled API35 upload reports a missing
PNG 17ms after upload path validation; API26 retained 2070 files in a 123111352-byte
archive. There are no per-pull byte/timing receipts in that run. The repeat-copy
mechanism is source-proven, but its contribution to runtime and the missing-file
race are unquantified. The full-suite collector stays unchanged at this checkpoint.
The extracted device directory currently contains 2070 files / 166486082 bytes,
including the 33830093-byte measurement text. These are retained file sizes, not
ADB transfer measurements. The supplied original ZIP path is absent in this
workspace; its byte/hash values above come from the supplied manifest receipt.

## Pinned tooling checked

Runner 1.6.2 sources were read from
https://dl.google.com/dl/android/maven2/androidx/test/runner/1.6.2/runner-1.6.2-sources.jar
(SHA256 44ae9da39c4bdf12f010a83081322f07e1ca48cf48bdf441affffc2233d55e7c).
ClassesArgTokenizer explicitly accepts named parameterized selectors.
TestRequestBuilder.MethodFilter strips only numeric suffixes; matching these
named methods requires the exact names used here.

Compose BOM 2025.05.01 pins UI 1.8.2 and Material3 1.3.2. Pinned AndroidDialog
creates/shows its native dialog in DisposableEffect; it does not establish that
Compose idle means native focus is settled. Material3 BasicAlertDialog delegates
to that Dialog with its DialogProperties (AlertDialog.kt:147). There is no evidence authorizing
focus-guard changes. Android 8 toybox stat source supports `%s %Y %n`:
https://android.googlesource.com/platform/external/toybox/+/android-8.0.0_r1/toys/other/stat.c
(decoded SHA256 c4302dd9c70d84dddf537111871734bca89df74ae734056671741ce92d9be94b).
`adb help` confirms ordinary pull; `--sync` is a push option and is never used.
Actions records the actual adb and device tool help/version in each artifact.

Actual device runs are unrun at this checkpoint. Parent probe execution, a NEW
Codex full-source independent review, complete 190+ cases on both APIs and all
other retained release gates remain mandatory. This context does not sign off
its own implementation or authorize release.
